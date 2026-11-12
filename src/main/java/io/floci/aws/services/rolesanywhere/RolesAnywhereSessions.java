package io.floci.aws.services.rolesanywhere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.auth.SigV4Canonicalization;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IAM Roles Anywhere's {@code CreateSession}: a request signed with an X.509 certificate's private key
 * ({@code AWS4-X509-RSA-SHA256} or {@code AWS4-X509-ECDSA-SHA256}) is exchanged for temporary credentials for a role.
 * The certificate must chain to an enabled trust anchor, be in its validity period, not be revoked by an enabled CRL
 * of that anchor, and have signed the request; the profile must be enabled and list the role, and the role must trust
 * Roles Anywhere. Every attempt is recorded under the certificate's subject.
 */
@ApplicationScoped
public class RolesAnywhereSessions {

    private static final Pattern AUTH = Pattern.compile(
            "(AWS4-X509-(?:RSA|ECDSA)-SHA256)\\s+Credential=([^/,\\s]+)/(\\d{8})/([^/,\\s]+)/rolesanywhere/aws4_request,"
                    + "\\s*SignedHeaders=([^,\\s]+),\\s*Signature=([0-9a-fA-F]+)");

    private final RolesAnywhereService service;
    private final LambdaExecutionRoleCredentials credentials;
    private final ObjectMapper mapper;

    @Inject
    public RolesAnywhereSessions(RolesAnywhereService service, LambdaExecutionRoleCredentials credentials) {
        this.service = service;
        this.credentials = credentials;
        this.mapper = service.mapper();
    }

    private static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    private static X509Certificate decode(String base64) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw RolesAnywhereService.validation("X-Amz-X509 isn't a base64 DER certificate.");
        }
    }

    public ObjectNode createSession(String region, HttpHeaders headers, UriInfo uri, byte[] body) {
        String authorization = headers.getHeaderString("Authorization");
        Matcher auth = authorization == null ? null : AUTH.matcher(authorization);
        if (auth == null || !auth.find()) {
            throw denied("CreateSession needs an AWS4-X509 signature.");
        }
        String leafHeader = headers.getHeaderString("X-Amz-X509");
        if (leafHeader == null) {
            throw RolesAnywhereService.validation("CreateSession needs the X-Amz-X509 header.");
        }
        X509Certificate leaf = decode(leafHeader);
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);
        String chainHeader = headers.getHeaderString("X-Amz-X509-Chain");
        if (chainHeader != null && !chainHeader.isBlank()) {
            for (String part : chainHeader.split(",")) {
                chain.add(decode(part));
            }
        }
        JsonNode in;
        try {
            in = mapper.readTree(body == null || body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body);
        } catch (IOException e) {
            throw RolesAnywhereService.validation("The request body isn't JSON.");
        }
        try {
            return exchange(region, headers, uri, body, auth, leaf, chain, in);
        } catch (AwsException e) {
            service.recordSubject(region, leaf, true);
            throw e;
        }
    }

    private ObjectNode exchange(String region, HttpHeaders headers, UriInfo uri, byte[] body, Matcher auth,
                                X509Certificate leaf, List<X509Certificate> chain, JsonNode in) {
        // The trust anchor, and the certificate's path to it.
        String anchorArn = in.path("trustAnchorArn").asText("");
        ObjectNode anchor = byArn(anchorArn, RolesAnywhereService.ANCHOR, "trust anchor");
        if (!anchor.path("enabled").asBoolean()) {
            throw denied("The trust anchor " + anchorArn + " is disabled.");
        }
        if (leaf.getBasicConstraints() >= 0) {
            throw denied("The signing certificate is a CA certificate; Roles Anywhere takes end-entity certificates.");
        }
        try {
            leaf.checkValidity();
        } catch (GeneralSecurityException e) {
            throw denied("The certificate isn't valid now: " + e.getMessage());
        }
        List<X509Certificate> anchorCertificates = service.anchorCertificates(region, anchor);
        Set<TrustAnchor> roots = new HashSet<>();
        anchorCertificates.forEach(certificate -> roots.add(new TrustAnchor(certificate, null)));
        try {
            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain);
            PKIXParameters parameters = new PKIXParameters(roots);
            parameters.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, parameters);
        } catch (GeneralSecurityException e) {
            throw denied("The certificate doesn't chain to the trust anchor: " + e.getMessage());
        }
        // Revocation, by the anchor's enabled CRLs.
        for (ObjectNode crl : service.list(region, RolesAnywhereService.CRL)) {
            if (!anchorArn.equals(crl.path("trustAnchorArn").asText()) || !crl.path("enabled").asBoolean()) {
                continue;
            }
            X509CRL list = RolesAnywhereService.crl(Base64.getDecoder().decode(crl.path("crlData").asText()));
            for (X509Certificate certificate : chain) {
                if (list.getIssuerX500Principal().equals(certificate.getIssuerX500Principal())
                        && list.isRevoked(certificate)) {
                    throw denied("The certificate " + certificate.getSerialNumber() + " is revoked by the CRL "
                            + crl.path("name").asText() + ".");
                }
            }
        }
        // The request signature, by the certificate's key.
        if (!auth.group(2).equals(leaf.getSerialNumber().toString())) {
            throw denied("The signature's credential names a serial number that isn't the certificate's.");
        }
        String signedHeaders = auth.group(5);
        String amzDate = headers.getHeaderString("X-Amz-Date");
        if (amzDate == null || !amzDate.startsWith(auth.group(3))) {
            throw denied("X-Amz-Date must be signed and match the credential scope's date.");
        }
        String canonicalHeaders = SigV4Canonicalization.canonicalHeaders(signedHeaders, name -> {
            List<String> values = headers.getRequestHeader(name);
            return values == null ? null : String.join(",", values);
        });
        String payloadHash = Digests.sha256Hex(body == null ? new byte[0] : body);
        String scope = auth.group(3) + "/" + auth.group(4) + "/rolesanywhere/aws4_request";
        boolean verified = false;
        for (String canonicalUri : SigV4Canonicalization.canonicalUriCandidates(uri.getRequestUri().getRawPath()
                .replaceFirst("^" + Pattern.quote(RolesAnywhereRouteFilter.PREFIX), ""))) {
            String canonicalRequest = "POST\n" + canonicalUri + "\n"
                    + SigV4Canonicalization.canonicalQueryString(uri.getRequestUri().getRawQuery(), false) + "\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
            String stringToSign = auth.group(1) + "\n" + amzDate + "\n" + scope + "\n"
                    + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            try {
                Signature signature = Signature.getInstance(auth.group(1).contains("ECDSA") ? "SHA256withECDSA"
                        : "SHA256withRSA");
                signature.initVerify(leaf.getPublicKey());
                signature.update(stringToSign.getBytes(StandardCharsets.UTF_8));
                verified |= signature.verify(HexFormat.of().parseHex(auth.group(6).toLowerCase(Locale.ROOT)));
            } catch (GeneralSecurityException e) {
                verified |= false;
            }
        }
        if (!verified) {
            throw denied("The request signature doesn't match the certificate's key.");
        }
        // The profile and the role.
        ObjectNode profile = byArn(in.path("profileArn").asText(""), RolesAnywhereService.PROFILE, "profile");
        if (!profile.path("enabled").asBoolean()) {
            throw denied("The profile is disabled.");
        }
        String roleArn = in.path("roleArn").asText("");
        boolean listed = false;
        for (JsonNode allowed : profile.path("roleArns")) {
            listed |= allowed.asText().equals(roleArn);
        }
        if (!listed) {
            throw denied("The profile doesn't allow the role " + roleArn + ".");
        }
        if (!service.roleTrustsRolesAnywhere(roleArn)) {
            throw denied("The role " + roleArn + " doesn't exist or doesn't trust Roles Anywhere.");
        }
        int duration = in.path("durationSeconds").asInt(profile.path("durationSeconds").asInt(3600));
        if (duration < 900 || duration > profile.path("durationSeconds").asInt(3600)) {
            throw RolesAnywhereService.validation("durationSeconds must be from 900 to the profile's "
                    + profile.path("durationSeconds").asInt(3600) + ".");
        }
        String commonName = cn(leaf);
        String sessionName = in.hasNonNull("roleSessionName") ? in.get("roleSessionName").asText()
                : leaf.getSerialNumber().toString(16);
        if (in.hasNonNull("roleSessionName") && !profile.path("acceptRoleSessionName").asBoolean()) {
            throw denied("The profile doesn't accept a roleSessionName.");
        }
        SessionCreds creds = credentials.forRole(roleArn, service.account(), sessionName)
                .orElseThrow(() -> denied("The role " + roleArn + " can't be assumed."));
        AwsArnUtils.Arn role = AwsArnUtils.parse(roleArn);
        String roleName = role.resource().substring(role.resource().lastIndexOf('/') + 1);
        ObjectNode out = mapper.createObjectNode();
        ObjectNode set = out.putArray("credentialSet").addObject();
        set.putObject("assumedRoleUser").put("arn", AwsArnUtils.Arn.global(role.partition(), "sts", role.accountId(),
                "assumed-role/" + roleName + "/" + sessionName).toString()).put("assumedRoleId", "AROA" + roleName
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + ":" + sessionName);
        set.putObject("credentials").put("accessKeyId", creds.accessKeyId())
                .put("secretAccessKey", creds.secretAccessKey()).put("sessionToken", creds.sessionToken())
                .put("expiration", Instant.now().plusSeconds(duration).toString());
        set.put("packedPolicySize", 0).put("roleArn", roleArn).put("sourceIdentity", "CN=" + commonName);
        ObjectNode subject = service.recordSubject(region, leaf, false);
        out.put("subjectArn", subject.get("subjectArn").asText());
        return out;
    }

    private static String cn(X509Certificate certificate) {
        Matcher matcher = Pattern.compile("(?:^|,)CN=([^,]+)").matcher(certificate.getSubjectX500Principal()
                .getName());
        return matcher.find() ? matcher.group(1) : certificate.getSerialNumber().toString();
    }

    private ObjectNode byArn(String arn, String kind, String label) {
        if (!AwsArnUtils.isArnFor(arn, "rolesanywhere")) {
            throw RolesAnywhereService.validation("The " + label + " ARN " + arn + " isn't valid.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        try {
            return service.get(parsed.region(), kind, parsed.resource().substring(parsed.resource().indexOf('/') + 1));
        } catch (AwsException e) {
            throw denied("The " + label + " " + arn + " doesn't exist.");
        }
    }
}

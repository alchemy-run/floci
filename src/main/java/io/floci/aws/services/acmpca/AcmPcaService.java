package io.floci.aws.services.acmpca;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Private Certificate Authority. A CA is created with its own key pair and CSR, and becomes
 * {@code ACTIVE} once a certificate for its key is imported: a root CA's own self-signed
 * certificate (issued from its CSR with the {@code RootCACertificate/V1} template) or a subordinate
 * CA's certificate signed by its parent, with the chain. An active CA signs certificates from CSRs
 * with AWS's templates and revokes them, publishing a CRL to S3 when CRLs are enabled, and writes
 * audit reports of what it issued and revoked to S3. Issuance, revocation, CRL generation and audit
 * reports are announced on EventBridge as {@code aws.acm-pca} events. A deleted CA stays
 * {@code DELETED} and restorable for its permanent deletion window.
 */
@ApplicationScoped
public class AcmPcaService implements Resettable {
    static final long CREATING_MILLIS = 1_000;
    static final long AUDIT_CREATING_MILLIS = 1_000;
    private static final Set<String> CA_TYPES = Set.of("ROOT", "SUBORDINATE");
    private static final Set<String> USAGE_MODES = Set.of("GENERAL_PURPOSE", "SHORT_LIVED_CERTIFICATE");
    private static final Set<String> PERMISSION_ACTIONS = Set.of("IssueCertificate", "GetCertificate",
            "ListPermissions");
    private static final String ACM_PRINCIPAL = "acm.amazonaws.com"; // partition-literal: the service principal name AWS uses in every partition
    private static final String EVENT_SOURCE = "aws.acm-pca";
    private static final long SHORT_LIVED_MAX_DAYS = 7;
    private static final int MAX_TAGS = 50;
    private static final DateTimeFormatter END_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AccountAwareStorageBackend<ObjectNode> authorities;
    private final AccountAwareStorageBackend<ObjectNode> certificates;
    private final AccountAwareStorageBackend<ObjectNode> auditReports;
    private final Instance<S3Service> s3;
    private final Instance<EventBridgeService> eventBridge;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public AcmPcaService(StorageFactory storageFactory, Instance<S3Service> s3, Instance<EventBridgeService> eventBridge,
                         RegionResolver regionResolver, ObjectMapper mapper) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.authorities = storageFactory.create("acm-pca", "acm-pca-authorities.json", type);
        this.certificates = storageFactory.create("acm-pca", "acm-pca-certificates.json", type);
        this.auditReports = storageFactory.create("acm-pca", "acm-pca-audit-reports.json", type);
        this.s3 = s3;
        this.eventBridge = eventBridge;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    // ── Certificate authorities ────────────────────────────────────────────

    public synchronized ObjectNode create(JsonNode in, String region) {
        String token = in.path("IdempotencyToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : authorities.scan(k -> k.startsWith(region + "::"))) {
                if (token.equals(existing.path("idempotencyToken").asText(null))
                        && Instant.parse(existing.path("createdAt").asText()).isAfter(Instant.now().minusSeconds(300))) {
                    return mapper.createObjectNode().put("CertificateAuthorityArn", existing.path("Arn").asText());
                }
            }
        }
        JsonNode configuration = in.path("CertificateAuthorityConfiguration");
        String keyAlgorithm = configuration.path("KeyAlgorithm").asText();
        String signing = configuration.path("SigningAlgorithm").asText();
        AcmPcaCrypto.signatureAlgorithm(signing, keyAlgorithm);
        String type = in.path("CertificateAuthorityType").asText();
        if (!CA_TYPES.contains(type)) {
            throw invalidArgs("CertificateAuthorityType must be ROOT or SUBORDINATE.");
        }
        String usageMode = in.path("UsageMode").asText("GENERAL_PURPOSE");
        if (!USAGE_MODES.contains(usageMode)) {
            throw invalidArgs("UsageMode must be GENERAL_PURPOSE or SHORT_LIVED_CERTIFICATE.");
        }
        X500Name subject = AcmPcaCrypto.subject(configuration.path("Subject"));
        JsonNode revocation = in.path("RevocationConfiguration");
        checkRevocation(revocation, region);
        checkTags(in.path("Tags"));
        if (authorities.scan(k -> k.startsWith(region + "::")).stream()
                .filter(ca -> !"DELETED".equals(ca.path("Status").asText())).count() >= 200) {
            throw new AwsException("LimitExceededException", "You have reached the limit of 200 certificate "
                    + "authorities in this Region.", 400);
        }
        KeyPair keys = AcmPcaCrypto.generate(keyAlgorithm);
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("acm-pca", region, regionResolver.getAccountId(), "certificate-authority/" + id)
                .toString();
        Instant now = Instant.now();
        ObjectNode ca = mapper.createObjectNode();
        ca.put("Arn", arn);
        ca.put("OwnerAccount", regionResolver.getAccountId());
        ca.put("CreatedAt", seconds(now));
        ca.put("LastStateChangeAt", seconds(now));
        ca.put("Type", type);
        ca.put("Status", "CREATING");
        ca.set("CertificateAuthorityConfiguration", configuration.deepCopy());
        ca.set("RevocationConfiguration", revocation.isObject() ? normalizeRevocation(revocation)
                : defaultRevocation());
        ca.put("KeyStorageSecurityStandard", in.path("KeyStorageSecurityStandard")
                .asText("FIPS_140_2_LEVEL_3_OR_HIGHER"));
        ca.put("UsageMode", usageMode);
        ca.put("createdAt", now.toString());
        if (token != null) {
            ca.put("idempotencyToken", token);
        }
        ca.put("privateKey", AcmPcaCrypto.encodeKey(keys.getPrivate()));
        ca.put("publicKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        ca.put("csr", AcmPcaCrypto.csr(keys, subject, signing, keyAlgorithm,
                configuration.path("CsrExtensions")));
        ca.put("crlNumber", 0);
        ca.set("Tags", tagMap(in.path("Tags")));
        ca.putArray("Permissions");
        authorities.put(key(region, id), ca);
        return mapper.createObjectNode().put("CertificateAuthorityArn", arn);
    }

    /** The CA as the caller sees it now: settled out of CREATING, expired, or past its deletion window. */
    private ObjectNode settle(String storeKey, ObjectNode ca) {
        Instant now = Instant.now();
        String status = ca.path("Status").asText();
        boolean changed = false;
        if ("CREATING".equals(status) && now.toEpochMilli() - (long) (ca.path("CreatedAt").asDouble() * 1000)
                >= CREATING_MILLIS) {
            ca.put("Status", "PENDING_CERTIFICATE");
            ca.put("LastStateChangeAt", seconds(now));
            changed = true;
        }
        if (Set.of("ACTIVE", "DISABLED").contains(status) && ca.has("NotAfter")
                && now.getEpochSecond() > ca.path("NotAfter").asDouble()) {
            ca.put("Status", "EXPIRED");
            ca.put("LastStateChangeAt", seconds(now));
            changed = true;
        }
        if ("DELETED".equals(status) && ca.has("RestorableUntil")
                && now.getEpochSecond() > ca.path("RestorableUntil").asDouble()) {
            authorities.delete(storeKey);
            return null;
        }
        if (changed) {
            authorities.put(storeKey, ca);
        }
        return ca;
    }

    /** The CA an ARN names in the caller's account, as of now. */
    ObjectNode require(String arn) {
        Located located = locate(arn);
        return located.ca();
    }

    private record Located(String key, ObjectNode ca, String region) {}

    private Located locate(String arn) {
        if (arn == null || !AwsArnUtils.isArnFor(arn, "acm-pca")) {
            throw new AwsException("InvalidArnException", "The ARN " + arn + " is not a valid certificate authority "
                    + "ARN.", 400);
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.resource().startsWith("certificate-authority/") || parsed.resource().split("/").length != 2) {
            throw new AwsException("InvalidArnException", "The ARN " + arn + " is not a valid certificate authority "
                    + "ARN.", 400);
        }
        String id = parsed.resource().substring("certificate-authority/".length());
        String storeKey = key(parsed.region(), id);
        ObjectNode ca = parsed.accountId().equals(regionResolver.getAccountId())
                ? authorities.get(storeKey).orElse(null)
                : authorities.getForAccount(parsed.accountId(), storeKey).filter(this::sharedWithCaller).orElse(null);
        if (ca != null && parsed.accountId().equals(regionResolver.getAccountId())) {
            ca = settle(storeKey, ca);
        }
        if (ca == null) {
            throw new AwsException("ResourceNotFoundException", "Could not find certificate authority " + arn + ".",
                    400);
        }
        return new Located(storeKey, ca, parsed.region());
    }

    /** Whether another account's CA has a policy naming the caller's account as a principal. */
    private boolean sharedWithCaller(ObjectNode ca) {
        String policy = ca.path("Policy").asText("");
        String account = regionResolver.getAccountId();
        return !policy.isEmpty() && (policy.contains("\"" + account + "\"") || policy.contains(":" + account + ":"));
    }

    private void save(Located located, ObjectNode ca) {
        if (!AwsArnUtils.parse(ca.path("Arn").asText()).accountId().equals(regionResolver.getAccountId())) {
            authorities.putForAccount(AwsArnUtils.parse(ca.path("Arn").asText()).accountId(), located.key(), ca);
        } else {
            authorities.put(located.key(), ca);
        }
    }

    public ObjectNode describe(JsonNode in) {
        ObjectNode out = mapper.createObjectNode();
        out.set("CertificateAuthority", view(require(in.path("CertificateAuthorityArn").asText(null))));
        return out;
    }

    ObjectNode view(ObjectNode ca) {
        ObjectNode copy = mapper.createObjectNode();
        for (String field : List.of("Arn", "OwnerAccount", "CreatedAt", "LastStateChangeAt", "Type", "Serial",
                "Status", "NotBefore", "NotAfter", "FailureReason", "CertificateAuthorityConfiguration",
                "RevocationConfiguration", "RestorableUntil", "KeyStorageSecurityStandard", "UsageMode")) {
            if (ca.has(field)) {
                copy.set(field, ca.get(field).deepCopy());
            }
        }
        return copy;
    }

    public ObjectNode list(JsonNode in, String region) {
        String owner = in.path("ResourceOwner").asText("SELF");
        List<ObjectNode> all = new ArrayList<>();
        if ("OTHER_ACCOUNTS".equals(owner)) {
            String caller = regionResolver.getAccountId();
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                    : authorities.scanAllAccountEntries(k -> k.startsWith(region + "::"))) {
                if (!entry.accountId().equals(caller) && sharedWithCaller(entry.value())) {
                    all.add(entry.value());
                }
            }
        } else {
            for (String storeKey : authorities.keys()) {
                if (storeKey.startsWith(region + "::")) {
                    authorities.get(storeKey).map(ca -> settle(storeKey, ca)).ifPresent(all::add);
                }
            }
        }
        all.sort(Comparator.comparingDouble(ca -> ca.path("CreatedAt").asDouble()));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("CertificateAuthorities");
        page(all, in, out).forEach(ca -> list.add(view(ca)));
        return out;
    }

    public synchronized void update(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        String status = ca.path("Status").asText();
        if (in.hasNonNull("Status")) {
            String requested = in.get("Status").asText();
            boolean allowed = ("ACTIVE".equals(requested) && "DISABLED".equals(status))
                    || ("DISABLED".equals(requested) && "ACTIVE".equals(status))
                    || requested.equals(status);
            if (!Set.of("ACTIVE", "DISABLED").contains(requested) || !allowed) {
                throw new AwsException("InvalidStateException", "The certificate authority is " + status
                        + " and can't be made " + requested + ".", 400);
            }
            if (!requested.equals(status)) {
                ca.put("Status", requested);
                ca.put("LastStateChangeAt", seconds(Instant.now()));
            }
        }
        if (in.path("RevocationConfiguration").isObject()) {
            checkRevocation(in.get("RevocationConfiguration"), located.region());
            ca.set("RevocationConfiguration", normalizeRevocation(in.get("RevocationConfiguration")));
        }
        save(located, ca);
        if ("ACTIVE".equals(ca.path("Status").asText())) {
            publishCrl(located, ca);
        }
    }

    public synchronized void delete(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        String status = ca.path("Status").asText();
        if ("ACTIVE".equals(status)) {
            throw new AwsException("InvalidStateException", "Disable the certificate authority before you delete "
                    + "it.", 400);
        }
        if ("DELETED".equals(status)) {
            throw new AwsException("InvalidStateException", "The certificate authority is already deleted.", 400);
        }
        int days = in.path("PermanentDeletionTimeInDays").asInt(30);
        if (days < 7 || days > 30) {
            throw invalidArgs("PermanentDeletionTimeInDays must be between 7 and 30.");
        }
        Instant now = Instant.now();
        ca.put("statusBeforeDeletion", status);
        ca.put("Status", "DELETED");
        ca.put("LastStateChangeAt", seconds(now));
        ca.put("RestorableUntil", seconds(now.plus(days, ChronoUnit.DAYS)));
        save(located, ca);
    }

    public synchronized void restore(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        if (!"DELETED".equals(ca.path("Status").asText())) {
            throw new AwsException("InvalidStateException", "Only a deleted certificate authority can be restored.",
                    400);
        }
        String previous = ca.path("statusBeforeDeletion").asText("PENDING_CERTIFICATE");
        ca.put("Status", "PENDING_CERTIFICATE".equals(previous) || "CREATING".equals(previous)
                ? "PENDING_CERTIFICATE" : ca.has("certificate") ? "DISABLED" : "PENDING_CERTIFICATE");
        ca.remove(List.of("RestorableUntil", "statusBeforeDeletion"));
        ca.put("LastStateChangeAt", seconds(Instant.now()));
        save(located, ca);
    }

    public ObjectNode csr(JsonNode in) {
        ObjectNode ca = require(in.path("CertificateAuthorityArn").asText(null));
        String status = ca.path("Status").asText();
        if ("CREATING".equals(status)) {
            throw new AwsException("RequestInProgressException", "The certificate authority is being created.", 400);
        }
        if (!"PENDING_CERTIFICATE".equals(status) && !"ACTIVE".equals(status)) {
            throw new AwsException("InvalidStateException", "The certificate authority is " + status + ".", 400);
        }
        return mapper.createObjectNode().put("Csr", ca.path("csr").asText());
    }

    public ObjectNode caCertificate(JsonNode in) {
        ObjectNode ca = require(in.path("CertificateAuthorityArn").asText(null));
        String status = ca.path("Status").asText();
        if (!ca.has("certificate") || !Set.of("ACTIVE", "DISABLED", "EXPIRED").contains(status)) {
            throw new AwsException("InvalidStateException", "The certificate authority is " + status
                    + " and has no certificate.", 400);
        }
        ObjectNode out = mapper.createObjectNode().put("Certificate", ca.path("certificate").asText());
        if (!ca.path("chain").asText("").isEmpty()) {
            out.put("CertificateChain", ca.path("chain").asText());
        }
        return out;
    }

    /**
     * Installs the CA's certificate: it must certify the CA's own key, be a CA certificate, and for
     * a root be self-signed, for a subordinate come with a chain that leads from it to a root.
     */
    public synchronized void importCertificate(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        String status = ca.path("Status").asText();
        if (!Set.of("PENDING_CERTIFICATE", "ACTIVE", "DISABLED", "EXPIRED").contains(status)) {
            throw new AwsException("InvalidStateException", "The certificate authority is " + status + ".", 400);
        }
        byte[] certificateBytes = blob(in, "Certificate");
        X509CertificateHolder certificate = AcmPcaCrypto.parseCertificates(certificateBytes).getFirst();
        SubjectPublicKeyInfo caKey = SubjectPublicKeyInfo.getInstance(Base64.getDecoder()
                .decode(ca.path("publicKey").asText()));
        if (!certificate.getSubjectPublicKeyInfo().equals(caKey)) {
            throw new AwsException("CertificateMismatchException", "The certificate does not match the certificate "
                    + "authority's key.", 400);
        }
        if (!AcmPcaCrypto.isCa(certificate)) {
            throw new AwsException("MalformedCertificateException", "The certificate is not a CA certificate.", 400);
        }
        String chainPem = "";
        if ("ROOT".equals(ca.path("Type").asText())) {
            if (!certificate.getIssuer().equals(certificate.getSubject())
                    || !AcmPcaCrypto.signedBy(certificate, caKey)) {
                throw new AwsException("InvalidRequestException", "A root CA's certificate must be self-signed.", 400);
            }
            if (in.hasNonNull("CertificateChain")) {
                throw new AwsException("InvalidRequestException", "A root CA's certificate takes no chain.", 400);
            }
        } else {
            if (!in.hasNonNull("CertificateChain")) {
                throw new AwsException("InvalidRequestException", "A subordinate CA's certificate needs its chain.",
                        400);
            }
            byte[] chainBytes = blob(in, "CertificateChain");
            List<X509CertificateHolder> chain = AcmPcaCrypto.parseCertificates(chainBytes);
            X509CertificateHolder child = certificate;
            for (X509CertificateHolder parent : chain) {
                if (!child.getIssuer().equals(parent.getSubject())
                        || !AcmPcaCrypto.signedBy(child, parent.getSubjectPublicKeyInfo())) {
                    throw new AwsException("InvalidRequestException", "The certificate chain does not lead from the "
                            + "certificate to a root.", 400);
                }
                child = parent;
            }
            if (!child.getIssuer().equals(child.getSubject()) || !AcmPcaCrypto.signedBy(child,
                    child.getSubjectPublicKeyInfo())) {
                throw new AwsException("InvalidRequestException", "The certificate chain must end with a self-signed "
                        + "root.", 400);
            }
            chainPem = new String(chainBytes, StandardCharsets.UTF_8).trim() + "\n";
        }
        if (certificate.getNotAfter().toInstant().isBefore(Instant.now())) {
            throw new AwsException("MalformedCertificateException", "The certificate has expired.", 400);
        }
        ca.put("certificate", AcmPcaCrypto.pem("CERTIFICATE", encoded(certificate)));
        ca.put("chain", chainPem);
        ca.put("Serial", AcmPcaCrypto.serialText(certificate.getSerialNumber()));
        ca.put("NotBefore", seconds(certificate.getNotBefore().toInstant()));
        ca.put("NotAfter", seconds(certificate.getNotAfter().toInstant()));
        ca.put("subjectDn", certificate.getSubject().toString());
        if (!"DISABLED".equals(status)) {
            ca.put("Status", "ACTIVE");
        }
        ca.put("LastStateChangeAt", seconds(Instant.now()));
        save(located, ca);
        publishCrl(located, ca);
    }

    // ── Certificates ───────────────────────────────────────────────────────

    public synchronized ObjectNode issue(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        String caArn = ca.path("Arn").asText();
        String token = in.path("IdempotencyToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : certificates.scan(k -> k.startsWith(located.key() + "::"))) {
                if (token.equals(existing.path("idempotencyToken").asText(null))
                        && Instant.parse(existing.path("issuedAt").asText()).isAfter(Instant.now().minusSeconds(60))) {
                    return mapper.createObjectNode().put("CertificateArn", existing.path("arn").asText());
                }
            }
        }
        AcmPcaTemplates.Template template = AcmPcaTemplates.forArn(in.path("TemplateArn").asText(null));
        PKCS10CertificationRequest csr = AcmPcaCrypto.parseCsr(blob(in, "Csr"));
        String status = ca.path("Status").asText();
        String keyAlgorithm = ca.path("CertificateAuthorityConfiguration").path("KeyAlgorithm").asText();
        String signing = in.path("SigningAlgorithm").asText();
        String jca = AcmPcaCrypto.signatureAlgorithm(signing, keyAlgorithm);
        SubjectPublicKeyInfo caKey = SubjectPublicKeyInfo.getInstance(Base64.getDecoder()
                .decode(ca.path("publicKey").asText()));
        boolean selfSign = template.root();
        X500Name issuer;
        Instant caNotAfter = null;
        if (selfSign) {
            if (!"ROOT".equals(ca.path("Type").asText())) {
                throw invalidArgs("Only a root CA can issue a RootCACertificate.");
            }
            if (!csr.getSubjectPublicKeyInfo().equals(caKey)) {
                throw invalidArgs("A RootCACertificate can only be issued for the CA's own CSR.");
            }
            if (!Set.of("PENDING_CERTIFICATE", "ACTIVE").contains(status)) {
                throw new AwsException("InvalidStateException", "The certificate authority is " + status + ".", 400);
            }
            issuer = csr.getSubject();
        } else {
            if (!"ACTIVE".equals(status)) {
                throw new AwsException("InvalidStateException", "The certificate authority is " + status
                        + "; only an ACTIVE certificate authority issues certificates.", 400);
            }
            X509CertificateHolder caCertificate = AcmPcaCrypto.parseCertificate(ca.path("certificate").asText());
            issuer = caCertificate.getSubject();
            caNotAfter = caCertificate.getNotAfter().toInstant();
            if (template.ca()) {
                BigInteger allowed = AcmPcaCrypto.pathLength(caCertificate);
                if (allowed != null && template.pathLength() != null
                        && template.pathLength() >= allowed.intValue()) {
                    throw invalidArgs("The template's path length " + template.pathLength() + " exceeds what the "
                            + "certificate authority's own certificate allows.");
                }
            }
        }
        Instant now = Instant.now();
        Instant notBefore = in.path("ValidityNotBefore").isObject()
                ? validityInstant(in.get("ValidityNotBefore"), now) : now.minus(60, ChronoUnit.MINUTES);
        Instant notAfter = validityInstant(in.path("Validity"), now);
        if (!notAfter.isAfter(now)) {
            throw new AwsException("ValidationException", "The certificate validity must end in the future.", 400);
        }
        if (caNotAfter != null && notAfter.isAfter(caNotAfter)) {
            throw new AwsException("ValidationException", "The certificate validity specified exceeds the "
                    + "certificate authority validity.", 400);
        }
        if ("SHORT_LIVED_CERTIFICATE".equals(ca.path("UsageMode").asText()) && !template.ca()
                && notAfter.isAfter(now.plus(SHORT_LIVED_MAX_DAYS, ChronoUnit.DAYS))) {
            throw new AwsException("ValidationException", "A certificate authority in SHORT_LIVED_CERTIFICATE mode "
                    + "issues certificates valid for at most 7 days.", 400);
        }
        JsonNode passthrough = in.path("ApiPassthrough");
        if (passthrough.isObject() && !template.apiPassthrough()) {
            throw invalidArgs("ApiPassthrough needs a template that accepts API passthrough.");
        }
        BigInteger serial = AcmPcaCrypto.serial();
        PrivateKey caPrivate = AcmPcaCrypto.decodeKey(ca.path("privateKey").asText(), keyAlgorithm);
        String pem = AcmPcaCrypto.sign(new AcmPcaCrypto.Issue(csr, template, passthrough.isObject() ? passthrough
                : mapper.createObjectNode(), notBefore, notAfter, serial, issuer, publicKey(caKey), caPrivate, jca,
                selfSign ? List.of() : crlUrls(ca, located.region()), selfSign ? null : ocspUrl(ca,
                located.region())));
        String certArn = caArn + "/certificate/" + serial.toString(16);
        ObjectNode record = mapper.createObjectNode();
        record.put("arn", certArn);
        record.put("serial", serial.toString(16));
        record.put("certificate", pem);
        record.put("template", template.name());
        record.put("issuedAt", now.toString());
        record.put("notBefore", notBefore.toString());
        record.put("notAfter", notAfter.toString());
        record.put("subject", AcmPcaCrypto.parseCertificate(pem).getSubject().toString());
        record.put("selfSigned", selfSign);
        if (token != null) {
            record.put("idempotencyToken", token);
        }
        putCertificate(located, serial, record);
        publish("ACM Private CA Certificate Issuance", certArn, located.region(), mapper.createObjectNode()
                .put("result", "success"));
        return mapper.createObjectNode().put("CertificateArn", certArn);
    }

    private void putCertificate(Located located, BigInteger serial, ObjectNode record) {
        String storeKey = located.key() + "::" + serial.toString(16);
        String owner = AwsArnUtils.parse(located.ca().path("Arn").asText()).accountId();
        if (owner.equals(regionResolver.getAccountId())) {
            certificates.put(storeKey, record);
        } else {
            certificates.putForAccount(owner, storeKey, record);
        }
    }

    private ObjectNode findCertificate(Located located, BigInteger serial) {
        String storeKey = located.key() + "::" + serial.toString(16);
        String owner = AwsArnUtils.parse(located.ca().path("Arn").asText()).accountId();
        return (owner.equals(regionResolver.getAccountId()) ? certificates.get(storeKey)
                : certificates.getForAccount(owner, storeKey)).orElse(null);
    }

    private static Instant validityInstant(JsonNode validity, Instant now) {
        String type = validity.path("Type").asText("");
        long value = validity.path("Value").asLong(0);
        if (value <= 0) {
            throw new AwsException("ValidationException", "Validity.Value must be positive.", 400);
        }
        return switch (type) {
            case "DAYS" -> now.plus(value, ChronoUnit.DAYS);
            case "MONTHS" -> LocalDateTime.ofInstant(now, ZoneOffset.UTC).plusMonths(value).toInstant(ZoneOffset.UTC);
            case "YEARS" -> LocalDateTime.ofInstant(now, ZoneOffset.UTC).plusYears(value).toInstant(ZoneOffset.UTC);
            case "ABSOLUTE" -> Instant.ofEpochSecond(value);
            case "END_DATE" -> {
                try {
                    yield LocalDateTime.parse(String.format("%014d", value), END_DATE).toInstant(ZoneOffset.UTC);
                } catch (RuntimeException e) {
                    throw new AwsException("ValidationException", "An END_DATE validity is YYYYMMDDHHMMSS.", 400);
                }
            }
            default -> throw new AwsException("ValidationException", "Validity.Type must be END_DATE, ABSOLUTE, DAYS, "
                    + "MONTHS or YEARS.", 400);
        };
    }

    public ObjectNode getCertificate(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        String certArn = in.path("CertificateArn").asText("");
        String prefix = located.ca().path("Arn").asText() + "/certificate/";
        if (!certArn.startsWith(prefix)) {
            throw new AwsException("InvalidArnException", "The certificate ARN " + certArn + " is not a certificate "
                    + "of this certificate authority.", 400);
        }
        ObjectNode record = findCertificate(located, AcmPcaCrypto.parseSerial(certArn.substring(prefix.length())));
        if (record == null) {
            throw new AwsException("ResourceNotFoundException", "Could not find certificate " + certArn + ".", 400);
        }
        ObjectNode out = mapper.createObjectNode().put("Certificate", record.path("certificate").asText());
        ObjectNode ca = located.ca();
        if (!record.path("selfSigned").asBoolean(false) && ca.has("certificate")) {
            out.put("CertificateChain", (ca.path("certificate").asText() + ca.path("chain").asText("")).trim()
                    + "\n");
        }
        return out;
    }

    public synchronized void revoke(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        String status = ca.path("Status").asText();
        if (!Set.of("ACTIVE", "DISABLED", "EXPIRED").contains(status)) {
            throw new AwsException("InvalidStateException", "The certificate authority is " + status + ".", 400);
        }
        String reason = in.path("RevocationReason").asText("");
        if (!Set.of("UNSPECIFIED", "KEY_COMPROMISE", "CERTIFICATE_AUTHORITY_COMPROMISE", "AFFILIATION_CHANGED",
                "SUPERSEDED", "CESSATION_OF_OPERATION", "PRIVILEGE_WITHDRAWN", "A_A_COMPROMISE").contains(reason)) {
            throw invalidArgs("RevocationReason " + reason + " is not valid.");
        }
        BigInteger serial = AcmPcaCrypto.parseSerial(in.path("CertificateSerial").asText(""));
        ObjectNode record = findCertificate(located, serial);
        if (record == null) {
            throw new AwsException("ResourceNotFoundException", "The certificate authority issued no certificate "
                    + "with serial " + in.path("CertificateSerial").asText() + ".", 400);
        }
        if (record.has("revokedAt")) {
            throw new AwsException("RequestAlreadyProcessedException", "The certificate is already revoked.", 400);
        }
        if (Instant.parse(record.path("notAfter").asText()).isBefore(Instant.now())) {
            throw new AwsException("RequestFailedException", "An expired certificate can't be revoked.", 400);
        }
        record.put("revokedAt", Instant.now().toString());
        record.put("revocationReason", reason);
        putCertificate(located, serial, record);
        publish("ACM Private CA Certificate Revocation", record.path("arn").asText(), located.region(),
                mapper.createObjectNode().put("result", "success"));
        publishCrl(located, ca);
    }

    // ── CRLs ───────────────────────────────────────────────────────────────

    private void checkRevocation(JsonNode revocation, String region) {
        JsonNode crl = revocation.path("CrlConfiguration");
        if (crl.path("Enabled").asBoolean(false)) {
            int days = crl.path("ExpirationInDays").asInt(7);
            if (days < 1 || days > 5000) {
                throw invalidArgs("CrlConfiguration.ExpirationInDays must be between 1 and 5000.");
            }
            String bucket = crl.path("S3BucketName").asText(null);
            if (bucket == null || bucket.isEmpty()) {
                throw invalidArgs("An enabled CRL needs an S3BucketName.");
            }
            if (!s3.isResolvable() || !s3.get().bucketExists(bucket)) {
                throw invalidArgs("The S3 bucket " + bucket + " does not exist or Private CA can't write to it.");
            }
        }
    }

    private ObjectNode normalizeRevocation(JsonNode revocation) {
        ObjectNode copy = revocation.deepCopy();
        ObjectNode crl = copy.has("CrlConfiguration") ? (ObjectNode) copy.get("CrlConfiguration")
                : copy.putObject("CrlConfiguration");
        if (!crl.has("Enabled")) {
            crl.put("Enabled", false);
        }
        if (crl.path("Enabled").asBoolean()) {
            if (!crl.has("ExpirationInDays")) {
                crl.put("ExpirationInDays", 7);
            }
            if (!crl.has("S3ObjectAcl")) {
                crl.put("S3ObjectAcl", "PUBLIC_READ");
            }
            if (!crl.has("CrlType")) {
                crl.put("CrlType", "COMPLETE");
            }
        }
        ObjectNode ocsp = copy.has("OcspConfiguration") ? (ObjectNode) copy.get("OcspConfiguration")
                : copy.putObject("OcspConfiguration");
        if (!ocsp.has("Enabled")) {
            ocsp.put("Enabled", false);
        }
        return copy;
    }

    private ObjectNode defaultRevocation() {
        return normalizeRevocation(mapper.createObjectNode());
    }

    private String crlKey(ObjectNode ca) {
        JsonNode crl = ca.path("RevocationConfiguration").path("CrlConfiguration");
        String path = crl.path("CustomPath").asText("crl");
        return path.replaceAll("^/+|/+$", "") + "/" + id(ca) + ".crl";
    }

    private List<String> crlUrls(ObjectNode ca, String region) {
        JsonNode crl = ca.path("RevocationConfiguration").path("CrlConfiguration");
        if (!crl.path("Enabled").asBoolean(false)
                || crl.path("CrlDistributionPointExtensionConfiguration").path("OmitExtension").asBoolean(false)) {
            return List.of();
        }
        String host = crl.hasNonNull("CustomCname") ? crl.get("CustomCname").asText()
                : crl.path("S3BucketName").asText() + ".s3." + region + "." + AwsRegions.dnsSuffixFor(region);
        return List.of("http://" + host + "/" + crlKey(ca));
    }

    private String ocspUrl(ObjectNode ca, String region) {
        JsonNode ocsp = ca.path("RevocationConfiguration").path("OcspConfiguration");
        if (!ocsp.path("Enabled").asBoolean(false)) {
            return null;
        }
        return ocsp.hasNonNull("OcspCustomCname") ? "http://" + ocsp.get("OcspCustomCname").asText()
                : "http://ocsp.acm-pca." + region + "." + AwsRegions.dnsSuffixFor(region);
    }

    /** Writes the CA's CRL to its bucket, when CRLs are enabled and the CA has a certificate. */
    private void publishCrl(Located located, ObjectNode ca) {
        JsonNode crl = ca.path("RevocationConfiguration").path("CrlConfiguration");
        if (!crl.path("Enabled").asBoolean(false) || !ca.has("certificate") || !s3.isResolvable()) {
            return;
        }
        List<AcmPcaCrypto.Revoked> revoked = new ArrayList<>();
        for (ObjectNode record : certificatesOf(located)) {
            if (record.has("revokedAt")) {
                revoked.add(new AcmPcaCrypto.Revoked(new BigInteger(record.path("serial").asText(), 16),
                        Instant.parse(record.path("revokedAt").asText()), record.path("revocationReason").asText()));
            }
        }
        String keyAlgorithm = ca.path("CertificateAuthorityConfiguration").path("KeyAlgorithm").asText();
        String signing = ca.path("CertificateAuthorityConfiguration").path("SigningAlgorithm").asText();
        X509CertificateHolder caCertificate = AcmPcaCrypto.parseCertificate(ca.path("certificate").asText());
        long number = ca.path("crlNumber").asLong(0) + 1;
        byte[] der = AcmPcaCrypto.crl(caCertificate.getSubject(), publicKey(caCertificate.getSubjectPublicKeyInfo()),
                AcmPcaCrypto.decodeKey(ca.path("privateKey").asText(), keyAlgorithm),
                AcmPcaCrypto.signatureAlgorithm(signing, keyAlgorithm), revoked, number,
                crl.path("ExpirationInDays").asInt(7));
        String bucket = crl.path("S3BucketName").asText();
        String result = "success";
        try {
            s3.get().putObject(bucket, crlKey(ca), der, "application/pkix-crl", Map.of());
        } catch (AwsException e) {
            result = "failure";
        }
        ca.put("crlNumber", number);
        save(located, ca);
        publish("ACM Private CA CRL Generation", ca.path("Arn").asText(), located.region(), mapper.createObjectNode()
                .put("result", result));
    }

    private List<ObjectNode> certificatesOf(Located located) {
        String owner = AwsArnUtils.parse(located.ca().path("Arn").asText()).accountId();
        String prefix = located.key() + "::";
        if (owner.equals(regionResolver.getAccountId())) {
            return certificates.scan(k -> k.startsWith(prefix));
        }
        List<ObjectNode> out = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : certificates.scanAllAccountEntries(k -> k.startsWith(prefix))) {
            if (entry.accountId().equals(owner)) {
                out.add(entry.value());
            }
        }
        return out;
    }

    // ── Audit reports ──────────────────────────────────────────────────────

    public synchronized ObjectNode createAuditReport(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        if (Set.of("CREATING", "PENDING_CERTIFICATE", "DELETED", "FAILED").contains(ca.path("Status").asText())) {
            throw new AwsException("InvalidStateException", "The certificate authority is "
                    + ca.path("Status").asText() + ".", 400);
        }
        String bucket = in.path("S3BucketName").asText("");
        String format = in.path("AuditReportResponseFormat").asText("");
        if (!Set.of("JSON", "CSV").contains(format)) {
            throw invalidArgs("AuditReportResponseFormat must be JSON or CSV.");
        }
        if (!s3.isResolvable() || !s3.get().bucketExists(bucket)) {
            throw invalidArgs("The S3 bucket " + bucket + " does not exist or Private CA can't write to it.");
        }
        String reportId = UUID.randomUUID().toString();
        String s3Key = "audit-report/" + id(ca) + "/" + reportId + "." + format.toLowerCase();
        List<ObjectNode> records = certificatesOf(located);
        records.sort(Comparator.comparing(r -> r.path("issuedAt").asText()));
        String account = AwsArnUtils.parse(ca.path("Arn").asText()).accountId();
        byte[] body;
        if ("JSON".equals(format)) {
            ArrayNode rows = mapper.createArrayNode();
            for (ObjectNode record : records) {
                ObjectNode row = rows.addObject();
                row.put("awsAccountId", account);
                row.put("certificateArn", record.path("arn").asText());
                row.put("serial", AcmPcaCrypto.serialText(new BigInteger(record.path("serial").asText(), 16)));
                row.put("subject", record.path("subject").asText());
                row.put("notBefore", record.path("notBefore").asText());
                row.put("notAfter", record.path("notAfter").asText());
                row.put("issuedAt", record.path("issuedAt").asText());
                row.put("templateArn", "arn:" + regionResolver.getPartition() + ":acm-pca:::template/" // partition-literal: ARN prefix, partition from the request
                        + record.path("template").asText());
                if (record.has("revokedAt")) {
                    row.put("revokedAt", record.path("revokedAt").asText());
                    row.put("revocationReason", record.path("revocationReason").asText());
                }
            }
            body = rows.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            StringBuilder csv = new StringBuilder("awsAccountId,certificateArn,serial,subject,notBefore,notAfter,"
                    + "issuedAt,revokedAt,revocationReason,templateArn\n");
            for (ObjectNode record : records) {
                csv.append(String.join(",", account, record.path("arn").asText(),
                        AcmPcaCrypto.serialText(new BigInteger(record.path("serial").asText(), 16)),
                        "\"" + record.path("subject").asText().replace("\"", "\"\"") + "\"",
                        record.path("notBefore").asText(), record.path("notAfter").asText(),
                        record.path("issuedAt").asText(), record.path("revokedAt").asText(""),
                        record.path("revocationReason").asText(""), record.path("template").asText())).append('\n');
            }
            body = csv.toString().getBytes(StandardCharsets.UTF_8);
        }
        s3.get().putObject(bucket, s3Key, body, "JSON".equals(format) ? "application/json" : "text/csv", Map.of());
        ObjectNode report = mapper.createObjectNode();
        report.put("AuditReportId", reportId);
        report.put("S3BucketName", bucket);
        report.put("S3Key", s3Key);
        report.put("CreatedAt", seconds(Instant.now()));
        auditReports.put(located.key() + "::" + reportId, report);
        publish("ACM Private CA Audit Report Generation", ca.path("Arn").asText(), located.region(),
                mapper.createObjectNode().put("result", "success").put("auditReportId", reportId));
        return mapper.createObjectNode().put("AuditReportId", reportId).put("S3Key", s3Key);
    }

    public ObjectNode describeAuditReport(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        String reportId = in.path("AuditReportId").asText("");
        ObjectNode report = auditReports.get(located.key() + "::" + reportId).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "Could not find audit report " + reportId + ".", 400));
        ObjectNode out = report.deepCopy();
        out.remove("AuditReportId");
        long age = Instant.now().toEpochMilli() - (long) (report.path("CreatedAt").asDouble() * 1000);
        out.put("AuditReportStatus", age < AUDIT_CREATING_MILLIS ? "CREATING" : "SUCCESS");
        return out;
    }

    // ── Permissions and policies ───────────────────────────────────────────

    public synchronized void createPermission(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        String principal = in.path("Principal").asText("");
        if (!ACM_PRINCIPAL.equals(principal)) {
            throw invalidArgs("The only principal a permission can name is " + ACM_PRINCIPAL + ".");
        }
        List<String> actions = new ArrayList<>();
        in.path("Actions").forEach(a -> actions.add(a.asText()));
        if (!PERMISSION_ACTIONS.containsAll(actions) || actions.size() != Set.copyOf(actions).size()) {
            throw invalidArgs("Permission actions are IssueCertificate, GetCertificate and ListPermissions.");
        }
        if (!Set.copyOf(actions).equals(PERMISSION_ACTIONS)) {
            throw new AwsException("ValidationException", "Permissions must contain all three actions: "
                    + "IssueCertificate, GetCertificate and ListPermissions.", 400);
        }
        String sourceAccount = in.path("SourceAccount").asText(AwsArnUtils.parse(ca.path("Arn").asText())
                .accountId());
        for (JsonNode existing : ca.withArray("Permissions")) {
            if (principal.equals(existing.path("Principal").asText())
                    && sourceAccount.equals(existing.path("SourceAccount").asText())) {
                throw new AwsException("PermissionAlreadyExistsException", "The permission already exists.", 400);
            }
        }
        ObjectNode permission = ca.withArray("Permissions").addObject();
        permission.put("CertificateAuthorityArn", ca.path("Arn").asText());
        permission.put("CreatedAt", seconds(Instant.now()));
        permission.put("Principal", principal);
        permission.put("SourceAccount", sourceAccount);
        ArrayNode list = permission.putArray("Actions");
        actions.forEach(list::add);
        save(located, ca);
    }

    public synchronized void deletePermission(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        String principal = in.path("Principal").asText("");
        String sourceAccount = in.path("SourceAccount").asText(null);
        ArrayNode permissions = ca.withArray("Permissions");
        for (int i = 0; i < permissions.size(); i++) {
            JsonNode permission = permissions.get(i);
            if (principal.equals(permission.path("Principal").asText())
                    && (sourceAccount == null || sourceAccount.equals(permission.path("SourceAccount").asText()))) {
                permissions.remove(i);
                save(located, ca);
                return;
            }
        }
        throw new AwsException("ResourceNotFoundException", "The certificate authority has no permission for "
                + principal + ".", 400);
    }

    public ObjectNode listPermissions(JsonNode in) {
        ObjectNode ca = require(in.path("CertificateAuthorityArn").asText(null));
        List<ObjectNode> all = new ArrayList<>();
        ca.withArray("Permissions").forEach(p -> all.add((ObjectNode) p));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Permissions");
        page(all, in, out).forEach(permission -> {
            ObjectNode copy = permission.deepCopy();
            copy.put("Policy", permissionPolicy(ca, permission));
            list.add(copy);
        });
        return out;
    }

    private String permissionPolicy(ObjectNode ca, JsonNode permission) {
        ObjectNode policy = mapper.createObjectNode().put("Version", "2012-10-17");
        ObjectNode statement = policy.putArray("Statement").addObject();
        statement.put("Effect", "Allow");
        statement.putObject("Principal").put("Service", permission.path("Principal").asText());
        ArrayNode actions = statement.putArray("Action");
        permission.path("Actions").forEach(a -> actions.add("acm-pca:" + a.asText()));
        statement.put("Resource", ca.path("Arn").asText());
        statement.putObject("Condition").putObject("StringEquals").put("aws:SourceAccount",
                permission.path("SourceAccount").asText());
        return policy.toString();
    }

    public synchronized void putPolicy(JsonNode in) {
        Located located = locate(in.path("ResourceArn").asText(null));
        ObjectNode ca = located.ca();
        String policy = in.path("Policy").asText("");
        try {
            JsonNode document = mapper.readTree(policy);
            if (document == null || !document.isObject() || !document.has("Statement")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new AwsException("InvalidPolicyException", "The policy is not a valid JSON policy document.", 400);
        }
        if (!policy.contains(ca.path("Arn").asText()) && !policy.contains("\"*\"")) {
            throw new AwsException("InvalidPolicyException", "The policy's Resource must name the certificate "
                    + "authority.", 400);
        }
        ca.put("Policy", policy);
        save(located, ca);
    }

    public ObjectNode getPolicy(JsonNode in) {
        ObjectNode ca = require(in.path("ResourceArn").asText(null));
        if (!ca.hasNonNull("Policy")) {
            throw new AwsException("ResourceNotFoundException", "The certificate authority has no policy.", 400);
        }
        return mapper.createObjectNode().put("Policy", ca.path("Policy").asText());
    }

    public synchronized void deletePolicy(JsonNode in) {
        Located located = locate(in.path("ResourceArn").asText(null));
        ObjectNode ca = located.ca();
        if (!ca.hasNonNull("Policy")) {
            throw new AwsException("ResourceNotFoundException", "The certificate authority has no policy.", 400);
        }
        ca.remove("Policy");
        save(located, ca);
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized void tag(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        checkTags(in.path("Tags"));
        ObjectNode tags = ca.withObject("Tags");
        in.path("Tags").forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText("")));
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("TooManyTagsException", "A certificate authority can have at most 50 tags.", 400);
        }
        save(located, ca);
    }

    public synchronized void untag(JsonNode in) {
        Located located = locate(in.path("CertificateAuthorityArn").asText(null));
        ObjectNode ca = located.ca();
        ObjectNode tags = ca.withObject("Tags");
        for (JsonNode tag : in.path("Tags")) {
            String key = tag.path("Key").asText();
            if (!tag.has("Value") || tags.path(key).asText().equals(tag.path("Value").asText())) {
                tags.remove(key);
            }
        }
        save(located, ca);
    }

    public ObjectNode listTags(JsonNode in) {
        ObjectNode ca = require(in.path("CertificateAuthorityArn").asText(null));
        List<ObjectNode> all = new ArrayList<>();
        ca.withObject("Tags").fields().forEachRemaining(e -> all.add(mapper.createObjectNode()
                .put("Key", e.getKey()).put("Value", e.getValue().asText())));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Tags");
        page(all, in, out).forEach(list::add);
        return out;
    }

    private static void checkTags(JsonNode tags) {
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("TooManyTagsException", "A certificate authority can have at most 50 tags.", 400);
        }
        for (JsonNode tag : tags) {
            if (tag.path("Key").asText("").startsWith("aws:")) {
                throw new AwsException("InvalidTagException", "Tag keys can't start with aws:.", 400);
            }
        }
    }

    // ── For ACM ────────────────────────────────────────────────────────────

    /** Why ACM couldn't have a CA sign a private certificate, as ACM's failure reasons name it. */
    public static final class AcmFailure extends RuntimeException {
        private final String reason;

        AcmFailure(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * Signs a certificate ACM requests on a caller's behalf: the CA must be ACTIVE in the caller's
     * account (or shared with it) and grant ACM its permission. Returns the certificate and chain.
     */
    public synchronized String[] issueForAcm(String caArn, PKCS10CertificationRequest csr, int validityDays) {
        Located located;
        try {
            located = locate(caArn);
        } catch (AwsException e) {
            throw new AcmFailure("InvalidArnException".equals(e.getErrorCode()) ? "PCA_INVALID_ARN"
                    : "PCA_RESOURCE_NOT_FOUND", e.getMessage());
        }
        ObjectNode ca = located.ca();
        if (!"ACTIVE".equals(ca.path("Status").asText())) {
            throw new AcmFailure("PCA_INVALID_STATE", "The private CA is " + ca.path("Status").asText() + ".");
        }
        String signing = ca.path("CertificateAuthorityConfiguration").path("SigningAlgorithm").asText();
        String keyAlgorithm = ca.path("CertificateAuthorityConfiguration").path("KeyAlgorithm").asText();
        X509CertificateHolder caCertificate = AcmPcaCrypto.parseCertificate(ca.path("certificate").asText());
        Instant now = Instant.now();
        int days = "SHORT_LIVED_CERTIFICATE".equals(ca.path("UsageMode").asText())
                ? (int) Math.min(validityDays, SHORT_LIVED_MAX_DAYS) : validityDays;
        Instant notAfter = now.plus(days, ChronoUnit.DAYS);
        if (notAfter.isAfter(caCertificate.getNotAfter().toInstant())) {
            notAfter = caCertificate.getNotAfter().toInstant();
        }
        BigInteger serial = AcmPcaCrypto.serial();
        String pem = AcmPcaCrypto.sign(new AcmPcaCrypto.Issue(csr, AcmPcaTemplates.byName(AcmPcaTemplates.DEFAULT),
                mapper.createObjectNode(), now.minus(60, ChronoUnit.MINUTES), notAfter, serial,
                caCertificate.getSubject(), publicKey(caCertificate.getSubjectPublicKeyInfo()),
                AcmPcaCrypto.decodeKey(ca.path("privateKey").asText(), keyAlgorithm),
                AcmPcaCrypto.signatureAlgorithm(signing, keyAlgorithm), crlUrls(ca, located.region()),
                ocspUrl(ca, located.region())));
        String certArn = ca.path("Arn").asText() + "/certificate/" + serial.toString(16);
        ObjectNode record = mapper.createObjectNode();
        record.put("arn", certArn);
        record.put("serial", serial.toString(16));
        record.put("certificate", pem);
        record.put("template", AcmPcaTemplates.DEFAULT);
        record.put("issuedAt", now.toString());
        record.put("notBefore", now.minus(60, ChronoUnit.MINUTES).toString());
        record.put("notAfter", notAfter.toString());
        record.put("subject", csr.getSubject().toString());
        record.put("issuedForAcm", true);
        putCertificate(located, serial, record);
        publish("ACM Private CA Certificate Issuance", certArn, located.region(), mapper.createObjectNode()
                .put("result", "success"));
        return new String[]{pem, (ca.path("certificate").asText() + ca.path("chain").asText("")).trim() + "\n"};
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void publish(String detailType, String resource, String region, ObjectNode detail) {
        if (eventBridge.isResolvable()) {
            eventBridge.get().publishServiceEvent(EVENT_SOURCE, detailType, detail.toString(), resource, region);
        }
    }

    private static byte[] blob(JsonNode in, String field) {
        String text = in.path(field).asText("");
        if (text.isEmpty()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] encoded(X509CertificateHolder certificate) {
        try {
            return certificate.getEncoded();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PublicKey publicKey(SubjectPublicKeyInfo info) {
        try {
            String algorithm = info.getAlgorithm().getAlgorithm().getId().startsWith("1.2.840.113549") ? "RSA" : "EC";
            return KeyFactory.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME)
                    .generatePublic(new X509EncodedKeySpec(info.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read a public key", e);
        }
    }

    private static String id(ObjectNode ca) {
        String arn = ca.path("Arn").asText();
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        tags.forEach(t -> map.put(t.path("Key").asText(), t.path("Value").asText("")));
        return map;
    }

    private static double seconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("MaxResults").asInt(1000);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new AwsException("InvalidNextTokenException", "The token specified in the NextToken argument "
                        + "is not valid.", 400);
            }
        }
        int end = Math.min(all.size(), start + Math.max(1, max));
        if (end < all.size()) {
            out.put("NextToken", Base64.getEncoder().encodeToString(Integer.toString(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return all.subList(Math.min(start, end), end);
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    static AwsException invalidArgs(String message) {
        return new AwsException("InvalidArgsException", message, 400);
    }

    @Override
    public void clear() {
        authorities.clear();
        certificates.clear();
        auditReports.clear();
    }
}

package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import java.util.LinkedHashMap;
import java.util.Map;

@Provider
public class PreSignedUrlFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(PreSignedUrlFilter.class);
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    private final PreSignedUrlGenerator presignGenerator;
    private final S3Service s3Service;
    private final IamService iamService;

    @Inject
    public PreSignedUrlFilter(PreSignedUrlGenerator presignGenerator,
                              S3Service s3Service,
                              IamService iamService) {
        this.presignGenerator = presignGenerator;
        this.s3Service = s3Service;
        this.iamService = iamService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // A browser preflight reuses the target request's presigned URL, so its OPTIONS method
        // must not be verified against a signature created for the follow-up PUT/GET request.
        // The dedicated S3 OPTIONS resource performs the bucket CORS evaluation instead.
        if (isCorsPreflight(requestContext)) {
            return;
        }

        var queryParams = requestContext.getUriInfo().getQueryParameters();

        // Only process if this is a pre-signed URL request
        String algorithm = queryParams.getFirst("X-Amz-Algorithm");
        if (algorithm == null) {
            return;
        }

        if (!"AWS4-HMAC-SHA256".equals(algorithm)) {
            requestContext.abortWith(
                errorResponse(
                    400,
                    "AuthorizationQueryParametersError",
                    "Unsupported X-Amz-Algorithm value: " + algorithm
                )
            );
            return;
        }

        if (S3RequestAuthorizationParser.isMissingRequiredPresignedParameter(queryParams)) {
            requestContext.abortWith(errorResponse(
                    S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_STATUS,
                    S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_CODE,
                    S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_MESSAGE));
            return;
        }

        String amzDate = queryParams.getFirst("X-Amz-Date");
        String expiresStr = queryParams.getFirst("X-Amz-Expires");
        String signature = queryParams.getFirst("X-Amz-Signature");

        int expires;
        try {
            expires = Integer.parseInt(expiresStr);
        } catch (NumberFormatException e) {
            requestContext.abortWith(errorResponse(403, "AccessDenied",
                    "Invalid X-Amz-Expires value."));
            return;
        }

        if (expires < 1 || expires > 604800) {
            requestContext.abortWith(
                errorResponse(
                    400,
                    "AuthorizationQueryParametersError",
                    "X-Amz-Expires must be between 1 and 604800 seconds."
                )
            );
            return;
        }

        // Check expiration
        if (presignGenerator.isExpired(amzDate, expires)) {
            requestContext.abortWith(errorResponse(403, "AccessDenied",
                    "Request has expired."));
            return;
        }

        // Cryptographic verification follows the global validate-signatures switch: full SigV4
        // when S3 enforce-auth is also on, Floci's own HMAC otherwise. With the switch off,
        // enforce-auth still authorizes the request against the X-Amz-Credential identity.
        boolean verifySigV4 = s3Service.isAuthEnforced() && presignGenerator.shouldValidateSignatures();
        if (verifySigV4) {
            String credential = queryParams.getFirst("X-Amz-Credential");
            String decodedCredential = URLDecoder.decode(credential, StandardCharsets.UTF_8);
            String[] credParts = decodedCredential.split("/");
            if (credParts.length < 5) {
                requestContext.abortWith(errorResponse(403, "InvalidAccessKeyId",
                        "The AWS Access Key Id you provided does not exist in our records."));
                return;
            }

            String accessKeyId = credParts[0];
            String secretKey = resolveSecretKey(accessKeyId, queryParams.getFirst("X-Amz-Security-Token"));
            if (secretKey == null) {
                requestContext.abortWith(errorResponse(403, "InvalidAccessKeyId",
                        "The AWS Access Key Id you provided does not exist in our records."));
                return;
            }

            if (!verifySigV4Signature(requestContext, signature, secretKey)) {
                requestContext.abortWith(errorResponse(403, "SignatureDoesNotMatch",
                        "The request signature we calculated does not match the signature you provided."));
            }
        } else if (!s3Service.isAuthEnforced() && presignGenerator.shouldValidateSignatures()) {
            String path = requestContext.getUriInfo().getPath();
            String[] parts = path.split("/", 3);
            if (parts.length < 3) {
                requestContext.abortWith(errorResponse(403, "AccessDenied",
                        "Invalid pre-signed URL path."));
                return;
            }
            String bucket = parts[1];
            String key = parts[2];
            String method = requestContext.getMethod();

            if (!presignGenerator.verifySignature(method, bucket, key, amzDate, expires, signature)) {
                requestContext.abortWith(errorResponse(403, "SignatureDoesNotMatch",
                        "The request signature we calculated does not match the signature you provided."));
                return;
            }
        }

        // Always enforce signed Content-Type on AWS SDK / distilled presigned
        // PUTs. validate-signatures defaults false (Floci HMAC vs real SigV4),
        // but a URL that lists content-type in X-Amz-SignedHeaders must reject
        // a mismatched Content-Type the same way real S3 does (403).
        String signedHeaders = maybeUrlDecode(queryParams.getFirst("X-Amz-SignedHeaders"));
        if (!verifySigV4
                && S3PresignedSignature.signedHeadersInclude(signedHeaders, "content-type")
                && !signedContentTypeMatches(requestContext, queryParams, signedHeaders, amzDate, signature)) {
            requestContext.abortWith(errorResponse(403, "SignatureDoesNotMatch",
                    "The request signature we calculated does not match the signature you provided."));
        }
    }

    private boolean signedContentTypeMatches(ContainerRequestContext requestContext,
                                             MultivaluedMap<String, String> queryParams,
                                             String signedHeaders, String amzDate, String signature) {
        String credential = maybeUrlDecode(queryParams.getFirst("X-Amz-Credential"));
        String accessKeyId = S3PresignedSignature.accessKeyId(credential);
        String credentialScope = S3PresignedSignature.credentialScope(credential);
        if (accessKeyId == null || credentialScope == null || amzDate == null || signature == null) {
            return false;
        }

        List<String> secrets = secretsFor(accessKeyId);
        if (secrets.isEmpty()) {
            return false;
        }

        Map<String, String> decodedQuery = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                String value = entry.getValue().getFirst();
                if ("X-Amz-Credential".equalsIgnoreCase(entry.getKey())
                        || "X-Amz-SignedHeaders".equalsIgnoreCase(entry.getKey())) {
                    value = maybeUrlDecode(value);
                }
                decodedQuery.put(entry.getKey(), value);
            }
        }
        String encodedQuery = S3PresignedSignature.encodeQuery(decodedQuery);
        URI requestUri = requestContext.getProperty(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY) instanceof URI uri
                ? uri : requestContext.getUriInfo().getRequestUri();
        String encodedPath = S3PresignedSignature.encodeS3Path(requestUri.getRawPath());
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestContext.getHeaders().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                requestHeaders.put(name, values.getFirst());
            }
        });

        for (Map<String, String> headers : contentTypeHeaderVariants(requestHeaders)) {
            for (String host : hostCandidates(requestContext, requestUri)) {
                String canonicalHeaders = S3PresignedSignature.canonicalHeaders(
                        signedHeaders, host, headers);
                for (String secret : secrets) {
                    String expected = S3PresignedSignature.signature(
                            requestContext.getMethod(), encodedPath, encodedQuery,
                            canonicalHeaders, signedHeaders, amzDate, credentialScope, secret);
                    if (S3PresignedSignature.matches(expected, signature)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * RestAssured and some HTTP clients append {@code ; charset=...} to
     * Content-Type. AWS signs the media type the caller passed (e.g.
     * {@code text/plain}). Try both the raw header and the type without
     * parameters so a charset suffix does not mask a real media-type match,
     * while {@code application/json} vs {@code text/plain} still fails.
     */
    private static List<Map<String, String>> contentTypeHeaderVariants(Map<String, String> headers) {
        List<Map<String, String>> variants = new ArrayList<>();
        variants.add(headers);
        String contentType = null;
        String contentTypeKey = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && "content-type".equalsIgnoreCase(entry.getKey())) {
                contentType = entry.getValue();
                contentTypeKey = entry.getKey();
                break;
            }
        }
        if (contentType != null && contentType.contains(";")) {
            Map<String, String> stripped = new LinkedHashMap<>(headers);
            stripped.put(contentTypeKey, contentType.split(";", 2)[0].trim());
            variants.add(stripped);
        }
        return variants;
    }

    private List<String> secretsFor(String accessKeyId) {
        List<String> secrets = new ArrayList<>();
        if (iamService != null) {
            iamService.findSecretKey(accessKeyId).ifPresent(secrets::add);
        }
        String envAccessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String envSecret = System.getenv("AWS_SECRET_ACCESS_KEY");
        if (envSecret != null && !envSecret.isBlank()
                && (envAccessKey == null || envAccessKey.equals(accessKeyId))
                && !secrets.contains(envSecret)) {
            secrets.add(envSecret);
        }
        if ("test".equals(accessKeyId) && !secrets.contains("test")) {
            secrets.add("test");
        }
        if (!secrets.contains(accessKeyId)) {
            secrets.add(accessKeyId);
        }
        return secrets;
    }

    private static List<String> hostCandidates(ContainerRequestContext requestContext, URI requestUri) {
        List<String> hosts = new ArrayList<>();
        addHost(hosts, requestContext.getHeaderString("Host"));
        if (requestUri.getAuthority() != null) {
            addHost(hosts, requestUri.getAuthority());
        }
        return hosts;
    }

    private static String maybeUrlDecode(String value) {
        if (value == null || !value.contains("%")) {
            return value;
        }
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static void addHost(List<String> hosts, String host) {
        if (host != null && !host.isBlank() && !hosts.contains(host)) {
            hosts.add(host.trim());
        }
    }

    private static boolean isCorsPreflight(ContainerRequestContext requestContext) {
        return "OPTIONS".equalsIgnoreCase(requestContext.getMethod())
                && hasText(requestContext.getHeaderString("Origin"))
                && hasText(requestContext.getHeaderString("Access-Control-Request-Method"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean verifySigV4Signature(ContainerRequestContext requestContext,
                                        String signature, String secretKey) {
        try {
            var queryParams = requestContext.getUriInfo().getQueryParameters();
            String credential = queryParams.getFirst("X-Amz-Credential");
            String amzDate = queryParams.getFirst("X-Amz-Date");
            String signedHeaders = queryParams.getFirst("X-Amz-SignedHeaders");

            String decodedCredential = URLDecoder.decode(credential, StandardCharsets.UTF_8);
            String[] credParts = decodedCredential.split("/");
            if (credParts.length < 5) {
                return false;
            }
            String date = credParts[1];
            String region = credParts[2];
            String service = credParts[3];
            String credentialScope = date + "/" + region + "/" + service + "/aws4_request";

            // Build canonical headers from signed headers
            URI requestUri = requestContext.getProperty(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY) instanceof URI uri
                    ? uri
                    : requestContext.getUriInfo().getRequestUri();
            String authority = S3VirtualHostFilter.resolveHost(requestContext.getHeaderString("Host"),
                    requestContext.getHeaderString("X-Forwarded-Host"), requestUri);

            StringBuilder canonicalHeaders = new StringBuilder();
            for (String header : signedHeaders.split(";")) {
                if ("host".equals(header)) {
                    canonicalHeaders.append("host:").append(authority).append("\n");
                } else {
                    String canonicalValue = canonicalizeHeaderValue(requestContext.getHeaderString(header));
                    canonicalHeaders.append(header).append(":").append(canonicalValue).append("\n");
                }
            }

            // Canonical request
            String path = requestUri.getRawPath();
            String canonicalQueryString = buildCanonicalQueryString(queryParams);
            String payloadHash = requestContext.getHeaderString("x-amz-content-sha256");
            if (payloadHash == null) {
                payloadHash = queryParams.getFirst("X-Amz-Content-Sha256");
            }
            if (payloadHash == null) {
                payloadHash = "UNSIGNED-PAYLOAD";
            }
            String canonicalRequest = requestContext.getMethod() + "\n"
                    + path + "\n"
                    + canonicalQueryString + "\n"
                    + canonicalHeaders + "\n"
                    + signedHeaders + "\n"
                    + payloadHash;

            // String to sign
            String stringToSign = "AWS4-HMAC-SHA256\n"
                    + amzDate + "\n"
                    + credentialScope + "\n"
                    + SigV4RequestValidator.sha256Hex(canonicalRequest);

            // Derive signing key and compute expected signature
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secretKey, date, region, service);
            String expectedSignature = SigV4RequestValidator.hexEncode(
                    SigV4RequestValidator.hmacSha256(signingKey, stringToSign));

            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            LOG.debugv("Presigned SigV4 signature verification failed: {0}", e.getMessage());
            return false;
        }
    }

    private String resolveSecretKey(String accessKeyId) {
        return resolveSecretKey(accessKeyId, null);
    }

    private String resolveSecretKey(String accessKeyId, String sessionToken) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return LEGACY_SECRET_KEY;
        }
        if (iamService != null) {
            Optional<String> registered = iamService.findSecretKey(accessKeyId, sessionToken);
            if (registered.isPresent()) {
                return registered.get();
            }
            // Deliberately no fallback for a bare 12-digit account ID here: AccountResolver
            // reads a 12-digit access key ID as the request's account directly, so trusting an
            // unregistered numeric key paired with the well-known "test" secret would let any
            // client forge a signed request for an arbitrary account under S3 auth enforcement.
            // A launched container's owning-account placeholder credentials (see
            // LaunchedContainerAwsEnv) are therefore not honored by this enforced path either;
            // they only work where no signature is required (auth enforcement disabled).
            return null;
        }
        return null;
    }

    /**
     * Builds the SigV4 canonical query string from the framework's decoded query parameters:
     * URI-encode each name and value, exclude {@code X-Amz-Signature}, and sort by encoded
     * name and then by encoded value. Package-private for unit testing.
     */
    static String buildCanonicalQueryString(MultivaluedMap<String, String> decodedParams) {
        List<String[]> encodedParams = new ArrayList<>();
        for (var entry : decodedParams.entrySet()) {
            if ("X-Amz-Signature".equals(entry.getKey())) {
                continue;
            }
            String encodedName = awsUriEncode(entry.getKey());
            for (String value : entry.getValue()) {
                encodedParams.add(new String[]{encodedName, awsUriEncode(value)});
            }
        }
        encodedParams.sort(Comparator.comparing((String[] p) -> p[0]).thenComparing(p -> p[1]));
        return encodedParams.stream()
                .map(p -> p[0] + "=" + p[1])
                .collect(Collectors.joining("&"));
    }

    /**
     * Canonicalizes a signed header value per SigV4: trim, then collapse sequential spaces to
     * a single space. Package-private for unit testing.
     */
    static String canonicalizeHeaderValue(String value) {
        return value == null ? "" : value.trim().replaceAll(" +", " ");
    }

    static String awsUriEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }

    static Response errorResponse(int status, String code, String message) {
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", code)
                  .elem("Message", message)
                .end("Error")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}

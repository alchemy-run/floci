package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Provider
public class PreSignedUrlFilter implements ContainerRequestFilter {

    private final PreSignedUrlGenerator presignGenerator;
    private final Instance<IamService> iamService;

    @Inject
    public PreSignedUrlFilter(PreSignedUrlGenerator presignGenerator, Instance<IamService> iamService) {
        this.presignGenerator = presignGenerator;
        this.iamService = iamService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        var queryParams = requestContext.getUriInfo().getQueryParameters();

        // Only process if this is a pre-signed URL request
        String algorithm = queryParams.getFirst("X-Amz-Algorithm");
        if (algorithm == null) {
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

        // Check expiration
        if (presignGenerator.isExpired(amzDate, expires)) {
            requestContext.abortWith(errorResponse(403, "AccessDenied",
                    "Request has expired."));
            return;
        }

        // Optionally verify Floci's own HMAC (if validateSignatures is enabled).
        if (presignGenerator.shouldValidateSignatures()) {
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
        if (S3PresignedSignature.signedHeadersInclude(signedHeaders, "content-type")
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
        URI requestUri = requestContext.getUriInfo().getRequestUri();
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
        if (iamService != null && !iamService.isUnsatisfied()) {
            iamService.get().findSecretKey(accessKeyId).ifPresent(secrets::add);
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

    private Response errorResponse(int status, String code, String message) {
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

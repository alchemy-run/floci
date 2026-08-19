package io.github.hectorvent.floci.services.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AWS SigV4 query-string signing for S3 presigned URLs, matching aws4fetch /
 * distilled {@code presignS3Url}. Used to enforce signed headers (especially
 * {@code content-type}) even when {@code floci.auth.validate-signatures} is off.
 */
final class S3PresignedSignature {

    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private S3PresignedSignature() {
    }

    static boolean signedHeadersInclude(String signedHeaders, String headerName) {
        if (signedHeaders == null || signedHeaders.isBlank() || headerName == null) {
            return false;
        }
        String needle = headerName.toLowerCase(Locale.ROOT);
        for (String part : signedHeaders.split(";")) {
            if (needle.equals(part.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String encodeQuery(Map<String, String> decodedParams) {
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : decodedParams.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if ("X-Amz-Signature".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            pairs.add(new String[]{
                    rfc3986(percentEncode(entry.getKey())),
                    rfc3986(percentEncode(entry.getValue() == null ? "" : entry.getValue()))
            });
        }
        pairs.sort(Comparator
                .comparing((String[] pair) -> pair[0])
                .thenComparing(pair -> pair[1]));
        StringBuilder query = new StringBuilder();
        for (String[] pair : pairs) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(pair[0]).append('=').append(pair[1]);
        }
        return query.toString();
    }

    static String encodeS3Path(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath.replace("+", " "), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = rawPath;
        }
        return rfc3986(percentEncode(decoded).replace("%2F", "/"));
    }

    static String canonicalHeaders(String signedHeaders, String host, Map<String, String> requestHeaders) {
        StringBuilder headers = new StringBuilder();
        for (String name : signedHeaders.toLowerCase(Locale.ROOT).split(";")) {
            String header = name.trim();
            if (header.isEmpty()) {
                continue;
            }
            String value = "host".equals(header)
                    ? (host == null ? "" : host)
                    : headerValue(requestHeaders, header);
            if (value == null) {
                value = "";
            }
            value = value.trim().replaceAll("\\s+", " ");
            if (!headers.isEmpty()) {
                headers.append('\n');
            }
            headers.append(header).append(':').append(value);
        }
        return headers.toString();
    }

    static String signature(String method, String encodedPath, String encodedQuery,
                            String canonicalHeaders, String signedHeaders,
                            String amzDate, String credentialScope, String secretKey) {
        String canonicalRequest = method.toUpperCase(Locale.ROOT) + "\n"
                + encodedPath + "\n"
                + encodedQuery + "\n"
                + canonicalHeaders + "\n\n"
                + signedHeaders.toLowerCase(Locale.ROOT) + "\n"
                + UNSIGNED_PAYLOAD;
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);
        String[] scope = credentialScope.split("/");
        byte[] signingKey = deriveSigningKey(secretKey, scope[0], scope[1], scope[2]);
        return hexEncode(hmacSha256(signingKey, stringToSign));
    }

    static boolean matches(String expectedSignature, String actualSignature) {
        if (expectedSignature == null || actualSignature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                actualSignature.getBytes(StandardCharsets.UTF_8));
    }

    static String credentialScope(String credential) {
        if (credential == null) {
            return null;
        }
        int slash = credential.indexOf('/');
        if (slash < 0 || slash == credential.length() - 1) {
            return null;
        }
        return credential.substring(slash + 1);
    }

    static String accessKeyId(String credential) {
        if (credential == null) {
            return null;
        }
        int slash = credential.indexOf('/');
        return slash < 0 ? credential : credential.substring(0, slash);
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String rfc3986(String urlEncoded) {
        return urlEncoded
                .replace("!", "%21")
                .replace("'", "%27")
                .replace("(", "%28")
                .replace(")", "%29")
                .replace("*", "%2A");
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            return hexEncode(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute SHA-256", e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}

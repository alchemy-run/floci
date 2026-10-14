package io.github.hectorvent.floci.services.msk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds MSK IAM SASL credentials the way the client libraries do: a SigV4 query presign of
 * {@code GET /?Action=kafka-cluster:Connect} for the {@code kafka-cluster} service, signing only
 * the {@code host} header over an empty body.
 */
final class MskIamSigning {

    static final String ACCESS_KEY_ID = "AKIAMSKTESTKEY000001";
    static final String SECRET_KEY = "msk-test-secret-key";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private MskIamSigning() {
    }

    /** The presign query parameters, including {@code X-Amz-Signature}, keyed by parameter name. */
    static Map<String, String> presign(String host, String region, Instant signedAt, String action,
                                       String sessionToken, String secretKey) {
        String amzDate = AMZ_DATE.format(signedAt);
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/kafka-cluster/aws4_request";
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("Action", action);
        parameters.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        parameters.put("X-Amz-Credential", ACCESS_KEY_ID + "/" + scope);
        parameters.put("X-Amz-Date", amzDate);
        parameters.put("X-Amz-Expires", "900");
        parameters.put("X-Amz-SignedHeaders", "host");
        if (sessionToken != null) {
            parameters.put("X-Amz-Security-Token", sessionToken);
        }
        String canonicalQuery = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .sorted()
                .collect(Collectors.joining("&"));
        String canonicalRequest = "GET\n/\n" + canonicalQuery + "\nhost:" + host + "\n\nhost\n" + EMPTY_SHA256;
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "kafka-cluster");
        key = hmac(key, "aws4_request");
        parameters.put("X-Amz-Signature", HexFormat.of().formatHex(hmac(key, stringToSign)));
        return parameters;
    }

    /** An AWS_MSK_IAM payload for a connection to {@code brokerHost}. */
    static byte[] iamPayload(String brokerHost, Map<String, String> presigned) {
        StringBuilder json = new StringBuilder("{\"version\":\"2020_10_22\",\"host\":\"").append(brokerHost)
                .append("\",\"user-agent\":\"aws-msk-iam-auth/2.0.0\"");
        presigned.forEach((name, value) -> json.append(",\"").append(name.toLowerCase(Locale.ROOT))
                .append("\":\"").append(value).append('"'));
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** An OAUTHBEARER client-first message carrying the presigned URL, User-Agent appended after signing. */
    static byte[] oauthBearerMessage(String region, Map<String, String> presigned) {
        String query = presigned.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String url = "https://kafka." + region + ".amazonaws.com/?" + query
                + "&User-Agent=" + encode("aws-msk-iam-sasl-signer-js/1.0.0");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));
        return ("n,,\u0001auth=Bearer " + token + "\u0001\u0001").getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append(String.format("%%%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Verifies the SASL credentials an Amazon MSK client presents on the SASL/IAM listener.
 *
 * <p>Both mechanisms MSK accepts carry a SigV4 presigned request for the
 * {@code kafka-cluster:Connect} action, signed for the {@code kafka-cluster} service:
 * <ul>
 *   <li>{@code AWS_MSK_IAM} (aws-msk-iam-auth): a JSON object holding the lowercased presign query
 *       parameters and the broker {@code host} the client connected to.</li>
 *   <li>{@code OAUTHBEARER} (aws-msk-iam-sasl-signer): a GS2 client-first message whose bearer
 *       token is the base64url of the presigned {@code https://kafka.<region>.amazonaws.com/} URL,
 *       with a {@code User-Agent} parameter appended after signing.</li>
 * </ul>
 * The signature is recomputed with the caller's secret key, so only a holder of the credential
 * authenticates. The empty-body payload hash is what the signer libraries use; the unsigned
 * payload form is accepted as the other valid presign construction.
 */
public final class MskIamAuthenticator {

    private static final Logger LOG = Logger.getLogger(MskIamAuthenticator.class);

    static final String CONNECT_ACTION = "kafka-cluster:Connect";
    static final String SIGNING_SERVICE = "kafka-cluster";
    static final String IAM_PAYLOAD_VERSION = "2020_10_22";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    /** Clock skew tolerated for a signature dated in the future, as AWS allows. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(15);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** Keys of the AWS_MSK_IAM payload, mapped to the query parameter names they were signed as. */
    private static final Map<String, String> IAM_PAYLOAD_PARAMETERS = Map.of(
            "action", "Action",
            "x-amz-algorithm", "X-Amz-Algorithm",
            "x-amz-credential", "X-Amz-Credential",
            "x-amz-date", "X-Amz-Date",
            "x-amz-expires", "X-Amz-Expires",
            "x-amz-security-token", "X-Amz-Security-Token",
            "x-amz-signedheaders", "X-Amz-SignedHeaders");

    /** Resolves the secret key for an access key id and, for temporary credentials, its session token. */
    @FunctionalInterface
    public interface SecretLookup {
        Optional<String> secretFor(String accessKeyId, String sessionToken);
    }

    private final SecretLookup secrets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MskIamAuthenticator(SecretLookup secrets, ObjectMapper objectMapper, Clock clock) {
        this.secrets = secrets;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Authenticates an {@code AWS_MSK_IAM} payload.
     *
     * @return the access key id the payload was signed with, empty when it does not authenticate
     */
    public Optional<String> authenticateIamPayload(byte[] payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()
                    || !IAM_PAYLOAD_VERSION.equals(node.path("version").asText(null))) {
                return Optional.empty();
            }
            String host = node.path("host").asText(null);
            String signature = node.path("x-amz-signature").asText(null);
            Map<String, String> parameters = new TreeMap<>();
            for (Map.Entry<String, String> entry : IAM_PAYLOAD_PARAMETERS.entrySet()) {
                JsonNode value = node.get(entry.getKey());
                if (value != null && !value.isNull()) {
                    parameters.put(entry.getValue(), value.asText());
                }
            }
            return verify(host, parameters, signature);
        } catch (Exception e) {
            LOG.debugv("AWS_MSK_IAM payload rejected: {0}", SigV4RequestValidator.sanitizeForLog(e.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * Authenticates an {@code OAUTHBEARER} client-first message.
     *
     * @return the access key id the token was signed with, empty when it does not authenticate
     */
    public Optional<String> authenticateOAuthBearer(byte[] clientFirstMessage) {
        try {
            String token = bearerToken(new String(clientFirstMessage, StandardCharsets.UTF_8));
            if (token == null) {
                return Optional.empty();
            }
            URI url = URI.create(new String(Base64.getUrlDecoder().decode(padBase64(token)), StandardCharsets.UTF_8));
            String rawQuery = url.getRawQuery();
            if (url.getHost() == null || rawQuery == null) {
                return Optional.empty();
            }
            String host = url.getPort() > 0 ? url.getHost() + ":" + url.getPort() : url.getHost();
            Map<String, String> parameters = new TreeMap<>();
            String signature = null;
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String name = percentDecode(eq >= 0 ? pair.substring(0, eq) : pair);
                String value = eq >= 0 ? percentDecode(pair.substring(eq + 1)) : "";
                if ("X-Amz-Signature".equals(name)) {
                    signature = value;
                } else if (!"User-Agent".equals(name)) {
                    parameters.put(name, value);
                }
            }
            return verify(host, parameters, signature);
        } catch (Exception e) {
            LOG.debugv("OAUTHBEARER token rejected: {0}", SigV4RequestValidator.sanitizeForLog(e.getMessage()));
            return Optional.empty();
        }
    }

    /** The bearer token of a GS2 client-first message ({@code n,,\u0001auth=Bearer <token>\u0001\u0001}). */
    static String bearerToken(String clientFirstMessage) {
        for (String part : clientFirstMessage.split("\u0001")) {
            if (part.startsWith("auth=")) {
                String value = part.substring("auth=".length()).trim();
                if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                    String token = value.substring("Bearer ".length()).trim();
                    return token.isEmpty() ? null : token;
                }
                return null;
            }
        }
        return null;
    }

    private Optional<String> verify(String host, Map<String, String> parameters, String signature) throws Exception {
        if (host == null || host.isBlank() || signature == null || signature.isBlank()) {
            return Optional.empty();
        }
        if (!CONNECT_ACTION.equals(parameters.get("Action"))
                || !ALGORITHM.equals(parameters.get("X-Amz-Algorithm"))
                || !"host".equals(parameters.get("X-Amz-SignedHeaders"))) {
            LOG.debug("MSK IAM credential is not a kafka-cluster:Connect presign signing only the host header");
            return Optional.empty();
        }
        String credential = parameters.get("X-Amz-Credential");
        String amzDate = parameters.get("X-Amz-Date");
        String expires = parameters.get("X-Amz-Expires");
        if (credential == null || amzDate == null || expires == null) {
            return Optional.empty();
        }
        Instant signedAt = Instant.from(AMZ_DATE.parse(amzDate));
        Instant now = clock.instant();
        if (now.isAfter(signedAt.plusSeconds(Long.parseLong(expires)))
                || now.plus(MAX_CLOCK_SKEW).isBefore(signedAt)) {
            LOG.debug("MSK IAM credential is expired or dated in the future");
            return Optional.empty();
        }
        String[] scope = credential.split("/");
        if (scope.length != 5 || !SIGNING_SERVICE.equals(scope[3]) || !"aws4_request".equals(scope[4])) {
            return Optional.empty();
        }
        String accessKeyId = scope[0];
        Optional<String> secret = secrets.secretFor(accessKeyId, parameters.get("X-Amz-Security-Token"));
        if (secret.isEmpty()) {
            LOG.debugv("MSK IAM credential names an unknown access key {0}",
                    SigV4RequestValidator.sanitizeForLog(accessKeyId));
            return Optional.empty();
        }
        String canonicalQuery = parameters.entrySet().stream()
                .map(entry -> Map.entry(encode(entry.getKey()), encode(entry.getValue())))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        String credentialScope = scope[1] + "/" + scope[2] + "/" + scope[3] + "/" + scope[4];
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secret.get(), scope[1], scope[2], scope[3]);
        for (String payloadHash : List.of(EMPTY_PAYLOAD_SHA256, UNSIGNED_PAYLOAD)) {
            String canonicalRequest = "GET\n/\n" + canonicalQuery + "\n"
                    + "host:" + host.trim() + "\n\n"
                    + "host\n"
                    + payloadHash;
            String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + credentialScope + "\n"
                    + SigV4RequestValidator.sha256Hex(canonicalRequest);
            String expected = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                return Optional.of(accessKeyId);
            }
        }
        LOG.debugv("MSK IAM signature mismatch for access key {0}", SigV4RequestValidator.sanitizeForLog(accessKeyId));
        return Optional.empty();
    }

    /** SigV4 URI encoding: every byte outside {@code A-Za-z0-9-_.~} as an uppercase {@code %XX}. */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /** Decodes {@code %XX} escapes only: a {@code +} in a signed query value is a literal plus. */
    static String percentDecode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int high = Character.digit(bytes[i + 1], 16);
                int low = Character.digit(bytes[i + 2], 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) | low);
                    i += 2;
                    continue;
                }
            }
            out.write(bytes[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String padBase64(String token) {
        int remainder = token.length() % 4;
        return remainder == 0 ? token : token + "=".repeat(4 - remainder);
    }
}

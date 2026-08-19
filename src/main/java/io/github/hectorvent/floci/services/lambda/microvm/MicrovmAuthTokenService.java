package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * MicroVM endpoint auth tokens. Real AWS mints an opaque token whose parts the
 * client sends as request headers to the MicroVM endpoint; Floci's equivalent
 * is an HMAC-signed statement of {@code microvmId | expiry | allowed ports}
 * under the single header {@link #HEADER}. The signing secret is per-process:
 * a Floci restart invalidates outstanding tokens, which is acceptable for the
 * short-lived tokens the bindings mint (minutes).
 */
@ApplicationScoped
public class MicrovmAuthTokenService {

    private static final Logger LOG = Logger.getLogger(MicrovmAuthTokenService.class);

    /** The header name clients send the token under (mirrored into {@code authToken}). */
    public static final String HEADER = "X-Aws-Proxy-Auth";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec secret;

    public MicrovmAuthTokenService() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.secret = new SecretKeySpec(key, HMAC_ALGORITHM);
    }

    /**
     * Mints a token authorizing access to {@code microvmId} for the given ports.
     * {@code allowedPorts} entries follow the API's {@code PortSpecification}
     * union: {@code {port}}, {@code {range:{startPort,endPort}}}, or
     * {@code {allPorts:{}}}.
     */
    public Map<String, Object> createToken(String microvmId, Number expirationInMinutes,
                                           List<Map<String, Object>> allowedPorts) {
        if (expirationInMinutes == null || expirationInMinutes.longValue() <= 0) {
            throw new AwsException("ValidationException", "expirationInMinutes must be positive", 400);
        }
        long expiresAtEpochSeconds = System.currentTimeMillis() / 1000
                + expirationInMinutes.longValue() * 60;
        String ports = canonicalPorts(allowedPorts);
        String payload = microvmId + "|" + expiresAtEpochSeconds + "|" + ports;
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + "|" + sign(payload)).getBytes(StandardCharsets.UTF_8));
        return Map.of("authToken", Map.of(HEADER, token));
    }

    /**
     * Validates a token for a request hitting {@code microvmId} on {@code port}.
     * Throws {@link AwsException} (403) on any failure.
     */
    public void validate(String token, String microvmId, int port) {
        if (token == null || token.isBlank()) {
            throw forbidden("Missing MicroVM auth token");
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw forbidden("Malformed MicroVM auth token");
        }
        int lastSep = decoded.lastIndexOf('|');
        if (lastSep < 0) {
            throw forbidden("Malformed MicroVM auth token");
        }
        String payload = decoded.substring(0, lastSep);
        String signature = decoded.substring(lastSep + 1);
        if (!constantTimeEquals(sign(payload), signature)) {
            throw forbidden("Invalid MicroVM auth token signature");
        }
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) {
            throw forbidden("Malformed MicroVM auth token");
        }
        if (!parts[0].equals(microvmId)) {
            throw forbidden("MicroVM auth token does not match this MicroVM");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw forbidden("Malformed MicroVM auth token");
        }
        if (System.currentTimeMillis() / 1000 > expiresAt) {
            throw forbidden("MicroVM auth token expired");
        }
        if (!portAllowed(parts[2], port)) {
            throw forbidden("MicroVM auth token does not allow port " + port);
        }
    }

    private static AwsException forbidden(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    // Canonical ports encoding: comma-separated "8080", "8100-8200", or "*".
    private static String canonicalPorts(List<Map<String, Object>> allowedPorts) {
        if (allowedPorts == null || allowedPorts.isEmpty()) {
            throw new AwsException("ValidationException", "allowedPorts is required", 400);
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> spec : allowedPorts) {
            if (spec.get("port") instanceof Number n) {
                parts.add(String.valueOf(n.intValue()));
            } else if (spec.get("range") instanceof Map<?, ?> range
                    && range.get("startPort") instanceof Number start
                    && range.get("endPort") instanceof Number end) {
                parts.add(start.intValue() + "-" + end.intValue());
            } else if (spec.containsKey("allPorts")) {
                parts.add("*");
            } else {
                throw new AwsException("ValidationException",
                        "Invalid allowedPorts entry: " + spec, 400);
            }
        }
        return String.join(",", parts);
    }

    private static boolean portAllowed(String canonical, int port) {
        for (String part : canonical.split(",")) {
            if ("*".equals(part)) {
                return true;
            }
            int dash = part.indexOf('-');
            if (dash > 0) {
                try {
                    int start = Integer.parseInt(part.substring(0, dash));
                    int end = Integer.parseInt(part.substring(dash + 1));
                    if (port >= start && port <= end) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // malformed segment can never match
                }
            } else if (part.equals(String.valueOf(port))) {
                return true;
            }
        }
        return false;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secret);
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOG.error("Failed to sign MicroVM auth token", e);
            throw new AwsException("ServiceException", "Token signing failed", 500);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(ba, bb);
    }
}

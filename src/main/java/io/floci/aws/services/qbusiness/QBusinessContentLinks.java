package io.floci.aws.services.qbusiness;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signed links Floci serves for Q Business: presigned document content ({@code GetDocumentContent}), Q Apps files
 * and exports, and anonymous web experience sessions. A link carries what it opens and when it expires, signed with
 * a key that lives as long as the emulator does.
 */
final class QBusinessContentLinks {

    static final String PATH = "/_qbusiness/content/";
    static final String WEB = "/_qbusiness/web/";
    private static final byte[] KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(KEY);
    }

    private QBusinessContentLinks() {
    }

    private static String mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(
                    StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token for {@code parts}, valid for {@code seconds}. */
    static String token(long seconds, String... parts) {
        long expires = System.currentTimeMillis() / 1000 + seconds;
        String payload = expires + "|" + String.join("|", parts);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(
                StandardCharsets.UTF_8));
        return encoded + "." + mac(payload);
    }

    /** The parts a token names, or null when it's forged or expired. */
    static String[] open(String token) {
        int dot = token.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!mac(payload).equals(token.substring(dot + 1))) {
            return null;
        }
        String[] parts = payload.split("\\|", -1);
        if (Long.parseLong(parts[0]) < System.currentTimeMillis() / 1000) {
            return null;
        }
        String[] rest = new String[parts.length - 1];
        System.arraycopy(parts, 1, rest, 0, rest.length);
        return rest;
    }

    static String sign(String account, String region, String appId, String indexId, String documentId, String format,
                       long seconds) {
        return token(seconds, "document", account, region, appId, indexId, documentId, format);
    }

    static String signSession(String account, String region, String appId, String webId, long seconds) {
        return token(seconds, "session", account, region, appId, webId);
    }

    static String webEndpoint(String base, String region, String webId) {
        return base + WEB + region + "/" + webId + "/";
    }
}

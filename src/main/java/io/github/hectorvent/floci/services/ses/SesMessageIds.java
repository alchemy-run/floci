package io.github.hectorvent.floci.services.ses;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SES message ids, e.g. {@code 0100018c7f3b1f2a-9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f-000000}:
 * a 16-hex-digit prefix ({@code 01000} followed by the send time in epoch milliseconds), a UUID,
 * and a six-digit sequence suffix.
 */
final class SesMessageIds {

    private static final Pattern MESSAGE_ID = Pattern.compile(
            "[0-9a-f]{16}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]{6}",
            Pattern.CASE_INSENSITIVE);

    private SesMessageIds() {
    }

    static String newMessageId() {
        return String.format("01000%011x", System.currentTimeMillis() & 0xFFFFFFFFFFFL)
                + "-" + UUID.randomUUID() + "-000000";
    }

    static boolean isWellFormed(String messageId) {
        return messageId != null && MESSAGE_ID.matcher(messageId).matches();
    }
}

package io.github.hectorvent.floci.services.cloudtrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic prompt-to-SQL generation for CloudTrail Lake GenerateQuery.
 *
 * <p>Recognises a fixed set of question shapes about recorded API activity (counts, top-N
 * groupings, and recent-event listings, optionally filtered by time window, event source, and
 * errors). A prompt outside those shapes yields no statement, which the service reports as the
 * same {@code GenerateResponseException} CloudTrail returns when it cannot generate a query.
 */
final class CloudTrailLakeQueryGenerator {
    private static final Pattern WINDOW =
            Pattern.compile("\\b(?:last|past|previous)\\s+(?:(\\d{1,4})\\s+)?(minute|hour|day|week|month)s?\\b");
    private static final Pattern TOP = Pattern.compile("\\btop\\s+(\\d{1,3})\\b");
    private static final Pattern SOURCE = Pattern.compile("\\b([a-z0-9-]+\\.amazonaws\\.com)\\b");
    private static final List<String> SUBJECTS = List.of("event", "api", "call", "activity", "request",
            "error", "user", "cloudtrail", "action", "operation", "log");

    private CloudTrailLakeQueryGenerator() {}

    static Optional<String> generate(String prompt, String eventDataStoreId) {
        String text = prompt.toLowerCase(Locale.ROOT);
        if (!containsAny(text, SUBJECTS)) {
            return Optional.empty();
        }
        List<String> filters = new ArrayList<>();
        Matcher window = WINDOW.matcher(text);
        if (window.find()) {
            int amount = window.group(1) == null ? 1 : Integer.parseInt(window.group(1));
            String unit = window.group(2);
            if ("week".equals(unit)) {
                amount *= 7;
                unit = "day";
            }
            if (amount < 1) {
                return Optional.empty();
            }
            filters.add("eventTime > now() - INTERVAL '" + amount + "' " + unit.toUpperCase(Locale.ROOT));
        } else if (text.contains("today")) {
            filters.add("eventTime >= date_trunc('day', now())");
        }
        Matcher source = SOURCE.matcher(text);
        if (source.find()) {
            filters.add("eventSource = '" + source.group(1) + "'");
        }
        if (containsAny(text, List.of("error", "failed", "failure", "denied"))) {
            filters.add("errorCode IS NOT NULL");
        }
        String where = filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters);
        String from = " FROM " + eventDataStoreId;

        Matcher top = TOP.matcher(text);
        boolean hasTop = top.find();
        int limit = hasTop ? Math.max(1, Integer.parseInt(top.group(1))) : 10;
        boolean ranked = hasTop || containsAny(text, List.of("most", "frequent", "common"));
        boolean counted = containsAny(text, List.of("how many", "count", "number of", "total"));
        String group = null;
        String alias = null;
        if (containsAny(text, List.of("by user", "per user", "which user", "users", "who "))) {
            group = "userIdentity.arn";
            alias = "userArn";
        } else if (containsAny(text, List.of("event name", "api call", "by event", "per event", "which event",
                "operation", "action"))) {
            group = "eventName";
            alias = "eventName";
        } else if (containsAny(text, List.of("by source", "per source", "by service", "per service",
                "event source", "which service"))) {
            group = "eventSource";
            alias = "eventSource";
        }
        if (group != null && (ranked || counted)) {
            return Optional.of("SELECT " + group + " AS " + alias + ", COUNT(*) AS eventCount" + from + where
                    + " GROUP BY " + group + " ORDER BY eventCount DESC LIMIT " + limit);
        }
        if (counted) {
            return Optional.of("SELECT COUNT(*) AS eventCount" + from + where);
        }
        if (containsAny(text, List.of("list", "show", "recent", "latest", "which", "what", "find", "display"))) {
            return Optional.of("SELECT eventTime, eventSource, eventName, userIdentity.arn AS userArn, errorCode"
                    + from + where + " ORDER BY eventTime DESC LIMIT " + (hasTop ? limit : 100));
        }
        return Optional.empty();
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A metric's tumbling window: an interval between a minute and a week ({@code 5m}, {@code 1h}, {@code 1w},
 * {@code PT5M}) and an offset that moves its boundaries (a duration such as {@code 18h} or {@code PT18H}, a time of
 * day such as {@code 00:03:00}, or a time stamp with its time zone such as {@code 2021-07-23T18:00-08}). Windows
 * start on boundaries from the epoch, or from Monday for whole weeks; a window's start is exclusive and its end
 * inclusive, and the metric's data point is placed at its end.
 */
record SiteWiseWindows(long intervalMillis, long anchorMillis) {

    private static final Pattern SHORT = Pattern.compile("(\\d+)([smhdw])");
    private static final long MONDAY = Duration.ofDays(4).toMillis();

    static SiteWiseWindows parse(JsonNode tumbling) {
        String interval = tumbling.path("interval").asText(null);
        if (interval == null) {
            throw SiteWiseStore.invalid("A metric's window has a tumbling interval.");
        }
        long millis = duration(interval, false);
        if (millis < Duration.ofMinutes(1).toMillis() || millis > Duration.ofDays(7).toMillis()) {
            throw SiteWiseStore.invalid("The interval " + interval + " isn't between 1 minute and 1 week.");
        }
        long anchor = millis % Duration.ofDays(7).toMillis() == 0 ? MONDAY : 0;
        String offset = tumbling.path("offset").asText(null);
        if (offset != null) {
            anchor = offset(offset.trim(), anchor);
        }
        return new SiteWiseWindows(millis, anchor);
    }

    private static long duration(String text, boolean seconds) {
        Matcher matcher = SHORT.matcher(text);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "s" -> {
                    if (!seconds) {
                        throw SiteWiseStore.invalid("An interval can't be in seconds: " + text);
                    }
                    yield Duration.ofSeconds(amount).toMillis();
                }
                case "m" -> Duration.ofMinutes(amount).toMillis();
                case "h" -> Duration.ofHours(amount).toMillis();
                case "d" -> Duration.ofDays(amount).toMillis();
                default -> Duration.ofDays(7 * amount).toMillis();
            };
        }
        try {
            return Duration.parse(text).toMillis();
        } catch (DateTimeParseException e) {
            throw SiteWiseStore.invalid("The duration " + text + " isn't valid.");
        }
    }

    private static long offset(String offset, long anchor) {
        if (offset.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
            return anchor + LocalTime.parse(offset.length() == 4 ? "0" + offset : offset).toNanoOfDay() / 1_000_000;
        }
        if (offset.contains("T") && Character.isDigit(offset.charAt(0)) && offset.indexOf('-') == 4) {
            String text = offset;
            Matcher zone = Pattern.compile("(.*[T]\\d{2}:\\d{2}(?::\\d{2})?)([+-]\\d{2})$").matcher(text);
            if (zone.matches()) {
                text = zone.group(1) + zone.group(2) + ":00";
            }
            try {
                return OffsetDateTime.parse(text).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (DateTimeParseException again) {
                    throw SiteWiseStore.invalid("The offset " + offset + " isn't valid.");
                }
            }
        }
        return anchor + duration(offset, true);
    }

    /** The end of the window that holds a time: the first boundary at or after it. */
    long endOf(long millis) {
        long since = millis - anchorMillis;
        long windows = Math.floorDiv(since + intervalMillis - 1, intervalMillis);
        return anchorMillis + windows * intervalMillis;
    }
}

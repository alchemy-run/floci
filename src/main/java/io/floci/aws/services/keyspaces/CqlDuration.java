package io.floci.aws.services.keyspaces;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CQL durations: months, days and nanoseconds, written as {@code 1y2mo3w4d5h6m7s8ms9us10ns} (any subset, optionally
 * negative), ISO 8601 ({@code P1Y2M3DT4H5M6S} or {@code P3W}), and stored in that first form.
 */
final class CqlDuration {

    private static final Pattern UNIT = Pattern.compile("(\\d+)(y|mo|w|d|h|m|s|ms|us|µs|ns)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile(
            "P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?",
            Pattern.CASE_INSENSITIVE);

    private CqlDuration() {
    }

    /** {@code [months, days, nanoseconds]}. */
    static long[] parse(String text) {
        String s = text.strip();
        boolean negative = s.startsWith("-");
        if (negative) {
            s = s.substring(1);
        }
        long months = 0;
        long days = 0;
        long nanos = 0;
        Matcher iso = ISO.matcher(s);
        if (s.toUpperCase(Locale.ROOT).startsWith("P") && iso.matches()) {
            months = num(iso.group(1)) * 12 + num(iso.group(2));
            days = num(iso.group(3)) * 7 + num(iso.group(4));
            nanos = (num(iso.group(5)) * 3600 + num(iso.group(6)) * 60 + num(iso.group(7))) * 1_000_000_000L;
        } else {
            Matcher m = UNIT.matcher(s);
            int end = 0;
            while (m.find()) {
                if (m.start() != end) {
                    throw new IllegalArgumentException("Invalid duration " + text);
                }
                end = m.end();
                long n = Long.parseLong(m.group(1));
                switch (m.group(2).toLowerCase(Locale.ROOT)) {
                    case "y" -> months += n * 12;
                    case "mo" -> months += n;
                    case "w" -> days += n * 7;
                    case "d" -> days += n;
                    case "h" -> nanos += n * 3_600_000_000_000L;
                    case "m" -> nanos += n * 60_000_000_000L;
                    case "s" -> nanos += n * 1_000_000_000L;
                    case "ms" -> nanos += n * 1_000_000L;
                    case "us", "µs" -> nanos += n * 1_000L;
                    default -> nanos += n;
                }
            }
            if (end != s.length() || s.isEmpty()) {
                throw new IllegalArgumentException("Invalid duration " + text);
            }
        }
        return negative ? new long[] {-months, -days, -nanos} : new long[] {months, days, nanos};
    }

    private static long num(String group) {
        return group == null ? 0 : Long.parseLong(group);
    }

    static String format(long months, long days, long nanos) {
        if (months == 0 && days == 0 && nanos == 0) {
            return "0s";
        }
        boolean negative = months < 0 || days < 0 || nanos < 0;
        months = Math.abs(months);
        days = Math.abs(days);
        nanos = Math.abs(nanos);
        StringBuilder out = new StringBuilder(negative ? "-" : "");
        append(out, months / 12, "y");
        append(out, months % 12, "mo");
        append(out, days, "d");
        append(out, nanos / 3_600_000_000_000L, "h");
        nanos %= 3_600_000_000_000L;
        append(out, nanos / 60_000_000_000L, "m");
        nanos %= 60_000_000_000L;
        append(out, nanos / 1_000_000_000L, "s");
        nanos %= 1_000_000_000L;
        append(out, nanos / 1_000_000L, "ms");
        nanos %= 1_000_000L;
        append(out, nanos / 1_000L, "us");
        append(out, nanos % 1_000L, "ns");
        return out.toString();
    }

    private static void append(StringBuilder out, long n, String unit) {
        if (n != 0) {
            out.append(n).append(unit);
        }
    }
}

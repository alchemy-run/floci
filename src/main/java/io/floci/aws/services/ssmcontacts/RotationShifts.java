package io.floci.aws.services.ssmcontacts;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shifts of an on-call rotation, computed from its recurrence: hand-off times (daily, weekly or monthly) mark
 * the boundaries, each shift spans {@code RecurrenceMultiplier} of them, and {@code NumberOfOnCalls} members are
 * on call at once, taken from the member list in turn. Shift coverage limits each day to its covered hours, and
 * overrides put other contacts on call for their window.
 */
final class RotationShifts {

    /** A contiguous span with the contacts on call, and the contacts an override replaced, if any. */
    record Shift(Instant start, Instant end, List<String> contacts, List<String> overridden) {
    }

    /** An override: other contacts on call between two instants. */
    record Override(Instant start, Instant end, List<String> contacts) {
    }

    private static final int MAX_SHIFTS = 1000;
    private static final Map<String, DayOfWeek> DAYS = Map.of("MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY,
            "WED", DayOfWeek.WEDNESDAY, "THU", DayOfWeek.THURSDAY, "FRI", DayOfWeek.FRIDAY, "SAT",
            DayOfWeek.SATURDAY, "SUN", DayOfWeek.SUNDAY);

    private RotationShifts() {
    }

    /** The shifts that overlap {@code [from, to)}. */
    static List<Shift> between(List<String> members, Instant rotationStart, ZoneId zone, JsonNode recurrence,
                               List<Override> overrides, Instant from, Instant to) {
        List<Shift> out = new ArrayList<>();
        if (members.isEmpty() || !to.isAfter(rotationStart)) {
            return out;
        }
        int multiplier = Math.max(1, recurrence.path("RecurrenceMultiplier").asInt(1));
        int onCall = Math.max(1, Math.min(members.size(), recurrence.path("NumberOfOnCalls").asInt(1)));
        List<Instant> boundaries = boundaries(rotationStart, zone, recurrence, to, multiplier);
        Instant start = rotationStart;
        int index = 0;
        for (int k = 0; out.size() < MAX_SHIFTS && start.isBefore(to); k++) {
            int boundary = (k + 1) * multiplier - 1;
            if (boundary >= boundaries.size()) {
                break;
            }
            Instant end = boundaries.get(boundary);
            List<String> contacts = new ArrayList<>();
            for (int j = 0; j < onCall; j++) {
                contacts.add(members.get((index + j) % members.size()));
            }
            index = (index + onCall) % members.size();
            if (end.isAfter(from)) {
                for (Instant[] covered : covered(start, end, zone, recurrence.path("ShiftCoverages"))) {
                    split(out, covered[0], covered[1], contacts, overrides, from, to);
                }
            }
            start = end;
        }
        return out;
    }

    /** Hand-off instants after the rotation start, far enough past {@code to} to close the last shift. */
    private static List<Instant> boundaries(Instant rotationStart, ZoneId zone, JsonNode recurrence, Instant to,
                                            int multiplier) {
        List<Instant> out = new ArrayList<>();
        LocalDate day = rotationStart.atZone(zone).toLocalDate();
        LocalDate last = to.atZone(zone).toLocalDate().plusDays(32L * multiplier + 1);
        while (!day.isAfter(last) && out.size() < 20_000) {
            for (JsonNode time : recurrence.path("DailySettings")) {
                add(out, day, time, zone, rotationStart);
            }
            for (JsonNode weekly : recurrence.path("WeeklySettings")) {
                if (DAYS.get(weekly.path("DayOfWeek").asText()) == day.getDayOfWeek()) {
                    add(out, day, weekly.path("HandOffTime"), zone, rotationStart);
                }
            }
            for (JsonNode monthly : recurrence.path("MonthlySettings")) {
                if (monthly.path("DayOfMonth").asInt() == day.getDayOfMonth()) {
                    add(out, day, monthly.path("HandOffTime"), zone, rotationStart);
                }
            }
            day = day.plusDays(1);
        }
        out.sort(Instant::compareTo);
        return out;
    }

    private static void add(List<Instant> out, LocalDate day, JsonNode time, ZoneId zone, Instant after) {
        Instant instant = ZonedDateTime.of(day, time(time), zone).toInstant();
        if (instant.isAfter(after) && !out.contains(instant)) {
            out.add(instant);
        }
    }

    /** The parts of a shift inside the rotation's shift coverage, or the whole shift when it has none. */
    private static List<Instant[]> covered(Instant start, Instant end, ZoneId zone, JsonNode coverages) {
        List<Instant[]> out = new ArrayList<>();
        if (!coverages.isObject() || coverages.isEmpty()) {
            out.add(new Instant[]{start, end});
            return out;
        }
        LocalDate last = end.atZone(zone).toLocalDate();
        for (LocalDate day = start.atZone(zone).toLocalDate().minusDays(1); !day.isAfter(last);
             day = day.plusDays(1)) {
            for (Map.Entry<String, DayOfWeek> entry : DAYS.entrySet()) {
                if (entry.getValue() != day.getDayOfWeek()) {
                    continue;
                }
                for (JsonNode window : coverages.path(entry.getKey())) {
                    ZonedDateTime from = ZonedDateTime.of(day, time(window.path("Start")), zone);
                    ZonedDateTime until = ZonedDateTime.of(day, time(window.path("End")), zone);
                    if (!until.isAfter(from)) {
                        until = until.plusDays(1);
                    }
                    Instant a = max(start, from.toInstant());
                    Instant b = min(end, until.toInstant());
                    if (a.isBefore(b)) {
                        out.add(new Instant[]{a, b});
                    }
                }
            }
        }
        out.sort((x, y) -> x[0].compareTo(y[0]));
        return out;
    }

    private static LocalTime time(JsonNode handOff) {
        return LocalTime.of(handOff.path("HourOfDay").asInt(), handOff.path("MinuteOfHour").asInt());
    }

    /** Adds a covered span, cut by overrides into regular and overridden shifts, clipped to the window. */
    private static void split(List<Shift> out, Instant start, Instant end, List<String> contacts,
                              List<Override> overrides, Instant from, Instant to) {
        List<Override> applicable = new ArrayList<>();
        for (Override override : overrides) {
            if (override.start().isBefore(end) && override.end().isAfter(start)) {
                applicable.add(override);
            }
        }
        applicable.sort((x, y) -> x.start().compareTo(y.start()));
        Instant cursor = start;
        for (Override override : applicable) {
            Instant oStart = max(cursor, override.start());
            Instant oEnd = min(end, override.end());
            if (cursor.isBefore(oStart)) {
                emit(out, cursor, oStart, contacts, List.of(), from, to);
            }
            if (oStart.isBefore(oEnd)) {
                emit(out, oStart, oEnd, override.contacts(), contacts, from, to);
                cursor = oEnd;
            }
        }
        if (cursor.isBefore(end)) {
            emit(out, cursor, end, contacts, List.of(), from, to);
        }
    }

    private static void emit(List<Shift> out, Instant start, Instant end, List<String> contacts,
                             List<String> overridden, Instant from, Instant to) {
        if (end.isAfter(from) && start.isBefore(to) && out.size() < MAX_SHIFTS) {
            out.add(new Shift(start, end, List.copyOf(contacts), List.copyOf(overridden)));
        }
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}

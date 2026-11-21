package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves what a user said into a slot value: custom slot types by their values and synonyms under the slot type's
 * resolution strategy and regex filter, extended {@code AMAZON.AlphaNumeric} by its pattern, and the built-in types
 * (numbers, dates, times, durations, confirmations, emails, phone numbers and the rest) by their usual spoken and
 * written forms.
 */
final class LexValues {

    private static final Map<String, Integer> UNITS = Map.ofEntries(Map.entry("zero", 0), Map.entry("one", 1),
            Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
            Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16),
            Map.entry("seventeen", 17), Map.entry("eighteen", 18), Map.entry("nineteen", 19),
            Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40), Map.entry("fifty", 50),
            Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80), Map.entry("ninety", 90),
            Map.entry("a", 1), Map.entry("an", 1));
    private static final Map<String, Integer> SCALES = Map.of("hundred", 100, "thousand", 1000, "million",
            1_000_000);
    private static final Set<String> YES = Set.of("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "correct",
            "right", "affirmative", "of course", "absolutely", "certainly", "please do", "yes please",
            "that's right", "that is right", "sounds good");
    private static final Set<String> NO = Set.of("no", "nope", "nah", "negative", "no thanks", "no thank you",
            "not really", "wrong", "incorrect", "cancel");
    private static final Set<String> MAYBE = Set.of("maybe", "perhaps", "possibly");
    private static final Set<String> DONT_KNOW = Set.of("don't know", "dont know", "i don't know", "not sure",
            "no idea", "i'm not sure");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(?:[.,]\\d+)?$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+$");
    private static final Pattern CLOCK = Pattern.compile(
            "^(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|o'clock)?$");
    private static final Pattern DURATION = Pattern.compile(
            "^(.+?)\\s+(seconds?|minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?)$");
    private static final Pattern UK_POSTCODE = Pattern.compile(
            "^([A-Za-z]{1,2}\\d[A-Za-z\\d]?)\\s*(\\d[A-Za-z]{2})$");
    private static final Pattern PERCENT = Pattern.compile("^(.+?)\\s*(%|percent|per cent)$");

    private LexValues() {
    }

    /** The open built-in types: any words fill them. */
    static boolean open(String typeId) {
        return Set.of("AMAZON.FreeFormInput", "AMAZON.FirstName", "AMAZON.LastName", "AMAZON.City",
                "AMAZON.Country", "AMAZON.State", "AMAZON.StreetName", "AMAZON.AlphaNumeric").contains(typeId);
    }

    /**
     * The value {@code text} gives a slot of type {@code typeId}, or empty when it doesn't fill the slot. A slot type of
     * the model resolves by its values; a built-in type by its forms.
     */
    static Optional<ObjectNode> resolve(ObjectMapper mapper, JsonNode model, String typeId, String text) {
        String original = text == null ? "" : text.strip();
        if (original.isEmpty()) {
            return Optional.empty();
        }
        if (typeId == null || typeId.startsWith("AMAZON.")) {
            return builtIn(typeId, original).map(interpreted -> value(mapper, original, interpreted,
                    List.of(interpreted)));
        }
        JsonNode type = null;
        for (JsonNode t : model.path("slotTypes")) {
            if (typeId.equals(t.path("slotTypeId").asText())) {
                type = t;
            }
        }
        if (type == null) {
            return Optional.empty();
        }
        return custom(mapper, type, original);
    }

    private static Optional<ObjectNode> custom(ObjectMapper mapper, JsonNode type, String original) {
        JsonNode selection = type.path("valueSelectionSetting");
        String regex = selection.path("regexFilter").path("pattern").asText(null);
        String strategy = selection.path("resolutionStrategy").asText("OriginalValue");
        String normalized = normalize(original);
        Set<String> resolved = new LinkedHashSet<>();
        for (JsonNode value : type.path("slotTypeValues")) {
            String sample = value.path("sampleValue").path("value").asText();
            boolean match = normalize(sample).equals(normalized);
            for (JsonNode synonym : value.path("synonyms")) {
                match |= normalize(synonym.path("value").asText()).equals(normalized);
            }
            if (match) {
                resolved.add(sample);
            }
        }
        if (resolved.isEmpty() && !type.path("slotTypeValues").isEmpty()) {
            String best = null;
            double score = 0;
            for (JsonNode value : type.path("slotTypeValues")) {
                String sample = value.path("sampleValue").path("value").asText();
                List<String> forms = new ArrayList<>();
                forms.add(sample);
                value.path("synonyms").forEach(s -> forms.add(s.path("value").asText()));
                for (String form : forms) {
                    double s = LexNlu.similarity(normalize(form), normalized);
                    if (s > score) {
                        score = s;
                        best = sample;
                    }
                }
            }
            if (best != null && score >= 0.8) {
                resolved.add(best);
            }
        }
        boolean extended = "AMAZON.AlphaNumeric".equals(type.path("parentSlotTypeSignature").asText());
        if (regex != null) {
            boolean matches;
            try {
                matches = Pattern.compile(regex).matcher(original.replace(" ", "")).matches();
            } catch (RuntimeException e) {
                matches = false;
            }
            if (!matches && resolved.isEmpty()) {
                return Optional.empty();
            }
            if (matches && resolved.isEmpty()) {
                String compact = original.replace(" ", "");
                return Optional.of(value(mapper, original, compact, List.of()));
            }
        } else if (extended && resolved.isEmpty()) {
            return builtIn("AMAZON.AlphaNumeric", original).map(v -> value(mapper, original, v, List.of()));
        }
        if (resolved.isEmpty()) {
            if ("TopResolution".equals(strategy)) {
                return Optional.empty();
            }
            return Optional.of(value(mapper, original, original, List.of()));
        }
        List<String> values = new ArrayList<>(resolved);
        String interpreted = switch (strategy) {
            case "TopResolution" -> values.get(0);
            case "Concatenation" -> String.join(" ", values);
            default -> original;
        };
        return Optional.of(value(mapper, original, interpreted, values));
    }

    private static ObjectNode value(ObjectMapper mapper, String original, String interpreted, List<String> resolved) {
        ObjectNode value = mapper.createObjectNode();
        value.put("originalValue", original);
        value.put("interpretedValue", interpreted);
        ArrayNode list = value.putArray("resolvedValues");
        resolved.forEach(list::add);
        return value;
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}@.:'%+-]+", " ")
                .replaceAll("(?<=\\p{L})[.]+(?=\\s|$)", "").trim().replaceAll("\\s+", " ");
    }

    /** The interpreted value of a built-in slot type. */
    static Optional<String> builtIn(String typeId, String original) {
        String text = normalize(original);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        switch (typeId == null ? "AMAZON.FreeFormInput" : typeId) {
            case "AMAZON.Number":
                return number(text).map(LexValues::format);
            case "AMAZON.Confirmation":
                return confirmation(text);
            case "AMAZON.Date":
                return date(text).map(LocalDate::toString);
            case "AMAZON.Time":
                return time(text);
            case "AMAZON.Duration":
                return duration(text);
            case "AMAZON.Percentage": {
                Matcher m = PERCENT.matcher(text);
                return m.matches() ? number(m.group(1)).map(LexValues::format) : Optional.empty();
            }
            case "AMAZON.EmailAddress": {
                String email = text.replace(" at ", "@").replace(" dot ", ".").replace(" ", "");
                return EMAIL.matcher(email).matches() ? Optional.of(email) : Optional.empty();
            }
            case "AMAZON.PhoneNumber": {
                StringBuilder digits = new StringBuilder();
                for (String word : text.split("[\\s-]+")) {
                    if (word.matches("\\+?\\d+")) {
                        digits.append(word);
                    } else if (UNITS.containsKey(word) && UNITS.get(word) < 10 && !"a".equals(word)
                            && !"an".equals(word)) {
                        digits.append(UNITS.get(word));
                    } else if ("oh".equals(word)) {
                        digits.append('0');
                    } else {
                        return Optional.empty();
                    }
                }
                String number = digits.toString();
                return number.replace("+", "").length() >= 7 ? Optional.of(number) : Optional.empty();
            }
            case "AMAZON.UKPostalCode": {
                Matcher m = UK_POSTCODE.matcher(original.strip());
                return m.matches() ? Optional.of((m.group(1) + " " + m.group(2)).toUpperCase(Locale.ROOT))
                        : Optional.empty();
            }
            case "AMAZON.AlphaNumeric": {
                String compact = original.replaceAll("[\\s-]+", "");
                return compact.matches("[A-Za-z0-9]+") ? Optional.of(compact.toUpperCase(Locale.ROOT))
                        : Optional.empty();
            }
            case "AMAZON.FirstName", "AMAZON.LastName", "AMAZON.City", "AMAZON.Country", "AMAZON.State",
                 "AMAZON.StreetName":
                return Optional.of(titleCase(original.strip()));
            default:
                return Optional.of(original.strip());
        }
    }

    private static String titleCase(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }

    private static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    static Optional<Double> number(String text) {
        String t = text.trim().replace(",", "");
        if (NUMBER.matcher(t).matches()) {
            return Optional.of(Double.parseDouble(t));
        }
        long total = 0;
        long current = 0;
        boolean any = false;
        boolean negative = false;
        for (String word : t.split("[\\s-]+")) {
            if (word.isEmpty() || "and".equals(word)) {
                continue;
            }
            if ("minus".equals(word) || "negative".equals(word)) {
                negative = true;
                continue;
            }
            if (UNITS.containsKey(word)) {
                current += UNITS.get(word);
                any = true;
            } else if (SCALES.containsKey(word)) {
                int scale = SCALES.get(word);
                current = (current == 0 ? 1 : current) * scale;
                if (scale >= 1000) {
                    total += current;
                    current = 0;
                }
                any = true;
            } else if (word.matches("\\d+")) {
                current += Long.parseLong(word);
                any = true;
            } else {
                return Optional.empty();
            }
        }
        if (!any) {
            return Optional.empty();
        }
        double value = total + current;
        return Optional.of(negative ? -value : value);
    }

    private static Optional<String> confirmation(String text) {
        String t = text.replaceAll("[.!]+$", "").trim();
        if (YES.contains(t) || t.startsWith("yes ")) {
            return Optional.of("Yes");
        }
        if (NO.contains(t) || t.startsWith("no ")) {
            return Optional.of("No");
        }
        if (MAYBE.contains(t)) {
            return Optional.of("Maybe");
        }
        if (DONT_KNOW.contains(t)) {
            return Optional.of("DontKnow");
        }
        return Optional.empty();
    }

    static Optional<LocalDate> date(String text) {
        LocalDate today = LocalDate.now();
        String t = text.replaceAll("^(on|for)\\s+", "").replaceAll("(\\d+)(st|nd|rd|th)\\b", "$1");
        switch (t) {
            case "today", "tonight":
                return Optional.of(today);
            case "tomorrow":
                return Optional.of(today.plusDays(1));
            case "yesterday":
                return Optional.of(today.minusDays(1));
            case "day after tomorrow", "the day after tomorrow":
                return Optional.of(today.plusDays(2));
            default:
                break;
        }
        try {
            return Optional.of(LocalDate.parse(t));
        } catch (DateTimeParseException e) {
            // other forms below
        }
        Matcher slash = Pattern.compile("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$").matcher(t);
        if (slash.matches()) {
            int year = slash.group(3) == null ? today.getYear() : Integer.parseInt(slash.group(3));
            year = year < 100 ? 2000 + year : year;
            try {
                return Optional.of(LocalDate.of(year, Integer.parseInt(slash.group(1)),
                        Integer.parseInt(slash.group(2))));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            String name = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (t.equals(name) || t.equals("this " + name)) {
                return Optional.of(today.with(TemporalAdjusters.nextOrSame(day)));
            }
            if (t.equals("next " + name)) {
                return Optional.of(today.with(TemporalAdjusters.next(day)));
            }
        }
        String[] words = t.replace(",", "").split("\\s+");
        Integer dayOfMonth = null;
        Month month = null;
        Integer year = null;
        for (String word : words) {
            Month m = month(word);
            if (m != null) {
                month = m;
            } else if (word.matches("\\d{1,2}")) {
                dayOfMonth = Integer.parseInt(word);
            } else if (word.matches("\\d{4}")) {
                year = Integer.parseInt(word);
            } else if (!"of".equals(word) && !"the".equals(word)) {
                return Optional.empty();
            }
        }
        if (month == null || dayOfMonth == null) {
            return Optional.empty();
        }
        try {
            LocalDate date = LocalDate.of(year == null ? today.getYear() : year, month, dayOfMonth);
            if (year == null && date.isBefore(today)) {
                date = date.plusYears(1);
            }
            return Optional.of(date);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Month month(String word) {
        for (Month m : Month.values()) {
            String full = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (word.equals(full) || (word.length() >= 3 && full.startsWith(word))) {
                return m;
            }
        }
        return null;
    }

    private static Optional<String> time(String text) {
        String t = text.replaceAll("^(at|around)\\s+", "").trim();
        switch (t) {
            case "noon", "midday":
                return Optional.of("12:00");
            case "midnight":
                return Optional.of("00:00");
            case "morning":
                return Optional.of("MO");
            case "afternoon":
                return Optional.of("AF");
            case "evening":
                return Optional.of("EV");
            case "night":
                return Optional.of("NI");
            default:
                break;
        }
        Matcher m = CLOCK.matcher(t.replace(".", "").replace("am", "am").trim());
        if (!m.matches()) {
            Matcher spoken = Pattern.compile("^(\\w+)(?:\\s+(\\w+))?\\s*(am|pm|o'clock)?$").matcher(t);
            if (spoken.matches()) {
                Optional<Double> hour = number(spoken.group(1));
                Optional<Double> minute = spoken.group(2) == null ? Optional.of(0.0) : number(spoken.group(2));
                if (hour.isPresent() && minute.isPresent() && (spoken.group(3) != null || spoken.group(2) != null)) {
                    return clock(hour.get().intValue(), minute.get().intValue(), spoken.group(3));
                }
            }
            return Optional.empty();
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (m.group(2) == null && m.group(3) == null) {
            return Optional.empty();
        }
        return clock(hour, minute, m.group(3));
    }

    private static Optional<String> clock(int hour, int minute, String suffix) {
        String s = suffix == null ? "" : suffix.replace(".", "");
        if ("pm".equals(s) && hour < 12) {
            hour += 12;
        } else if ("am".equals(s) && hour == 12) {
            hour = 0;
        }
        if (hour > 23 || minute > 59) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
    }

    private static Optional<String> duration(String text) {
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<Double> amount = number(m.group(1));
        if (amount.isEmpty()) {
            return Optional.empty();
        }
        String n = format(amount.get());
        String unit = m.group(2);
        if (unit.startsWith("sec")) {
            return Optional.of("PT" + n + "S");
        }
        if (unit.startsWith("min")) {
            return Optional.of("PT" + n + "M");
        }
        if (unit.startsWith("h")) {
            return Optional.of("PT" + n + "H");
        }
        if (unit.startsWith("d")) {
            return Optional.of("P" + n + "D");
        }
        if (unit.startsWith("w")) {
            return Optional.of("P" + n + "W");
        }
        if (unit.startsWith("mo")) {
            return Optional.of("P" + n + "M");
        }
        return Optional.of("P" + n + "Y");
    }
}

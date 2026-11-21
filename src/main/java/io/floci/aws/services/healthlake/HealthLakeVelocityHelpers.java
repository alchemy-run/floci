package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The helpers HealthLake registers in a C-CDA profile's Velocity context ({@code $arrayHelper}, {@code $stringHelper},
 * {@code $conditionalHelper}, {@code $dateHelper}, {@code $mathHelper}, {@code $encodingHelper}, {@code $jsonHelper},
 * {@code $cdaHelper}, {@code $carePlanHelper}, {@code $specializedHelper}). A C-CDA document is a map of maps: an
 * element's attributes and child elements are its keys, a repeated element a list, and its text {@code _}.
 */
public final class HealthLakeVelocityHelpers {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final UUID NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private HealthLakeVelocityHelpers() {
    }

    /** The helpers, by the name a template reads them under. */
    static Map<String, Object> all() {
        Map<String, Object> helpers = new LinkedHashMap<>();
        helpers.put("arrayHelper", new ArrayHelper());
        helpers.put("stringHelper", new StringHelper());
        helpers.put("conditionalHelper", new ConditionalHelper());
        helpers.put("dateHelper", new DateHelper());
        helpers.put("mathHelper", new MathHelper());
        helpers.put("encodingHelper", new EncodingHelper());
        helpers.put("jsonHelper", new JsonHelper());
        helpers.put("cdaHelper", new CdaHelper());
        helpers.put("carePlanHelper", new CarePlanHelper());
        helpers.put("specializedHelper", new SpecializedHelper());
        return helpers;
    }

    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object text = map.get("_");
            return text == null ? null : text.toString();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : text(list.get(0));
        }
        return value.toString();
    }

    static List<Object> list(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Object[] array) {
            return new ArrayList<>(List.of(array));
        }
        List<Object> one = new ArrayList<>();
        one.add(value);
        return one;
    }

    static double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(text(value)).trim());
        } catch (NumberFormatException | NullPointerException e) {
            return Double.NaN;
        }
    }

    static UUID uuid(String seed) {
        byte[] name = seed.getBytes(StandardCharsets.UTF_8);
        byte[] ns = new byte[16];
        long msb = NAMESPACE.getMostSignificantBits();
        long lsb = NAMESPACE.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            ns[i] = (byte) (msb >>> (8 * (7 - i)));
            ns[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ns);
            sha1.update(name);
            byte[] hash = sha1.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long high = 0;
            long low = 0;
            for (int i = 0; i < 8; i++) {
                high = (high << 8) | (hash[i] & 0xff);
                low = (low << 8) | (hash[8 + i] & 0xff);
            }
            return new UUID(high, low);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- arrays

    public static final class ArrayHelper {
        public Object elementAt(Object list, int index) {
            List<Object> items = list(list);
            return index >= 0 && index < items.size() ? items.get(index) : null;
        }

        public String charAt(Object value, int index) {
            String s = text(value);
            return s == null || index < 0 || index >= s.length() ? null : String.valueOf(s.charAt(index));
        }

        public int length(Object value) {
            return list(value).size();
        }

        public int strLength(Object value) {
            String s = text(value);
            return s == null ? 0 : s.length();
        }

        public List<Object> slice(Object list, int start, int end) {
            List<Object> items = list(list);
            int from = Math.max(0, Math.min(start, items.size()));
            int to = Math.max(from, Math.min(end, items.size()));
            return new ArrayList<>(items.subList(from, to));
        }

        public String strSlice(Object value, int start, int end) {
            String s = text(value);
            if (s == null) {
                return null;
            }
            int from = Math.max(0, Math.min(start, s.length()));
            return s.substring(from, Math.max(from, Math.min(end, s.length())));
        }

        public List<Object> toArray(Object value) {
            return list(value);
        }

        public List<Object> multipleToArray(Object... values) {
            LinkedHashSet<Object> unique = new LinkedHashSet<>();
            for (Object value : values) {
                unique.addAll(list(value));
            }
            unique.remove(null);
            return new ArrayList<>(unique);
        }

        public Object concat(Object... values) {
            boolean strings = values.length > 0;
            for (Object value : values) {
                strings &= value == null || value instanceof CharSequence;
            }
            if (strings) {
                StringBuilder out = new StringBuilder();
                for (Object value : values) {
                    if (value != null) {
                        out.append(value);
                    }
                }
                return out.toString();
            }
            List<Object> out = new ArrayList<>();
            for (Object value : values) {
                out.addAll(list(value));
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- strings

    public static final class StringHelper {
        public String toLower(Object value) {
            String s = text(value);
            return s == null ? null : s.toLowerCase(Locale.ROOT);
        }

        public String toUpper(Object value) {
            String s = text(value);
            return s == null ? null : s.toUpperCase(Locale.ROOT);
        }

        public String trim(Object value) {
            String s = text(value);
            return s == null ? null : s.trim();
        }

        public String trimAndLower(Object value) {
            String s = trim(value);
            return s == null ? null : s.toLowerCase(Locale.ROOT);
        }

        public String trimAndUpper(Object value) {
            String s = trim(value);
            return s == null ? null : s.toUpperCase(Locale.ROOT);
        }

        public String replace(Object value, String regex, String replacement) {
            String s = text(value);
            return s == null ? null : s.replaceAll(regex, Matcher.quoteReplacement(replacement));
        }

        public List<String> match(Object value, String regex) {
            List<String> out = new ArrayList<>();
            String s = text(value);
            if (s != null) {
                Matcher matcher = Pattern.compile(regex).matcher(s);
                while (matcher.find()) {
                    out.add(matcher.group());
                }
            }
            return out;
        }

        public boolean contains(Object value, String... substrings) {
            String s = text(value);
            if (s == null) {
                return false;
            }
            for (String substring : substrings) {
                if (substring != null && s.contains(substring)) {
                    return true;
                }
            }
            return false;
        }

        public List<String> split(Object value, String regex) {
            String s = text(value);
            return s == null ? new ArrayList<>() : new ArrayList<>(List.of(s.split(regex)));
        }

        public boolean startsWith(Object value, String prefix) {
            String s = text(value);
            return s != null && prefix != null && s.startsWith(prefix);
        }

        public Object coalesce(Object... values) {
            for (Object value : values) {
                String s = text(value);
                if (s != null && !s.isEmpty()) {
                    return value instanceof Map<?, ?> ? s : value;
                }
            }
            return null;
        }

        public String concat(Object... values) {
            StringBuilder out = new StringBuilder();
            for (Object value : values) {
                out.append(value == null ? "" : text(value));
            }
            return out.toString();
        }

        public String concatDefined(Object... values) {
            StringBuilder out = new StringBuilder();
            for (Object value : values) {
                String s = text(value);
                if (s != null) {
                    out.append(s);
                }
            }
            return out.toString();
        }

        public String parseReferenceData(Object value) {
            if (value instanceof Map<?, ?> map) {
                for (String key : List.of("_", "displayName", "text", "value")) {
                    Object found = map.get(key);
                    if (found != null) {
                        return found instanceof Map<?, ?> ? text(found) : found.toString();
                    }
                }
                return null;
            }
            return text(value);
        }

        public String decodeHtmlEntities(Object value) {
            String s = text(value);
            if (s == null) {
                return null;
            }
            return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        }

        public String escapeJsonString(Object value) {
            String s = text(value);
            if (s == null) {
                return null;
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    // ---------------------------------------------------------------- conditions

    public static final class ConditionalHelper {
        public boolean eq(Object context, Object... params) {
            String s = text(context);
            for (Object param : params) {
                if (s == null ? param == null : s.equals(text(param))) {
                    return true;
                }
            }
            return false;
        }

        public boolean ne(Object context, Object... params) {
            return !eq(context, params);
        }

        public boolean lt(Object context, Object other) {
            return number(context) < number(other);
        }

        public boolean gt(Object context, Object other) {
            return number(context) > number(other);
        }

        public boolean lte(Object context, Object other) {
            return number(context) <= number(other);
        }

        public boolean gte(Object context, Object other) {
            return number(context) >= number(other);
        }

        public boolean not(Object context) {
            return !truthy(context);
        }

        public boolean and(Object... params) {
            for (Object param : params) {
                if (!truthy(param)) {
                    return false;
                }
            }
            return true;
        }

        public boolean or(Object... params) {
            for (Object param : params) {
                if (truthy(param)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean truthy(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof Collection<?> c) {
                return !c.isEmpty();
            }
            String s = text(value);
            return s != null && !s.isEmpty() && !"false".equalsIgnoreCase(s);
        }
    }

    // ---------------------------------------------------------------- dates

    public static final class DateHelper {
        private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

        public String addHyphensDate(Object value) {
            String s = text(value);
            if (s == null || s.length() < 8 || !s.substring(0, 8).matches("\\d{8}")) {
                return s;
            }
            return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
        }

        /** An HL7 timestamp ({@code yyyyMMddHHmmss[.fff][+zzzz]}) as an ISO date-time in UTC. */
        public String formatAsDateTime(Object value) {
            String s = text(value);
            if (s == null) {
                return null;
            }
            Matcher m = Pattern.compile("(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.(\\d+))?"
                    + "([+-]\\d{4})?").matcher(s.trim());
            if (!m.matches()) {
                return getDateTime(value);
            }
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
            int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
            int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
            ZoneOffset offset = m.group(8) == null ? ZoneOffset.UTC : ZoneOffset.of(m.group(8).substring(0, 3) + ":"
                    + m.group(8).substring(3));
            OffsetDateTime time = OffsetDateTime.of(year, month, day, hour, minute, second, 0, offset);
            return ISO_MILLIS.format(time.toInstant());
        }

        public String getDateTime(Object value) {
            String s = text(value);
            if (s == null) {
                return null;
            }
            String trimmed = s.trim();
            for (DateTimeFormatter format : List.of(DateTimeFormatter.ISO_OFFSET_DATE_TIME,
                    DateTimeFormatter.ISO_INSTANT)) {
                try {
                    return ISO_MILLIS.format(Instant.from(format.parse(trimmed)));
                } catch (DateTimeParseException ignored) {
                    // the next format
                }
            }
            for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm")) {
                try {
                    return ISO_MILLIS.format(LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern))
                            .toInstant(ZoneOffset.UTC));
                } catch (DateTimeParseException ignored) {
                    // the next pattern
                }
            }
            try {
                return LocalDate.parse(trimmed).toString();
            } catch (DateTimeParseException ignored) {
                return trimmed.matches("\\d{8,14}.*") ? formatAsDateTime(trimmed) : trimmed;
            }
        }

        public boolean startDateLteEndDate(Object start, Object end) {
            String a = getDateTime(start);
            String b = getDateTime(end);
            return a == null || b == null || a.compareTo(b) <= 0;
        }

        public String now() {
            return ISO_MILLIS.format(Instant.now());
        }
    }

    // ---------------------------------------------------------------- numbers

    public static final class MathHelper {
        public boolean isNaN(Object value) {
            return Double.isNaN(number(value));
        }

        public double abs(Object value) {
            return Math.abs(number(value));
        }

        public double ceil(Object value) {
            return Math.ceil(number(value));
        }

        public double floor(Object value) {
            return Math.floor(number(value));
        }

        public double max(Object context, Object... params) {
            double max = number(context);
            for (Object param : params) {
                max = Math.max(max, number(param));
            }
            return max;
        }

        public double min(Object context, Object... params) {
            double min = number(context);
            for (Object param : params) {
                min = Math.min(min, number(param));
            }
            return min;
        }

        public double pow(Object context, Object exponent) {
            return Math.pow(number(context), number(exponent));
        }

        public double random() {
            return Math.random();
        }

        public long round(Object value) {
            return Math.round(number(value));
        }

        public double sign(Object value) {
            return Math.signum(number(value));
        }

        public long trunc(Object value) {
            return (long) number(value);
        }

        public double add(Object a, Object b) {
            return number(a) + number(b);
        }

        public double subtract(Object a, Object b) {
            return number(a) - number(b);
        }

        public double multiply(Object a, Object b) {
            return number(a) * number(b);
        }

        public double divide(Object a, Object b) {
            double divisor = number(b);
            return divisor == 0 ? number(a) : number(a) / divisor;
        }
    }

    // ---------------------------------------------------------------- encodings and JSON

    public static final class EncodingHelper {
        public String base64Encode(Object value) {
            String s = text(value);
            return s == null ? null : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
        }

        public String base64Decode(Object value) {
            String s = text(value);
            return s == null ? null : new String(Base64.getDecoder().decode(s.trim()), StandardCharsets.UTF_8);
        }

        public String escapeJson(Object value) {
            String s = text(value);
            return s == null ? null : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    .replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    public static final class JsonHelper {
        public String toString(Object value) {
            return value == null ? null : value instanceof Map<?, ?> || value instanceof Collection<?>
                    ? toJsonString(value) : value.toString();
        }

        public String toJsonString(Object value) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return null;
            }
        }

        public String toJsonStringPrettier(Object value) {
            try {
                return PRETTY.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return null;
            }
        }

        public String escapeSpecialChars(Object value) {
            return new StringHelper().escapeJsonString(value);
        }

        public String unescapeSpecialChars(Object value) {
            String s = text(value);
            return s == null ? null : s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }

    // ---------------------------------------------------------------- C-CDA

    public static final class CdaHelper {
        private static final Map<String, String> SYSTEMS = Map.ofEntries(
                Map.entry("2.16.840.1.113883.6.1", "http://loinc.org"),
                Map.entry("2.16.840.1.113883.6.96", "http://snomed.info/sct"),
                Map.entry("2.16.840.1.113883.6.88", "http://www.nlm.nih.gov/research/umls/rxnorm"),
                Map.entry("2.16.840.1.113883.6.90", "http://hl7.org/fhir/sid/icd-10-cm"),
                Map.entry("2.16.840.1.113883.6.103", "http://hl7.org/fhir/sid/icd-9-cm"),
                Map.entry("2.16.840.1.113883.6.12", "http://www.ama-assn.org/go/cpt"),
                Map.entry("2.16.840.1.113883.12.292", "http://hl7.org/fhir/sid/cvx"),
                Map.entry("2.16.840.1.113883.6.69", "http://hl7.org/fhir/sid/ndc"),
                Map.entry("2.16.840.1.113883.4.1", "http://hl7.org/fhir/sid/us-ssn"),
                Map.entry("2.16.840.1.113883.4.6", "http://hl7.org/fhir/sid/us-npi"),
                Map.entry("2.16.840.1.113883.5.1", "http://terminology.hl7.org/CodeSystem/v3-AdministrativeGender"),
                Map.entry("2.16.840.1.113883.5.4", "http://terminology.hl7.org/CodeSystem/v3-ActCode"),
                Map.entry("2.16.840.1.113883.5.83", "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation"),
                Map.entry("2.16.840.1.113883.5.111", "http://terminology.hl7.org/CodeSystem/v3-RoleCode"),
                Map.entry("2.16.840.1.113883.6.238", "urn:oid:2.16.840.1.113883.6.238"),
                Map.entry("2.16.840.1.113883.6.8", "http://unitsofmeasure.org"));

        /** The first section with each template id, as a map of normalized id to section. */
        public Map<String, Object> getFirstCdaSectionsByTemplateId(Object context, String... templateIds) {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<?, ?>> sections = sections(context);
            for (String id : templateIds) {
                for (Map<?, ?> section : sections) {
                    if (hasTemplate(section, id)) {
                        out.put(normalize(id), section);
                        break;
                    }
                }
            }
            return out;
        }

        public List<Map<String, Object>> getAllCdaSectionsByTemplateId(Object context, String... templateIds) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<?, ?> section : sections(context)) {
                for (String id : templateIds) {
                    if (hasTemplate(section, id)) {
                        Map<String, Object> wrapped = new LinkedHashMap<>();
                        wrapped.put(normalize(id), section);
                        out.add(wrapped);
                    }
                }
            }
            return out;
        }

        public List<Object> getAllCdaSectionsWithoutTemplateId(Object context, String... templateIds) {
            List<Object> out = new ArrayList<>();
            for (Map<?, ?> section : sections(context)) {
                for (String id : templateIds) {
                    if (hasTemplate(section, id)) {
                        out.add(section);
                    }
                }
            }
            return out;
        }

        static String normalize(String templateId) {
            return "_" + templateId.replace('.', '_');
        }

        private static boolean hasTemplate(Map<?, ?> section, String templateId) {
            for (Object template : list(section.get("templateId"))) {
                if (template instanceof Map<?, ?> map && templateId.equals(String.valueOf(map.get("root")))) {
                    return true;
                }
            }
            return false;
        }

        /** Every section of the document, nested ones included, in document order. */
        private static List<Map<?, ?>> sections(Object context) {
            List<Map<?, ?>> out = new ArrayList<>();
            collect(context, out);
            return out;
        }

        private static void collect(Object node, List<Map<?, ?>> out) {
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if ("section".equals(entry.getKey())) {
                        for (Object section : list(entry.getValue())) {
                            if (section instanceof Map<?, ?> s) {
                                out.add(s);
                            }
                        }
                    }
                    collect(entry.getValue(), out);
                }
            } else if (node instanceof List<?> items) {
                items.forEach(item -> collect(item, out));
            }
        }

        public String generatePractitionerId(Object practitioner) {
            return identity("Practitioner", practitioner);
        }

        public String generateOrganizationId(Object organization) {
            return identity("Organization", organization);
        }

        public String generateLocationId(Object location) {
            return identity("Location", location);
        }

        private static String identity(String kind, Object entity) {
            if (!(entity instanceof Map<?, ?> map)) {
                return uuid(kind + "|" + text(entity)).toString();
            }
            Object person = map.containsKey("assignedPerson") ? map.get("assignedPerson") : map;
            String seed = kind + "|" + new JsonHelper().toJsonString(((Map<?, ?>) (person instanceof Map<?, ?>
                    ? person : map)).get("name")) + "|" + new JsonHelper().toJsonString(map.get("addr")) + "|"
                    + new JsonHelper().toJsonString(map.get("telecom"));
            return uuid(seed).toString();
        }

        public Object getSpecifiedEntryRelationship(Object container, String typeCode) {
            for (Object relationship : list(container)) {
                if (relationship instanceof Map<?, ?> map && typeCode.equals(String.valueOf(map.get("typeCode")))) {
                    return relationship;
                }
            }
            return null;
        }

        public String getSystemUrl(Object codeSystem, boolean canBeUnknown) {
            String oid = text(codeSystem);
            if (oid == null) {
                return canBeUnknown ? null : "http://terminology.hl7.org/CodeSystem/data-absent-reason";
            }
            String system = SYSTEMS.get(oid);
            return system != null ? system : "urn:oid:" + oid;
        }

        public Map<String, Object> extractRangeFromQuantity(Object context) {
            Map<String, Object> result = new LinkedHashMap<>();
            String s = text(context instanceof Map<?, ?> m && m.get("value") != null ? m.get("value") : context);
            Matcher m = s == null ? null : Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")
                    .matcher(s);
            boolean valid = m != null && m.matches();
            result.put("isValid", valid);
            if (valid) {
                Map<String, Object> range = new LinkedHashMap<>();
                range.put("low", Map.of("value", Double.parseDouble(m.group(1))));
                range.put("high", Map.of("value", Double.parseDouble(m.group(2))));
                result.put("range", range);
            }
            return result;
        }

        public Map<String, Object> extractReferenceRange(Object range) {
            if (!(range instanceof Map<?, ?> map)) {
                return null;
            }
            Object observationRange = map.containsKey("observationRange") ? map.get("observationRange") : map;
            if (!(observationRange instanceof Map<?, ?> or) || !(or.get("value") instanceof Map<?, ?> value)) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (String bound : List.of("low", "high")) {
                if (value.get(bound) instanceof Map<?, ?> b) {
                    Map<String, Object> quantity = new LinkedHashMap<>();
                    quantity.put("value", number(b.get("value")));
                    if (b.get("unit") != null) {
                        quantity.put("unit", b.get("unit"));
                    }
                    out.put(bound, quantity);
                }
            }
            return out.isEmpty() ? null : out;
        }

        public String personalRelationshipRoleTypeCodeSystem() {
            return "2.16.840.1.113883.1.11.19563";
        }

        /** A parsed XML map back as XML, attribute names lowercased for XHTML. */
        public String xmlToString(Object block) {
            StringBuilder out = new StringBuilder();
            write(out, null, block);
            return out.toString();
        }

        private static void write(StringBuilder out, String name, Object node) {
            if (node instanceof List<?> items) {
                items.forEach(item -> write(out, name, item));
                return;
            }
            if (!(node instanceof Map<?, ?> map)) {
                if (name == null) {
                    out.append(escape(String.valueOf(node)));
                } else {
                    out.append('<').append(name).append('>').append(escape(String.valueOf(node))).append("</")
                            .append(name).append('>');
                }
                return;
            }
            if (name != null) {
                out.append('<').append(name);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?>) && !(entry.getValue() instanceof List<?>)
                            && !"_".equals(entry.getKey())) {
                        out.append(' ').append(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)).append("=\"")
                                .append(escape(String.valueOf(entry.getValue()))).append('"');
                    }
                }
                out.append('>');
            }
            if (map.get("_") != null) {
                out.append(escape(String.valueOf(map.get("_"))));
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) {
                    write(out, String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            if (name != null) {
                out.append("</").append(name).append('>');
            }
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }

    public static final class CarePlanHelper {
        /** A CarePlan activity from a treatment plan encounter: its status, code and scheduled period. */
        public Map<String, Object> getActivityFromTreatmentPlanEncounter(Object context) {
            Map<String, Object> activity = new LinkedHashMap<>();
            if (!(context instanceof Map<?, ?> encounter)) {
                return activity;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            String mood = String.valueOf(encounter.get("moodCode"));
            detail.put("status", "INT".equals(mood) || "RQO".equals(mood) ? "scheduled" : "not-started");
            if (encounter.get("code") instanceof Map<?, ?> code) {
                Map<String, Object> coding = new LinkedHashMap<>();
                coding.put("system", new CdaHelper().getSystemUrl(code.get("codeSystem"), true));
                coding.put("code", code.get("code"));
                coding.put("display", code.get("displayName"));
                detail.put("code", Map.of("coding", List.of(coding)));
            }
            if (encounter.get("effectiveTime") instanceof Map<?, ?> time) {
                Map<String, Object> period = new LinkedHashMap<>();
                DateHelper dates = new DateHelper();
                Object low = time.get("low") instanceof Map<?, ?> l ? l.get("value") : time.get("value");
                Object high = time.get("high") instanceof Map<?, ?> h ? h.get("value") : null;
                if (low != null) {
                    period.put("start", dates.formatAsDateTime(low));
                }
                if (high != null) {
                    period.put("end", dates.formatAsDateTime(high));
                }
                detail.put("scheduledPeriod", period);
            }
            activity.put("detail", detail);
            return activity;
        }

        public Object getFirstEffectiveTimeFromObservationComponent(Object components) {
            for (Object component : list(components)) {
                if (component instanceof Map<?, ?> c && c.get("observation") instanceof Map<?, ?> o
                        && o.get("effectiveTime") instanceof Map<?, ?> t) {
                    return t.get("value") != null ? t.get("value") : t.get("low") instanceof Map<?, ?> l
                            ? l.get("value") : null;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- specialized

    public static final class SpecializedHelper {
        private static final Pattern UUID_PATTERN = Pattern.compile(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        public String generateUUID(Object context) {
            return uuid(new JsonHelper().toString(context)).toString();
        }

        public String generateUUIDV2(Object context) {
            String s = new JsonHelper().toString(context);
            return uuid(s == null ? "" : s.replace("\n", "").replace("\r", "")).toString();
        }

        public String ensureUUID(Object context) {
            String s = text(context);
            return s != null && UUID_PATTERN.matcher(s).matches() ? s : generateUUID(context);
        }

        public String addHyphensSSN(Object context) {
            String s = text(context);
            return s != null && s.matches("\\d{9}") ? s.substring(0, 3) + "-" + s.substring(3, 5) + "-"
                    + s.substring(5) : s;
        }

        public boolean nullFlavorAwareOr(Object... values) {
            for (Object value : values) {
                if (value != null && !(value instanceof Map<?, ?> map && map.containsKey("nullFlavor")
                        && map.size() == 1)) {
                    return true;
                }
            }
            return false;
        }

        public List<Object> buildPresentedForm(Object b64String, Object components) {
            List<Object> forms = new ArrayList<>();
            for (Object data : list(b64String)) {
                String s = text(data);
                if (s != null && !s.isBlank()) {
                    forms.add(Map.of("contentType", "text/plain", "data", s.trim()));
                }
            }
            for (Object component : list(components)) {
                String s = extractTextFromNestedProperties(component);
                if (!s.isBlank()) {
                    forms.add(Map.of("contentType", "text/plain", "data", Base64.getEncoder().encodeToString(
                            s.getBytes(StandardCharsets.UTF_8))));
                }
            }
            return forms;
        }

        public String extractTextFromNestedProperties(Object node) {
            StringBuilder out = new StringBuilder();
            gather(node, out);
            return out.toString().trim();
        }

        private static void gather(Object node, StringBuilder out) {
            if (node instanceof Map<?, ?> map) {
                if (map.get("_") != null) {
                    out.append(map.get("_")).append(' ');
                }
                map.forEach((key, value) -> {
                    if (!"_".equals(key)) {
                        gather(value, out);
                    }
                });
            } else if (node instanceof List<?> items) {
                items.forEach(item -> gather(item, out));
            }
        }

        public Map<String, Object> buildDefaultDiagReportDetails(Object section) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", Map.of("coding", List.of(Map.of("system", "http://loinc.org", "code", "30954-2",
                    "display", "Relevant diagnostic tests/laboratory data Narrative"))));
            details.put("status", "final");
            return details;
        }

        public Map<String, Object> convertFeetAndInchesToCm(Object context) {
            Map<String, Object> result = new LinkedHashMap<>();
            String s = text(context);
            Matcher m = s == null ? null : Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ft\\s*(?:(\\d+(?:\\.\\d+)?)\\s*in)?")
                    .matcher(s);
            boolean valid = m != null && m.find();
            result.put("isValid", valid);
            if (valid) {
                double cm = Double.parseDouble(m.group(1)) * 30.48 + (m.group(2) == null ? 0
                        : Double.parseDouble(m.group(2)) * 2.54);
                result.put("value", Math.round(cm * 10) / 10.0);
                result.put("unit", "cm");
            }
            return result;
        }

        public Map<String, Object> extractNumberAndUnit(Object context) {
            Map<String, Object> result = new LinkedHashMap<>();
            String s = text(context);
            Matcher m = s == null ? null : Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([^\\d\\s].*)?$").matcher(s);
            boolean valid = m != null && m.matches();
            result.put("isValid", valid);
            if (valid) {
                result.put("value", Double.parseDouble(m.group(1)));
                result.put("unit", m.group(2) == null ? "" : m.group(2).trim());
            }
            return result;
        }

        public Map<String, Object> extractComparator(Object context) {
            Map<String, Object> result = new LinkedHashMap<>();
            String s = text(context);
            Matcher m = s == null ? null : Pattern.compile("^\\s*(<=|>=|<|>)\\s*(-?\\d+(?:\\.\\d+)?)").matcher(s);
            boolean valid = m != null && m.find();
            result.put("isValid", valid);
            if (valid) {
                result.put("comparator", m.group(1));
                result.put("number", Double.parseDouble(m.group(2)));
            }
            return result;
        }

        public Double extractDecimal(Object context) {
            String s = text(context);
            Matcher m = s == null ? null : Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(s);
            return m != null && m.find() ? Double.parseDouble(m.group()) : null;
        }

        public String personalRelationshipRoleTypeCodeSystem() {
            return "2.16.840.1.113883.1.11.19563";
        }

        public String convertMappedDataToPlainText(Object context) {
            StringBuilder out = new StringBuilder();
            for (Object row : list(context)) {
                if (row instanceof Map<?, ?> map) {
                    List<String> pairs = new ArrayList<>();
                    map.forEach((key, value) -> pairs.add(key + ": " + text(value)));
                    out.append(String.join(", ", pairs)).append('\n');
                }
            }
            return out.toString().trim();
        }

        /** An HTML table's rows as maps of header to cell text. */
        public List<Map<String, String>> extractAndMapTableData(Object table) {
            List<Map<String, String>> rows = new ArrayList<>();
            if (!(table instanceof Map<?, ?> map)) {
                return rows;
            }
            List<String> headers = new ArrayList<>();
            Object head = map.get("thead");
            for (Object tr : list(head instanceof Map<?, ?> h ? h.get("tr") : null)) {
                if (tr instanceof Map<?, ?> row) {
                    for (Object th : list(row.get("th"))) {
                        headers.add(extractTextFromNestedProperties(th));
                    }
                }
            }
            Object body = map.get("tbody");
            for (Object tr : list(body instanceof Map<?, ?> b ? b.get("tr") : map.get("tr"))) {
                if (tr instanceof Map<?, ?> row) {
                    List<Object> cells = list(row.get("td"));
                    Map<String, String> mapped = new LinkedHashMap<>();
                    for (int i = 0; i < cells.size(); i++) {
                        mapped.put(i < headers.size() ? headers.get(i) : "column" + (i + 1),
                                extractTextFromNestedProperties(cells.get(i)));
                    }
                    rows.add(mapped);
                }
            }
            return rows;
        }

        public String getObservationCategoryDisplayFromCode(Object code) {
            return switch (String.valueOf(text(code))) {
                case "vital-signs" -> "Vital Signs";
                case "laboratory" -> "Laboratory";
                case "imaging" -> "Imaging";
                case "procedure" -> "Procedure";
                case "survey" -> "Survey";
                case "exam" -> "Exam";
                case "therapy" -> "Therapy";
                case "activity" -> "Activity";
                default -> null;
            };
        }
    }
}

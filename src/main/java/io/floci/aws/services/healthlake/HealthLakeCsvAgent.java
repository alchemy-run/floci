package io.floci.aws.services.healthlake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How floci's Data Transformation Agent drafts a CSV mapping from sample files: each table becomes the FHIR resource
 * its name suggests (patients, encounters, practitioners, observations, conditions, allergies, medications), and each
 * column the element its name suggests, with date formats read from the sample values, sex codes mapped to FHIR
 * genders, and columns named after another table's primary key made references to it. Columns it does not recognize
 * are left out and listed.
 */
final class HealthLakeCsvAgent {

    private HealthLakeCsvAgent() {
    }

    private record Table(String name, HealthLakeCsvMapper.Table data, String type, String key) {
    }

    static HealthLakeTransformService.Draft draft(Map<String, String> files) {
        List<Table> tables = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String fileName = file.getKey().substring(file.getKey().lastIndexOf('/') + 1);
            String name = fileName.substring(0, fileName.length() - 4).replaceAll("[^A-Za-z0-9_]", "_");
            HealthLakeCsvMapper.Table data = HealthLakeCsvMapper.parse(file.getValue());
            String type = resourceType(name, data.header());
            if (type == null || data.header().isEmpty()) {
                skipped.add(name);
                continue;
            }
            tables.add(new Table(name, data, type, primaryKey(data.header())));
        }
        Map<String, Table> byKey = new LinkedHashMap<>();
        tables.forEach(table -> byKey.putIfAbsent(table.key().toUpperCase(Locale.ROOT), table));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("sourceSystemId", "sample-data");
        List<Object> declared = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (Table table : tables) {
            List<Object> fields = new ArrayList<>();
            List<Object> foreignKeys = new ArrayList<>();
            Set<String> unmapped = new LinkedHashSet<>();
            for (String column : table.data().header()) {
                String upper = column.toUpperCase(Locale.ROOT);
                Table referenced = byKey.get(upper);
                if (referenced != null && referenced != table) {
                    String path = reference(table.type(), referenced.type());
                    if (path != null) {
                        fields.add(field(path, column, Map.of("type", "resourceReference", "referencedTable",
                                referenced.name(), "referencedResourceType", referenced.type())));
                        foreignKeys.add(Map.of("column", column, "referencedTable", referenced.name(),
                                "referencedColumn", referenced.key()));
                        continue;
                    }
                }
                if (column.equals(table.key())) {
                    fields.add(field("identifier[0].value", column, Map.of("type", "direct")));
                    continue;
                }
                List<Map<String, Object>> mapped = columnMappings(table.type(), upper, column, table.data());
                if (mapped.isEmpty()) {
                    unmapped.add(column);
                } else {
                    fields.addAll(mapped);
                }
            }
            fields.addAll(constants(table.type(), table.key()));
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("targetResourceType", table.type());
            resource.put("fieldMappings", fields);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tableName", table.name());
            entry.put("primaryKeyColumn", table.key());
            if (!foreignKeys.isEmpty()) {
                entry.put("foreignKeys", foreignKeys);
            }
            entry.put("resourceMappings", List.of(resource));
            declared.add(entry);
            names.add(table.name() + " (" + table.type() + ")");
            summary.append(table.name()).append(" becomes ").append(table.type()).append(", keyed by ")
                    .append(table.key()).append(". ");
            if (!unmapped.isEmpty()) {
                summary.append("Not mapped: ").append(String.join(", ", unmapped)).append(". ");
            }
        }
        if (!skipped.isEmpty()) {
            summary.append("No FHIR resource was recognized for ").append(String.join(", ", skipped)).append('.');
        }
        if (declared.isEmpty()) {
            throw new IllegalArgumentException("None of the sample files is a table the agent recognizes.");
        }
        profile.put("tables", declared);
        return new HealthLakeTransformService.Draft(HealthLakeTransformService.yaml(profile), names,
                summary.toString().trim());
    }

    private static String resourceType(String table, List<String> header) {
        String name = table.toUpperCase(Locale.ROOT);
        if (name.contains("PATIENT") || name.startsWith("PT") || name.contains("DEMOG") || name.contains("MEMBER")) {
            return "Patient";
        }
        if (name.contains("ENC") || name.contains("VISIT") || name.contains("ADMISSION")) {
            return "Encounter";
        }
        if (name.contains("PROV") || name.contains("PRACT") || name.contains("PHYSICIAN") || name.contains("DOCTOR")) {
            return "Practitioner";
        }
        if (name.contains("OBS") || name.contains("LAB") || name.contains("RESULT") || name.contains("VITAL")) {
            return "Observation";
        }
        if (name.contains("COND") || name.contains("DIAG") || name.contains("PROBLEM")) {
            return "Condition";
        }
        if (name.contains("ALLERG")) {
            return "AllergyIntolerance";
        }
        if (name.contains("MED") || name.contains("RX") || name.contains("PRESCRIPTION")) {
            return "MedicationStatement";
        }
        if (name.contains("ORG") || name.contains("FACILIT")) {
            return "Organization";
        }
        for (String column : header) {
            String upper = column.toUpperCase(Locale.ROOT);
            if (upper.contains("DOB") || upper.contains("BIRTH")) {
                return "Patient";
            }
        }
        return null;
    }

    private static String primaryKey(List<String> header) {
        for (String column : header) {
            String upper = column.toUpperCase(Locale.ROOT);
            if (upper.endsWith("_ID") || upper.equals("ID") || upper.endsWith("ID")) {
                return column;
            }
        }
        return header.get(0);
    }

    /** The element a resource refers to another type by. */
    private static String reference(String type, String referenced) {
        return switch (referenced) {
            case "Patient" -> switch (type) {
                case "AllergyIntolerance" -> "patient.reference";
                case "Encounter", "Observation", "Condition", "MedicationStatement" -> "subject.reference";
                default -> null;
            };
            case "Encounter" -> Set.of("Observation", "Condition").contains(type) ? "encounter.reference" : null;
            case "Practitioner" -> switch (type) {
                case "Encounter" -> "participant[0].individual.reference";
                case "Observation" -> "performer[0].reference";
                case "Patient" -> "generalPractitioner[0].reference";
                default -> null;
            };
            case "Organization" -> "Patient".equals(type) ? "managingOrganization.reference" : null;
            default -> null;
        };
    }

    private static List<Map<String, Object>> columnMappings(String type, String upper, String column,
                                                            HealthLakeCsvMapper.Table data) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean person = "Patient".equals(type) || "Practitioner".equals(type);
        if (person && (upper.contains("FIRST") || upper.contains("GIVEN") || upper.equals("FNAME"))) {
            out.add(field("name[0].given[0]", column, direct()));
        } else if (person && (upper.contains("LAST") || upper.contains("FAMILY") || upper.contains("SURNAME")
                || upper.equals("LNAME"))) {
            out.add(field("name[0].family", column, direct()));
        } else if ("Patient".equals(type) && (upper.contains("DOB") || upper.contains("BIRTH"))) {
            out.add(field("birthDate", column, date(column, data, false)));
        } else if ("Patient".equals(type) && (upper.contains("SEX") || upper.contains("GENDER"))) {
            out.add(field("gender", column, Map.of("type", "valueMap", "mappings", genders(column, data))));
        } else if ("Patient".equals(type) && upper.contains("MRN")) {
            out.add(field("identifier[1].value", column, direct()));
            out.add(field("identifier[1].system", column, Map.of("type", "expression", "expression",
                    "urn:mrn")));
        } else if (person && upper.contains("PHONE")) {
            out.add(field("telecom[0].value", column, direct()));
            out.add(field("telecom[0].system", column, Map.of("type", "expression", "expression", "phone")));
        } else if (person && upper.contains("EMAIL")) {
            out.add(field("telecom[1].value", column, direct()));
            out.add(field("telecom[1].system", column, Map.of("type", "expression", "expression", "email")));
        } else if (person && (upper.contains("ZIP") || upper.contains("POSTAL"))) {
            out.add(field("address[0].postalCode", column, direct()));
        } else if (person && upper.contains("CITY")) {
            out.add(field("address[0].city", column, direct()));
        } else if (person && upper.equals("STATE")) {
            out.add(field("address[0].state", column, direct()));
        } else if (person && (upper.contains("ADDR") || upper.contains("STREET"))) {
            out.add(field("address[0].line[0]", column, direct()));
        } else if ("Practitioner".equals(type) && upper.contains("NPI")) {
            out.add(field("identifier[1].value", column, direct()));
            out.add(field("identifier[1].system", column, Map.of("type", "expression", "expression",
                    "http://hl7.org/fhir/sid/us-npi")));
        } else if (Set.of("Observation", "Condition", "AllergyIntolerance", "MedicationStatement").contains(type)
                && upper.contains("CODE") && !upper.contains("SYS")) {
            out.add(field(codePath(type) + ".coding[0].code", column, direct()));
        } else if (Set.of("Observation", "Condition", "AllergyIntolerance", "MedicationStatement").contains(type)
                && (upper.contains("DESC") || upper.contains("NAME") || upper.contains("DISPLAY"))) {
            out.add(field(codePath(type) + ".text", column, direct()));
        } else if ("Observation".equals(type) && (upper.contains("VALUE") || upper.contains("RESULT"))) {
            Map<String, Object> value = field("valueQuantity.value", column, direct());
            value.put("targetFhirType", "decimal");
            out.add(value);
        } else if ("Observation".equals(type) && upper.contains("UNIT")) {
            out.add(field("valueQuantity.unit", column, direct()));
        } else if ("Encounter".equals(type) && (upper.contains("START") || upper.contains("ADMIT")
                || upper.equals("DATE") || upper.endsWith("_DATE") || upper.endsWith("_DT"))
                && !upper.contains("DISCH") && !upper.contains("END")) {
            out.add(field("period.start", column, date(column, data, true)));
        } else if ("Encounter".equals(type) && (upper.contains("END") || upper.contains("DISCH"))) {
            out.add(field("period.end", column, date(column, data, true)));
        } else if ("Observation".equals(type) && (upper.contains("DATE") || upper.endsWith("_DT"))) {
            out.add(field("effectiveDateTime", column, date(column, data, true)));
        } else if ("Condition".equals(type) && (upper.contains("ONSET") || upper.contains("DATE"))) {
            out.add(field("onsetDateTime", column, date(column, data, true)));
        } else if ("Organization".equals(type) && upper.contains("NAME")) {
            out.add(field("name", column, direct()));
        }
        return out;
    }

    private static String codePath(String type) {
        return "MedicationStatement".equals(type) ? "medicationCodeableConcept" : "code";
    }

    private static List<Map<String, Object>> constants(String type, String key) {
        return switch (type) {
            case "Observation" -> List.of(field("status", key, Map.of("type", "expression", "expression", "final")));
            case "Encounter" -> List.of(field("status", key, Map.of("type", "expression", "expression",
                    "finished")), field("class.code", key, Map.of("type", "expression", "expression", "AMB")));
            case "MedicationStatement" -> List.of(field("status", key, Map.of("type", "expression", "expression",
                    "active")));
            default -> List.of();
        };
    }

    private static Map<String, Object> direct() {
        return Map.of("type", "direct");
    }

    private static Map<String, Object> field(String path, String column, Map<String, Object> transform) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fhirPath", path);
        field.put("sourceColumn", column);
        field.put("transform", transform);
        return field;
    }

    /** A date column as FHIR writes it: its format read from the sample values. */
    private static Map<String, Object> date(String column, HealthLakeCsvMapper.Table data, boolean dateTime) {
        String sample = null;
        for (Map<String, String> row : data.rows()) {
            String value = row.get(column);
            if (value != null && !value.isBlank()) {
                sample = value.trim();
                break;
            }
        }
        String from = sample == null ? null
                : sample.matches("\\d{4}-\\d{2}-\\d{2}") ? "yyyy-MM-dd"
                : sample.matches("\\d{2}/\\d{2}/\\d{4}") ? "MM/dd/yyyy"
                : sample.matches("\\d{8}") ? "yyyyMMdd"
                : sample.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?") ? (sample.length() > 16
                ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm")
                : null;
        if (from == null || ("yyyy-MM-dd".equals(from) && !dateTime)) {
            return direct();
        }
        boolean hasTime = from.contains("HH");
        String to = dateTime && hasTime ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd";
        return Map.of("type", "dateFormat", "fromPattern", from, "toPattern", to);
    }

    private static Map<String, String> genders(String column, HealthLakeCsvMapper.Table data) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (Map<String, String> row : data.rows()) {
            String value = row.get(column);
            if (value == null || value.isBlank() || mappings.containsKey(value)) {
                continue;
            }
            String lower = value.trim().toLowerCase(Locale.ROOT);
            mappings.put(value, lower.startsWith("m") ? "male" : lower.startsWith("f") ? "female"
                    : lower.startsWith("o") ? "other" : "unknown");
        }
        for (String[] standard : new String[][] {{"M", "male"}, {"F", "female"}, {"U", "unknown"},
            {"O", "other"}}) {
            mappings.putIfAbsent(standard[0], standard[1]);
        }
        return mappings;
    }
}

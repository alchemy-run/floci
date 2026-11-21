package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The data a {@code SYNTHEA} preload writes: a small synthetic population in the shape Synthea generates, each patient
 * with a few encounters and the vital signs taken at them, coded with LOINC and SNOMED CT. It is the same on every
 * preload, so tests can count on it.
 */
final class HealthLakeSynthea {

    static final int PATIENTS = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[][] NAMES = {
        {"Ada", "Lovelace", "female"}, {"Alan", "Turing", "male"}, {"Grace", "Hopper", "female"},
        {"Edsger", "Dijkstra", "male"}, {"Barbara", "Liskov", "female"}, {"Donald", "Knuth", "male"},
        {"Frances", "Allen", "female"}, {"John", "Backus", "male"}, {"Radia", "Perlman", "female"},
        {"Ken", "Thompson", "male"},
    };

    private HealthLakeSynthea() {
    }

    static List<JsonNode> population() {
        List<JsonNode> resources = new ArrayList<>();
        for (int i = 0; i < PATIENTS; i++) {
            String patientId = "synthea-patient-" + (i + 1);
            ObjectNode patient = MAPPER.createObjectNode();
            patient.put("resourceType", "Patient").put("id", patientId);
            ObjectNode name = patient.putArray("name").addObject();
            name.put("use", "official").put("family", NAMES[i][1]).putArray("given").add(NAMES[i][0]);
            patient.put("gender", NAMES[i][2])
                    .put("birthDate", LocalDate.of(1940 + i * 7, 1 + i, 1 + i * 2).toString());
            ObjectNode identifier = patient.putArray("identifier").addObject();
            identifier.put("system", "https://github.com/synthetichealth/synthea").put("value", patientId);
            ObjectNode address = patient.putArray("address").addObject();
            address.put("city", "Boston").put("state", "MA").put("country", "US");
            resources.add(patient);
            for (int visit = 0; visit < 2; visit++) {
                String encounterId = patientId + "-encounter-" + (visit + 1);
                String day = LocalDate.of(2025, 1 + visit * 5, 10 + i).toString();
                ObjectNode encounter = MAPPER.createObjectNode();
                encounter.put("resourceType", "Encounter").put("id", encounterId).put("status", "finished");
                encounter.putObject("class").put("system", "http://terminology.hl7.org/CodeSystem/v3-ActCode")
                        .put("code", "AMB");
                ArrayNode type = encounter.putArray("type");
                type.addObject().putArray("coding").addObject().put("system", "http://snomed.info/sct")
                        .put("code", "162673000").put("display", "General examination of patient (procedure)");
                encounter.putObject("subject").put("reference", "Patient/" + patientId);
                encounter.putObject("period").put("start", day + "T09:00:00Z").put("end", day + "T09:30:00Z");
                resources.add(encounter);
                resources.add(observation(patientId, encounterId, day, "8867-4", "Heart rate", 62 + i + visit * 3,
                        "/min"));
                resources.add(observation(patientId, encounterId, day, "29463-7", "Body weight", 58.0 + i * 2.5,
                        "kg"));
            }
        }
        return resources;
    }

    private static JsonNode observation(String patientId, String encounterId, String day, String code, String display,
                                        double value, String unit) {
        ObjectNode observation = MAPPER.createObjectNode();
        observation.put("resourceType", "Observation").put("id", encounterId + "-" + code).put("status", "final");
        observation.putArray("category").addObject().putArray("coding").addObject()
                .put("system", "http://terminology.hl7.org/CodeSystem/observation-category").put("code", "vital-signs");
        ObjectNode coding = observation.putObject("code").putArray("coding").addObject();
        coding.put("system", "http://loinc.org").put("code", code).put("display", display);
        observation.putObject("subject").put("reference", "Patient/" + patientId);
        observation.putObject("encounter").put("reference", "Encounter/" + encounterId);
        observation.put("effectiveDateTime", day + "T09:10:00Z");
        observation.putObject("valueQuantity").put("value", value).put("unit", unit)
                .put("system", "http://unitsofmeasure.org").put("code", unit);
        return observation;
    }
}

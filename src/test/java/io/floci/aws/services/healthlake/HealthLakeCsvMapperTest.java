package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HealthLake CSV profiles, run on the complete example of AWS's CSV profile reference and its building blocks. */
class HealthLakeCsvMapperTest {

    private static final String PROFILE = """
            sourceSystemId: acme-hospital
            tables:
              - tableName: PT_DEM
                primaryKeyColumn: PAT_ID
                resourceMappings:
                  - targetResourceType: Patient
                    fieldMappings:
                      - fhirPath: name[0].family
                        sourceColumn: LAST_NM
                        transform:
                          type: direct
                      - fhirPath: name[0].given[0]
                        sourceColumn: FIRST_NM
                      - fhirPath: name[0].text
                        sourceColumns: [FIRST_NM, LAST_NM]
                        transform:
                          type: concat
                          separator: " "
                      - fhirPath: birthDate
                        sourceColumn: DOB
                        transform:
                          type: dateFormat
                          fromPattern: MM/dd/yyyy
                          toPattern: yyyy-MM-dd
                      - fhirPath: gender
                        sourceColumn: SEX_CD
                        transform:
                          type: valueMap
                          mappings:
                            M: male
                            F: female
                      - fhirPath: deceasedBoolean
                        sourceColumn: DEATH_DT
                        transform:
                          type: conditional
                          conditionColumn: DEATH_DT
                          presentValue: "true"
                          absentValue: "false"
                        targetFhirType: boolean
                      - fhirPath: identifier[0].value
                        sourceColumn: RAW_ID
                        transform:
                          type: regex
                          pattern: "^[A-Z]+-(\\\\d+)$"
                          group: 1
                      - fhirPath: identifier[0].system
                        sourceColumn: PAT_ID
                        transform:
                          type: expression
                          expression: "urn:acme:${PAT_ID}"
                    aggregationMappings:
                      - childTable: PT_PHONE
                        joinColumn: PAT_ID
                        targetFhirArray: telecom
                        childFieldMappings:
                          - fhirPath: value
                            sourceColumn: PHONE_NUM
                          - fhirPath: system
                            sourceColumn: PHONE_NUM
                            transform:
                              type: expression
                              expression: phone
                          - fhirPath: use
                            sourceColumn: PHONE_TYPE
                            transform:
                              type: valueMap
                              mappings:
                                H: home
                                W: work
              - tableName: PROVIDERS
                primaryKeyColumn: PROV_ID
                resourceMappings:
                  - targetResourceType: Practitioner
                    fieldMappings:
                      - fhirPath: name[0].family
                        sourceColumn: PROV_LAST
              - tableName: ENCOUNTERS
                primaryKeyColumn: ENC_ID
                foreignKeys:
                  - column: PAT_ID
                    referencedTable: PT_DEM
                    referencedColumn: PAT_ID
                resourceMappings:
                  - targetResourceType: Encounter
                    fieldMappings:
                      - fhirPath: status
                        sourceColumn: ENC_ID
                        transform:
                          type: expression
                          expression: finished
                      - fhirPath: subject.reference
                        sourceColumn: PAT_ID
                        transform:
                          type: resourceReference
                          referencedTable: PT_DEM
                          referencedResourceType: Patient
                      - fhirPath: participant[0].individual.reference
                        sourceColumn: PROV_ID
                        transform:
                          type: resourceReference
                          referencedTable: PROVIDERS
                          referencedResourceType: Practitioner
                      - fhirPath: period.start
                        sourceColumn: ADMIT_DT
                        transform:
                          type: dateFormat
                          fromPattern: yyyyMMddHHmmXX
                          toPattern: "yyyy-MM-dd'T'HH:mm:ssXXX"
              - tableName: CONDITION_CODES
                primaryKeyColumn: COND_ID
                groupByPk: true
                resourceMappings:
                  - targetResourceType: Condition
                    fieldMappings:
                      - fhirPath: subject.reference
                        sourceColumn: PAT_ID
                        transform:
                          type: resourceReference
                          referencedTable: PT_DEM
                          referencedResourceType: Patient
                    arrayFieldMappings:
                      - fhirPath: code.coding[].code
                        sourceColumn: CODE
                      - fhirPath: code.coding[].system
                        sourceColumn: CODE_SYSTEM
                        transform:
                          type: valueMap
                          mappings:
                            ICD10: http://hl7.org/fhir/sid/icd-10-cm
                            SNOMED: http://snomed.info/sct
            """;

    private static Map<String, HealthLakeCsvMapper.Table> tables() {
        Map<String, HealthLakeCsvMapper.Table> tables = new LinkedHashMap<>();
        tables.put("PT_DEM", HealthLakeCsvMapper.parse("PAT_ID,LAST_NM,FIRST_NM,DOB,SEX_CD,DEATH_DT,RAW_ID\n"
                + "P1,Smith,John,03/15/1985,M,,MRN-123\n\"P2\",\"Garcia, Jr\",Maria,07/22/1990,X,2020-01-01,bad\n"));
        tables.put("PT_PHONE", HealthLakeCsvMapper.parse("PAT_ID\tPHONE_NUM\tPHONE_TYPE\nP1\t555-0100\tH\n"
                + "P1\t555-0101\tW\n"));
        tables.put("PROVIDERS", HealthLakeCsvMapper.parse("PROV_ID|PROV_LAST\nD1|House\n"));
        tables.put("ENCOUNTERS", HealthLakeCsvMapper.parse("ENC_ID,PAT_ID,PROV_ID,ADMIT_DT\n"
                + "E1,P1,D1,202601151430-0500\n"));
        tables.put("CONDITION_CODES", HealthLakeCsvMapper.parse("COND_ID,PAT_ID,CODE,CODE_SYSTEM\n"
                + "C1,P1,E11.9,ICD10\nC1,P1,44054006,SNOMED\n"));
        return tables;
    }

    private static JsonNode find(List<JsonNode> resources, String type, int index) {
        return resources.stream().filter(resource -> type.equals(resource.path("resourceType").asText()))
                .skip(index).findFirst().orElseThrow();
    }

    @Test
    void convertsTheReferenceExample() {
        HealthLakeCsvMapper.Result result = HealthLakeCsvMapper.convert(HealthLakeCsvMapper.profile(PROFILE),
                tables(), "store");
        assertEquals(5, result.resources().size(), "2 patients, a practitioner, an encounter, one grouped condition");
        JsonNode john = find(result.resources(), "Patient", 0);
        assertEquals("Smith", john.path("name").path(0).path("family").asText());
        assertEquals("John", john.path("name").path(0).path("given").path(0).asText());
        assertEquals("John Smith", john.path("name").path(0).path("text").asText());
        assertEquals("1985-03-15", john.path("birthDate").asText());
        assertEquals("male", john.path("gender").asText());
        assertFalse(john.path("deceasedBoolean").asBoolean(true));
        assertTrue(john.path("deceasedBoolean").isBoolean());
        assertEquals("123", john.path("identifier").path(0).path("value").asText());
        assertEquals("urn:acme:P1", john.path("identifier").path(0).path("system").asText());
        assertEquals(2, john.path("telecom").size());
        assertEquals("work", john.path("telecom").path(1).path("use").asText());
        assertEquals("phone", john.path("telecom").path(0).path("system").asText());

        JsonNode maria = find(result.resources(), "Patient", 1);
        assertEquals("Garcia, Jr", maria.path("name").path(0).path("family").asText());
        assertTrue(maria.path("gender").isMissingNode(), "X is not in the valueMap");
        assertTrue(maria.path("deceasedBoolean").asBoolean());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("valueMap")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("does not match")));

        JsonNode encounter = find(result.resources(), "Encounter", 0);
        assertEquals("Patient/" + john.path("id").asText(), encounter.path("subject").path("reference").asText());
        assertEquals("Practitioner/" + find(result.resources(), "Practitioner", 0).path("id").asText(),
                encounter.path("participant").path(0).path("individual").path("reference").asText());
        assertEquals("2026-01-15T14:30:00-05:00", encounter.path("period").path("start").asText());

        JsonNode condition = find(result.resources(), "Condition", 0);
        assertEquals(2, condition.path("code").path("coding").size());
        assertEquals("http://snomed.info/sct", condition.path("code").path("coding").path(1).path("system").asText());

        HealthLakeCsvMapper.Result again = HealthLakeCsvMapper.convert(HealthLakeCsvMapper.profile(PROFILE),
                tables(), "store");
        assertEquals(john.path("id"), find(again.resources(), "Patient", 0).path("id"), "ids are stable");
    }

    @Test
    void rejectsProfilesAndTablesThatDoNotFit() {
        assertThrows(IllegalArgumentException.class, () -> HealthLakeCsvMapper.profile("tables: []"));
        assertThrows(IllegalArgumentException.class, () -> HealthLakeCsvMapper.profile("""
                sourceSystemId: s
                tables:
                  - tableName: T
                    primaryKeyColumn: ID
                    resourceMappings:
                      - targetResourceType: Patient
                        fieldMappings:
                          - fhirPath: name[0].text
                            sourceColumn: A
                            transform:
                              type: concat
                              separator: " "
                """));
        Map<String, HealthLakeCsvMapper.Table> missingColumn = tables();
        missingColumn.put("PROVIDERS", HealthLakeCsvMapper.parse("PROV_ID\nD1\n"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HealthLakeCsvMapper.convert(HealthLakeCsvMapper.profile(PROFILE), missingColumn, "store"));
        assertTrue(error.getMessage().contains("PROV_LAST"), error.getMessage());
        Map<String, HealthLakeCsvMapper.Table> missingTable = tables();
        missingTable.remove("ENCOUNTERS");
        assertThrows(IllegalArgumentException.class,
                () -> HealthLakeCsvMapper.convert(HealthLakeCsvMapper.profile(PROFILE), missingTable, "store"));
    }

    @Test
    void embedsJsonAndKeepsMappedSubPaths() {
        String profile = """
                sourceSystemId: s
                tables:
                  - tableName: T
                    primaryKeyColumn: ID
                    resourceMappings:
                      - targetResourceType: Patient
                        fieldMappings:
                          - fhirPath: address[0].city
                            sourceColumn: CITY
                          - fhirPath: address[0]
                            sourceColumn: ADDRESS_JSON
                            targetFhirType: json
                """;
        HealthLakeCsvMapper.Result result = HealthLakeCsvMapper.convert(HealthLakeCsvMapper.profile(profile),
                Map.of("T", HealthLakeCsvMapper.parse("ID,CITY,ADDRESS_JSON\n1,Tacoma,\"{\"\"city\"\":\"\"Seattle\"\","
                        + "\"\"state\"\":\"\"WA\"\"}\"\n")), "store");
        JsonNode address = result.resources().get(0).path("address").path(0);
        assertEquals("Tacoma", address.path("city").asText());
        assertEquals("WA", address.path("state").asText());
    }
}

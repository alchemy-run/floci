package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.awaitDatastore;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.awaitJob;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.healthlake;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.keys;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.role;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.s3Get;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.s3Put;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HealthLake's Data Transformation Agent: a C-CDA profile from the starter kit, a CSV profile the agent drafts from
 * sample data, publishing, the synchronous conversion, a transformation job to S3, and an import that converts its
 * source files into a data store.
 */
@QuarkusTest
class HealthLakeTransformIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CCDA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ClinicalDocument xmlns="urn:hl7-org:v3">
              <templateId root="2.16.840.1.113883.10.20.22.1.2"/>
              <recordTarget><patientRole>
                <id root="2.16.840.1.113883.19.5" extension="MRN-1"/>
                <addr><streetAddressLine>1 Main St</streetAddressLine><city>Boston</city><state>MA</state>
                  <postalCode>02134</postalCode></addr>
                <patient>
                  <name><given>Ada</given><given>Augusta</given><family>Lovelace</family></name>
                  <administrativeGenderCode code="F" codeSystem="2.16.840.1.113883.5.1"/>
                  <birthTime value="19801210"/>
                </patient>
              </patientRole></recordTarget>
              <component><structuredBody>
                <component><section>
                  <templateId root="2.16.840.1.113883.10.20.22.2.5.1"/>
                  <entry><act><entryRelationship typeCode="SUBJ"><observation>
                    <effectiveTime><low value="20200101"/></effectiveTime>
                    <value code="44054006" codeSystem="2.16.840.1.113883.6.96" displayName="Diabetes mellitus type 2"/>
                  </observation></entryRelationship></act></entry>
                </section></component>
                <component><section>
                  <templateId root="2.16.840.1.113883.10.20.22.2.3.1"/>
                  <entry><organizer><component><observation>
                    <code code="2345-7" codeSystem="2.16.840.1.113883.6.1" displayName="Glucose"/>
                    <effectiveTime value="20250102083000"/>
                    <value value="95" unit="mg/dL"/>
                  </observation></component></organizer></entry>
                </section></component>
                <component><section>
                  <templateId root="2.16.840.1.113883.10.20.22.2.14"/>
                </section></component>
              </structuredBody></component>
            </ClinicalDocument>
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/healthlake/aws4_request";
    }

    private static JsonNode transformData(String body) throws Exception {
        String response = given().header("Authorization", auth()).contentType("application/json").body(body)
                .post("/transform-data").then().statusCode(200).extract().asString();
        return MAPPER.readTree(response);
    }

    private static List<JsonNode> entries(JsonNode bundle, String type) {
        List<JsonNode> out = new ArrayList<>();
        bundle.path("entry").forEach(entry -> {
            if (type.equals(entry.path("resource").path("resourceType").asText())) {
                out.add(entry.path("resource"));
            }
        });
        return out;
    }

    @Test
    void aCcdaStarterProfileConvertsADocument() throws Exception {
        healthlake("CreateDataTransformationProfile", "{\"SourceFormat\":\"CSV\",\"ProfileName\":\"bad\",\"Source\":"
                + "{\"StarterProfile\":{\"StarterProfileName\":\"ccda-starter-kit-v1\"}}}").statusCode(400);
        String profile = healthlake("CreateDataTransformationProfile", "{\"SourceFormat\":\"CCDA\",\"ProfileName\":"
                + "\"cardiology\",\"Source\":{\"StarterProfile\":{\"StarterProfileName\":\"ccda-starter-kit-v1\"}}}")
                .statusCode(200).body("Version", equalTo(0)).body("TargetFormat", equalTo("FHIR_R4"))
                .extract().path("ProfileId");
        healthlake("GetDataTransformationProfile", "{\"ProfileId\":\"" + profile + "\"}").statusCode(200)
                .body("Version", equalTo(0)).body("ProfileMapping", hasKey("Resources/Patient.vm"));
        given().header("Authorization", auth()).contentType("application/json").body("{\"ProfileId\":\"" + profile
                + "\",\"InputData\":{\"CcdaInput\":\"<x/>\"}}").post("/transform-data").then().statusCode(400)
                .body("message", containsString("no published version"));
        healthlake("PublishDataTransformationProfile", "{\"ProfileId\":\"" + profile + "\",\"SourceFormat\":\"CCDA\","
                + "\"ChangeDescription\":\"first\"}").statusCode(200).body("Version", equalTo(1));
        healthlake("ListDataTransformationProfileVersions", "{\"ProfileId\":\"" + profile + "\"}").statusCode(200)
                .body("Items.Version", equalTo(List.of(1, 0)));

        JsonNode result = transformData(MAPPER.createObjectNode().put("ProfileId", profile)
                .put("DriftDetectionEnabled", true).set("InputData", MAPPER.createObjectNode().put("CcdaInput", CCDA))
                .toString());
        JsonNode bundle = MAPPER.readTree(result.path("TransformedData").asText());
        assertEquals("collection", bundle.path("type").asText());
        JsonNode patient = entries(bundle, "Patient").get(0);
        assertEquals("Lovelace", patient.path("name").path(0).path("family").asText());
        assertEquals(List.of("Ada", "Augusta"), List.of(patient.path("name").path(0).path("given").path(0).asText(),
                patient.path("name").path(0).path("given").path(1).asText()));
        assertEquals("female", patient.path("gender").asText());
        assertEquals("1980-12-10", patient.path("birthDate").asText());
        assertEquals("Boston", patient.path("address").path(0).path("city").asText());
        JsonNode condition = entries(bundle, "Condition").get(0);
        assertEquals("http://snomed.info/sct", condition.path("code").path("coding").path(0).path("system").asText());
        assertEquals("Patient/" + patient.path("id").asText(), condition.path("subject").path("reference").asText());
        JsonNode observation = entries(bundle, "Observation").get(0);
        assertEquals("http://loinc.org", observation.path("code").path("coding").path(0).path("system").asText());
        assertEquals(95, observation.path("valueQuantity").path("value").asInt());
        assertEquals("2025-01-02T08:30:00.000Z", observation.path("effectiveDateTime").asText());
        assertEquals(1, entries(bundle, "Provenance").size());
        JsonNode drift = MAPPER.readTree(result.path("DriftReport").asText()).path("fileDriftResults").path(0);
        assertEquals("2.16.840.1.113883.10.20.22.1.2", drift.path("documentOid").asText());
        assertTrue(drift.path("unknownSections").has("2.16.840.1.113883.10.20.22.2.14"), drift.toString());

        String agent = given().header("Authorization", auth()).header("X-Amz-Target",
                "HealthLake.UpdateProfileWithAgent").contentType("application/x-amz-json-1.0")
                .body("{\"ProfileId\":\"" + profile + "\",\"InputMessage\":{\"Body\":\"Add a mapping for Medication "
                        + "resources\",\"Type\":\"normal\"}}").post("/").then().statusCode(200)
                .contentType("application/vnd.amazon.eventstream").extract().asString();
        assertTrue(agent.contains("EndOfOutputEvent") && agent.contains("Medications"), agent);
        healthlake("DeleteDataTransformationProfile", "{\"ProfileId\":\"" + profile + "\"}").statusCode(200)
                .body("ProfileName", equalTo("cardiology"));
    }

    @Test
    void theAgentDraftsACsvProfileThatJobsAndImportsUse() throws Exception {
        given().header("Authorization", auth().replace("healthlake", "s3")).put("/healthlake-csv").then()
                .statusCode(200);
        String patients = "PAT_ID,FIRST_NM,LAST_NM,DOB,SEX_CD,PHONE\nP1,John,Smith,03/15/1985,M,555-0100\n"
                + "P2,Maria,Garcia,07/22/1990,F,\n";
        String encounters = "ENC_ID,PAT_ID,ADMIT_DT\nE1,P1,2026-01-15 14:30\n";
        s3Put("healthlake-csv", "samples/PATIENTS.csv", patients);
        s3Put("healthlake-csv", "samples/ENCOUNTERS.csv", encounters);
        String profile = healthlake("CreateDataTransformationProfile", "{\"SourceFormat\":\"CSV\",\"ProfileName\":"
                + "\"intake\",\"Source\":{\"SampleData\":{\"S3Uri\":\"s3://healthlake-csv/samples/\"}}}")
                .statusCode(200).extract().path("ProfileId");
        healthlake("GetDataTransformationProfile", "{\"ProfileId\":\"" + profile + "\"}").statusCode(200)
                .body("ProfileMapping.size()", equalTo(0));
        String turn = given().header("Authorization", auth()).header("X-Amz-Target",
                "HealthLake.UpdateProfileWithAgent").contentType("application/x-amz-json-1.0")
                .body("{\"ProfileId\":\"" + profile + "\",\"InputMessage\":{\"Body\":\"Generate a profile to convert "
                        + "my CSVs into FHIR.\",\"Type\":\"normal\"}}").post("/").then().statusCode(200).extract()
                .asString();
        assertTrue(turn.contains("PATIENTS (Patient)") && turn.contains("ENCOUNTERS (Encounter)"), turn);
        String yaml = healthlake("GetDataTransformationProfile", "{\"ProfileId\":\"" + profile + "\"}")
                .statusCode(200).extract().path("ProfileMapping.'mapping.yaml'");
        assertTrue(yaml.contains("birthDate") && yaml.contains("resourceReference"), yaml);
        given().header("Authorization", auth()).header("X-Amz-Target", "HealthLake.UpdateProfileWithAgent")
                .contentType("application/x-amz-json-1.0").body("{\"ProfileId\":\"" + profile + "\",\"InputMessage\":"
                        + "{\"Body\":\"Publish the profile\",\"Type\":\"normal\"}}").post("/").then().statusCode(200)
                .body(containsString("version 1"));

        String sync = given().header("Authorization", auth()).contentType("application/json").body(MAPPER
                .createObjectNode().put("ProfileId", profile).put("ProvenanceEnabled", false).set("InputData",
                        MAPPER.createObjectNode().set("CsvInput", MAPPER.createObjectNode().put("PATIENTS", patients)
                                .put("ENCOUNTERS", encounters))).toString()).post("/transform-data").then()
                .statusCode(200).extract().path("TransformedData");
        JsonNode bundle = MAPPER.readTree(sync);
        assertEquals("transaction", bundle.path("type").asText());
        JsonNode john = entries(bundle, "Patient").get(0);
        assertEquals("1985-03-15", john.path("birthDate").asText());
        assertEquals("male", john.path("gender").asText());
        assertEquals("Patient/" + john.path("id").asText(), entries(bundle, "Encounter").get(0).path("subject")
                .path("reference").asText());
        assertEquals("2026-01-15T14:30:00Z", entries(bundle, "Encounter").get(0).path("period").path("start")
                .asText());

        String role = role("healthlake-csv");
        String job = healthlake("StartDataTransformationJob", "{\"ProfileId\":\"" + profile + "\",\"ClientToken\":"
                + "\"csv-1\",\"JobName\":\"intake\",\"DataAccessRoleArn\":\"" + role + "\",\"DriftDetectionEnabled\":"
                + "true,\"InputDataConfig\":{\"S3Uri\":\"s3://healthlake-csv/samples/\",\"SourceFormat\":\"CSV\"},"
                + "\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-csv/fhir\",\"KmsKeyId\":"
                + "\"k\"}}}").statusCode(200).body("JobStatus", equalTo("SUBMITTED")).extract().path("JobId");
        String status = null;
        for (int attempt = 0; attempt < 100 && !"COMPLETED".equals(status); attempt++) {
            status = healthlake("DescribeDataTransformationJob", "{\"JobId\":\"" + job + "\"}").statusCode(200)
                    .extract().path("TransformationJobProperties.JobStatus");
            Thread.sleep(100);
        }
        healthlake("DescribeDataTransformationJob", "{\"JobId\":\"" + job + "\"}")
                .body("TransformationJobProperties.JobStatus", equalTo("COMPLETED"))
                .body("TransformationJobProperties.ProfileVersion", equalTo(1))
                .body("TransformationJobProperties.JobProgressReport.TotalFilesConverted", equalTo(2));
        assertTrue(keys("healthlake-csv", "fhir/" + job + "/").contains("fhir/" + job + "/Patient.ndjson"));
        assertEquals(2, s3Get("healthlake-csv", "fhir/" + job + "/Patient.ndjson").lines().count());
        assertTrue(s3Get("healthlake-csv", "fhir/" + job + "/drift-report.json").contains("\"columnsCoverageRate\""));
        healthlake("ListDataTransformationJobs", "{\"JobStatus\":\"COMPLETED\"}").statusCode(200)
                .body("Items.JobId", hasItem(job));

        String datastore = healthlake("CreateFHIRDatastore", "{\"DatastoreTypeVersion\":\"R4\"}").statusCode(200)
                .extract().path("DatastoreId");
        awaitDatastore(datastore, "ACTIVE");
        String imported = healthlake("StartFHIRImportJob", "{\"DatastoreId\":\"" + datastore + "\",\"DataAccessRoleArn\":"
                + "\"" + role + "\",\"ProfileId\":\"" + profile + "\",\"InputFormat\":\"CSV\",\"InputDataConfig\":"
                + "{\"S3Uri\":\"s3://healthlake-csv/samples/\"},\"JobOutputDataConfig\":{\"S3Configuration\":"
                + "{\"S3Uri\":\"s3://healthlake-csv/import\",\"KmsKeyId\":\"k\"}}}").statusCode(200)
                .extract().path("JobId");
        assertEquals("COMPLETED", awaitJob("DescribeFHIRImportJob", datastore, imported, "ImportJobProperties"));
        healthlake("DescribeFHIRImportJob", "{\"DatastoreId\":\"" + datastore + "\",\"JobId\":\"" + imported + "\"}")
                .body("ImportJobProperties.JobProgressReport.TotalFilesConverted", equalTo(2));
        String exported = healthlake("StartFHIRExportJob", "{\"DatastoreId\":\"" + datastore + "\","
                + "\"DataAccessRoleArn\":\"" + role + "\",\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":"
                + "\"s3://healthlake-csv/export\",\"KmsKeyId\":\"k\"}}}").statusCode(200).extract().path("JobId");
        awaitJob("DescribeFHIRExportJob", datastore, exported, "ExportJobProperties");
        String exportedPatients = s3Get("healthlake-csv", "export/" + datastore + "-FHIR_EXPORT-" + exported
                + "/Patient/Patient-0.ndjson");
        assertEquals(2, exportedPatients.lines().count());
        assertTrue(new String(exportedPatients.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .contains("Garcia"));
    }
}

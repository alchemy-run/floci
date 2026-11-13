package io.floci.aws.services.healthlake;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.awaitJob;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.healthlake;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.role;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.s3Get;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.s3Put;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HealthLake data stores backed by HAPI FHIR servers: imports land in the server, its FHIR API answers at the data
 * store's endpoint to signed requests, and exports read what clients wrote through it.
 */
@Tag("docker")
@QuarkusTest
@TestProfile(HealthLakeDockerIntegrationTest.RealFhirProfile.class)
class HealthLakeDockerIntegrationTest {

    public static class RealFhirProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.healthlake.mock", "false",
                    "floci.services.healthlake.host-port-base", "18400",
                    "floci.services.healthlake.host-port-max", "18499");
        }
    }

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (RuntimeException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for HealthLake FHIR server tests");
    }

    private static RequestSpecification fhir() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/"
                + "healthlake/aws4_request, SignedHeaders=host, Signature=0").accept("application/fhir+json");
    }

    private static void await(String id, String status) {
        for (int attempt = 0; attempt < 360; attempt++) {
            String current = healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(200)
                    .extract().path("DatastoreProperties.DatastoreStatus");
            if (status.equals(current)) {
                return;
            }
            if ("CREATE_FAILED".equals(current)) {
                fail("Data store " + id + " failed to create");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("Data store " + id + " did not become " + status);
    }

    @Test
    void theEndpointServesTheDatastoresFhirServer() {
        String id = healthlake("CreateFHIRDatastore", "{\"DatastoreTypeVersion\":\"R4\"}").statusCode(200)
                .extract().path("DatastoreId");
        try {
            await(id, "ACTIVE");
            given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/"
                    + "aws4_request").put("/healthlake-hapi").then().statusCode(200);
            s3Put("healthlake-hapi", "in/p.ndjson", "{\"resourceType\":\"Patient\",\"id\":\"123\",\"name\":"
                    + "[{\"family\":\"Hapi\"}]}\n{\"resourceType\":\"Patient\",\"id\":\"bad id!\"}\n");
            String role = role("healthlake-hapi");
            String job = healthlake("StartFHIRImportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":\""
                    + role + "\",\"InputDataConfig\":{\"S3Uri\":\"s3://healthlake-hapi/in/\"},\"JobOutputDataConfig\":"
                    + "{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-hapi/out\",\"KmsKeyId\":\"k\"}}}")
                    .statusCode(200).extract().path("JobId");
            assertEquals("COMPLETED_WITH_ERRORS", awaitJob("DescribeFHIRImportJob", id, job, "ImportJobProperties"));

            String path = "/datastore/" + id + "/r4/";
            fhir().get(path + "Patient/123").then().statusCode(200).body("name[0].family", equalTo("Hapi"));
            given().get(path + "Patient/123").then().statusCode(403);
            fhir().get(path + "metadata").then().statusCode(200).body("fhirVersion", equalTo("4.0.1"));
            fhir().contentType("application/fhir+json").body("{\"resourceType\":\"Observation\",\"status\":\"final\","
                    + "\"code\":{\"text\":\"pulse\"},\"subject\":{\"reference\":\"Patient/123\"}}")
                    .post(path + "Observation").then().statusCode(201);
            fhir().get(path + "Observation?subject=Patient/123").then().statusCode(200).body("total", equalTo(1))
                    .body("link[0].url", containsString("/datastore/" + id + "/r4/"));

            String exported = healthlake("StartFHIRExportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":"
                    + "\"" + role + "\",\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-hapi/"
                    + "export\",\"KmsKeyId\":\"k\"}}}").statusCode(200).extract().path("JobId");
            assertEquals("COMPLETED", awaitJob("DescribeFHIRExportJob", id, exported, "ExportJobProperties"));
            String observations = s3Get("healthlake-hapi", "export/" + id + "-FHIR_EXPORT-" + exported
                    + "/Observation/Observation-0.ndjson");
            assertTrue(observations.contains("pulse"), observations);
        } finally {
            healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}");
        }
        for (int attempt = 0; attempt < 60; attempt++) {
            if (dockerClient.listContainersCmd().withShowAll(true).withNameFilter(List.of(
                    HealthLakeFhirServers.containerName(id))).exec().isEmpty()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("The data store's FHIR server was not removed");
    }
}

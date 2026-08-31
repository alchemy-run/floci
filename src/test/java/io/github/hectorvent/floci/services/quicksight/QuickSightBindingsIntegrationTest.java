package io.github.hectorvent.floci.services.quicksight;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Alchemy {@code test/AWS/QuickSight/Bindings.test.ts}: typed-error probes and
 * the SPICE ingestion / snapshot-job / embed-URL data plane.
 */
@QuarkusTest
class QuickSightBindingsIntegrationTest {

    private static final String ACCOUNT = "000000000901";
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createIngestionOnMissingDataSetYieldsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"IngestionType\":\"FULL_REFRESH\"}")
                .when()
                .put("/accounts/" + ACCOUNT
                        + "/data-sets/alchemy-nonexistent-quicksight-dataset-probe"
                        + "/ingestions/alchemy-quicksight-ingestion-probe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceType", equalTo("DATA_SET"));
    }

    @Test
    void generateEmbedUrlForMissingUserYieldsQuickSightUserNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "UserArn":"arn:aws:quicksight:us-east-1:000000000901:user/default/alchemy-nonexistent-user-probe",
                          "ExperienceConfiguration":{"Dashboard":{"InitialDashboardId":"alchemy-nonexistent-dashboard-probe"}}
                        }
                        """)
                .when()
                .post("/accounts/" + ACCOUNT + "/embed-url/registered-user")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("QuickSightUserNotFoundException"))
                .body("__type", equalTo("QuickSightUserNotFoundException"));
    }

    @Test
    void generateEmbedUrlRejectsMalformedUserArn() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "UserArn":"arn:invalid",
                          "ExperienceConfiguration":{"Dashboard":{"InitialDashboardId":"dash"}}
                        }
                        """)
                .when()
                .post("/accounts/" + ACCOUNT + "/embed-url/registered-user")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidParameterValueException"))
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void describeSnapshotJobOnMissingDashboardYieldsResourceNotFound() {
        given()
                .header("Authorization", auth(ACCOUNT))
                .when()
                .get("/accounts/" + ACCOUNT
                        + "/dashboards/alchemy-nonexistent-quicksight-dashboard-probe"
                        + "/snapshot-jobs/alchemy-nonexistent-snapshot-job-probe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void spiceIngestionAndDirectQueryRejection() {
        String authorization = auth(ACCOUNT);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DataSourceId":"bindings-source",
                          "Name":"Bindings Source",
                          "Type":"ATHENA",
                          "DataSourceParameters":{"AthenaParameters":{"WorkGroup":"primary"}}
                        }
                        """)
                .when()
                .post("/accounts/" + ACCOUNT + "/data-sources")
                .then()
                .statusCode(202)
                .body("DataSourceId", equalTo("bindings-source"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DataSetId":"direct-set",
                          "Name":"Direct",
                          "ImportMode":"DIRECT_QUERY",
                          "PhysicalTableMap":{
                            "probe":{
                              "CustomSql":{
                                "DataSourceArn":"arn:aws:quicksight:us-east-1:000000000901:datasource/bindings-source",
                                "Name":"probe",
                                "SqlQuery":"SELECT 1 AS n",
                                "Columns":[{"Name":"n","Type":"INTEGER"}]
                              }
                            }
                          }
                        }
                        """)
                .when()
                .post("/accounts/" + ACCOUNT + "/data-sets")
                .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"IngestionType\":\"FULL_REFRESH\"}")
                .when()
                .put("/accounts/" + ACCOUNT + "/data-sets/direct-set/ingestions/direct-ing")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidParameterValueException"))
                .body("__type", equalTo("InvalidParameterValueException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DataSetId":"spice-set",
                          "Name":"Spice",
                          "ImportMode":"SPICE",
                          "PhysicalTableMap":{
                            "probe":{
                              "CustomSql":{
                                "DataSourceArn":"arn:aws:quicksight:us-east-1:000000000901:datasource/bindings-source",
                                "Name":"probe",
                                "SqlQuery":"SELECT 1 AS n",
                                "Columns":[{"Name":"n","Type":"INTEGER"}]
                              }
                            }
                          }
                        }
                        """)
                .when()
                .post("/accounts/" + ACCOUNT + "/data-sets")
                .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"IngestionType\":\"FULL_REFRESH\"}")
                .when()
                .put("/accounts/" + ACCOUNT + "/data-sets/spice-set/ingestions/spice-ing")
                .then()
                .statusCode(201)
                .body("IngestionId", equalTo("spice-ing"))
                .body("IngestionStatus", equalTo("INITIALIZED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts/" + ACCOUNT + "/data-sets/spice-set/ingestions/spice-ing")
                .then()
                .statusCode(200)
                .body("Ingestion.IngestionStatus", equalTo("INITIALIZED"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/accounts/" + ACCOUNT + "/data-sets/spice-set/ingestions/spice-ing")
                .then()
                .statusCode(200)
                .body("IngestionId", equalTo("spice-ing"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts/" + ACCOUNT + "/data-sets/spice-set/ingestions")
                .then()
                .statusCode(200)
                .body("Ingestions[0].IngestionStatus", equalTo("CANCELLED"));
    }

    @Test
    void snapshotJobAndAnonymousEmbedRoundTrip() {
        String authorization = auth(ACCOUNT);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"Bindings Dashboard\",\"Definition\":{\"Sheets\":[{\"SheetId\":\"sheet1\"}]}}")
                .when()
                .post("/accounts/" + ACCOUNT + "/dashboards/bindings-dash")
                .then()
                .statusCode(201)
                .body("DashboardId", equalTo("bindings-dash"))
                .body("CreationStatus", equalTo("CREATION_SUCCESSFUL"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts/" + ACCOUNT + "/dashboards/bindings-dash/snapshot-jobs/missing-job")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SnapshotJobId":"job-1",
                          "UserConfiguration":{"AnonymousUsers":[]},
                          "SnapshotConfiguration":{"FileGroups":[{"Files":[{"FormatType":"PDF"}]}]}
                        }
                        """)
                .when()
                .post("/accounts/" + ACCOUNT + "/dashboards/bindings-dash/snapshot-jobs")
                .then()
                .statusCode(201)
                .body("SnapshotJobId", equalTo("job-1"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts/" + ACCOUNT + "/dashboards/bindings-dash/snapshot-jobs/job-1")
                .then()
                .statusCode(200)
                .body("JobStatus", equalTo("COMPLETED"))
                .body("DashboardId", equalTo("bindings-dash"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Namespace":"default",
                          "AuthorizedResourceArns":["arn:aws:quicksight:us-east-1:000000000901:dashboard/bindings-dash"],
                          "ExperienceConfiguration":{"Dashboard":{"InitialDashboardId":"bindings-dash"}}
                        }
                        """)
                .when()
                .post("/accounts/" + ACCOUNT + "/embed-url/anonymous-user")
                .then()
                .statusCode(200)
                .body("EmbedUrl", notNullValue());
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + REGION + "/quicksight/aws4_request";
    }
}

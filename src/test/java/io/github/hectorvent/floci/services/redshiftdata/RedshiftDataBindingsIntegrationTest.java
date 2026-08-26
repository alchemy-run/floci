package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * JSON 1.1 coverage for Alchemy's Redshift Data bindings suite: typed
 * not-found / unknown-workgroup probes plus execute / batch / metadata
 * / list / cancel / CSV result round-trips.
 */
@QuarkusTest
class RedshiftDataBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/redshift-data/aws4_request";
    private static final String WORKGROUP = "alchemy-test-rsd-wg";
    private static final String MISSING_ID = "d9b6c0c9-0747-4bf4-b142-e8883122f766";

    @Inject
    RedshiftDataService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void describeStatement_missingId_returnsResourceNotFound() {
        redshift("DescribeStatement", "{\"Id\":\"" + MISSING_ID + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceId", equalTo(MISSING_ID));
    }

    @Test
    void listDatabases_unknownWorkgroup_returnsValidationException() {
        redshift("ListDatabases",
                "{\"Database\":\"dev\",\"WorkgroupName\":\"alchemy-test-rsd-does-not-exist\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("alchemy-test-rsd-does-not-exist"));
    }

    @Test
    void executeDescribeGetResult_selectLiteral_roundTrips() {
        String id = redshift("ExecuteStatement",
                "{\"Sql\":\"SELECT 1 AS n\",\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .body("Id", org.hamcrest.Matchers.notNullValue())
                .extract().path("Id");

        redshift("DescribeStatement", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("FINISHED"))
                .body("HasResultSet", equalTo(true));

        redshift("GetStatementResult", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Records", hasSize(1))
                .body("Records[0][0].longValue", equalTo(1))
                .body("TotalNumRows", equalTo(1));
    }

    @Test
    void batchExecute_subStatementResult() {
        String id = redshift("BatchExecuteStatement",
                "{\"Sqls\":[\"SELECT 1 AS a\",\"SELECT 2 AS b\"],"
                        + "\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .extract().path("Id");

        String secondId = redshift("DescribeStatement", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("FINISHED"))
                .body("SubStatements", hasSize(2))
                .extract().path("SubStatements[1].Id");

        redshift("GetStatementResult", "{\"Id\":\"" + secondId + "\"}")
                .then()
                .statusCode(200)
                .body("Records[0][0].longValue", equalTo(2));
    }

    @Test
    void metadata_listsPgCatalogAfterExecute() {
        redshift("ExecuteStatement",
                "{\"Sql\":\"SELECT 1 AS n\",\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200);

        redshift("ListDatabases",
                "{\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .body("Databases", hasItem("dev"));

        redshift("ListSchemas",
                "{\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP
                        + "\",\"SchemaPattern\":\"pg_catalog\"}")
                .then()
                .statusCode(200)
                .body("Schemas", hasItem("pg_catalog"));

        redshift("ListTables",
                "{\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP
                        + "\",\"SchemaPattern\":\"pg_catalog\",\"TablePattern\":\"pg_class\"}")
                .then()
                .statusCode(200)
                .body("Tables.name", hasItem("pg_class"));

        redshift("DescribeTable",
                "{\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP
                        + "\",\"Schema\":\"pg_catalog\",\"Table\":\"pg_class\"}")
                .then()
                .statusCode(200)
                .body("ColumnList.size()", greaterThan(0));
    }

    @Test
    void listStatements_includesSubmitted() {
        String id = redshift("ExecuteStatement",
                "{\"Sql\":\"SELECT 42 AS answer\",\"Database\":\"dev\",\"WorkgroupName\":\""
                        + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .extract().path("Id");

        redshift("ListStatements", "{\"WorkgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .body("Statements.size()", greaterThan(0))
                .body("Statements.Id", hasItem(id));
    }

    @Test
    void cancel_finishedStatement_returnsValidationException() {
        String id = redshift("ExecuteStatement",
                "{\"Sql\":\"SELECT 1 AS n\",\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .extract().path("Id");

        redshift("CancelStatement", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void cancel_runningCountQuery_returnsStatusTrue() {
        String sql = "SELECT count(*) FROM pg_catalog.pg_attribute a "
                + "CROSS JOIN pg_catalog.pg_attribute b CROSS JOIN pg_catalog.pg_attribute c";
        String id = redshift("ExecuteStatement",
                "{\"Sql\":\"" + sql + "\",\"Database\":\"dev\",\"WorkgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .extract().path("Id");

        redshift("DescribeStatement", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("RUNNING"));

        redshift("CancelStatement", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo(true));

        redshift("DescribeStatement", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("ABORTED"));
    }

    @Test
    void getStatementResultV2_csvContainsLiteral() {
        String id = redshift("ExecuteStatement",
                "{\"Sql\":\"SELECT 7 AS n\",\"Database\":\"dev\",\"WorkgroupName\":\""
                        + WORKGROUP + "\",\"ResultFormat\":\"CSV\"}")
                .then()
                .statusCode(200)
                .extract().path("Id");

        redshift("GetStatementResultV2", "{\"Id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("ResultFormat", equalTo("CSV"))
                .body("Records[0].CSVRecords", containsString("7"));
    }

    private static Response redshift(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "RedshiftData." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}

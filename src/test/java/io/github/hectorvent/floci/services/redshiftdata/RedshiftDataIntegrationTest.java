package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 coverage for Alchemy {@code test/AWS/RedshiftServerless/Bindings.test.ts}:
 * ExecuteStatement / DescribeStatement / GetStatementResult for {@code SELECT 1 AS n}.
 */
@QuarkusTest
class RedshiftDataIntegrationTest {

    private static final String SERVERLESS = "RedshiftServerless";
    private static final String DATA = "RedshiftData";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void executeStatement_unknownWorkgroup_stillRunsSelectLiteral() {
        awsAction(DATA, "ExecuteStatement",
                "{\"Sql\":\"SELECT 1 AS n\",\"WorkgroupName\":\"alchemy-missing-rs-wg\",\"Database\":\"dev\"}")
                .then()
                .statusCode(200)
                .body("WorkgroupName", equalTo("alchemy-missing-rs-wg"));
    }

    @Test
    void selectOne_finishesWithLongValue() {
        String namespace = "alchemy-rs-data-ns";
        String workgroup = "alchemy-rs-data-wg";

        awsAction(SERVERLESS, "CreateNamespace", "{"
                + "\"namespaceName\":\"" + namespace + "\","
                + "\"dbName\":\"dev\","
                + "\"adminUsername\":\"alchemyadmin\","
                + "\"manageAdminPassword\":true"
                + "}")
                .then()
                .statusCode(200);

        awsAction(SERVERLESS, "CreateWorkgroup", "{"
                + "\"workgroupName\":\"" + workgroup + "\","
                + "\"namespaceName\":\"" + namespace + "\","
                + "\"baseCapacity\":8"
                + "}")
                .then()
                .statusCode(200);

        String statementId = awsAction(DATA, "ExecuteStatement", "{"
                + "\"Sql\":\"SELECT 1 AS n\","
                + "\"WorkgroupName\":\"" + workgroup + "\","
                + "\"Database\":\"dev\""
                + "}")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .body("WorkgroupName", equalTo(workgroup))
                .extract().path("Id");

        awsAction(DATA, "DescribeStatement", "{\"Id\":\"" + statementId + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("FINISHED"))
                .body("HasResultSet", equalTo(true));

        awsAction(DATA, "GetStatementResult", "{\"Id\":\"" + statementId + "\"}")
                .then()
                .statusCode(200)
                .body("TotalNumRows", equalTo(1))
                .body("Records[0][0].longValue", equalTo(1));

        awsAction(SERVERLESS, "DeleteWorkgroup", "{\"workgroupName\":\"" + workgroup + "\"}")
                .then()
                .statusCode(200);
        awsAction(SERVERLESS, "DeleteNamespace", "{\"namespaceName\":\"" + namespace + "\"}")
                .then()
                .statusCode(200);
    }
}

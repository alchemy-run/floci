package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class AthenaCatalogAndQueryIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void workGroupUpdateAndTags() {
        String name = "wg-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = "arn:aws:athena:us-east-1:000000000000:workgroup/" + name;

        athena("CreateWorkGroup", """
                {
                  "Name": "%s",
                  "Description": "initial",
                  "Configuration": {
                    "ResultConfiguration": {"OutputLocation":"s3://results/a/"},
                    "EnforceWorkGroupConfiguration": true
                  },
                  "Tags": [{"Key":"Environment","Value":"test"}]
                }
                """.formatted(name))
                .statusCode(200);

        athena("UpdateWorkGroup", """
                {
                  "WorkGroup": "%s",
                  "Description": "updated",
                  "State": "DISABLED",
                  "ConfigurationUpdates": {
                    "ResultConfigurationUpdates": {"OutputLocation":"s3://results/b/"},
                    "EnforceWorkGroupConfiguration": false
                  }
                }
                """.formatted(name))
                .statusCode(200);

        athena("GetWorkGroup", "{\"WorkGroup\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("WorkGroup.Description", equalTo("updated"))
                .body("WorkGroup.State", equalTo("DISABLED"))
                .body("WorkGroup.Configuration.ResultConfiguration.OutputLocation", equalTo("s3://results/b/"));

        athena("ListTagsForResource", "{\"ResourceARN\":\"%s\"}".formatted(arn))
                .statusCode(200)
                .body("Tags.Key", hasItem("Environment"));

        athena("DeleteWorkGroup", "{\"WorkGroup\":\"%s\",\"RecursiveDeleteOption\":true}".formatted(name))
                .statusCode(200);
    }

    @Test
    void dataCatalogNamedQueryAndPreparedStatement() {
        String catalog = "catalog-" + UUID.randomUUID().toString().substring(0, 8);
        String statement = "stmt_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "_");

        athena("CreateDataCatalog", """
                {
                  "Name": "%s",
                  "Type": "LAMBDA",
                  "Description": "federated",
                  "Parameters": {"function":"arn:aws:lambda:us-east-1:000000000000:function/meta"},
                  "Tags": [{"Key":"Owner","Value":"qa"}]
                }
                """.formatted(catalog))
                .statusCode(200);

        athena("GetDataCatalog", "{\"Name\":\"%s\"}".formatted(catalog))
                .statusCode(200)
                .body("DataCatalog.Type", equalTo("LAMBDA"))
                .body("DataCatalog.Parameters.function", equalTo("arn:aws:lambda:us-east-1:000000000000:function/meta"));

        String namedQueryId = athena("CreateNamedQuery", """
                {
                  "Name": "sales",
                  "Database": "analytics",
                  "QueryString": "SELECT 1",
                  "WorkGroup": "primary",
                  "ClientRequestToken": "token-%s"
                }
                """.formatted(catalog))
                .statusCode(200)
                .body("NamedQueryId", notNullValue())
                .extract().path("NamedQueryId");

        athena("GetNamedQuery", "{\"NamedQueryId\":\"%s\"}".formatted(namedQueryId))
                .statusCode(200)
                .body("NamedQuery.QueryString", equalTo("SELECT 1"));

        athena("CreatePreparedStatement", """
                {
                  "StatementName": "%s",
                  "WorkGroup": "primary",
                  "QueryStatement": "SELECT ? AS n",
                  "Description": "one"
                }
                """.formatted(statement))
                .statusCode(200);

        athena("GetPreparedStatement", "{\"WorkGroup\":\"primary\",\"StatementName\":\"%s\"}".formatted(statement))
                .statusCode(200)
                .body("PreparedStatement.QueryStatement", equalTo("SELECT ? AS n"));

        athena("UpdatePreparedStatement", """
                {
                  "StatementName": "%s",
                  "WorkGroup": "primary",
                  "QueryStatement": "SELECT ? AS m"
                }
                """.formatted(statement))
                .statusCode(200);

        athena("GetPreparedStatement", "{\"WorkGroup\":\"primary\",\"StatementName\":\"%s\"}".formatted(statement))
                .statusCode(200)
                .body("PreparedStatement.QueryStatement", equalTo("SELECT ? AS m"));

        athena("DeletePreparedStatement", "{\"WorkGroup\":\"primary\",\"StatementName\":\"%s\"}".formatted(statement))
                .statusCode(200);
        athena("DeleteNamedQuery", "{\"NamedQueryId\":\"%s\"}".formatted(namedQueryId))
                .statusCode(200);
        athena("DeleteDataCatalog", "{\"Name\":\"%s\"}".formatted(catalog))
                .statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse athena(String action, String body) {
        return given()
                .header("X-Amz-Target", "AmazonAthena." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when()
                .post("/")
                .then();
    }
}

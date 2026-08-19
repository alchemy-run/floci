package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
        athena("GetNamedQuery", "{\"NamedQueryId\":\"%s\"}".formatted(namedQueryId))
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("NamedQuery " + namedQueryId + " does not exist"));
        athena("DeleteDataCatalog", "{\"Name\":\"%s\"}".formatted(catalog))
                .statusCode(200);
    }

    @Test
    void selectLiteralReturnsValueRowWithoutDuck() {
        String id = athena("StartQueryExecution", "{\"QueryString\":\"SELECT 1\"}")
                .statusCode(200)
                .extract().path("QueryExecutionId");

        athena("GetQueryExecution", "{\"QueryExecutionId\":\"%s\"}".formatted(id))
                .statusCode(200)
                .body("QueryExecution.Status.State", equalTo("SUCCEEDED"));

        athena("GetQueryResults", "{\"QueryExecutionId\":\"%s\"}".formatted(id))
                .statusCode(200)
                .body("ResultSet.Rows.size()", equalTo(2))
                .body("ResultSet.Rows[1].Data[0].VarCharValue", equalTo("1"));
    }

    @Test
    void getNamedQueryMissingUsesDoesNotExistMessage() {
        String missing = "00000000-0000-0000-0000-000000000000";
        athena("GetNamedQuery", "{\"NamedQueryId\":\"%s\"}".formatted(missing))
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("NamedQuery " + missing + " does not exist"));
    }

    @Test
    void startQueryExecutionPublishesAthenaQueryStateChange() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String queueName = "athena-state-" + suffix;
        String ruleName = "athena-state-rule-" + suffix;

        String queueUrl = given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"" + queueName + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");

        String queueArn = given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "Name": "%s",
                          "EventBusName": "default",
                          "EventPattern": "{\\"source\\":[\\"aws.athena\\"],\\"detail-type\\":[\\"Athena Query State Change\\"],\\"detail\\":{\\"currentState\\":[\\"SUCCEEDED\\",\\"FAILED\\",\\"CANCELLED\\"]}}"
                        }
                        """.formatted(ruleName))
                .when().post("/")
                .then().statusCode(200);

        given()
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "Rule": "%s",
                          "EventBusName": "default",
                          "Targets": [{"Id": "q", "Arn": "%s"}]
                        }
                        """.formatted(ruleName, queueArn))
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        String queryId = athena("StartQueryExecution",
                "{\"QueryString\":\"SELECT 1\",\"WorkGroup\":\"primary\"}")
                .statusCode(200)
                .extract().path("QueryExecutionId");

        io.restassured.response.ValidatableResponse received = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            received = given()
                    .contentType("application/x-amz-json-1.0")
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
                    .when().post("/")
                    .then();
            if (received.extract().path("Messages") != null) {
                break;
            }
        }
        received.statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Body", containsString("\"detail-type\":\"Athena Query State Change\""))
                .body("Messages[0].Body", containsString("\"source\":\"aws.athena\""))
                .body("Messages[0].Body", containsString("\"currentState\":\"SUCCEEDED\""))
                .body("Messages[0].Body", containsString("\"queryExecutionId\":\"" + queryId + "\""))
                .body("Messages[0].Body", containsString("\"workgroupName\":\"primary\""));
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

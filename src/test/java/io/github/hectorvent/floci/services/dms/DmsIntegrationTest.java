package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Amazon DMS stub.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AmazonDMSv20160101.&lt;Action&gt;
 */
@QuarkusTest
class DmsIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dms/aws4_request";
    private static final String TARGET_PREFIX = "AmazonDMSv20160101.";

    private static io.restassured.response.Response dms(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }

    @Test
    void describeEndpoints_emptyAccount_returnsEmptyList() {
        dms("DescribeEndpoints", "{}")
                .then()
                .statusCode(200)
                .body("Endpoints", notNullValue());
    }

    @Test
    void createDescribeModifyDeleteEndpoint_roundTrip() {
        String identifier = "it-dms-source-roundtrip";
        dms("CreateEndpoint", """
                {"EndpointIdentifier":"%s","EndpointType":"source","EngineName":"mysql",
                 "ServerName":"source-db.example.com","Port":3306,"Username":"admin",
                 "Password":"correct-horse-battery-staple","DatabaseName":"app",
                 "Tags":[{"Key":"team","Value":"data"}]}
                """.formatted(identifier))
                .then()
                .statusCode(200)
                .body("Endpoint.EndpointIdentifier", equalTo(identifier))
                .body("Endpoint.EndpointType", equalTo("SOURCE"))
                .body("Endpoint.EngineName", equalTo("mysql"))
                .body("Endpoint.ServerName", equalTo("source-db.example.com"))
                .body("Endpoint.Port", equalTo(3306))
                .body("Endpoint.Status", equalTo("active"))
                .body("Endpoint.EndpointArn", containsString(":endpoint:"))
                .body("Endpoint.Password", nullValue());

        String arn = dms("DescribeEndpoints", """
                {"Filters":[{"Name":"endpoint-id","Values":["%s"]}]}
                """.formatted(identifier))
                .then()
                .statusCode(200)
                .body("Endpoints", hasSize(1))
                .body("Endpoints[0].EndpointIdentifier", equalTo(identifier))
                .extract()
                .path("Endpoints[0].EndpointArn");

        dms("ListTagsForResource", "{\"ResourceArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'team' }.Value", equalTo("data"));

        dms("ModifyEndpoint", """
                {"EndpointArn":"%s","Port":3307,"Username":"migrator"}
                """.formatted(arn))
                .then()
                .statusCode(200)
                .body("Endpoint.Port", equalTo(3307))
                .body("Endpoint.Username", equalTo("migrator"));

        dms("AddTagsToResource", """
                {"ResourceArn":"%s","Tags":[{"Key":"env","Value":"test"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        dms("RemoveTagsFromResource", """
                {"ResourceArn":"%s","TagKeys":["team"]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        dms("ListTagsForResource", "{\"ResourceArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("env"));

        dms("DeleteEndpoint", "{\"EndpointArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(200)
                .body("Endpoint.Status", equalTo("deleting"));

        dms("DescribeEndpoints", """
                {"Filters":[{"Name":"endpoint-id","Values":["%s"]}]}
                """.formatted(identifier))
                .then()
                .statusCode(200)
                .body("Endpoints", empty());
    }

    @Test
    void createEndpoint_duplicateIdentifier_resourceAlreadyExists() {
        String identifier = "it-dms-dup-endpoint";
        String body = """
                {"EndpointIdentifier":"%s","EndpointType":"source","EngineName":"mysql",
                 "ServerName":"db.example.com","Port":3306}
                """.formatted(identifier);
        dms("CreateEndpoint", body).then().statusCode(200);
        dms("CreateEndpoint", body)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsFault"));
    }

    @Test
    void describeSchemas_existingEndpointNeverRefreshed_invalidResourceState() {
        String identifier = "it-dms-schemas-endpoint";
        String arn = dms("CreateEndpoint", """
                {"EndpointIdentifier":"%s","EndpointType":"source","EngineName":"mysql",
                 "ServerName":"db.example.com","Port":3306}
                """.formatted(identifier))
                .then()
                .statusCode(200)
                .extract()
                .path("Endpoint.EndpointArn");

        dms("DescribeSchemas", "{\"EndpointArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));

        dms("DescribeRefreshSchemasStatus", "{\"EndpointArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));
    }

    @Test
    void describeConnectionsAndEvents_empty() {
        dms("DescribeConnections", "{}")
                .then()
                .statusCode(200)
                .body("Connections", empty());
        dms("DescribeEvents", "{\"SourceType\":\"replication-instance\",\"Duration\":60}")
                .then()
                .statusCode(200)
                .body("Events", empty());
    }

    @Test
    void describeEndpointSettings_mysql_returnsSettings() {
        dms("DescribeEndpointSettings", "{\"EngineName\":\"mysql\"}")
                .then()
                .statusCode(200)
                .body("EndpointSettings", not(empty()))
                .body("EndpointSettings.Name", hasItems("ServerName", "Port", "Username"));
    }

    @Test
    void describeOrderableReplicationInstances_returnsClasses() {
        dms("DescribeOrderableReplicationInstances", "{}")
                .then()
                .statusCode(200)
                .body("OrderableReplicationInstances", not(empty()))
                .body("OrderableReplicationInstances.ReplicationInstanceClass",
                        hasItem("dms.t3.micro"));
    }

    @Test
    void describeReplicationTasks_noMatch_emptyList() {
        dms("DescribeReplicationTasks", """
                {"Filters":[{"Name":"replication-task-id","Values":["alchemy-nonexistent-task"]}]}
                """)
                .then()
                .statusCode(200)
                .body("ReplicationTasks", empty());
    }

    @Test
    void taskAndReplicationMutations_missingArn_resourceNotFound() {
        String taskArn = "arn:aws:dms:us-east-1:000000000000:task:AAAAAAAAAAAAAAAAAAAAAAAAAA";
        String configArn = "arn:aws:dms:us-east-1:000000000000:replication-config:AAAAAAAAAAAAAAAAAAAAAAAAAA";

        dms("StartReplicationTask", """
                {"ReplicationTaskArn":"%s","StartReplicationTaskType":"start-replication"}
                """.formatted(taskArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("StopReplicationTask", "{\"ReplicationTaskArn\":\"%s\"}".formatted(taskArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("DescribeTableStatistics", "{\"ReplicationTaskArn\":\"%s\"}".formatted(taskArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("ReloadTables", """
                {"ReplicationTaskArn":"%s","TablesToReload":[{"SchemaName":"public","TableName":"t"}]}
                """.formatted(taskArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("DescribeReplications", """
                {"Filters":[{"Name":"replication-config-arn","Values":["%s"]}]}
                """.formatted(configArn))
                .then()
                .statusCode(200)
                .body("Replications", empty());

        dms("StartReplication", """
                {"ReplicationConfigArn":"%s","StartReplicationType":"start-replication"}
                """.formatted(configArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("StopReplication", "{\"ReplicationConfigArn\":\"%s\"}".formatted(configArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void deleteEndpoint_missing_resourceNotFound() {
        dms("DeleteEndpoint",
                "{\"EndpointArn\":\"arn:aws:dms:us-east-1:000000000000:endpoint:missing\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }
}

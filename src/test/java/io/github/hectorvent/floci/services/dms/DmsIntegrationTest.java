package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DmsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonDMSv20160101.";
    private static final String ACCOUNT_ID = "723679240095";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260101/us-east-1/dms/aws4_request";
    private static final String SUBNET_A = "subnet-default-us-east-1-a";
    private static final String SUBNET_B = "subnet-default-us-east-1-b";
    private static final String SUBNET_C = "subnet-default-us-east-1-c";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void subnetGroupLifecycleIsReadableThroughDescribe() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"Tf-Lifecycle\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationSubnetGroup.ReplicationSubnetGroupIdentifier", equalTo("tf-lifecycle"));

        dms("DescribeReplicationSubnetGroups")
                .body("{\"Filters\":[{\"Name\":\"replication-subnet-group-id\","
                        + "\"Values\":[\"tf-lifecycle\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationSubnetGroups", hasSize(1))
                .body("ReplicationSubnetGroups[0].ReplicationSubnetGroupDescription",
                        equalTo("terraform managed"))
                .body("ReplicationSubnetGroups[0].VpcId", equalTo("vpc-default-us-east-1"))
                .body("ReplicationSubnetGroups[0].SubnetGroupStatus", equalTo("Complete"))
                .body("ReplicationSubnetGroups[0].Subnets.SubnetIdentifier", contains(SUBNET_A, SUBNET_B))
                .body("ReplicationSubnetGroups[0].Subnets.find { it.SubnetIdentifier == '"
                        + SUBNET_A + "' }.SubnetAvailabilityZone.Name", equalTo("us-east-1a"))
                .body("ReplicationSubnetGroups[0].Subnets.find { it.SubnetIdentifier == '"
                        + SUBNET_B + "' }.SubnetAvailabilityZone.Name", equalTo("us-east-1b"))
                .body("ReplicationSubnetGroups[0].Subnets.SubnetStatus", everyItem(equalTo("Active")))
                .body("ReplicationSubnetGroups[0].SupportedNetworkTypes", contains("IPV4"))
                .body("ReplicationSubnetGroups[0].IsReadOnly", equalTo(false));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-lifecycle\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("DescribeReplicationSubnetGroups")
                .body("{\"Filters\":[{\"Name\":\"replication-subnet-group-id\","
                        + "\"Values\":[\"tf-lifecycle\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    /**
     * DMS pages at a minimum MaxRecords of 20, so a second page needs more than 20 groups. Walk the
     * pages on the wire and check Marker resumes where the previous page stopped and is absent from
     * the final response.
     */
    @Test
    void describePagesOnMarkerAndOmitsItFromTheFinalPage() {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String identifier = "tf-page-" + String.format("%02d", i);
            created.add(identifier);
            dms("CreateReplicationSubnetGroup")
                    .body(createBody(identifier))
            .when()
                    .post("/")
            .then()
                    .statusCode(200);
        }
        try {
            List<String> walked = new ArrayList<>();
            String marker = null;
            int pages = 0;
            do {
                String body = marker == null
                        ? "{\"MaxRecords\":20}"
                        : "{\"MaxRecords\":20,\"Marker\":\"" + marker + "\"}";
                Response page = dms("DescribeReplicationSubnetGroups")
                        .body(body)
                .when()
                        .post("/")
                .then()
                        .statusCode(200)
                        .extract().response();
                pages++;
                marker = page.path("Marker");
                List<String> identifiers =
                        page.path("ReplicationSubnetGroups.ReplicationSubnetGroupIdentifier");
                if (marker != null) {
                    assertEquals(20, identifiers.size(), "a non-final page must be full");
                }
                walked.addAll(identifiers);
            } while (marker != null);

            assertNull(marker, "the final page must not carry a Marker");
            assertTrue(pages > 1, "25 groups at MaxRecords=20 must span more than one page");
            assertEquals(walked.size(), walked.stream().distinct().count(), "a group was returned twice");
            assertEquals(created, walked.stream().filter(id -> id.startsWith("tf-page-")).sorted().toList());
        } finally {
            created.forEach(identifier -> dms("DeleteReplicationSubnetGroup")
                    .body("{\"ReplicationSubnetGroupIdentifier\":\"" + identifier + "\"}")
                    .when()
                    .post("/")
                    .then()
                    .statusCode(200));
        }
    }

    @Test
    void describeRejectsMaxRecordsOutsideTheDocumentedRange() {
        for (int outOfRange : new int[] {19, 101}) {
            dms("DescribeReplicationSubnetGroups")
                    .body("{\"MaxRecords\":" + outOfRange + "}")
            .when()
                    .post("/")
            .then()
                    .statusCode(400)
                    .body("__type", equalTo("InvalidParameterValueException"));
        }
    }

    @Test
    void createRejectsADescriptionCarryingAControlCharacter() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-control-char\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform\\u0001managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));

        dms("DescribeReplicationSubnetGroups")
                .body("{\"Filters\":[{\"Name\":\"replication-subnet-group-id\","
                        + "\"Values\":[\"tf-control-char\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void createRejectsADuplicateIdentifier() {
        dms("CreateReplicationSubnetGroup")
                .body(createBody("tf-duplicate"))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("CreateReplicationSubnetGroup")
                .body(createBody("tf-duplicate"))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsFault"));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-duplicate\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void createRejectsSubnetsInASingleAvailabilityZone() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-one-az\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ReplicationSubnetGroupDoesNotCoverEnoughAZs"));
    }

    @Test
    void createRejectsAnUnknownSubnet() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-bad-subnet\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"subnet-does-not-exist\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidSubnet"));
    }

    @Test
    void createRejectsAMissingDescription() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-no-description\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void deleteOfAMissingSubnetGroupFaults() {
        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-absent\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void aTagValueOfTheWrongTypeIsASerializationErrorOnTheWire() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-object-tag\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"],"
                        + "\"Tags\":[{\"Key\":\"env\",\"Value\":{\"nested\":1}}]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));

        dms("DescribeReplicationSubnetGroups")
                .body("{\"Filters\":[{\"Name\":\"replication-subnet-group-id\","
                        + "\"Values\":[\"tf-object-tag\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void unsupportedDmsActionReportsUnknownOperation() {
        dms("CreateReplicationTask")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void modifySubnetGroupSwapsSubnetsAndDescription() {
        dms("CreateReplicationSubnetGroup")
                .body(createBody("tf-modify"))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("ModifyReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-modify\","
                        + "\"ReplicationSubnetGroupDescription\":\"updated\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_C + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationSubnetGroup.ReplicationSubnetGroupDescription", equalTo("updated"))
                .body("ReplicationSubnetGroup.Subnets.SubnetIdentifier", contains(SUBNET_A, SUBNET_C));

        dms("ModifyReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-modify-absent\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-modify\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void endpointLifecycleNeverReturnsSecrets() {
        String endpointArn = dms("CreateEndpoint")
                .body("{\"EndpointIdentifier\":\"it-endpoint\",\"EndpointType\":\"source\","
                        + "\"EngineName\":\"mysql\",\"ServerName\":\"source-db.example.com\",\"Port\":3306,"
                        + "\"Username\":\"admin\",\"Password\":\"hunter2\",\"DatabaseName\":\"app\","
                        + "\"MySQLSettings\":{\"Password\":\"hunter2\",\"EventsPollInterval\":5},"
                        + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Endpoint.EndpointType", equalTo("SOURCE"))
                .body("Endpoint.EngineName", equalTo("mysql"))
                .body("Endpoint.Status", equalTo("active"))
                .body("Endpoint.Password", nullValue())
                .body("Endpoint.MySQLSettings.Password", nullValue())
                .body("Endpoint.MySQLSettings.EventsPollInterval", equalTo(5))
                .body("Endpoint.EndpointArn", containsString(":endpoint:"))
                .extract().path("Endpoint.EndpointArn");

        dms("DescribeEndpoints")
                .body("{\"Filters\":[{\"Name\":\"endpoint-id\",\"Values\":[\"it-endpoint\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Endpoints", hasSize(1))
                .body("Endpoints[0].EndpointArn", equalTo(endpointArn))
                .body("Endpoints[0].ServerName", equalTo("source-db.example.com"))
                .body("Endpoints[0].Port", equalTo(3306));

        dms("ModifyEndpoint")
                .body("{\"EndpointArn\":\"" + endpointArn + "\",\"EndpointType\":\"source\","
                        + "\"EngineName\":\"mysql\",\"Port\":3307,\"Username\":\"readonly\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Endpoint.EndpointArn", equalTo(endpointArn))
                .body("Endpoint.Port", equalTo(3307))
                .body("Endpoint.Username", equalTo("readonly"));

        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + endpointArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("TagList.Key", contains("team"));

        dms("DescribeSchemas")
                .body("{\"EndpointArn\":\"" + endpointArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));

        dms("DescribeRefreshSchemasStatus")
                .body("{\"EndpointArn\":\"" + endpointArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("DescribeConnections")
                .body("{\"Filters\":[{\"Name\":\"endpoint-arn\",\"Values\":[\"" + endpointArn + "\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        dms("DeleteEndpoint")
                .body("{\"EndpointArn\":\"" + endpointArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Endpoint.Status", equalTo("deleting"));

        dms("DescribeEndpoints")
                .body("{\"Filters\":[{\"Name\":\"endpoint-id\",\"Values\":[\"it-endpoint\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void deletingANonexistentReplicationInstanceFaults() {
        dms("DeleteReplicationInstance")
                .body("{\"ReplicationInstanceArn\":\"arn:aws:dms:us-east-1:" + ACCOUNT_ID
                        + ":rep:AAAAAAAAAAAAAAAAAAAAAAAAAA\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void replicationInstanceLifecycleSettlesAndBlocksItsSubnetGroup() {
        dms("CreateReplicationSubnetGroup")
                .body(createBody("it-instance-group"))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        String instanceArn = dms("CreateReplicationInstance")
                .body("{\"ReplicationInstanceIdentifier\":\"it-instance\","
                        + "\"ReplicationInstanceClass\":\"dms.t3.micro\",\"AllocatedStorage\":20,"
                        + "\"ReplicationSubnetGroupIdentifier\":\"it-instance-group\","
                        + "\"PubliclyAccessible\":false,\"MultiAZ\":false}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationInstance.ReplicationInstanceStatus", equalTo("creating"))
                .body("ReplicationInstance.ReplicationInstanceArn", containsString(":rep:"))
                .body("ReplicationInstance.ReplicationSubnetGroup.VpcId", equalTo("vpc-default-us-east-1"))
                .body("ReplicationInstance.VpcSecurityGroups.VpcSecurityGroupId", contains("sg-default-us-east-1"))
                .extract().path("ReplicationInstance.ReplicationInstanceArn");

        dms("DescribeReplicationInstances")
                .body("{\"Filters\":[{\"Name\":\"replication-instance-id\",\"Values\":[\"it-instance\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationInstances", hasSize(1))
                .body("ReplicationInstances[0].ReplicationInstanceStatus", equalTo("available"))
                .body("ReplicationInstances[0].ReplicationInstanceClass", equalTo("dms.t3.micro"))
                .body("ReplicationInstances[0].AllocatedStorage", equalTo(20));

        dms("DescribeReplicationInstanceTaskLogs")
                .body("{\"ReplicationInstanceArn\":\"" + instanceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationInstanceArn", equalTo(instanceArn))
                .body("ReplicationInstanceTaskLogs", hasSize(0));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"it-instance-group\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));

        dms("DeleteReplicationInstance")
                .body("{\"ReplicationInstanceArn\":\"" + instanceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationInstance.ReplicationInstanceStatus", equalTo("deleting"));

        dms("DescribeEvents")
                .body("{\"SourceType\":\"replication-instance\",\"SourceIdentifier\":\"it-instance\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Events.EventCategories.flatten()", hasItems("creation", "deletion"));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"it-instance-group\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void catalogueDescribesAreNonEmpty() {
        dms("DescribeOrderableReplicationInstances")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("OrderableReplicationInstances.ReplicationInstanceClass", hasItem("dms.t3.micro"));

        dms("DescribeEndpointSettings")
                .body("{\"EngineName\":\"mysql\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("EndpointSettings.Name", hasItem("EventsPollInterval"));

        dms("DescribeEvents")
                .body("{\"SourceType\":\"replication-instance\",\"Duration\":60}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void taskAndReplicationOperationsFaultForResourcesThatDoNotExist() {
        String taskArn = "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":task:AAAAAAAAAAAAAAAAAAAAAAAAAA";
        String configArn = "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":replication-config:AAAAAAAAAAAAAAAAAAAAAAAAAA";
        List<String[]> calls = List.of(
                new String[] {"DescribeReplicationTasks",
                        "{\"Filters\":[{\"Name\":\"replication-task-id\",\"Values\":[\"alchemy-nonexistent-task\"]}]}"},
                new String[] {"StartReplicationTask",
                        "{\"ReplicationTaskArn\":\"" + taskArn + "\",\"StartReplicationTaskType\":\"start-replication\"}"},
                new String[] {"StopReplicationTask", "{\"ReplicationTaskArn\":\"" + taskArn + "\"}"},
                new String[] {"DescribeTableStatistics", "{\"ReplicationTaskArn\":\"" + taskArn + "\"}"},
                new String[] {"ReloadTables", "{\"ReplicationTaskArn\":\"" + taskArn + "\","
                        + "\"TablesToReload\":[{\"SchemaName\":\"public\",\"TableName\":\"nonexistent\"}]}"},
                new String[] {"DescribeReplications",
                        "{\"Filters\":[{\"Name\":\"replication-config-arn\",\"Values\":[\"" + configArn + "\"]}]}"},
                new String[] {"StartReplication",
                        "{\"ReplicationConfigArn\":\"" + configArn + "\",\"StartReplicationType\":\"start-replication\"}"},
                new String[] {"StopReplication", "{\"ReplicationConfigArn\":\"" + configArn + "\"}"});
        for (String[] call : calls) {
            dms(call[0])
                    .body(call[1])
            .when()
                    .post("/")
            .then()
                    .statusCode(400)
                    .body("__type", equalTo("ResourceNotFoundFault"));
        }
    }

    @Test
    void tagsSurviveCreateAndAreReadableThroughListTagsForResource() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-tagged\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"],"
                        + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("env"))
                .body("TagList[0].Value", equalTo("test"))
                .body("TagList[0].ResourceArn", nullValue());

        dms("AddTagsToResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\","
                        + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"data\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("TagList.Key", containsInAnyOrder("env", "owner"));

        dms("RemoveTagsFromResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\",\"TagKeys\":[\"env\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("ListTagsForResource")
                .body("{\"ResourceArnList\":[\"" + arn("tf-tagged") + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("owner"))
                .body("TagList[0].ResourceArn", equalTo(arn("tf-tagged")));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-tagged\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void listTagsForAnUnknownArnFaults() {
        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn("tf-never-created") + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    private static String arn(String identifier) {
        return "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":subgrp:" + identifier;
    }

    private static String createBody(String identifier) {
        return "{\"ReplicationSubnetGroupIdentifier\":\"" + identifier + "\","
                + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}";
    }

    private static RequestSpecification dms(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER);
    }
}

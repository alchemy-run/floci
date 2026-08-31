package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for DMS replication instance CRUD.
 * Protocol: JSON 1.1 — {@code X-Amz-Target: AmazonDMSv20160101.&lt;Action&gt;}
 */
@QuarkusTest
class DmsReplicationInstanceIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dms/aws4_request";
    private static final String TARGET = "AmazonDMSv20160101.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void deleteReplicationInstance_missingArn_resourceNotFound() {
        dms("DeleteReplicationInstance",
                "{\"ReplicationInstanceArn\":\"arn:aws:dms:us-east-1:000000000000:rep:AAAAAAAAAAAAAAAAAAAAAAAAAA\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void describeReplicationInstances_unknownIdentifier_emptyPage() {
        dms("DescribeReplicationInstances",
                "{\"Filters\":[{\"Name\":\"replication-instance-id\",\"Values\":[\"missing-instance\"]}]}")
                .then()
                .statusCode(200)
                .body("ReplicationInstances", hasSize(0));
    }

    @Test
    void createDescribeModifyTagAndDelete() {
        String identifier = "it-dms-ri-roundtrip";
        Response created = dms("CreateReplicationInstance", """
                {
                  "ReplicationInstanceIdentifier": "%s",
                  "ReplicationInstanceClass": "dms.t3.micro",
                  "AllocatedStorage": 20,
                  "PubliclyAccessible": false,
                  "MultiAZ": false,
                  "Tags": [{"Key": "team", "Value": "data"}]
                }
                """.formatted(identifier));
        created.then()
                .statusCode(200)
                .body("ReplicationInstance.ReplicationInstanceIdentifier", equalTo(identifier))
                .body("ReplicationInstance.ReplicationInstanceClass", equalTo("dms.t3.micro"))
                .body("ReplicationInstance.AllocatedStorage", equalTo(20))
                .body("ReplicationInstance.ReplicationInstanceStatus", equalTo("available"))
                .body("ReplicationInstance.PubliclyAccessible", equalTo(false))
                .body("ReplicationInstance.MultiAZ", equalTo(false))
                .body("ReplicationInstance.ReplicationInstanceArn", containsString(":rep:"))
                .body("ReplicationInstance.ReplicationInstancePrivateIpAddresses", not(empty()));
        String arn = created.jsonPath().getString("ReplicationInstance.ReplicationInstanceArn");
        assertTrue(arn.contains(":rep:"));

        dms("DescribeReplicationInstances",
                "{\"Filters\":[{\"Name\":\"replication-instance-id\",\"Values\":[\"" + identifier + "\"]}]}")
                .then()
                .statusCode(200)
                .body("ReplicationInstances", hasSize(1))
                .body("ReplicationInstances[0].ReplicationInstanceArn", equalTo(arn))
                .body("ReplicationInstances[0].ReplicationInstanceStatus", equalTo("available"));

        dms("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("TagList[0].Key", equalTo("team"))
                .body("TagList[0].Value", equalTo("data"));

        dms("ModifyReplicationInstance", """
                {"ReplicationInstanceArn":"%s","AllocatedStorage":30,"ApplyImmediately":true}
                """.formatted(arn))
                .then()
                .statusCode(200)
                .body("ReplicationInstance.AllocatedStorage", equalTo(30))
                .body("ReplicationInstance.ReplicationInstanceStatus", equalTo("available"));

        dms("AddTagsToResource", """
                {"ResourceArn":"%s","Tags":[{"Key":"env","Value":"test"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        dms("RemoveTagsFromResource",
                "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"team\"]}")
                .then()
                .statusCode(200);

        dms("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("env"));

        dms("CreateReplicationInstance", """
                {
                  "ReplicationInstanceIdentifier": "%s",
                  "ReplicationInstanceClass": "dms.t3.micro"
                }
                """.formatted(identifier))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsFault"));

        dms("DeleteReplicationInstance", "{\"ReplicationInstanceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("ReplicationInstance.ReplicationInstanceStatus", equalTo("deleting"));

        dms("DescribeReplicationInstances",
                "{\"Filters\":[{\"Name\":\"replication-instance-id\",\"Values\":[\"" + identifier + "\"]}]}")
                .then()
                .statusCode(200)
                .body("ReplicationInstances", hasSize(0));
    }

    private static Response dms(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}

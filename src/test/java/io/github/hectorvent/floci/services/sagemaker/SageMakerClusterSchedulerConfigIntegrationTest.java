package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * SageMaker ClusterSchedulerConfig JSON 1.1 coverage used by Alchemy:
 * typed ResourceNotFound, create/describe/list/update/delete, one-policy-per-cluster
 * ConflictException, and tags.
 */
@QuarkusTest
class SageMakerClusterSchedulerConfigIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String TARGET = "SageMaker.";
    private static final String UNKNOWN = "abcdef012345";
    private static final String CLUSTER_ARN =
            "arn:aws:sagemaker:us-east-1:000000000000:cluster/floci-hyperpod-scheduler";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeClusterSchedulerConfig_unknownId_resourceNotFound() {
        invoke("DescribeClusterSchedulerConfig",
                "{\"ClusterSchedulerConfigId\":\"" + UNKNOWN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    void createDescribeUpdateTagAndDeleteClusterSchedulerConfig() {
        String name = "floci-cluster-policy";
        Response created = invoke("CreateClusterSchedulerConfig", """
                {
                  "Name": "%s",
                  "ClusterArn": "%s",
                  "SchedulerConfig": {
                    "PriorityClasses": [{"Name": "training", "Weight": 75}],
                    "FairShare": "Enabled"
                  },
                  "Description": "alchemy test policy",
                  "Tags": [{"Key": "purpose", "Value": "alchemy-test"}]
                }
                """.formatted(name, CLUSTER_ARN));
        created.then()
                .statusCode(200)
                .body("ClusterSchedulerConfigArn", startsWith("arn:aws:sagemaker:"))
                .body("ClusterSchedulerConfigArn", org.hamcrest.Matchers.containsString(
                        ":cluster-scheduler-config/"));
        String id = created.jsonPath().getString("ClusterSchedulerConfigId");
        String arn = created.jsonPath().getString("ClusterSchedulerConfigArn");

        invoke("DescribeClusterSchedulerConfig",
                "{\"ClusterSchedulerConfigId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("ClusterSchedulerConfigId", equalTo(id))
                .body("ClusterSchedulerConfigArn", equalTo(arn))
                .body("Name", equalTo(name))
                .body("ClusterArn", equalTo(CLUSTER_ARN))
                .body("Status", equalTo("Created"))
                .body("ClusterSchedulerConfigVersion", equalTo(1))
                .body("Description", equalTo("alchemy test policy"))
                .body("SchedulerConfig.FairShare", equalTo("Enabled"))
                .body("SchedulerConfig.PriorityClasses[0].Name", equalTo("training"))
                .body("SchedulerConfig.PriorityClasses[0].Weight", equalTo(75));

        invoke("ListClusterSchedulerConfigs", "{\"ClusterArn\":\"" + CLUSTER_ARN + "\"}")
                .then()
                .statusCode(200)
                .body("ClusterSchedulerConfigSummaries.Name", hasItem(name))
                .body("ClusterSchedulerConfigSummaries.find { it.Name == '" + name + "' }.Status",
                        equalTo("Created"));

        invoke("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'purpose' }.Value", equalTo("alchemy-test"));

        invoke("AddTags", """
                {"ResourceArn":"%s","Tags":[{"Key":"env","Value":"local"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("CreateClusterSchedulerConfig", """
                {
                  "Name": "floci-cluster-policy-conflict",
                  "ClusterArn": "%s",
                  "SchedulerConfig": {"FairShare": "Enabled"}
                }
                """.formatted(CLUSTER_ARN))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));

        invoke("UpdateClusterSchedulerConfig", """
                {
                  "ClusterSchedulerConfigId": "%s",
                  "TargetVersion": 1,
                  "SchedulerConfig": {
                    "PriorityClasses": [
                      {"Name": "inference", "Weight": 100},
                      {"Name": "training", "Weight": 75}
                    ],
                    "FairShare": "Enabled"
                  },
                  "Description": "alchemy test policy v2"
                }
                """.formatted(id))
                .then()
                .statusCode(200)
                .body("ClusterSchedulerConfigArn", equalTo(arn))
                .body("ClusterSchedulerConfigVersion", greaterThan(1));

        invoke("DescribeClusterSchedulerConfig",
                "{\"ClusterSchedulerConfigId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("Updated"))
                .body("ClusterSchedulerConfigVersion", equalTo(2))
                .body("Description", equalTo("alchemy test policy v2"))
                .body("SchedulerConfig.PriorityClasses[0].Name", equalTo("inference"))
                .body("SchedulerConfig.PriorityClasses[0].Weight", equalTo(100));

        invoke("DeleteClusterSchedulerConfig",
                "{\"ClusterSchedulerConfigId\":\"" + id + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribeClusterSchedulerConfig",
                "{\"ClusterSchedulerConfigId\":\"" + id + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));

        invoke("DeleteClusterSchedulerConfig",
                "{\"ClusterSchedulerConfigId\":\"" + id + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}

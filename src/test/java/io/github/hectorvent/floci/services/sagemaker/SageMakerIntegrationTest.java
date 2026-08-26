package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class SageMakerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String TARGET = "SageMaker.";
    private static final String UNKNOWN = "alchemy-nonexistent-hyperpod-cluster-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeCluster_unknownName_resourceNotFound() {
        invoke("DescribeCluster", "{\"ClusterName\":\"" + UNKNOWN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    void createDescribeTagUpdateAndDeleteCluster() {
        String name = "floci-hyperpod-cluster";
        Response created = invoke("CreateCluster", """
                {
                  "ClusterName": "%s",
                  "NodeRecovery": "None",
                  "InstanceGroups": [{
                    "InstanceGroupName": "controller",
                    "InstanceType": "ml.t3.medium",
                    "InstanceCount": 1,
                    "ExecutionRole": "arn:aws:iam::000000000000:role/HyperPod",
                    "LifeCycleConfig": {
                      "SourceS3Uri": "s3://sagemaker-lifecycle/lifecycle",
                      "OnCreate": "on_create.sh"
                    }
                  }],
                  "Tags": [{"Key": "purpose", "Value": "alchemy-test"}]
                }
                """.formatted(name));
        created.then()
                .statusCode(200)
                .body("ClusterArn", startsWith("arn:aws:sagemaker:"));
        String arn = created.jsonPath().getString("ClusterArn");

        invoke("DescribeCluster", "{\"ClusterName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ClusterName", equalTo(name))
                .body("ClusterArn", equalTo(arn))
                .body("ClusterStatus", equalTo("InService"))
                .body("NodeRecovery", equalTo("None"))
                .body("InstanceGroups", hasSize(1))
                .body("InstanceGroups[0].InstanceGroupName", equalTo("controller"))
                .body("InstanceGroups[0].InstanceType", equalTo("ml.t3.medium"))
                .body("InstanceGroups[0].CurrentCount", equalTo(1))
                .body("InstanceGroups[0].TargetCount", equalTo(1))
                .body("InstanceGroups[0].Status", equalTo("InService"));

        invoke("ListClusters", "{}")
                .then()
                .statusCode(200)
                .body("ClusterSummaries.find { it.ClusterName == '" + name + "' }.ClusterStatus",
                        equalTo("InService"));

        invoke("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'purpose' }.Value", equalTo("alchemy-test"));

        invoke("AddTags", """
                {"ResourceArn":"%s","Tags":[{"Key":"env","Value":"local"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("UpdateCluster", """
                {
                  "ClusterName": "%s",
                  "NodeRecovery": "Automatic",
                  "InstanceGroups": [{
                    "InstanceGroupName": "controller",
                    "InstanceType": "ml.t3.medium",
                    "InstanceCount": 2
                  }]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("ClusterArn", equalTo(arn));

        invoke("DescribeCluster", "{\"ClusterName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("NodeRecovery", equalTo("Automatic"))
                .body("InstanceGroups[0].TargetCount", equalTo(2))
                .body("ClusterStatus", equalTo("InService"));

        invoke("ListClusterNodes", "{\"ClusterName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ClusterNodeSummaries", hasSize(0));

        invoke("DeleteCluster", "{\"ClusterName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ClusterArn", equalTo(arn));

        invoke("DescribeCluster", "{\"ClusterName\":\"" + name + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));

        invoke("DeleteCluster", "{\"ClusterName\":\"" + name + "\"}")
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

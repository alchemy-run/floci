package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;

/**
 * Compute quota CRUD for SageMaker HyperPod task governance.
 * Protocol: JSON 1.1 — X-Amz-Target: SageMaker.&lt;Action&gt;
 */
@QuarkusTest
class SageMakerComputeQuotaIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String TARGET = "SageMaker.";
    private static final String UNKNOWN = "abcdef012345";
    private static final String CLUSTER_ARN =
            "arn:aws:sagemaker:us-east-1:000000000000:cluster/floci-hyperpod";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeComputeQuota_unknownId_resourceNotFound() {
        invoke("DescribeComputeQuota", "{\"ComputeQuotaId\":\"" + UNKNOWN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    void createDescribeTagUpdateAndDeleteComputeQuota() {
        String name = "floci-compute-quota";
        Response created = invoke("CreateComputeQuota", """
                {
                  "Name": "%s",
                  "ClusterArn": "%s",
                  "ComputeQuotaTarget": {"TeamName": "alchemy-research", "FairShareWeight": 10},
                  "ComputeQuotaConfig": {
                    "ComputeQuotaResources": [{"InstanceType": "ml.t3.medium", "Count": 1}]
                  },
                  "ActivationState": "Enabled",
                  "Tags": [{"Key": "purpose", "Value": "alchemy-test"}]
                }
                """.formatted(name, CLUSTER_ARN));
        created.then()
                .statusCode(200)
                .body("ComputeQuotaArn", startsWith("arn:aws:sagemaker:"))
                .body("ComputeQuotaId", startsWith(""));
        String id = created.jsonPath().getString("ComputeQuotaId");
        String arn = created.jsonPath().getString("ComputeQuotaArn");

        invoke("DescribeComputeQuota", "{\"ComputeQuotaId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("ComputeQuotaId", equalTo(id))
                .body("ComputeQuotaArn", equalTo(arn))
                .body("Name", equalTo(name))
                .body("Status", equalTo("Created"))
                .body("ComputeQuotaVersion", equalTo(1))
                .body("ClusterArn", equalTo(CLUSTER_ARN))
                .body("ComputeQuotaTarget.TeamName", equalTo("alchemy-research"))
                .body("ComputeQuotaTarget.FairShareWeight", equalTo(10))
                .body("ComputeQuotaConfig.ComputeQuotaResources[0].InstanceType", equalTo("ml.t3.medium"))
                .body("ComputeQuotaConfig.ComputeQuotaResources[0].Count", equalTo(1))
                .body("ActivationState", equalTo("Enabled"));

        invoke("ListComputeQuotas", "{\"NameContains\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ComputeQuotaSummaries.find { it.Name == '" + name + "' }.Status",
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

        invoke("UpdateComputeQuota", """
                {
                  "ComputeQuotaId": "%s",
                  "TargetVersion": 1,
                  "ComputeQuotaTarget": {"TeamName": "alchemy-research", "FairShareWeight": 20},
                  "ComputeQuotaConfig": {
                    "ComputeQuotaResources": [{"InstanceType": "ml.t3.medium", "Count": 2}]
                  },
                  "ActivationState": "Enabled"
                }
                """.formatted(id))
                .then()
                .statusCode(200)
                .body("ComputeQuotaArn", equalTo(arn))
                .body("ComputeQuotaVersion", greaterThan(1));

        invoke("DescribeComputeQuota", "{\"ComputeQuotaId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("Updated"))
                .body("ComputeQuotaVersion", equalTo(2))
                .body("ComputeQuotaTarget.FairShareWeight", equalTo(20))
                .body("ComputeQuotaConfig.ComputeQuotaResources[0].Count", equalTo(2));

        invoke("DeleteComputeQuota", "{\"ComputeQuotaId\":\"" + id + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribeComputeQuota", "{\"ComputeQuotaId\":\"" + id + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));

        invoke("DeleteComputeQuota", "{\"ComputeQuotaId\":\"" + id + "\"}")
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

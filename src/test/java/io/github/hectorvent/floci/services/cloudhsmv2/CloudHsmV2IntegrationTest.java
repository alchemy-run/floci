package io.github.hectorvent.floci.services.cloudhsmv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudHsmV2IntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cloudhsm/aws4_request";
    private static final String TARGET = "BaldrApiService.";
    private static final String UNKNOWN_CLUSTER = "cluster-aaaaaaaaaaa";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeClusters_unknownClusterId_emptyPage() {
        invoke("DescribeClusters", "{\"Filters\":{\"clusterIds\":[\"" + UNKNOWN_CLUSTER + "\"]}}")
                .then()
                .statusCode(200)
                .body("Clusters", hasSize(0));
    }

    @Test
    void deleteCluster_missingCluster_resourceNotFound() {
        invoke("DeleteCluster", "{\"ClusterId\":\"" + UNKNOWN_CLUSTER + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("CloudHsmResourceNotFoundException"));
    }

    @Test
    void createDescribeTagHsmAndDelete() {
        Response created = invoke("CreateCluster", """
                {
                  "HsmType": "hsm2m.medium",
                  "SubnetIds": ["subnet-aaaa1111", "subnet-bbbb2222"],
                  "TagList": [{"Key": "fixture", "Value": "cloudhsm-cluster"}]
                }
                """);
        created.then().statusCode(200).body("Cluster.ClusterId", startsWith("cluster-"));
        String clusterId = created.jsonPath().getString("Cluster.ClusterId");
        Map<String, String> subnetMapping = created.jsonPath().getMap("Cluster.SubnetMapping");
        String az = subnetMapping.keySet().iterator().next();

        invoke("DescribeClusters", "{\"Filters\":{\"clusterIds\":[\"" + clusterId + "\"]}}")
                .then()
                .statusCode(200)
                .body("Clusters", hasSize(1))
                .body("Clusters[0].ClusterId", equalTo(clusterId))
                .body("Clusters[0].HsmType", equalTo("hsm2m.medium"))
                .body("Clusters[0].State", equalTo("UNINITIALIZED"));

        invoke("ListTags", "{\"ResourceId\":\"" + clusterId + "\"}")
                .then()
                .statusCode(200);

        invoke("CreateHsm", "{\"ClusterId\":\"" + clusterId + "\",\"AvailabilityZone\":\"" + az + "\"}")
                .then()
                .statusCode(200)
                .body("Hsm.HsmId", startsWith("hsm-"))
                .body("Hsm.State", equalTo("ACTIVE"));

        String hsmId = invoke("DescribeClusters", "{\"Filters\":{\"clusterIds\":[\"" + clusterId + "\"]}}")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("Clusters[0].Hsms[0].HsmId");
        assertTrue(hsmId.startsWith("hsm-"));

        invoke("DeleteCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("CloudHsmInvalidRequestException"));

        invoke("DeleteHsm", "{\"ClusterId\":\"" + clusterId + "\",\"HsmId\":\"" + hsmId + "\"}")
                .then()
                .statusCode(200)
                .body("HsmId", equalTo(hsmId));

        invoke("DeleteCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then()
                .statusCode(200)
                .body("Cluster.State", equalTo("DELETED"));

        invoke("DescribeClusters", "{\"Filters\":{\"clusterIds\":[\"" + clusterId + "\"]}}")
                .then()
                .statusCode(200)
                .body("Clusters", hasSize(0));

        invoke("DeleteCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("CloudHsmResourceNotFoundException"));
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

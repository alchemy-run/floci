package io.github.hectorvent.floci.services.dax;

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
class DaxClusterIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dax/aws4_request";
    private static final String TARGET = "AmazonDAXV3.";
    private static final String UNKNOWN = "alchemy-nonexistent-dax-cluster-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeClusters_unknownName_clusterNotFoundFault() {
        invoke("DescribeClusters", "{\"ClusterNames\":[\"" + UNKNOWN + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ClusterNotFoundFault"));
    }

    @Test
    void createDescribeTagUpdateAndDeleteCluster() {
        invoke("CreateSubnetGroup", """
                {
                  "SubnetGroupName": "it-dax-subnets",
                  "Description": "dax cluster subnets",
                  "SubnetIds": ["subnet-aaaa1111", "subnet-bbbb2222"]
                }
                """)
                .then()
                .statusCode(200)
                .body("SubnetGroup.SubnetGroupName", equalTo("it-dax-subnets"));

        Response created = invoke("CreateCluster", """
                {
                  "ClusterName": "itdaxcluster",
                  "NodeType": "dax.t3.small",
                  "ReplicationFactor": 1,
                  "IamRoleArn": "arn:aws:iam::000000000000:role/DaxRole",
                  "SubnetGroupName": "it-dax-subnets",
                  "SecurityGroupIds": ["sg-00000001"],
                  "Description": "alchemy dax test cluster",
                  "Tags": [{"Key": "fixture", "Value": "dax-cluster"}]
                }
                """);
        created.then()
                .statusCode(200)
                .body("Cluster.ClusterName", equalTo("itdaxcluster"))
                .body("Cluster.Status", equalTo("available"))
                .body("Cluster.NodeType", equalTo("dax.t3.small"))
                .body("Cluster.TotalNodes", equalTo(1))
                .body("Cluster.ClusterArn", startsWith("arn:aws:dax:"))
                .body("Cluster.ClusterDiscoveryEndpoint.Address", equalTo("itdaxcluster.us-east-1.dax.localhost"));

        String arn = created.jsonPath().getString("Cluster.ClusterArn");

        invoke("DescribeClusters", "{\"ClusterNames\":[\"itdaxcluster\"]}")
                .then()
                .statusCode(200)
                .body("Clusters", hasSize(1))
                .body("Clusters[0].Status", equalTo("available"))
                .body("Clusters[0].TotalNodes", equalTo(1));

        invoke("ListTags", "{\"ResourceName\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("fixture"))
                .body("Tags[0].Value", equalTo("dax-cluster"));

        invoke("UpdateCluster", "{\"ClusterName\":\"itdaxcluster\",\"Description\":\"updated\"}")
                .then()
                .statusCode(200)
                .body("Cluster.Description", equalTo("updated"));

        invoke("DeleteCluster", "{\"ClusterName\":\"itdaxcluster\"}")
                .then()
                .statusCode(200)
                .body("Cluster.ClusterName", equalTo("itdaxcluster"));

        invoke("DescribeClusters", "{\"ClusterNames\":[\"itdaxcluster\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ClusterNotFoundFault"));

        invoke("DeleteSubnetGroup", "{\"SubnetGroupName\":\"it-dax-subnets\"}")
                .then()
                .statusCode(200);
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .accept(CONTENT_TYPE)
                .header("Authorization", AUTH)
                .header("X-Amz-Target", TARGET + action)
                .body(body)
                .when()
                .post("/");
    }
}

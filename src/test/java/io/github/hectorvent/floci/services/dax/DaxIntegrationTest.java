package io.github.hectorvent.floci.services.dax;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 DAX control-plane coverage used by Alchemy bindings:
 * DescribeClusters (typed ClusterNotFoundFault) and DescribeEvents (empty-ok list).
 */
@QuarkusTest
class DaxIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dax/aws4_request";
    private static final String CLUSTER = "daxitcluster";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeClusters_missingName_returnsClusterNotFoundFault() {
        dax("DescribeClusters", "{\"ClusterNames\":[\"alchemy-nonexistent-dax-probe\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ClusterNotFoundFault"));
    }

    @Test
    void describeEvents_returnsEventList() {
        dax("DescribeEvents", "{}")
                .then()
                .statusCode(200)
                .body("Events.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void clusterLifecycle_andScaleValidation() {
        dax("CreateSubnetGroup", "{"
                + "\"SubnetGroupName\":\"dax-it-subnets\","
                + "\"Description\":\"it\","
                + "\"SubnetIds\":[\"subnet-aaaa1111\",\"subnet-bbbb2222\"]}")
                .then()
                .statusCode(200)
                .body("SubnetGroup.SubnetGroupName", equalTo("dax-it-subnets"));

        dax("CreateCluster", "{"
                + "\"ClusterName\":\"" + CLUSTER + "\","
                + "\"NodeType\":\"dax.t3.small\","
                + "\"ReplicationFactor\":1,"
                + "\"IamRoleArn\":\"arn:aws:iam::000000000000:role/DaxRole\","
                + "\"SubnetGroupName\":\"dax-it-subnets\"}")
                .then()
                .statusCode(200)
                .body("Cluster.ClusterName", equalTo(CLUSTER))
                .body("Cluster.Status", equalTo("available"))
                .body("Cluster.TotalNodes", equalTo(1))
                .body("Cluster.ClusterDiscoveryEndpoint.Port", equalTo(8111))
                .body("Cluster.ClusterDiscoveryEndpoint.URL", notNullValue())
                .body("Cluster.Nodes[0].NodeId", notNullValue());

        dax("DescribeClusters", "{\"ClusterNames\":[\"" + CLUSTER + "\"]}")
                .then()
                .statusCode(200)
                .body("Clusters[0].ClusterName", equalTo(CLUSTER));

        dax("IncreaseReplicationFactor",
                "{\"ClusterName\":\"" + CLUSTER + "\",\"NewReplicationFactor\":1}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));

        dax("DecreaseReplicationFactor",
                "{\"ClusterName\":\"" + CLUSTER + "\",\"NewReplicationFactor\":0}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));

        dax("DescribeEvents", "{\"SourceName\":\"" + CLUSTER + "\"}")
                .then()
                .statusCode(200)
                .body("Events.SourceName", hasItem(CLUSTER));

        dax("DeleteCluster", "{\"ClusterName\":\"" + CLUSTER + "\"}")
                .then()
                .statusCode(200);

        dax("DeleteSubnetGroup", "{\"SubnetGroupName\":\"dax-it-subnets\"}")
                .then()
                .statusCode(200);
    }

    private static Response dax(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonDAXV3." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}

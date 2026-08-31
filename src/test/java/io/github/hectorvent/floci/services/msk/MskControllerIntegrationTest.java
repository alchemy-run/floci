package io.github.hectorvent.floci.services.msk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

@QuarkusTest
class MskControllerIntegrationTest {

    private static final String MISSING_ARN =
            "arn:aws:kafka:us-east-1:000000000000:cluster/alchemy-nonexistent-probe/00000000-0000-0000-0000-000000000000-1";

    @Test
    void describeClusterV2OnANonexistentClusterFailsWithNotFoundException() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/api/v2/clusters/" + encode(MISSING_ARN))
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void getBootstrapBrokersOnANonexistentClusterFailsWithNotFoundException() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(MISSING_ARN) + "/bootstrap-brokers")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void listTopicsOnANonexistentClusterFailsWithNotFoundException() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(MISSING_ARN) + "/topics")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void describeTopicOnANonexistentClusterFailsWithNotFoundException() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(MISSING_ARN) + "/topics/alchemy-probe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createTopicOnANonexistentClusterFailsWithBadRequestException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {"topicName": "alchemy-probe", "partitionCount": 1}
                        """)
                .when()
                .post("/v1/clusters/" + encode(MISSING_ARN) + "/topics")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BadRequestException"))
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void createClusterV1EchoesRequestedKafkaVersion() {
        String clusterArn = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {"clusterName": "v1-version-test", "kafkaVersion": "3.5.1"}
                        """)
                .when()
                .post("/v1/clusters")
                .then()
                .statusCode(200)
                .extract().path("clusterArn");

        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(clusterArn))
                .then()
                .statusCode(200)
                .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"));
    }

    @Test
    void createClusterV2EchoesRequestedKafkaVersionFromProvisioned() {
        String clusterArn = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {"clusterName": "v2-version-test", "provisioned": {"kafkaVersion": "3.5.1"}}
                        """)
                .when()
                .post("/api/v2/clusters")
                .then()
                .statusCode(200)
                .extract().path("clusterArn");

        given()
                .header("Authorization", auth())
                .when()
                .get("/api/v2/clusters/" + encode(clusterArn))
                .then()
                .statusCode(200)
                .body("clusterInfo.clusterType", equalTo("PROVISIONED"))
                .body("clusterInfo.provisioned.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"));
    }

    @Test
    void createClusterV2WithoutProvisionedFallsBackToDefaultKafkaVersion() {
        String clusterArn = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {"clusterName": "v2-default-version-test"}
                        """)
                .when()
                .post("/api/v2/clusters")
                .then()
                .statusCode(200)
                .extract().path("clusterArn");

        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(clusterArn))
                .then()
                .statusCode(200)
                .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.6.0"));
    }

    @Test
    void createServerlessClusterV2ExposesIamBootstrapBrokers() {
        String clusterArn = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "clusterName": "v2-serverless-test",
                          "tags": {"fixture": "kafka-serverless"},
                          "serverless": {
                            "vpcConfigs": [{"subnetIds": ["subnet-a", "subnet-b"], "securityGroupIds": ["sg-a"]}],
                            "clientAuthentication": {"sasl": {"iam": {"enabled": true}}}
                          }
                        }
                        """)
                .when()
                .post("/api/v2/clusters")
                .then()
                .statusCode(200)
                .body("clusterType", equalTo("SERVERLESS"))
                .body("state", equalTo("ACTIVE"))
                .extract().path("clusterArn");

        given()
                .header("Authorization", auth())
                .when()
                .get("/api/v2/clusters/" + encode(clusterArn))
                .then()
                .statusCode(200)
                .body("clusterInfo.clusterType", equalTo("SERVERLESS"))
                .body("clusterInfo.state", equalTo("ACTIVE"))
                .body("clusterInfo.serverless.clientAuthentication.sasl.iam.enabled", equalTo(true))
                .body("clusterInfo.tags.fixture", equalTo("kafka-serverless"));

        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(clusterArn) + "/bootstrap-brokers")
                .then()
                .statusCode(200)
                .body("$", hasKey("bootstrapBrokerStringSaslIam"));
    }

    @Test
    void topicRoundtripOnServerlessCluster() {
        String clusterArn = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "clusterName": "v2-topic-roundtrip",
                          "serverless": {
                            "vpcConfigs": [{"subnetIds": ["subnet-a", "subnet-b"]}]
                          }
                        }
                        """)
                .when()
                .post("/api/v2/clusters")
                .then()
                .statusCode(200)
                .extract().path("clusterArn");

        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(clusterArn) + "/topics")
                .then()
                .statusCode(200)
                .body("topics.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {"topicName": "alchemy-probe", "partitionCount": 1}
                        """)
                .when()
                .post("/v1/clusters/" + encode(clusterArn) + "/topics")
                .then()
                .statusCode(200)
                .body("topicName", equalTo("alchemy-probe"))
                .body("partitionCount", equalTo(1))
                .body("status", equalTo("ACTIVE"));

        given()
                .header("Authorization", auth())
                .when()
                .get("/v1/clusters/" + encode(clusterArn) + "/topics/alchemy-probe")
                .then()
                .statusCode(200)
                .body("partitionCount", equalTo(1));

        given()
                .header("Authorization", auth())
                .when()
                .delete("/v1/clusters/" + encode(clusterArn) + "/topics/alchemy-probe")
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/us-east-1/kafka/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

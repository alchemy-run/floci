package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies DSQL restJson1 cluster + CDC stream CRUD, tags, and not-found. */
@QuarkusTest
class DsqlIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String KINESIS_ARN =
            "arn:aws:kinesis:us-east-1:000000000000:stream/cdc-target";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/CdcRole";
    private static final String VPC_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"DenyNonVpcConnect","Effect":"Deny","Principal":{"AWS":"*"},"Action":["dsql:DbConnect","dsql:DbConnectAdmin"],"Resource":"*","Condition":{"Null":{"aws:SourceVpc":"true"}}}]}
            """;
    private static final String EXCEPTION_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"DenyNonVpcConnect","Effect":"Deny","Principal":{"AWS":"*"},"Action":["dsql:DbConnect","dsql:DbConnectAdmin"],"Resource":"*","Condition":{"Null":{"aws:SourceVpc":"true"},"StringNotEquals":{"aws:PrincipalArn":["arn:aws:iam::123456789012:role/ExceptionRole"]}}}]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getVpcEndpointServiceNameOnAMissingClusterFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/clusters/missingcluster0000000000/vpc-endpoint-service-name")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getStreamOnANonexistentClusterFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/stream/missingcluster0000000000/missingstream00000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("CLUSTER"));
    }

    @Test
    void createGetTagUntagDeleteClusterAndStreamLifecycle() {
        String authorization = auth(EAST);

        String clusterId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"deletionProtectionEnabled\":false,\"tags\":{\"app\":\"alchemy-test\"}}")
                .when()
                .post("/cluster")
                .then()
                .statusCode(200)
                .body("identifier", notNullValue())
                .body("status", equalTo("ACTIVE"))
                .body("arn", startsWith("arn:aws:dsql:"))
                .body("deletionProtectionEnabled", equalTo(false))
                .extract()
                .path("identifier");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/cluster/" + clusterId)
                .then()
                .statusCode(200)
                .body("identifier", equalTo(clusterId))
                .body("status", equalTo("ACTIVE"))
                .body("tags.app", equalTo("alchemy-test"))
                .body("endpoint", equalTo(clusterId + ".dsql.us-east-1.on.aws"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/clusters/" + clusterId + "/vpc-endpoint-service-name")
                .then()
                .statusCode(200)
                .body("serviceName", equalTo("com.amazonaws.us-east-1.dsql"));

        String streamId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(streamBody())
                .when()
                .post("/stream/" + clusterId)
                .then()
                .statusCode(200)
                .body("clusterIdentifier", equalTo(clusterId))
                .body("streamIdentifier", notNullValue())
                .body("status", equalTo("ACTIVE"))
                .body("ordering", equalTo("UNORDERED"))
                .body("format", equalTo("JSON"))
                .body("arn", startsWith("arn:aws:dsql:"))
                .extract()
                .path("streamIdentifier");

        String streamArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/stream/" + clusterId + "/" + streamId)
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("targetDefinition.kinesis.streamArn", equalTo(KINESIS_ARN))
                .body("targetDefinition.kinesis.roleArn", equalTo(ROLE_ARN))
                .body("tags.app", equalTo("alchemy-test"))
                .body("arn", equalTo(
                        "arn:aws:dsql:us-east-1:000000000000:cluster/" + clusterId + "/stream/" + streamId))
                .extract()
                .path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"1\"}}")
                .when()
                .post("/tags/" + encode(streamArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(streamArn))
                .then()
                .statusCode(200)
                .body("tags.app", equalTo("alchemy-test"))
                .body("tags.extra", equalTo("1"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "extra")
                .when()
                .delete("/tags/" + encode(streamArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/stream/" + clusterId + "/" + streamId)
                .then()
                .statusCode(200)
                .body("tags.app", equalTo("alchemy-test"))
                .body("tags.extra", equalTo(null));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/stream/" + clusterId + "/" + streamId)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/stream/" + clusterId + "/" + streamId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("STREAM"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/cluster/" + clusterId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/cluster/" + clusterId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void clusterDeletionProtectionAndPolicyLifecycle() {
        String authorization = auth(EAST);
        String identifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"deletionProtectionEnabled\":false,\"tags\":{\"app\":\"alchemy-test\"}}")
                .when()
                .post("/cluster")
                .then()
                .statusCode(200)
                .body("identifier", notNullValue())
                .body("status", equalTo("ACTIVE"))
                .body("deletionProtectionEnabled", equalTo(false))
                .extract()
                .path("identifier");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"deletionProtectionEnabled\":true}")
                .when()
                .post("/cluster/" + identifier)
                .then()
                .statusCode(200)
                .body("identifier", equalTo(identifier));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/cluster/" + identifier)
                .then()
                .statusCode(200)
                .body("deletionProtectionEnabled", equalTo(true));

        String version = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policy\":" + quote(VPC_POLICY) + "}")
                .when()
                .post("/cluster/" + identifier + "/policy")
                .then()
                .statusCode(200)
                .body("policyVersion", notNullValue())
                .extract()
                .path("policyVersion");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/cluster/" + identifier + "/policy")
                .then()
                .statusCode(200)
                .body("policy", containsString("DenyNonVpcConnect"))
                .body("policyVersion", equalTo(version));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policy\":" + quote(EXCEPTION_POLICY)
                        + ",\"expectedPolicyVersion\":" + quote(version) + "}")
                .when()
                .post("/cluster/" + identifier + "/policy")
                .then()
                .statusCode(200)
                .body("policyVersion", not(equalTo(version)));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/cluster/" + identifier + "/policy")
                .then()
                .statusCode(200)
                .body("policy", containsString("ExceptionRole"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/cluster/" + identifier)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("reason", equalTo("deletionProtectionEnabled"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"deletionProtectionEnabled\":false}")
                .when()
                .post("/cluster/" + identifier)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/cluster/" + identifier)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));
    }

    @Test
    void deleteClusterWithActiveStreamConflicts() {
        String authorization = auth(EAST);
        String clusterId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/cluster")
                .then()
                .statusCode(200)
                .extract()
                .path("identifier");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(streamBody())
                .when()
                .post("/stream/" + clusterId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/cluster/" + clusterId)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    private static String streamBody() {
        return """
                {
                  "targetDefinition": {
                    "kinesis": {
                      "streamArn": "%s",
                      "roleArn": "%s"
                    }
                  },
                  "ordering": "UNORDERED",
                  "format": "JSON",
                  "tags": { "app": "alchemy-test" }
                }
                """.formatted(KINESIS_ARN, ROLE_ARN);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/dsql/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}

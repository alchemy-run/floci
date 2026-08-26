package io.github.hectorvent.floci.services.xray;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * X-Ray restJson1 resource-policy coverage used by Alchemy ResourcePolicy:
 * Put/List/Delete, revision-id updates, and idempotent delete.
 */
@QuarkusTest
class XRayResourcePolicyIntegrationTest {

    private static final String ACCOUNT = "000000002601";
    private static final String REGION = "us-east-1";
    private static final String DOCUMENT = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
            "Principal":{"Service":"sns.amazonaws.com"},\
            "Action":["xray:PutTraceSegments"],"Resource":"*"}]}""";
    private static final String UPDATED = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
            "Principal":{"Service":"sns.amazonaws.com"},\
            "Action":["xray:PutTraceSegments","xray:GetSamplingRules"],"Resource":"*"}]}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putListUpdateAndDeleteResourcePolicy() {
        String name = "alchemy-xray-policy";

        String createdRevision = xray("PutResourcePolicy",
                "{\"PolicyName\":\"" + name + "\",\"PolicyDocument\":" + quote(DOCUMENT) + "}")
                .then()
                .statusCode(200)
                .body("ResourcePolicy.PolicyName", equalTo(name))
                .body("ResourcePolicy.PolicyDocument", equalTo(DOCUMENT))
                .body("ResourcePolicy.PolicyRevisionId", notNullValue())
                .body("ResourcePolicy.LastUpdatedTime", notNullValue())
                .extract().path("ResourcePolicy.PolicyRevisionId");

        xray("ListResourcePolicies", "{}")
                .then()
                .statusCode(200)
                .body("ResourcePolicies.PolicyName", hasItem(name))
                .body("ResourcePolicies.PolicyDocument", hasItem(DOCUMENT));

        String updatedRevision = xray("PutResourcePolicy",
                "{\"PolicyName\":\"" + name + "\",\"PolicyDocument\":" + quote(UPDATED)
                        + ",\"PolicyRevisionId\":\"" + createdRevision + "\"}")
                .then()
                .statusCode(200)
                .body("ResourcePolicy.PolicyDocument", equalTo(UPDATED))
                .body("ResourcePolicy.PolicyRevisionId", notNullValue())
                .extract().path("ResourcePolicy.PolicyRevisionId");
        assertNotEquals(createdRevision, updatedRevision);

        xray("PutResourcePolicy",
                "{\"PolicyName\":\"" + name + "\",\"PolicyDocument\":" + quote(UPDATED)
                        + ",\"PolicyRevisionId\":\"" + createdRevision + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidPolicyRevisionIdException"));

        xray("DeleteResourcePolicy", "{\"PolicyName\":\"" + name + "\"}")
                .then()
                .statusCode(200);

        xray("ListResourcePolicies", "{}")
                .then()
                .statusCode(200)
                .body("ResourcePolicies.PolicyName", not(hasItem(name)));

        xray("DeleteResourcePolicy", "{\"PolicyName\":\"" + name + "\"}")
                .then()
                .statusCode(200);
    }

    private static String quote(String document) {
        return "\"" + document.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Response xray(String action, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260205/" + REGION
                                + "/xray/aws4_request")
                .body(body)
                .when()
                .post("/" + action);
    }
}

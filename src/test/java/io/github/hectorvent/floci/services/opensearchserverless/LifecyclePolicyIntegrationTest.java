package io.github.hectorvent.floci.services.opensearchserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Lifecycle policy CRUD matching Alchemy's OpenSearch Serverless LifecyclePolicy
 * resource: create, batch-get, no-op version, update bumps version, delete, gone.
 */
@QuarkusTest
class LifecyclePolicyIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/aoss/aws4_request";
    private static final String TARGET = "OpenSearchServerless.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void batchGetLifecyclePolicy_missing_returnsErrorDetails() {
        aoss("BatchGetLifecyclePolicy",
                "{\"identifiers\":[{\"type\":\"retention\",\"name\":\"missing-lifecycle-policy\"}]}")
                .then()
                .statusCode(200)
                .body("lifecyclePolicyErrorDetails[0].name", equalTo("missing-lifecycle-policy"))
                .body("lifecyclePolicyErrorDetails[0].errorCode", equalTo("NOT_FOUND"));
    }

    @Test
    void lifecyclePolicyCreateGetUpdateDelete() {
        String name = "floci-lp-" + UUID.randomUUID().toString().substring(0, 8);
        String policy24h = """
                {\\"Rules\\":[{\\"ResourceType\\":\\"index\\",\\"Resource\\":[\\"index/alchemy-lp-test/*\\"],\\"MinIndexRetention\\":\\"24h\\"}]}
                """.trim();
        String policy48h = """
                {\\"Rules\\":[{\\"ResourceType\\":\\"index\\",\\"Resource\\":[\\"index/alchemy-lp-test/*\\"],\\"MinIndexRetention\\":\\"48h\\"}]}
                """.trim();

        String initialVersion = aoss("CreateLifecyclePolicy", """
                {
                  "type": "retention",
                  "name": "%s",
                  "description": "alchemy test retention",
                  "policy": "%s"
                }
                """.formatted(name, policy24h))
                .then()
                .statusCode(200)
                .body("lifecyclePolicyDetail.name", equalTo(name))
                .body("lifecyclePolicyDetail.type", equalTo("retention"))
                .body("lifecyclePolicyDetail.policyVersion", notNullValue())
                .body("lifecyclePolicyDetail.policy.Rules[0].MinIndexRetention", equalTo("24h"))
                .extract().path("lifecyclePolicyDetail.policyVersion");

        aoss("BatchGetLifecyclePolicy",
                "{\"identifiers\":[{\"type\":\"retention\",\"name\":\"" + name + "\"}]}")
                .then()
                .statusCode(200)
                .body("lifecyclePolicyDetails[0].name", equalTo(name))
                .body("lifecyclePolicyDetails[0].policyVersion", equalTo(initialVersion))
                .body("lifecyclePolicyDetails[0].policy.Rules[0].MinIndexRetention", equalTo("24h"));

        aoss("ListLifecyclePolicies", "{\"type\":\"retention\"}")
                .then()
                .statusCode(200)
                .body("lifecyclePolicySummaries.name", hasItem(name));

        aoss("CreateLifecyclePolicy", """
                {
                  "type": "retention",
                  "name": "%s",
                  "policy": "%s"
                }
                """.formatted(name, policy24h))
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        String updatedVersion = aoss("UpdateLifecyclePolicy", """
                {
                  "type": "retention",
                  "name": "%s",
                  "policyVersion": "%s",
                  "policy": "%s"
                }
                """.formatted(name, initialVersion, policy48h))
                .then()
                .statusCode(200)
                .body("lifecyclePolicyDetail.policyVersion", not(equalTo(initialVersion)))
                .body("lifecyclePolicyDetail.policy.Rules[0].MinIndexRetention", equalTo("48h"))
                .extract().path("lifecyclePolicyDetail.policyVersion");

        aoss("BatchGetLifecyclePolicy",
                "{\"identifiers\":[{\"type\":\"retention\",\"name\":\"" + name + "\"}]}")
                .then()
                .statusCode(200)
                .body("lifecyclePolicyDetails[0].policyVersion", equalTo(updatedVersion))
                .body("lifecyclePolicyDetails[0].policy.Rules[0].MinIndexRetention", equalTo("48h"));

        aoss("DeleteLifecyclePolicy",
                "{\"type\":\"retention\",\"name\":\"" + name + "\"}")
                .then()
                .statusCode(200);

        aoss("BatchGetLifecyclePolicy",
                "{\"identifiers\":[{\"type\":\"retention\",\"name\":\"" + name + "\"}]}")
                .then()
                .statusCode(200)
                .body("lifecyclePolicyDetails.name", not(hasItem(name)))
                .body("lifecyclePolicyErrorDetails[0].errorCode", equalTo("NOT_FOUND"));

        aoss("DeleteLifecyclePolicy",
                "{\"type\":\"retention\",\"name\":\"" + name + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response aoss(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}

package io.github.hectorvent.floci.services.opensearchserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Collection + security/access policy coverage matching Alchemy's
 * OpenSearch Serverless Collection test: typed not-found, policy lifecycle,
 * create ACTIVE collection, tags, delete until gone.
 */
@QuarkusTest
class OpenSearchServerlessCollectionIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/aoss/aws4_request";
    private static final String TARGET = "OpenSearchServerless.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSecurityPolicy_missing_returnsResourceNotFoundException() {
        aoss("GetSecurityPolicy",
                "{\"type\":\"encryption\",\"name\":\"alchemy-nonexistent-probe\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void batchGetCollection_missing_reportsCollectionErrorDetails() {
        aoss("BatchGetCollection",
                "{\"names\":[\"alchemy-nonexistent-collection-probe\"]}")
                .then()
                .statusCode(200)
                .body("collectionDetails", empty())
                .body("collectionErrorDetails", hasSize(greaterThan(0)))
                .body("collectionErrorDetails[0].name",
                        equalTo("alchemy-nonexistent-collection-probe"))
                .body("collectionErrorDetails[0].errorCode", equalTo("NOT_FOUND"));
    }

    @Test
    void securityAccessPolicyAndCollectionLifecycle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String collectionName = "floci-aoss-" + suffix;
        String encName = "floci-enc-" + suffix;
        String netName = "floci-net-" + suffix;
        String accName = "floci-acc-" + suffix;

        aoss("CreateSecurityPolicy", """
                {
                  "type": "encryption",
                  "name": "%s",
                  "description": "alchemy test encryption policy",
                  "policy": "{\\"Rules\\":[{\\"ResourceType\\":\\"collection\\",\\"Resource\\":[\\"collection/%s\\"]}],\\"AWSOwnedKey\\":true}"
                }
                """.formatted(encName, collectionName))
                .then()
                .statusCode(200)
                .body("securityPolicyDetail.name", equalTo(encName))
                .body("securityPolicyDetail.type", equalTo("encryption"))
                .body("securityPolicyDetail.policyVersion", notNullValue());

        String initialNetVersion = aoss("CreateSecurityPolicy", """
                {
                  "type": "network",
                  "name": "%s",
                  "policy": "[{\\"Rules\\":[{\\"ResourceType\\":\\"collection\\",\\"Resource\\":[\\"collection/%s\\"]}],\\"AllowFromPublic\\":true}]"
                }
                """.formatted(netName, collectionName))
                .then()
                .statusCode(200)
                .body("securityPolicyDetail.type", equalTo("network"))
                .extract().path("securityPolicyDetail.policyVersion");

        aoss("CreateAccessPolicy", """
                {
                  "type": "data",
                  "name": "%s",
                  "policy": "[{\\"Rules\\":[{\\"ResourceType\\":\\"collection\\",\\"Resource\\":[\\"collection/%s\\"],\\"Permission\\":[\\"aoss:*\\"]}],\\"Principal\\":[\\"arn:aws:iam::000000000000:role/alchemy-test-aoss-role\\"]}]"
                }
                """.formatted(accName, collectionName))
                .then()
                .statusCode(200)
                .body("accessPolicyDetail.name", equalTo(accName))
                .body("accessPolicyDetail.type", equalTo("data"));

        aoss("GetSecurityPolicy",
                "{\"type\":\"encryption\",\"name\":\"" + encName + "\"}")
                .then()
                .statusCode(200)
                .body("securityPolicyDetail.name", equalTo(encName));

        aoss("GetAccessPolicy", "{\"type\":\"data\",\"name\":\"" + accName + "\"}")
                .then()
                .statusCode(200)
                .body("accessPolicyDetail.name", equalTo(accName));

        aoss("UpdateSecurityPolicy", """
                {
                  "type": "network",
                  "name": "%s",
                  "policyVersion": "%s",
                  "policy": "[{\\"Rules\\":[{\\"ResourceType\\":\\"collection\\",\\"Resource\\":[\\"collection/%s\\"]}],\\"AllowFromPublic\\":true}]"
                }
                """.formatted(netName, initialNetVersion, collectionName))
                .then()
                .statusCode(200)
                .body("securityPolicyDetail.policyVersion", not(equalTo(initialNetVersion)));

        String collectionId = aoss("CreateCollection", """
                {
                  "name": "%s",
                  "type": "VECTORSEARCH",
                  "standbyReplicas": "DISABLED",
                  "tags": [{"key": "fixture", "value": "aoss-collection"}]
                }
                """.formatted(collectionName))
                .then()
                .statusCode(200)
                .body("createCollectionDetail.id", notNullValue())
                .body("createCollectionDetail.name", equalTo(collectionName))
                .body("createCollectionDetail.type", equalTo("VECTORSEARCH"))
                .body("createCollectionDetail.status", equalTo("ACTIVE"))
                .body("createCollectionDetail.arn", startsWith("arn:aws:aoss:"))
                .extract().path("createCollectionDetail.id");

        aoss("BatchGetCollection", "{\"ids\":[\"" + collectionId + "\"]}")
                .then()
                .statusCode(200)
                .body("collectionDetails[0].status", equalTo("ACTIVE"))
                .body("collectionDetails[0].type", equalTo("VECTORSEARCH"))
                .body("collectionDetails[0].collectionEndpoint",
                        startsWith("https://" + collectionId + "."));

        String arn = aoss("BatchGetCollection", "{\"ids\":[\"" + collectionId + "\"]}")
                .then()
                .statusCode(200)
                .extract().path("collectionDetails[0].arn");

        aoss("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("fixture"));

        aoss("DeleteCollection", "{\"id\":\"" + collectionId + "\"}")
                .then()
                .statusCode(200)
                .body("deleteCollectionDetail.status", equalTo("DELETING"));

        aoss("BatchGetCollection", "{\"ids\":[\"" + collectionId + "\"]}")
                .then()
                .statusCode(200)
                .body("collectionDetails", empty())
                .body("collectionErrorDetails", hasSize(greaterThan(0)));

        aoss("DeleteSecurityPolicy",
                "{\"type\":\"encryption\",\"name\":\"" + encName + "\"}")
                .then()
                .statusCode(200);
        aoss("GetSecurityPolicy",
                "{\"type\":\"encryption\",\"name\":\"" + encName + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        aoss("DeleteSecurityPolicy",
                "{\"type\":\"network\",\"name\":\"" + netName + "\"}")
                .then()
                .statusCode(200);
        aoss("DeleteAccessPolicy", "{\"type\":\"data\",\"name\":\"" + accName + "\"}")
                .then()
                .statusCode(200);
        aoss("GetAccessPolicy", "{\"type\":\"data\",\"name\":\"" + accName + "\"}")
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

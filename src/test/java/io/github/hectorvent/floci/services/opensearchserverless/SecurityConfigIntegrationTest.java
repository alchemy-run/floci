package io.github.hectorvent.floci.services.opensearchserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * SAML security config CRUD matching Alchemy's SecurityConfig resource:
 * typed not-found, create, list, description-only version stability,
 * samlOptions update version bump, delete, verify gone.
 */
@QuarkusTest
class SecurityConfigIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/aoss/aws4_request";
    private static final String TARGET = "OpenSearchServerless.";
    private static final String METADATA =
            "<EntityDescriptor entityID=\\\"https://idp.example.com/saml\\\"/>";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSecurityConfig_missing_returnsResourceNotFoundException() {
        aoss("GetSecurityConfig", "{\"id\":\"saml/000000000000/missing-config\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void samlSecurityConfigLifecycleCreateListUpdateDelete() {
        String name = "floci-saml-" + UUID.randomUUID().toString().substring(0, 8);

        String id = aoss("CreateSecurityConfig", """
                {
                  "type": "saml",
                  "name": "%s",
                  "description": "alchemy test saml config",
                  "samlOptions": {
                    "metadata": "%s",
                    "groupAttribute": "groups",
                    "sessionTimeout": 120
                  }
                }
                """.formatted(name, METADATA))
                .then()
                .statusCode(200)
                .body("securityConfigDetail.id", containsString("saml/"))
                .body("securityConfigDetail.id", containsString("/" + name))
                .body("securityConfigDetail.type", equalTo("saml"))
                .body("securityConfigDetail.configVersion", notNullValue())
                .body("securityConfigDetail.samlOptions.sessionTimeout", equalTo(120))
                .body("securityConfigDetail.samlOptions.groupAttribute", equalTo("groups"))
                .extract().path("securityConfigDetail.id");

        String initialVersion = aoss("GetSecurityConfig", "{\"id\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("securityConfigDetail.samlOptions.sessionTimeout", equalTo(120))
                .extract().path("securityConfigDetail.configVersion");

        aoss("ListSecurityConfigs", "{\"type\":\"saml\"}")
                .then()
                .statusCode(200)
                .body("securityConfigSummaries.id", hasItem(id));

        aoss("UpdateSecurityConfig", """
                {"id":"%s","configVersion":"%s","description":"alchemy test saml config v2"}
                """.formatted(id, initialVersion))
                .then()
                .statusCode(200)
                .body("securityConfigDetail.description", equalTo("alchemy test saml config v2"))
                .body("securityConfigDetail.configVersion", equalTo(initialVersion));

        aoss("UpdateSecurityConfig", """
                {
                  "id": "%s",
                  "configVersion": "%s",
                  "description": "alchemy test saml config v2",
                  "samlOptions": {
                    "metadata": "%s",
                    "groupAttribute": "groups",
                    "sessionTimeout": 180
                  }
                }
                """.formatted(id, initialVersion, METADATA))
                .then()
                .statusCode(200)
                .body("securityConfigDetail.configVersion", not(equalTo(initialVersion)))
                .body("securityConfigDetail.samlOptions.sessionTimeout", equalTo(180));

        aoss("CreateSecurityConfig", """
                {"type":"saml","name":"%s","samlOptions":{"metadata":"%s"}}
                """.formatted(name, METADATA))
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        aoss("DeleteSecurityConfig", "{\"id\":\"" + id + "\"}")
                .then()
                .statusCode(200);

        aoss("GetSecurityConfig", "{\"id\":\"" + id + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        aoss("DeleteSecurityConfig", "{\"id\":\"" + id + "\"}")
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

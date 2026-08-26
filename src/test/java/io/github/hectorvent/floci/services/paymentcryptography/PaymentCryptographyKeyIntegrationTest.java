package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class PaymentCryptographyKeyIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = PaymentCryptographyJsonHandler.TARGET_PREFIX;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/payment-cryptography/aws4_request";
    private static final String KEY_ATTRIBUTES = """
            "KeyAttributes": {
              "KeyAlgorithm": "AES_128",
              "KeyClass": "SYMMETRIC_KEY",
              "KeyUsage": "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
              "KeyModesOfUse": {
                "Encrypt": true,
                "Decrypt": true,
                "Wrap": true,
                "Unwrap": true
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listKeys_empty_returnsKeysArray() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListKeys")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Keys", notNullValue());
    }

    @Test
    void getKey_missing_returnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetKey")
                .header("Authorization", AUTH)
                .body("{\"KeyIdentifier\":\"alias/alchemy-nonexistent-payment-key-probe\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getAlias_missing_returnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetAlias")
                .header("Authorization", AUTH)
                .body("{\"AliasName\":\"alias/alchemy-nonexistent-payment-alias-probe\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void keyAndAliasLifecycle_createGetTagDisableDelete() {
        var created = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateKey")
                .header("Authorization", AUTH)
                .body("""
                        {
                          %s,
                          "Exportable": false,
                          "Enabled": true,
                          "Tags": [{"Key":"fixture","Value":"payment-cryptography-key"}]
                        }
                        """.formatted(KEY_ATTRIBUTES))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Key.KeyArn", startsWith("arn:aws:payment-cryptography:"))
                .body("Key.KeyState", equalTo("CREATE_COMPLETE"))
                .body("Key.Enabled", equalTo(true))
                .body("Key.Exportable", equalTo(false))
                .body("Key.KeyCheckValue", notNullValue())
                .body("Key.KeyAttributes.KeyAlgorithm", equalTo("AES_128"))
                .extract();
        String keyArn = created.path("Key.KeyArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetKey")
                .header("Authorization", AUTH)
                .body("{\"KeyIdentifier\":\"" + keyArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Key.KeyArn", equalTo(keyArn))
                .body("Key.KeyState", equalTo("CREATE_COMPLETE"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListKeys")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Keys.KeyArn", hasItem(keyArn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + keyArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "TagResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + keyArn + "\",\"Tags\":[{\"Key\":\"phase\",\"Value\":\"two\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UntagResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + keyArn + "\",\"TagKeys\":[\"fixture\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        String aliasName = "alias/floci-paycrypto-" + keyArn.substring(keyArn.length() - 8);
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateAlias")
                .header("Authorization", AUTH)
                .body("{\"AliasName\":\"" + aliasName + "\",\"KeyArn\":\"" + keyArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Alias.AliasName", equalTo(aliasName))
                .body("Alias.KeyArn", equalTo(keyArn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetAlias")
                .header("Authorization", AUTH)
                .body("{\"AliasName\":\"" + aliasName + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Alias.KeyArn", equalTo(keyArn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "StopKeyUsage")
                .header("Authorization", AUTH)
                .body("{\"KeyIdentifier\":\"" + keyArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Key.Enabled", equalTo(false));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "StartKeyUsage")
                .header("Authorization", AUTH)
                .body("{\"KeyIdentifier\":\"" + keyArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Key.Enabled", equalTo(true));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteKey")
                .header("Authorization", AUTH)
                .body("{\"KeyIdentifier\":\"" + keyArn + "\",\"DeleteKeyInDays\":3}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Key.KeyState", equalTo("DELETE_PENDING"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteKey")
                .header("Authorization", AUTH)
                .body("{\"KeyIdentifier\":\"" + keyArn + "\",\"DeleteKeyInDays\":3}")
        .when()
                .post("/")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteAlias")
                .header("Authorization", AUTH)
                .body("{\"AliasName\":\"" + aliasName + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetAlias")
                .header("Authorization", AUTH)
                .body("{\"AliasName\":\"" + aliasName + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}

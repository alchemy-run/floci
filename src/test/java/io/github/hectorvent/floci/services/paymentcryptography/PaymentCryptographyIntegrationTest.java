package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Control-plane JSON 1.0 coverage used by Alchemy PaymentCryptography.Key
 * (create/get/list/tags/delete) plus GetPublicKeyCertificate.
 */
@QuarkusTest
class PaymentCryptographyIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "PaymentCryptographyControlPlane";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/payment-cryptography/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getKey_missing_returnsResourceNotFoundException() {
        control("GetKey", "{\"KeyIdentifier\":\"key-does-not-exist\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void keyLifecycle_createGetTagDelete() {
        String created = control("CreateKey", """
                {
                  "KeyAttributes": {
                    "KeyAlgorithm": "AES_128",
                    "KeyClass": "SYMMETRIC_KEY",
                    "KeyUsage": "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                    "KeyModesOfUse": {"Encrypt": true, "Decrypt": true, "Wrap": true, "Unwrap": true}
                  },
                  "Exportable": false,
                  "Enabled": true,
                  "Tags": [{"Key": "alchemy::id", "Value": "DataKey"}]
                }
                """)
                .then()
                .statusCode(200)
                .body("Key.KeyArn", startsWith("arn:aws:payment-cryptography:"))
                .body("Key.KeyState", equalTo("CREATE_COMPLETE"))
                .body("Key.Enabled", equalTo(true))
                .body("Key.Exportable", equalTo(false))
                .body("Key.KeyCheckValue", notNullValue())
                .extract().path("Key.KeyArn");

        control("GetKey", "{\"KeyIdentifier\":\"" + created + "\"}")
                .then()
                .statusCode(200)
                .body("Key.KeyArn", equalTo(created))
                .body("Key.KeyAttributes.KeyAlgorithm", equalTo("AES_128"));

        control("ListTagsForResource", "{\"ResourceArn\":\"" + created + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"));

        control("TagResource", "{\"ResourceArn\":\"" + created
                + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .then()
                .statusCode(200);

        control("ListKeys", "{}")
                .then()
                .statusCode(200)
                .body("Keys.KeyArn", hasItem(created));

        control("DeleteKey", "{\"KeyIdentifier\":\"" + created + "\",\"DeleteKeyInDays\":3}")
                .then()
                .statusCode(200)
                .body("Key.KeyState", equalTo("DELETE_PENDING"));

        control("DeleteKey", "{\"KeyIdentifier\":\"" + created + "\",\"DeleteKeyInDays\":3}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        control("RestoreKey", "{\"KeyIdentifier\":\"" + created + "\"}")
                .then()
                .statusCode(200)
                .body("Key.KeyState", equalTo("CREATE_COMPLETE"));
    }

    @Test
    void getPublicKeyCertificate_returnsPemForEccKeyPair() {
        String arn = control("CreateKey", """
                {
                  "KeyAttributes": {
                    "KeyAlgorithm": "ECC_NIST_P256",
                    "KeyClass": "ASYMMETRIC_KEY_PAIR",
                    "KeyUsage": "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE",
                    "KeyModesOfUse": {"Sign": true}
                  },
                  "Exportable": false
                }
                """)
                .then()
                .statusCode(200)
                .extract().path("Key.KeyArn");

        control("GetPublicKeyCertificate", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("KeyCertificate", startsWith("-----BEGIN CERTIFICATE-----"))
                .body("KeyCertificateChain", startsWith("-----BEGIN CERTIFICATE-----"))
                .body("KeyCertificate", not(equalTo("")));
    }

    private static Response control(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "." + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}

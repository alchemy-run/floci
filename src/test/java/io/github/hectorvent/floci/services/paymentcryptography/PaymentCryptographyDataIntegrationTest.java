package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Data-plane restJson1 coverage used by Alchemy PaymentCryptography bindings:
 * Encrypt/Decrypt, HMAC, CVV2, Visa PIN, DUKPT re-encrypt.
 */
@QuarkusTest
class PaymentCryptographyDataIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CONTROL = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "PaymentCryptographyControlPlane";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/payment-cryptography/aws4_request";
    private static final String AES_BLOCK = "31323334353637383930313233343536";
    private static final String IV = "00000000000000000000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void encryptDecrypt_roundTripsAesCbc() {
        String arn = createKey("""
                "KeyAlgorithm": "AES_128",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                "KeyModesOfUse": {"Encrypt": true, "Decrypt": true, "Wrap": true, "Unwrap": true}
                """);

        String cipher = data("POST", "/keys/" + enc(arn) + "/encrypt", """
                {"PlainText":"%s","EncryptionAttributes":{"Symmetric":{"Mode":"CBC","InitializationVector":"%s"}}}
                """.formatted(AES_BLOCK, IV))
                .then()
                .statusCode(200)
                .body("KeyArn", equalTo(arn))
                .body("CipherText", not(equalTo(AES_BLOCK)))
                .extract().path("CipherText");

        data("POST", "/keys/" + enc(arn) + "/decrypt", """
                {"CipherText":"%s","DecryptionAttributes":{"Symmetric":{"Mode":"CBC","InitializationVector":"%s"}}}
                """.formatted(cipher, IV))
                .then()
                .statusCode(200)
                .body("PlainText", equalTo(AES_BLOCK));
    }

    @Test
    void generateAndVerifyMac_rejectsTampered() {
        String arn = createKey("""
                "KeyAlgorithm": "HMAC_SHA256",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_M7_HMAC_KEY",
                "KeyModesOfUse": {"Generate": true, "Verify": true}
                """);

        String mac = data("POST", "/mac/generate", """
                {"KeyIdentifier":"%s","MessageData":"%s","GenerationAttributes":{"Algorithm":"HMAC"}}
                """.formatted(arn, AES_BLOCK))
                .then()
                .statusCode(200)
                .body("Mac", matchesPattern("^[0-9A-Fa-f]+$"))
                .extract().path("Mac");

        data("POST", "/mac/verify", """
                {"KeyIdentifier":"%s","MessageData":"%s","Mac":"%s","VerificationAttributes":{"Algorithm":"HMAC"}}
                """.formatted(arn, AES_BLOCK, mac))
                .then()
                .statusCode(200)
                .body("KeyArn", equalTo(arn));

        String tampered = (mac.startsWith("0") ? "1" : "0") + mac.substring(1);
        data("POST", "/mac/verify", """
                {"KeyIdentifier":"%s","MessageData":"%s","Mac":"%s","VerificationAttributes":{"Algorithm":"HMAC"}}
                """.formatted(arn, AES_BLOCK, tampered))
                .then()
                .statusCode(400)
                .body("__type", equalTo("VerificationFailedException"))
                .body("Reason", equalTo("INVALID_MAC"));
    }

    @Test
    void cardValidation_generateVerifyAndRejectTampered() {
        String arn = createKey("""
                "KeyAlgorithm": "TDES_2KEY",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_C0_CARD_VERIFICATION_KEY",
                "KeyModesOfUse": {"Generate": true, "Verify": true}
                """);

        String cvv2 = data("POST", "/cardvalidationdata/generate", """
                {"KeyIdentifier":"%s","PrimaryAccountNumber":"9123456789012345",
                 "GenerationAttributes":{"CardVerificationValue2":{"CardExpiryDate":"0130"}}}
                """.formatted(arn))
                .then()
                .statusCode(200)
                .body("ValidationData", matchesPattern("^\\d{3}$"))
                .extract().path("ValidationData");

        data("POST", "/cardvalidationdata/verify", """
                {"KeyIdentifier":"%s","PrimaryAccountNumber":"9123456789012345",
                 "VerificationAttributes":{"CardVerificationValue2":{"CardExpiryDate":"0130"}},
                 "ValidationData":"%s"}
                """.formatted(arn, cvv2))
                .then()
                .statusCode(200)
                .body("KeyArn", equalTo(arn));

        StringBuilder tampered = new StringBuilder();
        for (char c : cvv2.toCharArray()) {
            tampered.append((c - '0' + 1) % 10);
        }
        data("POST", "/cardvalidationdata/verify", """
                {"KeyIdentifier":"%s","PrimaryAccountNumber":"9123456789012345",
                 "VerificationAttributes":{"CardVerificationValue2":{"CardExpiryDate":"0130"}},
                 "ValidationData":"%s"}
                """.formatted(arn, tampered))
                .then()
                .statusCode(400)
                .body("__type", equalTo("VerificationFailedException"));
    }

    @Test
    void pinData_generateVerifyAndTranslate() {
        String pvk = createKey("""
                "KeyAlgorithm": "TDES_2KEY",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_V2_VISA_PIN_VERIFICATION_KEY",
                "KeyModesOfUse": {"Generate": true, "Verify": true}
                """);
        String pek = createKey("""
                "KeyAlgorithm": "TDES_2KEY",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_P0_PIN_ENCRYPTION_KEY",
                "KeyModesOfUse": {"Encrypt": true, "Decrypt": true, "Wrap": true, "Unwrap": true}
                """);
        String pek2 = createKey("""
                "KeyAlgorithm": "TDES_2KEY",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_P0_PIN_ENCRYPTION_KEY",
                "KeyModesOfUse": {"Encrypt": true, "Decrypt": true, "Wrap": true, "Unwrap": true}
                """);

        var generated = data("POST", "/pindata/generate", """
                {"GenerationKeyIdentifier":"%s","EncryptionKeyIdentifier":"%s",
                 "GenerationAttributes":{"VisaPin":{"PinVerificationKeyIndex":1}},
                 "PrimaryAccountNumber":"9123456789012345","PinBlockFormat":"ISO_FORMAT_0"}
                """.formatted(pvk, pek))
                .then()
                .statusCode(200)
                .body("PinData.VerificationValue", matchesPattern("^\\d+$"))
                .body("EncryptedPinBlock", matchesPattern("^[0-9A-Fa-f]+$"))
                .extract();
        String pvv = generated.path("PinData.VerificationValue");
        String pinBlock = generated.path("EncryptedPinBlock");

        data("POST", "/pindata/verify", """
                {"VerificationKeyIdentifier":"%s","EncryptionKeyIdentifier":"%s",
                 "VerificationAttributes":{"VisaPin":{"PinVerificationKeyIndex":1,"VerificationValue":"%s"}},
                 "EncryptedPinBlock":"%s","PrimaryAccountNumber":"9123456789012345",
                 "PinBlockFormat":"ISO_FORMAT_0"}
                """.formatted(pvk, pek, pvv, pinBlock))
                .then()
                .statusCode(200)
                .body("VerificationKeyArn", equalTo(pvk));

        data("POST", "/pindata/translate", """
                {"IncomingKeyIdentifier":"%s","OutgoingKeyIdentifier":"%s",
                 "IncomingTranslationAttributes":{"IsoFormat0":{"PrimaryAccountNumber":"9123456789012345"}},
                 "OutgoingTranslationAttributes":{"IsoFormat0":{"PrimaryAccountNumber":"9123456789012345"}},
                 "EncryptedPinBlock":"%s"}
                """.formatted(pek, pek2, pinBlock))
                .then()
                .statusCode(200)
                .body("KeyArn", equalTo(pek2))
                .body("PinBlock", matchesPattern("^[0-9A-Fa-f]+$"))
                .body("PinBlock", not(equalTo(pinBlock)));
    }

    @Test
    void reEncrypt_dukptToSymmetricRoundTrip() {
        String bdk = createKey("""
                "KeyAlgorithm": "TDES_2KEY",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_B0_BASE_DERIVATION_KEY",
                "KeyModesOfUse": {"DeriveKey": true}
                """);
        String dataKey = createKey("""
                "KeyAlgorithm": "AES_128",
                "KeyClass": "SYMMETRIC_KEY",
                "KeyUsage": "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                "KeyModesOfUse": {"Encrypt": true, "Decrypt": true, "Wrap": true, "Unwrap": true}
                """);
        String ksn = "FFFF9876543210E00001";
        String plain = "41424344414243444142434441424344";

        String cipher = data("POST", "/keys/" + enc(bdk) + "/encrypt", """
                {"PlainText":"%s","EncryptionAttributes":{"Dukpt":{"KeySerialNumber":"%s","Mode":"CBC"}}}
                """.formatted(plain, ksn))
                .then()
                .statusCode(200)
                .body("CipherText", notNullValue())
                .extract().path("CipherText");

        String reencrypted = data("POST", "/keys/" + enc(bdk) + "/reencrypt", """
                {"OutgoingKeyIdentifier":"%s","CipherText":"%s",
                 "IncomingEncryptionAttributes":{"Dukpt":{"KeySerialNumber":"%s","Mode":"CBC"}},
                 "OutgoingEncryptionAttributes":{"Symmetric":{"Mode":"CBC","InitializationVector":"%s"}}}
                """.formatted(dataKey, cipher, ksn, IV))
                .then()
                .statusCode(200)
                .body("KeyArn", equalTo(dataKey))
                .extract().path("CipherText");

        data("POST", "/keys/" + enc(dataKey) + "/decrypt", """
                {"CipherText":"%s","DecryptionAttributes":{"Symmetric":{"Mode":"CBC","InitializationVector":"%s"}}}
                """.formatted(reencrypted, IV))
                .then()
                .statusCode(200)
                .body("PlainText", equalTo(plain));
    }

    private static String createKey(String attributes) {
        return given()
                .contentType(CONTROL)
                .header("X-Amz-Target", TARGET + ".CreateKey")
                .header("Authorization", AUTH)
                .body("{\"KeyAttributes\":{" + attributes + "},\"Exportable\":false}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().path("Key.KeyArn");
    }

    private static Response data(String method, String path, String body) {
        return given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post(path);
    }

    private static String enc(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}

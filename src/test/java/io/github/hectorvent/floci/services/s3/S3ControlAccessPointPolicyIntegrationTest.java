package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S3 Control Access Point policy REST-XML: Get/Put/DeleteAccessPointPolicy.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ControlAccessPointPolicyIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String BUCKET = "s3control-ap-policy-it-bucket";
    private static final String NAME = "s3control-ap-policy-it";
    private static final String MISSING = "alchemy-ap-policy-missing-xyz";

    private static final String POLICY_ONE = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},"Action":["s3:GetObject"],"Resource":"arn:aws:s3:us-east-1:000000000000:accesspoint/s3control-ap-policy-it/object/*"}]}
            """.trim();

    private static final String POLICY_TWO = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},"Action":["s3:GetObject"],"Resource":"arn:aws:s3:us-east-1:000000000000:accesspoint/s3control-ap-policy-it/object/*"},{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},"Action":["s3:ListBucket"],"Resource":"arn:aws:s3:us-east-1:000000000000:accesspoint/s3control-ap-policy-it"}]}
            """.trim();

    private static String putPolicyBody(String policyJson) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <PutAccessPointPolicyRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                  <Policy>%s</Policy>
                </PutAccessPointPolicyRequest>
                """.formatted(escapeXml(policyJson));
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @Test
    @Order(1)
    @DisplayName("GetAccessPointPolicy on a missing access point returns NoSuchAccessPoint")
    void getPolicyMissingAccessPoint() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspoint/" + MISSING + "/policy")
        .then()
            .statusCode(404)
            .contentType(containsString("xml"))
            .body(containsString("<Code>NoSuchAccessPoint</Code>"));
    }

    @Test
    @Order(2)
    @DisplayName("PutAccessPointPolicy on a missing access point returns NoSuchAccessPoint")
    void putPolicyMissingAccessPoint() {
        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(putPolicyBody(POLICY_ONE))
        .when()
            .put("/v20180820/accesspoint/" + MISSING + "/policy")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchAccessPoint</Code>"));
    }

    @Test
    @Order(3)
    @DisplayName("create access point, put/get/update/delete policy")
    void createPutGetUpdateDeleteAccessPointPolicy() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <CreateAccessPointRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <Bucket>%s</Bucket>
                    </CreateAccessPointRequest>
                    """.formatted(BUCKET))
        .when()
            .put("/v20180820/accesspoint/" + NAME)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchAccessPointPolicy</Code>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(putPolicyBody(POLICY_ONE))
        .when()
            .put("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(200);

        String first = given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(200)
            .body(containsString("<Policy>"))
            .body(containsString("s3:GetObject"))
            .extract()
            .body()
            .asString();
        String stored = XmlParser.extractFirst(first, "Policy", null);
        assertNotNull(stored);
        assertEquals(POLICY_ONE, stored);

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(putPolicyBody(POLICY_ONE))
        .when()
            .put("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(200);

        String redeployed = given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();
        assertEquals(stored, XmlParser.extractFirst(redeployed, "Policy", null));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(putPolicyBody(POLICY_TWO))
        .when()
            .put("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(200)
            .body(containsString("s3:ListBucket"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchAccessPointPolicy</Code>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete("/v20180820/accesspoint/" + NAME + "/policy")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchAccessPointPolicy</Code>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete("/v20180820/accesspoint/" + NAME)
        .then()
            .statusCode(200);
    }
}

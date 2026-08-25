package io.github.hectorvent.floci.services.b2bi;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class B2biPartnershipIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/b2bi/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void profileAndPartnershipLifecycle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String profileName = "floci-b2bi-pt-profile-" + suffix;
        String partnershipName = "floci-b2bi-partnership-" + suffix;
        String transformerName = "floci-b2bi-pt-transformer-" + suffix;
        String capabilityName = "floci-b2bi-pt-capability-" + suffix;

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListProfiles")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profiles", notNullValue());

        String profileId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateProfile")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "businessName": "Alchemy Trading Corp",
                          "phone": "+15555550100",
                          "email": "edi@alchemy.example",
                          "logging": "ENABLED",
                          "tags": [{"Key":"env","Value":"test"}]
                        }
                        """.formatted(profileName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profileId", startsWith("p-"))
                .body("profileArn", org.hamcrest.Matchers.containsString(":b2bi:"))
                .body("name", equalTo(profileName))
                .body("businessName", equalTo("Alchemy Trading Corp"))
                .body("logging", equalTo("ENABLED"))
                .body("logGroupName", startsWith("/aws/vendedlogs/b2bi/profile/"))
                .extract().path("profileId");

        String profileArn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profileId", equalTo(profileId))
                .body("email", equalTo("edi@alchemy.example"))
                .extract().path("profileArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListProfiles")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profiles.profileId", hasItem(profileId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceARN\":\"" + profileArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));

        String transformerId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateTransformer")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "inputConversion": {
                            "fromFormat": "X12",
                            "formatOptions": {
                              "x12": { "transactionSet": "X12_850", "version": "VERSION_4010" }
                            }
                          },
                          "mapping": {
                            "templateLanguage": "JSONATA",
                            "template": "{ \\"orderId\\": \\"test\\" }"
                          }
                        }
                        """.formatted(transformerName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformerId", startsWith("tr-"))
                .extract().path("transformerId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\",\"status\":\"active\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("status", equalTo("active"));

        String capabilityId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateCapability")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "type": "edi",
                          "configuration": {
                            "edi": {
                              "capabilityDirection": "INBOUND",
                              "type": {
                                "x12Details": {
                                  "transactionSet": "X12_850",
                                  "version": "VERSION_4010"
                                }
                              },
                              "inputLocation": { "bucketName": "bucket", "key": "inbound/" },
                              "outputLocation": { "bucketName": "bucket", "key": "processed/" },
                              "transformerId": "%s"
                            }
                          }
                        }
                        """.formatted(capabilityName, transformerId))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("capabilityId", startsWith("ca-"))
                .extract().path("capabilityId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListPartnerships")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("partnerships", notNullValue());

        String partnershipId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreatePartnership")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "profileId": "%s",
                          "name": "%s",
                          "email": "partner@alchemy.example",
                          "phone": "+15555550101",
                          "capabilities": ["%s"]
                        }
                        """.formatted(profileId, partnershipName, capabilityId))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("partnershipId", startsWith("ps-"))
                .body("profileId", equalTo(profileId))
                .body("partnershipArn", org.hamcrest.Matchers.containsString(":b2bi:"))
                .body("name", equalTo(partnershipName))
                .body("capabilities", hasItem(capabilityId))
                .extract().path("partnershipId");

        String partnershipArn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetPartnership")
                .header("Authorization", AUTH)
                .body("{\"partnershipId\":\"" + partnershipId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("partnershipId", equalTo(partnershipId))
                .body("name", equalTo(partnershipName))
                .body("capabilities", hasItem(capabilityId))
                .extract().path("partnershipArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListPartnerships")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("partnerships.partnershipId", hasItem(partnershipId));

        String renamed = partnershipName + "-renamed";
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdatePartnership")
                .header("Authorization", AUTH)
                .body("{\"partnershipId\":\"" + partnershipId + "\",\"name\":\"" + renamed + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("partnershipId", equalTo(partnershipId))
                .body("name", equalTo(renamed));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetPartnership")
                .header("Authorization", AUTH)
                .body("{\"partnershipId\":\"" + partnershipId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("name", equalTo(renamed));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.TagResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceARN\":\"" + partnershipArn + "\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"floci\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceARN\":\"" + partnershipArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("owner"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeletePartnership")
                .header("Authorization", AUTH)
                .body("{\"partnershipId\":\"" + partnershipId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetPartnership")
                .header("Authorization", AUTH)
                .body("{\"partnershipId\":\"" + partnershipId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteCapability")
                .header("Authorization", AUTH)
                .body("{\"capabilityId\":\"" + capabilityId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }
}

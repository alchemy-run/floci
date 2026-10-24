package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.testing.ConfiguredHostnameProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ConfiguredHostnameProfile.class)
class IotEndpointHostnameIntegrationTest {

    /** The emulator's own hostname does not leak into the AWS-shaped account endpoint. */
    @Test
    void configuredHostnameDoesNotReplaceTheAwsShapedEndpoint() {
        given()
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo(
                    IotEndpoints.awsAddress("iot:Data-ATS", "000000000000", "us-east-1")));
    }
}

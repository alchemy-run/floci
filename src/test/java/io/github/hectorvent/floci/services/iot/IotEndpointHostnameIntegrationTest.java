package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IotEndpointHostnameIntegrationTest.HostnameProfile.class)
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

    public static class HostnameProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.hostname", "floci");
        }
    }
}

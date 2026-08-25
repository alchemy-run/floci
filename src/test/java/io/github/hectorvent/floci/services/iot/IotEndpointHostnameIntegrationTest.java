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

    @Test
    void configuredHostnameDoesNotReplaceAtsDescribeEndpoint() {
        given()
            .queryParam("endpointType", "iot:Data-ATS")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("000000000000-ats.iot.us-east-1.amazonaws.com"));
    }

    public static class HostnameProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.hostname", "floci");
        }
    }
}

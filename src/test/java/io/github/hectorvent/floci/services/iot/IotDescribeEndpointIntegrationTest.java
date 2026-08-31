package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class IotDescribeEndpointIntegrationTest {

    @Test
    void defaultDescribeEndpointReturnsAtsDataEndpoint() {
        given()
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("000000000000-ats.iot.us-east-1.amazonaws.com"));
    }

    @Test
    void dataAtsEndpointTypeReturnsAtsHostname() {
        given()
            .queryParam("endpointType", "iot:Data-ATS")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("000000000000-ats.iot.us-east-1.amazonaws.com"));
    }

    @Test
    void dataEndpointTypeReturnsIotHostname() {
        given()
            .queryParam("endpointType", "iot:Data")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("000000000000.iot.us-east-1.amazonaws.com"));
    }

    @Test
    void jobsEndpointTypeReturnsJobsHostname() {
        given()
            .queryParam("endpointType", "iot:Jobs")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("000000000000.jobs.iot.us-east-1.amazonaws.com"));
    }
}

package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AccelerateIntegrationTest {

    private static final String BUCKET = "accelerate-int-test";

    @Test
    @Order(1)
    void createBucket() {
        given().when().put("/" + BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void getBeforePutHasNoStatus() {
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("AccelerateConfiguration"))
            .body(not(containsString("<Status>")));
    }

    @Test
    @Order(3)
    void putEnabledThenGet() {
        given()
            .body("""
                    <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Enabled</Status>
                    </AccelerateConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Enabled</Status>"));
    }

    @Test
    @Order(4)
    void putSuspendedThenGet() {
        given()
            .body("""
                    <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Suspended</Status>
                    </AccelerateConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Suspended</Status>"));
    }

    @Test
    @Order(5)
    void deleteBucket() {
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}

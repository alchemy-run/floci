package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3BucketTaggingIntegrationTest {

    private static final String BUCKET = "tagging-nosuchtagset-int-test";

    @Test
    @Order(1)
    void createBucket() {
        given().when().put("/" + BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void getBeforePutReturnsNoSuchTagSet() {
        given()
        .when()
            .get("/" + BUCKET + "?tagging")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchTagSet"));
    }

    @Test
    @Order(3)
    void putThenGet() {
        given()
            .body("""
                    <Tagging xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <TagSet>
                            <Tag><Key>Environment</Key><Value>test</Value></Tag>
                        </TagSet>
                    </Tagging>
                    """)
        .when()
            .put("/" + BUCKET + "?tagging")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("Environment"))
            .body(containsString("test"));
    }

    @Test
    @Order(4)
    void deleteThenGetReturnsNoSuchTagSet() {
        given()
        .when()
            .delete("/" + BUCKET + "?tagging")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?tagging")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchTagSet"));
    }

    @Test
    @Order(5)
    void deleteBucket() {
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}

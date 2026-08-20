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
class S3IntelligentTieringIntegrationTest {

    private static final String BUCKET = "int-tiering-int-test";
    private static final String CONFIG = """
            <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Id>archive</Id>
                <Status>Enabled</Status>
                <Tiering>
                    <Days>90</Days>
                    <AccessTier>ARCHIVE_ACCESS</AccessTier>
                </Tiering>
            </IntelligentTieringConfiguration>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given().when().put("/" + BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void getMissingReturnsNoSuchConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=archive")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    @Test
    @Order(3)
    void putThenGet() {
        given()
            .body(CONFIG)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=archive")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Enabled</Status>"))
            .body(containsString("ARCHIVE_ACCESS"));
    }

    @Test
    @Order(4)
    void listReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(200)
            .body(containsString("ListBucketIntelligentTieringConfigurationsOutput"))
            .body(containsString("<Id>archive</Id>"));
    }

    @Test
    @Order(5)
    void deleteThenGone() {
        given()
        .when()
            .delete("/" + BUCKET + "?intelligent-tiering&id=archive")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=archive")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    @Test
    @Order(6)
    void deleteBucket() {
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}

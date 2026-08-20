package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class S3RestoreObjectIntegrationTest {

    @Test
    void restoreOfStandardObjectReturnsInvalidObjectState() {
        String bucket = "restore-standard-bucket";
        given().when().put("/" + bucket).then().statusCode(200);
        given()
            .contentType("text/plain")
            .body("not archived")
        .when()
            .put("/" + bucket + "/std.txt")
        .then()
            .statusCode(200);

        given()
            .body("""
                    <RestoreRequest xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <Days>1</Days>
                      <GlacierJobParameters><Tier>Standard</Tier></GlacierJobParameters>
                    </RestoreRequest>
                    """)
        .when()
            .post("/" + bucket + "/std.txt?restore")
        .then()
            .statusCode(403)
            .body(containsString("InvalidObjectState"));

        given().when().delete("/" + bucket + "/std.txt").then().statusCode(204);
        given().when().delete("/" + bucket).then().statusCode(204);
    }
}

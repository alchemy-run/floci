package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class S3CopyReplaceContentTypeIntegrationTest {

    @Test
    void copyReplaceApplicationOctetStreamBecomesBinaryOctetStream() {
        String bucket = "copy-replace-ct-bucket";
        given().when().put("/" + bucket).then().statusCode(200);
        given()
            .contentType("text/plain")
            .body("Content")
        .when()
            .put("/" + bucket + "/source.txt")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-copy-source", "/" + bucket + "/source.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .contentType("application/octet-stream")
        .when()
            .put("/" + bucket + "/destination.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .head("/" + bucket + "/destination.txt")
        .then()
            .statusCode(200)
            .header("Content-Type", equalTo("binary/octet-stream"));

        given().when().delete("/" + bucket + "/source.txt").then().statusCode(204);
        given().when().delete("/" + bucket + "/destination.txt").then().statusCode(204);
        given().when().delete("/" + bucket).then().statusCode(204);
    }
}

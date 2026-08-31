package io.github.hectorvent.floci.services.geomaps;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

/**
 * Wire-format coverage for Amazon Location Maps v2 (geo-maps restJson1).
 * Mirrors the Alchemy GeoMaps binding suite: GetStaticMap, GetTile,
 * GetStyleDescriptor, GetSprites, GetGlyphs.
 */
@QuarkusTest
class GeoMapsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/geo-maps/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getStaticMap_returnsImageAndPricingBucket() {
        byte[] body = given()
                .header("Authorization", AUTH)
                .queryParam("center", "-122.3493,47.6205")
                .queryParam("zoom", "12")
                .queryParam("width", "400")
                .queryParam("height", "300")
        .when()
                .get("/static/map")
        .then()
                .statusCode(200)
                .header("Content-Type", containsString("image/png"))
                .header("x-amz-geo-pricing-bucket", not(emptyOrNullString()))
                .extract()
                .asByteArray();
        assertThat(body.length, greaterThan(0));
        assertThat(body[0] & 0xff, equalTo(0x89));
    }

    @Test
    void getStaticMap_v2Prefix_returnsImage() {
        given()
                .header("Authorization", AUTH)
                .queryParam("center", "-122.3493,47.6205")
                .queryParam("zoom", "12")
                .queryParam("width", "400")
                .queryParam("height", "300")
        .when()
                .get("/v2/static/map")
        .then()
                .statusCode(200)
                .header("Content-Type", containsString("image/png"))
                .header("x-amz-geo-pricing-bucket", equalTo("Maps"));
    }

    @Test
    void getStaticMap_missingHeight_returnsValidationException() {
        given()
                .header("Authorization", AUTH)
                .queryParam("center", "-122.3493,47.6205")
                .queryParam("zoom", "12")
                .queryParam("width", "400")
        .when()
                .get("/static/map")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getTile_returnsPayloadAndPricingBucket() {
        byte[] body = given()
                .header("Authorization", AUTH)
        .when()
                .get("/tiles/vector.basemap/0/0/0")
        .then()
                .statusCode(200)
                .header("x-amz-geo-pricing-bucket", not(emptyOrNullString()))
                .extract()
                .asByteArray();
        assertThat(body.length, greaterThan(0));
    }

    @Test
    void getStyleDescriptor_returnsMapLibreVersion8() {
        given()
                .header("Authorization", AUTH)
        .when()
                .get("/styles/Standard/descriptor")
        .then()
                .statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body("version", equalTo(8));
    }

    @Test
    void getSprites_returnsPngSheet() {
        byte[] body = given()
                .header("Authorization", AUTH)
        .when()
                .get("/styles/Standard/Light/Default/sprites/sprites.png")
        .then()
                .statusCode(200)
                .header("Content-Type", containsString("image/"))
                .extract()
                .asByteArray();
        assertThat(body.length, greaterThan(0));
    }

    @Test
    void getGlyphs_returnsPbfRange() {
        byte[] body = given()
                .header("Authorization", AUTH)
        .when()
                .get("/glyphs/Amazon Ember Regular/0-255.pbf")
        .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertThat(body.length, greaterThan(0));
    }
}

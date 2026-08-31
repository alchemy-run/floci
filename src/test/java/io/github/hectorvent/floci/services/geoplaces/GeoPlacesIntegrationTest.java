package io.github.hectorvent.floci.services.geoplaces;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

/**
 * REST JSON coverage for Amazon Location Places v2 against the canned catalog.
 */
@QuarkusTest
class GeoPlacesIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/geo-places/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void autocomplete_completesPartialAddress() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"QueryText\":\"1600 Pennsylvania\",\"MaxResults\":5}")
        .when()
                .post("/v2/autocomplete")
        .then()
                .statusCode(200)
                .header("x-amz-geo-pricing-bucket", equalTo("0"))
                .body("ResultItems", hasSize(greaterThan(0)))
                .body("ResultItems[0].Title", not(emptyOrNullString()))
                .body("ResultItems[0].PlaceId", not(emptyOrNullString()));
    }

    @Test
    void geocode_returnsCoordinates() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"QueryText\":\"1600 Pennsylvania Ave NW, Washington, DC\",\"MaxResults\":1}")
        .when()
                .post("/v2/geocode")
        .then()
                .statusCode(200)
                .header("x-amz-geo-pricing-bucket", equalTo("0"))
                .body("ResultItems", hasSize(greaterThan(0)))
                .body("ResultItems[0].Position", hasSize(2))
                .body("ResultItems[0].PlaceId", not(emptyOrNullString()));
    }

    @Test
    void getPlace_returnsDetailsForGeocodedId() {
        String placeId = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"QueryText\":\"Space Needle, Seattle, WA\",\"MaxResults\":1}")
        .when()
                .post("/v2/geocode")
        .then()
                .statusCode(200)
                .extract().path("ResultItems[0].PlaceId");

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/v2/place/" + placeId)
        .then()
                .statusCode(200)
                .header("x-amz-geo-pricing-bucket", equalTo("0"))
                .body("PlaceId", equalTo(placeId))
                .body("Address.Label", not(emptyOrNullString()))
                .body("PricingBucket", equalTo("0"));
    }

    @Test
    void reverseGeocode_returnsAddressForCoordinates() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"QueryPosition\":[-122.3493,47.6205],\"MaxResults\":1}")
        .when()
                .post("/v2/reverse-geocode")
        .then()
                .statusCode(200)
                .body("ResultItems", hasSize(greaterThan(0)))
                .body("ResultItems[0].Address.Label", not(emptyOrNullString()));
    }

    @Test
    void searchNearby_findsPlacesAroundPosition() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"QueryPosition\":[-122.3493,47.6205],\"QueryRadius\":1000,\"MaxResults\":5}")
        .when()
                .post("/v2/search-nearby")
        .then()
                .statusCode(200)
                .header("x-amz-geo-pricing-bucket", equalTo("0"))
                .body("ResultItems", hasSize(greaterThan(0)))
                .body("ResultItems[0].Title", not(emptyOrNullString()))
                .body("PricingBucket", equalTo("0"));
    }

    @Test
    void searchText_returnsRankedResults() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"QueryText\":\"Space Needle, Seattle, WA\",\"MaxResults\":5,\"BiasPosition\":[-122.3493,47.6205]}")
        .when()
                .post("/v2/search-text")
        .then()
                .statusCode(200)
                .body("ResultItems", hasSize(greaterThan(0)))
                .body("ResultItems[0].Position", hasSize(2));
    }

    @Test
    void suggest_returnsPlaceSuggestions() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"QueryText\":\"coffee\",\"BiasPosition\":[-122.3493,47.6205],\"MaxResults\":5}")
        .when()
                .post("/v2/suggest")
        .then()
                .statusCode(200)
                .body("ResultItems", hasSize(greaterThan(0)))
                .body("ResultItems[0].Title", not(emptyOrNullString()))
                .body("ResultItems[0].SuggestResultItemType", equalTo("Place"));
    }

    @Test
    void autocomplete_missingQueryText_isValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/v2/autocomplete")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }
}

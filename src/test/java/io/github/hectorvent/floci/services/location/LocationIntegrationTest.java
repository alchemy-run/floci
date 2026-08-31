package io.github.hectorvent.floci.services.location;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies Amazon Location Service v1 restJson1 operations used by Alchemy
 * {@code Location.test.ts}: Describe of an unknown map is a typed
 * ResourceNotFoundException, and create/update/tag/delete converge for maps,
 * place indexes, trackers, geofence collections, and API keys.
 */
@QuarkusTest
class LocationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000601";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeMapOnANonexistentMapFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/maps/v0/maps/alchemy-nonexistent-location-map")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void mapCreateUpdateTagAndDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String name = "lifecycle-map";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "MapName":"lifecycle-map",
                          "Configuration":{"Style":"VectorEsriNavigation"},
                          "Tags":{"Environment":"test","alchemy::id":"TestMap"}
                        }
                        """)
                .when()
                .post("/maps/v0/maps")
                .then()
                .statusCode(200)
                .body("MapName", equalTo(name))
                .body("MapArn", containsString(":map/"))
                .body("CreateTime", notNullValue())
                .extract()
                .path("MapArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/maps/v0/maps/" + name)
                .then()
                .statusCode(200)
                .body("MapName", equalTo(name))
                .body("MapArn", equalTo(arn))
                .body("DataSource", equalTo("Esri"))
                .body("Configuration.Style", equalTo("VectorEsriNavigation"))
                .body("Tags.Environment", equalTo("test"))
                .body("Tags.'alchemy::id'", equalTo("TestMap"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Description\":\"updated map\"}")
                .when()
                .patch("/maps/v0/maps/" + name)
                .then()
                .statusCode(200)
                .body("MapName", equalTo(name))
                .body("MapArn", equalTo(arn));

        String encodedArn = URLEncoder.encode(arn, StandardCharsets.UTF_8);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"Environment\":\"prod\"}}")
                .when()
                .post("/tags/" + encodedArn)
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/maps/v0/maps/" + name)
                .then()
                .statusCode(200)
                .body("Description", equalTo("updated map"))
                .body("Tags.Environment", equalTo("prod"))
                .body("Tags.'alchemy::id'", equalTo("TestMap"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/maps/v0/maps/" + name)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/maps/v0/maps/" + name)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void trackerConsumerAndApiKeyLifecycle() {
        String authorization = auth("000000000602", EAST);

        String trackerArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TrackerName\":\"consumer-tracker\"}")
                .when()
                .post("/tracking/v0/trackers")
                .then()
                .statusCode(200)
                .body("TrackerArn", containsString(":tracker/"))
                .extract()
                .path("TrackerArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/tracking/v0/trackers/consumer-tracker")
                .then()
                .statusCode(200)
                .body("PositionFiltering", equalTo("TimeBased"))
                .body("TrackerArn", equalTo(trackerArn));

        String collectionArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CollectionName\":\"consumer-fences\"}")
                .when()
                .post("/geofencing/v0/collections")
                .then()
                .statusCode(200)
                .body("CollectionArn", containsString(":geofence-collection/"))
                .extract()
                .path("CollectionArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ConsumerArn\":\"" + collectionArn + "\"}")
                .when()
                .post("/tracking/v0/trackers/consumer-tracker/consumers")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/tracking/v0/trackers/consumer-tracker/list-consumers")
                .then()
                .statusCode(200)
                .body("ConsumerArns", hasItem(collectionArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "KeyName":"alchemy-apikey-probe",
                          "NoExpiry":true,
                          "Restrictions":{
                            "AllowActions":["geo:GetMap*"],
                            "AllowResources":["arn:aws:geo:*:*:map/*"]
                          }
                        }
                        """)
                .when()
                .post("/metadata/v0/keys")
                .then()
                .statusCode(200)
                .body("Key", startsWith("v1.public."))
                .body("KeyArn", containsString(":api-key/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/metadata/v0/keys/alchemy-apikey-probe?forceDelete=true")
                .then()
                .statusCode(200);
    }

    @Test
    void trackerDevicePositionDataPlane() {
        String authorization = auth("000000000603", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TrackerName\":\"bindings-tracker\"}")
        .when()
                .post("/tracking/v0/trackers")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Updates\":[{\"DeviceId\":\"device-1\",\"Position\":[-122.3493,47.6205],\"SampleTime\":\"2026-01-01T00:00:00Z\"}]}")
        .when()
                .post("/tracking/v0/trackers/bindings-tracker/positions")
        .then()
                .statusCode(200)
                .body("Errors", hasSize(0));

        given()
                .header("Authorization", authorization)
        .when()
                .get("/tracking/v0/trackers/bindings-tracker/devices/device-1/positions/latest")
        .then()
                .statusCode(200)
                .body("DeviceId", equalTo("device-1"))
                .body("Position", hasSize(2));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DeviceIds\":[\"device-1\"]}")
        .when()
                .post("/tracking/v0/trackers/bindings-tracker/get-positions")
        .then()
                .statusCode(200)
                .body("DevicePositions", hasSize(1))
                .body("Errors", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
        .when()
                .post("/tracking/v0/trackers/bindings-tracker/devices/device-1/list-positions")
        .then()
                .statusCode(200)
                .body("DevicePositions", hasSize(greaterThan(0)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
        .when()
                .post("/tracking/v0/trackers/bindings-tracker/list-positions")
        .then()
                .statusCode(200)
                .body("Entries.DeviceId", hasItem("device-1"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DeviceState\":{\"DeviceId\":\"device-1\",\"SampleTime\":\"2026-01-01T00:00:00Z\",\"Position\":[-122.3493,47.6205],\"WiFiAccessPoints\":[{\"MacAddress\":\"A0:EC:F9:1E:32:C1\",\"Rss\":-66}]}}")
        .when()
                .post("/tracking/v0/trackers/bindings-tracker/positions/verify")
        .then()
                .statusCode(200)
                .body("InferredState.ProxyDetected", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DeviceIds\":[\"device-1\"]}")
        .when()
                .post("/tracking/v0/trackers/bindings-tracker/delete-positions")
        .then()
                .statusCode(200)
                .body("Errors", hasSize(0));
    }

    @Test
    void geofenceDataPlane() {
        String authorization = auth("000000000604", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CollectionName\":\"bindings-fences\"}")
        .when()
                .post("/geofencing/v0/collections")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Geometry\":{\"Circle\":{\"Center\":[-122.3493,47.6205],\"Radius\":200}}}")
        .when()
                .put("/geofencing/v0/collections/bindings-fences/geofences/fence-1")
        .then()
                .statusCode(200)
                .body("GeofenceId", equalTo("fence-1"));

        given()
                .header("Authorization", authorization)
        .when()
                .get("/geofencing/v0/collections/bindings-fences/geofences/fence-1")
        .then()
                .statusCode(200)
                .body("Status", not(emptyOrNullString()));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
        .when()
                .post("/geofencing/v0/collections/bindings-fences/list-geofences")
        .then()
                .statusCode(200)
                .body("Entries", hasSize(greaterThan(0)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Entries\":[{\"GeofenceId\":\"fence-2\",\"Geometry\":{\"Circle\":{\"Center\":[-122.3421,47.6091],\"Radius\":100}}}]}")
        .when()
                .post("/geofencing/v0/collections/bindings-fences/put-geofences")
        .then()
                .statusCode(200)
                .body("Successes", hasSize(1))
                .body("Errors", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DevicePositionUpdates\":[{\"DeviceId\":\"device-1\",\"Position\":[-122.3493,47.6205],\"SampleTime\":\"2026-01-01T00:00:00Z\"}]}")
        .when()
                .post("/geofencing/v0/collections/bindings-fences/positions")
        .then()
                .statusCode(200)
                .body("Errors", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DeviceState\":{\"Position\":[-122.3493,47.6205],\"Speed\":15},\"TimeHorizonMinutes\":30}")
        .when()
                .post("/geofencing/v0/collections/bindings-fences/forecast-geofence-events")
        .then()
                .statusCode(200)
                .body("ForecastedEvents", hasSize(greaterThanOrEqualTo(0)))
                .body("DistanceUnit", not(emptyOrNullString()));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GeofenceIds\":[\"fence-2\"]}")
        .when()
                .post("/geofencing/v0/collections/bindings-fences/delete-geofences")
        .then()
                .statusCode(200)
                .body("Errors", hasSize(0));
    }

    @Test
    void placesRoutesMapsAndJobsDataPlane() {
        String authorization = auth("000000000605", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"IndexName\":\"bindings-places\",\"DataSource\":\"Here\"}")
        .when()
                .post("/places/v0/indexes")
        .then()
                .statusCode(200);

        String placeId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Text\":\"Space Needle, Seattle, WA\",\"BiasPosition\":[-122.3493,47.6205],\"MaxResults\":3}")
        .when()
                .post("/places/v0/indexes/bindings-places/search/text")
        .then()
                .statusCode(200)
                .body("Results", hasSize(greaterThan(0)))
                .body("Results[0].Place.Label", not(emptyOrNullString()))
                .extract().path("Results[0].PlaceId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Position\":[-122.3493,47.6205],\"MaxResults\":1}")
        .when()
                .post("/places/v0/indexes/bindings-places/search/position")
        .then()
                .statusCode(200)
                .body("Results", hasSize(greaterThan(0)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Text\":\"coffee\",\"BiasPosition\":[-122.3493,47.6205],\"MaxResults\":5}")
        .when()
                .post("/places/v0/indexes/bindings-places/search/suggestions")
        .then()
                .statusCode(200)
                .body("Results", hasSize(greaterThan(0)))
                .body("Results[0].Text", not(emptyOrNullString()));

        given()
                .header("Authorization", authorization)
        .when()
                .get("/places/v0/indexes/bindings-places/places/" + placeId)
        .then()
                .statusCode(200)
                .body("Place.Label", not(emptyOrNullString()))
                .body("Place.Geometry.Point", hasSize(2));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CalculatorName\":\"bindings-routes\",\"DataSource\":\"Esri\"}")
        .when()
                .post("/routes/v0/calculators")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DeparturePosition\":[-122.3493,47.6205],\"DestinationPosition\":[-122.3421,47.6091]}")
        .when()
                .post("/routes/v0/calculators/bindings-routes/calculate/route")
        .then()
                .statusCode(200)
                .body("Summary.Distance", greaterThan(0f))
                .body("Summary.DurationSeconds", greaterThan(0f));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DeparturePositions\":[[-122.3493,47.6205]],\"DestinationPositions\":[[-122.3421,47.6091]]}")
        .when()
                .post("/routes/v0/calculators/bindings-routes/calculate/route-matrix")
        .then()
                .statusCode(200)
                .body("RouteMatrix", hasSize(1))
                .body("RouteMatrix[0][0].Distance", greaterThan(0f));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MapName\":\"bindings-map\",\"Configuration\":{\"Style\":\"VectorEsriNavigation\"}}")
        .when()
                .post("/maps/v0/maps")
        .then()
                .statusCode(200);

        byte[] style = given()
                .header("Authorization", authorization)
        .when()
                .get("/maps/v0/maps/bindings-map/style-descriptor")
        .then()
                .statusCode(200)
                .extract().asByteArray();
        assertThat(style.length, greaterThan(0));

        byte[] glyphs = given()
                .header("Authorization", authorization)
        .when()
                .get("/maps/v0/maps/bindings-map/glyphs/Arial%20Regular/0-255.pbf")
        .then()
                .statusCode(200)
                .extract().asByteArray();
        assertThat(glyphs.length, greaterThan(0));

        byte[] sprites = given()
                .header("Authorization", authorization)
        .when()
                .get("/maps/v0/maps/bindings-map/sprites/sprites.json")
        .then()
                .statusCode(200)
                .extract().asByteArray();
        assertThat(sprites.length, greaterThan(0));

        byte[] tile = given()
                .header("Authorization", authorization)
        .when()
                .get("/maps/v0/maps/bindings-map/tiles/0/0/0")
        .then()
                .statusCode(200)
                .extract().asByteArray();
        assertThat(tile.length, greaterThan(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
        .when()
                .post("/metadata/v0/jobs/list-jobs")
        .then()
                .statusCode(200)
                .body("Entries", hasSize(greaterThanOrEqualTo(0)));

        given()
                .header("Authorization", authorization)
        .when()
                .get("/metadata/v0/jobs/00000000-0000-4000-8000-000000000000")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"JobId\":\"00000000-0000-4000-8000-000000000000\"}")
        .when()
                .post("/metadata/v0/jobs/cancel-job")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/geo/aws4_request";
    }
}

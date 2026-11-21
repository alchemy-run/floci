package io.floci.aws.services.location;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Amazon Location Service and the Geo Maps, Places and Routes APIs. */
@QuarkusTest
class LocationIntegrationTest {
    private static final String SEATTLE = "[-122.3493,47.6205]";
    private static final String PIKE_PLACE = "[-122.3421,47.6091]";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String service, String method, String path, String body) {
        RequestSpecification request = given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/" + service + "/aws4_request")
                .contentType("application/json");
        if (body != null) {
            request = request.body(body);
        }
        return request.request(method, path).then();
    }

    private static ValidatableResponse geo(String method, String path, String body) {
        return call("geo", method, path, body);
    }

    private static String name(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void resourcesAreCreatedDescribedUpdatedTaggedListedAndDeleted() {
        String map = name("map");
        String arn = geo("POST", "/maps/v0/maps", "{\"MapName\":\"" + map
                + "\",\"Configuration\":{\"Style\":\"VectorEsriNavigation\"},\"Tags\":{\"a\":\"1\"}}")
                .statusCode(200).extract().path("MapArn");
        geo("GET", "/maps/v0/maps/" + map, null).statusCode(200)
                .body("DataSource", equalTo("Esri")).body("Tags.a", equalTo("1"))
                .body("Configuration.Style", equalTo("VectorEsriNavigation"));
        geo("PATCH", "/maps/v0/maps/" + map, "{\"Description\":\"updated\"}").statusCode(200);
        geo("GET", "/maps/v0/maps/" + map, null).statusCode(200).body("Description", equalTo("updated"));
        geo("POST", "/tags/" + arn, "{\"Tags\":{\"b\":\"2\"}}").statusCode(200);
        geo("GET", "/tags/" + arn, null).statusCode(200).body("Tags.b", equalTo("2")).body("Tags.a", equalTo("1"));
        geo("POST", "/maps/v0/list-maps", "{}").statusCode(200).body("Entries.MapName", hasItem(map));
        geo("POST", "/maps/v0/maps", "{\"MapName\":\"" + map + "\",\"Configuration\":{\"Style\":\"VectorEsriNavigation\"}}")
                .statusCode(409);
        geo("POST", "/maps/v0/maps", "{\"MapName\":\"bad-style\",\"Configuration\":{\"Style\":\"Nope\"}}")
                .statusCode(400);
        geo("DELETE", "/maps/v0/maps/" + map, null).statusCode(200);
        geo("GET", "/maps/v0/maps/" + map, null).statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        String key = name("key");
        geo("POST", "/metadata/v0/keys", "{\"KeyName\":\"" + key + "\",\"NoExpiry\":true,\"Restrictions\":"
                + "{\"AllowActions\":[\"geo:GetMap*\"],\"AllowResources\":[\"arn:aws:geo:*:*:map/*\"]}}")
                .statusCode(200).body("Key", startsWith("v1.public.")).body("KeyArn", containsString(":api-key/"));
        geo("POST", "/metadata/v0/list-keys", "{\"Filter\":{\"KeyStatus\":\"Active\"}}").statusCode(200)
                .body("Entries.KeyName", hasItem(key));
        geo("DELETE", "/metadata/v0/keys/" + key, null).statusCode(200);
    }

    @Test
    void geofencesAreEvaluatedAndForecast() {
        String collection = name("fences");
        geo("POST", "/geofencing/v0/collections", "{\"CollectionName\":\"" + collection + "\"}").statusCode(200);
        geo("PUT", "/geofencing/v0/collections/" + collection + "/geofences/needle",
                "{\"Geometry\":{\"Circle\":{\"Center\":" + SEATTLE + ",\"Radius\":200}}}").statusCode(200);
        geo("POST", "/geofencing/v0/collections/" + collection + "/put-geofences",
                "{\"Entries\":[{\"GeofenceId\":\"square\",\"Geometry\":{\"Polygon\":[[[-122.35,47.60],[-122.34,47.60],"
                        + "[-122.34,47.61],[-122.35,47.61],[-122.35,47.60]]]}},{\"GeofenceId\":\"open\",\"Geometry\":"
                        + "{\"Polygon\":[[[0,0],[1,0],[1,1],[0,1]]]}}]}")
                .statusCode(200).body("Successes.GeofenceId", contains("square"))
                .body("Errors[0].GeofenceId", equalTo("open")).body("Errors[0].Error.Code", equalTo("ValidationError"));
        geo("GET", "/geofencing/v0/collections/" + collection, null).statusCode(200).body("GeofenceCount", equalTo(2));
        geo("POST", "/geofencing/v0/collections/" + collection + "/list-geofences", "{}").statusCode(200)
                .body("Entries.GeofenceId", contains("needle", "square"));
        geo("POST", "/geofencing/v0/collections/" + collection + "/positions",
                "{\"DevicePositionUpdates\":[{\"DeviceId\":\"d1\",\"Position\":" + SEATTLE + ",\"SampleTime\":\""
                        + Instant.now() + "\"}]}").statusCode(200).body("Errors", empty());
        geo("POST", "/geofencing/v0/collections/" + collection + "/forecast-geofence-events",
                "{\"DeviceState\":{\"Position\":" + SEATTLE + ",\"Speed\":15},\"TimeHorizonMinutes\":10}")
                .statusCode(200).body("ForecastedEvents.find { it.GeofenceId == 'needle' }.EventType", equalTo("EXIT"))
                .body("ForecastedEvents.find { it.GeofenceId == 'needle' }.IsDeviceInGeofence", equalTo(true))
                .body("ForecastedEvents.find { it.GeofenceId == 'square' }.EventType", equalTo("ENTER"));
        geo("POST", "/geofencing/v0/collections/" + collection + "/delete-geofences", "{\"GeofenceIds\":[\"square\"]}")
                .statusCode(200).body("Errors", empty());
        geo("GET", "/geofencing/v0/collections/" + collection + "/geofences/square", null).statusCode(404);
        geo("DELETE", "/geofencing/v0/collections/" + collection, null).statusCode(200);
    }

    @Test
    void trackersFilterAndKeepPositionHistory() {
        String tracker = name("tracker");
        geo("POST", "/tracking/v0/trackers", "{\"TrackerName\":\"" + tracker + "\"}").statusCode(200);
        Instant now = Instant.now();
        String update = "{\"Updates\":[{\"DeviceId\":\"d1\",\"Position\":%s,\"SampleTime\":\"%s\"}]}";
        geo("POST", "/tracking/v0/trackers/" + tracker + "/positions", update.formatted(SEATTLE, now.minusSeconds(120)))
                .statusCode(200).body("Errors", empty());
        // Within 30 seconds of the last stored update: TimeBased filtering drops it.
        geo("POST", "/tracking/v0/trackers/" + tracker + "/positions",
                update.formatted(PIKE_PLACE, now.minusSeconds(110))).statusCode(200);
        geo("POST", "/tracking/v0/trackers/" + tracker + "/positions", update.formatted(PIKE_PLACE, now))
                .statusCode(200);
        geo("POST", "/tracking/v0/trackers/" + tracker + "/devices/d1/list-positions", "{}").statusCode(200)
                .body("DevicePositions", hasSize(2));
        geo("GET", "/tracking/v0/trackers/" + tracker + "/devices/d1/positions/latest", null).statusCode(200)
                .body("Position[0]", equalTo(-122.3421f));
        geo("POST", "/tracking/v0/trackers/" + tracker + "/get-positions", "{\"DeviceIds\":[\"d1\",\"ghost\"]}")
                .statusCode(200).body("DevicePositions", hasSize(1))
                .body("Errors[0].Error.Code", equalTo("ResourceNotFoundError"));
        geo("POST", "/tracking/v0/trackers/" + tracker + "/list-positions", "{}").statusCode(200)
                .body("Entries.DeviceId", contains("d1"));
        geo("POST", "/tracking/v0/trackers/" + tracker + "/positions/verify", "{\"DeviceState\":{\"DeviceId\":\"d1\","
                + "\"Position\":" + SEATTLE + ",\"SampleTime\":\"" + now + "\"}}").statusCode(200)
                .body("InferredState.ProxyDetected", equalTo(false));
        geo("POST", "/tracking/v0/trackers/" + tracker + "/delete-positions", "{\"DeviceIds\":[\"d1\"]}")
                .statusCode(200);
        geo("GET", "/tracking/v0/trackers/" + tracker + "/devices/d1/positions/latest", null).statusCode(404);
        geo("DELETE", "/tracking/v0/trackers/" + tracker, null).statusCode(200);
    }

    @Test
    void placeIndexesSearchTheGazetteer() {
        String here = name("here");
        String esri = name("esri");
        geo("POST", "/places/v0/indexes", "{\"IndexName\":\"" + here + "\",\"DataSource\":\"Here\"}").statusCode(200);
        geo("POST", "/places/v0/indexes", "{\"IndexName\":\"" + esri + "\",\"DataSource\":\"Esri\"}").statusCode(200);
        String placeId = geo("POST", "/places/v0/indexes/" + here + "/search/text",
                "{\"Text\":\"Space Needle, Seattle, WA\",\"BiasPosition\":" + SEATTLE + "}").statusCode(200)
                .body("Results[0].Place.Label", startsWith("Space Needle, 400 Broad St, Seattle"))
                .body("Summary.DataSource", equalTo("Here"))
                .extract().path("Results[0].PlaceId");
        geo("POST", "/places/v0/indexes/" + esri + "/search/text", "{\"Text\":\"Space Needle\"}").statusCode(200)
                .body("Results[0].PlaceId", nullValue());
        geo("GET", "/places/v0/indexes/" + here + "/places/" + placeId, null).statusCode(200)
                .body("Place.Municipality", equalTo("Seattle"));
        geo("POST", "/places/v0/indexes/" + here + "/search/position", "{\"Position\":" + SEATTLE + "}")
                .statusCode(200).body("Results[0].Place.Label", startsWith("Space Needle"));
        geo("POST", "/places/v0/indexes/" + here + "/search/suggestions",
                "{\"Text\":\"coffee\",\"BiasPosition\":" + SEATTLE + "}").statusCode(200)
                .body("Results.size()", greaterThan(0));
    }

    @Test
    void routeCalculatorsMeasureLegsAndMatrices() {
        String calculator = name("calc");
        geo("POST", "/routes/v0/calculators", "{\"CalculatorName\":\"" + calculator + "\",\"DataSource\":\"Esri\"}")
                .statusCode(200);
        geo("POST", "/routes/v0/calculators/" + calculator + "/calculate/route", "{\"DeparturePosition\":" + SEATTLE
                + ",\"DestinationPosition\":" + PIKE_PLACE + ",\"IncludeLegGeometry\":true}").statusCode(200)
                .body("Legs", hasSize(1)).body("Legs[0].Geometry.LineString.size()", greaterThan(1))
                .body("Summary.Distance", equalTo(1.722f)).body("Summary.DistanceUnit", equalTo("Kilometers"));
        geo("POST", "/routes/v0/calculators/" + calculator + "/calculate/route-matrix", "{\"DeparturePositions\":["
                + SEATTLE + "],\"DestinationPositions\":[" + PIKE_PLACE + "," + SEATTLE + "]}").statusCode(200)
                .body("RouteMatrix[0]", hasSize(2)).body("Summary.RouteCount", equalTo(2));
    }

    @Test
    void mapAssetsAreServed() {
        String map = name("map");
        geo("POST", "/maps/v0/maps", "{\"MapName\":\"" + map + "\",\"Configuration\":{\"Style\":\"VectorEsriNavigation\"}}")
                .statusCode(200);
        geo("GET", "/maps/v0/maps/" + map + "/style-descriptor", null).statusCode(200)
                .body("version", equalTo(8)).body("sources.floci.type", equalTo("vector"));
        byte[] tile = geo("GET", "/maps/v0/maps/" + map + "/tiles/0/0/0", null).statusCode(200)
                .contentType("application/vnd.mapbox-vector-tile").extract().asByteArray();
        assertTrue(tile.length > 0);
        geo("GET", "/maps/v0/maps/" + map + "/tiles/1/2/0", null).statusCode(400);
        geo("GET", "/maps/v0/maps/" + map + "/glyphs/Arial%20Regular/0-255", null).statusCode(200);
        byte[] png = call("geo-maps", "GET", "/v2/static/map?width=200&height=100&center=-122.3,47.6&zoom=10", null)
                .statusCode(200).contentType("image/png").extract().asByteArray();
        assertEquals((byte) 0x89, png[0]);
        call("geo-maps", "GET", "/v2/styles/Standard/descriptor", null).statusCode(200).body("version", equalTo(8));
        call("geo-maps", "GET", "/v2/tiles/vector.basemap/0/0/0", null).statusCode(200);
    }

    @Test
    void geoPlacesAndRoutesAnswerV2Requests() {
        call("geo-places", "POST", "/v2/geocode", "{\"QueryText\":\"1600 Pennsylvania Ave NW, Washington, DC\"}")
                .statusCode(200).header("x-amz-geo-pricing-bucket", notNullValue())
                .body("ResultItems[0].Address.Locality", equalTo("Washington"));
        call("geo-places", "POST", "/v2/autocomplete", "{\"QueryText\":\"1600 Pennsylvania\"}").statusCode(200)
                .body("ResultItems[0].Title", startsWith("White House"));
        call("geo-places", "POST", "/v2/reverse-geocode", "{\"QueryPosition\":" + SEATTLE + "}").statusCode(200)
                .body("ResultItems[0].Title", equalTo("Space Needle"));
        call("geo-places", "POST", "/v2/search-nearby", "{\"QueryPosition\":" + SEATTLE + ",\"QueryRadius\":1000,"
                + "\"Filter\":{\"IncludeCategories\":[\"museum\"]}}").statusCode(200)
                .body("ResultItems.Title", contains("Museum of Pop Culture"));
        call("geo-places", "POST", "/v2/search-text", "{\"QueryText\":\"Space Needle\"}").statusCode(400);
        call("geo-routes", "POST", "/v2/routes", "{\"Origin\":" + SEATTLE + ",\"Destination\":" + PIKE_PLACE
                + ",\"LegGeometryFormat\":\"Simple\"}").statusCode(200)
                .body("Routes[0].Legs[0].Geometry.LineString.size()", greaterThan(1))
                .body("Routes[0].Summary.Distance", greaterThan(1000));
        call("geo-routes", "POST", "/v2/isolines", "{\"Origin\":" + SEATTLE + ",\"Thresholds\":{\"Time\":[600]}}")
                .statusCode(200).body("Isolines[0].TimeThreshold", equalTo(600))
                .body("Isolines[0].Geometries[0].PolylinePolygon[0]", startsWith("B"));
        call("geo-routes", "POST", "/v2/optimize-waypoints", "{\"Origin\":" + SEATTLE + ",\"Waypoints\":["
                + "{\"Id\":\"far\",\"Position\":[-122.2,47.6]},{\"Id\":\"near\",\"Position\":" + PIKE_PLACE + "}]}")
                .statusCode(200).body("OptimizedWaypoints.Id", contains("Origin", "near", "far"));
        call("geo-routes", "POST", "/v2/snap-to-roads", "{\"TracePoints\":[{\"Position\":" + SEATTLE + "},"
                + "{\"Position\":" + PIKE_PLACE + "}]}").statusCode(200).body("SnappedTracePoints", hasSize(2));
    }

    @Test
    void jobsNeedAnExistingRoleAndUnknownJobsAreNotFound() {
        geo("POST", "/metadata/v0/jobs", "{\"Action\":\"ValidateAddress\",\"ExecutionRoleArn\":"
                + "\"arn:aws:iam::000000000000:role/missing\",\"InputOptions\":{\"Format\":\"Parquet\",\"Location\":"
                + "\"s3://floci-input/a.parquet\"},\"OutputOptions\":{\"Format\":\"Parquet\",\"Location\":\"s3://floci-output/\"}}")
                .statusCode(403);
        geo("GET", "/metadata/v0/jobs/00000000-0000-4000-8000-000000000000", null).statusCode(404);
        geo("POST", "/metadata/v0/jobs/cancel-job", "{\"JobId\":\"00000000-0000-4000-8000-000000000000\"}")
                .statusCode(404);
        geo("POST", "/metadata/v0/jobs/list-jobs", "{}").statusCode(200).body("Entries", notNullValue());
    }
}

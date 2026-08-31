package io.github.hectorvent.floci.services.georoutes;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies geo-routes restJson1 Calculate, Optimize, and Snap stubs against the Alchemy binding shapes. */
@QuarkusTest
class GeoRoutesIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/geo-routes/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void calculateRoutesReturnsARouteWithDistanceAndDuration() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "Origin": [-122.339, 47.61],
                          "Destination": [-122.201, 47.61],
                          "TravelMode": "Car"
                        }
                        """)
                .when()
                .post("/v2/routes")
                .then()
                .statusCode(200)
                .header(GeoRoutesService.PRICING_BUCKET_HEADER, GeoRoutesService.PRICING_BUCKET)
                .body("Routes", hasSize(greaterThan(0)))
                .body("Routes[0].Summary.Distance", greaterThan(0))
                .body("Routes[0].Summary.Duration", greaterThan(0))
                .body("Routes[0].Legs", hasSize(greaterThan(0)))
                .body("LegGeometryFormat", equalTo("Simple"));
    }

    @Test
    void calculateRoutesWithoutOriginFailsWithValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"Destination\":[-122.201,47.61]}")
                .when()
                .post("/v2/routes")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("Reason", equalTo("Missing"))
                .body("FieldList[0].Name", equalTo("Origin"));
    }

    @Test
    void calculateIsolinesReturnsADriveTimePolygon() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "Origin": [-122.339, 47.61],
                          "Thresholds": {"Time": [600]},
                          "TravelMode": "Car"
                        }
                        """)
                .when()
                .post("/v2/isolines")
                .then()
                .statusCode(200)
                .header(GeoRoutesService.PRICING_BUCKET_HEADER, GeoRoutesService.PRICING_BUCKET)
                .body("Isolines", hasSize(greaterThan(0)))
                .body("Isolines[0].Geometries", hasSize(greaterThan(0)))
                .body("Isolines[0].TimeThreshold", equalTo(600))
                .body("Isolines[0].Geometries[0].Polygon", notNullValue());
    }

    @Test
    void calculateRouteMatrixReturnsA2x2Grid() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "Origins": [
                            {"Position": [-122.339, 47.61]},
                            {"Position": [-122.335, 47.608]}
                          ],
                          "Destinations": [
                            {"Position": [-122.201, 47.61]},
                            {"Position": [-122.313, 47.62]}
                          ],
                          "RoutingBoundary": {
                            "Geometry": {
                              "Circle": {"Center": [-122.3, 47.61], "Radius": 30000}
                            }
                          }
                        }
                        """)
                .when()
                .post("/v2/route-matrix")
                .then()
                .statusCode(200)
                .header(GeoRoutesService.PRICING_BUCKET_HEADER, GeoRoutesService.PRICING_BUCKET)
                .body("ErrorCount", equalTo(0))
                .body("RouteMatrix", hasSize(2))
                .body("RouteMatrix[0]", hasSize(2))
                .body("RouteMatrix[0][0].Duration", greaterThan(0))
                .body("RoutingBoundary.Geometry.Circle.Radius", equalTo(30000));
    }

    @Test
    void optimizeWaypointsKeepsOriginStopsAndDestination() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "Origin": [-122.339, 47.61],
                          "Destination": [-122.201, 47.61],
                          "Waypoints": [
                            {"Id": "stop-1", "Position": [-122.335, 47.608]},
                            {"Id": "stop-2", "Position": [-122.313, 47.62]}
                          ]
                        }
                        """)
                .when()
                .post("/v2/optimize-waypoints")
                .then()
                .statusCode(200)
                .header(GeoRoutesService.PRICING_BUCKET_HEADER, GeoRoutesService.PRICING_BUCKET)
                .body("OptimizedWaypoints", hasSize(4))
                .body("OptimizedWaypoints.Id", hasItems("stop-1", "stop-2"))
                .body("Distance", greaterThan(0))
                .body("Duration", greaterThan(0))
                .body("TimeBreakdown.TravelDuration", greaterThan(0));
    }

    @Test
    void snapToRoadsReturnsAConfidenceForEachTracePoint() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "TracePoints": [
                            {"Position": [-122.339, 47.61]},
                            {"Position": [-122.337, 47.609]},
                            {"Position": [-122.335, 47.608]}
                          ],
                          "TravelMode": "Car"
                        }
                        """)
                .when()
                .post("/v2/snap-to-roads")
                .then()
                .statusCode(200)
                .header(GeoRoutesService.PRICING_BUCKET_HEADER, GeoRoutesService.PRICING_BUCKET)
                .body("SnappedTracePoints", hasSize(3))
                .body("SnappedTracePoints[0].Confidence", greaterThan(0f))
                .body("SnappedGeometryFormat", equalTo("Simple"));
    }
}

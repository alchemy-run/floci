package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayUsagePlanCrudIntegrationTest {

    @Test
    void testCreateAndRetrieveUsagePlan() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"before\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"after\"},"
                        + "{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));

        given()
                .get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("id", equalTo(usagePlanId))
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));
    }

    @Test
    void quotaAndThrottlePatchesRoundTripWithoutPartialUpdates() {
        String id = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"combined-plan","throttle":{"burstLimit":5,"rateLimit":2.5},
                         "quota":{"limit":20,"offset":1,"period":"DAY"}}
                        """)
                .post("/usageplans").then().statusCode(201)
                .body("throttle.burstLimit", equalTo(5))
                .body("quota.limit", equalTo(20))
                .extract().path("id");

        given().contentType(ContentType.JSON).body("""
                {"patchOperations":[
                  {"op":"replace","path":"/quota/limit","value":"30"},
                  {"op":"replace","path":"/throttle/burstLimit","value":"8"},
                  {"op":"replace","path":"/nope","value":"invalid"}]}
                """)
                .patch("/usageplans/" + id).then().statusCode(400);
        given().get("/usageplans/" + id).then().statusCode(200)
                .body("quota.limit", equalTo(20))
                .body("throttle.burstLimit", equalTo(5));

        given().contentType(ContentType.JSON).body("""
                {"patchOperations":[
                  {"op":"replace","path":"/quota/limit","value":"30"},
                  {"op":"replace","path":"/throttle/burstLimit","value":"8"},
                  {"op":"add","path":"/apiStages","value":"api:test"}]}
                """)
                .patch("/usageplans/" + id).then().statusCode(200)
                .body("quota.limit", equalTo(30))
                .body("throttle.burstLimit", equalTo(8))
                .body("apiStages[0].stage", equalTo("test"));
        given().delete("/usageplans/" + id).then().statusCode(202);
    }

    @Test
    void testUnsupportedPatchPathIsRejected() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"reject-path\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/nope\",\"value\":\"x\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400);
    }
}

package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayExecuteApiHostIntegrationTest {

    @Test
    void restApiHostHeaderRoutesToStageDataPlane() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"execute-host-rest\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET/responses/200")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET/integration")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"ok\\\":true}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(201);

        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"host\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);

        given()
                .header("Host", apiId + ".execute-api.us-east-1.amazonaws.com")
                .when().get("/test/")
                .then()
                .statusCode(200)
                .body("ok", equalTo(true));

        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void httpApiDefaultStageHostHeaderOmitsStageFromPath() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"execute-host-http\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"MOCK\",\"payloadFormatVersion\":\"1.0\"}")
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given().contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /echo\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"$default\",\"autoDeploy\":true}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201);

        // Host rewrite must land on the v2 execute-api dispatcher. MOCK has
        // no Lambda URI, so the handler answers 500 after the route matches;
        // a missed rewrite would 404 on the bare `/echo` path.
        given()
                .header("Host", apiId + ".execute-api.us-east-1.amazonaws.com")
                .when().get("/echo")
                .then()
                .statusCode(org.hamcrest.Matchers.not(404));

        given().when().delete("/v2/apis/" + apiId).then().statusCode(204);
    }
}

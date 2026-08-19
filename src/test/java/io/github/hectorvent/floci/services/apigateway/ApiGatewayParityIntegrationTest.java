package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ApiGatewayParityIntegrationTest {

    @Test
    void restApiBinaryMediaTypesRoundTrip() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-api\",\"binaryMediaTypes\":[\"application/octet-stream\"]}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("binaryMediaTypes", hasItem("application/octet-stream"))
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"add\",\"path\":\"/binaryMediaTypes/image~1png\",\"value\":\"image/png\"}]}")
                .when().patch("/restapis/" + apiId)
                .then()
                .statusCode(200)
                .body("binaryMediaTypes", hasItems("application/octet-stream", "image/png"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"remove\",\"path\":\"/binaryMediaTypes/application~1octet-stream\"}]}")
                .when().patch("/restapis/" + apiId)
                .then()
                .statusCode(200)
                .body("binaryMediaTypes", hasItem("image/png"));

        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void usagePlanGetUpdateAndThrottle() {
        String planId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-plan\",\"description\":\"first\",\"throttle\":{\"burstLimit\":10,\"rateLimit\":100}}")
                .when().post("/usageplans")
                .then()
                .statusCode(201)
                .body("throttle.burstLimit", equalTo(10))
                .body("throttle.rateLimit", equalTo(100.0f))
                .extract().path("id");

        given().when().get("/usageplans/" + planId)
                .then()
                .statusCode(200)
                .body("id", equalTo(planId))
                .body("description", equalTo("first"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/throttle/burstLimit\",\"value\":\"20\"},{\"op\":\"replace\",\"path\":\"/throttle/rateLimit\",\"value\":\"200\"}]}")
                .when().patch("/usageplans/" + planId)
                .then()
                .statusCode(200)
                .body("throttle.burstLimit", equalTo(20))
                .body("throttle.rateLimit", equalTo(200.0f));

        given().when().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.id", hasItem(planId));

        given().when().delete("/usageplans/" + planId).then().statusCode(202);
    }

    @Test
    void apiKeyUpdateAndDelete() {
        String keyId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-key\",\"enabled\":true}")
                .when().post("/apikeys")
                .then()
                .statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"parity-key-renamed\"},{\"op\":\"replace\",\"path\":\"/enabled\",\"value\":\"false\"}]}")
                .when().patch("/apikeys/" + keyId)
                .then()
                .statusCode(200)
                .body("name", equalTo("parity-key-renamed"))
                .body("enabled", equalTo(false));

        given().when().delete("/apikeys/" + keyId).then().statusCode(202);
        given().when().get("/apikeys/" + keyId).then().statusCode(404);
    }

    @Test
    void authorizerUpdateDeleteAndGatewayResponses() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-auth-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"token-auth\",\"type\":\"TOKEN\",\"authorizerUri\":\"arn:aws:apigateway:us-east-1:lambda:path/invocations\",\"identitySource\":\"method.request.header.Authorization\"}")
                .when().post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"token-auth-2\"}]}")
                .when().patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("name", equalTo("token-auth-2"));

        given().when().delete("/restapis/" + apiId + "/authorizers/" + authorizerId).then().statusCode(202);

        given().contentType(ContentType.JSON)
                .body("{\"statusCode\":\"404\",\"responseTemplates\":{\"application/json\":\"{\\\"message\\\":\\\"gone\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then()
                .statusCode(200)
                .body("responseType", equalTo("DEFAULT_4XX"))
                .body("statusCode", equalTo("404"))
                .body("defaultResponse", equalTo(false));

        given().when().get("/restapis/" + apiId + "/gatewayresponses")
                .then()
                .statusCode(200)
                .body("item.responseType", hasItem("DEFAULT_4XX"));

        given().when().delete("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX").then().statusCode(202);
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void vpcLinkAndDeploymentUpdate() {
        String vpcLinkId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-link\",\"description\":\"first\",\"targetArns\":[\"arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/demo/abc\"]}")
                .when().post("/vpclinks")
                .then()
                .statusCode(201)
                .body("status", equalTo("AVAILABLE"))
                .body("targetArns", hasItem("arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/demo/abc"))
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"updated\"}]}")
                .when().patch("/vpclinks/" + vpcLinkId)
                .then()
                .statusCode(200)
                .body("description", equalTo("updated"));

        given().when().delete("/vpclinks/" + vpcLinkId).then().statusCode(202);

        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-deploy-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"first deploy\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"second deploy\"}]}")
                .when().patch("/restapis/" + apiId + "/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("description", equalTo("second deploy"));

        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void v2ExportResetCorsAndDomainNames() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-http\",\"protocolType\":\"HTTP\",\"corsConfiguration\":{\"allowOrigins\":[\"*\"]}}")
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("corsConfiguration.allowOrigins", hasItem("*"))
                .extract().path("apiId");

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"HTTP_PROXY\",\"integrationUri\":\"https://example.com\",\"payloadFormatVersion\":\"1.0\"}")
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given().contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /ping\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201);

        given().when().get("/v2/apis/" + apiId + "/exports/OAS30")
                .then()
                .statusCode(200)
                .body("openapi", equalTo("3.0.1"))
                .body("paths.'/ping'.get.operationId", notNullValue());

        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"$default\",\"autoDeploy\":true}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201);

        given().when().delete("/v2/apis/" + apiId + "/stages/$default/cache/authorizers")
                .then()
                .statusCode(204);

        given().when().delete("/v2/apis/" + apiId + "/cors")
                .then()
                .statusCode(204);

        given().when().get("/v2/apis/" + apiId)
                .then()
                .statusCode(200)
                .body("corsConfiguration", org.hamcrest.Matchers.nullValue());

        given().contentType(ContentType.JSON)
                .body("{\"domainName\":\"parity.example.com\",\"domainNameConfigurations\":[{\"certificateArn\":\"arn:aws:acm:us-east-1:000000000000:certificate/abc\",\"endpointType\":\"REGIONAL\"}]}")
                .when().post("/v2/domainnames")
                .then()
                .statusCode(201)
                .body("domainName", equalTo("parity.example.com"))
                .body("domainNameConfigurations[0].domainNameStatus", equalTo("AVAILABLE"));

        given().when().get("/v2/domainnames")
                .then()
                .statusCode(200)
                .body("items.domainName", hasItem("parity.example.com"));

        given().when().delete("/v2/domainnames/parity.example.com").then().statusCode(204);
        given().when().delete("/v2/apis/" + apiId).then().statusCode(204);
    }

    @Test
    void getResourcesEmbedsMethodsAndUsageRoundTrip() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-embed-api\"}")
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

        given().when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .body("item[0].resourceMethods.GET", notNullValue());

        given().when().get("/restapis/" + apiId + "/resources?embed=methods")
                .then()
                .statusCode(200)
                .body("item[0].resourceMethods.GET.httpMethod", equalTo("GET"))
                .body("item[0].resourceMethods.GET.authorizationType", equalTo("NONE"));

        String planId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-usage-plan\"}")
                .when().post("/usageplans")
                .then()
                .statusCode(201)
                .extract().path("id");

        String keyId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-usage-key\",\"enabled\":true}")
                .when().post("/apikeys")
                .then()
                .statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"keyId\":\"" + keyId + "\",\"keyType\":\"API_KEY\"}")
                .when().post("/usageplans/" + planId + "/keys")
                .then()
                .statusCode(201);

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-08-19&endDate=2026-08-19")
                .then()
                .statusCode(200)
                .body("usagePlanId", equalTo(planId))
                .body("values", notNullValue());

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/remaining\",\"value\":\"100\"}]}")
                .when().patch("/usageplans/" + planId + "/keys/" + keyId + "/usage")
                .then()
                .statusCode(400);

        given().when().delete("/usageplans/" + planId).then().statusCode(202);
        given().when().delete("/apikeys/" + keyId).then().statusCode(202);
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void v2StagePersistsDescription() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"parity-stage-desc\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");

        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"$default\",\"autoDeploy\":true,\"description\":\"primitives\",\"tags\":{\"owner\":\"alchemy\"}}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201)
                .body("description", equalTo("primitives"))
                .body("tags.owner", equalTo("alchemy"));

        given().when().get("/v2/apis/" + apiId + "/stages/$default")
                .then()
                .statusCode(200)
                .body("autoDeploy", equalTo(true))
                .body("description", equalTo("primitives"))
                .body("tags.owner", equalTo("alchemy"));

        String stageArn = "arn:aws:apigateway:us-east-1::/apis/" + apiId + "/stages/$default";
        given().contentType(ContentType.JSON)
                .body("{\"tags\":{\"env\":\"test\"}}")
                .when().post("/v2/tags/" + stageArn)
                .then()
                .statusCode(201);

        given().when().get("/v2/tags/" + stageArn)
                .then()
                .statusCode(200)
                .body("tags.owner", equalTo("alchemy"))
                .body("tags.env", equalTo("test"));

        given().when().delete("/v2/apis/" + apiId).then().statusCode(204);
    }
}

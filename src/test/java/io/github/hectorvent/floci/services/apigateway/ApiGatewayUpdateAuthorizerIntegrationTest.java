package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ApiGatewayUpdateAuthorizerIntegrationTest {

    @Test
    void optionalSettingsPersistAcrossCreateUpdateGetAndList() {
        String apiId = given().contentType("application/json")
                .body(Map.of("name", "authorizer-optional-settings"))
                .when().post("/restapis")
                .then().statusCode(201).extract().path("id");
        String uri = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:us-east-1:000000000000:function:token-authorizer/invocations";
        String credentials = "arn:aws:iam::000000000000:role/authorizer";
        try {
            String authorizerId = given().contentType("application/json")
                    .body(Map.of("name", "token-authorizer", "type", "TOKEN", "authorizerUri", uri,
                            "identitySource", "method.request.header.Authorization", "authType", "custom",
                            "authorizerCredentials", credentials, "identityValidationExpression", "^Bearer .+$"))
                    .when().post("/restapis/" + apiId + "/authorizers")
                    .then().statusCode(201)
                    .body("authorizerResultTtlInSeconds", equalTo(300))
                    .body("authType", equalTo("custom"))
                    .body("authorizerCredentials", equalTo(credentials))
                    .body("identityValidationExpression", equalTo("^Bearer .+$"))
                    .extract().path("id");
            String path = "/restapis/" + apiId + "/authorizers/" + authorizerId;

            given().contentType("application/json")
                    .body(Map.of("patchOperations", List.of(
                            Map.of("op", "remove", "path", "/authorizerResultTtlInSeconds"))))
                    .when().patch(path)
                    .then().statusCode(200).body("authorizerResultTtlInSeconds", equalTo(300));

            given().contentType("application/json")
                    .body(Map.of("patchOperations", List.of(
                            Map.of("op", "replace", "path", "/authorizerResultTtlInSeconds", "value", "60"),
                            Map.of("op", "replace", "path", "/authType", "value", "updated"),
                            Map.of("op", "replace", "path", "/authorizerCredentials", "value", credentials + "-updated"),
                            Map.of("op", "replace", "path", "/identityValidationExpression", "value", "^Token .+$"))))
                    .when().patch(path)
                    .then().statusCode(200).body("authorizerResultTtlInSeconds", equalTo(60));

            for (String field : List.of("authorizerCredentials", "identityValidationExpression", "authorizerUri")) {
                given().contentType("application/json")
                        .body(Map.of("patchOperations", List.of(Map.of("op", "remove", "path", "/" + field))))
                        .when().patch(path).then().statusCode(400);
            }
            given().when().get(path).then().statusCode(200)
                    .body("authorizerUri", equalTo(uri))
                    .body("authType", equalTo("updated"))
                    .body("authorizerResultTtlInSeconds", equalTo(60))
                    .body("authorizerCredentials", equalTo(credentials + "-updated"))
                    .body("identityValidationExpression", equalTo("^Token .+$"));
            given().when().get("/restapis/" + apiId + "/authorizers").then().statusCode(200)
                    .body("item[0].id", equalTo(authorizerId))
                    .body("item[0].authorizerResultTtlInSeconds", equalTo(60))
                    .body("item[0].authorizerCredentials", equalTo(credentials + "-updated"));
            for (int attempt = 0; attempt < 2; attempt++) {
                given().contentType("application/json")
                        .body(Map.of("patchOperations", List.of(
                                Map.of("op", "remove", "path", "/authorizerResultTtlInSeconds"))))
                        .when().patch(path).then().statusCode(200)
                        .body("authorizerResultTtlInSeconds", equalTo(300));
            }
            given().when().get(path).then().statusCode(200)
                    .body("authorizerResultTtlInSeconds", equalTo(300))
                    .body("authorizerCredentials", equalTo(credentials + "-updated"));
        } finally {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void invalidPatchAfterRemovalDoesNotChangeStoredSettings() {
        String apiId = given().contentType("application/json")
                .body(Map.of("name", "authorizer-removal-atomicity"))
                .when().post("/restapis")
                .then().statusCode(201).extract().path("id");
        try {
            String authorizerId = given().contentType("application/json")
                    .body(Map.of("name", "request-authorizer", "type", "REQUEST",
                            "identitySource", "method.request.header.Authorization", "authorizerResultTtlInSeconds", 0))
                    .when().post("/restapis/" + apiId + "/authorizers")
                    .then().statusCode(201).extract().path("id");
            String path = "/restapis/" + apiId + "/authorizers/" + authorizerId;
            given().contentType("application/json")
                    .body(Map.of("patchOperations", List.of(
                            Map.of("op", "remove", "path", "/identitySource"),
                            Map.of("op", "remove", "path", "/authorizerResultTtlInSeconds"),
                            Map.of("op", "remove", "path", "/name"))))
                    .when().patch(path).then().statusCode(400);
            given().when().get(path).then().statusCode(200)
                    .body("name", equalTo("request-authorizer"))
                    .body("identitySource", equalTo("method.request.header.Authorization"))
                    .body("authorizerResultTtlInSeconds", equalTo(0));
            given().contentType("application/json")
                    .body(Map.of("patchOperations", List.of(Map.of("op", "remove", "path", "/identitySource"))))
                    .when().patch(path).then().statusCode(200).body("identitySource", nullValue());
            given().when().get(path).then().statusCode(200)
                    .body("identitySource", nullValue())
                    .body("authorizerResultTtlInSeconds", equalTo(0));
        } finally {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void shouldUpdateAuthorizerAndPersistChanges() {
        // Step 1: Create API
        Map<String, Object> createApiBody = new HashMap<>();
        createApiBody.put("name", "update-authorizer-api");

        String apiId = given()
                .contentType("application/json")
                .body(createApiBody)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // Step 2: Create Authorizer
        Map<String, Object> createAuthorizerBody = new HashMap<>();
        createAuthorizerBody.put("name", "before-update");
        createAuthorizerBody.put("type", "TOKEN");
        createAuthorizerBody.put("authorizerUri", "arn:aws:lambda:us-east-1:123456789012:function:my-authorizer");
        createAuthorizerBody.put("identitySource", "method.request.header.Authorization");
        createAuthorizerBody.put("authorizerResultTtlInSeconds", 300);

        String authorizerId = given()
                .contentType("application/json")
                .body(createAuthorizerBody)
                .when()
                .post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // Step 3: Update Authorizer via PATCH
        Map<String, Object> patchOp1 = new HashMap<>();
        patchOp1.put("op", "replace");
        patchOp1.put("path", "/name");
        patchOp1.put("value", "after-update");

        Map<String, Object> patchOp2 = new HashMap<>();
        patchOp2.put("op", "replace");
        patchOp2.put("path", "/identitySource");
        patchOp2.put("value", "method.request.header.X-Token");

        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("patchOperations", new Object[]{patchOp1, patchOp2});

        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("id", equalTo(authorizerId))
                .body("name", equalTo("after-update"))
                .body("identitySource", equalTo("method.request.header.X-Token"));

        // Step 4: GET Authorizer to verify persistence
        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("name", equalTo("after-update"))
                .body("identitySource", equalTo("method.request.header.X-Token"));
    }

    /**
     * A non-numeric /authorizerResultTtlInSeconds must be rejected before any mutation, otherwise the
     * stored value can no longer be serialised and every later GET/ListAuthorizers fails permanently.
     */
    @Test
    void shouldRejectNonNumericAuthorizerResultTtlAndLeaveAuthorizerReadable() {
        Map<String, Object> createApiBody = new HashMap<>();
        createApiBody.put("name", "ttl-validation-api");

        String apiId = given()
                .contentType("application/json")
                .body(createApiBody)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> createAuthorizerBody = new HashMap<>();
        createAuthorizerBody.put("name", "ttl-authorizer");
        createAuthorizerBody.put("type", "TOKEN");
        createAuthorizerBody.put("authorizerUri", "arn:aws:lambda:us-east-1:123456789012:function:my-authorizer");
        createAuthorizerBody.put("identitySource", "method.request.header.Authorization");
        createAuthorizerBody.put("authorizerResultTtlInSeconds", 300);

        String authorizerId = given()
                .contentType("application/json")
                .body(createAuthorizerBody)
                .when()
                .post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> badOp = new HashMap<>();
        badOp.put("op", "replace");
        badOp.put("path", "/authorizerResultTtlInSeconds");
        badOp.put("value", "not-a-number");

        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("patchOperations", new Object[]{badOp});

        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(400);

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("authorizerResultTtlInSeconds", equalTo(300));

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(200);
    }

    /**
     * A PATCH is all-or-nothing: a valid op followed by an invalid one must reject the whole request
     * without leaving the valid op's mutation visible.
     */
    @Test
    void shouldRejectWholePatchAndLeaveNameUnchangedWhenALaterOpIsInvalid() {
        Map<String, Object> createApiBody = new HashMap<>();
        createApiBody.put("name", "partial-apply-api");

        String apiId = given()
                .contentType("application/json")
                .body(createApiBody)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> createAuthorizerBody = new HashMap<>();
        createAuthorizerBody.put("name", "original-name");
        createAuthorizerBody.put("type", "TOKEN");
        createAuthorizerBody.put("authorizerUri", "arn:aws:lambda:us-east-1:123456789012:function:my-authorizer");
        createAuthorizerBody.put("identitySource", "method.request.header.Authorization");

        String authorizerId = given()
                .contentType("application/json")
                .body(createAuthorizerBody)
                .when()
                .post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> validOp = new HashMap<>();
        validOp.put("op", "replace");
        validOp.put("path", "/name");
        validOp.put("value", "renamed");

        Map<String, Object> invalidOp = new HashMap<>();
        invalidOp.put("op", "replace");
        invalidOp.put("path", "/authorizerResultTtlInSeconds");
        invalidOp.put("value", "not-a-number");

        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("patchOperations", new Object[]{validOp, invalidOp});

        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(400);

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("name", equalTo("original-name"));
    }
}

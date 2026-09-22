package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class ApiGatewayUpdateBasePathMappingIntegrationTest {

    @Test
    void testCreateAndUpdateBasePathMapping() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"mapping-api\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{\"domainName\":\"mapping.example.test\"}").when().post("/domainnames").then().statusCode(201);
        String basePath = "v1";
        String stageBefore = "before";
        String createBody = "{\"basePath\":\"" + basePath + "\",\"restApiId\":\"" + apiId + "\",\"stage\":\"" + stageBefore + "\"}";
        given().contentType("application/json").body(createBody).when().post("/domainnames/mapping.example.test/basepathmappings").then().statusCode(201);
        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/stage\",\"value\":\"after\"}]}";
        given().contentType("application/json").body(patchBody).when().patch("/domainnames/mapping.example.test/basepathmappings/v1").then().statusCode(200).body("basePath", equalTo(basePath)).body("restApiId", equalTo(apiId)).body("stage", equalTo("after"));
        given().when().get("/domainnames/mapping.example.test/basepathmappings/v1").then().statusCode(200).body("stage", equalTo("after"));
    }

    @Test
    void v2UpdatePreservesIdentityAndOmittedFieldsWhileOldKeysCanBeReused() {
        String auth = authorization("000000000001", "us-east-1");
        String domain = "update-mapping-identity.example.test";
        createDomain(auth, domain);
        String firstApi = createApi(auth, "first-mapping-api", "blue");
        String secondApi = createApi(auth, "second-mapping-api", "green");
        String id = createMapping(auth, domain, firstApi, "blue", "old");
        String path = mappingPath(domain, id);

        given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("apiId", secondApi, "stage", "green", "apiMappingKey", "new"))
                .when().patch(path).then().statusCode(200)
                .body("apiMappingId", equalTo(id)).body("apiId", equalTo(secondApi))
                .body("stage", equalTo("green")).body("apiMappingKey", equalTo("new"));
        given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("apiId", secondApi))
                .when().patch(path).then().statusCode(200)
                .body("apiMappingId", equalTo(id)).body("stage", equalTo("green"))
                .body("apiMappingKey", equalTo("new"));
        given().header("Authorization", auth)
                .get("/domainnames/" + domain + "/basepathmappings/old").then().statusCode(404);
        given().header("Authorization", auth)
                .get("/domainnames/" + domain + "/basepathmappings/new").then().statusCode(200)
                .body("restApiId", equalTo(secondApi)).body("stage", equalTo("green"));

        String replacement = createMapping(auth, domain, firstApi, "blue", "old");
        assertNotEquals(id, replacement);
        given().header("Authorization", auth).get("/v2/domainnames/" + domain + "/apimappings")
                .then().statusCode(200).body("items.apiMappingId", hasItems(id, replacement));
        given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("apiId", secondApi, "apiMappingKey", ""))
                .when().patch(path).then().statusCode(200)
                .body("apiMappingId", equalTo(id)).body("apiMappingKey", equalTo(""))
                .body("stage", equalTo("green"));
        given().header("Authorization", auth).delete(path).then().statusCode(204);
        given().header("Authorization", auth).get(path).then().statusCode(404);
        assertMapping(auth, domain, replacement, firstApi, "blue", "old");
    }

    @Test
    void v2RenameConflictAndInvalidTargetsLeaveTheMappingUnchanged() {
        String auth = authorization("000000000001", "us-east-1");
        String domain = "update-mapping-errors.example.test";
        createDomain(auth, domain);
        String firstApi = createApi(auth, "mapping-before", "blue");
        String secondApi = createApi(auth, "mapping-after", "green");
        String id = createMapping(auth, domain, firstApi, "blue", "original");
        String occupied = createMapping(auth, domain, firstApi, "blue", "taken");
        String root = createMapping(auth, domain, firstApi, "blue", "");
        for (String key : List.of("taken", "", "/", " ")) {
            given().header("Authorization", auth).contentType("application/json")
                    .body(Map.of("apiId", secondApi, "stage", "green", "apiMappingKey", key))
                    .when().patch(mappingPath(domain, id)).then().statusCode(409);
            assertMapping(auth, domain, id, firstApi, "blue", "original");
        }
        assertMapping(auth, domain, occupied, firstApi, "blue", "taken");
        assertMapping(auth, domain, root, firstApi, "blue", "");

        List<Map<String, Object>> invalid = List.of(
                Map.of("apiId", "missing", "stage", "green", "apiMappingKey", "unused"),
                Map.of("apiId", secondApi, "stage", "missing", "apiMappingKey", "unused"),
                Map.of("apiId", secondApi, "apiMappingKey", "unused"),
                Map.of("stage", "green", "apiMappingKey", "unused"),
                Map.of("apiId", ""),
                Map.of("apiId", firstApi, "stage", List.of("blue")),
                Map.of("apiId", firstApi, "apiMappingKey", 123));
        for (Map<String, Object> request : invalid) {
            given().header("Authorization", auth).contentType("application/json").body(request)
                    .when().patch(mappingPath(domain, id)).then().statusCode(400);
            assertMapping(auth, domain, id, firstApi, "blue", "original");
            given().header("Authorization", auth)
                    .get("/domainnames/" + domain + "/basepathmappings/unused").then().statusCode(404);
        }
        for (String request : List.of("null", "{\"apiId\":null}",
                "{\"apiId\":\"" + firstApi + "\",\"stage\":null}")) {
            given().header("Authorization", auth).contentType("application/json").body(request)
                    .when().patch(mappingPath(domain, id)).then().statusCode(400);
            assertMapping(auth, domain, id, firstApi, "blue", "original");
        }
        given().header("Authorization", auth).contentType("application/json").body(Map.of("apiId", firstApi))
                .when().patch(mappingPath(domain, "missing")).then().statusCode(404);
    }

    @Test
    void v2UpdatesUseTheRequestAccountAndRegionForMappingsAndTargets() {
        String owner = authorization("000000000001", "us-east-1");
        String otherAccount = authorization("000000000002", "us-east-1");
        String otherRegion = authorization("000000000001", "eu-west-1");
        String domain = "update-mapping-isolation.example.test";
        createDomain(owner, domain);
        String api = createApi(owner, "owner-api", "blue");
        String id = createMapping(owner, domain, api, "blue", "same");
        for (String auth : List.of(otherAccount, otherRegion)) {
            String isolatedDomain = auth.equals(otherRegion) ? "regional-" + domain : domain;
            createDomain(auth, isolatedDomain);
            String otherApi = createApi(auth, "isolated-api", "blue");
            String otherId = createMapping(auth, isolatedDomain, otherApi, "blue", "same");
            given().header("Authorization", auth).contentType("application/json")
                    .body(Map.of("apiId", otherApi, "apiMappingKey", "wrong"))
                    .when().patch(mappingPath(domain, id)).then().statusCode(404);
            given().header("Authorization", auth).get(mappingPath(domain, id)).then().statusCode(404);
            given().header("Authorization", auth).delete(mappingPath(domain, id)).then().statusCode(404);
            given().header("Authorization", owner).contentType("application/json")
                    .body(Map.of("apiId", otherApi, "apiMappingKey", "wrong"))
                    .when().patch(mappingPath(domain, id)).then().statusCode(400);
            assertMapping(auth, isolatedDomain, otherId, otherApi, "blue", "same");
        }
        assertMapping(owner, domain, id, api, "blue", "same");
    }

    @Test
    void v1CreatedMappingsHaveStableIdsWhenUpdatedThroughV2() {
        String auth = authorization("000000000001", "us-east-1");
        String domain = "update-v1-mapping.example.test";
        createDomain(auth, domain);
        String api = createApi(auth, "v1-mapping-target", "blue");
        given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("restApiId", api, "stage", "blue", "basePath", "v1"))
                .post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);
        String id = given().header("Authorization", auth).get("/v2/domainnames/" + domain + "/apimappings")
                .then().statusCode(200).extract().path("items[0].apiMappingId");
        assertNotEquals(ApiGatewayController.apiMappingId("v1"), id);
        given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("apiId", api, "apiMappingKey", "v2"))
                .patch(mappingPath(domain, id)).then().statusCode(200).body("apiMappingId", equalTo(id));
        assertMapping(auth, domain, id, api, "blue", "v2");
    }

    private static String authorization(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region
                + "/apigateway/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String mappingPath(String domain, String id) {
        return "/v2/domainnames/" + domain + "/apimappings/" + id;
    }

    private static void createDomain(String auth, String domain) {
        given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("domainName", domain,
                        "domainNameConfigurations", List.of(Map.of("endpointType", "REGIONAL"))))
                .post("/v2/domainnames").then().statusCode(201);
    }

    private static String createApi(String auth, String name, String stage) {
        String api = given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("name", name, "protocolType", "HTTP"))
                .post("/v2/apis").then().statusCode(201).extract().path("apiId");
        given().header("Authorization", auth).contentType("application/json").body(Map.of("stageName", stage))
                .post("/v2/apis/" + api + "/stages").then().statusCode(201);
        return api;
    }

    private static String createMapping(String auth, String domain, String api, String stage, String key) {
        return given().header("Authorization", auth).contentType("application/json")
                .body(Map.of("apiId", api, "stage", stage, "apiMappingKey", key))
                .post("/v2/domainnames/" + domain + "/apimappings")
                .then().statusCode(201).extract().path("apiMappingId");
    }

    private static void assertMapping(String auth, String domain, String id, String api, String stage, String key) {
        given().header("Authorization", auth).get(mappingPath(domain, id)).then().statusCode(200)
                .body("apiMappingId", equalTo(id)).body("apiId", equalTo(api))
                .body("stage", equalTo(stage)).body("apiMappingKey", equalTo(key));
    }
}

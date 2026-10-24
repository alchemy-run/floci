package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Exercises transformed auth through the REST importer and the real execute-api HTTP verifier. */
@QuarkusTest
class SamRestApiAuthIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void conflictingAuthorizersCannotSilentlyReplaceTheSamIamDefault(boolean swagger, boolean repeated)
            throws Exception {
        ObjectNode template = template(swagger);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        ObjectNode scheme = customAuthorizer();
        ObjectNode schemes = mapper.createObjectNode().set("custom", scheme);
        if (swagger) {
            body.set("securityDefinitions", schemes);
        } else {
            body.set("components", mapper.createObjectNode().set("securitySchemes", schemes));
        }
        ((ObjectNode) body.at("/paths/~1items/post")).set("security",
                mapper.readTree(repeated ? "[{\"AWS_IAM\":[]},{\"AWS_IAM\":[]}]" : "[{\"custom\":[]}]"));
        String title = "conflicting-sam-" + UUID.randomUUID();
        ((ObjectNode) body.get("info")).put("title", title);
        JsonNode document = new SamTransformProcessor(mapper).expandSamTemplate(template)
                .at("/Resources/Api/Properties/Body");

        given().contentType(ContentType.JSON).queryParam("mode", "import")
                .body(document.toString()).post("/restapis").then().statusCode(400)
                .body("message", containsString("Only one Authorizer or IAM authorization"));
        given().get("/restapis").then().statusCode(200)
                .body("item.find { it.name == '" + title + "' }", equalTo(null));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void importedSamApiRequiresBothValidSignatureAndEnabledStageKey(boolean swagger, boolean existingScheme)
            throws Exception {
        ObjectNode template = template(swagger);
        if (existingScheme) {
            ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
            ObjectNode schemes = mapper.createObjectNode().set("existing", mapper.readTree("""
                    {"type":"apiKey","name":"Authorization","in":"header",
                     "x-amazon-apigateway-authtype":"custom-authentication"}
                    """));
            if (swagger) {
                body.set("securityDefinitions", schemes);
            } else {
                body.set("components", mapper.createObjectNode().set("securitySchemes", schemes));
            }
            body.set("security", mapper.readTree("[{\"existing\":[]}]"));
        }
        JsonNode document = new SamTransformProcessor(mapper).expandSamTemplate(template)
                .at("/Resources/Api/Properties/Body");
        String apiId = null;
        String keyId = null;
        String planId = null;
        try {
            apiId = given().contentType(ContentType.JSON).queryParam("mode", "import")
                    .body(document.toString()).post("/restapis")
                    .then().statusCode(201).extract().path("id");
            String resourceId = given().get("/restapis/" + apiId + "/resources")
                    .then().statusCode(200).extract().path("item.find { it.path == '/items' }.id");
            assertNotNull(resourceId);
            given().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                    .then().statusCode(200)
                    .body("authorizationType", equalTo("AWS_IAM"))
                    .body("apiKeyRequired", equalTo(true));
            for (String stage : new String[] {"test", "unlinked"}) {
                given().contentType(ContentType.JSON).body(Map.of("stageName", stage))
                        .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
            }
            String suffix = UUID.randomUUID().toString();
            keyId = given().contentType(ContentType.JSON)
                    .body(Map.of("name", "sam-key-" + suffix, "enabled", true))
                    .post("/apikeys").then().statusCode(201).extract().path("id");
            String keyValue = given().queryParam("includeValue", true).get("/apikeys/" + keyId)
                    .then().statusCode(200).extract().path("value");
            planId = given().contentType(ContentType.JSON)
                    .body(Map.of("name", "sam-plan-" + suffix,
                            "apiStages", new Object[] {Map.of("apiId", apiId, "stage", "test")}))
                    .post("/usageplans").then().statusCode(201).extract().path("id");
            given().contentType(ContentType.JSON).body(Map.of("keyId", keyId, "keyType", "API_KEY"))
                    .post("/usageplans/" + planId + "/keys").then().statusCode(201);

            String path = "/execute-api/" + apiId + "/test/items";
            Map<String, String> signed = signed(path);
            given().header("x-api-key", keyValue).post(path).then().statusCode(403);
            given().headers(signed).post(path).then().statusCode(403);
            given().headers(signed).header("x-api-key", "unknown-key").post(path)
                    .then().statusCode(403);
            given().headers(signed).header("x-api-key", keyValue).body("tampered").post(path)
                    .then().statusCode(403);
            given().headers(signed).header("x-api-key", keyValue).post(path)
                    .then().statusCode(200).body("accepted", equalTo(true));

            ObjectNode conflicting = document.deepCopy();
            ObjectNode schemes = (ObjectNode) (swagger ? conflicting.path("securityDefinitions")
                    : conflicting.at("/components/securitySchemes"));
            schemes.set("custom", customAuthorizer());
            ((ObjectNode) conflicting.at("/paths/~1items/post")).set("security",
                    mapper.readTree("[{\"custom\":[]},{\"AWS_IAM\":[]},{\"api_key\":[]}]"));
            given().contentType(ContentType.JSON).queryParam("mode", "overwrite")
                    .body(conflicting.toString()).put("/restapis/" + apiId).then().statusCode(400)
                    .body("message", containsString("Only one Authorizer or IAM authorization"));
            given().headers(signed).header("x-api-key", keyValue).post(path)
                    .then().statusCode(200).body("accepted", equalTo(true));

            String unlinked = "/execute-api/" + apiId + "/unlinked/items";
            given().headers(signed(unlinked)).header("x-api-key", keyValue).post(unlinked)
                    .then().statusCode(403);
            given().contentType(ContentType.JSON)
                    .body("""
                            {"patchOperations":[{"op":"replace","path":"/enabled","value":"false"}]}
                            """)
                    .patch("/apikeys/" + keyId).then().statusCode(200);
            given().headers(signed(path)).header("x-api-key", keyValue).post(path)
                    .then().statusCode(403);

            ObjectNode operation = (ObjectNode) document.at("/paths/~1items/post");
            operation.set("security", mapper.createArrayNode());
            given().contentType(ContentType.JSON).queryParam("mode", "overwrite")
                    .body(document.toString()).put("/restapis/" + apiId).then().statusCode(200);
            String newResourceId = given().get("/restapis/" + apiId + "/resources")
                    .then().statusCode(200).extract().path("item.find { it.path == '/items' }.id");
            given().get("/restapis/" + apiId + "/resources/" + newResourceId + "/methods/POST")
                    .then().statusCode(200)
                    .body("authorizationType", equalTo("NONE"))
                    .body("apiKeyRequired", equalTo(false));
        } finally {
            if (planId != null) {
                given().delete("/usageplans/" + planId).then().statusCode(202);
            }
            if (keyId != null) {
                given().delete("/apikeys/" + keyId).then().statusCode(202);
            }
            if (apiId != null) {
                given().delete("/restapis/" + apiId).then().statusCode(202);
            }
        }
    }

    private ObjectNode customAuthorizer() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"type":"apiKey","name":"Authorization","in":"header",
                  "x-amazon-apigateway-authtype":"custom",
                  "x-amazon-apigateway-authorizer":{"type":"request",
                    "identitySource":"method.request.header.Authorization",
                    "authorizerUri":"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:custom/invocations"}}
                """);
    }

    private Map<String, String> signed(String path) throws Exception {
        return ExecuteApiRequestSigner.signedHeaders("POST", path, Map.of(),
                "localhost:" + RestAssured.port, null, "test", "test", "us-east-1", Instant.now());
    }

    private ObjectNode template(boolean swagger) throws Exception {
        ObjectNode template = (ObjectNode) mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Api":{"Type":"AWS::Serverless::Api","Properties":{
                    "StageName":"test","Auth":{"DefaultAuthorizer":"AWS_IAM","ApiKeyRequired":true},
                    "DefinitionBody":{"openapi":"3.0.1","info":{"title":"SAM auth","version":"1"},
                      "paths":{"/items":{"post":{
                        "responses":{"200":{"description":"Accepted"}},
                        "x-amazon-apigateway-integration":{"type":"mock",
                          "requestTemplates":{"application/json":"{\\"statusCode\\":200}"},
                          "responses":{"default":{"statusCode":"200","responseTemplates":{
                            "application/json":"{\\"accepted\\":true}"}}}}
                      }}}
                    }
                  }}
                }}
                """);
        if (swagger) {
            ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
            body.remove("openapi");
            body.put("swagger", "2.0");
        }
        return template;
    }
}

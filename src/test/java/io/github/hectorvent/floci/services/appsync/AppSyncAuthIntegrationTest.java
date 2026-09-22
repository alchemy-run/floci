package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncExecutionController;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.RoleSessionCredentials;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testutil.AppSyncRequestSigner;
import io.restassured.RestAssured;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppSyncAuthIntegrationTest {

    private static final String MGMT_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    @Inject
    ResolvedServiceCatalog catalog;

    @Inject
    IamService iamService;

    @Test
    void executionRoleQueriesJsResolversWithFieldScopedPermissions() throws Exception {
        String apiId = given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body(Map.of("name", "iam-js-" + UUID.randomUUID(), "authenticationType", "AWS_IAM"))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");
        String roleName = "appsync-execution-" + UUID.randomUUID();
        IamRole role = iamService.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 3600, Map.of());
        RoleSessionCredentials credentials = iamService.mintRoleSession(role.getArn());
        try {
            startSchema(apiId, "type Query { add(a: Int!, b: Int!): Int greeting: String }");
            awaitSchemaSuccess(apiId);
            given()
                .header("Authorization", MGMT_AUTH)
                .contentType("application/json")
                .body(Map.of("environmentVariables", Map.of("GREETING", "hello from resolver env")))
            .when()
                .put("/v1/apis/" + apiId + "/environmentVariables")
            .then()
                .statusCode(200);
            given()
                .header("Authorization", MGMT_AUTH)
                .contentType("application/json")
                .body(Map.of("name", "local", "type", "NONE"))
            .when()
                .post("/v1/apis/" + apiId + "/datasources")
            .then()
                .statusCode(200);
            Map<String, String> codeByField = Map.of(
                    "add", """
                        export function request(ctx) { return { payload: ctx.args.a + ctx.args.b }; }
                        export function response(ctx) { return ctx.result; }
                        """,
                    "greeting", """
                        export function request(ctx) { return { payload: null }; }
                        export function response(ctx) { return ctx.env.GREETING; }
                        """);
            for (Map.Entry<String, String> entry : codeByField.entrySet()) {
                given()
                    .header("Authorization", MGMT_AUTH)
                    .contentType("application/json")
                    .body(Map.of("fieldName", entry.getKey(), "dataSourceName", "local",
                            "code", entry.getValue(),
                            "runtime", Map.of("name", "APPSYNC_JS", "runtimeVersion", "1.0.0")))
                .when()
                    .post("/v1/apis/" + apiId + "/types/Query/resolvers")
                .then()
                    .statusCode(200);
            }
            String fieldPrefix = "arn:aws:appsync:us-east-1:000000000000:apis/" + apiId + "/types/";
            iamService.putRolePolicy(roleName, "graphql", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"appsync:GraphQL",
                    "Resource":"%s*/fields/*"}]}
                    """.formatted(fieldPrefix));
            String body = """
                    {"query":"query($a: Int!, $b: Int!) { add(a: $a, b: $b) greeting }",
                     "variables":{"a":2,"b":3}}
                    """;
            String pathStyle = "/v1/apis/" + apiId + "/graphql";
            String host = apiId + ".appsync-api.us-east-1.localhost.floci.io:" + RestAssured.port;
            for (String path : List.of(pathStyle, "/graphql")) {
                Map<String, String> signed = signGraphqlRequest(path, host, body, credentials);
                given().headers(signed).body(body).post(path)
                .then()
                    .statusCode(200)
                    .body("data.add", equalTo(5))
                    .body("data.greeting", equalTo("hello from resolver env"))
                    .body("errors", nullValue());
            }
            given().headers(signGraphqlRequest("/graphql", host, body, credentials)).body(body)
                .post(pathStyle)
            .then()
                .statusCode(401);
            Map<String, String> headers = signGraphqlRequest(pathStyle, host, body, credentials);

            iamService.putRolePolicy(roleName, "graphql", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"appsync:GraphQL",
                    "Resource":"%sQuery/fields/add"}]}
                    """.formatted(fieldPrefix));
            given().contentType("application/json").headers(headers).body(body)
                .post("/v1/apis/" + apiId + "/graphql")
            .then()
                .statusCode(200)
                .body("data.add", equalTo(5))
                .body("data.greeting", nullValue())
                .body("errors[0].errorType", equalTo("Unauthorized"));

            headers.put("x-amz-security-token", "invalid-token");
            given().contentType("application/json").headers(headers).body(body)
                .post("/v1/apis/" + apiId + "/graphql")
            .then()
                .statusCode(401)
                .body("errors[0].errorType", equalTo("UnauthorizedException"));
        } finally {
            iamService.unregisterSession("000000000000", credentials.accessKeyId());
            try {
                for (String policyName : iamService.listRolePolicies(roleName)) {
                    iamService.deleteRolePolicy(roleName, policyName);
                }
                iamService.deleteRole(roleName);
            } finally {
                given().header("Authorization", MGMT_AUTH)
                    .delete("/v1/apis/" + apiId)
                .then().statusCode(204);
            }
        }
    }

    private static Map<String, String> signGraphqlRequest(String path, String host, String body,
                                                         RoleSessionCredentials credentials) throws Exception {
        String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String day = date.substring(0, 8);
        String scope = day + "/us-east-1/appsync/aws4_request";
        String names = "content-type;host;x-amz-date;x-amz-security-token";
        String contentType = "application/json; charset=UTF-8";
        String canonicalHeaders = "content-type:" + contentType + "\nhost:" + host + "\nx-amz-date:" + date
                + "\nx-amz-security-token:" + credentials.sessionToken() + "\n";
        String canonicalRequest = "POST\n" + path + "\n\n" + canonicalHeaders + "\n" + names + "\n" + sha256(body);
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" + sha256(canonicalRequest);
        byte[] key = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(hmac(hmac(key, "us-east-1"), "appsync"), "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, toSign));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", contentType);
        headers.put("host", host);
        headers.put("x-amz-date", date);
        headers.put("x-amz-security-token", credentials.sessionToken());
        headers.put("authorization", "AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + names + ", Signature=" + signature);
        return headers;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void catalogRegistersExecutionController() {
        assertTrue(catalog.byResourceClass(AppSyncExecutionController.class).isPresent());
    }

    @Test
    void unsignedApiKeyPostReturnsAppSyncJsonNotS3Xml() {
        String apiId = createApi("auth-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);
        String apiKey = createApiKey(apiId);

        given()
            .contentType("application/graphql")
            .header("x-api-key", apiKey)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body(not(containsString("<Error>")))
            .body(not(containsString("NoSuchBucket")))
            .body("data.hello", nullValue());
    }

    @Test
    void missingCredentialsReturns401UnauthorizedException() {
        String apiId = createApi("unauth-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"))
            .header("x-amz-request-id", not(nullValue()))
            .body("errors[0].errorType", equalTo("UnauthorizedException"))
            .body("errors[0].message", equalTo("Missing authorization header"))
            .body("data", nullValue())
            .body("__type", nullValue());
    }

    @Test
    void invalidApiKeyReturns401() {
        String apiId = createApi("badkey-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .header("x-api-key", "da2-does-not-exist")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"))
            .body("errors[0].message", equalTo("You are not authorized to make this call."));
    }

    @Test
    void dummySigV4OnApiKeyApiReturns401() {
        String apiId = createApi("sigv4-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .header("Authorization", MGMT_AUTH)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"));
    }

    @Test
    void sigV4PlusValidApiKeyDoesNotFallBackToApiKey() {
        String apiId = createApi("nofallback-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);
        String apiKey = createApiKey(apiId);

        given()
            .contentType("application/json")
            .header("x-api-key", apiKey)
            .header("Authorization", MGMT_AUTH)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"));
    }

    @Test
    void unknownApiReturns404BeforeAuth() {
        given()
            .contentType("application/json")
            .header("x-api-key", "da2-looks-valid")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/does-not-exist-xyz/graphql")
        .then()
            .statusCode(404)
            .header("x-amzn-errortype", containsString("NotFoundException"));
    }

    @Test
    void missingCredentialsOnApiWithoutSchemaReturns401Not502() {
        String bareApiId = createApi("bare-auth-" + UUID.randomUUID().toString().substring(0, 8));

        given()
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + bareApiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"))
            .body("errors[0].message", equalTo("Missing authorization header"));
    }

    @Test
    void iamCallerOnApiKeyFieldReturns200WithUnauthorizedError() throws Exception {
        String apiId = given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "multi-%s",
                  "authenticationType": "AWS_IAM",
                  "additionalAuthenticationProviders": [
                    {"authenticationType": "API_KEY"}
                  ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8)))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        startSchema(apiId, "type Query { hello: String @aws_api_key }");
        awaitSchemaSuccess(apiId);

        String body = "{\"query\":\"{ hello }\"}";
        given()
            .contentType("application/json")
            .headers(iamSignedHeaders(apiId, body))
            .body(body)
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .header("x-amzn-errortype", nullValue())
            .body("data.hello", nullValue())
            .body("errors[0].errorType", equalTo("Unauthorized"))
            .body("errors[0].message", equalTo("Not Authorized to access hello on type Query"))
            .body("errors[0].path", equalTo(java.util.List.of("hello")));
    }

    @Test
    void additionalModeIamCanIntrospect() throws Exception {
        String apiId = given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "intro-%s",
                  "authenticationType": "API_KEY",
                  "additionalAuthenticationProviders": [
                    {"authenticationType": "AWS_IAM"}
                  ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8)))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        String body = "{\"query\":\"{ __schema { types { name } } }\"}";
        given()
            .contentType("application/json")
            .headers(iamSignedHeaders(apiId, body))
            .body(body)
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("data.__schema.types", not(nullValue()))
            .body("errors", nullValue());
    }

    @Test
    void awsAuthOnFieldDefinitionSchemaSucceeds() {
        String apiId = createApi("aws-auth-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { secret: String @aws_auth(cognito_groups: [\"Admins\"]) }");
        awaitSchemaSuccess(apiId);
    }

    /**
     * Data-plane requests in {@code AWS_IAM} mode must carry a real SigV4 signature; the bare
     * {@code Credential=} header that is enough for the management plane is rejected there.
     */
    private static Map<String, String> iamSignedHeaders(String apiId, String body) throws Exception {
        return AppSyncRequestSigner.signedHeaders(apiId, "localhost:" + RestAssured.port, body,
                "test", "test", "us-east-1", Instant.now());
    }

    private static String createApi(String name) {
        return given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("""
                {"name": "%s", "authenticationType": "API_KEY"}
                """.formatted(name))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");
    }

    private static String createApiKey(String apiId) {
        return given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .extract().path("apiKey.id");
    }

    private static void startSchema(String apiId, String definition) {
        String escaped = definition.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("{\"definition\": \"" + escaped + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200);
    }

    private static void awaitSchemaSuccess(String apiId) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> {
                    String status = given()
                        .header("Authorization", MGMT_AUTH)
                    .when()
                        .get("/v1/apis/" + apiId + "/schemacreation")
                    .then()
                        .statusCode(200)
                        .extract().path("status");
                    return "SUCCESS".equals(status);
                });
    }
}

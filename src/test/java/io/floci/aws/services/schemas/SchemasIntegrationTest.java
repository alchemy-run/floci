package io.floci.aws.services.schemas;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventBridge schema registry: registries, policies, versioned schemas, search, export, inference, code bindings,
 * and a discoverer that infers schemas from the events its bus accepts and announces them on the default bus.
 */
@QuarkusTest
class SchemasIntegrationTest {

    static final String JSON = "application/json";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification schemas() {
        return given().header("Authorization", auth("schemas")).contentType(JSON);
    }

    static RequestSpecification events(String action, String body) {
        return given().header("Authorization", auth("events")).header("X-Amz-Target", "AWSEvents." + action)
                .contentType("application/x-amz-json-1.1").body(body);
    }

    static RequestSpecification query(String service) {
        return given().header("Authorization", auth(service)).contentType("application/x-www-form-urlencoded");
    }

    static String openApi(String extra) {
        return "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Order\",\"version\":\"1.0.0\"},\"paths\":{},"
                + "\"components\":{\"schemas\":{\"Order\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":"
                + "\"string\"}" + extra + "}}}}}";
    }

    static String quoted(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String event(String detail) {
        return "{\"Source\":\"my.shop\",\"DetailType\":\"Order Placed\",\"EventBusName\":\"shop\",\"Detail\":"
                + quoted(detail) + "}";
    }

    @Test
    void registriesSchemasAndDiscovery() throws IOException {
        String registry = schemas().body("{\"Description\":\"orders\",\"tags\":{\"team\":\"shop\"}}")
                .post("/v1/registries/name/orders").then().statusCode(201).extract().path("RegistryArn");
        schemas().body("{}").post("/v1/registries/name/orders").then().statusCode(409);
        schemas().get("/v1/registries?scope=LOCAL").then().body("Registries.RegistryName", hasItem("orders"))
                .body("Registries.RegistryName", not(hasItem("aws.events")));
        schemas().get("/v1/registries?scope=AWS").then().body("Registries.RegistryName",
                equalTo(List.of("aws.events")));

        // Versioned schemas.
        schemas().body("{\"Type\":\"OpenApi3\",\"Content\":\"{}\"}").post("/v1/registries/name/orders/schemas/name/"
                + "Order").then().statusCode(400);
        schemas().body("{\"Type\":\"OpenApi3\",\"Description\":\"an order\",\"Content\":" + quoted(openApi(""))
                + "}").post("/v1/registries/name/orders/schemas/name/Order").then().statusCode(201)
                .body("SchemaVersion", equalTo("1"));
        schemas().body("{\"Content\":" + quoted(openApi("")) + "}").put("/v1/registries/name/orders/schemas/name/"
                + "Order").then().statusCode(200).body("SchemaVersion", equalTo("1"));
        schemas().body("{\"Content\":" + quoted(openApi(",\"total\":{\"type\":\"number\"}")) + "}")
                .put("/v1/registries/name/orders/schemas/name/Order").then().statusCode(200)
                .body("SchemaVersion", equalTo("2"));
        schemas().get("/v1/registries/name/orders/schemas/name/Order?schemaVersion=1").then()
                .body("Content", not(containsString("total")));
        schemas().get("/v1/registries/name/orders/schemas/name/Order/versions").then()
                .body("SchemaVersions.SchemaVersion", equalTo(List.of("1", "2")));
        schemas().get("/v1/registries/name/orders/schemas/search?keywords=total").then()
                .body("Schemas.SchemaName", equalTo(List.of("Order")));
        schemas().delete("/v1/registries/name/orders").then().statusCode(400);

        // Export: only discovered and AWS-managed schemas, to JSON Schema Draft 4.
        schemas().get("/v1/registries/name/orders/schemas/name/Order/export?type=JSONSchemaDraft4").then()
                .statusCode(403).body("message", containsString("export"));
        schemas().get("/v1/registries/name/aws.events/schemas/name/aws.s3@ObjectCreated/export?type="
                + "JSONSchemaDraft4").then().statusCode(200)
                .body("Content", containsString("#/definitions/ObjectCreated"))
                .body("Content", containsString("draft-04"));

        // Policies are revisioned.
        String revision = schemas().body("{\"Policy\":\"{\\\"Statement\\\":[]}\"}").put("/v1/policy?registryName="
                + "orders").then().statusCode(200).extract().path("RevisionId");
        schemas().body("{\"Policy\":\"{\\\"Statement\\\":[]}\",\"RevisionId\":\"stale\"}")
                .put("/v1/policy?registryName=orders").then().statusCode(412);
        schemas().get("/v1/policy?registryName=orders").then().body("RevisionId", equalTo(revision));

        // Inference from sample events.
        String sample = "{\"version\":\"0\",\"id\":\"1\",\"detail-type\":\"Order Placed\",\"source\":\"my.shop\","
                + "\"account\":\"000000000000\",\"time\":\"2026-01-01T00:00:00Z\",\"region\":\"us-east-1\","
                + "\"resources\":[],\"detail\":{\"orderId\":\"o-1\",\"total\":12.5,\"items\":[{\"sku\":\"a\"}]}}";
        schemas().body("{\"Type\":\"OpenApi3\",\"Events\":[" + quoted(sample) + "]}").post("/v1/discover").then()
                .statusCode(200).body("Content", containsString("OrderPlaced"))
                .body("Content", containsString("#/components/schemas/OrderPlaced"));

        // Code bindings are generated source.
        schemas().post("/v1/registries/name/orders/schemas/name/Order/language/TypeScript3").then().statusCode(202)
                .body("Status", equalTo("CREATE_IN_PROGRESS"));
        await().atMost(Duration.ofSeconds(10)).until(() -> "CREATE_COMPLETE".equals(schemas()
                .get("/v1/registries/name/orders/schemas/name/Order/language/TypeScript3").then().extract()
                .path("Status")));
        byte[] zip = schemas().get("/v1/registries/name/orders/schemas/name/Order/language/TypeScript3/source")
                .then().statusCode(200).extract().asByteArray();
        String source = unzip(zip);
        assertTrue(source.contains("export interface Order") && source.contains("\"total\"?: number"), source);

        // A discoverer infers schemas from its bus and announces them on the default bus.
        String queueUrl = xml(query("sqs").formParam("Action", "CreateQueue").formParam("QueueName", "schema-events"),
                "QueueUrl");
        events("PutRule", "{\"Name\":\"schemas\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.schemas\\\"]}\"}")
                .post("/").then().statusCode(200);
        events("PutTargets", "{\"Rule\":\"schemas\",\"Targets\":[{\"Id\":\"q\",\"Arn\":"
                + "\"arn:aws:sqs:us-east-1:000000000000:schema-events\"}]}").post("/").then().statusCode(200);
        String bus = events("CreateEventBus", "{\"Name\":\"shop\"}").post("/").then().statusCode(200).extract()
                .path("EventBusArn");
        schemas().body("{\"SourceArn\":\"arn:aws:events:us-east-1:000000000000:event-bus/missing\"}")
                .post("/v1/discoverers").then().statusCode(404);
        String discoverer = schemas().body("{\"SourceArn\":\"" + bus + "\",\"Description\":\"shop\"}")
                .post("/v1/discoverers").then().statusCode(201).body("State", equalTo("STARTED")).extract()
                .path("DiscovererId");
        schemas().body("{\"SourceArn\":\"" + bus + "\"}").post("/v1/discoverers").then().statusCode(409);
        events("PutEvents", "{\"Entries\":[" + event("{\"orderId\":\"o-1\"}") + "]}").post("/").then()
                .statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> schemas().get("/v1/registries/name/discovered-schemas/"
                + "schemas/name/my.shop@OrderPlaced").then().extract().statusCode() == 200);
        events("PutEvents", "{\"Entries\":[" + event("{\"orderId\":\"o-2\",\"coupon\":\"X\"}") + "]}").post("/")
                .then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> "2".equals(schemas().get("/v1/registries/name/"
                + "discovered-schemas/schemas/name/my.shop@OrderPlaced").then().extract().path("SchemaVersion")));
        schemas().get("/v1/registries/name/discovered-schemas/schemas/name/my.shop@OrderPlaced").then()
                .body("Content", containsString("coupon"));
        List<String> bodies = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            String body = query("sqs").formParam("Action", "ReceiveMessage").formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", "10").post("/").then().extract().asString();
            bodies.add(body);
            String all = String.join("", bodies);
            return all.contains("Schema Created") && all.contains("Schema Version Created");
        });

        // A stopped discoverer infers nothing.
        schemas().post("/v1/discoverers/id/" + discoverer + "/stop").then().statusCode(200)
                .body("State", equalTo("STOPPED"));
        events("PutEvents", "{\"Entries\":[" + event("{\"orderId\":\"o-3\",\"gift\":true}") + "]}").post("/")
                .then().statusCode(200);
        schemas().get("/v1/registries/name/discovered-schemas/schemas/name/my.shop@OrderPlaced").then()
                .body("SchemaVersion", equalTo("2"));

        // Tags, and deleting the registry once it's empty.
        schemas().body("{\"tags\":{\"env\":\"dev\"}}").post("/tags/" + registry).then().statusCode(204);
        schemas().get("/tags/" + registry).then().body("tags.env", equalTo("dev")).body("tags.team", equalTo("shop"));
        schemas().delete("/v1/registries/name/orders/schemas/name/Order/version/1").then().statusCode(204);
        schemas().delete("/v1/registries/name/orders/schemas/name/Order/version/2").then().statusCode(400);
        schemas().delete("/v1/registries/name/orders/schemas/name/Order").then().statusCode(204);
        schemas().delete("/v1/registries/name/orders").then().statusCode(204);
        schemas().delete("/v1/discoverers/id/" + discoverer).then().statusCode(204);
        schemas().get("/v1/discoverers/id/" + discoverer).then().statusCode(404);
    }

    static String unzip(byte[] zip) throws IOException {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                out.append(entry.getName()).append('\n').append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out.toString();
    }

    static String xml(RequestSpecification spec, String element) {
        String body = spec.post("/").then().statusCode(200).extract().asString();
        return body.replaceAll("(?s).*<" + element + ">(.*?)</" + element + ">.*", "$1");
    }
}

package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * IoT SiteWise over REST: asset models with transforms and metrics, assets in hierarchies, ingestion and the data
 * plane's queries, aliases and disassociated streams, gateways, SiteWise Monitor, bulk imports, settings and tags.
 */
@QuarkusTest
class SiteWiseIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ACCOUNT = "000000000000";

    @Inject
    IamService iam;

    @Inject
    S3Service s3;

    @Inject
    KmsService kms;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification sw() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/iotsitewise"
                + "/aws4_request").contentType("application/json");
    }

    static JsonNode json(ValidatableResponse response) {
        try {
            return MAPPER.readTree(response.extract().asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void awaitModel(String id) {
        await(() -> sw().get("/asset-models/" + id).then().statusCode(200).extract()
                .path("assetModelStatus.state"), "ACTIVE");
    }

    static void awaitAsset(String id) {
        await(() -> sw().get("/assets/" + id).then().statusCode(200).extract().path("assetStatus.state"), "ACTIVE");
    }

    static void await(Supplier<Object> probe, Object expected) {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(probe.get())) {
                return;
            }
            sleep(100);
        }
        fail("never reached " + expected);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String value(String entryId, String assetId, String propertyId, long seconds, double value) {
        return "{\"entryId\":\"" + entryId + "\",\"assetId\":\"" + assetId + "\",\"propertyId\":\"" + propertyId
                + "\",\"propertyValues\":[{\"value\":{\"doubleValue\":" + value + "},\"timestamp\":{\"timeInSeconds\":"
                + seconds + "},\"quality\":\"GOOD\"}]}";
    }

    static String turbineModel(String name) {
        return "{\"assetModelName\":\"" + name + "\",\"assetModelDescription\":\"wind turbine\","
                + "\"assetModelProperties\":["
                + "{\"name\":\"Serial\",\"dataType\":\"STRING\",\"type\":{\"attribute\":{\"defaultValue\":\"S-1\"}}},"
                + "{\"name\":\"Temperature\",\"dataType\":\"DOUBLE\",\"unit\":\"Celsius\",\"type\":{\"measurement\":{}}},"
                + "{\"name\":\"Fahrenheit\",\"dataType\":\"DOUBLE\",\"type\":{\"transform\":{\"expression\":"
                + "\"c * 9 / 5 + 32\",\"variables\":[{\"name\":\"c\",\"value\":{\"propertyId\":\"Temperature\"}}]}}},"
                + "{\"name\":\"Band\",\"dataType\":\"STRING\",\"type\":{\"transform\":{\"expression\":"
                + "\"if(c < 20, 'cool', 'warm')\",\"variables\":[{\"name\":\"c\",\"value\":{\"propertyId\":"
                + "\"Temperature\"}}]}}},"
                + "{\"name\":\"Max\",\"dataType\":\"DOUBLE\",\"type\":{\"metric\":{\"expression\":\"max(c)\","
                + "\"variables\":[{\"name\":\"c\",\"value\":{\"propertyId\":\"Temperature\"}}],"
                + "\"window\":{\"tumbling\":{\"interval\":\"1m\"}}}}}]}";
    }

    static String propertyId(JsonNode model, String name) {
        for (JsonNode property : model.get("assetModelProperties")) {
            if (name.equals(property.get("name").asText())) {
                return property.get("id").asText();
            }
        }
        throw new IllegalStateException("no property " + name);
    }

    // ---------------------------------------------------------------- models and assets

    @Test
    void modelsCheckTheirDefinitions() {
        sw().body("{\"assetModelName\":\"bad-expression\",\"assetModelProperties\":[{\"name\":\"T\",\"dataType\":"
                + "\"DOUBLE\",\"type\":{\"transform\":{\"expression\":\"x +\",\"variables\":[]}}}]}")
                .post("/asset-models").then().statusCode(400).header("x-amzn-ErrorType", "InvalidRequestException")
                .body("message", containsString("isn't valid"));
        sw().body("{\"assetModelName\":\"bad-variable\",\"assetModelProperties\":[{\"name\":\"T\",\"dataType\":"
                + "\"DOUBLE\",\"type\":{\"transform\":{\"expression\":\"x + 1\",\"variables\":[{\"name\":\"x\","
                + "\"value\":{\"propertyId\":\"Nope\"}}]}}}]}")
                .post("/asset-models").then().statusCode(400).body("message", containsString("Nope"));
        sw().body("{\"assetModelName\":\"cycle\",\"assetModelProperties\":["
                + "{\"name\":\"A\",\"dataType\":\"DOUBLE\",\"type\":{\"transform\":{\"expression\":\"b\","
                + "\"variables\":[{\"name\":\"b\",\"value\":{\"propertyId\":\"B\"}}]}}},"
                + "{\"name\":\"B\",\"dataType\":\"DOUBLE\",\"type\":{\"transform\":{\"expression\":\"a\","
                + "\"variables\":[{\"name\":\"a\",\"value\":{\"propertyId\":\"A\"}}]}}}]}")
                .post("/asset-models").then().statusCode(400).body("message", containsString("depends on itself"));
        sw().body("{\"assetModelName\":\"agg-transform\",\"assetModelProperties\":["
                + "{\"name\":\"M\",\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}},"
                + "{\"name\":\"A\",\"dataType\":\"DOUBLE\",\"type\":{\"transform\":{\"expression\":\"avg(m)\","
                + "\"variables\":[{\"name\":\"m\",\"value\":{\"propertyId\":\"M\"}}]}}}]}")
                .post("/asset-models").then().statusCode(400).body("message", containsString("aggregation"));
        sw().body("{\"assetModelName\":\"bad-window\",\"assetModelProperties\":["
                + "{\"name\":\"M\",\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}},"
                + "{\"name\":\"A\",\"dataType\":\"DOUBLE\",\"type\":{\"metric\":{\"expression\":\"avg(m)\","
                + "\"variables\":[{\"name\":\"m\",\"value\":{\"propertyId\":\"M\"}}],"
                + "\"window\":{\"tumbling\":{\"interval\":\"30s\"}}}}}]}")
                .post("/asset-models").then().statusCode(400);
        sw().body("{\"assetModelName\":\"bad-default\",\"assetModelProperties\":[{\"name\":\"N\",\"dataType\":"
                + "\"INTEGER\",\"type\":{\"attribute\":{\"defaultValue\":\"x\"}}}]}")
                .post("/asset-models").then().statusCode(400).body("message", containsString("INTEGER"));
        sw().body("{\"assetModelName\":\"missing-name\"}").post("/asset-models").then().statusCode(202);
        sw().body("{\"assetModelName\":\"missing-name\"}").post("/asset-models").then().statusCode(409)
                .header("x-amzn-ErrorType", "ResourceAlreadyExistsException");
    }

    @Test
    void assetsComputeTransformsAndAnswerTheDataPlane() {
        JsonNode created = json(sw().body(turbineModel("turbine-data")).post("/asset-models").then().statusCode(202)
                .body("assetModelStatus.state", equalTo("CREATING")));
        String modelId = created.get("assetModelId").asText();
        assertEquals("arn:aws:iotsitewise:us-east-1:" + ACCOUNT + ":asset-model/" + modelId,
                created.get("assetModelArn").asText());
        awaitModel(modelId);
        JsonNode model = json(sw().get("/asset-models/" + modelId).then().statusCode(200)
                .body("assetModelVersion", equalTo("1")).header("ETag", notNullValue()));
        String temperature = propertyId(model, "Temperature");
        String fahrenheit = propertyId(model, "Fahrenheit");
        String band = propertyId(model, "Band");
        String serial = propertyId(model, "Serial");
        // A transform's variable written by name is stored by ID.
        assertEquals(temperature, model.at("/assetModelProperties/2/type/transform/variables/0/value/propertyId")
                .asText());

        String assetId = sw().body("{\"assetName\":\"turbine-1\",\"assetModelId\":\"" + modelId
                + "\",\"tags\":{\"site\":\"north\"}}").post("/assets").then().statusCode(202)
                .body("assetStatus.state", equalTo("CREATING")).extract().path("assetId");
        sw().body("{\"assetName\":\"too-early\",\"assetModelId\":\"" + modelId + "\"}").post("/assets").then()
                .statusCode(202);
        awaitAsset(assetId);
        JsonNode asset = json(sw().get("/assets/" + assetId).then().statusCode(200));
        assertEquals(5, asset.get("assetProperties").size());
        assertEquals("$aws/sitewise/asset-models/" + modelId + "/assets/" + assetId + "/properties/" + temperature,
                asset.at("/assetProperties/1/notification/topic").asText());
        sw().get("/assets/" + assetId + "?excludeProperties=true").then().statusCode(200)
                .body("assetProperties", empty());

        // The attribute's default is its first value.
        sw().get("/properties/latest?assetId=" + assetId + "&propertyId=" + serial).then().statusCode(200)
                .body("propertyValue.value.stringValue", equalTo("S-1"));

        long now = Instant.now().getEpochSecond();
        JsonNode put = json(sw().body("{\"entries\":["
                + value("a", assetId, temperature, now - 120, 10.0) + ","
                + value("b", assetId, temperature, now - 60, 30.0) + ","
                + value("c", assetId, temperature, now - 30 * 86400, 1.0) + ","
                + value("d", assetId, fahrenheit, now, 1.0) + "]}")
                .post("/properties").then().statusCode(200));
        assertEquals(2, put.get("errorEntries").size());
        assertEquals("TimestampOutOfRangeException", put.at("/errorEntries/0/errors/0/errorCode").asText());
        assertEquals("InvalidRequestException", put.at("/errorEntries/1/errors/0/errorCode").asText());

        sw().get("/properties/latest?assetId=" + assetId + "&propertyId=" + temperature).then().statusCode(200)
                .body("propertyValue.value.doubleValue", equalTo(30.0f))
                .body("propertyValue.timestamp.timeInSeconds", equalTo((int) (now - 60)));
        sw().get("/properties/latest?assetId=" + assetId + "&propertyId=" + fahrenheit).then().statusCode(200)
                .body("propertyValue.value.doubleValue", equalTo(86.0f));
        sw().get("/properties/history?assetId=" + assetId + "&propertyId=" + band + "&timeOrdering=ASCENDING")
                .then().statusCode(200).body("assetPropertyValueHistory.value.stringValue",
                        equalTo(List.of("cool", "warm")));
        sw().get("/properties/history?assetId=" + assetId + "&propertyId=" + temperature
                + "&timeOrdering=DESCENDING&maxResults=1").then().statusCode(200)
                .body("assetPropertyValueHistory", hasSize(1))
                .body("assetPropertyValueHistory[0].value.doubleValue", equalTo(30.0f))
                .body("nextToken", notNullValue());

        String start = Instant.ofEpochSecond(now - 3600).toString();
        String end = Instant.ofEpochSecond(now + 60).toString();
        JsonNode aggregates = json(sw().get("/properties/aggregates?assetId=" + assetId + "&propertyId=" + temperature
                + "&aggregateTypes=AVERAGE&aggregateTypes=COUNT&aggregateTypes=MAXIMUM&resolution=1h&startDate="
                + start + "&endDate=" + end).then().statusCode(200));
        double count = 0;
        double max = 0;
        for (JsonNode aggregate : aggregates.get("aggregatedValues")) {
            count += aggregate.at("/value/count").asDouble();
            max = Math.max(max, aggregate.at("/value/maximum").asDouble());
        }
        assertEquals(2.0, count);
        assertEquals(30.0, max);

        JsonNode interpolated = json(sw().get("/properties/interpolated?assetId=" + assetId + "&propertyId="
                + temperature + "&startTimeInSeconds=" + (now - 120) + "&endTimeInSeconds=" + (now - 60)
                + "&quality=GOOD&intervalInSeconds=30&type=LINEAR_INTERPOLATION").then().statusCode(200));
        assertEquals(3, interpolated.get("interpolatedAssetPropertyValues").size());
        assertEquals(20.0, interpolated.at("/interpolatedAssetPropertyValues/1/value/doubleValue").asDouble(), 1e-9);

        JsonNode batch = json(sw().body("{\"entries\":[{\"entryId\":\"t\",\"assetId\":\"" + assetId
                + "\",\"propertyId\":\"" + fahrenheit + "\"},{\"entryId\":\"missing\",\"assetId\":\""
                + "11111111-1111-4111-8111-111111111111\",\"propertyId\":\"" + temperature + "\"}]}")
                .post("/properties/batch/latest").then().statusCode(200));
        assertEquals(86.0, batch.at("/successEntries/0/assetPropertyValue/value/doubleValue").asDouble());
        assertEquals("ResourceNotFoundException", batch.at("/errorEntries/0/errorCode").asText());

        sw().get("/assets/" + assetId + "/properties").then().statusCode(200)
                .body("assetPropertySummaries", hasSize(5));
        sw().get("/assets/" + assetId + "/properties/" + temperature).then().statusCode(200)
                .body("assetProperty.unit", equalTo("Celsius"))
                .body("assetProperty.type.measurement", notNullValue());
        sw().get("/tags?resourceArn=arn:aws:iotsitewise:us-east-1:" + ACCOUNT + ":asset/" + assetId).then()
                .statusCode(200).body("tags.site", equalTo("north"));
        sw().get("/timeseries/describe/?assetId=" + assetId + "&propertyId=" + temperature).then().statusCode(200)
                .body("timeSeriesId", equalTo(assetId + "_" + temperature)).body("dataType", equalTo("DOUBLE"));

        // A model with assets can't be deleted; an asset is deleted, then the model.
        sw().delete("/asset-models/" + modelId).then().statusCode(409)
                .header("x-amzn-ErrorType", "ConflictingOperationException");
        for (Object id : sw().get("/assets?assetModelId=" + modelId).then().statusCode(200).extract()
                .jsonPath().getList("assetSummaries.id")) {
            awaitAsset((String) id);
            sw().delete("/assets/" + id).then().statusCode(202).body("assetStatus.state", equalTo("DELETING"));
            await(() -> sw().get("/assets/" + id).then().extract().statusCode(), 404);
        }
        sw().delete("/asset-models/" + modelId).then().statusCode(202);
        await(() -> sw().get("/asset-models/" + modelId).then().extract().statusCode(), 404);
    }

    @Test
    void modelUpdatesPropagateAndKeepVersions() {
        String modelId = sw().body("{\"assetModelName\":\"pump\",\"assetModelProperties\":[{\"name\":\"Flow\","
                + "\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}}]}").post("/asset-models").then()
                .statusCode(202).extract().path("assetModelId");
        awaitModel(modelId);
        ValidatableResponse described = sw().get("/asset-models/" + modelId).then().statusCode(200);
        String eTag = described.extract().header("ETag");
        JsonNode model = json(described);
        String flow = propertyId(model, "Flow");
        String assetId = sw().body("{\"assetName\":\"pump-1\",\"assetModelId\":\"" + modelId + "\"}").post("/assets")
                .then().statusCode(202).extract().path("assetId");
        awaitAsset(assetId);
        sw().body("{\"propertyAlias\":\"/plant/pump-1/flow\",\"propertyNotificationState\":\"ENABLED\"}")
                .put("/assets/" + assetId + "/properties/" + flow).then().statusCode(200);
        awaitAsset(assetId);

        sw().header("If-Match", "A".repeat(43)).body("{\"assetModelName\":\"pump\"}").put("/asset-models/" + modelId).then()
                .statusCode(412).header("x-amzn-ErrorType", "PreconditionFailedException");
        sw().header("If-Match", eTag).body("{\"assetModelName\":\"pump\",\"assetModelProperties\":["
                + "{\"id\":\"" + flow + "\",\"name\":\"Flow\",\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}},"
                + "{\"name\":\"Pressure\",\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}}]}")
                .put("/asset-models/" + modelId).then().statusCode(202)
                .body("assetModelStatus.state", equalTo("UPDATING"));
        // Until the update is done, the active version is the one before it.
        sw().get("/asset-models/" + modelId + "?assetModelVersion=ACTIVE").then().statusCode(200)
                .body("assetModelProperties", hasSize(1));
        sw().put("/asset-models/" + modelId).then().statusCode(400);
        awaitModel(modelId);
        sw().get("/asset-models/" + modelId).then().statusCode(200).body("assetModelVersion", equalTo("2"))
                .body("assetModelProperties", hasSize(2));
        sw().get("/asset-models/" + modelId + "?assetModelVersion=ACTIVE").then().statusCode(200)
                .body("assetModelProperties", hasSize(2));
        JsonNode asset = json(sw().get("/assets/" + assetId).then().statusCode(200));
        assertEquals(2, asset.get("assetProperties").size());
        assertEquals("/plant/pump-1/flow", asset.at("/assetProperties/0/alias").asText());
        assertEquals("ENABLED", asset.at("/assetProperties/0/notification/state").asText());

        // Values sent to the alias reach the property.
        long now = Instant.now().getEpochSecond();
        sw().body("{\"entries\":[{\"entryId\":\"1\",\"propertyAlias\":\"/plant/pump-1/flow\",\"propertyValues\":"
                + "[{\"value\":{\"doubleValue\":4.5},\"timestamp\":{\"timeInSeconds\":" + now + "}}]}]}")
                .post("/properties").then().statusCode(200).body("errorEntries", empty());
        sw().get("/properties/latest?propertyAlias=/plant/pump-1/flow").then().statusCode(200)
                .body("propertyValue.value.doubleValue", equalTo(4.5f));
        sw().get("/properties/latest?assetId=" + assetId + "&propertyId=" + flow).then().statusCode(200)
                .body("propertyValue.value.doubleValue", equalTo(4.5f));
    }

    @Test
    void disassociatedStreamsAreAssociatedWithTheirHistory() {
        long now = Instant.now().getEpochSecond();
        sw().body("{\"entries\":[{\"entryId\":\"1\",\"propertyAlias\":\"/plant/loose/rpm\",\"propertyValues\":["
                + "{\"value\":{\"integerValue\":1200},\"timestamp\":{\"timeInSeconds\":" + (now - 10) + "}},"
                + "{\"value\":{\"integerValue\":1300},\"timestamp\":{\"timeInSeconds\":" + now + "}}]}]}")
                .post("/properties").then().statusCode(200).body("errorEntries", empty());
        String id = sw().get("/timeseries/describe/?alias=/plant/loose/rpm").then().statusCode(200)
                .body("dataType", equalTo("INTEGER")).extract().path("timeSeriesId");
        sw().get("/timeseries/?timeSeriesType=DISASSOCIATED").then().statusCode(200)
                .body("TimeSeriesSummaries.timeSeriesId", hasItem(id));
        // A value of another type is refused.
        sw().body("{\"entries\":[{\"entryId\":\"x\",\"propertyAlias\":\"/plant/loose/rpm\",\"propertyValues\":["
                + "{\"value\":{\"stringValue\":\"fast\"},\"timestamp\":{\"timeInSeconds\":" + now + "}}]}]}")
                .post("/properties").then().statusCode(200)
                .body("errorEntries[0].errors[0].errorCode", equalTo("InvalidRequestException"));

        String modelId = sw().body("{\"assetModelName\":\"motor\",\"assetModelProperties\":[{\"name\":\"Rpm\","
                + "\"dataType\":\"INTEGER\",\"type\":{\"measurement\":{}}}]}").post("/asset-models").then()
                .statusCode(202).extract().path("assetModelId");
        awaitModel(modelId);
        String rpm = propertyId(json(sw().get("/asset-models/" + modelId).then()), "Rpm");
        String assetId = sw().body("{\"assetName\":\"motor-1\",\"assetModelId\":\"" + modelId + "\"}")
                .post("/assets").then().statusCode(202).extract().path("assetId");
        awaitAsset(assetId);
        sw().body("{\"propertyAlias\":\"/plant/loose/rpm\"}").put("/assets/" + assetId + "/properties/" + rpm).then()
                .statusCode(409);
        sw().post("/timeseries/associate/?alias=/plant/loose/rpm&assetId=" + assetId + "&propertyId=" + rpm).then()
                .statusCode(200);
        sw().get("/properties/history?assetId=" + assetId + "&propertyId=" + rpm).then().statusCode(200)
                .body("assetPropertyValueHistory.value.integerValue", equalTo(List.of(1200, 1300)));
        sw().get("/timeseries/describe/?alias=/plant/loose/rpm").then().statusCode(200)
                .body("assetId", equalTo(assetId));
        sw().post("/timeseries/disassociate/?alias=/plant/loose/rpm&assetId=" + assetId + "&propertyId=" + rpm)
                .then().statusCode(200);
        sw().get("/timeseries/describe/?alias=/plant/loose/rpm").then().statusCode(200)
                .body("assetId", equalTo(null));
        sw().post("/timeseries/delete/?alias=/plant/loose/rpm").then().statusCode(200);
        sw().get("/timeseries/describe/?alias=/plant/loose/rpm").then().statusCode(404);
    }

    @Test
    void hierarchiesAssociateChildrenAndMetricsAggregateThem() throws Exception {
        String sensorModel = sw().body("{\"assetModelName\":\"sensor\",\"assetModelProperties\":[{\"name\":\"Power\","
                + "\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}}]}").post("/asset-models").then()
                .statusCode(202).extract().path("assetModelId");
        awaitModel(sensorModel);
        String power = propertyId(json(sw().get("/asset-models/" + sensorModel).then()), "Power");
        String farmModel = sw().body("{\"assetModelName\":\"farm\",\"assetModelHierarchies\":[{\"name\":\"Sensors\","
                + "\"childAssetModelId\":\"" + sensorModel + "\"}],\"assetModelProperties\":[{\"name\":\"Total\","
                + "\"dataType\":\"DOUBLE\",\"type\":{\"metric\":{\"expression\":\"sum(p)\",\"variables\":[{\"name\":"
                + "\"p\",\"value\":{\"propertyId\":\"" + power + "\",\"hierarchyId\":\"Sensors\"}}],"
                + "\"window\":{\"tumbling\":{\"interval\":\"1m\"}}}}}]}").post("/asset-models").then()
                .statusCode(202).extract().path("assetModelId");
        awaitModel(farmModel);
        JsonNode farmDescribed = json(sw().get("/asset-models/" + farmModel).then());
        String hierarchy = farmDescribed.at("/assetModelHierarchies/0/id").asText();
        String total = propertyId(farmDescribed, "Total");
        assertEquals(hierarchy, farmDescribed.at("/assetModelProperties/0/type/metric/variables/0/value/hierarchyId")
                .asText());

        String farm = sw().body("{\"assetName\":\"farm-1\",\"assetModelId\":\"" + farmModel + "\"}").post("/assets")
                .then().statusCode(202).extract().path("assetId");
        String a = sw().body("{\"assetName\":\"sensor-a\",\"assetModelId\":\"" + sensorModel + "\"}").post("/assets")
                .then().statusCode(202).extract().path("assetId");
        String b = sw().body("{\"assetName\":\"sensor-b\",\"assetModelId\":\"" + sensorModel + "\"}").post("/assets")
                .then().statusCode(202).extract().path("assetId");
        awaitAsset(farm);
        awaitAsset(a);
        awaitAsset(b);
        sw().body("{\"hierarchyId\":\"" + hierarchy + "\",\"childAssetId\":\"" + a + "\"}")
                .post("/assets/" + farm + "/associate").then().statusCode(200);
        sw().body("{\"hierarchyId\":\"" + hierarchy + "\",\"childAssetId\":\"" + b + "\"}")
                .post("/assets/" + farm + "/associate").then().statusCode(200);
        sw().body("{\"hierarchyId\":\"" + hierarchy + "\",\"childAssetId\":\"" + farm + "\"}")
                .post("/assets/" + a + "/associate").then().statusCode(400);
        sw().get("/assets/" + farm + "/hierarchies?hierarchyId=" + hierarchy).then().statusCode(200)
                .body("assetSummaries", hasSize(2));
        sw().get("/assets/" + a + "/hierarchies?traversalDirection=PARENT").then().statusCode(200)
                .body("assetSummaries[0].id", equalTo(farm));
        sw().get("/assets/" + a + "/assetRelationships?traversalType=PATH_TO_ROOT").then().statusCode(200)
                .body("assetRelationshipSummaries[0].hierarchyInfo.parentAssetId", equalTo(farm))
                .body("assetRelationshipSummaries[1].hierarchyInfo.childAssetId", equalTo(farm));
        sw().get("/assets?filter=TOP_LEVEL").then().statusCode(200).body("assetSummaries.id", hasItem(farm));
        sw().delete("/assets/" + farm).then().statusCode(409);
        sw().delete("/assets/" + a).then().statusCode(409);

        // The metric sums every child's power over each minute, at the minute's end.
        long now = Instant.now().getEpochSecond();
        sw().body("{\"entries\":[" + value("a", a, power, now, 2.0) + "," + value("b", b, power, now, 3.0) + "]}")
                .post("/properties").then().statusCode(200).body("errorEntries", empty());
        long windowEnd = (now / 60 + 1) * 60;
        for (int i = 0; i < 150; i++) {
            JsonNode latest = json(sw().get("/properties/latest?assetId=" + farm + "&propertyId=" + total).then()
                    .statusCode(200));
            if (latest.has("propertyValue")) {
                assertEquals(5.0, latest.at("/propertyValue/value/doubleValue").asDouble());
                assertEquals(windowEnd, latest.at("/propertyValue/timestamp/timeInSeconds").asLong());
                break;
            }
            if (i == 149) {
                fail("the metric was never computed");
            }
            Thread.sleep(500);
        }

        sw().body("{\"hierarchyId\":\"" + hierarchy + "\",\"childAssetId\":\"" + a + "\"}")
                .post("/assets/" + farm + "/disassociate").then().statusCode(200);
        sw().get("/assets/" + farm + "/hierarchies?hierarchyId=" + hierarchy).then().statusCode(200)
                .body("assetSummaries", hasSize(1));
    }

    @Test
    void compositeAndComponentModels() {
        String component = sw().body("{\"assetModelName\":\"bearing\",\"assetModelType\":\"COMPONENT_MODEL\","
                + "\"assetModelProperties\":[{\"name\":\"Vibration\",\"dataType\":\"DOUBLE\",\"type\":"
                + "{\"measurement\":{}}}]}").post("/asset-models").then().statusCode(202).extract().path("assetModelId");
        awaitModel(component);
        String model = sw().body("{\"assetModelName\":\"gearbox\"}").post("/asset-models").then().statusCode(202)
                .extract().path("assetModelId");
        awaitModel(model);
        JsonNode composite = json(sw().body("{\"assetModelCompositeModelName\":\"Front bearing\","
                + "\"assetModelCompositeModelType\":\"CUSTOM\",\"composedAssetModelId\":\"" + component + "\"}")
                .post("/asset-models/" + model + "/composite-models").then().statusCode(202));
        String compositeId = composite.get("assetModelCompositeModelId").asText();
        assertEquals("Front bearing", composite.at("/assetModelCompositeModelPath/1/name").asText());
        awaitModel(model);
        sw().get("/asset-models/" + model + "/composite-models/" + compositeId).then().statusCode(200)
                .body("assetModelCompositeModelProperties[0].name", equalTo("Vibration"))
                .body("compositionDetails.compositionRelationship[0].id", equalTo(component));
        sw().get("/asset-models/" + component + "/composition-relationships").then().statusCode(200)
                .body("compositionRelationshipSummaries[0].assetModelId", equalTo(model));
        sw().get("/asset-models/" + model + "/properties?filter=ALL").then().statusCode(200)
                .body("assetModelPropertySummaries[0].assetModelCompositeModelId", equalTo(compositeId));
        sw().body("{\"assetName\":\"nope\",\"assetModelId\":\"" + component + "\"}").post("/assets").then()
                .statusCode(400);
        String asset = sw().body("{\"assetName\":\"gearbox-1\",\"assetModelId\":\"" + model + "\"}").post("/assets")
                .then().statusCode(202).extract().path("assetId");
        awaitAsset(asset);
        sw().get("/assets/" + asset + "/composite-models/" + compositeId).then().statusCode(200)
                .body("assetCompositeModelProperties[0].name", equalTo("Vibration"))
                .body("assetCompositeModelPath[0].id", equalTo(asset));
        sw().delete("/asset-models/" + component).then().statusCode(409);
    }

    // ---------------------------------------------------------------- gateways and monitor

    @Test
    void gatewaysAndTheirCapabilities() {
        String id = sw().body("{\"gatewayName\":\"edge\",\"gatewayPlatform\":{\"greengrassV2\":{"
                + "\"coreDeviceThingName\":\"core-1\"}},\"gatewayVersion\":\"3\"}").post("/20200301/gateways").then()
                .statusCode(201).extract().path("gatewayId");
        sw().body("{\"gatewayName\":\"edge-2\",\"gatewayPlatform\":{\"greengrassV2\":{"
                + "\"coreDeviceThingName\":\"core-1\"}}}").post("/20200301/gateways").then().statusCode(409);
        sw().body("{\"capabilityNamespace\":\"iotsitewise:opcuacollector:3\",\"capabilityConfiguration\":"
                + "\"{\\\"sources\\\":[]}\"}").post("/20200301/gateways/" + id + "/capability").then()
                .statusCode(201).body("capabilitySyncStatus", equalTo("OUT_OF_SYNC"));
        sw().body("{\"capabilityNamespace\":\"iotsitewise:publisher:2\",\"capabilityConfiguration\":\"{\"}")
                .post("/20200301/gateways/" + id + "/capability").then().statusCode(400);
        sw().get("/20200301/gateways/" + id + "/capability/iotsitewise%3Aopcuacollector%3A3").then().statusCode(200)
                .body("capabilityConfiguration", equalTo("{\"sources\":[]}"));
        sw().get("/20200301/gateways/" + id).then().statusCode(200)
                .body("gatewayCapabilitySummaries[0].capabilityNamespace", equalTo("iotsitewise:opcuacollector:3"));
        sw().body("{\"gatewayName\":\"edge-renamed\"}").put("/20200301/gateways/" + id).then().statusCode(200);
        sw().get("/20200301/gateways").then().statusCode(200).body("gatewaySummaries.gatewayName",
                hasItem("edge-renamed"));
        sw().delete("/20200301/gateways/" + id).then().statusCode(200);
        sw().get("/20200301/gateways/" + id).then().statusCode(404);
    }

    @Test
    void portalsProjectsDashboardsAndAccessPolicies() {
        String role = iam.createRole("sitewise-portal", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                + "\"Allow\",\"Principal\":{\"Service\":\"monitor.iotsitewise.amazonaws.com\"},\"Action\":"
                + "\"sts:AssumeRole\"}]}", null, 3600, Map.of()).getArn();
        JsonNode portal = json(sw().body("{\"portalName\":\"ops\",\"portalContactEmail\":\"ops@example.com\","
                + "\"roleArn\":\"" + role + "\",\"portalAuthMode\":\"IAM\",\"portalLogoImageFile\":{\"type\":\"PNG\","
                + "\"data\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5E"
                + "rkJggg==\"}}").post("/portals").then().statusCode(202).body("portalStatus.state", equalTo("CREATING")));
        String portalId = portal.get("portalId").asText();
        await(() -> sw().get("/portals/" + portalId).then().statusCode(200).extract().path("portalStatus.state"),
                "ACTIVE");
        String logo = sw().get("/portals/" + portalId).then().extract().path("portalLogoImageLocation.url");
        given().get(logo.substring(logo.indexOf("/_floci"))).then().statusCode(200).contentType("image/png");

        String projectId = sw().body("{\"portalId\":\"" + portalId + "\",\"projectName\":\"line-1\"}")
                .post("/projects").then().statusCode(201).extract().path("projectId");
        String modelId = sw().body("{\"assetModelName\":\"press\",\"assetModelProperties\":[{\"name\":\"Force\","
                + "\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}}]}").post("/asset-models").then()
                .statusCode(202).extract().path("assetModelId");
        awaitModel(modelId);
        String force = propertyId(json(sw().get("/asset-models/" + modelId).then()), "Force");
        String assetId = sw().body("{\"assetName\":\"press-1\",\"assetModelId\":\"" + modelId + "\"}")
                .post("/assets").then().statusCode(202).extract().path("assetId");
        awaitAsset(assetId);
        sw().body("{\"assetIds\":[\"" + assetId + "\",\"11111111-1111-4111-8111-111111111111\"]}")
                .post("/projects/" + projectId + "/assets/associate").then().statusCode(200)
                .body("errors", hasSize(1));
        sw().get("/projects/" + projectId + "/assets").then().statusCode(200).body("assetIds", equalTo(
                List.of(assetId)));
        String definition = "{\\\"widgets\\\":[{\\\"type\\\":\\\"sc-line-chart\\\",\\\"title\\\":\\\"Force\\\","
                + "\\\"x\\\":0,\\\"y\\\":0,\\\"height\\\":3,\\\"width\\\":3,\\\"metrics\\\":[{\\\"type\\\":"
                + "\\\"iotsitewise\\\",\\\"assetId\\\":\\\"" + assetId + "\\\",\\\"propertyId\\\":\\\"" + force
                + "\\\"}]}]}";
        String dashboardId = sw().body("{\"projectId\":\"" + projectId + "\",\"dashboardName\":\"overview\","
                + "\"dashboardDefinition\":\"" + definition + "\"}").post("/dashboards").then().statusCode(201)
                .extract().path("dashboardId");
        sw().body("{\"projectId\":\"" + projectId + "\",\"dashboardName\":\"broken\",\"dashboardDefinition\":"
                + "\"{\\\"widgets\\\":[{\\\"type\\\":\\\"x\\\"}]}\"}").post("/dashboards").then().statusCode(400);
        sw().get("/dashboards?projectId=" + projectId).then().statusCode(200)
                .body("dashboardSummaries[0].id", equalTo(dashboardId));

        String policyId = sw().body("{\"accessPolicyIdentity\":{\"iamRole\":{\"arn\":\"" + role + "\"}},"
                + "\"accessPolicyResource\":{\"portal\":{\"id\":\"" + portalId + "\"}},\"accessPolicyPermission\":"
                + "\"ADMINISTRATOR\"}").post("/access-policies").then().statusCode(201).extract().path("accessPolicyId");
        sw().body("{\"accessPolicyIdentity\":{\"user\":{\"id\":\"u-1\"}},\"accessPolicyResource\":{\"portal\":"
                + "{\"id\":\"" + portalId + "\"}},\"accessPolicyPermission\":\"VIEWER\"}").post("/access-policies")
                .then().statusCode(400);
        sw().get("/access-policies?identityType=IAM&iamArn=" + role).then().statusCode(200)
                .body("accessPolicySummaries[0].id", equalTo(policyId));

        String page = given().get("/_floci/iotsitewise/portals/" + portalId).then().statusCode(200).extract()
                .asString();
        assertTrue(page.contains("line-1") && page.contains("overview") && page.contains("press-1"), page);

        sw().delete("/portals/" + portalId).then().statusCode(409);
        sw().delete("/projects/" + projectId).then().statusCode(409);
        sw().delete("/dashboards/" + dashboardId).then().statusCode(204);
        sw().delete("/projects/" + projectId).then().statusCode(204);
        sw().get("/access-policies/" + policyId).then().statusCode(200);
        sw().delete("/portals/" + portalId).then().statusCode(202);
        sw().get("/access-policies/" + policyId).then().statusCode(404);
        await(() -> sw().get("/portals/" + portalId).then().extract().statusCode(), 404);
    }

    // ---------------------------------------------------------------- imports and settings

    @Test
    void bulkImportsReadCsvAndReportRejectedRows() {
        s3.createBucket("sitewise-import", "us-east-1");
        String role = iam.createRole("sitewise-import", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null,
                3600, Map.of()).getArn();
        long now = Instant.now().getEpochSecond();
        String csv = "/import/flow," + (now - 100) + ",DOUBLE,1.5\n/import/flow," + (now - 50) + ",DOUBLE,2.5\n"
                + "/import/flow,abc,DOUBLE,3\n";
        s3.putObject("sitewise-import", "data/flow.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
        String jobId = sw().body("{\"jobName\":\"flow\",\"jobRoleArn\":\"" + role + "\",\"files\":[{\"bucket\":"
                + "\"sitewise-import\",\"key\":\"data/flow.csv\"}],\"errorReportLocation\":{\"bucket\":"
                + "\"sitewise-import\",\"prefix\":\"errors/\"},\"jobConfiguration\":{\"fileFormat\":{\"csv\":"
                + "{\"columnNames\":[\"ALIAS\",\"TIMESTAMP_SECONDS\",\"DATA_TYPE\",\"VALUE\"]}}}}")
                .post("/jobs").then().statusCode(202).body("jobStatus", equalTo("PENDING")).extract().path("jobId");
        await(() -> sw().get("/jobs/" + jobId).then().statusCode(200).extract().path("jobStatus"),
                "COMPLETED_WITH_FAILURES");
        sw().get("/properties/history?propertyAlias=/import/flow").then().statusCode(200)
                .body("assetPropertyValueHistory.value.doubleValue", equalTo(List.of(1.5f, 2.5f)));
        String report = new String(s3.getObject("sitewise-import", "errors/" + jobId + "/errors.csv").getData(),
                StandardCharsets.UTF_8);
        assertTrue(report.contains("abc"), report);
        sw().get("/jobs?filter=COMPLETED_WITH_FAILURES").then().statusCode(200).body("jobSummaries.id",
                hasItem(jobId));
    }

    @Test
    void settingsAndTags() {
        sw().get("/configuration/account/storage").then().statusCode(200)
                .body("storageType", equalTo("SITEWISE_DEFAULT_STORAGE"));
        s3.createBucket("sitewise-cold", "us-east-1");
        String role = iam.createRole("sitewise-cold", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null,
                3600, Map.of()).getArn();
        sw().body("{\"storageType\":\"MULTI_LAYER_STORAGE\",\"multiLayerStorage\":{\"customerManagedS3Storage\":"
                + "{\"s3ResourceArn\":\"arn:aws:s3:::missing-bucket/\",\"roleArn\":\"" + role + "\"}}}")
                .post("/configuration/account/storage").then().statusCode(400);

        long now = Instant.now().getEpochSecond();
        sw().body("{\"entries\":[{\"entryId\":\"1\",\"propertyAlias\":\"/cold/temp\",\"propertyValues\":["
                + "{\"value\":{\"doubleValue\":7.5},\"timestamp\":{\"timeInSeconds\":" + now + "}}]}]}")
                .post("/properties").then().statusCode(200);
        sw().body("{\"storageType\":\"MULTI_LAYER_STORAGE\",\"multiLayerStorage\":{\"customerManagedS3Storage\":"
                + "{\"s3ResourceArn\":\"arn:aws:s3:::sitewise-cold/tier/\",\"roleArn\":\"" + role + "\"}},"
                + "\"disassociatedDataStorage\":\"ENABLED\",\"retentionPeriod\":{\"numberOfDays\":30}}")
                .post("/configuration/account/storage").then().statusCode(200)
                .body("configurationStatus.state", equalTo("ACTIVE"));
        boolean raw = s3.listObjects("sitewise-cold", "tier/raw/", null, 1000).stream()
                .anyMatch(o -> o.getKey().endsWith("_GOOD.avro"));
        assertTrue(raw, "no raw cold tier file");
        sw().get("/configuration/account/storage").then().statusCode(200)
                .body("retentionPeriod.numberOfDays", equalTo(30));

        String key = kms.createKey("sitewise", "us-east-1").getKeyId();
        sw().body("{\"encryptionType\":\"KMS_BASED_ENCRYPTION\",\"kmsKeyId\":\"" + key + "\"}")
                .post("/configuration/account/encryption").then().statusCode(200)
                .body("kmsKeyArn", containsString(key));
        sw().body("{\"encryptionType\":\"KMS_BASED_ENCRYPTION\",\"kmsKeyId\":\"nope\"}")
                .post("/configuration/account/encryption").then().statusCode(400);
        sw().body("{\"loggingOptions\":{\"level\":\"ERROR\"}}").put("/logging").then().statusCode(200);
        sw().get("/logging").then().statusCode(200).body("loggingOptions.level", equalTo("ERROR"));

        String modelArn = sw().body("{\"assetModelName\":\"tagged\",\"tags\":{\"a\":\"1\"}}").post("/asset-models")
                .then().statusCode(202).extract().path("assetModelArn");
        sw().body("{\"tags\":{\"b\":\"2\"}}").post("/tags?resourceArn=" + modelArn).then().statusCode(200);
        sw().delete("/tags?resourceArn=" + modelArn + "&tagKeys=a").then().statusCode(200);
        sw().get("/tags?resourceArn=" + modelArn).then().statusCode(200).body("tags", equalTo(Map.of("b", "2")));
        sw().get("/tags?resourceArn=arn:aws:iotsitewise:us-east-1:" + ACCOUNT
                + ":asset/11111111-1111-4111-8111-111111111111").then().statusCode(404);
    }

    // ---------------------------------------------------------------- computation models

    @Test
    void anomalyDetectionTrainsAndScoresAnAsset() {
        String modelId = sw().body("{\"assetModelName\":\"anomaly-motor\",\"assetModelProperties\":["
                + "{\"name\":\"Current\",\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}},"
                + "{\"name\":\"Vibration\",\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}},"
                + "{\"name\":\"Anomaly\",\"dataType\":\"STRING\",\"type\":{\"measurement\":{}}}]}")
                .post("/asset-models").then().statusCode(202).extract().path("assetModelId");
        awaitModel(modelId);
        JsonNode model = json(sw().get("/asset-models/" + modelId).then());
        String current = propertyId(model, "Current");
        String vibration = propertyId(model, "Vibration");
        String anomaly = propertyId(model, "Anomaly");
        String assetId = sw().body("{\"assetName\":\"anomaly-motor-1\",\"assetModelId\":\"" + modelId + "\"}")
                .post("/assets").then().statusCode(202).extract().path("assetId");
        awaitAsset(assetId);
        long now = Instant.now().getEpochSecond() / 60 * 60;
        StringBuilder entries = new StringBuilder();
        int n = 0;
        for (long t = now - 1500; t <= now - 420; t += 60) {
            double wobble = (n++ % 3) * 0.1;
            entries.setLength(0);
            entries.append(value("c", assetId, current, t, 10 + wobble)).append(',')
                    .append(value("v", assetId, vibration, t, 2 + wobble));
            sw().body("{\"entries\":[" + entries + "]}").post("/properties").then().statusCode(200)
                    .body("errorEntries", empty());
        }

        String binding = "{\"input_properties\":{\"list\":[{\"assetModelProperty\":{\"assetModelId\":\"" + modelId
                + "\",\"propertyId\":\"" + current + "\"}},{\"assetModelProperty\":{\"assetModelId\":\"" + modelId
                + "\",\"propertyId\":\"" + vibration + "\"}}]},\"result_property\":{\"assetModelProperty\":"
                + "{\"assetModelId\":\"" + modelId + "\",\"propertyId\":\"" + anomaly + "\"}}}";
        String configuration = "{\"anomalyDetection\":{\"inputProperties\":\"${input_properties}\","
                + "\"resultProperty\":\"${result_property}\"}}";
        sw().body("{\"computationModelName\":\"bad\",\"computationModelConfiguration\":{\"anomalyDetection\":"
                + "{\"inputProperties\":\"${nope}\",\"resultProperty\":\"${result_property}\"}},"
                + "\"computationModelDataBinding\":" + binding + "}").post("/computation-models").then()
                .statusCode(400);
        String computationId = sw().body("{\"computationModelName\":\"motor-anomalies\","
                + "\"computationModelConfiguration\":" + configuration + ",\"computationModelDataBinding\":"
                + binding + "}").post("/computation-models").then().statusCode(202).extract()
                .path("computationModelId");
        await(() -> sw().get("/computation-models/" + computationId).then().statusCode(200).extract()
                .path("computationModelStatus.state"), "ACTIVE");
        JsonNode described = json(sw().get("/computation-models/" + computationId).then());
        String training = null;
        String inference = null;
        for (JsonNode definition : described.get("actionDefinitions")) {
            if ("AWS/ANOMALY_DETECTION_TRAINING".equals(definition.get("actionType").asText())) {
                training = definition.get("actionDefinitionId").asText();
            } else {
                inference = definition.get("actionDefinitionId").asText();
            }
        }
        sw().body("{\"dataBindingValueFilter\":{\"assetModel\":{\"assetModelId\":\"" + modelId + "\"}}}")
                .post("/computation-models/data-binding-usages").then().statusCode(200)
                .body("dataBindingUsageSummaries", hasSize(3));

        String payload = "{\\\"trainingMode\\\":\\\"TRAIN_MODEL\\\",\\\"exportDataStartTime\\\":" + (now - 1500)
                + ",\\\"exportDataEndTime\\\":" + (now - 420) + ",\\\"targetSamplingRate\\\":\\\"PT1M\\\"}";
        String actionId = sw().body("{\"targetResource\":{\"computationModelId\":\"" + computationId
                + "\"},\"resolveTo\":{\"assetId\":\"" + assetId + "\"},\"actionDefinitionId\":\"" + training
                + "\",\"actionPayload\":{\"stringValue\":\"" + payload + "\"}}").post("/actions").then()
                .statusCode(202).extract().path("actionId");
        sw().get("/actions/" + actionId).then().statusCode(200).body("actionDefinitionId", equalTo(training));
        JsonNode executions = json(sw().get("/executions?targetResourceType=COMPUTATION_MODEL&targetResourceId="
                + computationId + "&resolveToResourceType=ASSET&resolveToResourceId=" + assetId).then()
                .statusCode(200));
        String executionId = executions.at("/executionSummaries/0/executionId").asText();
        sw().get("/executions/" + executionId).then().statusCode(200)
                .body("executionStatus.state", equalTo("COMPLETED")).body("executionResult.modelVersion", equalTo("1"));
        sw().get("/computation-models/" + computationId + "/execution-summary?resolveToResourceType=ASSET"
                + "&resolveToResourceId=" + assetId).then().statusCode(200)
                .body("computationModelExecutionSummary.modelVersion", equalTo("1"));
        sw().get("/computation-models/" + computationId + "/resolve-to-resources").then().statusCode(200)
                .body("computationModelResolveToResourceSummaries[0].resolveTo.assetId", equalTo(assetId));

        // An excursion on both inputs, then inference: its first run scores the samples since the last window.
        sw().body("{\"entries\":[" + value("c", assetId, current, now - 120, 40.0) + ","
                + value("v", assetId, vibration, now - 120, 9.0) + "]}").post("/properties").then().statusCode(200);
        sw().body("{\"targetResource\":{\"computationModelId\":\"" + computationId + "\"},\"resolveTo\":"
                + "{\"assetId\":\"" + assetId + "\"},\"actionDefinitionId\":\"" + inference + "\",\"actionPayload\":"
                + "{\"stringValue\":\"{\\\"inferenceMode\\\":\\\"START\\\",\\\"dataUploadFrequency\\\":\\\"PT1M\\\"}"
                + "\"}}").post("/actions").then().statusCode(400);
        sw().body("{\"targetResource\":{\"computationModelId\":\"" + computationId + "\"},\"resolveTo\":"
                + "{\"assetId\":\"" + assetId + "\"},\"actionDefinitionId\":\"" + inference + "\",\"actionPayload\":"
                + "{\"stringValue\":\"{\\\"inferenceMode\\\":\\\"START\\\",\\\"dataUploadFrequency\\\":\\\"PT5M\\\"}"
                + "\"}}").post("/actions").then().statusCode(202);
        await(() -> {
            JsonNode latest = json(sw().get("/properties/latest?assetId=" + assetId + "&propertyId=" + anomaly)
                    .then().statusCode(200));
            return latest.has("propertyValue");
        }, true);
        JsonNode history = json(sw().get("/properties/history?assetId=" + assetId + "&propertyId=" + anomaly
                + "&maxResults=100").then().statusCode(200));
        boolean detected = false;
        for (JsonNode result : history.get("assetPropertyValueHistory")) {
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(result.at("/value/stringValue").asText());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            detected |= parsed.get("prediction").asInt() == 1
                    && "ANOMALY_DETECTED".equals(parsed.get("prediction_reason").asText())
                    && parsed.get("diagnostics").size() == 2;
        }
        assertTrue(detected, history.toString());
        sw().get("/computation-models/" + computationId + "/execution-summary?resolveToResourceType=ASSET"
                + "&resolveToResourceId=" + assetId).then().statusCode(200)
                .body("computationModelExecutionSummary.inferenceTimerActive", equalTo("TRUE"));
        sw().delete("/computation-models/" + computationId).then().statusCode(202);
        await(() -> sw().get("/computation-models/" + computationId).then().extract().statusCode(), 404);
    }

    @Test
    void l4eCompositeModelsOfferActions() {
        String modelId = sw().body("{\"assetModelName\":\"l4e-pump\",\"assetModelProperties\":[{\"name\":\"Flow\","
                + "\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}}],\"assetModelCompositeModels\":[{\"name\":"
                + "\"prediction\",\"type\":\"AWS/L4E_ANOMALY\",\"properties\":["
                + "{\"name\":\"AWS/L4E_ANOMALY_RESULT\",\"dataType\":\"STRUCT\",\"dataTypeSpec\":"
                + "\"AWS/L4E_ANOMALY_RESULT\",\"type\":{\"measurement\":{}}},"
                + "{\"name\":\"AWS/L4E_ANOMALY_INPUT\",\"dataType\":\"STRUCT\",\"dataTypeSpec\":"
                + "\"AWS/L4E_ANOMALY_INPUT\",\"type\":{\"attribute\":{}}}]}]}").post("/asset-models").then()
                .statusCode(202).extract().path("assetModelId");
        awaitModel(modelId);
        JsonNode model = json(sw().get("/asset-models/" + modelId).then());
        String composite = model.at("/assetModelCompositeModels/0/id").asText();
        JsonNode described = json(sw().get("/asset-models/" + modelId + "/composite-models/" + composite).then()
                .statusCode(200));
        assertEquals(3, described.get("actionDefinitions").size());
        String training = described.at("/actionDefinitions/0/actionDefinitionId").asText();
        String assetId = sw().body("{\"assetName\":\"l4e-pump-1\",\"assetModelId\":\"" + modelId + "\"}")
                .post("/assets").then().statusCode(202).extract().path("assetId");
        awaitAsset(assetId);
        sw().get("/assets/" + assetId + "/composite-models/" + composite).then().statusCode(200)
                .body("actionDefinitions[0].actionDefinitionId", equalTo(training));
        // Without inputs listed, training is refused.
        sw().body("{\"targetResource\":{\"assetId\":\"" + assetId + "\"},\"actionDefinitionId\":\"" + training
                + "\",\"actionPayload\":{\"stringValue\":\"{\\\"l4ETraining\\\":{\\\"trainingMode\\\":\\\"START\\\","
                + "\\\"exportDataStartTime\\\":1,\\\"exportDataEndTime\\\":2}}\"}}").post("/actions").then()
                .statusCode(400);
    }

    // ---------------------------------------------------------------- workspaces

    @Test
    void workspacesHoldTasksPipelinesDatasetsAndJobs() {
        String role = iam.createRole("sitewise-task", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null,
                3600, Map.of()).getArn();
        sw().body("{\"workspaceName\":\"plant\",\"encryptionConfiguration\":{\"encryptionType\":"
                + "\"SITEWISE_DEFAULT_ENCRYPTION\"}}").post("/workspaces").then().statusCode(200)
                .body("workspaceStatus.state", equalTo("CREATING"));
        await(() -> sw().get("/workspaces/plant").then().statusCode(200).extract().path("workspaceStatus.state"),
                "ACTIVE");
        sw().body("{\"workspaceName\":\"plant\",\"encryptionConfiguration\":{\"encryptionType\":"
                + "\"SITEWISE_DEFAULT_ENCRYPTION\"}}").post("/workspaces").then().statusCode(409);

        String task = "{\"workspaceName\":\"plant\",\"taskName\":\"clean\",\"taskConfiguration\":"
                + "{\"containerTaskConfiguration\":{\"ecrUri\":\"public.ecr.aws/docker/library/busybox:latest\","
                + "\"taskExecutionRole\":\"" + role + "\",\"processingType\":\"GENERIC_COMPUTE_PROCESSING\","
                + "\"processingUnit\":\"UNITS_2\",\"command\":[\"true\"]}}}";
        sw().body(task).post("/workspaces/plant/tasks").then().statusCode(200).body("version", equalTo("1"));
        sw().body("{\"description\":\"cleans\"}").put("/workspaces/plant/tasks/clean").then().statusCode(200)
                .body("version", equalTo("2"));
        sw().body("{\"pipelineName\":\"loop\",\"computations\":[{\"computeNodeName\":\"a\",\"taskName\":\"clean\","
                + "\"dependsOn\":[\"b\"]},{\"computeNodeName\":\"b\",\"taskName\":\"clean\",\"dependsOn\":[\"a\"]}]}")
                .post("/workspaces/plant/pipelines").then().statusCode(400).body("message", containsString("cycle"));
        sw().body("{\"pipelineName\":\"nightly\",\"computations\":[{\"computeNodeName\":\"first\",\"taskName\":"
                + "\"clean\"},{\"computeNodeName\":\"second\",\"taskName\":\"clean\",\"dependsOn\":[\"first\"]}]}")
                .post("/workspaces/plant/pipelines").then().statusCode(200);
        sw().get("/workspaces/plant/pipelines").then().statusCode(200)
                .body("pipelineSummaries[0].pipelineName", equalTo("nightly"));
        sw().delete("/workspaces/plant/tasks/clean").then().statusCode(409);

        // Datasets and their segments.
        long now = Instant.now().getEpochSecond();
        sw().body("{\"entries\":[{\"entryId\":\"1\",\"propertyAlias\":\"/plant/line/speed\",\"propertyValues\":["
                + "{\"value\":{\"doubleValue\":3.0},\"timestamp\":{\"timeInSeconds\":" + (now - 20) + "}},"
                + "{\"value\":{\"doubleValue\":9.0},\"timestamp\":{\"timeInSeconds\":" + (now - 10) + "}}]}]}")
                .post("/properties").then().statusCode(200);
        String series = sw().get("/timeseries/describe/?alias=/plant/line/speed").then().statusCode(200).extract()
                .path("timeSeriesId");
        String source = sw().body("{\"datasetName\":\"raw\",\"workspaceName\":\"plant\",\"datasetSource\":"
                + "{\"sourceType\":\"SITEWISE\",\"sourceFormat\":\"TIMESERIES\"}}").post("/datasets").then()
                .statusCode(202).extract().path("datasetId");
        String curated = sw().body("{\"datasetName\":\"curated\",\"workspaceName\":\"plant\",\"datasetSource\":"
                + "{\"sourceType\":\"SITEWISE\",\"sourceFormat\":\"TIMESERIES\"}}").post("/datasets").then()
                .statusCode(202).extract().path("datasetId");
        String segment = "{\"sourceDatasetId\":\"" + source + "\",\"timeSeriesId\":\"" + series + "\","
                + "\"startTimestamp\":{\"timeInSeconds\":" + (now - 30) + "},\"endTimestamp\":{\"timeInSeconds\":"
                + now + "}}";
        sw().body("{\"workspaceName\":\"plant\",\"associateDataSegmentEntries\":[" + segment + "]}")
                .post("/datasets/" + curated + "/data-segments/associate").then().statusCode(200)
                .body("failedAssociations", empty()).body("datasetVersion", equalTo("2"));
        sw().get("/datasets/" + curated + "/data-segments?workspaceName=plant").then().statusCode(200)
                .body("dataSegments[0].alias", equalTo("/plant/line/speed"))
                .body("dataSegments[0].dataType", equalTo("DOUBLE"));
        sw().get("/datasets?sourceType=SITEWISE&workspaceName=plant").then().statusCode(200)
                .body("datasetSummaries", hasSize(2));

        // Search, enrichment and export run in the background.
        String searchId = sw().body("{\"queryStatement\":\"line speed spikes\",\"searchType\":\"QUICK\"}")
                .post("/workspaces/plant/searches").then().statusCode(202).extract().path("searchId");
        await(() -> sw().get("/workspaces/plant/searches/" + searchId).then().statusCode(200).extract()
                .path("status"), "SUCCEEDED");
        sw().get("/workspaces/plant/searches/" + searchId + "/results").then().statusCode(200)
                .body("searchResults[0].timeSeriesId", equalTo(series))
                .body("searchResults[0].topTimestamp.timeInSeconds", equalTo((int) (now - 10)));
        String enrichment = sw().body("{\"jobConfiguration\":{\"eventDetection\":{\"datasetId\":\"" + curated
                + "\",\"trimSettings\":{\"startTime\":{\"timeInSeconds\":" + (now - 60) + "},\"endTime\":"
                + "{\"timeInSeconds\":" + now + "}}}}}").post("/workspaces/plant/enrichment-jobs").then()
                .statusCode(200).extract().path("jobId");
        await(() -> sw().get("/workspaces/plant/enrichment-jobs/" + enrichment).then().statusCode(200).extract()
                .path("status"), "COMPLETED");
        sw().get("/datasets/" + curated).then().statusCode(200)
                .body("enrichmentStatus.video.status", equalTo("FULLY_ENRICHED"));
        s3.createBucket("sitewise-export", "us-east-1");
        String export = sw().body("{\"destinationS3Uri\":\"s3://sitewise-export/out\",\"errorReportLocation\":"
                + "{\"s3Uri\":\"s3://sitewise-export/errors\"},\"input\":{\"dataset\":{\"datasetId\":\"" + curated
                + "\"}}}").post("/workspaces/plant/dataset-export-jobs").then().statusCode(202).extract().path("jobId");
        await(() -> sw().get("/workspaces/plant/dataset-export-jobs/" + export).then().statusCode(200).extract()
                .path("status"), "COMPLETED");
        String csv = new String(s3.getObject("sitewise-export", "out/" + export + "/" + series + ".csv").getData(),
                StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"9.0\""), csv);

        sw().body("{\"workspaceName\":\"plant\",\"disassociateDataSegmentEntries\":[" + segment + "]}")
                .post("/datasets/" + curated + "/data-segments/disassociate").then().statusCode(200)
                .body("failedDisassociations", empty());
        sw().delete("/workspaces/plant").then().statusCode(409);
        sw().delete("/workspaces/plant/pipelines/nightly").then().statusCode(200);
        sw().delete("/workspaces/plant/tasks/clean").then().statusCode(200);
        sw().delete("/workspaces/plant").then().statusCode(200);
        await(() -> sw().get("/workspaces/plant").then().extract().statusCode(), 404);
    }

    @Test
    void videoClipsAreImportedAndCaptured() {
        s3.createBucket("sitewise-video", "us-east-1");
        String role = iam.createRole("sitewise-video", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null,
                3600, Map.of()).getArn();
        byte[] mp4 = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p',
            '4', '1'};
        s3.putObject("sitewise-video", "clips/a.mp4", mp4, "video/mp4", Map.of());
        long now = Instant.now().getEpochSecond();
        String jobId = sw().body("{\"jobName\":\"clips\",\"jobRoleArn\":\"" + role + "\",\"files\":[{\"bucket\":"
                + "\"sitewise-video\",\"key\":\"clips/a.mp4\",\"alias\":\"/plant/camera\",\"startTime\":"
                + "{\"timeInSeconds\":" + (now - 60) + "},\"fileFormat\":{\"mp4\":{}}}],\"errorReportLocation\":"
                + "{\"bucket\":\"sitewise-video\",\"prefix\":\"errors/\"}}").post("/jobs").then().statusCode(202)
                .extract().path("jobId");
        await(() -> sw().get("/jobs/" + jobId).then().statusCode(200).extract().path("jobStatus"), "COMPLETED");
        String series = sw().get("/timeseries/describe/?alias=/plant/camera").then().statusCode(200)
                .body("dataType", equalTo("VIDEO")).extract().path("timeSeriesId");
        sw().body("{\"workspaceName\":\"cameras\",\"encryptionConfiguration\":{\"encryptionType\":"
                + "\"SITEWISE_DEFAULT_ENCRYPTION\"}}").post("/workspaces").then().statusCode(200);
        String data = sw().body("{\"timeSeriesId\":\"" + series + "\",\"startTime\":{\"timeInSeconds\":"
                + (now - 120) + "},\"endTime\":{\"timeInSeconds\":" + now + "}}")
                .post("/workspaces/cameras/get-capture-data").then().statusCode(200)
                .body("dataType", equalTo("VIDEO-MP4")).extract().path("data");
        assertEquals(Base64.getEncoder().encodeToString(mp4), data);
    }
}

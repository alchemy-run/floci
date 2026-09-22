package io.github.hectorvent.floci.services.appintegrations;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppIntegrationsIntegrationTest {

    private static final String EVENT_INTEGRATION_NAME = "floci-event-integration";

    private static String eventIntegrationArn;
    private static String dataIntegrationId;
    private static String dataIntegrationArn;

    @Test
    @Order(20)
    void signedApplicationLifecycleUsesTheAwsPathsAndSharedTags() {
        String request = applicationRequest("com.example.http", UUID.randomUUID().toString());
        ExtractableResponse<Response> created = signed("123456789012", "us-east-1").body(request)
                .post("/applications").then().statusCode(200)
                .body("Id", notNullValue()).body("Arn", containsString(":application/")).extract();
        String id = created.path("Id");
        String arn = created.path("Arn");
        String encoded = URLEncoder.encode(arn, StandardCharsets.UTF_8);
        try {
            signed("123456789012", "us-east-1").body(request).post("/applications")
                    .then().statusCode(200).body("Id", equalTo(id));
            signed("123456789012", "us-east-1").urlEncodingEnabled(false)
                    .get("/applications/" + encoded).then().statusCode(200)
                    .body("Id", equalTo(id)).body("Namespace", equalTo("com.example.http"))
                    .body("ApplicationSourceConfig.ExternalUrlConfig.AccessUrl", equalTo("https://example.com"))
                    .body("CreatedTime", notNullValue());
            signed("123456789012", "us-east-1").body("""
                    {"Description":"updated","Permissions":["User.Details.View"],
                     "ApplicationSourceConfig":{"ExternalUrlConfig":{"AccessUrl":"https://updated.example.com"}}}
                    """).patch("/applications/" + id).then().statusCode(200);
            signed("123456789012", "us-east-1").get("/applications/" + id).then().statusCode(200)
                    .body("Arn", equalTo(arn)).body("Description", equalTo("updated"))
                    .body("Permissions[0]", equalTo("User.Details.View"));
            signed("123456789012", "us-east-1").get("/applications/" + id + "/associations")
                    .then().statusCode(200).body("ApplicationAssociations.size()", equalTo(0));
            signed("123456789012", "us-east-1").body("{\"tags\":{\"env\":\"test\"}}")
                    .post("/tags/" + arn).then().statusCode(200);
            signed("123456789012", "us-east-1").get("/tags/" + arn)
                    .then().statusCode(200).body("tags.env", equalTo("test"));
            signed("123456789012", "us-east-1").queryParam("tagKeys", "env")
                    .delete("/tags/" + arn).then().statusCode(200);
            signed("123456789012", "us-east-1").get("/applications/" + id)
                    .then().statusCode(200).body("Tags.env", nullValue());
            signed("123456789012", "us-east-1").get("/applications")
                    .then().statusCode(200).body("Applications.Id", hasItem(id));
        } finally {
            signed("123456789012", "us-east-1").delete("/applications/" + id).then().statusCode(200);
        }
        signed("123456789012", "us-east-1").get("/applications/" + id)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        signed("123456789012", "us-east-1").get("/applications/" + id + "/associations")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(21)
    void applicationsAreAccountAndRegionScopedAndAppConfigKeepsItsRoute() {
        String request = applicationRequest("com.example.isolation", UUID.randomUUID().toString());
        String id = signed("123456789012", "us-east-1").body(request).post("/applications")
                .then().statusCode(200).extract().path("Id");
        try {
            signed("999999999999", "us-east-1").get("/applications/" + id)
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            signed("123456789012", "us-west-2").get("/applications/" + id)
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            String other = signed("999999999999", "us-east-1").body(request).post("/applications")
                    .then().statusCode(200).body("Arn", containsString(":999999999999:"))
                    .extract().path("Id");
            signed("999999999999", "us-east-1").delete("/applications/" + other).then().statusCode(200);
            String forged = "arn:aws:app-integrations:us-west-2:123456789012:application/" + id;
            signed("123456789012", "us-east-1").urlEncodingEnabled(false)
                    .get("/applications/" + URLEncoder.encode(forged, StandardCharsets.UTF_8))
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            signed("123456789012", "us-east-1").delete("/applications/" + id).then().statusCode(200);
        }
        String appConfigId = given().contentType("application/json")
                .header("Authorization", authorization("123456789012", "us-east-1", "appconfig"))
                .body("{\"Name\":\"appintegrations-route-regression\"}").post("/applications")
                .then().statusCode(201).extract().path("Id");
        given().header("Authorization", authorization("123456789012", "us-east-1", "appconfig"))
                .delete("/applications/" + appConfigId).then().statusCode(204);
        signed("123456789012", "us-east-1").get("/_appintegrations/applications")
                .then().statusCode(404).body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    @Order(22)
    void applicationPaginationPreservesQueriesThroughSignedRouting() {
        String first = signed("222222222222", "us-east-1")
                .body(applicationRequest("com.example.page.one", UUID.randomUUID().toString()))
                .post("/applications").then().statusCode(200).extract().path("Id");
        String second = signed("222222222222", "us-east-1")
                .body(applicationRequest("com.example.page.two", UUID.randomUUID().toString()))
                .post("/applications").then().statusCode(200).extract().path("Id");
        try {
            String next = signed("222222222222", "us-east-1").queryParam("maxResults", 1)
                    .queryParam("applicationType", "STANDARD").get("/applications")
                    .then().statusCode(200).body("Applications.size()", equalTo(1))
                    .body("NextToken", notNullValue()).extract().path("NextToken");
            signed("222222222222", "us-east-1").queryParam("maxResults", 1).queryParam("nextToken", next)
                    .get("/applications").then().statusCode(200).body("Applications.size()", equalTo(1))
                    .body("NextToken", nullValue());
            for (String invalid : new String[]{"0", "51", "not-a-number"}) {
                signed("222222222222", "us-east-1").queryParam("maxResults", invalid)
                        .get("/applications").then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
            }
            signed("222222222222", "us-east-1").queryParam("nextToken", "%%%")
                    .get("/applications").then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        } finally {
            signed("222222222222", "us-east-1").delete("/applications/" + first).then().statusCode(200);
            signed("222222222222", "us-east-1").delete("/applications/" + second).then().statusCode(200);
        }
    }

    @Test
    @Order(23)
    void dataAssociationWritesDenyOrdinaryCallersWithoutCreatingExecutionState() {
        String request = """
                {"Name":"association-contract","KmsKey":"key/abc","SourceURI":"s3://source","ClientToken":"%s"}
                """.formatted(UUID.randomUUID());
        ExtractableResponse<Response> created = signed("333333333333", "us-east-1").body(request)
                .post("/dataIntegrations").then().statusCode(200).extract();
        String id = created.path("Id");
        String arn = created.path("Arn");
        try {
            signed("333333333333", "us-east-1").body(request).post("/dataIntegrations")
                    .then().statusCode(200).body("Id", equalTo(id));
            signed("333333333333", "us-east-1").get("/dataIntegrations/" + id + "/associations")
                    .then().statusCode(200).body("DataIntegrationAssociations.size()", equalTo(0));
            signed("333333333333", "us-east-1").body("{\"ClientId\":\"client\"}")
                    .post("/dataIntegrations/" + id + "/associations")
                    .then().statusCode(403).body("__type", equalTo("AccessDeniedException"))
                    .body("message", containsString("app-integrations:CreateDataIntegrationAssociation on resource: " + arn))
                    .body("message", containsString("explicit deny in a resource-based policy"));
            signed("333333333333", "us-east-1")
                    .body("{\"ExecutionConfiguration\":{\"ExecutionMode\":\"ON_DEMAND\"}}")
                    .patch("/dataIntegrations/" + id + "/associations/00000000-0000-0000-0000-000000000001")
                    .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
            signed("333333333333", "us-east-1").get("/dataIntegrations/" + id + "/associations")
                    .then().statusCode(200).body("DataIntegrationAssociations.size()", equalTo(0));
            signed("444444444444", "us-east-1").get("/dataIntegrations/" + id + "/associations")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            signed("333333333333", "us-east-1").delete("/dataIntegrations/" + id).then().statusCode(200);
        }
    }

    private static RequestSpecification signed(String account, String region) {
        return given().contentType("application/json")
                .header("Authorization", authorization(account, region, "app-integrations"));
    }

    private static String authorization(String account, String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=test";
    }

    private static String applicationRequest(String namespace, String token) {
        return """
                {"Name":"workspace","Namespace":"%s","ClientToken":"%s",
                 "ApplicationSourceConfig":{"ExternalUrlConfig":{"AccessUrl":"https://example.com"}}}
                """.formatted(namespace, token);
    }

    @Test
    @Order(1)
    void createEventIntegration() {
        eventIntegrationArn = given()
            .contentType("application/json")
            .body("""
                {
                  "Name": "%s",
                  "Description": "partner events",
                  "EventBridgeBus": "floci-bus",
                  "EventFilter": {"Source": "aws.partner/example.com/1234"},
                  "Tags": {"team": "data"}
                }
                """.formatted(EVENT_INTEGRATION_NAME))
        .when()
            .post("/eventIntegrations")
        .then()
            .statusCode(200)
            .body("EventIntegrationArn", containsString(":app-integrations:"))
            .body("EventIntegrationArn", containsString(":event-integration/" + EVENT_INTEGRATION_NAME))
            .extract().path("EventIntegrationArn");
    }

    @Test
    @Order(2)
    void getEventIntegrationEchoesTheRequest() {
        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200)
            .body("Name", equalTo(EVENT_INTEGRATION_NAME))
            .body("Description", equalTo("partner events"))
            .body("EventIntegrationArn", equalTo(eventIntegrationArn))
            .body("EventBridgeBus", equalTo("floci-bus"))
            .body("EventFilter.Source", equalTo("aws.partner/example.com/1234"))
            .body("Tags.team", equalTo("data"));
    }

    @Test
    @Order(3)
    void listEventIntegrations() {
        given()
        .when()
            .get("/eventIntegrations")
        .then()
            .statusCode(200)
            .body("EventIntegrations.Name", hasItem(EVENT_INTEGRATION_NAME))
            .body("EventIntegrations.find { it.Name == '" + EVENT_INTEGRATION_NAME + "' }.EventBridgeBus",
                    equalTo("floci-bus"));
    }

    @Test
    @Order(4)
    void listEventIntegrationAssociationsIsEmptyForANewIntegration() {
        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME + "/associations")
        .then()
            .statusCode(200)
            .body("EventIntegrationAssociations.size()", equalTo(0));
    }

    @Test
    @Order(5)
    void listAssociationsForAMissingIntegrationReturnsResourceNotFound() {
        given()
        .when()
            .get("/eventIntegrations/no-such-integration/associations")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(6)
    void tagRoundTripOnTheEventIntegration() {
        given()
        .when()
            .get("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("data"));

        given()
            .contentType("application/json")
            .body("{\"tags\": {\"env\": \"test\"}}")
        .when()
            .post("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("test"));

        given()
            .queryParam("tagKeys", "env")
        .when()
            .delete("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.env", nullValue())
            .body("tags.team", equalTo("data"));
    }

    @Test
    @Order(7)
    void updateEventIntegration() {
        given()
            .contentType("application/json")
            .body("{\"Description\": \"partner events, revised\"}")
        .when()
            .patch("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200)
            .body("Description", equalTo("partner events, revised"))
            .body("EventBridgeBus", equalTo("floci-bus"))
            .body("EventFilter.Source", equalTo("aws.partner/example.com/1234"));

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .patch("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200)
            .body("Description", equalTo("partner events, revised"));
    }

    @Test
    @Order(8)
    void duplicateEventIntegrationNameReturnsDuplicateResource() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "Name": "%s",
                  "EventBridgeBus": "floci-bus",
                  "EventFilter": {"Source": "aws.partner/example.com/1234"}
                }
                """.formatted(EVENT_INTEGRATION_NAME))
        .when()
            .post("/eventIntegrations")
        .then()
            .statusCode(409)
            .body("__type", equalTo("DuplicateResourceException"));
    }

    @Test
    @Order(9)
    void createEventIntegrationWithoutEventFilterReturnsInvalidRequest() {
        given()
            .contentType("application/json")
            .body("{\"Name\": \"no-filter\", \"EventBridgeBus\": \"floci-bus\"}")
        .when()
            .post("/eventIntegrations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(10)
    void getMissingEventIntegrationReturnsResourceNotFound() {
        given()
        .when()
            .get("/eventIntegrations/no-such-integration")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(11)
    void createDataIntegration() {
        ExtractableResponse<Response> response = given()
            .contentType("application/json")
            .body("""
                {
                  "Name": "floci-data-integration",
                  "Description": "salesforce pull",
                  "KmsKey": "arn:aws:kms:us-east-1:000000000000:key/abc",
                  "SourceURI": "Salesforce://AppFlow/test",
                  "ScheduleConfig": {"ScheduleExpression": "rate(1 hour)", "FirstExecutionFrom": "1439788800000"},
                  "FileConfiguration": {"Folders": ["/home/data"]},
                  "Tags": {"team": "data"}
                }
                """)
        .when()
            .post("/dataIntegrations")
        .then()
            .statusCode(200)
            .body("Id", notNullValue())
            .body("Arn", containsString(":data-integration/"))
            .body("Name", equalTo("floci-data-integration"))
            .body("KmsKey", equalTo("arn:aws:kms:us-east-1:000000000000:key/abc"))
            .body("SourceURI", equalTo("Salesforce://AppFlow/test"))
            .body("ScheduleConfiguration.ScheduleExpression", equalTo("rate(1 hour)"))
            .body("FileConfiguration.Folders[0]", equalTo("/home/data"))
            .body("Tags.team", equalTo("data"))
            .extract();
        dataIntegrationId = response.path("Id");
        dataIntegrationArn = response.path("Arn");
    }

    @Test
    @Order(12)
    void getDataIntegrationByIdAndByArn() {
        given()
        .when()
            .get("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200)
            .body("Id", equalTo(dataIntegrationId))
            .body("Arn", equalTo(dataIntegrationArn))
            .body("Description", equalTo("salesforce pull"))
            .body("ScheduleConfiguration.FirstExecutionFrom", equalTo("1439788800000"));

        String encodedArn = URLEncoder.encode(dataIntegrationArn, StandardCharsets.UTF_8);
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/dataIntegrations/" + encodedArn)
        .then()
            .statusCode(200)
            .body("Id", equalTo(dataIntegrationId));
    }

    @Test
    @Order(13)
    void listDataIntegrations() {
        given()
        .when()
            .get("/dataIntegrations")
        .then()
            .statusCode(200)
            .body("DataIntegrations.Name", hasItem("floci-data-integration"))
            .body("DataIntegrations.find { it.Name == 'floci-data-integration' }.Arn",
                    equalTo(dataIntegrationArn))
            .body("DataIntegrations.find { it.Name == 'floci-data-integration' }.SourceURI",
                    equalTo("Salesforce://AppFlow/test"));
    }

    @Test
    @Order(14)
    void tagRoundTripOnTheDataIntegration() {
        given()
            .contentType("application/json")
            .body("{\"tags\": {\"tier\": \"gold\"}}")
        .when()
            .post("/tags/" + dataIntegrationArn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/" + dataIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.tier", equalTo("gold"))
            .body("tags.team", equalTo("data"));
    }

    @Test
    @Order(15)
    void updateDataIntegration() {
        given()
            .contentType("application/json")
            .body("{\"Description\": \"salesforce pull, revised\"}")
        .when()
            .patch("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200)
            .body("Description", equalTo("salesforce pull, revised"))
            .body("Name", equalTo("floci-data-integration"));
    }

    @Test
    @Order(16)
    void createDataIntegrationWithoutKmsKeyReturnsInvalidRequest() {
        given()
            .contentType("application/json")
            .body("{\"Name\": \"no-key\"}")
        .when()
            .post("/dataIntegrations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(17)
    void getMissingDataIntegrationReturnsResourceNotFound() {
        given()
        .when()
            .get("/dataIntegrations/00000000-0000-0000-0000-00000000dead")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(18)
    void deleteDataIntegration() {
        given()
        .when()
            .delete("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(19)
    void deleteEventIntegration() {
        given()
        .when()
            .delete("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .delete("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}

package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.detective.model.DetectiveState;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DetectiveGraphIntegrationTest {
    private static final String ACCOUNT = "720000000001";
    private static final String OTHER = "720000000002";
    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @Inject
    DetectiveService service;

    @Inject
    StorageFactory storageFactory;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void graphLifecycleUsesAwsRoutesAndRetainsIdentityWhileRetagging() {
        request(ACCOUNT, EAST).body("{}").post("/graphs/list").then().statusCode(200)
                .body("GraphList", hasSize(0));
        String arn = create(ACCOUNT, EAST);
        String createdTime = request(ACCOUNT, EAST).body("{\"MaxResults\":1}").post("/graphs/list")
                .then().statusCode(200).body("GraphList", hasSize(1))
                .body("GraphList[0].Arn", equalTo(arn)).extract().path("GraphList[0].CreatedTime");
        Instant.parse(createdTime);
        request(ACCOUNT, EAST).body("{}").post("/graph").then().statusCode(409)
                .body("__type", equalTo("ConflictException"));
        request(ACCOUNT, EAST).get("/tags/{arn}", arn).then().statusCode(200)
                .body("Tags.env", equalTo("test"));
        request(ACCOUNT, EAST).body("{\"Tags\":{\"env\":\"prod\",\"team\":\"security\"}}")
                .post("/tags/{arn}", arn).then().statusCode(200);
        request(ACCOUNT, EAST).queryParam("tagKeys", "team", "absent").delete("/tags/{arn}", arn)
                .then().statusCode(200);
        request(ACCOUNT, EAST).get("/tags/{arn}", arn).then().statusCode(200)
                .body("Tags", equalTo(Map.of("env", "prod")));
        request(ACCOUNT, EAST).body("{}").post("/graphs/list").then().statusCode(200)
                .body("GraphList[0].Arn", equalTo(arn)).body("GraphList[0].CreatedTime", equalTo(createdTime));
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "Accounts",
                        new Object[]{Map.of("AccountId", OTHER, "EmailAddress", "member@example.com")}))
                .post("/graph/members").then().statusCode(200);
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/graph/removal")
                .then().statusCode(200);
        assertMissing(ACCOUNT, EAST, arn);
        request(ACCOUNT, EAST).body("{}").post("/graphs/list").then().statusCode(200)
                .body("GraphList", hasSize(0));
        String replacement = create(ACCOUNT, EAST);
        assertNotEquals(arn, replacement);
        assertMissing(ACCOUNT, EAST, arn);
        request(ACCOUNT, EAST).body(Map.of("GraphArn", replacement)).post("/graph/members/list")
                .then().statusCode(200).body("MemberDetails", hasSize(0));
        request(ACCOUNT, EAST).get("/tags/{arn}", replacement).then().statusCode(200)
                .body("Tags", equalTo(Map.of("env", "test")));
    }

    @Test
    void foreignAndMissingGraphsCannotBeReadTaggedOrDeleted() {
        String arn = create(ACCOUNT, EAST);
        for (String[] scope : new String[][]{{OTHER, EAST}, {ACCOUNT, WEST}}) {
            request(scope[0], scope[1]).body("{}").post("/graphs/list").then().statusCode(200)
                    .body("GraphList", hasSize(0));
            assertMissing(scope[0], scope[1], arn);
            String otherArn = create(scope[0], scope[1]);
            assertNotEquals(arn, otherArn);
            assertMissing(scope[0], scope[1], arn);
            request(scope[0], scope[1]).body("{}").post("/graphs/list").then().statusCode(200)
                    .body("GraphList[0].Arn", equalTo(otherArn));
        }
        assertMissing(ACCOUNT, EAST, "arn:aws:detective:" + EAST + ":" + ACCOUNT
                + ":graph:ffffffffffffffffffffffffffffffff");
        request(ACCOUNT, EAST).get("/tags/{arn}", arn).then().statusCode(200)
                .body("Tags.env", equalTo("test"));
    }

    @Test
    void validationFailuresLeaveGraphAndTagsUnchanged() {
        for (String body : new String[]{"[]", "null", "{\"Tags\":[]}", "{\"Tags\":{\"env\":false}}",
                "{\"Tags\":{\"\":\"value\"}}"}) {
            request(ACCOUNT, EAST).body(body).post("/graph").then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
        request(ACCOUNT, EAST).body("{}").post("/graphs/list").then().statusCode(200)
                .body("GraphList", hasSize(0));
        String arn = create(ACCOUNT, EAST);
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            tags.put("key" + i, "value");
        }
        request(ACCOUNT, EAST).body(Map.of("Tags", tags)).post("/tags/{arn}", arn)
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
        request(ACCOUNT, EAST).body("{\"Tags\":{\"env\":null}}").post("/tags/{arn}", arn)
                .then().statusCode(400);
        request(ACCOUNT, EAST).delete("/tags/{arn}", arn).then().statusCode(400);
        request(ACCOUNT, EAST).get("/tags/{arn}", arn).then().statusCode(200)
                .body("Tags", equalTo(Map.of("env", "test")));
        for (String body : new String[]{"{}", "{\"GraphArn\":\"bad\"}"}) {
            request(ACCOUNT, EAST).body(body).post("/graph/removal").then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
        request(ACCOUNT, EAST).body("{\"NextToken\":\"invented\"}").post("/graphs/list")
                .then().statusCode(400);
    }

    @Test
    void memberLookupsAndInvitationsReadStoredMetadataWithScopeIsolation() {
        String arn = create(ACCOUNT, EAST);
        Map<String, Object> lookup = Map.of("GraphArn", arn, "AccountIds", new String[]{OTHER, "720000000003"});
        request(ACCOUNT, EAST).body(lookup).post("/graph/members/get").then().statusCode(200)
                .body("MemberDetails", hasSize(0)).body("UnprocessedAccounts", hasSize(2));
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "Accounts",
                        new Object[]{Map.of("AccountId", OTHER, "EmailAddress", "member@example.com")}))
                .post("/graph/members").then().statusCode(200).body("Members[0].Status", equalTo("INVITED"));
        request(ACCOUNT, EAST).body(lookup).post("/graph/members/get").then().statusCode(200)
                .body("MemberDetails", hasSize(1)).body("MemberDetails[0].AccountId", equalTo(OTHER))
                .body("UnprocessedAccounts[0].AccountId", equalTo("720000000003"));
        request(OTHER, EAST).body("{}").post("/invitations/list").then().statusCode(200)
                .body("Invitations", hasSize(1)).body("Invitations[0].GraphArn", equalTo(arn))
                .body("Invitations[0].AdministratorId", equalTo(ACCOUNT))
                .body("Invitations[0].InvitationType", equalTo("INVITATION"));
        for (String[] scope : new String[][]{{ACCOUNT, EAST}, {OTHER, WEST}, {"720000000003", EAST}}) {
            request(scope[0], scope[1]).body("{}").post("/invitations/list").then().statusCode(200)
                    .body("Invitations", hasSize(0));
        }
        for (String[] scope : new String[][]{{OTHER, EAST}, {ACCOUNT, WEST}}) {
            request(scope[0], scope[1]).body(lookup).post("/graph/members/get").then().statusCode(404);
            request(scope[0], scope[1]).body(Map.of("GraphArn", arn))
                    .post("/investigations/listInvestigations").then().statusCode(404);
        }
        for (Object accounts : new Object[]{new String[]{}, new String[]{"bad"}, new Object[]{42}, OTHER}) {
            request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "AccountIds", accounts))
                    .post("/graph/members/get").then().statusCode(400).body("__type", equalTo("ValidationException"));
        }
        request(OTHER, EAST).body("{\"NextToken\":\"bad\"}").post("/invitations/list").then().statusCode(400);
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/graph/removal").then().statusCode(200);
        request(OTHER, EAST).body("{}").post("/invitations/list").then().statusCode(200)
                .body("Invitations", hasSize(0));
    }

    @Test
    void freshGraphHasCoreDatasourceButCannotPretendToInvestigateOrEnableUnsupportedSources() {
        String arn = create(ACCOUNT, EAST);
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/investigations/listInvestigations")
                .then().statusCode(200).body("InvestigationDetails", hasSize(0));
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/graph/datasources/list")
                .then().statusCode(200).body("DatasourcePackages.DETECTIVE_CORE.DatasourcePackageIngestState", equalTo("STARTED"));
        for (String path : new String[]{"/graph/datasources/update", "/investigations/startInvestigation"}) {
            request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post(path)
                    .then().statusCode(400).body("__type", equalTo("ValidationException"))
                    .body("message", org.hamcrest.Matchers.containsString("Floci does not support Detective"));
        }
        for (String path : new String[]{"/investigations/getInvestigation", "/investigations/listIndicators",
                "/investigations/updateInvestigationState"}) {
            request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "InvestigationId", "00000000000000000000000000000000"))
                    .post(path).then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        }
        for (Object limit : new Object[]{0, 201, 1.5, 4294967297L}) {
            request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "MaxResults", limit))
                    .post("/investigations/listInvestigations").then().statusCode(400);
        }
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "NextToken", "1"))
                .post("/investigations/listInvestigations").then().statusCode(400);
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/investigations/listInvestigations")
                .then().statusCode(200).body("InvestigationDetails", hasSize(0));
    }

    @Test
    void datasourceCollectionPersistsActualManagementEventsAndExcludesOtherScopes() throws Exception {
        String parameter = "/detective/core-datasource";
        managementCall(ACCOUNT, EAST, "PutParameter", parameter);
        String arn = create(ACCOUNT, EAST);
        try {
            managementCall(ACCOUNT, EAST, "PutParameter", parameter);
            managementCall(OTHER, EAST, "PutParameter", parameter);
            managementCall(ACCOUNT, WEST, "PutParameter", parameter);
            String started = request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "MaxResults", 1))
                    .post("/graph/datasources/list").then().statusCode(200)
                    .body("DatasourcePackages.DETECTIVE_CORE.DatasourcePackageIngestState", equalTo("STARTED"))
                    .extract().path("DatasourcePackages.DETECTIVE_CORE.LastIngestStateChange.STARTED.Timestamp");
            Instant.parse(started);
            AccountAwareStorageBackend<DetectiveState> storage = storageFactory.create("detective", "detective-state.json",
                    new TypeReference<Map<String, DetectiveState>>() {});
            DetectiveState state = storage.getForAccount(ACCOUNT, EAST).orElseThrow();
            assertEquals(1, state.getCoreEvents().size());
            JsonNode event = objectMapper.readTree(state.getCoreEvents().values().iterator().next());
            assertEquals("PutParameter", event.path("eventName").asText());
            assertEquals(ACCOUNT, event.path("recipientAccountId").asText());
            assertEquals(EAST, event.path("awsRegion").asText());
            assertEquals(parameter, event.path("requestParameters").path("name").asText());
            assertFalse(event.toString().contains("private-value"));
            request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/graph/datasources/list")
                    .then().statusCode(200)
                    .body("DatasourcePackages.DETECTIVE_CORE.LastIngestStateChange.STARTED.Timestamp", equalTo(started));
            assertEquals(1, state.getCoreEvents().size());
            for (String[] scope : new String[][]{{OTHER, EAST}, {ACCOUNT, WEST}}) {
                request(scope[0], scope[1]).body(Map.of("GraphArn", arn)).post("/graph/datasources/list")
                        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            }
            for (Object limit : new Object[]{0, 201, 1.5, 4294967297L}) {
                request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "MaxResults", limit))
                        .post("/graph/datasources/list").then().statusCode(400);
            }
            request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "NextToken", "invented"))
                    .post("/graph/datasources/list").then().statusCode(400);
        } finally {
            request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/graph/removal").then().statusCode(200);
            managementCall(ACCOUNT, EAST, "DeleteParameter", parameter);
            managementCall(OTHER, EAST, "DeleteParameter", parameter);
            managementCall(ACCOUNT, WEST, "DeleteParameter", parameter);
        }
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn)).post("/graph/datasources/list")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        String replacement = create(ACCOUNT, EAST);
        try {
            request(ACCOUNT, EAST).body(Map.of("GraphArn", replacement)).post("/graph/datasources/list")
                    .then().statusCode(200);
            AccountAwareStorageBackend<DetectiveState> storage = storageFactory.create("detective", "detective-state.json",
                    new TypeReference<Map<String, DetectiveState>>() {});
            assertTrue(storage.getForAccount(ACCOUNT, EAST).orElseThrow().getCoreEvents().isEmpty());
        } finally {
            request(ACCOUNT, EAST).body(Map.of("GraphArn", replacement)).post("/graph/removal").then().statusCode(200);
        }
    }

    private static void managementCall(String account, String region, String operation, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Name", name);
        if ("PutParameter".equals(operation)) {
            body.put("Value", "private-value");
            body.put("Type", "String");
            body.put("Overwrite", true);
        }
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM." + operation)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260922/" + region
                        + "/ssm/aws4_request, SignedHeaders=host, Signature=abc")
                .body(body).post("/").then().statusCode(200);
    }

    private static void assertMissing(String account, String region, String arn) {
        request(account, region).get("/tags/{arn}", arn).then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        request(account, region).body("{\"Tags\":{\"env\":\"foreign\"}}")
                .post("/tags/{arn}", arn).then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        request(account, region).queryParam("tagKeys", "env").delete("/tags/{arn}", arn)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        request(account, region).body(Map.of("GraphArn", arn)).post("/graph/removal")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void datasourceHistoryComesFromGraphAndMembershipState() {
        String arn = create(ACCOUNT, EAST);
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "Accounts",
                        new Object[]{Map.of("AccountId", OTHER, "EmailAddress", "member@example.com")}))
                .post("/graph/members").then().statusCode(200);

        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "AccountIds",
                        new Object[]{ACCOUNT, OTHER, "123456789012"}))
                .post("/graph/datasources/get").then().statusCode(200)
                .body("MemberDatasources", hasSize(2))
                .body("MemberDatasources[0].AccountId", equalTo(ACCOUNT))
                .body("MemberDatasources[0].GraphArn", equalTo(arn))
                .body("MemberDatasources[0].DatasourcePackageIngestHistory.DETECTIVE_CORE.keySet()",
                        hasSize(1))
                .body("MemberDatasources[1].AccountId", equalTo(OTHER))
                .body("MemberDatasources[1].DatasourcePackageIngestHistory", equalTo(Map.of()))
                .body("UnprocessedAccounts", hasSize(1))
                .body("UnprocessedAccounts[0].AccountId", equalTo("123456789012"));
        request(ACCOUNT, EAST).body(Map.of("GraphArn", arn, "AccountIds", new Object[]{"bad"}))
                .post("/graph/datasources/get").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        request(OTHER, EAST).body(Map.of("GraphArn", arn, "AccountIds", new Object[]{OTHER}))
                .post("/graph/datasources/get").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        String unknown = "arn:aws:detective:us-east-1:720000000003:graph:" + "a".repeat(32);
        request(ACCOUNT, EAST).body(Map.of("GraphArns", new Object[]{arn, unknown}))
                .post("/membership/datasources/get").then().statusCode(200)
                .body("MembershipDatasources", hasSize(1))
                .body("MembershipDatasources[0].AccountId", equalTo(ACCOUNT))
                .body("UnprocessedGraphs", hasSize(1))
                .body("UnprocessedGraphs[0].GraphArn", equalTo(unknown));
        request(OTHER, WEST).body(Map.of("GraphArns", new Object[]{arn}))
                .post("/membership/datasources/get").then().statusCode(200)
                .body("MembershipDatasources", hasSize(1))
                .body("MembershipDatasources[0].AccountId", equalTo(OTHER))
                .body("MembershipDatasources[0].GraphArn", equalTo(arn))
                .body("UnprocessedGraphs", hasSize(0));
        request("720000000004", EAST).body(Map.of("GraphArns", new Object[]{arn}))
                .post("/membership/datasources/get").then().statusCode(200)
                .body("MembershipDatasources", hasSize(0))
                .body("UnprocessedGraphs", hasSize(1));
        request(ACCOUNT, EAST).body(Map.of("GraphArns", new Object[]{"not-an-arn"}))
                .post("/membership/datasources/get").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String create(String account, String region) {
        return request(account, region).body("{\"Tags\":{\"env\":\"test\"}}")
                .post("/graph").then().statusCode(200).extract().path("GraphArn");
    }

    private static RequestSpecification request(String account, String region) {
        return given().contentType("application/json").header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region
                        + "/detective/aws4_request, SignedHeaders=host, Signature=abc");
    }
}

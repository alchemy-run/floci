package io.github.hectorvent.floci.services.macie2;

import io.restassured.specification.RequestSpecification;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class MacieSessionIntegrationTest {
    private static final String ACCOUNT = "710000000001";
    private static final String OTHER = "710000000002";
    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @Inject
    MacieService service;

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void sessionLifecycleRetainsMetadataAndCleansUpMembers() {
        request(ACCOUNT, EAST).get("/macie").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        String createdAt = request(ACCOUNT, EAST).get("/macie").then().statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("findingPublishingFrequency", equalTo("SIX_HOURS"))
                .body("serviceRole", equalTo("arn:aws:iam::" + ACCOUNT
                        + ":role/aws-service-role/macie.amazonaws.com/AWSServiceRoleForAmazonMacie"))
                .extract().path("createdAt");
        Instant.parse(createdAt);
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(409);
        request(ACCOUNT, EAST).body(Map.of("account", Map.of("accountId", OTHER, "email", "member@example.com")))
                .post("/members").then().statusCode(200);
        request(ACCOUNT, EAST).queryParam("onlyAssociated", "false").get("/members")
                .then().statusCode(200).body("members", hasSize(1));
        request(ACCOUNT, EAST).body("{\"status\":\"PAUSED\",\"findingPublishingFrequency\":\"FIFTEEN_MINUTES\"}")
                .patch("/macie").then().statusCode(200);
        String updatedAt = request(ACCOUNT, EAST).get("/macie").then().statusCode(200)
                .body("status", equalTo("PAUSED"))
                .body("findingPublishingFrequency", equalTo("FIFTEEN_MINUTES"))
                .body("createdAt", equalTo(createdAt)).extract().path("updatedAt");
        assertFalse(Instant.parse(updatedAt).isBefore(Instant.parse(createdAt)));
        request(ACCOUNT, EAST).body("{\"status\":\"ENABLED\"}").patch("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).get("/macie").then().statusCode(200)
                .body("findingPublishingFrequency", equalTo("FIFTEEN_MINUTES"));
        request(ACCOUNT, EAST).delete("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).get("/macie").then().statusCode(404);
        request(ACCOUNT, EAST).delete("/macie").then().statusCode(404);
        request(ACCOUNT, EAST).body("{}").patch("/macie").then().statusCode(404);
        request(ACCOUNT, EAST).body("{\"status\":\"PAUSED\",\"findingPublishingFrequency\":\"ONE_HOUR\"}")
                .post("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).get("/macie").then().statusCode(200)
                .body("status", equalTo("PAUSED")).body("findingPublishingFrequency", equalTo("ONE_HOUR"));
        request(ACCOUNT, EAST).queryParam("onlyAssociated", "false").get("/members")
                .then().statusCode(200).body("members", hasSize(0));
    }

    @Test
    void sessionsAndMemberAssociationsAreIsolatedByAccountAndRegion() {
        request(ACCOUNT, EAST).body("{\"findingPublishingFrequency\":\"ONE_HOUR\"}")
                .post("/macie").then().statusCode(200);
        for (String[] scope : new String[][]{{OTHER, EAST}, {ACCOUNT, WEST}}) {
            request(scope[0], scope[1]).get("/macie").then().statusCode(404);
            request(scope[0], scope[1]).body("{\"status\":\"PAUSED\"}")
                    .patch("/macie").then().statusCode(404);
            request(scope[0], scope[1]).delete("/macie").then().statusCode(404);
            request(scope[0], scope[1]).body("{}").post("/macie").then().statusCode(200);
        }
        for (String[] scope : new String[][]{{ACCOUNT, EAST}, {OTHER, WEST}}) {
            if (WEST.equals(scope[1])) {
                request(scope[0], scope[1]).body("{}").post("/macie").then().statusCode(200);
            }
            request(scope[0], scope[1]).body(Map.of("account",
                            Map.of("accountId", "710000000003", "email", "member@example.com")))
                    .post("/members").then().statusCode(200);
        }
        request(ACCOUNT, EAST).get("/macie").then().statusCode(200)
                .body("status", equalTo("ENABLED")).body("findingPublishingFrequency", equalTo("ONE_HOUR"));
        request(ACCOUNT, EAST).delete("/macie").then().statusCode(200);
        request(OTHER, EAST).get("/macie").then().statusCode(200);
        request(ACCOUNT, WEST).get("/macie").then().statusCode(200);
        request(OTHER, WEST).queryParam("onlyAssociated", "false").get("/members")
                .then().statusCode(200).body("members", hasSize(1));
    }

    @Test
    void invalidSessionRequestsDoNotCreateOrMutateSessions() {
        for (String body : new String[]{"[]", "null", "{\"status\":false}", "{\"status\":\"DISABLED\"}",
                "{\"findingPublishingFrequency\":\"DAILY\"}", "{\"findingPublishingFrequency\":42}"}) {
            request(ACCOUNT, EAST).body(body).post("/macie").then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
        request(ACCOUNT, EAST).get("/macie").then().statusCode(404);
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).body("{\"status\":\"PAUSED\",\"findingPublishingFrequency\":\"BAD\"}")
                .patch("/macie").then().statusCode(400);
        request(ACCOUNT, EAST).get("/macie").then().statusCode(200)
                .body("status", equalTo("ENABLED")).body("findingPublishingFrequency", equalTo("SIX_HOURS"));
    }

    @Test
    void customIdentifierCountsMatchesAndHonorsContextWithoutPersistingDetections() {
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        Map<String, Object> input = Map.of("regex", "EMP-[0-9]{8}",
                "sampleText", "ids EMP-12345678 and EMP-87654321 but not EMP-123");
        request(ACCOUNT, EAST).body(input).post("/custom-data-identifiers/test")
                .then().statusCode(200).body("matchCount", equalTo(2));
        request(ACCOUNT, EAST).body(Map.of("regex", "EMP-[0-9]{8}", "sampleText", "no employee IDs"))
                .post("/custom-data-identifiers/test").then().statusCode(200).body("matchCount", equalTo(0));
        for (int distance : new int[]{12, 13}) {
            request(ACCOUNT, EAST).body(Map.of("regex", "EMP-[0-9]{8}", "sampleText", "Employee EMP-12345678",
                            "keywords", new String[]{"employee"}, "maximumMatchDistance", distance))
                    .post("/custom-data-identifiers/test").then().statusCode(200)
                    .body("matchCount", equalTo(distance == 13 ? 1 : 0));
        }
        request(ACCOUNT, EAST).body(Map.of("regex", "EMP-[0-9]{8}", "sampleText", "EMP-12345678 employee",
                        "keywords", new String[]{"employee"}))
                .post("/custom-data-identifiers/test").then().statusCode(200).body("matchCount", equalTo(0));
        for (String ignored : new String[]{"EMP-1234", "emp-1234"}) {
            request(ACCOUNT, EAST).body(Map.of("regex", "EMP-[0-9]{8}",
                            "sampleText", "EMP-12345678 EMP-87654321", "ignoreWords", new String[]{ignored}))
                    .post("/custom-data-identifiers/test").then().statusCode(200)
                    .body("matchCount", equalTo(ignored.startsWith("EMP") ? 1 : 2));
        }
        for (String[] scope : new String[][]{{OTHER, EAST}, {ACCOUNT, WEST}}) {
            request(scope[0], scope[1]).body(input).post("/custom-data-identifiers/test")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            request(scope[0], scope[1]).body("{}").post("/macie").then().statusCode(200);
            request(scope[0], scope[1]).body(input).post("/custom-data-identifiers/test")
                    .then().statusCode(200).body("matchCount", equalTo(2));
        }
        request(ACCOUNT, EAST).delete("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).body(input).post("/custom-data-identifiers/test").then().statusCode(404);
        request(OTHER, EAST).body(input).post("/custom-data-identifiers/test")
                .then().statusCode(200).body("matchCount", equalTo(2));
    }

    @Test
    void membershipReadsDoNotInventAdministratorsInvitationsOrAnalysis() {
        for (String path : new String[]{"/administrator", "/invitations", "/invitations/count"}) {
            request(ACCOUNT, EAST).get(path).then().statusCode(404);
        }
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).get("/administrator").then().statusCode(200).body(equalTo("{}"));
        request(ACCOUNT, EAST).get("/invitations").then().statusCode(200).body("invitations", hasSize(0));
        request(ACCOUNT, EAST).get("/invitations/count").then().statusCode(200).body("invitationsCount", equalTo(0));
        request(ACCOUNT, EAST).queryParam("maxResults", 0).get("/invitations").then().statusCode(400);
        request(ACCOUNT, EAST).queryParam("nextToken", "bad").get("/invitations").then().statusCode(400);
        request(ACCOUNT, EAST).body(Map.of("account", Map.of("accountId", OTHER, "email", "member@example.com")))
                .post("/members").then().statusCode(200);
        request(OTHER, EAST).body("{}").post("/macie").then().statusCode(200);
        request(OTHER, EAST).get("/invitations/count").then().statusCode(200).body("invitationsCount", equalTo(0));
        request(OTHER, EAST).get("/administrator").then().statusCode(200).body(equalTo("{}"));
        request(ACCOUNT, WEST).get("/invitations/count").then().statusCode(404);
        request(ACCOUNT, EAST).body("{}").post("/findings").then().statusCode(200)
                .body("findingIds", hasSize(0));
        request(ACCOUNT, EAST).body("{}").post("/jobs").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", org.hamcrest.Matchers.containsString("S3 reading and evaluation are not implemented"));
        request(ACCOUNT, EAST).body("{}").post("/jobs/list").then().statusCode(200)
                .body("items", hasSize(0));
    }

    @Test
    void invalidOrExcessivelyExpensiveIdentifierTestsFailExplicitly() {
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        for (Object input : new Object[]{Map.of(), Map.of("regex", "[", "sampleText", "test"),
                Map.of("regex", 42, "sampleText", "test"), Map.of("regex", "test", "sampleText", true),
                Map.of("regex", "test", "sampleText", "test", "keywords", "test"),
                Map.of("regex", "test", "sampleText", "test", "ignoreWords", new Object[]{false}),
                Map.of("regex", "test", "sampleText", "test", "keywords", new String[]{"ab"}),
                Map.of("regex", "test", "sampleText", "test", "maximumMatchDistance", 0),
                Map.of("regex", "test", "sampleText", "test", "maximumMatchDistance", 301),
                Map.of("regex", "test", "sampleText", "test", "maximumMatchDistance", 1.5),
                Map.of("regex", "test", "sampleText", "test", "maximumMatchDistance", 4294967297L),
                Map.of("regex", "x".repeat(513), "sampleText", "test"),
                Map.of("regex", "test", "sampleText", "x".repeat(1001)),
                Map.of("regex", "a*", "sampleText", "test"),
                Map.of("regex", "(a)", "sampleText", "a"),
                Map.of("regex", "(?=a)a", "sampleText", "a"),
                Map.of("regex", "(?:a+){10}$", "sampleText", "a".repeat(999) + "!")}) {
            request(ACCOUNT, EAST).body(input).post("/custom-data-identifiers/test")
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));
        }
        request(ACCOUNT, EAST).body(Map.of("regex", "test", "sampleText", "test"))
                .post("/custom-data-identifiers/test").then().statusCode(200).body("matchCount", equalTo(1));
    }

    @Test
    void managementResourcesRoundTripUpdatesTagsSoftDeletionAndSessionCleanup() {
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        String allowId = request(ACCOUNT, EAST).body(Map.of("name", "Tickets", "criteria",
                        Map.of("regex", "TICKET-[0-9]{6}"), "tags", Map.of("env", "test")))
                .post("/allow-lists").then().statusCode(200).extract().path("id");
        String allowArn = request(ACCOUNT, EAST).get("/allow-lists/" + allowId).then().statusCode(200)
                .body("criteria.regex", equalTo("TICKET-[0-9]{6}"))
                .body("status.code", equalTo("OK")).extract().path("arn");
        request(ACCOUNT, EAST).body(Map.of("name", "Tickets", "description", "updated",
                        "criteria", Map.of("regex", "TICKET-[0-9]{7}")))
                .put("/allow-lists/" + allowId).then().statusCode(200).body("id", equalTo(allowId));
        request(ACCOUNT, EAST).get("/allow-lists/" + allowId).then().statusCode(200)
                .body("description", equalTo("updated")).body("criteria.regex", equalTo("TICKET-[0-9]{7}"));
        String identifierId = request(ACCOUNT, EAST).body(Map.of("name", "Employees", "regex", "EMP-[0-9]{8}"))
                .post("/custom-data-identifiers").then().statusCode(200).extract().path("customDataIdentifierId");
        request(ACCOUNT, EAST).delete("/custom-data-identifiers/" + identifierId).then().statusCode(200);
        request(ACCOUNT, EAST).get("/custom-data-identifiers/" + identifierId).then().statusCode(200)
                .body("deleted", equalTo(true)).body("regex", equalTo("EMP-[0-9]{8}"));
        request(ACCOUNT, EAST).body("{}").post("/custom-data-identifiers/list").then().statusCode(200)
                .body("items", hasSize(0));
        request(ACCOUNT, EAST).body(Map.of("ids", new String[]{identifierId, "missing"}))
                .post("/custom-data-identifiers/get").then().statusCode(200)
                .body("customDataIdentifiers[0].deleted", equalTo(true))
                .body("notFoundIdentifierIds[0]", equalTo("missing"));
        String filterId = request(ACCOUNT, EAST).body(Map.of("name", "LowSeverity", "action", "ARCHIVE",
                        "findingCriteria", Map.of("criterion", Map.of("severity.description", Map.of("eq", new String[]{"Low"})))))
                .post("/findingsfilters").then().statusCode(200).extract().path("id");
        String filterArn = request(ACCOUNT, EAST).get("/findingsfilters/" + filterId).then().statusCode(200)
                .extract().path("arn");
        request(ACCOUNT, EAST).body(Map.of("action", "NOOP")).patch("/findingsfilters/" + filterId)
                .then().statusCode(200).body("id", equalTo(filterId));
        request(ACCOUNT, EAST).body(Map.of("tags", Map.of("phase", "two"))).post("/tags/" + filterArn)
                .then().statusCode(200);
        request(ACCOUNT, EAST).get("/findingsfilters/" + filterId).then().statusCode(200)
                .body("action", equalTo("NOOP")).body("tags.phase", equalTo("two"));
        request(ACCOUNT, EAST).queryParam("tagKeys", "env").delete("/tags/" + allowArn).then().statusCode(200);
        request(ACCOUNT, EAST).get("/tags/" + allowArn).then().statusCode(200).body("tags", equalTo(Map.of()));
        request(OTHER, EAST).body("{}").post("/macie").then().statusCode(200);
        request(OTHER, EAST).get("/allow-lists/" + allowId).then().statusCode(404);
        request(OTHER, EAST).get("/tags/" + allowArn).then().statusCode(404);
        request(ACCOUNT, WEST).body("{}").post("/macie").then().statusCode(200);
        request(ACCOUNT, WEST).get("/findingsfilters/" + filterId).then().statusCode(404);
        request(ACCOUNT, EAST).queryParam("ignoreJobChecks", "true").delete("/allow-lists/" + allowId)
                .then().statusCode(200);
        request(ACCOUNT, EAST).get("/allow-lists/" + allowId).then().statusCode(404);
        request(ACCOUNT, EAST).delete("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).get("/findingsfilters").then().statusCode(200)
                .body("findingsFilterListItems", hasSize(0));
        request(ACCOUNT, EAST).get("/custom-data-identifiers/" + identifierId).then().statusCode(404);
    }

    @Test
    void sampleFindingsAreExplicitSamplesAndDiscoveryHasNoInventedExecution() {
        request(ACCOUNT, EAST).body("{}").post("/macie").then().statusCode(200);
        request(ACCOUNT, EAST).body("{}").post("/findings/sample").then().statusCode(200);
        String id = request(ACCOUNT, EAST).body("{}").post("/findings").then().statusCode(200)
                .body("findingIds", hasSize(1)).extract().path("findingIds[0]");
        request(ACCOUNT, EAST).body(Map.of("findingIds", new String[]{id})).post("/findings/describe")
                .then().statusCode(200).body("findings[0].sample", equalTo(true))
                .body("findings[0].type", equalTo("Policy:IAMUser/S3BucketPublic"));
        request(ACCOUNT, EAST).body(Map.of("groupBy", "severity.description")).post("/findings/statistics")
                .then().statusCode(200).body("countsByGroup[0].groupKey", equalTo("High"))
                .body("countsByGroup[0].count", equalTo(1));
        request(ACCOUNT, EAST).get("/automated-discovery/configuration").then().statusCode(200)
                .body("status", equalTo("DISABLED"));
        request(ACCOUNT, EAST).get("/classification-scopes").then().statusCode(200)
                .body("classificationScopes", hasSize(1));
        request(ACCOUNT, EAST).get("/reveal-configuration").then().statusCode(200)
                .body("configuration.status", equalTo("DISABLED"));
        request(ACCOUNT, EAST).get("/classification-export-configuration").then().statusCode(200)
                .body("configuration", equalTo(Map.of()));
        request(ACCOUNT, EAST).get("/usage").then().statusCode(200).body("usageTotals", hasSize(0));
        request(ACCOUNT, EAST).body("{}").post("/managed-data-identifiers/list").then().statusCode(200)
                .body("items[0].id", equalTo("EMAIL_ADDRESS"));
        request(ACCOUNT, EAST).body("{}").post("/datasources/s3/statistics").then().statusCode(200)
                .body("bucketCount", equalTo(0));
        request(ACCOUNT, EAST).body("{}").post("/datasources/search-resources").then().statusCode(200)
                .body("matchingResources", hasSize(0));
    }

    private static RequestSpecification request(String account, String region) {
        return given().contentType("application/json").header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region
                        + "/macie2/aws4_request, SignedHeaders=host, Signature=abc");
    }
}

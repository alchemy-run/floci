package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.cloudtrail.CloudTrailManagementIntegrationTest.call;
import static io.github.hectorvent.floci.services.cloudtrail.CloudTrailManagementIntegrationTest.ct;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudTrailLakeIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String OTHER = "720000000002";
    @Inject CloudTrailLakeService lake;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void lifecyclePersistsMetadataAndIngestsOnlyActualSelectedCalls() {
        String parameter = "/cloudtrail/lake-ingestion";
        String name = "cloudtrail-lake-" + java.util.UUID.randomUUID();
        Map<String, Object> props = Map.of("Name", name, "MultiRegionEnabled", false, "RetentionPeriod", 7,
                "AdvancedEventSelectors", List.of(Map.of("FieldSelectors", List.of(
                        Map.of("Field", "eventCategory", "Equals", List.of("Management")),
                        Map.of("Field", "eventSource", "Equals", List.of("ssm.amazonaws.com")),
                        Map.of("Field", "eventName", "Equals", List.of("PutParameter"))))),
                "TagsList", List.of(Map.of("Key", "fixture", "Value", "lake")));
        String arn = ct(ACCOUNT, REGION, "CreateEventDataStore", props).statusCode(200)
                .body("Status", equalTo("ENABLED")).body("TerminationProtectionEnabled", equalTo(true))
                .extract().path("EventDataStoreArn");
        try {
            ct(ACCOUNT, REGION, "CreateEventDataStore", props).statusCode(400)
                    .body("__type", containsString("EventDataStoreAlreadyExistsException"));
            ct(OTHER, REGION, "GetEventDataStore", Map.of("EventDataStore", arn)).statusCode(400)
                    .body("__type", containsString("EventDataStoreNotFoundException"));
            ct(ACCOUNT, "us-west-2", "GetEventDataStore", Map.of("EventDataStore", arn)).statusCode(400)
                    .body("__type", containsString("EventDataStoreNotFoundException"));
            ct(OTHER, REGION, "ListEventDataStores", Map.of()).statusCode(200)
                    .body("EventDataStores.EventDataStoreArn", not(hasItem(arn)));
            ct(ACCOUNT, "us-west-2", "ListEventDataStores", Map.of()).statusCode(200)
                    .body("EventDataStores.EventDataStoreArn", not(hasItem(arn)));
            ct(OTHER, REGION, "ListTags", Map.of("ResourceIdList", List.of(arn))).statusCode(400)
                    .body("__type", containsString("ResourceNotFoundException"));
            ct(ACCOUNT, REGION, "DeleteEventDataStore", Map.of("EventDataStore", arn)).statusCode(400)
                    .body("__type", containsString("EventDataStoreTerminationProtectedException"));
            put(ACCOUNT, REGION, parameter);
            assertEquals(1, count(arn, parameter));
            put(OTHER, REGION, parameter);
            put(ACCOUNT, "us-west-2", parameter);
            assertEquals(1, count(arn, parameter));
            ct(ACCOUNT, REGION, "StopEventDataStoreIngestion", Map.of("EventDataStore", arn)).statusCode(200);
            put(ACCOUNT, REGION, parameter);
            assertEquals(1, count(arn, parameter));
            ct(ACCOUNT, REGION, "GetEventDataStore", Map.of("EventDataStore", arn)).statusCode(200)
                    .body("Status", equalTo("STOPPED_INGESTION"));
            ct(ACCOUNT, REGION, "UpdateEventDataStore", Map.of("EventDataStore", arn,
                    "MultiRegionEnabled", true, "RetentionPeriod", 14, "TerminationProtectionEnabled", false))
                    .statusCode(200).body("RetentionPeriod", equalTo(14));
            ct(ACCOUNT, REGION, "StartEventDataStoreIngestion", Map.of("EventDataStore", arn)).statusCode(200);
            put(ACCOUNT, "us-west-2", parameter);
            assertEquals(2, count(arn, parameter));
            ct(ACCOUNT, REGION, "AddTags", Map.of("ResourceId", arn, "TagsList", List.of(Map.of("Key", "team", "Value", "audit"))))
                    .statusCode(200);
            ct(ACCOUNT, REGION, "RemoveTags", Map.of("ResourceId", arn, "TagsList", List.of(Map.of("Key", "fixture"))))
                    .statusCode(200);
            ct(ACCOUNT, REGION, "ListTags", Map.of("ResourceIdList", List.of(arn))).statusCode(200)
                    .body("ResourceTagList[0].TagsList.Key", contains("team"));
            ct(ACCOUNT, REGION, "StartQuery", Map.of("QueryStatement", "SELECT * FROM " + arn.substring(arn.lastIndexOf('/') + 1)))
                    .statusCode(400).body("__type", containsString("UnsupportedOperationException"));
            ct(ACCOUNT, REGION, "DeleteEventDataStore", Map.of("EventDataStore", arn)).statusCode(200);
            ct(ACCOUNT, REGION, "GetEventDataStore", Map.of("EventDataStore", arn)).statusCode(200)
                    .body("Status", equalTo("PENDING_DELETION"));
            put(ACCOUNT, REGION, parameter);
            assertEquals(2, count(arn, parameter));
            ct(ACCOUNT, REGION, "DeleteEventDataStore", Map.of("EventDataStore", arn)).statusCode(400)
                    .body("__type", containsString("InactiveEventDataStoreException"));
            ct(ACCOUNT, REGION, "RestoreEventDataStore", Map.of("EventDataStore", arn)).statusCode(200)
                    .body("EventDataStoreArn", equalTo(arn)).body("Status", equalTo("STOPPED_INGESTION"));
            assertEquals(2, count(arn, parameter));
        } finally {
            ct(ACCOUNT, REGION, "UpdateEventDataStore", Map.of("EventDataStore", arn, "TerminationProtectionEnabled", false))
                    .statusCode(200);
            ct(ACCOUNT, REGION, "DeleteEventDataStore", Map.of("EventDataStore", arn)).statusCode(200);
            call(ACCOUNT, REGION, "ssm", "AmazonSSM.DeleteParameter", Map.of("Name", parameter)).statusCode(200);
            call(OTHER, REGION, "ssm", "AmazonSSM.DeleteParameter", Map.of("Name", parameter)).statusCode(200);
            call(ACCOUNT, "us-west-2", "ssm", "AmazonSSM.DeleteParameter", Map.of("Name", parameter)).statusCode(200);
        }
    }

    @Test
    void missingStoresAndUnsupportedRuntimeFeaturesNeverReportSuccess() {
        String missing = "arn:aws:cloudtrail:us-east-1:000000000000:eventdatastore/00000000-0000-0000-0000-000000000000";
        for (String operation : List.of("GetEventDataStore", "UpdateEventDataStore", "DeleteEventDataStore",
                "RestoreEventDataStore", "StartEventDataStoreIngestion", "StopEventDataStoreIngestion")) {
            ct(ACCOUNT, REGION, operation, Map.of("EventDataStore", missing)).statusCode(400)
                    .body("__type", containsString("EventDataStoreNotFoundException"));
        }
        ct(ACCOUNT, REGION, "GetEventDataStore", Map.of("EventDataStore", "arn:bad")).statusCode(400)
                .body("__type", containsString("EventDataStoreARNInvalidException"));
        ct(ACCOUNT, REGION, "CreateEventDataStore", Map.of("Name", "lake-invalid", "RetentionPeriod", 6)).statusCode(400)
                .body("__type", containsString("InvalidParameterException"));
        ct(ACCOUNT, REGION, "CreateEventDataStore", Map.of("Name", "lake-organization", "OrganizationEnabled", true)).statusCode(400)
                .body("__type", containsString("UnsupportedOperationException"));
        ct(ACCOUNT, REGION, "CreateEventDataStore", Map.of("Name", "lake-data", "AdvancedEventSelectors",
                List.of(Map.of("FieldSelectors", List.of(Map.of("Field", "eventCategory", "Equals", List.of("Data")))))))
                .statusCode(400).body("__type", containsString("UnsupportedOperationException"));
        ct(ACCOUNT, REGION, "ListEventDataStores", Map.of("NextToken", "not-a-token")).statusCode(400)
                .body("__type", containsString("InvalidNextTokenException"));
        ct(ACCOUNT, REGION, "ListEventDataStores", Map.of("MaxResults", 0)).statusCode(400)
                .body("__type", containsString("InvalidMaxResultsException"));
    }

    private long count(String arn, String parameter) {
        return lake.collectedEvents(arn, REGION).stream()
                .filter(e -> parameter.equals(e.path("requestParameters").path("name").asText())).count();
    }

    private static void put(String account, String region, String parameter) {
        call(account, region, "ssm", "AmazonSSM.PutParameter",
                Map.of("Name", parameter, "Type", "String", "Value", "secret", "Overwrite", true)).statusCode(200);
    }
}

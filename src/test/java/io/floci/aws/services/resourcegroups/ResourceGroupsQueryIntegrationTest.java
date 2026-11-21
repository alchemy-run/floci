package io.floci.aws.services.resourcegroups;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** Query groups, updates, tags, listing, configurations, search and the account-level operations. */
@QuarkusTest
class ResourceGroupsQueryIntegrationTest {
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261008/" + REGION + "/" + service + "/aws4_request";
    }

    private static ValidatableResponse call(String path, Object body) {
        return given().header("Authorization", auth("resource-groups"))
                .contentType("application/json").body(body).post(path).then();
    }

    private static String tagQuery(String key, String value) {
        return "{\"ResourceTypeFilters\":[\"AWS::AllSupported\"],\"TagFilters\":[{\"Key\":\"" + key
                + "\",\"Values\":[\"" + value + "\"]}]}";
    }

    private static String queue(String name, String tagKey, String tagValue) {
        String url = given().header("Authorization", auth("sqs")).header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .contentType("application/x-amz-json-1.0")
                .body("{\"QueueName\":\"" + name + "\",\"tags\":{\"" + tagKey + "\":\"" + tagValue + "\"}}")
                .post("/").then().statusCode(200).extract().path("QueueUrl");
        return given().header("Authorization", auth("sqs")).header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .contentType("application/x-amz-json-1.0")
                .body("{\"QueueUrl\":\"" + url + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .post("/").then().statusCode(200).extract().path("Attributes.QueueArn");
    }

    @Test
    void aTagQueryGroupListsWhatItsQuerySelectsAndFollowsUpdates() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "query-" + suffix;
        String tagKey = "rg-it-" + suffix;
        String first = queue("rg-it-a-" + suffix, tagKey, "v1");
        String second = queue("rg-it-b-" + suffix, tagKey, "v2");

        String arn = call("/groups", Map.of("Name", name, "Description", "first",
                "ResourceQuery", Map.of("Type", "TAG_FILTERS_1_0", "Query", tagQuery(tagKey, "v1")),
                "Tags", Map.of("team", "data"))).statusCode(200)
                .body("Group.Description", equalTo("first"))
                .body("ResourceQuery.Type", equalTo("TAG_FILTERS_1_0"))
                .body("Tags.team", equalTo("data"))
                .body("GroupConfiguration", nullValue())
                .extract().path("Group.GroupArn");
        try {
            call("/list-group-resources", Map.of("Group", name)).statusCode(200)
                    .body("Resources.Identifier.ResourceArn", contains(first))
                    .body("Resources[0].Identifier.ResourceType", equalTo("AWS::SQS::Queue"))
                    .body("QueryErrors", empty());
            call("/get-group-query", Map.of("Group", name)).statusCode(200)
                    .body("GroupQuery.GroupName", equalTo(name))
                    .body("GroupQuery.ResourceQuery.Query", equalTo(tagQuery(tagKey, "v1")));

            call("/update-group-query", Map.of("Group", name,
                    "ResourceQuery", Map.of("Type", "TAG_FILTERS_1_0", "Query", tagQuery(tagKey, "v2"))))
                    .statusCode(200).body("GroupQuery.ResourceQuery.Query", equalTo(tagQuery(tagKey, "v2")));
            call("/list-group-resources", Map.of("Group", arn)).statusCode(200)
                    .body("ResourceIdentifiers.ResourceArn", contains(second));

            // An omitted member keeps its value; Description "" clears it.
            call("/update-group", Map.of("Group", name, "Description", "second")).statusCode(200)
                    .body("Group.Description", equalTo("second"));
            call("/update-group", Map.of("Group", name, "Criticality", 3)).statusCode(200)
                    .body("Group.Description", equalTo("second")).body("Group.Criticality", equalTo(3));

            // Tags through the group's own tag routes.
            String encoded = arn.replace(":", "%3A").replace("/", "%2F");
            given().header("Authorization", auth("resource-groups")).contentType("application/json")
                    .body(Map.of("Tags", Map.of("env", "test"))).urlEncodingEnabled(false)
                    .put("/resources/" + encoded + "/tags").then().statusCode(200)
                    .body("Arn", equalTo(arn)).body("Tags.env", equalTo("test"));
            given().header("Authorization", auth("resource-groups")).contentType("application/json")
                    .body(Map.of("Keys", List.of("team"))).urlEncodingEnabled(false)
                    .patch("/resources/" + encoded + "/tags").then().statusCode(200).body("Keys", contains("team"));
            given().header("Authorization", auth("resource-groups")).urlEncodingEnabled(false)
                    .get("/resources/" + encoded + "/tags").then().statusCode(200)
                    .body("Tags.env", equalTo("test")).body("Tags.team", nullValue());

            // Listing and filters.
            call("/groups-list", Map.of()).statusCode(200)
                    .body("GroupIdentifiers.GroupName", hasItem(name)).body("Groups.Name", hasItem(name));
            call("/groups-list", Map.of("Filters", List.of(Map.of("Name", "criticality", "Values", List.of("3")))))
                    .statusCode(200).body("GroupIdentifiers.GroupName", contains(name));

            // A query group has no configuration and takes no members.
            call("/get-group-configuration", Map.of("Group", name)).statusCode(200)
                    .body("GroupConfiguration.Configuration", nullValue());
            call("/group-resources", Map.of("Group", name, "ResourceArns", List.of(first))).statusCode(400)
                    .body("__type", equalTo("BadRequestException"));

            // Search runs the same query without a group.
            call("/resources/search", Map.of("ResourceQuery",
                    Map.of("Type", "TAG_FILTERS_1_0", "Query", tagQuery(tagKey, "v1")))).statusCode(200)
                    .body("ResourceIdentifiers.ResourceArn", contains(first));
            call("/resources/search", Map.of("ResourceQuery",
                    Map.of("Type", "TAG_FILTERS_1_0", "Query", "not json"))).statusCode(400);

            // Application-group-only operations.
            call("/list-grouping-statuses", Map.of("Group", name)).statusCode(400)
                    .body("message", containsString("application group"));
            call("/list-grouping-statuses", Map.of("Group", "missing-" + suffix)).statusCode(404)
                    .body("__type", equalTo("NotFoundException"));
            call("/start-tag-sync-task", Map.of("Group", name, "TagKey", "k", "TagValue", "v",
                    "RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/sync")).statusCode(400)
                    .body("__type", equalTo("BadRequestException"));
            call("/get-tag-sync-task", Map.of("TaskArn", arn + "/" + "a".repeat(26) + "/tag-sync-task/" + "b".repeat(26)))
                    .statusCode(404);
            call("/list-tag-sync-tasks", Map.of()).statusCode(200).body("TagSyncTasks", empty());

            call("/groups", Map.of("Name", name, "ResourceQuery",
                    Map.of("Type", "TAG_FILTERS_1_0", "Query", tagQuery(tagKey, "v1")))).statusCode(400)
                    .body("message", containsString("group already exists"));
        } finally {
            call("/delete-group", Map.of("Group", name)).statusCode(200);
        }
        call("/groups-list", Map.of()).statusCode(200).body("GroupIdentifiers.GroupName", not(hasItem(name)));
    }

    @Test
    void aGenericConfigurationCanBeReplacedButAPoolConfigurationCannot() {
        String name = "config-" + UUID.randomUUID().toString().substring(0, 8);
        call("/groups", Map.of("Name", name, "Configuration", ResourceGroupsServiceTest.CONFIGURATION_NODE))
                .statusCode(200);
        try {
            call("/put-group-configuration", Map.of("Group", name,
                    "Configuration", List.of(ResourceGroupsServiceTest.CONFIGURATION_NODE.get(1),
                            ResourceGroupsServiceTest.CONFIGURATION_NODE.get(0)))).statusCode(200);
            call("/put-group-configuration", Map.of("Group", name, "Configuration",
                    List.of(Map.of("Type", "AWS::ResourceGroups::Generic")))).statusCode(400);
            call("/get-group-configuration", Map.of("Group", name)).statusCode(200)
                    .body("GroupConfiguration.Status", equalTo("UPDATE_COMPLETE"))
                    .body("GroupConfiguration.Configuration.Type", hasItem("AWS::EC2::CapacityReservationPool"));
            call("/get-group-query", Map.of("Group", name)).statusCode(400);
            call("/ungroup-resources", Map.of("Group", name,
                    "ResourceArns", List.of("arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:f")))
                    .statusCode(200).body("Failed[0].ErrorCode", equalTo("ResourceArnValidationException"));
        } finally {
            call("/delete-group", Map.of("Group", name)).statusCode(200);
        }
    }

    @Test
    void accountSettingsTurnLifecycleEventsOnAndOff() {
        call("/get-account-settings", Map.of()).statusCode(200)
                .body("AccountSettings.GroupLifecycleEventsStatus", equalTo("INACTIVE"));
        call("/update-account-settings", Map.of("GroupLifecycleEventsDesiredStatus", "ACTIVE")).statusCode(200)
                .body("AccountSettings.GroupLifecycleEventsStatus", equalTo("ACTIVE"));
        try {
            String name = "events-" + UUID.randomUUID().toString().substring(0, 8);
            call("/groups", Map.of("Name", name, "ResourceQuery", Map.of("Type", "TAG_FILTERS_1_0",
                    "Query", tagQuery("k", "v")))).statusCode(200);
            call("/delete-group", Map.of("Group", name)).statusCode(200);
        } finally {
            call("/update-account-settings", Map.of("GroupLifecycleEventsDesiredStatus", "INACTIVE"))
                    .statusCode(200).body("AccountSettings.GroupLifecycleEventsDesiredStatus", equalTo("INACTIVE"));
        }
    }
}

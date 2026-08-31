package io.github.hectorvent.floci.services.mailmanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 Mail Manager coverage used by Alchemy MailManager.test.ts:
 * GetRuleSet on a missing id is ResourceNotFoundException; deletes are
 * idempotent; rule-set / traffic-policy / relay / address-list / archive
 * round-trip, including archive PENDING_DELETION tombstones.
 */
@QuarkusTest
class MailManagerIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TARGET_PREFIX = "MailManagerSvc.";
    private static final String MISSING_RULE_SET = "rs-00000000000000000000000000";
    private static final String MISSING_TRAFFIC_POLICY = "tp-00000000000000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getRuleSet_missing_returnsResourceNotFoundException() {
        mailmanager("GetRuleSet", "{\"RuleSetId\":\"" + MISSING_RULE_SET + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteRuleSet_and_deleteTrafficPolicy_missing_areIdempotent() {
        mailmanager("DeleteRuleSet", "{\"RuleSetId\":\"" + MISSING_RULE_SET + "\"}")
                .then()
                .statusCode(200);
        mailmanager("DeleteTrafficPolicy",
                "{\"TrafficPolicyId\":\"" + MISSING_TRAFFIC_POLICY + "\"}")
                .then()
                .statusCode(200);
    }

    @Test
    void ruleSetTrafficPolicyRelay_lifecycle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ruleSetName = "floci-mm-rs-" + suffix;
        String policyName = "floci-mm-tp-" + suffix;
        String relayName = "floci-mm-rl-" + suffix;

        String ruleSetId = mailmanager("CreateRuleSet", """
                {
                  "RuleSetName": "%s",
                  "Rules": [{"Name": "DropAll", "Actions": [{"Drop": {}}]}],
                  "Tags": [{"Key": "fixture", "Value": "mailmanager"}]
                }
                """.formatted(ruleSetName))
                .then()
                .statusCode(200)
                .body("RuleSetId", startsWith("rs-"))
                .extract().path("RuleSetId");

        mailmanager("GetRuleSet", "{\"RuleSetId\":\"" + ruleSetId + "\"}")
                .then()
                .statusCode(200)
                .body("RuleSetName", equalTo(ruleSetName))
                .body("RuleSetArn", org.hamcrest.Matchers.containsString("mailmanager"))
                .body("Rules[0].Name", equalTo("DropAll"));

        String ruleSetArn = mailmanager("GetRuleSet", "{\"RuleSetId\":\"" + ruleSetId + "\"}")
                .then()
                .extract().path("RuleSetArn");
        mailmanager("ListTagsForResource", "{\"ResourceArn\":\"" + ruleSetArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"));

        mailmanager("UpdateRuleSet", """
                {
                  "RuleSetId": "%s",
                  "Rules": [
                    {"Name": "DropSecret", "Actions": [{"Drop": {}}]},
                    {"Name": "DropRest", "Actions": [{"Drop": {}}]}
                  ]
                }
                """.formatted(ruleSetId))
                .then()
                .statusCode(200);
        mailmanager("GetRuleSet", "{\"RuleSetId\":\"" + ruleSetId + "\"}")
                .then()
                .body("Rules.size()", equalTo(2));

        String policyId = mailmanager("CreateTrafficPolicy", """
                {
                  "TrafficPolicyName": "%s",
                  "DefaultAction": "ALLOW",
                  "MaxMessageSizeBytes": 10485760,
                  "PolicyStatements": [{
                    "Action": "DENY",
                    "Conditions": [{
                      "IpExpression": {
                        "Evaluate": {"Attribute": "SENDER_IP"},
                        "Operator": "CIDR_MATCHES",
                        "Values": ["192.0.2.0/24"]
                      }
                    }]
                  }]
                }
                """.formatted(policyName))
                .then()
                .statusCode(200)
                .body("TrafficPolicyId", startsWith("tp-"))
                .extract().path("TrafficPolicyId");

        mailmanager("GetTrafficPolicy", "{\"TrafficPolicyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200)
                .body("DefaultAction", equalTo("ALLOW"))
                .body("MaxMessageSizeBytes", equalTo(10485760));

        mailmanager("UpdateTrafficPolicy", """
                {
                  "TrafficPolicyId": "%s",
                  "DefaultAction": "DENY",
                  "MaxMessageSizeBytes": 5242880
                }
                """.formatted(policyId))
                .then()
                .statusCode(200);
        mailmanager("GetTrafficPolicy", "{\"TrafficPolicyId\":\"" + policyId + "\"}")
                .then()
                .body("DefaultAction", equalTo("DENY"))
                .body("MaxMessageSizeBytes", equalTo(5242880));

        String relayId = mailmanager("CreateRelay", """
                {
                  "RelayName": "%s",
                  "ServerName": "smtp.example.com",
                  "ServerPort": 25,
                  "Authentication": {"NoAuthentication": {}}
                }
                """.formatted(relayName))
                .then()
                .statusCode(200)
                .body("RelayId", notNullValue())
                .extract().path("RelayId");

        mailmanager("GetRelay", "{\"RelayId\":\"" + relayId + "\"}")
                .then()
                .statusCode(200)
                .body("ServerName", equalTo("smtp.example.com"))
                .body("ServerPort", equalTo(25));

        mailmanager("UpdateRelay", """
                {"RelayId": "%s", "ServerPort": 587}
                """.formatted(relayId))
                .then()
                .statusCode(200);
        mailmanager("GetRelay", "{\"RelayId\":\"" + relayId + "\"}")
                .then()
                .body("ServerPort", equalTo(587));

        mailmanager("DeleteRelay", "{\"RelayId\":\"" + relayId + "\"}").then().statusCode(200);
        mailmanager("DeleteTrafficPolicy", "{\"TrafficPolicyId\":\"" + policyId + "\"}")
                .then().statusCode(200);
        mailmanager("DeleteRuleSet", "{\"RuleSetId\":\"" + ruleSetId + "\"}").then().statusCode(200);

        mailmanager("GetRuleSet", "{\"RuleSetId\":\"" + ruleSetId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        mailmanager("GetTrafficPolicy", "{\"TrafficPolicyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        mailmanager("GetRelay", "{\"RelayId\":\"" + relayId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void addressListAndArchive_tombstoneThenRecreate() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String listName = "floci-mm-al-" + suffix;
        String archiveName = "floci-mm-a-" + suffix;

        String listId = mailmanager("CreateAddressList", """
                {
                  "AddressListName": "%s",
                  "Tags": [{"Key": "fixture", "Value": "mailmanager"}]
                }
                """.formatted(listName))
                .then()
                .statusCode(200)
                .body("AddressListId", startsWith("al-"))
                .extract().path("AddressListId");

        String listArn = mailmanager("GetAddressList", "{\"AddressListId\":\"" + listId + "\"}")
                .then()
                .statusCode(200)
                .body("AddressListName", equalTo(listName))
                .extract().path("AddressListArn");
        mailmanager("TagResource", """
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "test"}]}
                """.formatted(listArn))
                .then()
                .statusCode(200);
        mailmanager("ListTagsForResource", "{\"ResourceArn\":\"" + listArn + "\"}")
                .then()
                .body("Tags.Key", hasItem("env"));

        String archiveId = mailmanager("CreateArchive", """
                {
                  "ArchiveName": "%s",
                  "Retention": {"RetentionPeriod": "THREE_MONTHS"}
                }
                """.formatted(archiveName))
                .then()
                .statusCode(200)
                .body("ArchiveId", startsWith("a-"))
                .extract().path("ArchiveId");

        mailmanager("GetArchive", "{\"ArchiveId\":\"" + archiveId + "\"}")
                .then()
                .statusCode(200)
                .body("ArchiveState", equalTo("ACTIVE"))
                .body("Retention.RetentionPeriod", equalTo("THREE_MONTHS"));

        mailmanager("UpdateArchive", """
                {
                  "ArchiveId": "%s",
                  "Retention": {"RetentionPeriod": "SIX_MONTHS"}
                }
                """.formatted(archiveId))
                .then()
                .statusCode(200);
        mailmanager("GetArchive", "{\"ArchiveId\":\"" + archiveId + "\"}")
                .then()
                .body("Retention.RetentionPeriod", equalTo("SIX_MONTHS"));

        mailmanager("DeleteAddressList", "{\"AddressListId\":\"" + listId + "\"}")
                .then().statusCode(200);
        mailmanager("GetAddressList", "{\"AddressListId\":\"" + listId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        mailmanager("DeleteArchive", "{\"ArchiveId\":\"" + archiveId + "\"}")
                .then().statusCode(200);
        mailmanager("GetArchive", "{\"ArchiveId\":\"" + archiveId + "\"}")
                .then()
                .statusCode(200)
                .body("ArchiveState", equalTo("PENDING_DELETION"));

        String recreatedId = mailmanager("CreateArchive", """
                {
                  "ArchiveName": "%s",
                  "Retention": {"RetentionPeriod": "THREE_MONTHS"}
                }
                """.formatted(archiveName))
                .then()
                .statusCode(200)
                .body("ArchiveId", not(equalTo(archiveId)))
                .extract().path("ArchiveId");
        mailmanager("GetArchive", "{\"ArchiveId\":\"" + recreatedId + "\"}")
                .then()
                .body("ArchiveState", equalTo("ACTIVE"));
    }

    private static io.restassured.response.Response mailmanager(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH)
                .body(body)
        .when()
                .post("/");
    }
}

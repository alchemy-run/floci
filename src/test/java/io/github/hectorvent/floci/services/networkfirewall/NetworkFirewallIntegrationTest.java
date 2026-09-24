package io.github.hectorvent.floci.services.networkfirewall;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NetworkFirewallIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET_PREFIX = "NetworkFirewall_20201112.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=723679240095/20260101/us-east-1/network-firewall/aws4_request";
    private static final String FIREWALL_ARN =
            "arn:aws:network-firewall:us-east-1:723679240095:firewall/AWSAccelerator-us-east-1-nfw";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createListAndDescribeFirewall_returnsPersistentReadyResource() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallName\":\"AWSAccelerator-us-east-1-nfw\","
                    + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                    + "firewall-policy/AWSAccelerator-us-east-1-nfw-policy\","
                    + "\"VpcId\":\"vpc-1234567890abcdef0\","
                    + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-11111111111111111\"}]}" )
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewall.FirewallArn", equalTo(FIREWALL_ARN));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListFirewalls")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewalls.FirewallArn", hasItem(FIREWALL_ARN));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallArn\":\"" + FIREWALL_ARN + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewall.FirewallArn", equalTo(FIREWALL_ARN))
            .body("FirewallStatus.Status", equalTo("READY"))
            .body("FirewallStatus.SyncStates.us-east-1a.Attachment.Status", equalTo("READY"))
            .body("FirewallStatus.SyncStates.us-east-1a.Attachment.SubnetId",
                    equalTo("subnet-11111111111111111"))
            .body("FirewallStatus.SyncStates.us-east-1a.Attachment.EndpointId",
                    matchesPattern("vpce-[0-9a-f]{17}"));
    }

    @Test
    void createFirewall_withoutProtectionFlags_defaultsToProtected() {
        // botocore 2020-11-12: CreateFirewall initializes DeleteProtection,
        // SubnetChangeProtection and FirewallPolicyChangeProtection to TRUE when the
        // caller omits them. AvailabilityZoneChangeProtection is the lone exception,
        // documented as defaulting to FALSE.
        String name = "DefaultProtectionFirewall";
        call("CreateFirewall", "{\"FirewallName\":\"" + name + "\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-policy\","
                + "\"VpcId\":\"vpc-0123456789abcdef0\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-def00000000000001\"}]}")
            .statusCode(200);

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.DeleteProtection", equalTo(true))
            .body("Firewall.SubnetChangeProtection", equalTo(true))
            .body("Firewall.FirewallPolicyChangeProtection", equalTo(true))
            .body("Firewall.AvailabilityZoneChangeProtection", equalTo(false));
    }

    @Test
    void describeFirewall_withoutIdentifier_returnsAwsError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void createAndListRuleGroup_returnsAwsMetadataShape() {
        String arn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "stateful-rulegroup/vellum-domain-allow-list";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateRuleGroup")
            .header("Authorization", AUTH_HEADER)
            .body("{\"RuleGroupName\":\"vellum-domain-allow-list\",\"Type\":\"STATEFUL\","
                    + "\"Capacity\":100,\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleGroupResponse.RuleGroupArn", equalTo(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListRuleGroups")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Type\":\"STATEFUL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleGroups.Arn", hasItem(arn))
            .body("RuleGroups.find { it.Arn == '" + arn + "' }.Name",
                    equalTo("vellum-domain-allow-list"));
    }

    @Test
    void associateFirewallPolicy_thenDescribeFirewall_showsNewPolicyArn() {
        String firewallArn = "arn:aws:network-firewall:us-east-1:723679240095:firewall/AssocTestFirewall";
        String initialPolicyArn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/AssocTestFirewall-initial-policy";
        String newPolicyArn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/AssocTestFirewall-new-policy";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallName\":\"AssocTestFirewall\","
                    + "\"FirewallPolicyArn\":\"" + initialPolicyArn + "\","
                    + "\"VpcId\":\"vpc-assoctest0000000000\","
                    + "\"FirewallPolicyChangeProtection\":false,"
                    + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-22222222222222222\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateFirewallPolicy")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallPolicyName\":\"AssocTestFirewall-new-policy\","
                    + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                    + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallPolicyResponse.FirewallPolicyArn", equalTo(newPolicyArn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "AssociateFirewallPolicy")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallArn\":\"" + firewallArn + "\","
                    + "\"FirewallPolicyArn\":\"" + newPolicyArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallPolicyArn", equalTo(newPolicyArn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallArn\":\"" + firewallArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewall.FirewallPolicyArn", equalTo(newPolicyArn));
    }

    @Test
    void deleteFirewall_withDeleteProtection_isRejectedAndLeavesTheFirewall() {
        String name = "DeleteProtectedFirewall";
        createFirewall(name, "\"DeleteProtection\":true,", "subnet-33333333333333333");

        call("DeleteFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.FirewallArn", equalTo(firewallArn(name)));
    }

    @Test
    void associateSubnets_addsToTheExistingMappingsWithoutDuplicating() {
        String name = "AssociateSubnetsFirewall";
        createFirewall(name, "", "subnet-44444444444444444");

        call("AssociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-55555555555555555\"},"
                + "{\"SubnetId\":\"subnet-44444444444444444\"}]}")
            .statusCode(200)
            .body("SubnetMappings.SubnetId", hasItems(
                    "subnet-44444444444444444", "subnet-55555555555555555"))
            .body("SubnetMappings", hasSize(2));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", hasItems(
                    "subnet-44444444444444444", "subnet-55555555555555555"))
            .body("Firewall.SubnetMappings", hasSize(2));
    }

    @Test
    void associateSubnets_partiallyInvalidRequest_leavesStoredMappingsUntouched() {
        // A valid mapping followed by one missing its required SubnetId must reject
        // the WHOLE request without mutating stored state: validate-then-commit,
        // never commit-then-validate.
        String name = "PartialInvalidAssociateFirewall";
        createFirewall(name, "", "subnet-11111111111111111");

        call("AssociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-22222222222222222\"},"
                + "{\"IPAddressType\":\"IPV4\"}]}")
            .statusCode(400);

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", not(hasItem("subnet-22222222222222222")))
            .body("Firewall.SubnetMappings", hasSize(1));
    }

    @Test
    void associateSubnets_withSubnetChangeProtection_isRejected() {
        String name = "SubnetProtectedAssociateFirewall";
        createFirewall(name, "\"SubnetChangeProtection\":true,", "subnet-66666666666666666");

        call("AssociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-77777777777777777\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", not(hasItem("subnet-77777777777777777")));
    }

    @Test
    void updateFirewallDescription_cannotSmuggleProtectedMappingsPastChangeProtection() {
        // The UpdateFirewall* ops each model a specific mutable field. Extra fields in the
        // raw request (SubnetMappings here) must not be persisted, otherwise a plain
        // UpdateFirewallDescription bypasses SubnetChangeProtection entirely.
        String name = "SmuggledMappingsFirewall";
        createFirewall(name, "\"SubnetChangeProtection\":true,", "subnet-88888888888888888");

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"Description\":\"updated\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-99999999999999999\"}]}")
            .statusCode(200);

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.Description", equalTo("updated"))
            .body("Firewall.SubnetMappings.SubnetId", not(hasItem("subnet-99999999999999999")))
            .body("Firewall.SubnetMappings", hasSize(1));
    }

    @Test
    void updateFirewallDescription_cannotSmuggleAnotherOperationsProtectionField() {
        // Each UpdateFirewall* operation models exactly one mutable field (botocore
        // 2020-11-12). A raw UpdateFirewallDescription request that also carries
        // DeleteProtection (only modeled on UpdateFirewallDeleteProtection) must not
        // persist it, otherwise DeleteProtection can be flipped without ever calling
        // UpdateFirewallDeleteProtection, and DescribeFirewall reports a change that op
        // never made.
        String name = "SmuggledDeleteProtectionFirewall";
        createFirewall(name, "", "subnet-11122233344455566");

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"Description\":\"updated\","
                + "\"DeleteProtection\":true}")
            .statusCode(200);

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.Description", equalTo("updated"))
            .body("Firewall.DeleteProtection", equalTo(false));
    }

    @Test
    void updateFirewallDeleteProtection_returnsTheFlatModelledShape() {
        // botocore 2020-11-12: UpdateFirewallDeleteProtectionResponse is
        // {FirewallArn, FirewallName, DeleteProtection, UpdateToken} at the top
        // level -- NOT nested under Firewall/FirewallStatus. Every UpdateFirewall*
        // op shares this flat shape with its own field substituted for
        // DeleteProtection, so a typed SDK client can read it back.
        String name = "UpdateShapeFirewall";
        createFirewall(name, "", "subnet-ee000000000000001");

        call("UpdateFirewallDeleteProtection", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"DeleteProtection\":true}")
            .statusCode(200)
            .body("FirewallArn", equalTo(firewallArn(name)))
            .body("FirewallName", equalTo(name))
            .body("DeleteProtection", equalTo(true))
            .body("UpdateToken", not(nullValue()))
            .body("Firewall", nullValue())
            .body("FirewallStatus", nullValue());
    }

    @Test
    void disassociateSubnets_removesOnlyTheNamedSubnets() {
        String name = "DisassociateSubnetsFirewall";
        createFirewall(name, "", "subnet-88888888888888888", "subnet-99999999999999999");

        call("DisassociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetIds\":[\"subnet-88888888888888888\"]}")
            .statusCode(200)
            .body("SubnetMappings.SubnetId", contains("subnet-99999999999999999"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", contains("subnet-99999999999999999"));
    }

    @Test
    void disassociateSubnets_removingTheLastSubnet_leavesNoSyncStates() {
        String name = "DrainedSubnetsFirewall";
        createFirewall(name, "", "subnet-aaaaaaaaaaaaaaaaa");

        call("DisassociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetIds\":[\"subnet-aaaaaaaaaaaaaaaaa\"]}")
            .statusCode(200)
            .body("SubnetMappings", empty());

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings", empty())
            .body("FirewallStatus.SyncStates", anEmptyMap());
    }

    @Test
    void disassociateSubnets_withSubnetChangeProtection_isRejected() {
        String name = "SubnetProtectedDisassociateFirewall";
        createFirewall(name, "\"SubnetChangeProtection\":true,", "subnet-bbbbbbbbbbbbbbbbb");

        call("DisassociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetIds\":[\"subnet-bbbbbbbbbbbbbbbbb\"]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", hasItem("subnet-bbbbbbbbbbbbbbbbb"));
    }

    @Test
    void associateAvailabilityZones_addsToTheExistingMappingsWithoutDuplicating() {
        String name = "AssociateZonesFirewall";
        createFirewall(name, "", "subnet-ccccccccccccccccc");

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"}]}")
            .statusCode(200)
            .body("AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1a"));

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"},"
                + "{\"AvailabilityZone\":\"us-east-1b\"}]}")
            .statusCode(200)
            .body("AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1a", "us-east-1b"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.AvailabilityZoneMappings.AvailabilityZone",
                    contains("us-east-1a", "us-east-1b"));
    }

    @Test
    void associateAvailabilityZones_withAvailabilityZoneChangeProtection_isRejected() {
        String name = "ZoneProtectedAssociateFirewall";
        createFirewall(name, "\"AvailabilityZoneChangeProtection\":true,", "subnet-ddddddddddddddddd");

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1c\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.AvailabilityZoneMappings", nullValue());
    }

    @Test
    void disassociateAvailabilityZones_removesOnlyTheNamedZones() {
        String name = "DisassociateZonesFirewall";
        createFirewall(name, "", "subnet-eeeeeeeeeeeeeeeee");

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"},"
                + "{\"AvailabilityZone\":\"us-east-1b\"}]}")
            .statusCode(200);

        call("DisassociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"}]}")
            .statusCode(200)
            .body("AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1b"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1b"));
    }

    @Test
    void disassociateAvailabilityZones_withAvailabilityZoneChangeProtection_isRejected() {
        String name = "ZoneProtectedDisassociateFirewall";
        createFirewall(name, "\"AvailabilityZoneChangeProtection\":true,", "subnet-fffffffffffffffff");

        call("DisassociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));
    }

    @Test
    void disassociateSubnets_withoutSubnetIds_leavesTheFirewallUntouched() {
        String name = "FallbackSubnetsFirewall";
        call("CreateFirewall", "{\"FirewallName\":\"" + name + "\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-policy\","
                + "\"VpcId\":\"vpc-0123456789abcdef0\","
                + "\"SubnetChangeProtection\":false}")
            .statusCode(200);

        call("DisassociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings", nullValue())
            .body("FirewallStatus.SyncStates", aMapWithSize(6));
    }

    @Test
    void createFirewall_whenAlreadyPresent_isRejectedAsAnInvalidRequest() {
        String name = "DuplicateNameFirewall";
        createFirewall(name, "", "subnet-11111111111111121");

        call("CreateFirewall", "{\"FirewallName\":\"" + name + "\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-other-policy\","
                + "\"VpcId\":\"vpc-0123456789abcdef1\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-11111111111111122\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("Firewall already exists: " + name));
    }

    @Test
    void createRuleGroup_whenAlreadyPresent_namesTheResourceKindInTheError() {
        String body = "{\"RuleGroupName\":\"duplicate-rule-group\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":100,\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any\"}}}";
        call("CreateRuleGroup", body).statusCode(200);

        call("CreateRuleGroup", body)
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("RuleGroup already exists: duplicate-rule-group"));
    }

    @Test
    void deleteFirewall_returnsTheDeletedFirewallAndItsStatus() {
        String name = "DeletableFirewall";
        createFirewall(name, "", "subnet-11111111111111112");

        call("DeleteFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.FirewallArn", equalTo(firewallArn(name)))
            .body("Firewall.FirewallName", equalTo(name))
            .body("FirewallStatus.Status", equalTo("READY"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeRuleGroup_whenMissing_namesTheResourceKindInTheError() {
        call("DescribeRuleGroup", "{\"RuleGroupName\":\"no-such-rule-group\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("RuleGroup not found: no-such-rule-group"));
    }

    @Test
    void createRuleGroup_withUnmodelledType_isRejectedAndStoresNothing() {
        call("CreateRuleGroup", "{\"RuleGroupName\":\"bogus-type-rule-group\",\"Type\":\"FOO\","
                + "\"Capacity\":100,\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any\"}}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        call("DescribeRuleGroup", "{\"RuleGroupName\":\"bogus-type-rule-group\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void updateRuleGroup_withUnmodelledType_isRejected() {
        call("UpdateRuleGroup", "{\"RuleGroupName\":\"bogus-update-rule-group\",\"Type\":\"stateful\","
                + "\"Capacity\":100,\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any\"}}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void createRuleGroup_withStatefulDomainType_usesTheStatefulArnPrefix() {
        call("CreateRuleGroup", "{\"RuleGroupName\":\"domain-list-rule-group\",\"Type\":\"STATEFUL_DOMAIN\","
                + "\"Capacity\":100,\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any\"}}}")
            .statusCode(200)
            .body("RuleGroupResponse.RuleGroupArn", equalTo(
                    "arn:aws:network-firewall:us-east-1:723679240095:"
                            + "stateful-rulegroup/domain-list-rule-group"));
    }

    @Test
    void updateLoggingConfiguration_withUnmodelledLogTypeOrDestinationType_isRejected() {
        String name = "LoggingEnumFirewall";
        createFirewall(name, "", "subnet-11111111111111113");

        call("UpdateLoggingConfiguration", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"LoggingConfiguration\":{\"LogDestinationConfigs\":[{\"LogType\":\"AUDIT\","
                + "\"LogDestinationType\":\"S3\",\"LogDestination\":{\"bucketName\":\"b\"}}]}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        call("UpdateLoggingConfiguration", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"LoggingConfiguration\":{\"LogDestinationConfigs\":[{\"LogType\":\"ALERT\","
                + "\"LogDestinationType\":\"s3\",\"LogDestination\":{\"bucketName\":\"b\"}}]}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        call("DescribeLoggingConfiguration", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("LoggingConfiguration.LogDestinationConfigs", empty());
    }

    @Test
    void updateLoggingConfiguration_withModelledEnumValues_isStored() {
        String name = "LoggingValidFirewall";
        createFirewall(name, "", "subnet-11111111111111114");

        call("UpdateLoggingConfiguration", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"LoggingConfiguration\":{\"LogDestinationConfigs\":[{\"LogType\":\"FLOW\","
                + "\"LogDestinationType\":\"CloudWatchLogs\","
                + "\"LogDestination\":{\"logGroup\":\"/aws/nfw\"}}]}}")
            .statusCode(200)
            .body("LoggingConfiguration.LogDestinationConfigs[0].LogDestinationType",
                    equalTo("CloudWatchLogs"));
    }

    @Test
    void updateFirewallDescription_whenNeverSet_omitsTheFieldFromTheResponse() {
        String name = "NoDescriptionFirewall";
        createFirewall(name, "", "subnet-11111111111111115");

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("FirewallArn", equalTo(firewallArn(name)))
            .body("FirewallName", equalTo(name))
            .body("UpdateToken", not(emptyOrNullString()))
            .body("$", not(hasKey("Description")));
    }

    @Test
    void updateFirewallAnalysisSettings_whenNeverSet_omitsTheFieldFromTheResponse() {
        String name = "NoAnalysisSettingsFirewall";
        createFirewall(name, "", "subnet-11111111111111116");

        call("UpdateFirewallAnalysisSettings", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("FirewallArn", equalTo(firewallArn(name)))
            .body("FirewallName", equalTo(name))
            .body("UpdateToken", not(emptyOrNullString()))
            .body("$", not(hasKey("EnabledAnalysisTypes")));
    }

    @Test
    void updateFirewallDescription_whenProvided_storesAndEchoesTheValue() {
        String name = "DescribedFirewall";
        createFirewall(name, "", "subnet-11111111111111117");

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"Description\":\"managed by the accelerator\"}")
            .statusCode(200)
            .body("Description", equalTo("managed by the accelerator"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.Description", equalTo("managed by the accelerator"));
    }

    @Test
    void associateFirewallPolicy_withPolicyChangeProtection_isRejected() {
        String name = "PolicyProtectedFirewall";
        String initialPolicyArn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-policy";
        createFirewall(name, "\"FirewallPolicyChangeProtection\":true,", "subnet-11111111111111118");

        call("CreateFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "-replacement\","
                + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
            .statusCode(200);

        call("AssociateFirewallPolicy", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-replacement\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.FirewallPolicyArn", equalTo(initialPolicyArn));
    }

    @Test
    void updateFirewallDescription_omittingDescription_clearsTheStoredValue() {
        String name = "ClearDescriptionFirewall";
        createFirewall(name, "", "subnet-11111111111111119");

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"Description\":\"initial description\"}")
            .statusCode(200)
            .body("Description", equalTo("initial description"));

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("$", not(hasKey("Description")));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.Description", nullValue());
    }

    @Test
    void updateFirewallAnalysisSettings_omittingTypes_preservesTheStoredValue() {
        // Deliberate asymmetry with Description: botocore documents omission as removal for
        // UpdateFirewallDescription only. EnabledAnalysisTypes carries no such statement, so
        // the stored value is preserved rather than inferring AWS behaviour the model omits.
        String name = "PreserveAnalysisFirewall";
        createFirewall(name, "", "subnet-1111111111111111a");

        call("UpdateFirewallAnalysisSettings", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"EnabledAnalysisTypes\":[\"TLS_SNI\"]}")
            .statusCode(200)
            .body("EnabledAnalysisTypes", contains("TLS_SNI"));

        call("UpdateFirewallAnalysisSettings", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("EnabledAnalysisTypes", contains("TLS_SNI"));
    }

    @Test
    void describeFirewall_exposesTheCurrentUpdateTokenAtTheTopLevel() {
        String name = "TokenExposedFirewall";
        createFirewall(name, "", "subnet-11111111111111123");

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("UpdateToken", not(emptyOrNullString()))
            .body("Firewall", not(hasKey("UpdateToken")));
    }

    @Test
    void updateFirewallDescription_withTheCurrentToken_succeedsAndRotatesTheToken() {
        String name = "CurrentTokenFirewall";
        createFirewall(name, "", "subnet-11111111111111124");
        String currentToken = currentUpdateToken(name);

        String newToken = call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"UpdateToken\":\"" + currentToken + "\",\"Description\":\"checked in\"}")
            .statusCode(200)
            .body("Description", equalTo("checked in"))
            .body("UpdateToken", not(equalTo(currentToken)))
            .extract().path("UpdateToken");

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("UpdateToken", equalTo(newToken));
    }

    @Test
    void updateFirewallDescription_withAStaleToken_isRejectedAndLeavesTheFirewallUntouched() {
        String name = "StaleTokenFirewall";
        createFirewall(name, "", "subnet-11111111111111125");

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"UpdateToken\":\"00000000-0000-0000-0000-000000000000\","
                + "\"Description\":\"should not stick\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidTokenException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.Description", nullValue());
    }

    @Test
    void updateFirewallDescription_withoutAToken_appliesUnconditionally() {
        String name = "UnconditionalUpdateFirewall";
        createFirewall(name, "", "subnet-11111111111111126");

        call("UpdateFirewallDescription", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"Description\":\"no token needed\"}")
            .statusCode(200)
            .body("Description", equalTo("no token needed"));
    }

    @Test
    void associateSubnets_withAStaleToken_isRejectedAndLeavesTheMappingsUntouched() {
        String name = "StaleTokenAssociateFirewall";
        createFirewall(name, "", "subnet-11111111111111127");

        call("AssociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"UpdateToken\":\"00000000-0000-0000-0000-000000000000\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-11111111111111128\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidTokenException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings", hasSize(1));
    }

    @Test
    void associateSubnets_withTheCurrentToken_succeedsAndReturnsANewToken() {
        String name = "CurrentTokenAssociateFirewall";
        createFirewall(name, "", "subnet-11111111111111129");
        String currentToken = currentUpdateToken(name);

        call("AssociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"UpdateToken\":\"" + currentToken + "\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-1111111111111112a\"}]}")
            .statusCode(200)
            .body("UpdateToken", not(equalTo(currentToken)));
    }

    @Test
    void associateFirewallPolicy_withAStaleToken_isRejectedAndLeavesThePolicyUntouched() {
        String name = "StaleTokenPolicyFirewall";
        String initialPolicyArn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-policy";
        createFirewall(name, "", "subnet-1111111111111112b");

        call("CreateFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "-new-policy\","
                + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
            .statusCode(200);

        call("AssociateFirewallPolicy", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"UpdateToken\":\"00000000-0000-0000-0000-000000000000\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-new-policy\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidTokenException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.FirewallPolicyArn", equalTo(initialPolicyArn));
    }

    @Test
    void associateFirewallPolicy_returnsTheRotatedUpdateToken() {
        String name = "PolicyResponseTokenFirewall";
        createFirewall(name, "", "subnet-1111111111111112c");

        call("CreateFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "-new-policy\","
                + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
            .statusCode(200);

        call("AssociateFirewallPolicy", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-new-policy\"}")
            .statusCode(200)
            .body("UpdateToken", not(emptyOrNullString()));
    }

    @Test
    void createRuleGroup_withSuricataRules_describesThemAsRulesStringWithActiveStatus() {
        String name = "rules-string-rule-group";
        String rules = "pass tcp any any -> any 80 (msg:\"allow http\"; sid:100001; rev:1;)";
        call("CreateRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":100,\"Rules\":" + json(rules) + ",\"Description\":\"v1\"}")
            .statusCode(200)
            .body("UpdateToken", not(emptyOrNullString()))
            .body("RuleGroupResponse.RuleGroupArn", equalTo(ruleGroupArn("stateful", name)))
            .body("RuleGroupResponse.RuleGroupStatus", equalTo("ACTIVE"))
            .body("RuleGroupResponse.Capacity", equalTo(100))
            .body("RuleGroupResponse", not(hasKey("Rules")))
            .body("RuleGroupResponse", not(hasKey("ResourceArn")));

        call("DescribeRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\"}")
            .statusCode(200)
            .body("RuleGroup.RulesSource.RulesString", equalTo(rules))
            .body("RuleGroupResponse.RuleGroupStatus", equalTo("ACTIVE"))
            .body("RuleGroupResponse.Description", equalTo("v1"))
            .body("RuleGroupResponse.Type", equalTo("STATEFUL"));
    }

    @Test
    void createRuleGroup_withBothRuleGroupAndRules_isRejected() {
        call("CreateRuleGroup", "{\"RuleGroupName\":\"both-definitions-rule-group\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":10,\"Rules\":\"pass ip any any -> any any (sid:1;)\","
                + "\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any -> any any (sid:1;)\"}}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void describeRuleGroup_byNameAndType_distinguishesStatelessFromStateful() {
        String name = "same-name-both-types";
        call("CreateRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":10,\"Rules\":\"pass ip any any -> any any (sid:1;)\"}")
            .statusCode(200);
        call("CreateRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATELESS\","
                + "\"Capacity\":10,\"RuleGroup\":" + STATELESS_DEFINITION + "}")
            .statusCode(200);

        call("DescribeRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATELESS\"}")
            .statusCode(200)
            .body("RuleGroupResponse.RuleGroupArn", equalTo(ruleGroupArn("stateless", name)));
        call("DescribeRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\"}")
            .statusCode(200)
            .body("RuleGroupResponse.RuleGroupArn", equalTo(ruleGroupArn("stateful", name)));
    }

    @Test
    void updateRuleGroup_byArnOnly_updatesInPlaceAndKeepsIdentityCapacityAndTags() {
        // Regression: UpdateRuleGroup used to delete and re-create the rule group from the
        // update request, which carries no RuleGroupName or Capacity when addressed by ARN,
        // so it failed with "RuleGroupName is required." after already deleting the group.
        String name = "update-in-place-rule-group";
        String arn = ruleGroupArn("stateful", name);
        String ruleGroupId = call("CreateRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":100,\"Rules\":\"pass tcp any any -> any 80 (sid:1;)\",\"Description\":\"v1\","
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"nfw\"}]}")
            .statusCode(200)
            .extract().path("RuleGroupResponse.RuleGroupId");
        String token = call("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
            .statusCode(200)
            .extract().path("UpdateToken");

        String newToken = call("UpdateRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\",\"UpdateToken\":\"" + token
                + "\",\"Type\":\"STATEFUL\",\"Rules\":\"drop tcp any any -> any 23 (sid:2;)\",\"Description\":\"v2\"}")
            .statusCode(200)
            .body("UpdateToken", not(equalTo(token)))
            .body("RuleGroupResponse.RuleGroupArn", equalTo(arn))
            .body("RuleGroupResponse.RuleGroupId", equalTo(ruleGroupId))
            .extract().path("UpdateToken");

        call("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("UpdateToken", equalTo(newToken))
            .body("RuleGroup.RulesSource.RulesString", equalTo("drop tcp any any -> any 23 (sid:2;)"))
            .body("RuleGroupResponse.Description", equalTo("v2"))
            .body("RuleGroupResponse.Capacity", equalTo(100))
            .body("RuleGroupResponse.RuleGroupId", equalTo(ruleGroupId))
            .body("RuleGroupResponse.Tags.find { it.Key == 'fixture' }.Value", equalTo("nfw"));
    }

    @Test
    void updateRuleGroup_withStaleOrMissingToken_isRejectedAndLeavesTheRuleGroupIntact() {
        String name = "stale-token-rule-group";
        String arn = ruleGroupArn("stateful", name);
        call("CreateRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":10,\"Rules\":\"pass tcp any any -> any 80 (sid:1;)\"}")
            .statusCode(200);

        call("UpdateRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\","
                + "\"UpdateToken\":\"00000000-0000-0000-0000-000000000000\","
                + "\"Rules\":\"drop tcp any any -> any 23 (sid:2;)\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidTokenException"));
        call("UpdateRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\","
                + "\"Rules\":\"drop tcp any any -> any 23 (sid:2;)\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        call("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("RuleGroup.RulesSource.RulesString", equalTo("pass tcp any any -> any 80 (sid:1;)"));
    }

    @Test
    void updateFirewallPolicy_byArnOnly_updatesInPlace() {
        String name = "update-in-place-policy";
        String arn = policyArn(name);
        call("CreateFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "\",\"Description\":\"pass\","
                + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
            .statusCode(200)
            .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("ACTIVE"));
        String token = call("DescribeFirewallPolicy", "{\"FirewallPolicyArn\":\"" + arn + "\"}")
            .statusCode(200)
            .extract().path("UpdateToken");

        call("UpdateFirewallPolicy", "{\"FirewallPolicyArn\":\"" + arn + "\",\"UpdateToken\":\"" + token + "\","
                + "\"Description\":\"drop\",\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:drop\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:drop\"]}}")
            .statusCode(200)
            .body("FirewallPolicyResponse.FirewallPolicyArn", equalTo(arn))
            .body("UpdateToken", not(equalTo(token)));

        call("DescribeFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "\"}")
            .statusCode(200)
            .body("FirewallPolicy.StatelessDefaultActions", contains("aws:drop"))
            .body("FirewallPolicyResponse.Description", equalTo("drop"))
            .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("ACTIVE"));
    }

    @Test
    void deleteRuleGroup_referencedByAPolicy_isRejectedUntilThePolicyIsDeleted() {
        String groupName = "referenced-rule-group";
        String groupArn = ruleGroupArn("stateful", groupName);
        String policyName = "referencing-policy";
        call("CreateRuleGroup", "{\"RuleGroupName\":\"" + groupName + "\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":10,\"Rules\":\"pass tcp any any -> any 443 (sid:1;)\"}")
            .statusCode(200);
        call("CreateFirewallPolicy", "{\"FirewallPolicyName\":\"" + policyName + "\","
                + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:forward_to_sfe\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:forward_to_sfe\"],"
                + "\"StatefulRuleGroupReferences\":[{\"ResourceArn\":\"" + groupArn + "\"}]}}")
            .statusCode(200);

        call("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
            .statusCode(200)
            .body("RuleGroupResponse.NumberOfAssociations", equalTo(1));
        call("DeleteRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DeleteFirewallPolicy", "{\"FirewallPolicyArn\":\"" + policyArn(policyName) + "\"}")
            .statusCode(200)
            .body("FirewallPolicyResponse.FirewallPolicyArn", equalTo(policyArn(policyName)))
            .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("DELETING"));
        call("DeleteRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
            .statusCode(200)
            .body("RuleGroupResponse.RuleGroupArn", equalTo(groupArn))
            .body("RuleGroupResponse.RuleGroupStatus", equalTo("DELETING"));
        call("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteFirewallPolicy_associatedWithAFirewall_isRejected() {
        String name = "InUsePolicyFirewall";
        String policyName = name + "-policy";
        call("CreateFirewallPolicy", "{\"FirewallPolicyName\":\"" + policyName + "\","
                + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
            .statusCode(200);
        createFirewall(name, "", "subnet-2222222222222222a");

        call("DeleteFirewallPolicy", "{\"FirewallPolicyName\":\"" + policyName + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));
    }

    @Test
    void tagResource_untagResource_andListTagsForResource_coverRuleGroupsPoliciesAndFirewalls() {
        String groupArn = ruleGroupArn("stateful", "tagged-rule-group");
        call("CreateRuleGroup", "{\"RuleGroupName\":\"tagged-rule-group\",\"Type\":\"STATEFUL\","
                + "\"Capacity\":10,\"Rules\":\"pass tcp any any -> any 80 (sid:1;)\","
                + "\"Tags\":[{\"Key\":\"keep\",\"Value\":\"1\"},{\"Key\":\"drop\",\"Value\":\"1\"}]}")
            .statusCode(200);
        call("CreateFirewallPolicy", "{\"FirewallPolicyName\":\"tagged-policy\","
                + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
            .statusCode(200);
        createFirewall("TaggedFirewall", "", "subnet-2222222222222222b");

        for (String arn : new String[] {groupArn, policyArn("tagged-policy"), firewallArn("TaggedFirewall")}) {
            call("TagResource", "{\"ResourceArn\":\"" + arn + "\","
                    + "\"Tags\":[{\"Key\":\"keep\",\"Value\":\"2\"},{\"Key\":\"added\",\"Value\":\"yes\"}]}")
                .statusCode(200);
            call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"drop\"]}")
                .statusCode(200);
            call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("Tags.find { it.Key == 'keep' }.Value", equalTo("2"))
                .body("Tags.find { it.Key == 'added' }.Value", equalTo("yes"))
                .body("Tags.Key", not(hasItem("drop")));
        }

        call("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
            .statusCode(200)
            .body("RuleGroupResponse.Tags.Key", hasItems("keep", "added"))
            .body("RuleGroupResponse.Tags", hasSize(2));
        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn("TaggedFirewall") + "\"}")
            .statusCode(200)
            .body("Firewall.Tags.Key", hasItems("keep", "added"));
    }

    @Test
    void tagResource_onAnUnknownArn_returnsResourceNotFound() {
        call("TagResource", "{\"ResourceArn\":\"" + ruleGroupArn("stateful", "no-such-tagged-group") + "\","
                + "\"Tags\":[{\"Key\":\"k\",\"Value\":\"v\"}]}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeRuleGroupSummary_reportsTheConfiguredRuleOptions() {
        String name = "summary-rule-group";
        String rules = "pass tcp any any -> any 443 (msg:\"allow https\"; sid:100001; rev:1;)\n"
                + "# a comment line\n"
                + "drop tcp any any -> any 23 (msg:\"block \\\"telnet\\\"; now\"; sid:100002; metadata:team net;)";
        call("CreateRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\",\"Capacity\":10,"
                + "\"Rules\":" + json(rules) + ",\"SummaryConfiguration\":{\"RuleOptions\":[\"SID\",\"MSG\"]}}")
            .statusCode(200);

        call("DescribeRuleGroupSummary", "{\"RuleGroupArn\":\"" + ruleGroupArn("stateful", name) + "\"}")
            .statusCode(200)
            .body("RuleGroupName", equalTo(name))
            .body("Summary.RuleSummaries", hasSize(2))
            .body("Summary.RuleSummaries[0].SID", equalTo("100001"))
            .body("Summary.RuleSummaries[0].Msg", equalTo("allow https"))
            .body("Summary.RuleSummaries[1].SID", equalTo("100002"))
            .body("Summary.RuleSummaries[1].Msg", equalTo("block \"telnet\"; now"))
            .body("Summary.RuleSummaries[1]", not(hasKey("Metadata")));
    }

    @Test
    void describeRuleGroupMetadata_returnsCapacityAndTypeWithoutTheDefinition() {
        String name = "metadata-rule-group";
        call("CreateRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\",\"Capacity\":10,"
                + "\"Description\":\"meta\",\"RuleGroup\":{\"RulesSource\":{\"RulesString\":"
                + "\"pass tcp any any -> any 80 (sid:1;)\"},\"StatefulRuleOptions\":{\"RuleOrder\":\"STRICT_ORDER\"}}}")
            .statusCode(200);

        call("DescribeRuleGroupMetadata", "{\"RuleGroupArn\":\"" + ruleGroupArn("stateful", name) + "\"}")
            .statusCode(200)
            .body("RuleGroupArn", equalTo(ruleGroupArn("stateful", name)))
            .body("RuleGroupName", equalTo(name))
            .body("Capacity", equalTo(10))
            .body("Type", equalTo("STATEFUL"))
            .body("Description", equalTo("meta"))
            .body("StatefulRuleOptions.RuleOrder", equalTo("STRICT_ORDER"))
            .body("$", not(hasKey("RuleGroup")));
    }

    private static final String STATELESS_DEFINITION = "{\"RulesSource\":{\"StatelessRulesAndCustomActions\":"
            + "{\"StatelessRules\":[{\"Priority\":1,\"RuleDefinition\":{\"Actions\":[\"aws:pass\"],"
            + "\"MatchAttributes\":{\"Protocols\":[6]}}}]}}}";

    private static String ruleGroupArn(String kind, String name) {
        return "arn:aws:network-firewall:us-east-1:723679240095:" + kind + "-rulegroup/" + name;
    }

    private static String policyArn(String name) {
        return "arn:aws:network-firewall:us-east-1:723679240095:firewall-policy/" + name;
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String currentUpdateToken(String firewallName) {
        return call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(firewallName) + "\"}")
            .statusCode(200)
            .extract().path("UpdateToken");
    }

    @Test
    void flowCapture_runsAgainstTheFirewallAndCompletesWithNoTrackedFlows() {
        String name = "FlowCaptureFirewall";
        String arn = firewallArn(name);
        createFirewall(name, "", "subnet-f10w00000000000001");
        String filters = "\"FlowFilters\":[{\"SourceAddress\":{\"AddressDefinition\":\"10.78.1.10/32\"}}]";

        String operationId = call("StartFlowCapture", "{\"FirewallArn\":\"" + arn + "\"," + filters + "}")
            .statusCode(200)
            .body("FirewallArn", equalTo(arn))
            .body("FlowOperationStatus", equalTo("IN_PROGRESS"))
            .extract().path("FlowOperationId");

        call("DescribeFlowOperation",
                "{\"FirewallArn\":\"" + arn + "\",\"FlowOperationId\":\"" + operationId + "\"}")
            .statusCode(200)
            .body("FlowOperationId", equalTo(operationId))
            .body("FlowOperationType", equalTo("FLOW_CAPTURE"))
            .body("FlowOperationStatus", equalTo("COMPLETED"))
            .body("FlowOperation.FlowFilters[0].SourceAddress.AddressDefinition", equalTo("10.78.1.10/32"))
            .body("FlowRequestTimestamp", not(nullValue()));

        call("ListFlowOperations", "{\"FirewallArn\":\"" + arn + "\",\"FlowOperationType\":\"FLOW_CAPTURE\"}")
            .statusCode(200)
            .body("FlowOperations.FlowOperationId", contains(operationId))
            .body("FlowOperations[0].FlowOperationStatus", equalTo("COMPLETED"));

        call("ListFlowOperations", "{\"FirewallArn\":\"" + arn + "\",\"FlowOperationType\":\"FLOW_FLUSH\"}")
            .statusCode(200)
            .body("FlowOperations", empty());

        call("ListFlowOperationResults",
                "{\"FirewallArn\":\"" + arn + "\",\"FlowOperationId\":\"" + operationId + "\"}")
            .statusCode(200)
            .body("FlowOperationStatus", equalTo("COMPLETED"))
            .body("Flows", empty());

        call("StartFlowFlush", "{\"FirewallArn\":\"" + arn + "\"," + filters + "}")
            .statusCode(200)
            .body("FlowOperationStatus", equalTo("IN_PROGRESS"));
        call("ListFlowOperations", "{\"FirewallArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("FlowOperations", hasSize(2));
    }

    @Test
    void flowOperations_validateTheFirewallScopeAndFilters() {
        String name = "FlowValidationFirewall";
        String arn = firewallArn(name);
        createFirewall(name, "", "subnet-f10w00000000000002");
        String filters = "\"FlowFilters\":[{\"SourceAddress\":{\"AddressDefinition\":\"10.0.0.1/32\"}}]";

        call("StartFlowCapture", "{\"FirewallArn\":\"" + firewallArn("NoSuchFlowFirewall") + "\"," + filters + "}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        call("StartFlowCapture", "{\"FirewallArn\":\"" + arn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        call("StartFlowCapture", "{\"FirewallArn\":\"" + arn + "\","
                + "\"FlowFilters\":[{\"SourceAddress\":{\"AddressDefinition\":\"not-a-cidr\"}}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        call("StartFlowCapture", "{\"FirewallArn\":\"" + arn + "\",\"AvailabilityZone\":\"us-east-1f\"," + filters + "}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        call("StartFlowCapture", "{\"FirewallArn\":\"" + arn + "\",\"AvailabilityZone\":\"us-east-1a\"," + filters + "}")
            .statusCode(200);
        call("DescribeFlowOperation", "{\"FirewallArn\":\"" + arn + "\",\"FlowOperationId\":\"missing\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void analysisReports_requireAnEnabledAnalysisTypeAndCompleteWithoutFindings() {
        String name = "AnalysisReportFirewall";
        String arn = firewallArn(name);
        createFirewall(name, "", "subnet-a0a1000000000001");

        call("StartAnalysisReport", "{\"FirewallArn\":\"" + arn + "\",\"AnalysisType\":\"TLS_SNI\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        call("ListAnalysisReports", "{\"FirewallArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("AnalysisReports", empty());
        call("GetAnalysisReportResults",
                "{\"FirewallArn\":\"" + arn + "\",\"AnalysisReportId\":\"alchemy-nonexistent-analysis-report-id\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        call("UpdateFirewallAnalysisSettings",
                "{\"FirewallArn\":\"" + arn + "\",\"EnabledAnalysisTypes\":[\"TLS_SNI\"]}")
            .statusCode(200);
        String reportId = call("StartAnalysisReport",
                "{\"FirewallName\":\"" + name + "\",\"AnalysisType\":\"TLS_SNI\"}")
            .statusCode(200)
            .extract().path("AnalysisReportId");

        call("ListAnalysisReports", "{\"FirewallArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("AnalysisReports.AnalysisReportId", contains(reportId))
            .body("AnalysisReports[0].AnalysisType", equalTo("TLS_SNI"))
            .body("AnalysisReports[0].Status", equalTo("COMPLETED"));
        call("GetAnalysisReportResults",
                "{\"FirewallArn\":\"" + arn + "\",\"AnalysisReportId\":\"" + reportId + "\"}")
            .statusCode(200)
            .body("Status", equalTo("COMPLETED"))
            .body("AnalysisType", equalTo("TLS_SNI"))
            .body("AnalysisReportResults", empty());
        call("StartAnalysisReport", "{\"FirewallArn\":\"" + arn + "\",\"AnalysisType\":\"HTTP_HOST\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    private static String firewallArn(String name) {
        return "arn:aws:network-firewall:us-east-1:723679240095:firewall/" + name;
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + action)
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static void createFirewall(String name, String extraFields, String... subnetIds) {
        StringBuilder mappings = new StringBuilder();
        for (String subnetId : subnetIds) {
            if (!mappings.isEmpty()) {
                mappings.append(',');
            }
            mappings.append("{\"SubnetId\":\"").append(subnetId).append("\"}");
        }
        call("CreateFirewall", "{\"FirewallName\":\"" + name + "\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-policy\","
                + "\"VpcId\":\"vpc-0123456789abcdef0\","
                + "\"DeleteProtection\":false,\"SubnetChangeProtection\":false,"
                + "\"FirewallPolicyChangeProtection\":false,"
                + extraFields
                + "\"SubnetMappings\":[" + mappings + "]}")
            .statusCode(200);
    }
}

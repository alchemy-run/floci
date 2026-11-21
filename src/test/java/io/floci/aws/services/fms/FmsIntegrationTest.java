package io.floci.aws.services.fms;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * AWS Firewall Manager over JSON 1.1: an organization's management account designating the default administrator,
 * which then manages policies, applications and protocols lists, resource sets, its notification channel and
 * third-party firewalls, and the refusals accounts outside that role get.
 */
@QuarkusTest
class FmsIntegrationTest {

    private static final String MANAGEMENT = "222233334444";
    private static final String OUTSIDER = "555566667777";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse fms(String account, String action, String body) {
        return given().header("Authorization", auth(account, "fms")).header("X-Amz-Target", "AWSFMS_20180101." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static ValidatableResponse organizations(String action, String body) {
        return given().header("Authorization", auth(MANAGEMENT, "organizations"))
                .header("X-Amz-Target", "AWSOrganizationsV20161128." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    @Test
    void theDefaultAdministratorManagesPoliciesListsAndResourceSets() {
        fms(OUTSIDER, "GetAdminAccount", "{}").statusCode(400).body("__type", containsString("ResourceNotFound"));
        fms(OUTSIDER, "ListPolicies", "{}").statusCode(400).body("__type", containsString("AccessDenied"));
        fms(OUTSIDER, "AssociateAdminAccount", "{\"AdminAccount\":\"" + OUTSIDER + "\"}").statusCode(400)
                .body("__type", containsString("InvalidOperation"));

        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}").statusCode(200);
        fms(MANAGEMENT, "GetAdminAccount", "{}").statusCode(400).body("__type", containsString("ResourceNotFound"));
        fms(MANAGEMENT, "ListAdminsManagingAccount", "{}").statusCode(400)
                .body("__type", containsString("ResourceNotFound"));
        fms(MANAGEMENT, "AssociateAdminAccount", "{\"AdminAccount\":\"" + OUTSIDER + "\"}").statusCode(400)
                .body("__type", containsString("InvalidInput"));
        fms(MANAGEMENT, "AssociateAdminAccount", "{\"AdminAccount\":\"" + MANAGEMENT + "\"}").statusCode(200);
        fms(MANAGEMENT, "GetAdminAccount", "{}").statusCode(200).body("AdminAccount", equalTo(MANAGEMENT))
                .body("RoleStatus", equalTo("READY"));
        fms(MANAGEMENT, "GetAdminScope", "{\"AdminAccount\":\"" + MANAGEMENT + "\"}").statusCode(200)
                .body("Status", equalTo("ONBOARDING_COMPLETE"))
                .body("AdminScope.AccountScope.AllAccountsEnabled", equalTo(true));
        fms(MANAGEMENT, "ListAdminAccountsForOrganization", "{}").statusCode(200)
                .body("AdminAccounts[0].DefaultAdmin", equalTo(true));
        fms(MANAGEMENT, "ListAdminsManagingAccount", "{}").statusCode(200).body("AdminAccounts", hasItem(MANAGEMENT));
        fms(MANAGEMENT, "ListMemberAccounts", "{}").statusCode(200).body("MemberAccounts", hasItem(MANAGEMENT));

        String policy = fms(MANAGEMENT, "PutPolicy", "{\"Policy\":{\"PolicyName\":\"web\",\"SecurityServicePolicyData\":"
                + "{\"Type\":\"WAFV2\",\"ManagedServiceData\":\"{\\\"type\\\":\\\"WAFV2\\\",\\\"defaultAction\\\":"
                + "{\\\"type\\\":\\\"ALLOW\\\"}}\"},\"ResourceType\":\"AWS::ElasticLoadBalancingV2::LoadBalancer\","
                + "\"ExcludeResourceTags\":false,\"RemediationEnabled\":false},\"TagList\":[{\"Key\":\"team\","
                + "\"Value\":\"sec\"}]}").statusCode(200).body("Policy.PolicyUpdateToken", equalTo("1"))
                .body("PolicyArn", containsString(":fms:us-east-1:" + MANAGEMENT + ":policy/"))
                .extract().path("Policy.PolicyId");
        fms(MANAGEMENT, "PutPolicy", "{\"Policy\":{\"PolicyId\":\"" + policy + "\",\"PolicyUpdateToken\":\"9\","
                + "\"PolicyName\":\"web\",\"SecurityServicePolicyData\":{\"Type\":\"WAFV2\"},\"ResourceType\":"
                + "\"AWS::ElasticLoadBalancingV2::LoadBalancer\",\"ExcludeResourceTags\":false,"
                + "\"RemediationEnabled\":false}}").statusCode(400).body("__type", containsString("InvalidOperation"));
        fms(MANAGEMENT, "PutPolicy", "{\"Policy\":{\"PolicyName\":\"bad\",\"SecurityServicePolicyData\":{\"Type\":"
                + "\"WAFV2\",\"ManagedServiceData\":\"{\\\"type\\\":\\\"DNS_FIREWALL\\\"}\"},\"ResourceType\":"
                + "\"AWS::EC2::VPC\",\"ExcludeResourceTags\":false,\"RemediationEnabled\":false}}").statusCode(400)
                .body("__type", containsString("InvalidInput"));
        fms(MANAGEMENT, "ListPolicies", "{}").statusCode(200).body("PolicyList", hasSize(1))
                .body("PolicyList[0].SecurityServiceType", equalTo("WAFV2"));
        fms(MANAGEMENT, "ListComplianceStatus", "{\"PolicyId\":\"" + policy + "\"}").statusCode(200)
                .body("PolicyComplianceStatusList[0].EvaluationResults[0].ComplianceStatus", equalTo("COMPLIANT"));
        fms(MANAGEMENT, "GetComplianceDetail", "{\"PolicyId\":\"" + policy + "\",\"MemberAccount\":\"" + MANAGEMENT
                + "\"}").statusCode(200).body("PolicyComplianceDetail.Violators", hasSize(0));
        String policyArn = "arn:aws:fms:us-east-1:" + MANAGEMENT + ":policy/" + policy;
        fms(MANAGEMENT, "ListTagsForResource", "{\"ResourceArn\":\"" + policyArn + "\"}").statusCode(200)
                .body("TagList[0].Key", equalTo("team"));

        String apps = fms(MANAGEMENT, "PutAppsList", "{\"AppsList\":{\"ListName\":\"web-apps\",\"AppsList\":"
                + "[{\"AppName\":\"web\",\"Protocol\":\"tcp\",\"Port\":8080}]}}").statusCode(200)
                .body("AppsListArn", containsString(":applications-list/")).extract().path("AppsList.ListId");
        fms(MANAGEMENT, "PutAppsList", "{\"AppsList\":{\"ListId\":\"" + apps + "\",\"ListUpdateToken\":\"1\","
                + "\"ListName\":\"web-apps\",\"AppsList\":[{\"AppName\":\"web\",\"Protocol\":\"tcp\",\"Port\":8443}]}}")
                .statusCode(200).body("AppsList.PreviousAppsList.'1'[0].Port", equalTo(8080));
        fms(MANAGEMENT, "ListAppsLists", "{\"MaxResults\":10,\"DefaultLists\":true}").statusCode(200)
                .body("AppsLists[0].ListName", equalTo("FMS-Default-Public-Access-Apps-Allowed"));
        fms(MANAGEMENT, "PutProtocolsList", "{\"ProtocolsList\":{\"ListName\":\"protocols\",\"ProtocolsList\":"
                + "[\"tcp\"]}}").statusCode(200);
        fms(MANAGEMENT, "ListProtocolsLists", "{\"MaxResults\":10}").statusCode(200)
                .body("ProtocolsLists", hasSize(1));

        String set = fms(MANAGEMENT, "PutResourceSet", "{\"ResourceSet\":{\"Name\":\"albs\",\"ResourceTypeList\":"
                + "[\"AWS::ElasticLoadBalancingV2::LoadBalancer\"]}}").statusCode(200)
                .extract().path("ResourceSet.Id");
        fms(MANAGEMENT, "BatchAssociateResource", "{\"ResourceSetIdentifier\":\"" + set + "\",\"Items\":["
                + "\"arn:aws:elasticloadbalancing:us-east-1:" + MANAGEMENT + ":loadbalancer/app/a/1\",\"not-an-arn\"]}")
                .statusCode(200).body("FailedItems[0].Reason", equalTo("NOT_VALID_ARN"));
        fms(MANAGEMENT, "ListResourceSetResources", "{\"Identifier\":\"" + set + "\"}").statusCode(200)
                .body("Items", hasSize(1)).body("Items[0].AccountId", equalTo(MANAGEMENT));

        fms(MANAGEMENT, "GetNotificationChannel", "{}").statusCode(400)
                .body("__type", containsString("ResourceNotFound"));
        fms(MANAGEMENT, "GetThirdPartyFirewallAssociationStatus", "{\"ThirdPartyFirewall\":"
                + "\"PALO_ALTO_NETWORKS_CLOUD_NGFW\"}").statusCode(200)
                .body("ThirdPartyFirewallStatus", equalTo("NOT_EXIST"));
        fms(MANAGEMENT, "AssociateThirdPartyFirewall", "{\"ThirdPartyFirewall\":\"PALO_ALTO_NETWORKS_CLOUD_NGFW\"}")
                .statusCode(200);
        fms(MANAGEMENT, "GetThirdPartyFirewallAssociationStatus", "{\"ThirdPartyFirewall\":"
                + "\"PALO_ALTO_NETWORKS_CLOUD_NGFW\"}").statusCode(200)
                .body("ThirdPartyFirewallStatus", equalTo("ONBOARD_COMPLETE"));

        fms(MANAGEMENT, "DeletePolicy", "{\"PolicyId\":\"" + policy + "\"}").statusCode(200);
        fms(MANAGEMENT, "GetPolicy", "{\"PolicyId\":\"" + policy + "\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFound"));
        fms(MANAGEMENT, "DisassociateAdminAccount", "{}").statusCode(200);
        fms(MANAGEMENT, "GetAdminAccount", "{}").statusCode(400).body("__type", containsString("ResourceNotFound"));
        fms(MANAGEMENT, "ListPolicies", "{}").statusCode(400).body("__type", containsString("AccessDenied"));
        organizations("DeleteOrganization", "{}").statusCode(200);
    }
}

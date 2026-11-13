package io.floci.aws.services.opensearchserverless;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenSearch Serverless without a search engine ({@code engine: none}): security, access and lifecycle policies with
 * their versions, security configurations, account settings, collection groups, collections and the encryption policy
 * they need, VPC endpoints and tags. Collections serve indexes in {@link AossDockerIntegrationTest}.
 */
@QuarkusTest
class AossIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification aoss(String action, String body) {
        return given().header("Authorization", auth("aoss")).header("X-Amz-Target", "OpenSearchServerless." + action)
                .contentType("application/x-amz-json-1.0").body(body);
    }

    static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String encryption(String pattern) {
        return json("{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + pattern + "\"]}],"
                + "\"AWSOwnedKey\":true}");
    }

    static String network(String pattern) {
        return json("[{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + pattern + "\"]}],"
                + "\"AllowFromPublic\":true}]");
    }

    static String data(String collection, String principal) {
        return json("[{\"Rules\":[{\"ResourceType\":\"index\",\"Resource\":[\"index/" + collection + "/*\"],"
                + "\"Permission\":[\"aoss:*\"]},{\"ResourceType\":\"collection\",\"Resource\":[\"collection/"
                + collection + "\"],\"Permission\":[\"aoss:DescribeCollectionItems\"]}],\"Principal\":[\"" + principal
                + "\"]}]");
    }

    static void awaitActive(String id) {
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(1)).until(() -> "ACTIVE".equals(
                aoss("BatchGetCollection", "{\"ids\":[\"" + id + "\"]}").post("/").then().extract()
                        .path("collectionDetails[0].status")));
    }

    @Test
    void policiesHaveVersionsAndAreChecked() {
        aoss("CreateSecurityPolicy", "{\"type\":\"encryption\",\"name\":\"bad-enc\",\"policy\":"
                + json("{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/x\"]}]}") + "}")
                .post("/").then().statusCode(400).body("message", containsString("AWSOwnedKey"));
        aoss("CreateSecurityPolicy", "{\"type\":\"network\",\"name\":\"bad-net\",\"policy\":"
                + json("[{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/x\"]}]}]") + "}")
                .post("/").then().statusCode(400);
        aoss("CreateAccessPolicy", "{\"type\":\"data\",\"name\":\"bad-data\",\"policy\":" + json("[{\"Rules\":[{"
                + "\"ResourceType\":\"index\",\"Resource\":[\"index/x/*\"],\"Permission\":[\"aoss:CreateCollectionItems\""
                + "]}],\"Principal\":[\"arn:aws:iam::000000000000:root\"]}]") + "}").post("/").then().statusCode(400)
                .body("message", containsString("doesn't apply"));
        aoss("CreateSecurityPolicy", "{\"type\":\"network\",\"name\":\"dash-net\",\"policy\":" + json("[{\"Rules\":[{"
                + "\"ResourceType\":\"dashboard\",\"Resource\":[\"collection/logs\"]}],\"AllowFromPublic\":true}]") + "}")
                .post("/").then().statusCode(200);
        aoss("DeleteSecurityPolicy", "{\"type\":\"network\",\"name\":\"dash-net\"}").post("/").then().statusCode(200);
        String version = aoss("CreateSecurityPolicy", "{\"type\":\"network\",\"name\":\"policy-net\",\"description\":"
                + "\"v1\",\"policy\":" + network("logs*") + "}").post("/").then().statusCode(200)
                .body("securityPolicyDetail.policy[0].AllowFromPublic", equalTo(true)).extract()
                .path("securityPolicyDetail.policyVersion");
        aoss("CreateSecurityPolicy", "{\"type\":\"network\",\"name\":\"policy-net\",\"policy\":" + network("x") + "}")
                .post("/").then().statusCode(400).body("__type", containsString("ConflictException"));
        String same = aoss("UpdateSecurityPolicy", "{\"type\":\"network\",\"name\":\"policy-net\",\"policyVersion\":\""
                + version + "\",\"description\":\"v1\"}").post("/").then().statusCode(200).extract()
                .path("securityPolicyDetail.policyVersion");
        assertEquals(version, same);
        String next = aoss("UpdateSecurityPolicy", "{\"type\":\"network\",\"name\":\"policy-net\",\"policyVersion\":\""
                + version + "\",\"policy\":" + network("metrics*") + "}").post("/").then().statusCode(200).extract()
                .path("securityPolicyDetail.policyVersion");
        assertNotEquals(version, next);
        aoss("UpdateSecurityPolicy", "{\"type\":\"network\",\"name\":\"policy-net\",\"policyVersion\":\"" + version
                + "\",\"description\":\"stale\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ConflictException"));
        aoss("ListSecurityPolicies", "{\"type\":\"network\",\"resource\":[\"collection/metrics-a\"]}").post("/")
                .then().statusCode(200).body("securityPolicySummaries.name", hasItem("policy-net"));
        aoss("ListSecurityPolicies", "{\"type\":\"network\",\"resource\":[\"collection/other\"]}").post("/").then()
                .statusCode(200).body("securityPolicySummaries.name", not(hasItem("policy-net")));

        aoss("CreateLifecyclePolicy", "{\"type\":\"retention\",\"name\":\"keep\",\"policy\":" + json("{\"Rules\":[{"
                + "\"ResourceType\":\"index\",\"Resource\":[\"index/logs/*\"],\"MinIndexRetention\":\"24h\"},{"
                + "\"ResourceType\":\"index\",\"Resource\":[\"index/logs/audit*\"],\"MinIndexRetention\":\"30d\"}]}")
                + "}").post("/").then().statusCode(200);
        aoss("CreateLifecyclePolicy", "{\"type\":\"retention\",\"name\":\"short\",\"policy\":" + json("{\"Rules\":[{"
                + "\"ResourceType\":\"index\",\"Resource\":[\"index/x/*\"],\"MinIndexRetention\":\"2h\"}]}") + "}")
                .post("/").then().statusCode(400);
        aoss("BatchGetEffectiveLifecyclePolicy", "{\"resourceIdentifiers\":[{\"type\":\"retention\",\"resource\":"
                + "\"index/logs/audit-2026\"},{\"type\":\"retention\",\"resource\":\"index/other/a\"}]}").post("/")
                .then().statusCode(200).body("effectiveLifecyclePolicyDetails[0].retentionPeriod", equalTo("30d"))
                .body("effectiveLifecyclePolicyErrorDetails", hasSize(1));
        aoss("BatchGetLifecyclePolicy", "{\"identifiers\":[{\"type\":\"retention\",\"name\":\"keep\"},{\"type\":"
                + "\"retention\",\"name\":\"nope\"}]}").post("/").then().statusCode(200)
                .body("lifecyclePolicyDetails", hasSize(1)).body("lifecyclePolicyErrorDetails", hasSize(1));

        String config = aoss("CreateSecurityConfig", "{\"type\":\"saml\",\"name\":\"idp\",\"samlOptions\":{\"metadata\":"
                + "\"<EntityDescriptor/>\",\"groupAttribute\":\"groups\"}}").post("/").then().statusCode(200)
                .body("securityConfigDetail.samlOptions.sessionTimeout", equalTo(60))
                .extract().path("securityConfigDetail.id");
        assertTrue(config.startsWith("saml/000000000000/idp"), config);
        aoss("GetSecurityConfig", "{\"id\":\"" + config + "\"}").post("/").then().statusCode(200)
                .body("securityConfigDetail.type", equalTo("saml"));
        aoss("GetPoliciesStats", "{}").post("/").then().statusCode(200)
                .body("SecurityPolicyStats.NetworkPolicyCount", equalTo(1))
                .body("LifecyclePolicyStats.RetentionPolicyCount", equalTo(1))
                .body("SecurityConfigStats.SamlConfigCount", equalTo(1));
        aoss("DeleteSecurityConfig", "{\"id\":\"" + config + "\"}").post("/").then().statusCode(200);
        aoss("DeleteSecurityPolicy", "{\"type\":\"network\",\"name\":\"policy-net\"}").post("/").then()
                .statusCode(200);
        aoss("GetSecurityPolicy", "{\"type\":\"network\",\"name\":\"policy-net\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        aoss("DeleteLifecyclePolicy", "{\"type\":\"retention\",\"name\":\"keep\"}").post("/").then().statusCode(200);

        aoss("UpdateAccountSettings", "{\"capacityLimits\":{\"maxIndexingCapacityInOCU\":20}}").post("/").then()
                .statusCode(200).body("accountSettingsDetail.capacityLimits.maxIndexingCapacityInOCU", equalTo(20))
                .body("accountSettingsDetail.capacityLimits.maxSearchCapacityInOCU", equalTo(10));
    }

    @Test
    void collectionsNeedEncryptionPoliciesAndGroups() {
        aoss("CreateCollection", "{\"name\":\"unencrypted\"}").post("/").then().statusCode(400)
                .body("message", containsString("No matching security policy of encryption type"));
        aoss("CreateSecurityPolicy", "{\"type\":\"encryption\",\"name\":\"enc-all\",\"policy\":" + encryption("col-*")
                + "}").post("/").then().statusCode(200);
        String group = aoss("CreateCollectionGroup", "{\"name\":\"team\",\"standbyReplicas\":\"DISABLED\",\"tags\":"
                + "[{\"key\":\"owner\",\"value\":\"search\"}]}").post("/").then().statusCode(200)
                .body("createCollectionGroupDetail.tags[0].key", equalTo("owner")).extract()
                .path("createCollectionGroupDetail.id");
        aoss("CreateCollection", "{\"name\":\"col-x\",\"collectionGroupName\":\"absent\"}").post("/").then()
                .statusCode(400);
        String id = aoss("CreateCollection", "{\"name\":\"col-search\",\"type\":\"VECTORSEARCH\",\"standbyReplicas\":"
                        + "\"DISABLED\",\"collectionGroupName\":\"team\",\"deletionProtection\":\"ENABLED\",\"tags\":"
                        + "[{\"key\":\"k\",\"value\":\"v\"}]}").post("/").then().statusCode(200)
                .body("createCollectionDetail.status", equalTo("CREATING"))
                .body("createCollectionDetail.kmsKeyArn", equalTo("auto")).extract().path("createCollectionDetail.id");
        assertEquals(20, id.length());
        awaitActive(id);
        aoss("BatchGetCollection", "{\"names\":[\"col-search\",\"missing\"]}").post("/").then().statusCode(200)
                .body("collectionDetails[0].collectionEndpoint", containsString(id + ".us-east-1.aoss."))
                .body("collectionDetails[0].dashboardEndpoint", containsString("/_dashboards"))
                .body("collectionDetails[0].type", equalTo("VECTORSEARCH"))
                .body("collectionErrorDetails[0].name", equalTo("missing"));
        aoss("BatchGetCollectionGroup", "{\"ids\":[\"" + group + "\"]}").post("/").then().statusCode(200)
                .body("collectionGroupDetails[0].numberOfCollections", equalTo(1));
        aoss("DeleteCollectionGroup", "{\"id\":\"" + group + "\"}").post("/").then().statusCode(400);
        aoss("ListCollections", "{\"collectionFilters\":{\"collectionGroupName\":\"team\"}}").post("/").then()
                .statusCode(200).body("collectionSummaries.id", hasItem(id));
        String arn = "arn:aws:aoss:us-east-1:000000000000:collection/" + id;
        aoss("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"env\",\"value\":\"dev\"}]}")
                .post("/").then().statusCode(200);
        aoss("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}").post("/").then().statusCode(200)
                .body("tags.key", hasItem("env")).body("tags.key", hasItem("k"));
        aoss("CreateIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\"}").post("/").then().statusCode(403)
                .body("message", containsString("No network policy"));

        aoss("DeleteCollection", "{\"id\":\"" + id + "\"}").post("/").then().statusCode(400)
                .body("message", containsString("deletion protection"));
        aoss("UpdateCollection", "{\"id\":\"" + id + "\",\"deletionProtection\":\"DISABLED\",\"description\":\"d\"}")
                .post("/").then().statusCode(200).body("updateCollectionDetail.description", equalTo("d"));
        aoss("DeleteCollection", "{\"id\":\"" + id + "\"}").post("/").then().statusCode(200)
                .body("deleteCollectionDetail.status", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(20)).until(() -> aoss("BatchGetCollection", "{\"ids\":[\"" + id + "\"]}")
                .post("/").then().extract().<List<Object>>path("collectionDetails").isEmpty());
        aoss("DeleteCollectionGroup", "{\"id\":\"" + group + "\"}").post("/").then().statusCode(200);
    }

    @Test
    void vpcEndpointsUseSubnetsOfTheirVpc() {
        String vpc = given().header("Authorization", auth("ec2")).formParam("Action", "CreateVpc")
                .formParam("Version", "2016-11-15").formParam("CidrBlock", "10.81.0.0/16").post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        String subnet = given().header("Authorization", auth("ec2")).formParam("Action", "CreateSubnet")
                .formParam("Version", "2016-11-15").formParam("VpcId", vpc).formParam("CidrBlock", "10.81.1.0/24")
                .post("/").then().statusCode(200).extract().xmlPath().getString("CreateSubnetResponse.subnet.subnetId");
        aoss("CreateVpcEndpoint", "{\"name\":\"bad\",\"vpcId\":\"vpc-0000000\",\"subnetIds\":[\"" + subnet + "\"]}")
                .post("/").then().statusCode(400);
        String id = aoss("CreateVpcEndpoint", "{\"name\":\"private\",\"vpcId\":\"" + vpc + "\",\"subnetIds\":[\""
                + subnet + "\"]}").post("/").then().statusCode(200)
                .body("createVpcEndpointDetail.status", equalTo("PENDING")).extract()
                .path("createVpcEndpointDetail.id");
        assertTrue(id.startsWith("vpce-"), id);
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(aoss("BatchGetVpcEndpoint",
                "{\"ids\":[\"" + id + "\"]}").post("/").then().extract().path("vpcEndpointDetails[0].status")));
        aoss("UpdateVpcEndpoint", "{\"id\":\"" + id + "\",\"removeSubnetIds\":[\"" + subnet + "\"]}").post("/")
                .then().statusCode(400);
        aoss("ListVpcEndpoints", "{\"vpcEndpointFilters\":{\"status\":\"ACTIVE\"}}").post("/").then().statusCode(200)
                .body("vpcEndpointSummaries.id", hasItem(id));
        aoss("DeleteVpcEndpoint", "{\"id\":\"" + id + "\"}").post("/").then().statusCode(200);
    }
}

package io.floci.aws.services.shield;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Shield Advanced: the subscription gate, protections on resources that must exist, health checks, protection groups
 * whose members follow their pattern, application-layer response limits, Shield Response Team access, tags, and the
 * commitment that locks the subscription.
 */
@QuarkusTest
class ShieldIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification shield(String action, String body) {
        return given().header("Authorization", auth("shield")).header("X-Amz-Target", "AWSShield_20160616." + action)
                .contentType("application/x-amz-json-1.1").body(body);
    }

    static RequestSpecification query(String service) {
        return given().header("Authorization", auth(service)).contentType("application/x-www-form-urlencoded");
    }

    static String xmlValue(String xml, String element) {
        return xml.replaceAll("(?s).*<" + element + ">([^<]+)</" + element + ">.*", "$1");
    }

    @Test
    void protectionsGroupsAndAccess() {
        // Without a subscription.
        shield("GetSubscriptionState", "{}").post("/").then().statusCode(200)
                .body("SubscriptionState", equalTo("INACTIVE"));
        shield("DescribeSubscription", "{}").post("/").then().statusCode(400)
                .body("message", equalTo("The subscription does not exist."));
        shield("DescribeProtectionGroup", "{\"ProtectionGroupId\":\"nope\"}").post("/").then().statusCode(400)
                .body("message", containsString("protection group nope"));
        shield("DescribeAttackStatistics", "{}").post("/").then().statusCode(200).body("DataItems", hasSize(0));

        shield("CreateSubscription", "{}").post("/").then().statusCode(200);
        shield("CreateSubscription", "{}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceAlreadyExistsException"));
        shield("DescribeSubscription", "{}").post("/").then().statusCode(200)
                .body("Subscription.TimeCommitmentInSeconds", equalTo(31536000))
                .body("Subscription.SubscriptionLimits.ProtectionGroupLimits.MaxProtectionGroups", equalTo(100));
        shield("DeleteSubscription", "{}").post("/").then().statusCode(400)
                .body("__type", containsString("LockedSubscriptionException"));

        // Protections must name a resource that exists.
        String allocation = xmlValue(query("ec2").formParam("Action", "AllocateAddress").formParam("Version",
                "2016-11-15").formParam("Domain", "vpc").post("/").then().statusCode(200).extract().asString(),
                "allocationId");
        String eip = "arn:aws:ec2:us-east-1:000000000000:eip-allocation/" + allocation;
        shield("CreateProtection", "{\"Name\":\"x\",\"ResourceArn\":\"arn:aws:ec2:us-east-1:000000000000:"
                + "eip-allocation/eipalloc-00000000\"}").post("/").then().statusCode(400)
                .body("__type", containsString("InvalidResourceException"));
        shield("CreateProtection", "{\"Name\":\"x\",\"ResourceArn\":\"arn:aws:sqs:us-east-1:000000000000:q\"}")
                .post("/").then().statusCode(400);
        String protection = shield("CreateProtection", "{\"Name\":\"edge-ip\",\"ResourceArn\":\"" + eip
                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"net\"}]}").post("/").then().statusCode(200).extract()
                .path("ProtectionId");
        shield("CreateProtection", "{\"Name\":\"again\",\"ResourceArn\":\"" + eip + "\"}").post("/").then()
                .statusCode(400).body("__type", containsString("ResourceAlreadyExistsException"));
        String protectionArn = shield("DescribeProtection", "{\"ResourceArn\":\"" + eip + "\"}").post("/").then()
                .statusCode(200).body("Protection.Id", equalTo(protection)).extract().path("Protection.ProtectionArn");
        shield("EnableApplicationLayerAutomaticResponse", "{\"ResourceArn\":\"" + eip + "\",\"Action\":"
                + "{\"Block\":{}}}").post("/").then().statusCode(400)
                .body("message", containsString("CloudFront distributions and Application Load Balancers"));

        // Health checks come from Route 53.
        shield("AssociateHealthCheck", "{\"ProtectionId\":\"" + protection + "\",\"HealthCheckArn\":"
                + "\"arn:aws:route53:::healthcheck/missing\"}").post("/").then().statusCode(400);
        String healthCheck = xmlValue(given().header("Authorization", auth("route53")).contentType("application/xml")
                .body("<CreateHealthCheckRequest xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\">"
                        + "<CallerReference>shield-1</CallerReference><HealthCheckConfig><IPAddress>192.0.2.10"
                        + "</IPAddress><Port>80</Port><Type>HTTP</Type><ResourcePath>/</ResourcePath>"
                        + "</HealthCheckConfig></CreateHealthCheckRequest>").post("/2013-04-01/healthcheck").then()
                .statusCode(201).extract().asString(), "Id");
        shield("AssociateHealthCheck", "{\"ProtectionId\":\"" + protection + "\",\"HealthCheckArn\":"
                + "\"arn:aws:route53:::healthcheck/" + healthCheck + "\"}").post("/").then().statusCode(200);
        shield("DescribeProtection", "{\"ProtectionId\":\"" + protection + "\"}").post("/").then()
                .body("Protection.HealthCheckIds", equalTo(List.of(healthCheck)));

        // Groups: members follow the pattern.
        shield("CreateProtectionGroup", "{\"ProtectionGroupId\":\"all\",\"Aggregation\":\"SUM\",\"Pattern\":\"ALL\"}")
                .post("/").then().statusCode(200);
        shield("CreateProtectionGroup", "{\"ProtectionGroupId\":\"ips\",\"Aggregation\":\"MAX\",\"Pattern\":"
                + "\"BY_RESOURCE_TYPE\",\"ResourceType\":\"ELASTIC_IP_ALLOCATION\"}").post("/").then().statusCode(200);
        shield("CreateProtectionGroup", "{\"ProtectionGroupId\":\"pick\",\"Aggregation\":\"SUM\",\"Pattern\":"
                + "\"ARBITRARY\",\"Members\":[\"arn:aws:cloudfront::000000000000:distribution/E1\"]}").post("/")
                .then().statusCode(400).body("message", containsString("isn't a protected resource"));
        shield("CreateProtectionGroup", "{\"ProtectionGroupId\":\"bad\",\"Aggregation\":\"SUM\",\"Pattern\":"
                + "\"ALL\",\"ResourceType\":\"ELASTIC_IP_ALLOCATION\"}").post("/").then().statusCode(400);
        shield("ListResourcesInProtectionGroup", "{\"ProtectionGroupId\":\"all\"}").post("/").then()
                .body("ResourceArns", equalTo(List.of(eip)));
        shield("ListResourcesInProtectionGroup", "{\"ProtectionGroupId\":\"ips\"}").post("/").then()
                .body("ResourceArns", equalTo(List.of(eip)));
        shield("UpdateProtectionGroup", "{\"ProtectionGroupId\":\"all\",\"Aggregation\":\"MEAN\",\"Pattern\":"
                + "\"ALL\"}").post("/").then().statusCode(200);
        shield("DescribeProtectionGroup", "{\"ProtectionGroupId\":\"all\"}").post("/").then()
                .body("ProtectionGroup.Aggregation", equalTo("MEAN"))
                .body("ProtectionGroup.Members", equalTo(List.of(eip)));
        shield("ListProtectionGroups", "{\"InclusionFilters\":{\"Patterns\":[\"BY_RESOURCE_TYPE\"]}}").post("/")
                .then().body("ProtectionGroups.ProtectionGroupId", equalTo(List.of("ips")));

        // Shield Response Team access needs a role it may assume, with the access policy.
        shield("AssociateDRTLogBucket", "{\"LogBucket\":\"flow-logs\"}").post("/").then().statusCode(400)
                .body("__type", containsString("NoAssociatedRoleException"));
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"drt.shield.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        String role = xmlValue(query("iam").formParam("Action", "CreateRole").formParam("Version", "2010-05-08")
                .formParam("RoleName", "ShieldDrt").formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().asString(), "Arn");
        shield("AssociateDRTRole", "{\"RoleArn\":\"" + role + "\"}").post("/").then().statusCode(400)
                .body("message", containsString("AWSShieldDRTAccessPolicy"));
        query("iam").formParam("Action", "AttachRolePolicy").formParam("Version", "2010-05-08")
                .formParam("RoleName", "ShieldDrt")
                .formParam("PolicyArn", "arn:aws:iam::aws:policy/service-role/AWSShieldDRTAccessPolicy").post("/")
                .then().statusCode(200);
        shield("AssociateDRTRole", "{\"RoleArn\":\"" + role + "\"}").post("/").then().statusCode(200);
        given().header("Authorization", auth("s3")).put("/flow-logs").then().statusCode(200);
        shield("AssociateDRTLogBucket", "{\"LogBucket\":\"flow-logs\"}").post("/").then().statusCode(200);
        shield("DescribeDRTAccess", "{}").post("/").then().body("RoleArn", equalTo(role))
                .body("LogBucketList", equalTo(List.of("flow-logs")));

        // Proactive engagement needs a phone number.
        shield("EnableProactiveEngagement", "{}").post("/").then().statusCode(400);
        shield("AssociateProactiveEngagementDetails", "{\"EmergencyContactList\":[{\"EmailAddress\":"
                + "\"soc@example.com\",\"PhoneNumber\":\"+12065550100\"}]}").post("/").then().statusCode(200);
        shield("DescribeSubscription", "{}").post("/").then()
                .body("Subscription.ProactiveEngagementStatus", equalTo("ENABLED"));

        // Tags, and removing a protection drops it from groups.
        shield("TagResource", "{\"ResourceARN\":\"" + protectionArn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":"
                + "\"prod\"}]}").post("/").then().statusCode(200);
        shield("ListTagsForResource", "{\"ResourceARN\":\"" + protectionArn + "\"}").post("/").then()
                .body("Tags.Key", equalTo(List.of("team", "env")));
        shield("DeleteProtection", "{\"ProtectionId\":\"" + protection + "\"}").post("/").then().statusCode(200);
        shield("ListResourcesInProtectionGroup", "{\"ProtectionGroupId\":\"all\"}").post("/").then()
                .body("ResourceArns", hasSize(0));
        shield("DescribeProtection", "{\"ProtectionId\":\"" + protection + "\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }
}

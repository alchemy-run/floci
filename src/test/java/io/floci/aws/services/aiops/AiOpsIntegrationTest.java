package io.floci.aws.services.aiops;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;

/** CloudWatch investigation groups: one per account and Region, with their role, policy and tags. */
@QuarkusTest
class AiOpsIntegrationTest {
    private static final String TRUST = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"aiops.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static RequestSpecification aiops(String region) {
        return given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/aiops/aws4_request");
    }

    private static String role(String name, String trust) {
        return given().formParam("Action", "CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", trust)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/iam/aws4_request")
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    @Test
    void anInvestigationGroupIsCreatedChangedAndDeleted() {
        String region = "eu-west-3";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleArn = role("aiops-" + suffix, TRUST);
        String untrusted = role("aiops-untrusted-" + suffix, TRUST.replace("aiops.amazonaws.com",
                "lambda.amazonaws.com"));

        aiops(region).body("{\"name\":\"ops\",\"roleArn\":\"" + untrusted + "\"}").post("/investigationGroups")
                .then().statusCode(400).body("message", containsString("does not trust"));
        aiops(region).body("{\"name\":\"ops\",\"roleArn\":\"" + roleArn + "\",\"retentionInDays\":3}")
                .post("/investigationGroups").then().statusCode(400);

        String arn = aiops(region).body("{\"name\":\"ops\",\"roleArn\":\"" + roleArn + "\",\"retentionInDays\":7,"
                        + "\"tagKeyBoundaries\":[\"Application\"],\"tags\":{\"team\":\"sre\"}}")
                .post("/investigationGroups").then().statusCode(201)
                .body("arn", matchesPattern("arn:aws:aiops:eu-west-3:\\d{12}:investigation-group/[A-Za-z0-9]{16}"))
                .extract().path("arn");
        aiops(region).body("{\"name\":\"second\",\"roleArn\":\"" + roleArn + "\"}").post("/investigationGroups")
                .then().statusCode(409).body("__type", containsString("ConflictException"));

        aiops(region).get("/investigationGroups/ops").then().statusCode(200)
                .body("arn", equalTo(arn)).body("retentionInDays", equalTo(7))
                .body("encryptionConfiguration.type", equalTo("AWS_OWNED_KEY"))
                .body("tagKeyBoundaries", hasItem("Application"))
                .body("isCloudTrailEventHistoryEnabled", equalTo(false));
        aiops(region).get("/investigationGroups").then().statusCode(200)
                .body("investigationGroups", hasSize(1)).body("investigationGroups[0].name", equalTo("ops"));

        aiops(region).body("{\"tagKeyBoundaries\":[\"Application\",\"Service\"],\"isCloudTrailEventHistoryEnabled\":true}")
                .patch("/investigationGroups/" + arn).then().statusCode(200);
        aiops(region).urlEncodingEnabled(false).get("/investigationGroups/" + arn.replace("/", "%2F")).then()
                .statusCode(200).body("tagKeyBoundaries", hasItem("Service")).body("isCloudTrailEventHistoryEnabled", equalTo(true));

        aiops(region).get("/investigationGroups/ops/policy").then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"aiops.alarms.cloudwatch.amazonaws.com\"},\"Action\":[\"aiops:CreateInvestigation\"],"
                + "\"Resource\":\"*\"}]}";
        aiops(region).body("{\"policy\":" + quoted(policy) + "}").post("/investigationGroups/ops/policy")
                .then().statusCode(200).body("investigationGroupArn", equalTo(arn));
        aiops(region).get("/investigationGroups/ops/policy").then().statusCode(200).body("policy", equalTo(policy));
        aiops(region).delete("/investigationGroups/ops/policy").then().statusCode(200);

        aiops(region).get("/tags/" + arn).then().statusCode(200).body("tags.team", equalTo("sre"));
        aiops(region).body("{\"tags\":{\"env\":\"dev\"}}").post("/tags/" + arn).then().statusCode(200);
        aiops(region).delete("/tags/" + arn + "?tagKeys=team").then().statusCode(200);
        aiops(region).get("/tags/" + arn).then().statusCode(200).body("tags.env", equalTo("dev"))
                .body("tags.team", equalTo(null));

        aiops(region).delete("/investigationGroups/ops").then().statusCode(200);
        aiops(region).get("/investigationGroups/" + arn).then().statusCode(404);
        aiops(region).get("/investigationGroups").then().statusCode(200).body("investigationGroups", hasSize(0));
    }

    @Test
    void aMissingGroupIsNotFound() {
        aiops("ap-south-2").get("/investigationGroups/nonexistent0000000000").then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    private static String quoted(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

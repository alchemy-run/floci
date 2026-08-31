package io.github.hectorvent.floci.services.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Image Builder instance-profile managed policy Alchemy ImageBuilder tests
 * attach. AttachRolePolicy fails with NoSuchEntity if it is missing from the
 * seed catalog.
 */
@QuarkusTest
class EC2InstanceProfileForImageBuilderIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static final String POLICY_ARN =
            "arn:aws:iam::aws:policy/EC2InstanceProfileForImageBuilder";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    @Test
    void getAndAttachImageBuilderInstanceProfilePolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("EC2InstanceProfileForImageBuilder"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(POLICY_ARN));

        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "it-imagebuilder-instance-profile-role")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "it-imagebuilder-instance-profile-role")
            .formParam("PolicyArn", POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "it-imagebuilder-instance-profile-role")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore")
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}

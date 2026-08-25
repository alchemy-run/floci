package io.github.hectorvent.floci.services.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * DMS VPC management managed policy Alchemy ReplicationSubnetGroup attaches.
 * AttachRolePolicy fails with NoSuchEntity if it is missing from the seed catalog.
 */
@QuarkusTest
class AmazonDMSVPCManagementRoleIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static final String POLICY_ARN =
            "arn:aws:iam::aws:policy/service-role/AmazonDMSVPCManagementRole";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"dms.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @Test
    void getAndAttachDmsVpcManagementRolePolicy() {
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
                    equalTo("AmazonDMSVPCManagementRole"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(POLICY_ARN));

        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "it-dms-vpc-role-attach")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "it-dms-vpc-role-attach")
            .formParam("PolicyArn", POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}

package io.floci.aws.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * IAM names are not case sensitive, so an AWS managed policy ARN resolves in any case: AWS documents and samples
 * name {@code AwsGlueDataBrewServiceRole}, which attaches the {@code AWSGlueDataBrewServiceRole} AWS publishes.
 */
@QuarkusTest
class IamManagedPolicyCaseIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";
    private static final String CANONICAL = "arn:aws:iam::aws:policy/service-role/AWSGlueDataBrewServiceRole";
    private static final String OTHER_CASE = "arn:aws:iam::aws:policy/service-role/AwsGlueDataBrewServiceRole";

    private static ValidatableResponse iam(String action, String... pairs) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH).formParam("Action", action);
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            request = request.formParam(pairs[index], pairs[index + 1]);
        }
        return request.post("/").then();
    }

    @Test
    void managedPolicyArnsResolveInAnyCase() {
        String role = "case-role-" + Long.toString(System.nanoTime(), 36);
        iam("CreateRole", "RoleName", role, "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":"
                + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"databrew.amazonaws.com\"},"
                + "\"Action\":\"sts:AssumeRole\"}]}").statusCode(200);
        iam("GetPolicy", "PolicyArn", OTHER_CASE).statusCode(200)
                .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(CANONICAL));
        iam("AttachRolePolicy", "RoleName", role, "PolicyArn", OTHER_CASE).statusCode(200);
        iam("ListAttachedRolePolicies", "RoleName", role).statusCode(200)
                .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.AttachedPolicies.member"
                        + ".PolicyArn", equalTo(CANONICAL));
        iam("DetachRolePolicy", "RoleName", role, "PolicyArn", OTHER_CASE).statusCode(200);
        iam("ListAttachedRolePolicies", "RoleName", role).statusCode(200)
                .body(not(containsString("DataBrew")));
        iam("GetPolicy", "PolicyArn", "arn:aws:iam::aws:policy/service-role/AWSGlueDataBrewServiceRoleX")
                .statusCode(404);
        iam("DeleteRole", "RoleName", role).statusCode(200);
    }
}

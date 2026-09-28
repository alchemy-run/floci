package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Least-privilege Lambda execution roles (assumed-role sessions) against Secrets Manager with
 * global IAM enforcement off: as on AWS, a role granted only {@code secretsmanager:GetSecretValue}
 * on a secret reads it (by full ARN or by name) but is denied every other operation, unless the
 * secret's resource policy grants it.
 */
@QuarkusTest
class SecretsManagerRoleSessionAccessIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @Inject
    IamService iamService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSecretValueOnlyRoleIsDeniedDescribeSecret() {
        String name = "role-session-secret-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = createSecret(name);
        String session = roleSession(arn, "secretsmanager:GetSecretValue");

        call(session, "GetSecretValue", "{\"SecretId\":\"" + arn + "\"}")
                .then().statusCode(200).body("SecretString", equalTo("value"));
        call(session, "GetSecretValue", "{\"SecretId\":\"" + name + "\"}")
                .then().statusCode(200).body("ARN", equalTo(arn));

        call(session, "DescribeSecret", "{\"SecretId\":\"" + arn + "\"}")
                .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
        call(session, "DescribeSecret", "{\"SecretId\":\"" + name + "\"}")
                .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
        call(session, "PutSecretValue", "{\"SecretId\":\"" + arn + "\",\"SecretString\":\"other\"}")
                .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
        call(session, "ListSecrets", "{}")
                .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void rolesScopedToOtherSecretsAreDenied() {
        String bound = createSecret("role-session-bound-" + UUID.randomUUID().toString().substring(0, 8));
        String other = createSecret("role-session-other-" + UUID.randomUUID().toString().substring(0, 8));
        String session = roleSession(bound, "secretsmanager:GetSecretValue");

        call(session, "GetSecretValue", "{\"SecretId\":\"" + other + "\"}")
                .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void secretResourcePolicyGrantsTheRole() {
        String arn = createSecret("role-session-policy-" + UUID.randomUUID().toString().substring(0, 8));
        IamRole role = createRole(arn, "secretsmanager:GetSecretValue");
        String session = iamService.mintRoleSession(role.getArn()).accessKeyId();

        call(session, "DescribeSecret", "{\"SecretId\":\"" + arn + "\"}").then().statusCode(403);

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"" + role.getArn() + "\"},"
                + "\"Action\":\"secretsmanager:DescribeSecret\",\"Resource\":\"*\"}]}";
        given().header("X-Amz-Target", "secretsmanager.PutResourcePolicy").contentType(CONTENT_TYPE)
                .body("{\"SecretId\":\"" + arn + "\",\"ResourcePolicy\":" + quote(policy) + "}")
                .when().post("/").then().statusCode(200);

        call(session, "DescribeSecret", "{\"SecretId\":\"" + arn + "\"}")
                .then().statusCode(200).body("ARN", equalTo(arn));
    }

    private static String createSecret(String name) {
        return given().header("X-Amz-Target", "secretsmanager.CreateSecret").contentType(CONTENT_TYPE)
                .body("{\"Name\":\"" + name + "\",\"SecretString\":\"value\"}")
                .when().post("/").then().statusCode(200).extract().path("ARN");
    }

    private String roleSession(String secretArn, String action) {
        return iamService.mintRoleSession(createRole(secretArn, action).getArn()).accessKeyId();
    }

    private IamRole createRole(String secretArn, String action) {
        String roleName = "sm-role-" + UUID.randomUUID().toString().substring(0, 8);
        IamRole role = iamService.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 3600, Map.of());
        iamService.putRolePolicy(roleName, "Binding", "{\"Version\":\"2012-10-17\",\"Statement\":[{"
                + "\"Effect\":\"Allow\",\"Action\":[\"" + action + "\"],\"Resource\":[\"" + secretArn + "\"]}]}");
        return role;
    }

    private static io.restassured.response.Response call(String accessKeyId, String operation, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20260720/us-east-1/secretsmanager/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "secretsmanager." + operation)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

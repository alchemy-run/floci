package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM answers a role's trust policy in its own form: a list of one value comes back as that value. Clients that
 * compare the document they sent with the one they read (Alchemy, Terraform) normalize for it.
 */
@QuarkusTest
class IamTrustPolicyFormIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000932";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void singleItemListsReadBackAsValues() throws Exception {
        String roleName = "trust-policy-form";
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Federated\":[\"accounts.google.com\"]},"
                + "\"Action\":[\"sts:AssumeRoleWithWebIdentity\"],"
                + "\"Condition\":{\"StringEquals\":{\"accounts.google.com:sub\":[\"1042\"],"
                + "\"accounts.google.com:aud\":[\"a\",\"b\"]}}}]}";
        iam("CreateRole", Map.of("RoleName", roleName, "AssumeRolePolicyDocument", trust)).statusCode(200);
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", roleName)).statusCode(200));

        String returned = iam("GetRole", Map.of("RoleName", roleName)).statusCode(200).extract()
                .path("GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument");
        JsonNode statement = MAPPER.readTree(URLDecoder.decode(returned, StandardCharsets.UTF_8))
                .path("Statement").get(0);
        assertEquals("sts:AssumeRoleWithWebIdentity", statement.path("Action").asText());
        assertEquals("accounts.google.com", statement.path("Principal").path("Federated").asText());
        JsonNode equals = statement.path("Condition").path("StringEquals");
        assertEquals("1042", equals.path("accounts.google.com:sub").asText());
        assertTrue(equals.path("accounts.google.com:aud").isArray());

        // ListRoles answers the same form.
        String listed = iam("ListRoles", Map.of()).statusCode(200).extract()
                .path("ListRolesResponse.ListRolesResult.Roles.member.find { it.RoleName == '" + roleName
                        + "' }.AssumeRolePolicyDocument");
        assertTrue(URLDecoder.decode(listed, StandardCharsets.UTF_8).contains(
                "\"Action\":\"sts:AssumeRoleWithWebIdentity\""), listed);
    }

    private static ValidatableResponse iam(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261018/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }
}

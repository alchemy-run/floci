package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validationExaminesSyntaxAndReportsUnsupportedSemantics() {
        assertEquals("WARNING", PolicyEvaluator.validate(identity(policy("s3:GetObject", "*")))
                .path("findings").get(0).path("findingType").asText());
        for (String invalid : new String[]{"{", "{}", "[]", "null", "{\"Statement\":[]}",
                "{\"Statement\":{\"Effect\":\"Maybe\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}}",
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":42,\"Resource\":\"*\"}}",
                policy("s3:GetObject", "*") + " {}",
                "{\"Statement\":{},\"Statement\":{}}"}) {
            assertEquals("ERROR", PolicyEvaluator.validate(identity(invalid))
                    .path("findings").get(0).path("findingType").asText(), invalid);
        }
    }

    @Test
    void subsetChecksCompareActionsResourcesAndStatementUnions() {
        String wider = policy("s3:*", "arn:aws:s3:::example/*");
        String narrower = policy("s3:GetObject", "arn:aws:s3:::example/documents/*");
        assertEquals("PASS", subset(wider, narrower));
        assertEquals("FAIL", subset(narrower, wider));
        assertEquals("FAIL", subset(wider, policy("s3:GetObject", "arn:aws:s3:::another/*")));
        assertEquals("FAIL", subset(wider, policy("dynamodb:GetItem", "arn:aws:s3:::example/*")));
        String union = "{\"Statement\":[" + statement("Allow", "s3:GetObject", "*") + ","
                + statement("Allow", "s3:PutObject", "*") + "]}";
        String array = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\"],\"Resource\":\"*\"}}";
        assertEquals("PASS", subset(union, array));
        assertEquals("PASS", subset(array, union));
    }

    @Test
    void denyPrecedenceAndWildcardDifferencesAreEvaluatedRatherThanSampled() {
        String denied = "{\"Statement\":[" + statement("Allow", "s3:*", "arn:aws:s3:::example/*") + ","
                + statement("Deny", "s3:Delete*", "arn:aws:s3:::example/protected/*") + "]}";
        assertEquals("PASS", access(denied, "s3:DeleteObject", "arn:aws:s3:::example/protected/file"));
        assertEquals("FAIL", access(denied, "s3:DeleteObject", "arn:aws:s3:::example/other/file"));
        assertEquals("FAIL", access(denied, "s3:Delete*", "arn:aws:s3:::example/*"));
        assertEquals("PASS", subset(policy("s3:*", "*"), denied));
        assertEquals("FAIL", subset(denied, policy("s3:*", "arn:aws:s3:::example/*")));
        assertEquals("PASS", subset(policy("s3:Get?bject", "*"), policy("S3:GETOBJECT", "*")));
        assertEquals("FAIL", subset(policy("s3:GetObject", "*"), policy("s3:Get?bject", "*")));
    }

    @Test
    void accessChecksSupportActionsOnlyResourcesOnlyAndMultipleChecks() {
        String document = policy("s3:GetObject", "arn:aws:s3:::example/*");
        ObjectNode request = identity(document);
        request.putArray("access").addObject().putArray("actions").add("s3:DeleteBucket");
        assertEquals("PASS", PolicyEvaluator.checkAccessNotGranted(request).path("result").asText());
        request.putArray("access").addObject().putArray("resources").add("arn:aws:s3:::example/file");
        assertEquals("FAIL", PolicyEvaluator.checkAccessNotGranted(request).path("result").asText());
        request.withArray("access").addObject().putArray("actions").add("s3:DeleteBucket");
        assertEquals("FAIL", PolicyEvaluator.checkAccessNotGranted(request).path("result").asText());
        request.putArray("access").addObject();
        assertThrows(AwsException.class, () -> PolicyEvaluator.checkAccessNotGranted(request));
    }

    @Test
    void publicCheckDistinguishesPrivatePublicAndDeniedPolicies() {
        assertEquals("PASS", publicResult("{\"AWS\":\"arn:aws:iam::111111111111:root\"}", false));
        assertEquals("FAIL", publicResult("\"*\"", false));
        assertEquals("FAIL", publicResult("{\"AWS\":[\"111111111111\",\"*\"]}", false));
        assertEquals("PASS", publicResult("\"*\"", true));
        assertThrows(AwsException.class, () -> publicResult("{\"AWS\":\"arn:aws:iam::*:root\"}", false));
        assertThrows(AwsException.class, () -> publicResult("{\"Service\":\"s3.amazonaws.com\"}", false));
    }

    @Test
    void unsupportedPolicyElementsNeverProducePassingVerdicts() {
        for (String element : new String[]{"\"Condition\":{\"StringEquals\":{\"aws:PrincipalOrgID\":\"o-example\"}}",
                "\"NotAction\":\"s3:DeleteObject\"", "\"NotResource\":\"*\"", "\"NotPrincipal\":\"*\""}) {
            String document = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\","
                    + element + "}}";
            assertEquals("ERROR", PolicyEvaluator.validate(identity(document)).path("findings").get(0)
                    .path("findingType").asText());
            ObjectNode request = identity(document);
            request.putArray("access").addObject().putArray("actions").add("s3:DeleteBucket");
            assertThrows(AwsException.class, () -> PolicyEvaluator.checkAccessNotGranted(request));
            assertThrows(AwsException.class, () -> subset(document, policy("s3:GetObject", "*")));
        }
        assertThrows(AwsException.class, () -> access(policy("s3:GetObject", "arn:aws:s3:::${aws:username}/*"),
                "s3:DeleteBucket", "*"));
        ObjectNode request = identity(policy("s3:GetObject", "*"));
        request.put("policyType", "RESOURCE_POLICY");
        assertThrows(AwsException.class, () -> PolicyEvaluator.checkNoNewAccess(request));
    }

    private String subset(String existing, String updated) {
        ObjectNode request = mapper.createObjectNode().put("policyType", "IDENTITY_POLICY")
                .put("existingPolicyDocument", existing).put("newPolicyDocument", updated);
        return PolicyEvaluator.checkNoNewAccess(request).path("result").asText();
    }

    private String access(String document, String action, String resource) {
        ObjectNode request = identity(document);
        ObjectNode check = request.putArray("access").addObject();
        check.putArray("actions").add(action);
        check.putArray("resources").add(resource);
        return PolicyEvaluator.checkAccessNotGranted(request).path("result").asText();
    }

    private String publicResult(String principal, boolean deny) {
        String allow = "{\"Effect\":\"Allow\",\"Principal\":" + principal + ",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}";
        String document = "{\"Statement\":[" + allow + (deny ? "," + allow.replace("Allow", "Deny") : "") + "]}";
        ObjectNode request = mapper.createObjectNode().put("policyDocument", document).put("resourceType", "AWS::S3::Bucket");
        return PolicyEvaluator.checkNoPublicAccess(request).path("result").asText();
    }

    private ObjectNode identity(String document) {
        return mapper.createObjectNode().put("policyType", "IDENTITY_POLICY").put("policyDocument", document);
    }

    private String policy(String action, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":" + statement("Allow", action, resource) + "}";
    }

    private String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }
}

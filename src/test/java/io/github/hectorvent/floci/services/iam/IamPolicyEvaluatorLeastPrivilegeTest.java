package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the SES/KMS least-privilege cases: the Lambda basic-execution managed
 * policy must not grant {@code ses:SendEmail} / {@code kms:GetKeyRotationStatus}.
 */
class IamPolicyEvaluatorLeastPrivilegeTest {

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    private static final String SEND_SCOPED = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"ses:SendEmail","Resource":[
                "arn:aws:ses:us-east-1:000000000000:identity/ses-bindings.alchemy-test.example.com",
                "arn:aws:ses:us-east-1:000000000000:identity/*@ses-bindings.alchemy-test.example.com",
                "arn:aws:ses:us-east-1:000000000000:template/*"
              ]}
            ]}""";

    @Test
    void basicExecutionRoleDoesNotAllowOutsiderSendEmail() {
        CallerContext caller = CallerContext.of(List.of(
                AwsManagedPolicies.LAMBDA_BASIC_EXECUTION_DOCUMENT, SEND_SCOPED));
        assertEquals(Decision.DENY, evaluator.evaluate(caller, null, "ses:SendEmail",
                "arn:aws:ses:us-east-1:000000000000:identity/sender@not-the-bound-domain.test",
                null));
    }

    @Test
    void scopedSendEmailAllowsAddressAtBoundDomain() {
        CallerContext caller = CallerContext.of(List.of(
                AwsManagedPolicies.LAMBDA_BASIC_EXECUTION_DOCUMENT, SEND_SCOPED));
        assertEquals(Decision.ALLOW, evaluator.evaluate(caller, null, "ses:SendEmail",
                "arn:aws:ses:us-east-1:000000000000:identity/noreply@ses-bindings.alchemy-test.example.com",
                null));
    }

    @Test
    void basicExecutionRoleDoesNotAllowGetKeyRotationStatus() {
        CallerContext caller = CallerContext.of(List.of(
                AwsManagedPolicies.LAMBDA_BASIC_EXECUTION_DOCUMENT));
        assertEquals(Decision.DENY, evaluator.evaluate(caller, null, "kms:GetKeyRotationStatus",
                "arn:aws:kms:us-east-1:000000000000:key/abc", null));
    }

    @Test
    void placeholderPermissiveDocumentWouldAllowOutsider() {
        CallerContext caller = CallerContext.of(List.of(
                AwsManagedPolicies.PERMISSIVE_DOCUMENT, SEND_SCOPED));
        assertEquals(Decision.ALLOW, evaluator.evaluate(caller, null, "ses:SendEmail",
                "arn:aws:ses:us-east-1:000000000000:identity/sender@not-the-bound-domain.test",
                null));
    }
}

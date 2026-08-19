package io.github.hectorvent.floci.services.lambda;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaUrlInvocationControllerTest {

    @Test
    void hasSigV4_detectsAuthorizationHeader() {
        assertTrue(LambdaUrlInvocationController.hasSigV4(
                "AWS4-HMAC-SHA256 Credential=AKIA.../us-east-1/lambda/aws4_request", null));
        assertFalse(LambdaUrlInvocationController.hasSigV4("Bearer token", null));
        assertFalse(LambdaUrlInvocationController.hasSigV4((String) null, (String) null));
    }

    @Test
    void hasSigV4_detectsQueryStringSigning() {
        assertTrue(LambdaUrlInvocationController.hasSigV4(null, "AWS4-HMAC-SHA256"));
        assertFalse(LambdaUrlInvocationController.hasSigV4(null, "none"));
    }
}

package io.github.hectorvent.floci.services.sagemaker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SageMakerRuntimeRoutingFilterTest {

    private static final String SAGEMAKER_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";

    @Test
    void recognizesSageMakerCredentialScope() {
        assertTrue(SageMakerRuntimeRoutingFilter.isSageMaker(SAGEMAKER_AUTH));
        assertFalse(SageMakerRuntimeRoutingFilter.isSageMaker(S3_AUTH));
        assertFalse(SageMakerRuntimeRoutingFilter.isSageMaker(null));
        assertFalse(SageMakerRuntimeRoutingFilter.isSageMaker(""));
    }

    @Test
    void prefixesInvokePathsOntoTheInternalPrefix() {
        assertEquals("/sagemaker-runtime/endpoints/demo/invocations",
                SageMakerRuntimeRoutingFilter.rewritePath("/endpoints/demo/invocations"));
        assertEquals("/sagemaker-runtime/endpoints/demo/invocations",
                SageMakerRuntimeRoutingFilter.rewritePath("/endpoints/demo/invocations/"));
        assertEquals("/sagemaker-runtime/endpoints/demo/async-invocations",
                SageMakerRuntimeRoutingFilter.rewritePath("/endpoints/demo/async-invocations"));
        assertEquals("/sagemaker-runtime/endpoints/demo/invocations-response-stream",
                SageMakerRuntimeRoutingFilter.rewritePath("/endpoints/demo/invocations-response-stream"));
    }

    @Test
    void leavesAlreadyPrefixedAndUnrelatedPathsAlone() {
        assertEquals("/sagemaker-runtime/endpoints/demo/invocations",
                SageMakerRuntimeRoutingFilter.rewritePath(
                        "/sagemaker-runtime/endpoints/demo/invocations"));
        assertEquals("/FeatureGroup/demo",
                SageMakerRuntimeRoutingFilter.rewritePath("/FeatureGroup/demo"));
        assertEquals("/endpoints/demo/other",
                SageMakerRuntimeRoutingFilter.rewritePath("/endpoints/demo/other"));
    }
}

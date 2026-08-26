package io.github.hectorvent.floci.services.sagemaker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SageMakerFeatureStoreRoutingFilterTest {

    private static final String SAGEMAKER_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";

    @Test
    void recognizesSageMakerCredentialScope() {
        assertTrue(SageMakerFeatureStoreRoutingFilter.isSageMaker(SAGEMAKER_AUTH));
        assertFalse(SageMakerFeatureStoreRoutingFilter.isSageMaker(S3_AUTH));
        assertFalse(SageMakerFeatureStoreRoutingFilter.isSageMaker(null));
        assertFalse(SageMakerFeatureStoreRoutingFilter.isSageMaker(""));
    }

    @Test
    void recognizesFeatureStoreHost() {
        assertTrue(SageMakerFeatureStoreRoutingFilter.isFeatureStoreHost(
                "featurestore-runtime.sagemaker.us-east-1.amazonaws.com"));
        assertTrue(SageMakerFeatureStoreRoutingFilter.isFeatureStoreHost(
                "featurestore-runtime.sagemaker-fips.us-west-2.amazonaws.com:443"));
        assertFalse(SageMakerFeatureStoreRoutingFilter.isFeatureStoreHost(
                "runtime.sagemaker.us-east-1.amazonaws.com"));
        assertFalse(SageMakerFeatureStoreRoutingFilter.isFeatureStoreHost(
                "b637dccd.lambda-url.us-east-1.localhost:4566"));
        assertFalse(SageMakerFeatureStoreRoutingFilter.isFeatureStoreHost(null));
    }

    @Test
    void skipsLambdaUrlHosts() {
        assertTrue(SageMakerFeatureStoreRoutingFilter.isLambdaUrlHost(
                "abc.lambda-url.us-east-1.localhost:4566"));
        assertFalse(SageMakerFeatureStoreRoutingFilter.isLambdaUrlHost("localhost:4566"));
    }

    @Test
    void prefixesFeatureStorePathsOntoTheInternalPrefix() {
        assertEquals("/sagemaker-featurestore/FeatureGroup/demo",
                SageMakerFeatureStoreRoutingFilter.rewritePath("/FeatureGroup/demo"));
        assertEquals("/sagemaker-featurestore/FeatureGroup/demo/ListRecords",
                SageMakerFeatureStoreRoutingFilter.rewritePath("/FeatureGroup/demo/ListRecords"));
        assertEquals("/sagemaker-featurestore/BatchGetRecord",
                SageMakerFeatureStoreRoutingFilter.rewritePath("/BatchGetRecord"));
        assertEquals("/sagemaker-featurestore/BatchWriteRecord",
                SageMakerFeatureStoreRoutingFilter.rewritePath("/BatchWriteRecord/"));
    }

    @Test
    void leavesAlreadyPrefixedLambdaUrlAndUnrelatedPathsAlone() {
        assertEquals("/sagemaker-featurestore/FeatureGroup/demo",
                SageMakerFeatureStoreRoutingFilter.rewritePath(
                        "/sagemaker-featurestore/FeatureGroup/demo"));
        assertEquals("/lambda-url/abc/batch-write-record",
                SageMakerFeatureStoreRoutingFilter.rewritePath("/lambda-url/abc/batch-write-record"));
        assertEquals("/endpoints/demo/invocations",
                SageMakerFeatureStoreRoutingFilter.rewritePath("/endpoints/demo/invocations"));
    }
}

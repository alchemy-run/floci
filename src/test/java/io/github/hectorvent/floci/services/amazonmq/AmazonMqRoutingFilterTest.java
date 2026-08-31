package io.github.hectorvent.floci.services.amazonmq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonMqRoutingFilterTest {

    private static final String MQ_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/mq/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesMqCredentialScope() {
        assertTrue(AmazonMqRoutingFilter.isMq(MQ_AUTH));
        assertFalse(AmazonMqRoutingFilter.isMq(S3_AUTH));
        assertFalse(AmazonMqRoutingFilter.isMq(null));
        assertFalse(AmazonMqRoutingFilter.isMq(""));
    }

    @Test
    void prefixesOnlyTagPaths() {
        assertEquals(
                "/aws-mq/v1/tags/arn:aws:mq:us-east-1:000000000000:configuration:n:c-1",
                AmazonMqRoutingFilter.rewritePath(
                        "/v1/tags/arn:aws:mq:us-east-1:000000000000:configuration:n:c-1"));
        assertEquals("/aws-mq/v1/tags", AmazonMqRoutingFilter.rewritePath("/v1/tags"));
        assertEquals("/aws-mq/v1/tags", AmazonMqRoutingFilter.rewritePath("/v1/tags/"));
    }

    @Test
    void leavesBrokerAndConfigurationPathsAlone() {
        assertEquals("/v1/brokers", AmazonMqRoutingFilter.rewritePath("/v1/brokers"));
        assertEquals("/v1/configurations", AmazonMqRoutingFilter.rewritePath("/v1/configurations"));
        assertEquals(
                "/v1/broker-engine-types",
                AmazonMqRoutingFilter.rewritePath("/v1/broker-engine-types"));
        assertEquals(
                "/aws-mq/v1/tags/arn:aws:mq:us-east-1:000000000000:configuration:n:c-1",
                AmazonMqRoutingFilter.rewritePath(
                        "/aws-mq/v1/tags/arn:aws:mq:us-east-1:000000000000:configuration:n:c-1"));
    }
}

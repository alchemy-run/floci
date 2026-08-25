package io.github.hectorvent.floci.services.msk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MskRoutingFilterTest {

    private static final String KAFKA_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/kafka/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesKafkaCredentialScope() {
        assertTrue(MskRoutingFilter.isKafka(KAFKA_AUTH));
        assertFalse(MskRoutingFilter.isKafka(S3_AUTH));
        assertFalse(MskRoutingFilter.isKafka(null));
        assertFalse(MskRoutingFilter.isKafka(""));
    }

    @Test
    void prefixesClusterAndTopicPathsAndStripsTrailingSlash() {
        assertEquals("/aws-kafka/api/v2/clusters", MskRoutingFilter.rewritePath("/api/v2/clusters"));
        assertEquals("/aws-kafka/api/v2/clusters", MskRoutingFilter.rewritePath("/api/v2/clusters/"));
        assertEquals("/aws-kafka/v1/clusters", MskRoutingFilter.rewritePath("/v1/clusters"));
        assertEquals(
                "/aws-kafka/api/v2/clusters/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc",
                MskRoutingFilter.rewritePath(
                        "/api/v2/clusters/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc"));
        assertEquals(
                "/aws-kafka/v1/clusters/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc/topics",
                MskRoutingFilter.rewritePath(
                        "/v1/clusters/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc/topics"));
        assertEquals(
                "/aws-kafka/v1/tags/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc",
                MskRoutingFilter.rewritePath(
                        "/v1/tags/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/tags/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc",
                MskRoutingFilter.rewritePath(
                        "/tags/arn:aws:kafka:us-east-1:000000000000:cluster/demo/abc"));
        assertEquals(
                "/aws-kafka/api/v2/clusters",
                MskRoutingFilter.rewritePath("/aws-kafka/api/v2/clusters"));
    }
}

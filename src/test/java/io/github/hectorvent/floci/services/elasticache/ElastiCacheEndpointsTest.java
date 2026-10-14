package io.github.hectorvent.floci.services.elasticache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElastiCacheEndpointsTest {

    @ParameterizedTest
    @CsvSource({
            "us-east-1, use1",
            "us-west-2, usw2",
            "eu-central-1, euc1",
            "ap-southeast-2, apse2",
            "ap-northeast-1, apne1",
            "sa-east-1, sae1",
            "ca-central-1, cac1"
    })
    void regionCodesAreAwsShortForms(String region, String code) {
        assertEquals(code, ElastiCacheEndpoints.regionCode(region));
    }

    @Test
    void aReplicationGroupPrimaryEndpointHasTheAwsShapeUnderFlocisDomain() {
        String hash = ElastiCacheEndpoints.hash("000000000000", "us-east-1");

        assertEquals(6, hash.length());
        assertEquals("master.my-cache." + hash + ".use1.cache.localhost.floci.io",
                ElastiCacheEndpoints.primary("My-Cache", "000000000000", "us-east-1", Optional.empty()));
    }

    @Test
    void aMemcachedConfigurationEndpointHasTheAwsShapeUnderFlocisDomain() {
        String hash = ElastiCacheEndpoints.hash("000000000000", "us-east-1");

        assertEquals("sessions." + hash + ".cfg.use1.cache.localhost.floci.io",
                ElastiCacheEndpoints.memcachedConfiguration("sessions", "000000000000", "us-east-1",
                        Optional.empty()));
    }

    @Test
    void theHashIsFixedPerAccountAndRegion() {
        assertEquals(ElastiCacheEndpoints.hash("000000000000", "us-east-1"),
                ElastiCacheEndpoints.hash("000000000000", "us-east-1"));
        assertNotEquals(ElastiCacheEndpoints.hash("000000000000", "us-east-1"),
                ElastiCacheEndpoints.hash("111111111111", "us-east-1"));
    }

    @Test
    void aConfiguredHostnameReplacesTheDomainButLocalhostAndIpLiteralsCannotCarryAPrefix() {
        assertTrue(ElastiCacheEndpoints.primary("c", "000000000000", "us-east-1", Optional.of("floci.internal"))
                .endsWith(".use1.cache.floci.internal"));
        assertTrue(ElastiCacheEndpoints.primary("c", "000000000000", "us-east-1", Optional.of("localhost"))
                .endsWith(".use1.cache.localhost.floci.io"));
        assertEquals("10.0.0.5",
                ElastiCacheEndpoints.primary("c", "000000000000", "us-east-1", Optional.of("10.0.0.5")));
    }
}

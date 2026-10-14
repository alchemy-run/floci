package io.github.hectorvent.floci.services.elasticache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerlessCacheEndpointsTest {

    @Test
    void addressHasTheAwsServerlessShape() {
        String address = ServerlessCacheEndpoints.address("fixturecache", "000000000000", "us-east-1");

        assertTrue(address.matches("fixturecache-[a-z0-9]{6}\\.serverless\\.use1\\.cache\\.amazonaws\\.com"), address);
        assertTrue(address.contains("cache.amazonaws.com"));
    }

    @Test
    void addressIsStablePerCacheAndDistinctAcrossAccounts() {
        assertEquals(ServerlessCacheEndpoints.address("c", "000000000000", "us-east-1"),
                ServerlessCacheEndpoints.address("c", "000000000000", "us-east-1"));
        assertNotEquals(ServerlessCacheEndpoints.address("c", "000000000000", "us-east-1"),
                ServerlessCacheEndpoints.address("c", "111111111111", "us-east-1"));
    }

    @Test
    void regionCodesMatchAwsShortForms() {
        assertEquals("use1", ServerlessCacheEndpoints.regionCode("us-east-1"));
        assertEquals("usw2", ServerlessCacheEndpoints.regionCode("us-west-2"));
        assertEquals("euw1", ServerlessCacheEndpoints.regionCode("eu-west-1"));
        assertEquals("euc1", ServerlessCacheEndpoints.regionCode("eu-central-1"));
        assertEquals("apse2", ServerlessCacheEndpoints.regionCode("ap-southeast-2"));
        assertEquals("apne1", ServerlessCacheEndpoints.regionCode("ap-northeast-1"));
        assertEquals("usgw1", ServerlessCacheEndpoints.regionCode("us-gov-west-1"));
    }

    @Test
    void chinaRegionsUseTheChinaDomain() {
        assertTrue(ServerlessCacheEndpoints.address("c", "000000000000", "cn-north-1")
                .endsWith(".serverless.cnn1.cache.amazonaws.com.cn"));
    }

    @Test
    void certificateCommonNamesFitRfc5280() {
        String shortAddress = ServerlessCacheEndpoints.address("c", "000000000000", "us-east-1");
        assertEquals(shortAddress, ServerlessCacheEndpoints.certificateCommonName(shortAddress));
        String longAddress = ServerlessCacheEndpoints.address("aws-elasticache-lnjfxputnohhizf37dxyweuc",
                "000000000000", "us-east-1");
        assertEquals("*.serverless.use1.cache.amazonaws.com",
                ServerlessCacheEndpoints.certificateCommonName(longAddress));
    }

    @Test
    void portsFollowTheEngine() {
        assertEquals(6379, ServerlessCacheEndpoints.primaryPort("valkey"));
        assertEquals(6380, ServerlessCacheEndpoints.readerPort("redis"));
        assertEquals(11211, ServerlessCacheEndpoints.primaryPort("memcached"));
        assertEquals(11212, ServerlessCacheEndpoints.readerPort("memcached"));
    }
}

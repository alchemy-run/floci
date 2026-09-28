package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.CreateRequest;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.ModifyRequest;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.Page;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshot;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheServerlessQueryHandlerTest {

    private static final String REGION = "us-east-1";

    private ElastiCacheServerlessService service;
    private ElastiCacheServerlessQueryHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(ElastiCacheServerlessService.class);
        handler = new ElastiCacheServerlessQueryHandler(service, new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void createParsesEveryQueryShapedMember() {
        when(service.createServerlessCache(any(), eq(REGION))).thenReturn(cache("creating", null));
        MultivaluedMap<String, String> params = params(
                "ServerlessCacheName", "FixtureCache",
                "Engine", "valkey",
                "MajorEngineVersion", "8",
                "Description", "fixture",
                "CacheUsageLimits.DataStorage.Maximum", "1",
                "CacheUsageLimits.DataStorage.Unit", "GB",
                "CacheUsageLimits.ECPUPerSecond.Maximum", "1000",
                "SecurityGroupIds.SecurityGroupId.1", "sg-1",
                "SubnetIds.SubnetId.1", "subnet-1",
                "SubnetIds.SubnetId.2", "subnet-2",
                "SnapshotArnsToRestore.SnapshotArn.1", "arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:s",
                "Tags.Tag.1.Key", "fixture",
                "Tags.Tag.1.Value", "elasticache-serverless",
                "SnapshotRetentionLimit", "3",
                "DailySnapshotTime", "04:00",
                "KmsKeyId", "alias/cache",
                "NetworkType", "ipv4");

        Response response = handler.handle("CreateServerlessCache", params, REGION);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<CreateRequest> captured = ArgumentCaptor.forClass(CreateRequest.class);
        verify(service).createServerlessCache(captured.capture(), eq(REGION));
        CreateRequest request = captured.getValue();
        assertEquals("FixtureCache", request.serverlessCacheName());
        assertEquals("valkey", request.engine());
        assertEquals("8", request.majorEngineVersion());
        assertEquals(1, request.cacheUsageLimits().dataStorageMaximum());
        assertEquals("GB", request.cacheUsageLimits().dataStorageUnit());
        assertEquals(1000, request.cacheUsageLimits().ecpuMaximum());
        assertTrue(request.cacheUsageLimits().ecpuPresent());
        assertEquals(List.of("sg-1"), request.securityGroupIds());
        assertEquals(List.of("subnet-1", "subnet-2"), request.subnetIds());
        assertEquals(1, request.snapshotArnsToRestore().size());
        assertEquals(Map.of("fixture", "elasticache-serverless"), request.tags());
        assertEquals(3, request.snapshotRetentionLimit());
        assertEquals("04:00", request.dailySnapshotTime());
        assertEquals("alias/cache", request.kmsKeyId());
        assertEquals("ipv4", request.networkType());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CreateServerlessCacheResult><ServerlessCache>"), body);
        assertTrue(body.contains("<Status>creating</Status>"), body);
        assertFalse(body.contains("<Endpoint>"), "a creating cache has no endpoint yet");
    }

    @Test
    void createAcceptsGenericMemberListsAndOmitsAbsentLimits() {
        when(service.createServerlessCache(any(), eq(REGION))).thenReturn(cache("creating", null));

        handler.handle("CreateServerlessCache", params("ServerlessCacheName", "c", "Engine", "redis",
                "SecurityGroupIds.member.1", "sg-1", "SubnetIds.member.1", "subnet-1"), REGION);

        ArgumentCaptor<CreateRequest> captured = ArgumentCaptor.forClass(CreateRequest.class);
        verify(service).createServerlessCache(captured.capture(), eq(REGION));
        assertEquals(List.of("sg-1"), captured.getValue().securityGroupIds());
        assertEquals(List.of("subnet-1"), captured.getValue().subnetIds());
        assertNull(captured.getValue().cacheUsageLimits());
    }

    @Test
    void describeRendersTheAwsServerlessCacheShape() {
        ServerlessCache available = cache("available", new Endpoint("c-abc123.serverless.use1.cache.amazonaws.com", 6379));
        available.setReaderEndpoint(new Endpoint("c-abc123.serverless.use1.cache.amazonaws.com", 6380));
        when(service.describeServerlessCaches("c", null, null, REGION)).thenReturn(new Page<>(List.of(available), null));

        String body = (String) handler.handle("DescribeServerlessCaches", params("ServerlessCacheName", "c"), REGION)
                .getEntity();

        assertTrue(body.contains("<ServerlessCaches><member><ServerlessCacheName>c</ServerlessCacheName>"), body);
        assertTrue(body.contains("<Status>available</Status>"), body);
        assertTrue(body.contains("<CacheUsageLimits><DataStorage><Maximum>1</Maximum><Unit>GB</Unit></DataStorage>"
                + "<ECPUPerSecond><Maximum>1000</Maximum></ECPUPerSecond></CacheUsageLimits>"), body);
        assertTrue(body.contains("<Endpoint><Address>c-abc123.serverless.use1.cache.amazonaws.com</Address>"
                + "<Port>6379</Port></Endpoint>"), body);
        assertTrue(body.contains("<ReaderEndpoint><Address>c-abc123.serverless.use1.cache.amazonaws.com</Address>"
                + "<Port>6380</Port></ReaderEndpoint>"), body);
        assertTrue(body.contains("<SecurityGroupIds><SecurityGroupId>sg-1</SecurityGroupId></SecurityGroupIds>"), body);
        assertTrue(body.contains("<SubnetIds><SubnetId>subnet-1</SubnetId></SubnetIds>"), body);
        assertTrue(body.contains("<ARN>arn:aws:elasticache:us-east-1:000000000000:serverlesscache:c</ARN>"), body);
        assertTrue(body.contains("<StorageEncryptionType>sse-elasticache</StorageEncryptionType>"), body);
    }

    @Test
    void faultsKeepTheirModeledCodeAndStatus() {
        when(service.describeServerlessCaches("nope", null, null, REGION)).thenThrow(
                new AwsException("ServerlessCacheNotFoundFault", "Serverless cache nope not found.", 404));

        Response response = handler.handle("DescribeServerlessCaches", params("ServerlessCacheName", "nope"), REGION);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ServerlessCacheNotFoundFault</Code>"));
    }

    @Test
    void modifyParsesRemoveUserGroupAndLimits() {
        when(service.modifyServerlessCache(any(), eq(REGION))).thenReturn(cache("modifying", null));

        handler.handle("ModifyServerlessCache", params("ServerlessCacheName", "c", "RemoveUserGroup", "true",
                "CacheUsageLimits.ECPUPerSecond.Maximum", "2000", "Engine", "valkey"), REGION);

        ArgumentCaptor<ModifyRequest> captured = ArgumentCaptor.forClass(ModifyRequest.class);
        verify(service).modifyServerlessCache(captured.capture(), eq(REGION));
        assertEquals(Boolean.TRUE, captured.getValue().removeUserGroup());
        assertFalse(captured.getValue().cacheUsageLimits().dataStoragePresent());
        assertEquals(2000, captured.getValue().cacheUsageLimits().ecpuMaximum());
        assertEquals("valkey", captured.getValue().engine());
    }

    @Test
    void snapshotResponsesUseTheServerlessCacheSnapshotElement() {
        ServerlessCacheSnapshot snapshot = new ServerlessCacheSnapshot();
        snapshot.setServerlessCacheSnapshotName("snap");
        snapshot.setArn("arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:snap");
        snapshot.setStatus("creating");
        snapshot.setSnapshotType("manual");
        snapshot.setCreateTime(Instant.parse("2026-09-24T00:00:00Z"));
        snapshot.setServerlessCacheName("c");
        snapshot.setEngine("valkey");
        snapshot.setMajorEngineVersion("8");
        when(service.createServerlessCacheSnapshot("snap", "c", null, Map.of(), REGION)).thenReturn(snapshot);
        when(service.describeServerlessCacheSnapshots(null, null, null, null, null, REGION))
                .thenReturn(new Page<>(List.of(snapshot), null));

        String created = (String) handler.handle("CreateServerlessCacheSnapshot",
                params("ServerlessCacheSnapshotName", "snap", "ServerlessCacheName", "c"), REGION).getEntity();
        String described = (String) handler.handle("DescribeServerlessCacheSnapshots", params(), REGION).getEntity();

        assertTrue(created.contains("<CreateServerlessCacheSnapshotResult><ServerlessCacheSnapshot>"
                + "<ServerlessCacheSnapshotName>snap</ServerlessCacheSnapshotName>"), created);
        assertTrue(created.contains("<ServerlessCacheConfiguration><ServerlessCacheName>c</ServerlessCacheName>"
                + "<Engine>valkey</Engine><MajorEngineVersion>8</MajorEngineVersion></ServerlessCacheConfiguration>"),
                created);
        assertTrue(described.contains("<ServerlessCacheSnapshots><ServerlessCacheSnapshot>"), described);
    }

    @Test
    void tagActionsAreClaimedOnlyForServerlessArns() {
        assertTrue(handler.handles("ListTagsForResource",
                params("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:serverlesscache:c")));
        assertTrue(handler.handles("AddTagsToResource",
                params("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:s")));
        assertFalse(handler.handles("ListTagsForResource",
                params("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g")));
        assertFalse(handler.handles("ListTagsForResource", params()));
        assertTrue(handler.handles("CreateServerlessCache", params()));
        assertFalse(handler.handles("CreateReplicationGroup", params()));
    }

    @Test
    void tagActionsOperateOnTheNamedServerlessResource() {
        when(service.listTags("serverlesscache", "c", REGION)).thenReturn(Map.of("team", "cache"));

        String body = (String) handler.handle("AddTagsToResource", params(
                "ResourceName", "arn:aws:elasticache:us-east-1:000000000000:serverlesscache:c",
                "Tags.Tag.1.Key", "team", "Tags.Tag.1.Value", "cache"), REGION).getEntity();
        handler.handle("RemoveTagsFromResource", params(
                "ResourceName", "arn:aws:elasticache:us-east-1:000000000000:serverlesscache:c",
                "TagKeys.member.1", "old"), REGION);

        verify(service).addTags("serverlesscache", "c", Map.of("team", "cache"), REGION);
        verify(service).removeTags("serverlesscache", "c", List.of("old"), REGION);
        assertTrue(body.contains("<TagList><Tag><Key>team</Key><Value>cache</Value></Tag></TagList>"), body);
    }

    @Test
    void tagActionsRejectAnotherAccountsArn() {
        Response response = handler.handle("ListTagsForResource",
                params("ResourceName", "arn:aws:elasticache:us-east-1:111111111111:serverlesscache:c"), REGION);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>InvalidParameterValue</Code>"));
    }

    private static ServerlessCache cache(String status, Endpoint endpoint) {
        ServerlessCache cache = new ServerlessCache();
        cache.setServerlessCacheName("c");
        cache.setArn("arn:aws:elasticache:us-east-1:000000000000:serverlesscache:c");
        cache.setStatus(status);
        cache.setEngine("valkey");
        cache.setMajorEngineVersion("8");
        cache.setFullEngineVersion("8.1");
        cache.setDataStorageMaximum(1);
        cache.setDataStorageUnit("GB");
        cache.setEcpuPerSecondMaximum(1000);
        cache.setSecurityGroupIds(List.of("sg-1"));
        cache.setSubnetIds(List.of("subnet-1"));
        cache.setCreateTime(Instant.parse("2026-09-24T00:00:00Z"));
        cache.setEndpoint(endpoint);
        return cache;
    }

    private static MultivaluedMap<String, String> params(String... keyValues) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }
}

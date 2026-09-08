package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the empty-list read responses for the subnet/parameter group describes, so
 * SDK clients get a valid 200 instead of failing with UnsupportedOperation (400).
 */
class ElastiCacheQueryHandlerTest {

    private ElastiCacheQueryHandler handler;
    private ElastiCacheService service;
    private ElastiCacheMemcachedService memcachedService;

    @BeforeEach
    void setUp() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        service = mock(ElastiCacheService.class);
        memcachedService = mock(ElastiCacheMemcachedService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        handler = new ElastiCacheQueryHandler(sigV4Validator, service, memcachedService, regionResolver);
    }

    @Test
    void describeCacheSubnetGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheSubnetGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheSubnetGroupsResult><CacheSubnetGroups></CacheSubnetGroups></DescribeCacheSubnetGroupsResult>"),
                "Expected empty CacheSubnetGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeCacheParameterGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheParameterGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheParameterGroupsResult><CacheParameterGroups></CacheParameterGroups></DescribeCacheParameterGroupsResult>"),
                "Expected empty CacheParameterGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void unsupportedOperationStillReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("UnsupportedOperation"));
    }

    @Test
    void describeCacheSubnetGroupsReturnsPersistedTopology() {
        when(service.listSubnetGroups(null)).thenReturn(List.of(
                new CacheSubnetGroup("private", "private subnets", List.of("subnet-a", "subnet-b"), Map.of("env", "test"))));

        Response response = handler.handle("DescribeCacheSubnetGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheSubnetGroupName>private</CacheSubnetGroupName>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-a</SubnetIdentifier>"));
        assertTrue(body.contains("arn:aws:elasticache:us-east-1:000000000000:subnetgroup:private"));
    }

    @Test
    void describeReplicationGroupsReturnsReachableNodeEndpoint() {
        ReplicationGroup group = new ReplicationGroup("cache", "test", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setReplicasPerNodeGroup(1);
        when(service.listReplicationGroups(null)).thenReturn(List.of(group));

        Response response = handler.handle("DescribeReplicationGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<PrimaryEndpoint><Address>localhost</Address><Port>6379</Port>"));
        assertTrue(body.contains("<CurrentRole>replica</CurrentRole>"));
        assertTrue(body.contains("arn:aws:elasticache:us-east-1:000000000000:replicationgroup:cache"));
    }

    @Test
    void describeCacheClustersReturnsEveryConfiguredNode() {
        CacheCluster cluster = new CacheCluster("mem", CacheClusterStatus.AVAILABLE, "memcached", "1.6.22",
                new Endpoint("localhost", 11211), Instant.now());
        cluster.setNumCacheNodes(2);
        when(memcachedService.listCacheClusters(null)).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeCacheClusters", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<NumCacheNodes>2</NumCacheNodes>"));
        assertTrue(body.contains("<CacheNodeId>mem-0002</CacheNodeId>"));
        assertTrue(body.contains("arn:aws:elasticache:us-east-1:000000000000:cluster:mem"));
    }

    @Test
    void createReplicationGroupPersistsProvisionedTopology() {
        ReplicationGroup group = new ReplicationGroup("cache", "old", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        when(service.createReplicationGroup("cache", "new", AuthMode.NO_AUTH, null)).thenReturn(group);
        when(service.saveReplicationGroup(group)).thenReturn(group);
        MultivaluedMap<String, String> request = params();
        request.add("ReplicationGroupId", "cache");
        request.add("ReplicationGroupDescription", "new");
        request.add("CacheNodeType", "cache.t4g.medium");
        request.add("ReplicasPerNodeGroup", "1");
        request.add("SecurityGroupIds.SecurityGroupId.1", "sg-cache");
        request.add("Port", "6380");
        request.add("Tags.Tag.1.Key", "env");
        request.add("Tags.Tag.1.Value", "test");

        Response response = handler.handle("CreateReplicationGroup", request);

        assertEquals(200, response.getStatus());
        assertEquals("cache.t4g.medium", group.getCacheNodeType());
        assertEquals(1, group.getReplicasPerNodeGroup());
        assertEquals(List.of("sg-cache"), group.getSecurityGroupIds());
        assertEquals(6380, group.getConfigurationEndpoint().port());
        assertEquals("test", group.getTags().get("env"));
    }

    @Test
    void describeReplicationGroupMemberReportsVpcSecurityGroups() {
        ReplicationGroup group = new ReplicationGroup("cache", "test", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setSecurityGroupIds(List.of("sg-cache"));
        when(service.listReplicationGroups(null)).thenReturn(List.of(group));
        MultivaluedMap<String, String> request = params();
        request.add("CacheClusterId", "cache-0001-001");

        Response response = handler.handle("DescribeCacheClusters", request);

        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains(
                "<SecurityGroups><member><SecurityGroupId>sg-cache</SecurityGroupId><Status>active</Status></member></SecurityGroups>"));
    }

    @Test
    void createSubnetGroupReadsAwsQuerySubnetIdentifierMembers() {
        CacheSubnetGroup group = new CacheSubnetGroup("private", "private subnets", List.of("subnet-a"), Map.of());
        when(service.createSubnetGroup(eq("private"), eq("private subnets"), anyList(), anyMap())).thenReturn(group);
        MultivaluedMap<String, String> request = params();
        request.add("CacheSubnetGroupName", "private");
        request.add("CacheSubnetGroupDescription", "private subnets");
        request.add("SubnetIds.SubnetIdentifier.1", "subnet-a");
        request.add("SubnetIds.SubnetIdentifier.2", "subnet-b");

        Response response = handler.handle("CreateCacheSubnetGroup", request);

        assertEquals(200, response.getStatus());
        verify(service).createSubnetGroup("private", "private subnets", List.of("subnet-a", "subnet-b"), Map.of());
    }

    @Test
    void describeCacheEngineVersionsAdvertisesTwoValkeyVersions() {
        MultivaluedMap<String, String> request = params();
        request.add("Engine", "valkey");

        Response response = handler.handle("DescribeCacheEngineVersions", request);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<EngineVersion>8.0</EngineVersion>"));
        assertTrue(body.contains("<EngineVersion>8.1</EngineVersion>"));
    }

    @Test
    void testFailoverAcceptsAReplicationGroupWithAReplica() {
        ReplicationGroup group = new ReplicationGroup("cache", "test", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setReplicasPerNodeGroup(1);
        when(service.getReplicationGroup("cache")).thenReturn(group);
        MultivaluedMap<String, String> request = params();
        request.add("ReplicationGroupId", "cache");

        Response response = handler.handle("TestFailover", request);

        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<TestFailoverResponse"));
    }

    @Test
    void replicaScaleUsesTheMatchingQueryResponseEnvelope() {
        ReplicationGroup group = new ReplicationGroup("cache", "test", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        when(service.setReplicaCount("cache", 1)).thenReturn(group);
        MultivaluedMap<String, String> request = params();
        request.add("ReplicationGroupId", "cache");
        request.add("NewReplicaCount", "1");

        Response response = handler.handle("IncreaseReplicaCount", request);

        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<IncreaseReplicaCountResponse"));
    }

    @Test
    void deleteReplicationGroupForwardsTheFinalSnapshotName() {
        ReplicationGroup group = new ReplicationGroup("cache", "test", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        when(service.getReplicationGroup("cache")).thenReturn(group);
        MultivaluedMap<String, String> request = params();
        request.add("ReplicationGroupId", "cache");
        request.add("FinalSnapshotIdentifier", "cache-final");

        Response response = handler.handle("DeleteReplicationGroup", request);

        assertEquals(200, response.getStatus());
        verify(service).deleteReplicationGroup("cache", "cache-final");
    }

    @Test
    void serverlessDescribes_returnEmptyListsWithoutFilters() {
        String caches = (String) handler.handle("DescribeServerlessCaches", params()).getEntity();
        assertTrue(caches.contains("<ServerlessCaches></ServerlessCaches>"));

        String snapshots = (String) handler.handle("DescribeServerlessCacheSnapshots", params()).getEntity();
        assertTrue(snapshots.contains("<ServerlessCacheSnapshots></ServerlessCacheSnapshots>"));

        MultivaluedMap<String, String> events = params();
        events.add("SourceType", "serverless-cache");
        assertTrue(((String) handler.handle("DescribeEvents", events).getEntity()).contains("<Events></Events>"));
    }

    @Test
    void serverlessLookups_surfaceTypedNotFoundFaults() {
        MultivaluedMap<String, String> cacheReq = params();
        cacheReq.add("ServerlessCacheName", "nope");
        Response cache = handler.handle("DescribeServerlessCaches", cacheReq);
        assertEquals(404, cache.getStatus());
        assertTrue(((String) cache.getEntity()).contains("ServerlessCacheNotFoundFault"));

        for (String action : List.of("DeleteServerlessCacheSnapshot", "ExportServerlessCacheSnapshot")) {
            MultivaluedMap<String, String> request = params();
            request.add("ServerlessCacheSnapshotName", "nope");
            Response response = handler.handle(action, request);
            assertEquals(404, response.getStatus(), action);
            assertTrue(((String) response.getEntity()).contains("ServerlessCacheSnapshotNotFoundFault"), action);
        }

        MultivaluedMap<String, String> copyReq = params();
        copyReq.add("SourceServerlessCacheSnapshotName", "nope");
        copyReq.add("TargetServerlessCacheSnapshotName", "nope-copy");
        Response copy = handler.handle("CopyServerlessCacheSnapshot", copyReq);
        assertEquals(404, copy.getStatus());
        assertTrue(((String) copy.getEntity()).contains("ServerlessCacheSnapshotNotFoundFault"));
    }

    private static MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }
}

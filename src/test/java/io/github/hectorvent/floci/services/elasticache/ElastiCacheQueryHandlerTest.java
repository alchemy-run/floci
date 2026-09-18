package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.ClusterNode;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.core.common.AwsException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

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
        when(regionResolver.getRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        handler = new ElastiCacheQueryHandler(sigV4Validator, service, memcachedService, regionResolver);
    }

    @Test
    void describeCacheSubnetGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheSubnetGroups", params(), "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheSubnetGroupsResult><CacheSubnetGroups></CacheSubnetGroups></DescribeCacheSubnetGroupsResult>"),
                "Expected empty CacheSubnetGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeCacheParameterGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheParameterGroups", params(), "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheParameterGroupsResult><CacheParameterGroups></CacheParameterGroups></DescribeCacheParameterGroupsResult>"),
                "Expected empty CacheParameterGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeReplicationGroups_reportsClusterModeTopology() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setClusterEnabled(true);
        group.setEngine("valkey");
        group.setAutomaticFailoverEnabled(true);
        group.setClusterNodes(List.of(
                new ClusterNode("grp-0001-001", "0001", true, 6379, "0-8191"),
                new ClusterNode("grp-0002-001", "0002", true, 6380, "8192-16383")));
        when(service.listReplicationGroups("grp")).thenReturn(List.of(group));
        when(service.memberCacheClusters(group)).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true),
                new ElastiCacheService.MemberCacheCluster(group, "grp-0002-001", 6380, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("ReplicationGroupId", "grp");
        Response response = handler.handle("DescribeReplicationGroups", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<ClusterEnabled>true</ClusterEnabled>"));
        assertTrue(body.contains("<ClusterMode>enabled</ClusterMode>"));
        assertTrue(body.contains("<AutomaticFailover>enabled</AutomaticFailover>"));
        assertTrue(body.contains("<Engine>valkey</Engine>"));
        assertTrue(body.contains("<ClusterId>grp-0001-001</ClusterId>"));
        assertTrue(body.contains("<ClusterId>grp-0002-001</ClusterId>"));
        assertTrue(body.contains("<NodeGroupId>0002</NodeGroupId>"));
        assertTrue(body.contains("<Slots>8192-16383</Slots>"));
        assertTrue(body.contains("<CacheClusterId>grp-0002-001</CacheClusterId>"));
        assertFalse(body.contains("<PrimaryEndpoint>"),
                "Cluster-mode node groups carry Slots, not a PrimaryEndpoint");
    }

    @Test
    void describeReplicationGroups_reportsSingleNodeGroupWithPrimaryEndpoint() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setEngine("valkey");
        when(service.listReplicationGroups("grp")).thenReturn(List.of(group));
        when(service.memberCacheClusters(group)).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("ReplicationGroupId", "grp");
        Response response = handler.handle("DescribeReplicationGroups", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<ClusterEnabled>false</ClusterEnabled>"));
        assertTrue(body.contains("<ClusterId>grp-001</ClusterId>"));
        assertTrue(body.contains("<PrimaryEndpoint><Address>localhost</Address><Port>6379</Port></PrimaryEndpoint>"));
        assertTrue(body.contains("<CurrentRole>primary</CurrentRole>"));
    }

    @Test
    void describeCacheClusters_answersForReplicationGroupMembers() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setEngine("valkey");
        group.setEngineVersion("8.2");
        group.setCacheNodeType("cache.t4g.micro");
        when(service.listMemberCacheClusters("grp-0001-001")).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("CacheClusterId", "grp-0001-001");
        params.putSingle("ShowCacheNodeInfo", "true");
        Response response = handler.handle("DescribeCacheClusters", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheClusterId>grp-0001-001</CacheClusterId>"));
        assertTrue(body.contains("<CacheClusterStatus>available</CacheClusterStatus>"));
        assertTrue(body.contains("<ReplicationGroupId>grp</ReplicationGroupId>"));
        assertTrue(body.contains("<Engine>valkey</Engine>"));
        assertTrue(body.contains("<EngineVersion>8.2</EngineVersion>"));
        assertTrue(body.contains("<CacheNodeType>cache.t4g.micro</CacheNodeType>"));
        assertTrue(body.contains("<Endpoint><Address>localhost</Address><Port>6379</Port></Endpoint>"));
    }

    @Test
    void describeCacheClusters_membersOfFailedGroupReportRestoreFailed() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.CREATE_FAILED,
                AuthMode.NO_AUTH, null, Instant.now(), 6379);
        when(service.listMemberCacheClusters("grp-0001-001")).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("CacheClusterId", "grp-0001-001");
        params.putSingle("ShowCacheNodeInfo", "true");
        Response response = handler.handle("DescribeCacheClusters", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheClusterStatus>restore-failed</CacheClusterStatus>"),
                "CacheClusterStatus has no create-failed; the documented failure value is restore-failed: " + body);
        assertFalse(body.contains("<CacheNodes>"),
                "A failed group has no endpoint, so members must not hand out node endpoints");
    }

    @Test
    void describeReplicationGroups_reportsCreateFailedStatusInAwsWireForm() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.CREATE_FAILED,
                AuthMode.NO_AUTH, null, Instant.now(), 6379);
        when(service.listReplicationGroups("grp")).thenReturn(List.of(group));
        when(service.memberCacheClusters(group)).thenReturn(List.of());

        MultivaluedMap<String, String> params = params();
        params.putSingle("ReplicationGroupId", "grp");
        Response response = handler.handle("DescribeReplicationGroups", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Status>create-failed</Status>"),
                "AWS reports this status hyphenated, not as the enum constant");
    }

    @Test
    void unsupportedOperationStillReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", params(), "us-east-1");

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("UnsupportedOperation"));
    }

    @Test
    void legacySubnetGroupNameAndMembersRemainReadable() throws Exception {
        CacheSubnetGroup group = new ObjectMapper().readValue(
                "{\"cacheSubnetGroupName\":\"private\",\"subnetIds\":[\"subnet-a\"]}", CacheSubnetGroup.class);

        assertEquals("private", group.getName());
        assertTrue(group.getSubnetAvailabilityZones().containsKey("subnet-a"));
    }

    @Test
    void describeCacheSubnetGroupsReturnsPersistedTopology() {
        when(service.describeCacheSubnetGroups(null)).thenReturn(List.of(
                new CacheSubnetGroup("private", "private subnets", "vpc-test", Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1b"))));

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
        group.setArn("arn:aws:elasticache:us-east-1:000000000000:replicationgroup:cache");
        when(service.memberCacheClusters(group)).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "cache-001", 6379, true),
                new ElastiCacheService.MemberCacheCluster(group, "cache-002", 6379, false)));
        when(service.listReplicationGroups(null)).thenReturn(List.of(group));

        Response response = handler.handle("DescribeReplicationGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<PrimaryEndpoint><Address>localhost</Address><Port>6379</Port>"));
        assertTrue(body.contains("<CurrentRole>replica</CurrentRole>"));
        assertEquals(1, body.split("<NodeGroups>", -1).length - 1);
        assertEquals(1, body.split("<ARN>", -1).length - 1);
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
                AuthMode.NO_AUTH, new Endpoint("localhost", 6380), Instant.now(), 6380);
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class))).thenReturn(group);
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
        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> requestCaptor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        verify(service).createReplicationGroup(requestCaptor.capture());
        assertEquals(6380, requestCaptor.getValue().port());
    }

    @Test
    void describeReplicationGroupMemberReportsVpcSecurityGroups() {
        ReplicationGroup group = new ReplicationGroup("cache", "test", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setSecurityGroupIds(List.of("sg-cache"));
        when(service.listMemberCacheClusters("cache-0001-001")).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "cache-0001-001", 6379, true)));
        MultivaluedMap<String, String> request = params();
        request.add("CacheClusterId", "cache-0001-001");

        Response response = handler.handle("DescribeCacheClusters", request);

        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains(
                "<SecurityGroups><member><SecurityGroupId>sg-cache</SecurityGroupId><Status>active</Status></member></SecurityGroups>"));
    }

    @Test
    void createSubnetGroupReadsAwsQuerySubnetIdentifierMembers() {
        CacheSubnetGroup group = new CacheSubnetGroup("private", "private subnets", "vpc-test", Map.of("subnet-a", "us-east-1a"));
        when(service.createCacheSubnetGroup(eq("private"), eq("private subnets"), anyList(), anyMap())).thenReturn(group);
        MultivaluedMap<String, String> request = params();
        request.add("CacheSubnetGroupName", "private");
        request.add("CacheSubnetGroupDescription", "private subnets");
        request.add("SubnetIds.SubnetIdentifier.1", "subnet-a");
        request.add("SubnetIds.SubnetIdentifier.2", "subnet-b");

        Response response = handler.handle("CreateCacheSubnetGroup", request);

        assertEquals(200, response.getStatus());
        verify(service).createCacheSubnetGroup("private", "private subnets", List.of("subnet-a", "subnet-b"), Map.of());
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

    private static ReplicationGroup group(String id) {
        ReplicationGroup g = new ReplicationGroup(id, "d", ReplicationGroupStatus.AVAILABLE, AuthMode.NO_AUTH,
                new Endpoint("localhost", 6379), java.time.Instant.now(), 6379);
        g.setArn("arn:aws:elasticache:us-east-1:000000000000:replicationgroup:" + id);
        return g;
    }

    @Test
    void createReplicationGroup_passesSettingsAndTagsToService() {
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("ReplicationGroupDescription", "d");
        p.add("AtRestEncryptionEnabled", "true");
        p.add("KmsKeyId", "alias/cache");
        p.add("SnapshotRetentionLimit", "7");
        p.add("SnapshotWindow", "06:30-07:30");
        p.add("Tags.Tag.1.Key", "Name");
        p.add("Tags.Tag.1.Value", "g1");

        assertEquals(200, handler.handle("CreateReplicationGroup", p, "us-east-1").getStatus());

        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        verify(service).createReplicationGroup(captor.capture());
        ElastiCacheService.CreateReplicationGroupRequest request = captor.getValue();
        assertEquals("g1", request.replicationGroupId());
        assertEquals("d", request.description());
        assertEquals(AuthMode.NO_AUTH, request.authMode());
        assertEquals("us-east-1", request.region());
        assertEquals(new ReplicationGroupSettings(true, "alias/cache", 7, "06:30-07:30"), request.settings());
        assertEquals(Map.of("Name", "g1"), request.tags());
    }

    @Test
    void createReplicationGroup_passesTheRequestedPortToService() {
        // Port is a modeled optional input on CreateReplicationGroup; the handler dropped it,
        // so a caller that pinned one always read back whatever port floci allocated.
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("ReplicationGroupDescription", "d");
        p.add("Port", "6395");

        assertEquals(200, handler.handle("CreateReplicationGroup", p, "us-east-1").getStatus());

        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        verify(service).createReplicationGroup(captor.capture());
        assertEquals(6395, captor.getValue().port());
    }

    @Test
    void createReplicationGroup_leavesPortNullWhenAbsent() {
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("ReplicationGroupDescription", "d");

        assertEquals(200, handler.handle("CreateReplicationGroup", p, "us-east-1").getStatus());

        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        verify(service).createReplicationGroup(captor.capture());
        assertNull(captor.getValue().port(),
                "An absent Port must stay null so the service allocates as before");
    }

    @Test
    void createReplicationGroup_readsTheEncryptionFlagAsAwsDoes() {
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        // a live account reads anything but "false" as true — probed with banana, yes and "TRUE "
        for (String value : new String[] {"true", "banana", "yes", "TRUE "}) {
            MultivaluedMap<String, String> p = params();
            p.add("ReplicationGroupId", "g1");
            p.add("AtRestEncryptionEnabled", value);
            handler.handle("CreateReplicationGroup", p, "us-east-1");
        }
        for (String value : new String[] {"false", "FALSE"}) {
            MultivaluedMap<String, String> p = params();
            p.add("ReplicationGroupId", "g1");
            p.add("AtRestEncryptionEnabled", value);
            handler.handle("CreateReplicationGroup", p, "us-east-1");
        }
        MultivaluedMap<String, String> omitted = params();
        omitted.add("ReplicationGroupId", "g1");
        handler.handle("CreateReplicationGroup", omitted, "us-east-1");

        verify(service, times(7)).createReplicationGroup(captor.capture());
        List<Boolean> seen = captor.getAllValues().stream()
                .map(r -> r.settings().atRestEncryptionEnabled()).toList();
        assertEquals(java.util.Arrays.asList(true, true, true, true, false, false, null), seen);
    }

    @Test
    void describeReplicationGroups_emitsStoredSettingsAndArn() {
        ReplicationGroup g = group("g1");
        g.setAtRestEncryptionEnabled(true);
        g.setKmsKeyId("arn:aws:kms:us-east-1:000000000000:key/k1");
        g.setSnapshotRetentionLimit(7);
        g.setSnapshotWindow("06:30-07:30");
        when(service.listReplicationGroups("g1")).thenReturn(List.of(g));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");

        String body = (String) handler.handle("DescribeReplicationGroups", p, "us-east-1").getEntity();

        assertTrue(body.contains("<AtRestEncryptionEnabled>true</AtRestEncryptionEnabled>"), body);
        assertTrue(body.contains("<KmsKeyId>arn:aws:kms:us-east-1:000000000000:key/k1</KmsKeyId>"), body);
        assertTrue(body.contains("<SnapshotRetentionLimit>7</SnapshotRetentionLimit>"), body);
        assertTrue(body.contains("<SnapshotWindow>06:30-07:30</SnapshotWindow>"), body);
        assertTrue(body.contains("<ARN>arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g1</ARN>"), body);
    }

    /**
     * A group created with TransitEncryptionEnabled=true and no auth token is recorded as
     * AuthMode.IAM. Reporting the auth-token flag as TransitEncryptionEnabled answered false for
     * it, which Terraform reads back on every plan as drift on transit_encryption_enabled.
     */
    @Test
    void describeReplicationGroups_reportsTransitEncryptionWithoutAnAuthToken() {
        ReplicationGroup g = new ReplicationGroup("g1", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.IAM, new Endpoint("localhost", 6379), Instant.now(), 6379);
        when(service.listReplicationGroups("g1")).thenReturn(List.of(g));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");

        String body = (String) handler.handle("DescribeReplicationGroups", p, "us-east-1").getEntity();

        assertTrue(body.contains("<TransitEncryptionEnabled>true</TransitEncryptionEnabled>"), body);
        // ... and it is still distinct from AuthTokenEnabled, which only a token sets.
        assertTrue(body.contains("<AuthTokenEnabled>false</AuthTokenEnabled>"), body);
    }

    @Test
    void describeReplicationGroups_reportsNoTransitEncryptionForAPlainGroup() {
        when(service.listReplicationGroups("g1")).thenReturn(List.of(group("g1")));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");

        String body = (String) handler.handle("DescribeReplicationGroups", p, "us-east-1").getEntity();

        assertTrue(body.contains("<TransitEncryptionEnabled>false</TransitEncryptionEnabled>"), body);
    }

    /**
     * The member-cluster view of the same group reported AtRestEncryptionEnabled as a hardcoded
     * false, so DescribeCacheClusters contradicted DescribeReplicationGroups about one group.
     */
    @Test
    void describeCacheClusters_reportTheGroupsRealEncryptionFlags() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.IAM, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setAtRestEncryptionEnabled(true);
        when(service.listMemberCacheClusters("grp-0001-001")).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("CacheClusterId", "grp-0001-001");
        String body = (String) handler.handle("DescribeCacheClusters", params, "us-east-1").getEntity();

        assertTrue(body.contains("<TransitEncryptionEnabled>true</TransitEncryptionEnabled>"), body);
        assertTrue(body.contains("<AtRestEncryptionEnabled>true</AtRestEncryptionEnabled>"), body);
    }

    @Test
    void listTagsForResource_readsAReplicationGroupByItsArn() {
        ReplicationGroup g = group("g1");
        g.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "g1")));
        when(service.getReplicationGroup("g1")).thenReturn(g);
        when(service.getReplicationGroup("absent")).thenThrow(
                new AwsException("ReplicationGroupNotFoundFault", "Replication group absent not found.", 404));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g1");
        String body = (String) handler.handle("ListTagsForResource", p, "us-east-1").getEntity();
        assertTrue(body.contains("<Key>Name</Key>"), body);

        p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:absent");
        Response response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ReplicationGroupNotFoundFault"));

        // the store keys groups by id alone: a same-named group created under another region is
        // not the one this ARN names
        ReplicationGroup elsewhere = group("g2");
        elsewhere.setArn("arn:aws:elasticache:eu-west-1:000000000000:replicationgroup:g2");
        elsewhere.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "west")));
        when(service.getReplicationGroup("g2")).thenReturn(elsewhere);
        p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g2");
        response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus(), (String) response.getEntity());
        assertFalse(((String) response.getEntity()).contains("west"));
    }

    @Test
    void modifyReplicationGroup_passesSnapshotSettingsOnly() {
        when(service.modifyReplicationGroup(eq("g1"), isNull(), isNull(), any())).thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("SnapshotRetentionLimit", "3");
        p.add("SnapshotWindow", "01:00-02:00");
        p.add("AtRestEncryptionEnabled", "true");
        p.add("KmsKeyId", "alias/other");

        assertEquals(200, handler.handle("ModifyReplicationGroup", p, "us-east-1").getStatus());
        verify(service).modifyReplicationGroup("g1", null, null, new ReplicationGroupSettings(null, null, 3, "01:00-02:00"));
    }
}

package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.ContainerEndpoints;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.CreateRequest;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.ModifyRequest;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.UsageLimits;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ServerlessCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ServerlessCacheContainerManager.TlsMaterial;
import io.github.hectorvent.floci.services.elasticache.container.ServerlessCacheDataPlane;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshot;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshotEntry;
import io.github.hectorvent.floci.services.kms.KmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheServerlessServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String DEFAULT_VPC = "vpc-default-us-east-1";
    private static final String OTHER_VPC = "vpc-0other";
    private static final String CONTAINER_IP = "172.18.0.9";

    @TempDir
    Path tlsDir;

    private Ec2Service ec2;
    private ServerlessCacheContainerManager containers;
    private ContainerEndpoints endpoints;
    private ServerlessCacheDataPlane dataPlane;
    private final List<Runnable> deferred = new ArrayList<>();
    private boolean deferTasks;
    private ElastiCacheServerlessService service;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        containers = mock(ServerlessCacheContainerManager.class);
        endpoints = mock(ContainerEndpoints.class);
        dataPlane = mock(ServerlessCacheDataPlane.class);
        when(ec2.resolveDefaultVpcId(REGION)).thenReturn(DEFAULT_VPC);
        Subnet defaultA = subnet("subnet-default-us-east-1-a", DEFAULT_VPC, true);
        Subnet defaultB = subnet("subnet-default-us-east-1-b", DEFAULT_VPC, true);
        Subnet other = subnet("subnet-0other", OTHER_VPC, false);
        when(ec2.describeSubnets(REGION, List.of(), Map.of())).thenReturn(List.of(other, defaultB, defaultA));
        for (Subnet subnet : List.of(defaultA, defaultB, other)) {
            when(ec2.findSubnetById(REGION, subnet.getSubnetId())).thenReturn(Optional.of(subnet));
        }
        when(ec2.findSubnetById(eq(REGION), eq("subnet-missing"))).thenReturn(Optional.empty());
        SecurityGroup defaultGroup = group("sg-default-us-east-1", "default", DEFAULT_VPC);
        SecurityGroup otherGroup = group("sg-0other", "app", OTHER_VPC);
        when(ec2.getSecurityGroupsForVpc(REGION, DEFAULT_VPC, Map.of())).thenReturn(List.of(defaultGroup));
        when(ec2.describeSecurityGroups(REGION, List.of("sg-default-us-east-1"), List.of(), Map.of()))
                .thenReturn(List.of(defaultGroup));
        when(ec2.describeSecurityGroups(REGION, List.of("sg-0other"), List.of(), Map.of()))
                .thenReturn(List.of(otherGroup));
        when(ec2.describeSecurityGroups(REGION, List.of("sg-missing"), List.of(), Map.of()))
                .thenThrow(new AwsException("InvalidGroup.NotFound", "missing", 400));
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ElastiCacheContainerHandle handle = new ElastiCacheContainerHandle(
                            "container-" + invocation.getArgument(0), invocation.getArgument(0), "127.0.0.1", 41000);
                    handle.setNetworkIp(CONTAINER_IP);
                    return handle;
                });
        Executor executor = task -> {
            if (deferTasks) {
                deferred.add(task);
            } else {
                task.run();
            }
        };
        service = new ElastiCacheServerlessService(
                AccountAwareStorageBackend.<ServerlessCache>inMemory(ACCOUNT),
                AccountAwareStorageBackend.<ServerlessCacheSnapshot>inMemory(ACCOUNT),
                new RegionResolver(REGION, ACCOUNT), ec2, mock(KmsService.class), containers, endpoints,
                FlociCertificateAuthority.loadOrCreate(tlsDir), dataPlane,
                "valkey/valkey:8", "memcached:1.6", executor);
    }

    @Test
    void createAnswersCreatingThenBecomesAvailableOnAnAwsShapedTlsEndpoint() {
        ServerlessCache created = service.createServerlessCache(request("FixtureCache", "valkey", limits(1, 1000)), REGION);

        assertEquals("creating", created.getStatus());
        assertNull(created.getEndpoint());
        assertEquals("fixturecache", created.getServerlessCacheName());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:serverlesscache:fixturecache", created.getArn());

        ServerlessCache described = describe("fixturecache");
        assertEquals("available", described.getStatus());
        assertEquals("valkey", described.getEngine());
        assertEquals("8", described.getMajorEngineVersion());
        assertEquals("8.1", described.getFullEngineVersion());
        assertEquals(1, described.getDataStorageMaximum());
        assertEquals(1000, described.getEcpuPerSecondMaximum());
        assertTrue(described.getEndpoint().address()
                .matches("fixturecache-[a-z0-9]{6}\\.serverless\\.use1\\.cache\\.amazonaws\\.com"),
                described.getEndpoint().address());
        assertEquals(6379, described.getEndpoint().port());
        assertEquals(described.getEndpoint().address(), described.getReaderEndpoint().address());
        assertEquals(6380, described.getReaderEndpoint().port());
        assertEquals(List.of("subnet-default-us-east-1-a", "subnet-default-us-east-1-b"), described.getSubnetIds());
        assertEquals(List.of("sg-default-us-east-1"), described.getSecurityGroupIds());

        ArgumentCaptor<TlsMaterial> tls = ArgumentCaptor.forClass(TlsMaterial.class);
        verify(containers).tryStart(eq("serverless-000000000000-us-east-1-fixturecache"), eq("valkey"),
                eq("valkey/valkey:8"), eq(ACCOUNT), eq(REGION), tls.capture());
        assertEquals(2, tls.getValue().certificateChainPem().split("BEGIN CERTIFICATE", -1).length - 1,
                "the container serves the leaf followed by Floci's CA");
        verify(endpoints).routeToContainer(described.getEndpoint().address(), CONTAINER_IP);
    }

    @Test
    void aMaximumLengthNameGetsACertificateForItsHostname() throws Exception {
        String name = "aws-elasticache-lnjfxputnohhizf37dxyweuc";
        service.createServerlessCache(request(name, "valkey", null), REGION);

        ServerlessCache described = describe(name);
        assertEquals("available", described.getStatus());
        String address = described.getEndpoint().address();
        assertTrue(address.length() > 64, address);
        ArgumentCaptor<TlsMaterial> tls = ArgumentCaptor.forClass(TlsMaterial.class);
        verify(containers).tryStart(anyString(), eq("valkey"), anyString(), anyString(), anyString(), tls.capture());
        java.security.cert.X509Certificate leaf = new io.github.hectorvent.floci.services.acm.CertificateGenerator()
                .parseCertificate(tls.getValue().certificateChainPem());
        assertTrue(leaf.getSubjectAlternativeNames().stream().anyMatch(san -> address.equals(san.get(1))),
                "the leaf names the cache hostname");
    }

    @Test
    void memcachedCachesServeOn11211WithReader11212() {
        service.createServerlessCache(request("mc", "memcached", null), REGION);

        ServerlessCache described = describe("mc");
        assertEquals("1.6", described.getMajorEngineVersion());
        assertEquals(11211, described.getEndpoint().port());
        assertEquals(11212, described.getReaderEndpoint().port());
        verify(containers).tryStart(anyString(), eq("memcached"), eq("memcached:1.6"), anyString(), anyString(), any());
    }

    @Test
    void withoutDockerTheCacheIsAvailableButItsHostnameIsRefused() {
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(null);

        service.createServerlessCache(request("nodocker", "valkey", null), REGION);

        ServerlessCache described = describe("nodocker");
        assertEquals("available", described.getStatus());
        verify(endpoints).refuse(described.getEndpoint().address());
        verify(endpoints, never()).routeToContainer(anyString(), anyString());
    }

    @Test
    void failedProvisioningReportsCreateFailed() {
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("image pull failed"));

        service.createServerlessCache(request("broken", "valkey", null), REGION);

        assertEquals("create-failed", describe("broken").getStatus());
    }

    @Test
    void createValidatesLikeAws() {
        assertError("InvalidParameterValue", () -> service.createServerlessCache(request(null, "valkey", null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(request("1cache", "valkey", null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(request("a--b", "valkey", null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(request("cache-", "valkey", null), REGION));
        assertError("InvalidParameterValue",
                () -> service.createServerlessCache(request("x".repeat(41), "valkey", null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(request("c", "mongodb", null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(request("c", null, null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(
                withVersion(request("c", "redis", null), "6"), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(
                request("c", "valkey", limits(0, 1000)), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(
                request("c", "valkey", limits(1, 999)), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(
                request("c", "valkey", new UsageLimits(true, 5, null, null, false, null, null)), REGION));
        assertError("InvalidParameterCombination", () -> service.createServerlessCache(
                request("c", "valkey", new UsageLimits(true, 5, 10, "GB", false, null, null)), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(new CreateRequest("c", null, "valkey",
                null, null, null, null, null, Map.of(), null, null, 36, null, null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(new CreateRequest("c", null, "valkey",
                null, null, null, null, null, Map.of(), null, null, null, "25:00", null), REGION));
        assertError("UserGroupNotFound", () -> service.createServerlessCache(new CreateRequest("c", null, "valkey",
                null, null, null, null, null, Map.of(), "my-user-group", null, null, null, null), REGION));
        assertError("InvalidParameterCombination", () -> service.createServerlessCache(new CreateRequest("c", null,
                "memcached", null, null, null, null, null, Map.of(), "my-user-group", null, null, null, null), REGION));
    }

    @Test
    void createResolvesSubnetsAndSecurityGroupsAgainstEc2() {
        assertError("InvalidParameterValue", () -> service.createServerlessCache(
                network("c", List.of("subnet-missing"), null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(
                network("c", List.of("subnet-default-us-east-1-a", "subnet-0other"), null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(
                network("c", null, List.of("sg-missing")), REGION));
        assertError("InvalidParameterCombination", () -> service.createServerlessCache(
                network("c", List.of("subnet-default-us-east-1-a"), List.of("sg-0other")), REGION));

        service.createServerlessCache(network("custom", List.of("subnet-0other"), List.of("sg-0other")), REGION);
        ServerlessCache described = describe("custom");
        assertEquals(List.of("subnet-0other"), described.getSubnetIds());
        assertEquals(List.of("sg-0other"), described.getSecurityGroupIds());
    }

    @Test
    void duplicateAndMissingCachesUseTheTypedFaults() {
        service.createServerlessCache(request("dup", "valkey", null), REGION);

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createServerlessCache(request("dup", "valkey", null), REGION));
        assertEquals("ServerlessCacheAlreadyExistsFault", duplicate.getErrorCode());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeServerlessCaches("nope", null, null, REGION));
        assertEquals("ServerlessCacheNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void modifyAnswersModifyingAndOnlyRunsOnAvailableCaches() {
        deferTasks = true;
        service.createServerlessCache(request("mod", "redis", limits(1, 1000)), REGION);
        assertError("InvalidServerlessCacheStateFault", () -> service.modifyServerlessCache(
                modify("mod", limits(2, 2000), null, null), REGION));
        runDeferred();

        ServerlessCache modified = service.modifyServerlessCache(modify("mod", limits(2, 2000), "valkey", null), REGION);
        assertEquals("modifying", modified.getStatus());
        assertEquals(2, modified.getDataStorageMaximum());
        assertEquals("valkey", modified.getEngine());
        assertEquals("8", modified.getMajorEngineVersion());
        runDeferred();
        assertEquals("available", describe("mod").getStatus());

        assertError("InvalidParameterCombination", () -> service.modifyServerlessCache(
                modify("mod", null, "redis", null), REGION));
        assertError("InvalidParameterCombination", () -> service.modifyServerlessCache(
                modify("mod", null, null, "7"), REGION));
    }

    @Test
    void deleteAnswersDeletingThenTheCacheAndItsRouteAreGone() {
        deferTasks = true;
        service.createServerlessCache(request("gone", "valkey", null), REGION);
        assertError("InvalidServerlessCacheStateFault",
                () -> service.deleteServerlessCache("gone", null, REGION));
        runDeferred();
        String address = describe("gone").getEndpoint().address();

        ServerlessCache deleting = service.deleteServerlessCache("gone", null, REGION);
        assertEquals("deleting", deleting.getStatus());
        assertEquals("deleting", describe("gone").getStatus());
        assertError("InvalidServerlessCacheStateFault",
                () -> service.deleteServerlessCache("gone", null, REGION));
        runDeferred();

        assertError("ServerlessCacheNotFoundFault", () -> service.describeServerlessCaches("gone", null, null, REGION));
        verify(containers).stop(any());
        verify(endpoints).release(address);
    }

    @Test
    void snapshotsCaptureTheKeyspaceAndRestoreIntoANewCache() throws Exception {
        List<ServerlessCacheSnapshotEntry> keyspace = List.of(
                new ServerlessCacheSnapshotEntry("a2V5", "cGF5bG9hZA==", null, 0));
        when(dataPlane.capture("valkey", "127.0.0.1", 41000)).thenReturn(keyspace);
        service.createServerlessCache(request("source", "valkey", null), REGION);

        deferTasks = true;
        ServerlessCacheSnapshot creating = service.createServerlessCacheSnapshot("snap", "source", null, Map.of(), REGION);
        assertEquals("creating", creating.getStatus());
        assertEquals("manual", creating.getSnapshotType());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:snap", creating.getArn());
        assertError("InvalidServerlessCacheSnapshotStateFault",
                () -> service.deleteServerlessCacheSnapshot("snap", REGION));
        assertError("ServerlessCacheSnapshotAlreadyExistsFault",
                () -> service.createServerlessCacheSnapshot("snap", "source", null, Map.of(), REGION));
        runDeferred();

        ServerlessCacheSnapshot available = service.describeServerlessCacheSnapshots(null, "snap", null, null, null,
                REGION).items().getFirst();
        assertEquals("available", available.getStatus());
        assertEquals(10, available.getBytesUsedForCache());
        assertEquals("source", available.getServerlessCacheName());

        ServerlessCacheSnapshot copy = service.copyServerlessCacheSnapshot("snap", "snap-copy", null, Map.of(), REGION);
        assertEquals("creating", copy.getStatus());
        runDeferred();

        service.createServerlessCache(new CreateRequest("restored", null, "valkey", null, null, null, null,
                List.of("arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:snap-copy"),
                Map.of(), null, null, null, null, null), REGION);
        runDeferred();
        verify(dataPlane).restore("valkey", "127.0.0.1", 41000, keyspace);
        assertEquals("available", describe("restored").getStatus());

        ServerlessCacheSnapshot deleted = service.deleteServerlessCacheSnapshot("snap", REGION);
        assertEquals("deleting", deleted.getStatus());
        assertError("ServerlessCacheSnapshotNotFoundFault",
                () -> service.describeServerlessCacheSnapshots(null, "snap", null, null, null, REGION));
    }

    @Test
    void snapshotsRequireAnAvailableCacheAndCompatibleEngine() {
        deferTasks = true;
        service.createServerlessCache(request("busy", "valkey", null), REGION);
        assertError("InvalidServerlessCacheStateFault",
                () -> service.createServerlessCacheSnapshot("s1", "busy", null, Map.of(), REGION));
        assertError("ServerlessCacheNotFoundFault",
                () -> service.createServerlessCacheSnapshot("s1", "missing", null, Map.of(), REGION));
        runDeferred();
        service.createServerlessCacheSnapshot("s1", "busy", null, Map.of(), REGION);
        runDeferred();

        assertError("InvalidParameterCombination", () -> service.createServerlessCache(new CreateRequest("mc", null,
                "memcached", null, null, null, null,
                List.of("arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:s1"),
                Map.of(), null, null, null, null, null), REGION));
        assertError("InvalidParameterValue", () -> service.createServerlessCache(new CreateRequest("v", null,
                "valkey", null, null, null, null,
                List.of("arn:aws:elasticache:us-east-1:000000000000:serverlesscachesnapshot:none"),
                Map.of(), null, null, null, null, null), REGION));
    }

    @Test
    void exportValidatesTheSnapshotAndThenRefusesExplicitly() {
        assertError("ServerlessCacheSnapshotNotFoundFault",
                () -> service.exportServerlessCacheSnapshot("none", "bucket", REGION));
        service.createServerlessCache(request("exp", "valkey", null), REGION);
        service.createServerlessCacheSnapshot("exp-snap", "exp", null, Map.of(), REGION);

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportServerlessCacheSnapshot("exp-snap", "bucket", REGION));
        assertEquals("InvalidParameterValue", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("not supported"));
    }

    @Test
    void tagsAreReadAndWrittenOnCachesAndSnapshots() {
        service.createServerlessCache(new CreateRequest("tagged", null, "valkey", null, null, null, null, null,
                Map.of("fixture", "elasticache-serverless"), null, null, null, null, null), REGION);

        service.addTags("serverlesscache", "tagged", Map.of("team", "cache"), REGION);
        service.removeTags("serverlesscache", "tagged", List.of("fixture"), REGION);

        assertEquals(Map.of("team", "cache"), service.listTags("serverlesscache", "tagged", REGION));
        assertError("ServerlessCacheSnapshotNotFoundFault",
                () -> service.listTags("serverlesscachesnapshot", "none", REGION));
    }

    @Test
    void describePagesWithNextToken() {
        for (String name : List.of("p1", "p2", "p3")) {
            service.createServerlessCache(request(name, "valkey", null), REGION);
        }

        ElastiCacheServerlessService.Page<ServerlessCache> first = service.describeServerlessCaches(null, 2, null, REGION);
        assertEquals(List.of("p1", "p2"), first.items().stream().map(ServerlessCache::getServerlessCacheName).toList());
        assertNotNull(first.nextToken());
        ElastiCacheServerlessService.Page<ServerlessCache> second =
                service.describeServerlessCaches(null, 2, first.nextToken(), REGION);
        assertEquals(List.of("p3"), second.items().stream().map(ServerlessCache::getServerlessCacheName).toList());
        assertNull(second.nextToken());
    }

    @Test
    void resetStopsEngineContainersAndReleasesTheirHostnames() {
        service.createServerlessCache(request("reset", "valkey", null), REGION);
        String address = describe("reset").getEndpoint().address();

        service.clear();

        verify(containers).stop(any());
        verify(endpoints).release(address);
    }

    private ServerlessCache describe(String name) {
        return service.describeServerlessCaches(name, null, null, REGION).items().getFirst();
    }

    private void runDeferred() {
        List<Runnable> tasks = new ArrayList<>(deferred);
        deferred.clear();
        tasks.forEach(Runnable::run);
    }

    private static void assertError(String code, Runnable call) {
        AwsException error = assertThrows(AwsException.class, call::run);
        assertEquals(code, error.getErrorCode(), error.getMessage());
    }

    private static UsageLimits limits(int storageGb, int ecpu) {
        return new UsageLimits(true, storageGb, null, "GB", true, ecpu, null);
    }

    private static CreateRequest request(String name, String engine, UsageLimits limits) {
        return new CreateRequest(name, "d", engine, null, limits, null, null, null, Map.of(), null, null, null,
                null, null);
    }

    private static CreateRequest withVersion(CreateRequest request, String majorVersion) {
        return new CreateRequest(request.serverlessCacheName(), request.description(), request.engine(),
                majorVersion, request.cacheUsageLimits(), null, null, null, Map.of(), null, null, null, null, null);
    }

    private static CreateRequest network(String name, List<String> subnetIds, List<String> securityGroupIds) {
        return new CreateRequest(name, null, "valkey", null, null, null, securityGroupIds, null, Map.of(), null,
                subnetIds, null, null, null);
    }

    private static ModifyRequest modify(String name, UsageLimits limits, String engine, String majorVersion) {
        return new ModifyRequest(name, null, limits, null, null, null, null, null, engine, majorVersion);
    }

    private static Subnet subnet(String id, String vpcId, boolean defaultForAz) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(id);
        subnet.setVpcId(vpcId);
        subnet.setRegion(REGION);
        subnet.setAvailabilityZone(REGION + "a");
        subnet.setDefaultForAz(defaultForAz);
        return subnet;
    }

    private static SecurityGroup group(String id, String name, String vpcId) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(id);
        group.setGroupName(name);
        group.setVpcId(vpcId);
        group.setRegion(REGION);
        return group;
    }
}

package io.github.hectorvent.floci.services.kinesisanalytics.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link FlinkContainerManager}'s container specifications and
 * {@code application_properties.json} construction. The JSON shape must exactly match real MSF/KDA's
 * runtime file so a real {@code KinesisAnalyticsRuntime.getApplicationProperties()} call in a user's
 * JAR finds it. The file-injection path itself was verified live against a real {@code apache/flink}
 * image rather than re-implemented here with a mocked DockerClient.
 */
class FlinkContainerManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> AWS_ENV = List.of(
            "AWS_DEFAULT_REGION=us-west-2",
            "AWS_REGION=us-west-2",
            "AWS_ACCESS_KEY_ID=test",
            "AWS_SECRET_ACCESS_KEY=test",
            "AWS_SESSION_TOKEN=test",
            "FLOCI_HOSTNAME=localhost.floci.io",
            "FLOCI_ENDPOINT=http://localhost.floci.io:4566",
            "AWS_ENDPOINT_URL=http://localhost.floci.io:4566");

    private ContainerLifecycleManager lifecycleManager;
    private LaunchedContainerAwsEnv awsEnv;
    private S3Service s3Service;
    private FlinkRestClient flinkRest;
    private FlinkContainerManager manager;

    @BeforeEach
    void setUp() {
        EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        when(dockerConfig.logMaxSize()).thenReturn("10m");
        when(dockerConfig.logMaxFile()).thenReturn("3");

        EmulatorConfig.KinesisAnalyticsServiceConfig kinesisAnalyticsConfig =
                mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(kinesisAnalyticsConfig.defaultImage()).thenReturn(Optional.empty());

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kinesisAnalyticsConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.of("floci-network"));

        EmulatorConfig.DnsConfig dnsConfig = mock(EmulatorConfig.DnsConfig.class);
        when(dnsConfig.containerFallbackEnabled()).thenReturn(false);

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.docker()).thenReturn(dockerConfig);
        when(config.services()).thenReturn(servicesConfig);
        when(config.dns()).thenReturn(dnsConfig);
        when(config.defaultRegion()).thenReturn("us-west-2");

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        // The log4j-console.properties copy is now required (a failure fails cluster startup, see
        // log4jConfigCopyFailureRollsBackTheClusterInsteadOfStartingWithTheStockLogFormat below), so
        // every test that gets as far as create() succeeding needs a working docker client for that
        // copy to land on by default.
        when(lifecycleManager.getDockerClient()).thenReturn(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        s3Service = mock(S3Service.class);
        awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(eq("us-west-2"), eq(Optional.empty()))).thenReturn(AWS_ENV);
        flinkRest = mock(FlinkRestClient.class);

        manager = new FlinkContainerManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                containerDetector,
                config,
                regionResolver,
                awsEnv,
                s3Service,
                flinkRest,
                MAPPER);
    }

    private FlinkApplication application(String name) {
        return new FlinkApplication(name,
                "arn:aws:kinesisanalytics:us-west-2:000000000000:application/" + name,
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");
    }

    @Test
    void startClusterLabelsJobManagerContainerWithResourceIdentity() {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.KinesisAnalyticsServiceConfig kinesisAnalytics =
                Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.kinesisAnalytics()).thenReturn(kinesisAnalytics);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(kinesisAnalytics.defaultImage()).thenReturn(Optional.empty());
        EmulatorConfig.DockerConfig docker = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        EmulatorConfig.StorageConfig storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn("floci-data");

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("jm-container-id");
        when(lifecycleManager.getDockerClient()).thenReturn(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        when(lifecycleManager.startCreated(any(), any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("jm-container-id", Map.of(8081,
                        new ContainerLifecycleManager.EndpointInfo("localhost", 8081))));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        FlinkContainerManager manager = new FlinkContainerManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class), config,
                regionResolver, Mockito.mock(LaunchedContainerAwsEnv.class), Mockito.mock(S3Service.class),
                Mockito.mock(FlinkRestClient.class), MAPPER);

        FlinkApplication app = new FlinkApplication();
        app.setApplicationName("my-app");
        app.setApplicationArn("arn:aws:kinesisanalytics:us-east-1:000000000000:application/my-app");

        manager.startCluster(app);

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "kinesisanalytics",
                "io.floci.resource-id", "my-app",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }

    @Test
    void applicationPropertiesJsonMatchesTheRealMsfFileShape() throws Exception {
        FlinkApplication app = new FlinkApplication("demo", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.put("ProducerConfigProperties", Map.of("flink.stream.initpos", "LATEST", "aws.region", "us-west-2"));
        groups.put("ConsumerConfigProperties", Map.of("aws.region", "us-west-2"));
        app.setEnvironmentProperties(groups);

        byte[] json = manager.applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        assertTrue(root.isArray());
        assertEquals(2, root.size());
        assertEquals("ProducerConfigProperties", root.get(0).get("PropertyGroupId").asText());
        assertEquals("LATEST", root.get(0).get("PropertyMap").get("flink.stream.initpos").asText());
        assertEquals("us-west-2", root.get(0).get("PropertyMap").get("aws.region").asText());
        assertEquals("ConsumerConfigProperties", root.get(1).get("PropertyGroupId").asText());
    }

    @Test
    void applicationPropertiesJsonIsAnEmptyArrayWhenNoPropertiesConfigured() throws Exception {
        FlinkApplication app = new FlinkApplication("bare", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/bare",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");

        byte[] json = manager.applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        // Real MSF always provides the file, even with zero property groups configured, so
        // KinesisAnalyticsRuntime.getApplicationProperties() never has to handle a missing file.
        assertTrue(root.isArray());
        assertEquals(0, root.size());
    }

    @Test
    void msfStyleLog4j2ConfigBakesInTheApplicationArnAndVersion() throws Exception {
        FlinkApplication app = new FlinkApplication("demo",
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo",
                "FLINK-2_3", "arn:aws:iam::000000000000:role/x", "STREAMING");
        app.setApplicationVersionId(2L);

        String config = new String(manager.msfStyleLog4j2Config(app), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(config.contains(
                "\"applicationARN\":\"%enc{arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo}{JSON}\""));
        assertTrue(config.contains("\"applicationVersionId\":\"%enc{2}{JSON}\""));
        assertTrue(config.contains("\"messageSchemaVersion\":\"1\""));
        assertTrue(config.contains("appender.console.layout.type = PatternLayout"));
    }

    @Test
    void msfStyleLog4j2ConfigEscapesLog4jAndPropertiesSyntaxInTheApplicationName() throws Exception {
        // KinesisAnalyticsV2Service now rejects an ApplicationName outside AWS's own
        // [a-zA-Z0-9_.-] charset, but this stays defensive in case some other caller ever builds a
        // FlinkApplication directly: '%' would be a log4j2 conversion-specifier marker, '\' is a
        // java.util.Properties escape character (Flink loads this file with Properties-style
        // parsing), and '$' could start a log4j2 '${...}' Lookup that leaks an env var/system
        // property into every log line -- none of that may reach the pattern unescaped.
        FlinkApplication app = new FlinkApplication("100%-\\-${env:PATH}-owned",
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/100%-\\-${env:PATH}-owned",
                "FLINK-2_3", "arn:aws:iam::000000000000:role/x", "STREAMING");
        app.setApplicationVersionId(1L);

        String config = new String(manager.msfStyleLog4j2Config(app), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(config.contains(
                "\"applicationARN\":\"%enc{arn:aws:kinesisanalytics:us-east-1:000000000000:application/100%%-\\\\-{env:PATH}-owned}{JSON}\""));

        java.util.Properties parsed = new java.util.Properties();
        parsed.load(new java.io.ByteArrayInputStream(config.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String loadedPattern = parsed.getProperty("appender.console.layout.pattern");
        assertTrue(loadedPattern.contains(
                "\"applicationARN\":\"%enc{arn:aws:kinesisanalytics:us-east-1:000000000000:application/100%%-\\-{env:PATH}-owned}{JSON}\""));
        assertFalse(loadedPattern.contains("${env:PATH}"), "the $ must not survive, or log4j2 would treat "
                + "this as a Lookup and resolve it against the environment at config-load time");
    }

    @Test
    void bareClusterInjectsAwsSdkEnvironmentAndContainerReachability() {
        FlinkApplication app = application("bare");
        when(lifecycleManager.create(any())).thenReturn("jm-id");
        when(lifecycleManager.startCreated(any(), any())).thenReturn(new ContainerInfo(
                "jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))));

        manager.startCluster(app);

        ContainerSpec jmSpec = captureCreatedSpecs().getFirst();
        assertTrue(jmSpec.env().containsAll(AWS_ENV));
        assertTrue(jmSpec.env().contains(
                "FLINK_PROPERTIES=jobmanager.rpc.address: localhost\nrest.bind-address: 0.0.0.0"));
        assertEquals(List.of("jobmanager"), jmSpec.cmd());
        assertEquals("floci-network", jmSpec.networkMode());
        assertTrue(jmSpec.extraHosts().contains("host.docker.internal:host-gateway"));
        assertTrue(jmSpec.dnsServers().contains("172.18.0.2"));
        verify(awsEnv).sdkBaselineEnv("us-west-2", Optional.empty());
    }

    @Test
    void codeClusterInjectsTheSameAwsSdkEnvironmentIntoBothFlinkProcesses() {
        FlinkApplication app = application("with-code");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        app.setParallelism(3);
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.create(any())).thenReturn("jm-id").thenReturn("tm-id");
        when(lifecycleManager.startCreated(any(), any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))))
                .thenReturn(new ContainerInfo("tm-id", Map.of()));

        manager.startCluster(app);

        List<ContainerSpec> specs = captureCreatedSpecs();
        ContainerSpec jmSpec = specs.get(0);
        ContainerSpec tmSpec = specs.get(1);
        assertTrue(jmSpec.env().containsAll(AWS_ENV));
        assertTrue(tmSpec.env().containsAll(AWS_ENV));
        assertEquals(List.of("jobmanager"), jmSpec.cmd());
        assertEquals(List.of("taskmanager"), tmSpec.cmd());
        assertTrue(jmSpec.env().stream().anyMatch(env -> env.startsWith("FLINK_PROPERTIES=jobmanager.rpc.address")));
        assertTrue(tmSpec.env().contains(
                "FLINK_PROPERTIES=jobmanager.rpc.address: localhost\ntaskmanager.numberOfTaskSlots: 3"));
        assertEquals("container:jm-id", tmSpec.networkMode());
        verify(awsEnv).sdkBaselineEnv("us-west-2", Optional.empty());
    }

    @Test
    void jobManagerStartFailureRemovesThePartialCluster() {
        FlinkApplication app = application("jm-failure");
        when(lifecycleManager.create(any())).thenThrow(new RuntimeException("jobmanager failed"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager, times(2)).removeIfExists("floci-kinesisanalytics-000000000000-us-west-2-jm-failure");
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-kinesisanalytics-000000000000-us-west-2-jm-failure-tm");
        assertNull(app.getContainerId());
        assertNull(app.getTaskManagerContainerId());
    }

    @Test
    void taskManagerStartFailureStopsTheJobManagerAndClearsClusterState() {
        FlinkApplication app = application("tm-failure");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.create(any())).thenReturn("jm-id")
                .thenThrow(new RuntimeException("taskmanager failed"));
        when(lifecycleManager.startCreated(any(), any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager).stopAndRemove("jm-id", null);
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-kinesisanalytics-000000000000-us-west-2-tm-failure-tm");
        assertNull(app.getContainerId());
        assertNull(app.getRestEndpoint());
        assertNull(app.getTaskManagerContainerId());
    }

    @Test
    void log4jConfigCopyFailureRollsBackTheClusterInsteadOfStartingWithTheStockLogFormat() {
        // Before this test, a failed copy of log4j-console.properties was only logged: startCreated()
        // still ran and startCluster() reported success, so the cluster silently kept the stock
        // (non-JSON) log format instead of the MSF-style CloudWatch schema this whole feature exists
        // to provide. copyFileIntoContainer(..., required=true) now rethrows instead, so this failure
        // takes the same create()-failure rollback path already covered above.
        FlinkApplication app = application("log4j-copy-failure");
        when(lifecycleManager.create(any())).thenReturn("jm-id");
        when(lifecycleManager.getDockerClient()).thenThrow(new RuntimeException("docker copy unavailable"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager, times(2)).removeIfExists("floci-kinesisanalytics-000000000000-us-west-2-log4j-copy-failure");
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-kinesisanalytics-000000000000-us-west-2-log4j-copy-failure-tm");
        verify(lifecycleManager, Mockito.never()).startCreated(any(), any());
        assertNull(app.getContainerId());
    }

    @Test
    void sameNameInAnotherScopeCannotRemoveRunningCluster() {
        FlinkApplication running = application("shared");
        when(lifecycleManager.create(any())).thenReturn("owned-jm");
        when(lifecycleManager.startCreated(any(), any())).thenReturn(new ContainerInfo(
                "owned-jm", Map.of(8081, new EndpointInfo("localhost", 49152))));
        manager.startCluster(running);

        FlinkApplication otherRegion = new FlinkApplication("shared",
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/shared",
                "FLINK-1_18", "role", "STREAMING");
        FlinkApplication otherAccount = new FlinkApplication("shared",
                "arn:aws:kinesisanalytics:us-west-2:111111111111:application/shared",
                "FLINK-1_18", "role", "STREAMING");
        manager.stopCluster(otherRegion);
        manager.stopCluster(otherAccount);
        verify(lifecycleManager, Mockito.never()).stopAndRemove(eq("owned-jm"), any());
        verify(lifecycleManager).removeIfExists("floci-kinesisanalytics-000000000000-us-east-1-shared");
        verify(lifecycleManager).removeIfExists("floci-kinesisanalytics-111111111111-us-west-2-shared");
        manager.stopAll();
        verify(lifecycleManager).stopAndRemove("owned-jm", null);
    }

    @Test
    void savepointsVolumeIsFlinkOwnedAndSharedWithTheTaskManager() {
        FlinkApplication app = application("snap");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        when(lifecycleManager.create(any())).thenReturn("jm-id").thenReturn("tm-id");
        when(lifecycleManager.startCreated(any(), any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))))
                .thenReturn(new ContainerInfo("tm-id", Map.of()));

        manager.startCluster(app);

        String volume = "floci-kinesisanalytics-000000000000-us-west-2-snap-savepoints";
        for (ContainerSpec spec : captureCreatedSpecs()) {
            assertTrue(spec.mounts().stream().anyMatch(m -> volume.equals(m.getSource())
                    && "/opt/flink/savepoints".equals(m.getTarget())), spec.cmd() + " must mount " + volume);
        }
        // log4j config + the flink-owned mount point are extracted into the JobManager before it starts.
        org.mockito.InOrder order = Mockito.inOrder(docker, lifecycleManager);
        order.verify(docker, Mockito.calls(2)).copyArchiveToContainerCmd("jm-id");
        order.verify(lifecycleManager).startCreated(eq("jm-id"), any());
    }

    @Test
    void savepointMountPointTarIsAFlinkOwnedDirectory() throws Exception {
        byte[] tar = FlinkContainerManager.flinkOwnedDirectoryTar("savepoints");
        try (var in = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                new java.io.ByteArrayInputStream(tar))) {
            var entry = in.getNextEntry();
            assertEquals("savepoints/", entry.getName());
            assertTrue(entry.isDirectory());
            assertEquals(9999L, entry.getLongUserId());
            assertEquals(9999L, entry.getLongGroupId());
            assertNull(in.getNextEntry());
        }
    }

    @Test
    void savepointAbortedBecauseTasksWereNotRunningIsRetriggered() throws Exception {
        FlinkApplication app = application("retry");
        app.setRestEndpoint("http://jm:8081");
        app.setFlinkJobId("job-1");
        var snapshot = new io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot("snap", 1L, "FLINK-1_20");
        snapshot.setFlinkRequestId("req-1");
        when(flinkRest.savepointStatus("http://jm:8081", "job-1", "req-1")).thenReturn(
                new FlinkRestClient.SavepointStatus("COMPLETED", null, true,
                        "CheckpointException: Checkpoint triggering task Source (1/1) is not being executed at "
                                + "the moment. Aborting checkpoint. Failure reason: Not all required tasks are "
                                + "currently running."));
        when(flinkRest.triggerSavepoint("http://jm:8081", "job-1", "/opt/flink/savepoints")).thenReturn("req-2");

        assertTrue(manager.advanceSnapshot(app, snapshot));
        assertEquals(io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus.CREATING,
                snapshot.getSnapshotStatus());
        assertEquals("req-2", snapshot.getFlinkRequestId());

        when(flinkRest.savepointStatus("http://jm:8081", "job-1", "req-2")).thenReturn(
                new FlinkRestClient.SavepointStatus("COMPLETED", "file:/opt/flink/savepoints/savepoint-1", false, null));
        assertTrue(manager.advanceSnapshot(app, snapshot));
        assertEquals(io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus.READY,
                snapshot.getSnapshotStatus());
    }

    @Test
    void savepointTaskRetriesAreBoundedAndOtherFailuresAreTerminal() {
        FlinkApplication app = application("bounded");
        app.setRestEndpoint("http://jm:8081");
        app.setFlinkJobId("job-1");
        var snapshot = new io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot("snap", 1L, "FLINK-1_20");
        snapshot.setFlinkRequestId("req-1");
        snapshot.setFlinkTriggerAttempts(FlinkContainerManager.MAX_SAVEPOINT_TRIGGER_ATTEMPTS);
        when(flinkRest.savepointStatus(any(), any(), any())).thenReturn(new FlinkRestClient.SavepointStatus(
                "COMPLETED", null, true, "Not all required tasks are currently running."));
        assertTrue(manager.advanceSnapshot(app, snapshot));
        assertEquals(io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus.FAILED,
                snapshot.getSnapshotStatus());

        var ioFailure = new io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot("io", 1L, "FLINK-1_20");
        ioFailure.setFlinkRequestId("req-io");
        when(flinkRest.savepointStatus(any(), any(), eq("req-io"))).thenReturn(new FlinkRestClient.SavepointStatus(
                "COMPLETED", null, true, "java.io.IOException: Failed to create savepoint directory"));
        assertTrue(manager.advanceSnapshot(app, ioFailure));
        assertEquals(io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus.FAILED,
                ioFailure.getSnapshotStatus());
    }

    @Test
    void jobIsOnlyRunningOnceEveryVertexIsDeployed() throws Exception {
        assertFalse(FlinkRestClient.jobFullyRunning(MAPPER.readTree(
                "{\"state\":\"RUNNING\",\"vertices\":[{\"status\":\"RUNNING\"},{\"status\":\"DEPLOYING\"}]}")));
        assertFalse(FlinkRestClient.jobFullyRunning(MAPPER.readTree(
                "{\"state\":\"CREATED\",\"vertices\":[{\"status\":\"CREATED\"}]}")));
        assertTrue(FlinkRestClient.jobFullyRunning(MAPPER.readTree(
                "{\"state\":\"RUNNING\",\"vertices\":[{\"status\":\"RUNNING\"},{\"status\":\"FINISHED\"}]}")));
    }

    @Test
    void savepointStatusParsesTheFlinkFailureCause() throws Exception {
        FlinkRestClient.SavepointStatus status = FlinkRestClient.parseSavepointStatus(MAPPER.readTree(
                "{\"status\":{\"id\":\"COMPLETED\"},\"operation\":{\"failure-cause\":{\"class\":\"x\","
                        + "\"stack-trace\":\"Failure reason: Not all required tasks are currently running.\"}}}"));
        assertTrue(status.failed());
        assertTrue(status.tasksNotRunning());
        FlinkRestClient.SavepointStatus ok = FlinkRestClient.parseSavepointStatus(MAPPER.readTree(
                "{\"status\":{\"id\":\"COMPLETED\"},\"operation\":{\"location\":\"file:/opt/flink/savepoints/s\"}}"));
        assertFalse(ok.failed());
        assertFalse(ok.tasksNotRunning());
        assertEquals("file:/opt/flink/savepoints/s", ok.location());
    }

    private List<ContainerSpec> captureCreatedSpecs() {
        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager, atLeastOnce()).create(captor.capture());
        return captor.getAllValues();
    }
}

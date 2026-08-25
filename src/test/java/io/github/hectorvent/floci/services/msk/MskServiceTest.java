package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class MskServiceTest {

    private MskService mskService;
    private StorageFactory storageFactory;
    private EmulatorConfig config;
    private RedpandaManager redpandaManager;

    @BeforeEach
    void setUp() {
        storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var mskConfig = Mockito.mock(EmulatorConfig.MskServiceConfig.class);
        
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.msk()).thenReturn(mskConfig);
        when(mskConfig.mock()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");

        redpandaManager = Mockito.mock(RedpandaManager.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        mskService = new MskService(storageFactory, config, regionResolver, redpandaManager);
    }

    @Test
    void createCluster() {
        MskCluster cluster = mskService.createCluster("test-cluster");
        assertNotNull(cluster);
        assertEquals("test-cluster", cluster.getClusterName());
        assertEquals(ClusterState.ACTIVE, cluster.getState());
        assertTrue(cluster.getClusterArn().contains("test-cluster"));
    }

    @Test
    void createClusterPopulatesCurrentBrokerSoftwareInfoWithDefaultKafkaVersion() {
        MskCluster cluster = mskService.createCluster("test-cluster");
        assertNotNull(cluster.getCurrentBrokerSoftwareInfo());
        assertEquals("3.6.0", cluster.getCurrentBrokerSoftwareInfo().getKafkaVersion());
    }

    @Test
    void createClusterEchoesRequestedKafkaVersion() {
        MskCluster cluster = mskService.createCluster("test-cluster", "3.5.1");
        assertNotNull(cluster.getCurrentBrokerSoftwareInfo());
        assertEquals("3.5.1", cluster.getCurrentBrokerSoftwareInfo().getKafkaVersion());
    }

    @Test
    void describeCluster() {
        MskCluster created = mskService.createCluster("test-cluster");
        MskCluster described = mskService.describeCluster(created.getClusterArn());
        assertEquals(created.getClusterArn(), described.getClusterArn());
    }

    @Test
    void listClusters() {
        mskService.createCluster("cluster-1");
        mskService.createCluster("cluster-2");
        List<MskCluster> clusters = mskService.listClusters();
        assertEquals(2, clusters.size());
    }

    @Test
    void deleteCluster() {
        MskCluster cluster = mskService.createCluster("test-cluster");
        mskService.deleteCluster(cluster.getClusterArn());
        assertTrue(mskService.listClusters().isEmpty());
    }

    @Test
    void describeClusterOnANonexistentArnThrowsNotFoundException() {
        AwsException error = assertThrows(AwsException.class,
                () -> mskService.describeCluster(
                        "arn:aws:kafka:us-east-1:000000000000:cluster/alchemy-nonexistent-probe/00000000-0000-0000-0000-000000000000-1"));
        assertEquals("NotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void listTopicsOnANonexistentClusterThrowsNotFoundException() {
        AwsException error = assertThrows(AwsException.class,
                () -> mskService.listTopics(
                        "arn:aws:kafka:us-east-1:000000000000:cluster/alchemy-nonexistent-probe/00000000-0000-0000-0000-000000000000-1",
                        null));
        assertEquals("NotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void createTopicOnANonexistentClusterThrowsBadRequestException() {
        AwsException error = assertThrows(AwsException.class,
                () -> mskService.createTopic(
                        "arn:aws:kafka:us-east-1:000000000000:cluster/alchemy-nonexistent-probe/00000000-0000-0000-0000-000000000000-1",
                        Map.of("topicName", "alchemy-probe", "partitionCount", 1)));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createServerlessClusterV2IsActiveWithIamBootstrap() {
        MskCluster cluster = mskService.createClusterV2(Map.of(
                "clusterName", "serverless-unit",
                "serverless", Map.of(
                        "vpcConfigs", List.of(Map.of("subnetIds", List.of("subnet-a", "subnet-b"))),
                        "clientAuthentication", Map.of("sasl", Map.of("iam", Map.of("enabled", true))))));
        assertEquals("SERVERLESS", cluster.getClusterType());
        assertEquals(ClusterState.ACTIVE, cluster.getState());
        assertTrue(cluster.isIamAuthEnabled());
        assertEquals("localhost:9098", cluster.getBootstrapBrokers());
        Map<String, Object> brokers = mskService.bootstrapBrokersResponse(cluster.getClusterArn());
        assertEquals("localhost:9098", brokers.get("bootstrapBrokerStringSaslIam"));
    }

    @Test
    void topicRoundtripOnServerlessCluster() {
        MskCluster cluster = mskService.createClusterV2(Map.of(
                "clusterName", "topic-roundtrip",
                "serverless", Map.of("vpcConfigs", List.of())));
        assertTrue(mskService.listTopics(cluster.getClusterArn(), null).isEmpty());

        var created = mskService.createTopic(cluster.getClusterArn(), Map.of(
                "topicName", "alchemy-probe",
                "partitionCount", 1));
        assertEquals("alchemy-probe", created.getTopicName());
        assertEquals(1, created.getPartitionCount());

        var described = mskService.describeTopic(cluster.getClusterArn(), "alchemy-probe");
        assertEquals(1, described.getPartitionCount());

        mskService.deleteTopic(cluster.getClusterArn(), "alchemy-probe");
        assertTrue(mskService.listTopics(cluster.getClusterArn(), null).isEmpty());
    }
}

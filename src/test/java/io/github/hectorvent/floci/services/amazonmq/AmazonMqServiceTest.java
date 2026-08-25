package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.container.RabbitMqManager;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerState;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfiguration;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AmazonMqServiceTest {

    private AmazonMqService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(inv -> new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var mqConfig = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.amazonmq()).thenReturn(mqConfig);
        when(mqConfig.mock()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        RabbitMqManager rabbitMqManager = Mockito.mock(RabbitMqManager.class);
        service = new AmazonMqService(storageFactory, config, regionResolver, rabbitMqManager);
    }

    private CreateBrokerParams rabbitParams(String name) {
        return new CreateBrokerParams(name, "RABBITMQ", null, "SINGLE_INSTANCE",
                "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123", true, null)), null);
    }

    @Test
    void createBrokerComesUpRunningWithEndpoints() {
        Broker broker = service.createBroker(rabbitParams("orders"));

        assertEquals("orders", broker.getBrokerName());
        assertEquals("RABBITMQ", broker.getEngineType());
        assertEquals(BrokerState.RUNNING, broker.getBrokerState());
        assertTrue(broker.getBrokerId().startsWith("b-"));
        assertTrue(broker.getBrokerArn().contains(":mq:"));
        assertTrue(broker.getBrokerArn().contains("orders"));
        assertFalse(broker.getBrokerInstances().isEmpty());
        assertFalse(broker.getBrokerInstances().get(0).getEndpoints().isEmpty());
    }

    @Test
    void createBrokerDefaultsEngineVersionWhenAbsent() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        assertEquals("3.13", broker.getEngineVersion());
    }

    @Test
    void createBrokerRejectsNonRabbitEngine() {
        CreateBrokerParams activeMq = new CreateBrokerParams("legacy", "ACTIVEMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false, null, null);
        assertThrows(AwsException.class, () -> service.createBroker(activeMq));
    }

    @Test
    void createBrokerRejectsNonSingleInstanceDeployment() {
        CreateBrokerParams cluster = new CreateBrokerParams("ha", "RABBITMQ", null,
                "CLUSTER_MULTI_AZ", "mq.t3.micro", false, false, null, null);
        assertThrows(AwsException.class, () -> service.createBroker(cluster));
    }

    @Test
    void createBrokerRejectsDuplicateName() {
        service.createBroker(rabbitParams("orders"));
        assertThrows(AwsException.class, () -> service.createBroker(rabbitParams("orders")));
    }

    @Test
    void describeBrokerThrowsWhenMissing() {
        assertThrows(AwsException.class, () -> service.describeBroker("b-does-not-exist"));
    }

    @Test
    void deleteBrokerRemovesIt() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        service.deleteBroker(broker.getBrokerId());
        assertTrue(service.listBrokers().isEmpty());
    }

    @Test
    void userApiRejectedForRabbitMq() {
        // The standalone User API applies only to ActiveMQ; AWS rejects it for
        // RabbitMQ brokers, and every broker we host is RabbitMQ.
        Broker broker = service.createBroker(rabbitParams("orders"));
        String id = broker.getBrokerId();

        AwsException create = assertThrows(AwsException.class,
                () -> service.createUser(id, new MqUser("alice", "AnotherPass99", false, null)));
        assertEquals("BadRequestException", create.getErrorCode());
        assertThrows(AwsException.class, () -> service.listUsers(id));
        assertThrows(AwsException.class, () -> service.describeUser(id, "alice"));
        assertThrows(AwsException.class, () -> service.deleteUser(id, "alice"));
    }

    @Test
    void userApiThrowsNotFoundWhenBrokerMissing() {
        // Live AWS ListUsers/CreateUser on a well-formed but missing broker id
        // is NotFoundException, not the RabbitMQ BadRequest that applies only
        // after the broker is shown to exist.
        String missing = "b-00000000-0000-0000-0000-000000000000";

        AwsException list = assertThrows(AwsException.class, () -> service.listUsers(missing));
        assertEquals("NotFoundException", list.getErrorCode());
        assertEquals(404, list.getHttpStatus());

        AwsException create = assertThrows(AwsException.class,
                () -> service.createUser(missing, new MqUser("alchemyprobe", "SuperSecretPassw0rd!", false, null)));
        assertEquals("NotFoundException", create.getErrorCode());
        assertEquals(404, create.getHttpStatus());
    }

    @Test
    void createBrokerSeedsAdminUser() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        assertEquals(1, broker.getUsers().size());
        assertEquals("admin", broker.getUsers().get(0).getUsername());
        assertEquals("AdminPass123", broker.getUsers().get(0).getPassword());
    }

    @Test
    void createBrokerRequiresExactlyOneUser() {
        CreateBrokerParams noUsers = new CreateBrokerParams("orders", "RABBITMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false, null, null);
        assertThrows(AwsException.class, () -> service.createBroker(noUsers));
    }

    @Test
    void createBrokerRejectsWeakPassword() {
        CreateBrokerParams weak = new CreateBrokerParams("orders", "RABBITMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "short", true, null)), null);
        assertThrows(AwsException.class, () -> service.createBroker(weak));
    }

    @Test
    void createBrokerMarksFailedWhenProvisioningThrows() {
        AmazonMqService realModeService = realModeServiceWithFailingManager();

        assertThrows(AwsException.class,
                () -> realModeService.createBroker(rabbitParams("orders")));

        // The failed broker is persisted as CREATION_FAILED, not left dangling.
        List<Broker> brokers = realModeService.listBrokers();
        assertEquals(1, brokers.size());
        assertEquals(BrokerState.CREATION_FAILED, brokers.get(0).getBrokerState());
    }

    @Test
    void rebootRunningBrokerStaysRunning() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        Broker rebooted = service.rebootBroker(broker.getBrokerId());
        assertEquals(BrokerState.RUNNING, rebooted.getBrokerState());
    }

    @Test
    void rebootRejectsNonRunningBroker() {
        // A failed-provisioning broker is CREATION_FAILED; AWS allows RebootBroker
        // only on a RUNNING broker, so rebooting it must throw rather than promote
        // it (with no backing container) to RUNNING.
        AmazonMqService realModeService = realModeServiceWithFailingManager();
        assertThrows(AwsException.class,
                () -> realModeService.createBroker(rabbitParams("orders")));
        Broker failed = realModeService.listBrokers().get(0);
        assertEquals(BrokerState.CREATION_FAILED, failed.getBrokerState());

        assertThrows(AwsException.class, () -> realModeService.rebootBroker(failed.getBrokerId()));
    }

    @Test
    void createConfigurationSeedsRevisionOneWithDefaultData() {
        MqConfiguration configuration = service.createConfiguration(activeMqConfig("orders-cfg"));

        assertTrue(configuration.getId().startsWith("c-"));
        assertTrue(configuration.getArn().contains(":configuration:"));
        assertEquals("ActiveMQ", configuration.getEngineType());
        assertEquals("5.18", configuration.getEngineVersion());
        assertEquals(1, configuration.latestRevision().getRevision());
        assertNotEquals("", configuration.latestRevision().getData());
        assertEquals("messaging", configuration.getTags().get("team"));
    }

    @Test
    void updateConfigurationPublishesNextRevision() {
        MqConfiguration created = service.createConfiguration(activeMqConfig("orders-cfg"));
        String xml = "<broker xmlns=\"http://activemq.apache.org/schema/core\"/>";
        String encoded = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));

        MqConfiguration updated = service.updateConfiguration(
                created.getId(), encoded, "alchemy test config");

        assertEquals(2, updated.latestRevision().getRevision());
        assertEquals(xml, updated.latestRevision().getData());
        assertEquals("alchemy test config", updated.latestRevision().getDescription());
    }

    @Test
    void describeConfigurationMissingIsNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.describeConfiguration("c-00000000-0000-0000-0000-000000000000"));
        assertEquals("NotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void describeBrokerEngineTypesFiltersActiveMq() {
        List<java.util.Map<String, Object>> types = service.describeBrokerEngineTypes("ACTIVEMQ");
        assertEquals(1, types.size());
        assertEquals("ACTIVEMQ", types.get(0).get("engineType"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> versions =
                (List<java.util.Map<String, Object>>) types.get(0).get("engineVersions");
        assertFalse(versions.isEmpty());
        assertNotNull(versions.get(0).get("name"));
    }

    @Test
    void deleteConfigurationRemovesIt() {
        MqConfiguration created = service.createConfiguration(activeMqConfig("orders-cfg"));
        service.deleteConfiguration(created.getId());
        assertThrows(AwsException.class, () -> service.describeConfiguration(created.getId()));
    }

    @Test
    void createTagsMergesOntoConfiguration() {
        MqConfiguration created = service.createConfiguration(activeMqConfig("orders-cfg"));
        service.createTags(created.getArn(), java.util.Map.of("env", "test"));
        assertEquals("messaging", service.describeConfiguration(created.getId()).getTags().get("team"));
        assertEquals("test", service.describeConfiguration(created.getId()).getTags().get("env"));
    }

    private CreateConfigurationParams activeMqConfig(String name) {
        return new CreateConfigurationParams(name, "ACTIVEMQ", "5.18", "SIMPLE",
                java.util.Map.of("team", "messaging"));
    }

    private AmazonMqService realModeServiceWithFailingManager() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(inv -> new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var mqConfig = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.amazonmq()).thenReturn(mqConfig);
        when(mqConfig.mock()).thenReturn(false);
        when(config.defaultRegion()).thenReturn("us-east-1");

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        RabbitMqManager failingManager = Mockito.mock(RabbitMqManager.class);
        Mockito.doThrow(new RuntimeException("docker unavailable"))
                .when(failingManager).startContainer(Mockito.any());
        return new AmazonMqService(storageFactory, config, regionResolver, failingManager);
    }
}

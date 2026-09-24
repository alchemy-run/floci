package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.container.ActiveMqBrokerConfig;
import io.github.hectorvent.floci.services.amazonmq.container.ActiveMqManager;
import io.github.hectorvent.floci.services.amazonmq.container.RabbitMqManager;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerState;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AmazonMqServiceTest {

    private AmazonMqService service;

    @BeforeEach
    void setUp() {
        service = newService(true, Mockito.mock(RabbitMqManager.class), Mockito.mock(ActiveMqManager.class),
                Mockito.mock(AmazonMqConfigurationService.class));
    }

    private static AmazonMqService newService(boolean mock, RabbitMqManager rabbitMqManager,
                                              ActiveMqManager activeMqManager,
                                              AmazonMqConfigurationService configurations) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AmazonMqServiceConfig mqConfig = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.amazonmq()).thenReturn(mqConfig);
        when(mqConfig.mock()).thenReturn(mock);
        when(config.defaultRegion()).thenReturn("us-east-1");

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        return new AmazonMqService(storageFactory, config, regionResolver, rabbitMqManager,
                activeMqManager, configurations);
    }

    private CreateBrokerParams rabbitParams(String name) {
        return new CreateBrokerParams(name, "RABBITMQ", null, "SINGLE_INSTANCE",
                "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123", true, null)), null);
    }

    private CreateBrokerParams activeMqParams(String name, String engineVersion) {
        return new CreateBrokerParams(name, "ACTIVEMQ", engineVersion, "SINGLE_INSTANCE",
                "mq.t3.micro", true, true,
                List.of(new MqUser("alchemyadmin", "SuperSecretPassw0rd!", false, null)), null);
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
    void createBrokerRejectsUnknownEngine() {
        CreateBrokerParams kafka = new CreateBrokerParams("legacy", "KAFKA", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123", true, null)), null);
        assertThrows(AwsException.class, () -> service.createBroker(kafka));
    }

    @Test
    void createBrokerAcceptsDeploymentModeCasing() {
        // DeploymentMode is matched case-insensitively for the same reason EngineType is: the
        // wire enum is upper case but callers spell it however their tooling does. The
        // canonical wire casing is stored so DescribeBroker reads back the enum value the SDKs
        // expect, whatever casing the request used.
        CreateBrokerParams mixedCase = new CreateBrokerParams("orders", "RABBITMQ", null,
                "single_instance", "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123", true, null)), null);
        Broker broker = service.createBroker(mixedCase);
        assertEquals("SINGLE_INSTANCE", broker.getDeploymentMode());
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
        // The standalone User API applies only to ActiveMQ; AWS rejects it for RabbitMQ brokers.
        Broker broker = service.createBroker(rabbitParams("orders"));
        String id = broker.getBrokerId();

        assertThrows(AwsException.class,
                () -> service.createUser(id, new MqUser("alice", "AnotherPass99", false, null)));
        assertThrows(AwsException.class, () -> service.listUsers(id));
        assertThrows(AwsException.class, () -> service.describeUser(id, "alice"));
        assertThrows(AwsException.class, () -> service.deleteUser(id, "alice"));
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
        // A failed-provisioning broker is CREATION_FAILED; AWS allows RebootBroker only on a
        // RUNNING broker, so rebooting it must throw rather than promote it to RUNNING.
        AmazonMqService realModeService = realModeServiceWithFailingManager();
        assertThrows(AwsException.class,
                () -> realModeService.createBroker(rabbitParams("orders")));
        Broker failed = realModeService.listBrokers().get(0);
        assertEquals(BrokerState.CREATION_FAILED, failed.getBrokerState());

        assertThrows(AwsException.class, () -> realModeService.rebootBroker(failed.getBrokerId()));
    }

    private AmazonMqService realModeServiceWithFailingManager() {
        RabbitMqManager failingManager = Mockito.mock(RabbitMqManager.class);
        Mockito.doThrow(new RuntimeException("docker unavailable"))
                .when(failingManager).startContainer(Mockito.any());
        return newService(false, failingManager, Mockito.mock(ActiveMqManager.class),
                Mockito.mock(AmazonMqConfigurationService.class));
    }

    // --- ActiveMQ ---

    @Test
    void createActiveMqBrokerComesUpRunningWithEveryProtocolEndpoint() {
        Broker broker = service.createBroker(activeMqParams("orders", "5.18"));

        assertEquals("ACTIVEMQ", broker.getEngineType());
        assertEquals("5.18", broker.getEngineVersion());
        assertEquals(BrokerState.RUNNING, broker.getBrokerState());
        List<String> endpoints = broker.getBrokerInstances().get(0).getEndpoints();
        assertEquals(5, endpoints.size());
        assertTrue(endpoints.get(0).startsWith("tcp://"));
        assertTrue(endpoints.stream().anyMatch(e -> e.startsWith("amqp://")));
        assertTrue(endpoints.stream().anyMatch(e -> e.startsWith("stomp://")));
        assertTrue(endpoints.stream().anyMatch(e -> e.startsWith("mqtt://")));
        assertTrue(endpoints.stream().anyMatch(e -> e.startsWith("ws://")));
        assertNotNull(broker.getBrokerInstances().get(0).getConsoleURL());
    }

    @Test
    void createActiveMqBrokerDefaultsToLatestEngineVersion() {
        Broker broker = service.createBroker(activeMqParams("orders", null));
        assertEquals("5.18", broker.getEngineVersion());
    }

    @Test
    void createActiveMqBrokerRejectsUnsupportedEngineVersion() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createBroker(activeMqParams("orders", "4.0")));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void createActiveMqBrokerRequiresAUser() {
        CreateBrokerParams noUsers = new CreateBrokerParams("orders", "ACTIVEMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false, List.of(), null);
        assertThrows(AwsException.class, () -> service.createBroker(noUsers));
    }

    @Test
    void createActiveMqBrokerAcceptsSeveralUsers() {
        CreateBrokerParams params = new CreateBrokerParams("orders", "ACTIVEMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123!", true, null),
                        new MqUser("app", "AppPassword123", false, List.of("apps"))), null);
        Broker broker = service.createBroker(params);
        assertEquals(2, service.listUsers(broker.getBrokerId()).size());
    }

    @Test
    void createUserStagesAPendingCreateThatDeleteDiscards() {
        Broker broker = service.createBroker(activeMqParams("orders", null));
        String id = broker.getBrokerId();

        service.createUser(id, new MqUser("alchemytenant", "AnotherSecretPassw0rd!", true, List.of("tenants")));

        MqUser described = service.describeUser(id, "alchemytenant");
        assertEquals("alchemytenant", described.getUsername());
        assertEquals(MqUser.CHANGE_CREATE, described.getPendingChange());
        assertEquals(Boolean.TRUE, described.getPendingConsoleAccess());
        assertEquals(List.of("tenants"), described.getPendingGroups());
        assertEquals(2, service.listUsers(id).size());

        service.deleteUser(id, "alchemytenant");
        assertEquals(1, service.listUsers(id).size());
        AwsException e = assertThrows(AwsException.class, () -> service.describeUser(id, "alchemytenant"));
        assertEquals("NotFoundException", e.getErrorCode());
    }

    @Test
    void createUserRejectsAnExistingUsername() {
        Broker broker = service.createBroker(activeMqParams("orders", null));
        AwsException e = assertThrows(AwsException.class, () -> service.createUser(broker.getBrokerId(),
                new MqUser("alchemyadmin", "AnotherSecretPassw0rd!", false, null)));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void userOperationsOnAMissingUserAreNotFound() {
        Broker broker = service.createBroker(activeMqParams("orders", null));
        String id = broker.getBrokerId();

        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.updateUser(id, "nobody", null, true, null)).getErrorCode());
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.deleteUser(id, "nobody")).getErrorCode());
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.describeUser(id, "nobody")).getErrorCode());
    }

    @Test
    void updateAndDeleteOfAnAppliedUserStayPendingUntilReboot() {
        Broker broker = service.createBroker(activeMqParams("orders", null));
        String id = broker.getBrokerId();
        service.createUser(id, new MqUser("reader", "ReaderPassw0rd!", false, null));
        service.rebootBroker(id);
        assertNull(service.describeUser(id, "reader").getPendingChange());

        service.updateUser(id, "reader", null, true, List.of("readers"));
        MqUser updated = service.describeUser(id, "reader");
        assertEquals(MqUser.CHANGE_UPDATE, updated.getPendingChange());
        assertFalse(updated.isConsoleAccess());
        assertEquals(Boolean.TRUE, updated.getPendingConsoleAccess());

        service.rebootBroker(id);
        MqUser applied = service.describeUser(id, "reader");
        assertNull(applied.getPendingChange());
        assertTrue(applied.isConsoleAccess());
        assertEquals(List.of("readers"), applied.getGroups());

        service.deleteUser(id, "reader");
        assertEquals(MqUser.CHANGE_DELETE, service.describeUser(id, "reader").getPendingChange());
        service.rebootBroker(id);
        assertThrows(AwsException.class, () -> service.describeUser(id, "reader"));
    }

    @Test
    void promoteOnActiveMqBrokerIsABadRequest() {
        Broker broker = service.createBroker(activeMqParams("orders", null));
        AwsException e = assertThrows(AwsException.class,
                () -> service.promote(broker.getBrokerId(), "SWITCHOVER"));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void activeMqBrokerStartsARealContainerWithItsUsersAndConfiguration() {
        ActiveMqManager activeMqManager = Mockito.mock(ActiveMqManager.class);
        AmazonMqConfigurationService configurations = Mockito.mock(AmazonMqConfigurationService.class);
        when(configurations.resolveRevision("c-1", null)).thenReturn(3);
        when(configurations.activeMqDocument("c-1", 3)).thenReturn("<broker/>");
        AmazonMqService realMode = newService(false, Mockito.mock(RabbitMqManager.class), activeMqManager,
                configurations);

        CreateBrokerParams params = new CreateBrokerParams("orders", "ACTIVEMQ", "5.18", "SINGLE_INSTANCE",
                "mq.t3.micro", true, true,
                List.of(new MqUser("alchemyadmin", "SuperSecretPassw0rd!", true, List.of("admins"))),
                null, "c-1", null, null, null, null, null);
        Broker broker = realMode.createBroker(params);

        assertEquals(BrokerState.CREATION_IN_PROGRESS, broker.getBrokerState());
        assertEquals(Integer.valueOf(3), broker.getConfigurationRevision());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActiveMqBrokerConfig.Credential>> credentials = ArgumentCaptor.forClass(List.class);
        Mockito.verify(activeMqManager).startContainer(Mockito.same(broker), credentials.capture(),
                Mockito.eq("<broker/>"));
        assertEquals(List.of(new ActiveMqBrokerConfig.Credential("alchemyadmin", "SuperSecretPassw0rd!", true,
                List.of("admins"))), credentials.getValue());

        when(activeMqManager.isReady(Mockito.any())).thenReturn(true);
        realMode.pollReadiness(broker);
        assertEquals(BrokerState.RUNNING, realMode.describeBroker(broker.getBrokerId()).getBrokerState());
    }

    @Test
    void activeMqRebootRestartsTheContainerWithStagedUsers() {
        ActiveMqManager activeMqManager = Mockito.mock(ActiveMqManager.class);
        AmazonMqService realMode = newService(false, Mockito.mock(RabbitMqManager.class), activeMqManager,
                Mockito.mock(AmazonMqConfigurationService.class));
        Broker broker = realMode.createBroker(activeMqParams("orders", null));
        when(activeMqManager.isReady(Mockito.any())).thenReturn(true);
        realMode.pollReadiness(broker);
        realMode.createUser(broker.getBrokerId(), new MqUser("tenant", "TenantPassw0rd!", false, null));

        Broker rebooting = realMode.rebootBroker(broker.getBrokerId());
        assertEquals(BrokerState.REBOOT_IN_PROGRESS, rebooting.getBrokerState());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActiveMqBrokerConfig.Credential>> credentials = ArgumentCaptor.forClass(List.class);
        Mockito.verify(activeMqManager, Mockito.timeout(5000).times(2))
                .startContainer(Mockito.any(Broker.class), credentials.capture(), Mockito.isNull());
        assertEquals(List.of("alchemyadmin", "tenant"),
                credentials.getValue().stream().map(ActiveMqBrokerConfig.Credential::username).toList());

        // The restart runs off the request thread; once it has finished the poller promotes the broker.
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            Broker current = realMode.describeBroker(broker.getBrokerId());
            realMode.pollReadiness(current);
            return current.getBrokerState() == BrokerState.RUNNING;
        });
        assertNull(realMode.describeUser(broker.getBrokerId(), "tenant").getPendingChange());
    }

    @Test
    void activeMqBrokerWhoseContainerExitsFailsCreation() {
        ActiveMqManager activeMqManager = Mockito.mock(ActiveMqManager.class);
        AmazonMqService realMode = newService(false, Mockito.mock(RabbitMqManager.class), activeMqManager,
                Mockito.mock(AmazonMqConfigurationService.class));
        Broker broker = realMode.createBroker(activeMqParams("orders", null));
        when(activeMqManager.isReady(Mockito.any())).thenReturn(false);
        when(activeMqManager.hasExited(Mockito.any())).thenReturn(true);

        realMode.pollReadiness(broker);

        assertEquals(BrokerState.CREATION_FAILED, realMode.describeBroker(broker.getBrokerId()).getBrokerState());
    }

    @Test
    void deletingAnActiveMqBrokerStopsItsContainer() {
        ActiveMqManager activeMqManager = Mockito.mock(ActiveMqManager.class);
        RabbitMqManager rabbitMqManager = Mockito.mock(RabbitMqManager.class);
        AmazonMqService realMode = newService(false, rabbitMqManager, activeMqManager,
                Mockito.mock(AmazonMqConfigurationService.class));
        Broker broker = realMode.createBroker(activeMqParams("orders", null));

        realMode.deleteBroker(broker.getBrokerId());

        Mockito.verify(activeMqManager).stopContainer(Mockito.any(Broker.class));
        Mockito.verify(activeMqManager).removeBrokerStorage(Mockito.any(Broker.class));
        Mockito.verifyNoInteractions(rabbitMqManager);
        assertTrue(realMode.listBrokers().isEmpty());
    }
}

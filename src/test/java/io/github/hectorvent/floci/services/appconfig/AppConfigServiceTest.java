package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigServiceTest {
    private AccountAwareStorageBackend<Application> applicationStore;
    private AccountAwareStorageBackend<Environment> environmentStore;
    private AccountAwareStorageBackend<Deployment> deploymentStore;
    private AccountAwareStorageBackend<String> activeConfigStore;
    private AppConfigService service;

    @BeforeEach
    void setUp() {
        applicationStore = mock(AccountAwareStorageBackend.class);
        environmentStore = mock(AccountAwareStorageBackend.class);
        AccountAwareStorageBackend<ConfigurationProfile> profileStore = mock(AccountAwareStorageBackend.class);
        deploymentStore = mock(AccountAwareStorageBackend.class);
        activeConfigStore = mock(AccountAwareStorageBackend.class);
        Map<String, Deployment> deployments = new HashMap<>();
        doAnswer(invocation -> {
            deployments.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Deployment.class));
            return null;
        }).when(deploymentStore).put(anyString(), any(Deployment.class));
        when(deploymentStore.scan(any())).thenAnswer(invocation -> List.copyOf(deployments.values()));
        when(deploymentStore.keys()).thenAnswer(invocation -> Set.copyOf(deployments.keySet()));
        doAnswer(invocation -> deployments.remove(invocation.getArgument(0, String.class)))
                .when(deploymentStore).delete(anyString());
        when(activeConfigStore.keys()).thenReturn(Set.of());
        StorageFactory storageFactory = mock(StorageFactory.class);
        doAnswer(invocation -> switch (invocation.getArgument(1, String.class)) {
            case "appconfig-applications.json" -> applicationStore;
            case "appconfig-environments.json" -> environmentStore;
            case "appconfig-profiles.json" -> profileStore;
            case "appconfig-deployments.json" -> deploymentStore;
            case "appconfig-active-configs.json" -> activeConfigStore;
            default -> mock(AccountAwareStorageBackend.class);
        }).when(storageFactory).create(anyString(), anyString(), any(TypeReference.class));

        Application application = new Application();
        application.setId("app");
        applicationStore.put("app", application);
        when(applicationStore.get("app")).thenReturn(Optional.of(application));
        Environment environment = new Environment();
        environment.setId("env");
        environment.setApplicationId("app");
        environmentStore.put("env", environment);
        when(environmentStore.get("env")).thenReturn(Optional.of(environment));
        ConfigurationProfile profile = new ConfigurationProfile();
        profile.setId("profile");
        profile.setApplicationId("app");
        when(profileStore.get("profile")).thenReturn(Optional.of(profile));
        service = new AppConfigService(storageFactory, mock(EmulatorConfig.class));
    }

    @Test
    void listDeploymentsReturnsDescendingPages() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::env::2", deployment("app", "env", 2));

        AppConfigService.DeploymentPage page = service.listDeployments("app", "env", 1, null);

        assertEquals(List.of(2), page.items().stream().map(DeploymentSummary::getDeploymentNumber).toList());
        assertNotNull(page.nextToken());
    }

    @Test
    void listDeploymentsCursorSurvivesNewDeployment() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::env::2", deployment("app", "env", 2));
        deploymentStore.put("app::env::3", deployment("app", "env", 3));

        AppConfigService.DeploymentPage firstPage = service.listDeployments("app", "env", 1, null);
        deploymentStore.put("app::env::4", deployment("app", "env", 4));

        AppConfigService.DeploymentPage secondPage = service.listDeployments(
                "app", "env", 1, firstPage.nextToken());

        assertEquals(List.of(2), secondPage.items().stream()
                .map(DeploymentSummary::getDeploymentNumber).toList());
    }

    @Test
    void listDeploymentsFiltersApplicationAndEnvironment() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("other::env::2", deployment("other", "env", 2));
        deploymentStore.put("app::other-env::3", deployment("app", "other-env", 3));

        AppConfigService.DeploymentPage page = service.listDeployments("app", "env", null, null);

        assertEquals(List.of(1), page.items().stream().map(DeploymentSummary::getDeploymentNumber).toList());
    }

    @Test
    void listDeploymentsRejectsInvalidPageSize() {
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 0, null));
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 51, null));
    }

    @Test
    void listDeploymentsRejectsUnknownToken() {
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 1, "unknown"));
    }

    @Test
    void deleteEnvironmentRemovesRuntimeState() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        when(activeConfigStore.keys()).thenReturn(Set.of("env::profile"));

        service.deleteEnvironment("app", "env");

        verify(deploymentStore).delete("app::env::1");
        verify(activeConfigStore).delete("env::profile");
        verify(environmentStore).delete("env");
    }

    @Test
    void startDeploymentUsesHighestNumberAfterAnotherEnvironmentIsDeleted() {
        Environment otherEnvironment = new Environment();
        otherEnvironment.setId("other-env");
        otherEnvironment.setApplicationId("app");
        when(environmentStore.get("other-env")).thenReturn(Optional.of(otherEnvironment));
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::other-env::2", deployment("app", "other-env", 2));
        deploymentStore.put("app::env::3", deployment("app", "env", 3));

        service.deleteEnvironment("app", "other-env");
        Deployment deployment = service.startDeployment("app", "env", Map.of(
                "ConfigurationProfileId", "profile",
                "ConfigurationVersion", "version",
                "DeploymentStrategyId", "AppConfig.AllAtOnce"));

        assertEquals(4, deployment.getDeploymentNumber());
        assertEquals(Set.of("app::env::1", "app::env::3", "app::env::4"), deploymentStore.keys());
    }

    @Test
    void concurrentEnvironmentDeletesDoNotBothSucceed() throws Exception {
        AtomicReference<Environment> stored = new AtomicReference<>(environmentStore.get("env").orElseThrow());
        CountDownLatch reads = new CountDownLatch(2);
        when(environmentStore.get("env")).thenAnswer(invocation -> {
            Environment environment = stored.get();
            reads.countDown();
            reads.await(200, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(environment);
        });
        doAnswer(invocation -> {
            stored.set(null);
            return null;
        }).when(environmentStore).delete("env");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(this::deleteEnvironment);
            Future<Boolean> second = executor.submit(this::deleteEnvironment);
            long successes = List.of(first.get(), second.get()).stream()
                    .filter(Boolean::booleanValue)
                    .count();
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean deleteEnvironment() {
        try {
            service.deleteEnvironment("app", "env");
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    private static Deployment deployment(String applicationId, String environmentId, int number) {
        Deployment deployment = new Deployment();
        deployment.setApplicationId(applicationId);
        deployment.setEnvironmentId(environmentId);
        deployment.setDeploymentNumber(number);
        deployment.setConfigurationProfileId("profile");
        deployment.setConfigurationVersion("version");
        deployment.setDeploymentStrategyId("AppConfig.AllAtOnce");
        deployment.setState("COMPLETE");
        return deployment;
    }
}

package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.ListApplicationsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmrServerlessServiceTest {

    private static final String ACCOUNT = "111111111111";
    private static final String ID = "00abcdefabcdef01";
    private InMemoryStorage<String, Application> rawStorage;
    private RequestContext requestContext;
    private EmrServerlessService service;

    @BeforeEach
    void setUp() {
        rawStorage = new InMemoryStorage<>();
        AccountAwareStorageBackend<Application> storage =
                new AccountAwareStorageBackend<>(rawStorage, null, ACCOUNT);
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(anyString(), anyString(), any());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn("us-east-1");
        requestContext = new RequestContext();
        requestContext.setAccountId(ACCOUNT);
        requestContext.setRegion("us-east-1");
        service = new EmrServerlessService(config, factory, mock(EmrServerlessJobService.class));
        service.requestContext = requestContext;
    }

    @Test
    void getMigratesBothLegacyKeyShapesOnlyInTheOwningScope() {
        for (String key : new String[] {ID, ACCOUNT + "/" + ID}) {
            rawStorage.clear();
            Application app = legacyApplication();
            rawStorage.put(key, app);

            requestContext.setAccountId("222222222222");
            assertNotFound();
            assertTrue(rawStorage.get(key).isPresent());
            requestContext.setAccountId(ACCOUNT);
            requestContext.setRegion("us-west-2");
            assertNotFound();
            assertTrue(rawStorage.get(key).isPresent());

            requestContext.setRegion("us-east-1");
            assertSame(app, service.getApplication(ID));
            assertTrue(rawStorage.get(key).isEmpty());
            assertSame(app, rawStorage.get(ACCOUNT + "/us-east-1/" + ID).orElseThrow());
            service.deleteApplication(ID);
            assertNotFound();
        }
    }

    @Test
    void listMigratesOnlyLegacyApplicationsOwnedByTheRequestedAccountAndRegion() {
        for (String key : new String[] {ID, ACCOUNT + "/" + ID}) {
            rawStorage.clear();
            rawStorage.put(key, legacyApplication());
            requestContext.setAccountId("222222222222");
            assertTrue(service.listApplications(new ListApplicationsRequest()).items().isEmpty());
            requestContext.setAccountId(ACCOUNT);
            requestContext.setRegion("us-west-2");
            assertTrue(service.listApplications(new ListApplicationsRequest()).items().isEmpty());
            assertTrue(rawStorage.get(key).isPresent());

            requestContext.setRegion("us-east-1");
            assertEquals(ID, service.listApplications(new ListApplicationsRequest()).items().getFirst().getId());
            assertTrue(rawStorage.get(key).isEmpty());
            assertTrue(rawStorage.get(ACCOUNT + "/us-east-1/" + ID).isPresent());
        }
    }

    @Test
    void aForeignArnUnderTheScopedKeyIsNotReturned() {
        Application app = legacyApplication();
        app.setArn("arn:aws:emr-serverless:us-west-2:" + ACCOUNT + ":/applications/" + ID);
        String key = ACCOUNT + "/us-east-1/" + ID;
        rawStorage.put(key, app);
        assertNotFound();
        assertTrue(service.listApplications(new ListApplicationsRequest()).items().isEmpty());
        assertSame(app, rawStorage.get(key).orElseThrow());
    }

    private Application legacyApplication() {
        Application app = new Application();
        app.setApplicationId(ID);
        app.setArn("arn:aws:emr-serverless:us-east-1:" + ACCOUNT + ":/applications/" + ID);
        app.setState("CREATED");
        return app;
    }

    private void assertNotFound() {
        AwsException error = assertThrows(AwsException.class, () -> service.getApplication(ID));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }
}

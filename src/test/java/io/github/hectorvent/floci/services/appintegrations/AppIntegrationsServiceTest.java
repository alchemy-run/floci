package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegration;
import io.github.hectorvent.floci.services.appintegrations.model.EventIntegration;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AppIntegrations domain rules that the REST layer only passes through: required members,
 * duplicate names, ARN-or-id resolution, and the partial-update semantics of the two
 * PATCH operations.
 */
class AppIntegrationsServiceTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppIntegrationsService service;

    @BeforeEach
    void setUp() {
        service = new AppIntegrationsService(new SharedStorageFactory(),
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void createEventIntegrationBuildsTheAwsArnAndStoresTheFilterSource() {
        EventIntegration integration = service.createEventIntegration("orders", "partner events",
                eventFilter("aws.partner/example.com/1234"), "floci-bus", Map.of("team", "data"), REGION);

        assertEquals("arn:aws:app-integrations:us-east-1:000000000000:event-integration/orders",
                integration.getEventIntegrationArn());
        assertEquals("aws.partner/example.com/1234", integration.getEventFilterSource());
        assertEquals("floci-bus", integration.getEventBridgeBus());
        assertEquals("data", integration.getTags().get("team"));
    }

    @Test
    void createEventIntegrationRejectsAMissingRequiredMember() {
        AwsException noName = assertThrows(AwsException.class, () -> service.createEventIntegration(
                null, null, eventFilter("aws.partner/x"), "floci-bus", Map.of(), REGION));
        assertEquals("InvalidRequestException", noName.getErrorCode());

        AwsException noBus = assertThrows(AwsException.class, () -> service.createEventIntegration(
                "orders", null, eventFilter("aws.partner/x"), null, Map.of(), REGION));
        assertEquals("InvalidRequestException", noBus.getErrorCode());

        AwsException noFilter = assertThrows(AwsException.class, () -> service.createEventIntegration(
                "orders", null, null, "floci-bus", Map.of(), REGION));
        assertEquals("InvalidRequestException", noFilter.getErrorCode());
    }

    @Test
    void duplicateEventIntegrationNameRaisesDuplicateResource() {
        service.createEventIntegration("orders", null, eventFilter("aws.partner/x"), "bus", Map.of(), REGION);

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createEventIntegration(
                "orders", null, eventFilter("aws.partner/x"), "bus", Map.of(), REGION));
        assertEquals("DuplicateResourceException", duplicate.getErrorCode());
        assertEquals(409, duplicate.getHttpStatus());
    }

    @Test
    void updateEventIntegrationLeavesAnAbsentDescriptionAlone() {
        service.createEventIntegration("orders", "first", eventFilter("aws.partner/x"), "bus", Map.of(), REGION);

        service.updateEventIntegration("orders", Optional.of("second"), REGION);
        assertEquals("second", service.getEventIntegration("orders", REGION).getDescription());

        service.updateEventIntegration("orders", Optional.empty(), REGION);
        assertEquals("second", service.getEventIntegration("orders", REGION).getDescription());
    }

    @Test
    void getMissingEventIntegrationRaisesResourceNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getEventIntegration("nope", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void createDataIntegrationRequiresNameAndKmsKey() {
        AwsException noName = assertThrows(AwsException.class, () -> service.createDataIntegration(
                null, null, "arn:aws:kms:us-east-1:000000000000:key/abc", null, null, null, null,
                Map.of(), REGION));
        assertEquals("InvalidRequestException", noName.getErrorCode());

        AwsException noKey = assertThrows(AwsException.class, () -> service.createDataIntegration(
                "orders", null, null, null, null, null, null, Map.of(), REGION));
        assertEquals("InvalidRequestException", noKey.getErrorCode());
    }

    @Test
    void dataIntegrationResolvesByIdAndByItsOwnArn() {
        DataIntegration created = service.createDataIntegration("orders", "pull", "key/abc",
                "Salesforce://AppFlow/test", null, null, null, Map.of(), REGION);

        assertEquals(created.getId(), service.getDataIntegration(created.getId(), REGION).getId());
        assertEquals(created.getId(), service.getDataIntegration(created.getArn(), REGION).getId());

        AwsException wrongType = assertThrows(AwsException.class, () -> service.getDataIntegration(
                "arn:aws:app-integrations:us-east-1:000000000000:event-integration/orders", REGION));
        assertEquals("InvalidRequestException", wrongType.getErrorCode());
    }

    @Test
    void updateDataIntegrationAppliesOnlyThePresentMembers() {
        DataIntegration created = service.createDataIntegration("orders", "pull", "key/abc",
                null, null, null, null, Map.of(), REGION);

        service.updateDataIntegration(created.getId(), Optional.empty(), Optional.of("revised"), REGION);

        DataIntegration updated = service.getDataIntegration(created.getId(), REGION);
        assertEquals("orders", updated.getName());
        assertEquals("revised", updated.getDescription());
    }

    @Test
    void tagsAreSharedAcrossBothResourceTypesByArn() {
        EventIntegration event = service.createEventIntegration("orders", null,
                eventFilter("aws.partner/x"), "bus", Map.of("team", "data"), REGION);
        DataIntegration data = service.createDataIntegration("orders-data", null, "key/abc",
                null, null, null, null, Map.of(), REGION);

        service.tagResource(REGION, data.getArn(), Map.of("tier", "gold"));
        assertEquals("gold", service.listTags(REGION, data.getArn()).get("tier"));
        assertEquals("data", service.listTags(REGION, event.getEventIntegrationArn()).get("team"));

        service.untagResource(REGION, event.getEventIntegrationArn(), List.of("team"));
        assertTrue(service.listTags(REGION, event.getEventIntegrationArn()).isEmpty());

        AwsException unknown = assertThrows(AwsException.class, () -> service.listTags(REGION,
                "arn:aws:app-integrations:us-east-1:000000000000:data-integration/missing"));
        assertEquals("ResourceNotFoundException", unknown.getErrorCode());
    }

    @Test
    void tagWritesAnswerTwoHundredAsTheAwsModelDoes() {
        assertEquals(200, service.tagResourceSuccessStatus());
        assertEquals(200, service.untagResourceSuccessStatus());
        assertEquals("app-integrations", service.serviceKey());
    }

    @Test
    void deleteRemovesTheIntegrationFromItsListing() {
        service.createEventIntegration("orders", null, eventFilter("aws.partner/x"), "bus", Map.of(), REGION);
        DataIntegration data = service.createDataIntegration("orders-data", null, "key/abc",
                null, null, null, null, Map.of(), REGION);

        service.deleteEventIntegration("orders", REGION);
        service.deleteDataIntegration(data.getArn(), REGION);

        assertTrue(service.listEventIntegrations(REGION).isEmpty());
        assertTrue(service.listDataIntegrations(REGION).isEmpty());
    }

    @Test
    void applicationLifecycleRetainsIdentityAndCopiesMutableConfiguration() {
        ObjectNode request = application("com.example.workspace");
        request.putObject("Tags").put("team", "support");
        ObjectNode created = service.createApplication(request, REGION);
        String id = created.path("Id").asText();
        String arn = created.path("Arn").asText();
        assertEquals("arn:aws:app-integrations:us-east-1:000000000000:application/" + id, arn);
        ObjectNode original = service.getApplication(arn, REGION);
        assertEquals("com.example.workspace", original.path("Namespace").asText());
        assertTrue(original.path("CreatedTime").isNumber());

        ObjectNode update = MAPPER.createObjectNode().put("Name", "renamed").put("Description", "updated");
        update.putArray("Permissions").add("User.Details.View");
        update.putObject("ApplicationSourceConfig").putObject("ExternalUrlConfig")
                .put("AccessUrl", "https://updated.example.com");
        service.updateApplication(id, update, REGION);
        update.putArray("Permissions");
        ObjectNode updated = service.getApplication(id, REGION);
        assertEquals(arn, updated.path("Arn").asText());
        assertEquals(original.get("CreatedTime"), updated.get("CreatedTime"));
        assertEquals("renamed", updated.path("Name").asText());
        assertEquals("User.Details.View", updated.path("Permissions").get(0).asText());
        assertEquals("https://updated.example.com", updated.path("ApplicationSourceConfig")
                .path("ExternalUrlConfig").path("AccessUrl").asText());
        service.updateApplication(arn, MAPPER.createObjectNode(), REGION);
        assertEquals("updated", service.getApplication(id, REGION).path("Description").asText());
        assertTrue(service.listApplicationAssociations(id, REGION).isEmpty());

        service.tagResource(REGION, arn, Map.of("env", "test"));
        service.untagResource(REGION, arn, List.of("team"));
        assertEquals(Map.of("env", "test"), service.listTags(REGION, arn));
        service.getApplication(id, REGION).put("Name", "not persisted");
        assertEquals("renamed", service.getApplication(id, REGION).path("Name").asText());
        service.deleteApplication(arn, REGION);
        assertTrue(service.listApplications(REGION, null).isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listApplicationAssociations(id, REGION)).getErrorCode());
        assertThrows(AwsException.class, () -> service.listTags(REGION, arn));
    }

    @Test
    void applicationNamespaceAndIdempotencyAreScopedAndValidated() {
        ObjectNode request = application("com.example.unique").put("ClientToken", "create-once");
        ObjectNode created = service.createApplication(request, REGION);
        assertEquals(created, service.createApplication(request.deepCopy(), REGION));
        assertEquals(1, service.listApplications(REGION, null).size());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.createApplication(request.deepCopy().put("Name", "different"), REGION)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.createApplication(application("com.example.unique"), REGION)).getErrorCode());
        ObjectNode otherRegion = service.createApplication(request, "us-west-2");
        assertNotEquals(created.path("Arn"), otherRegion.path("Arn"));
        assertThrows(AwsException.class, () -> service.getApplication(created.path("Arn").asText(), "us-west-2"));
        ObjectNode invalid = application("com.example.invalid");
        invalid.remove("ApplicationSourceConfig");
        assertThrows(AwsException.class, () -> service.createApplication(invalid, REGION));
        assertThrows(AwsException.class, () -> service.createApplication(MAPPER.createArrayNode(), REGION));
    }

    @Test
    void identifiersDoNotAliasForeignArnsAndRejectedRenamesLeaveStateUntouched() {
        DataIntegration first = service.createDataIntegration("first", null, "key/abc",
                "s3://source", null, null, null, Map.of(), REGION);
        service.createDataIntegration("second", null, "key/abc",
                "s3://source", null, null, null, Map.of(), REGION);
        for (String foreign : List.of(first.getArn().replace("000000000000", "111111111111"),
                first.getArn().replace("us-east-1", "us-west-2"),
                first.getArn().replace(":app-integrations:", ":another-service:"),
                first.getArn().replace("arn:aws:", "arn:aws-cn:"))) {
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> service.getDataIntegration(foreign, REGION)).getErrorCode());
        }
        assertEquals("DuplicateResourceException", assertThrows(AwsException.class,
                () -> service.updateDataIntegration(first.getId(), Optional.of("second"),
                        Optional.of("must not persist"), REGION)).getErrorCode());
        assertEquals("first", service.getDataIntegration(first.getId(), REGION).getName());
        assertNull(service.getDataIntegration(first.getId(), REGION).getDescription());
        assertTrue(service.listDataIntegrationAssociations(first.getId(), REGION).isEmpty());
        AwsException denied = service.dataIntegrationAssociationWriteDenied(first.getArn(),
                "CreateDataIntegrationAssociation", REGION);
        assertEquals("AccessDeniedException", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
        assertTrue(denied.getMessage().contains(first.getArn()));
        assertTrue(denied.getMessage().contains("explicit deny in a resource-based policy"));
    }

    @Test
    void allResourceTypesAndTokensAreIsolatedByAccount() {
        RequestContext context = new RequestContext();
        context.setAccountId("000000000000");
        SharedStorageFactory storage = new SharedStorageFactory(context);
        RegionResolver resolver = new RegionResolver(REGION, "000000000000") {
            @Override
            public String getAccountId() {
                return context.getAccountId();
            }
        };
        AppIntegrationsService scoped = new AppIntegrationsService(storage, resolver);
        ObjectNode request = application("com.example.accounts").put("ClientToken", "same-token");
        ObjectNode first = scoped.createApplication(request, REGION);
        EventIntegration event = scoped.createEventIntegration("shared-name", null,
                eventFilter("partner"), "default", Map.of(), REGION);
        DataIntegration data = scoped.createDataIntegration("shared-name", null, "key",
                "s3://source", null, null, null, Map.of(), REGION);

        context.setAccountId("111111111111");
        assertTrue(scoped.listApplications(REGION, null).isEmpty());
        assertTrue(scoped.listEventIntegrations(REGION).isEmpty());
        assertTrue(scoped.listDataIntegrations(REGION).isEmpty());
        assertThrows(AwsException.class, () -> scoped.getApplication(first.path("Id").asText(), REGION));
        assertThrows(AwsException.class, () -> scoped.getEventIntegration("shared-name", REGION));
        assertThrows(AwsException.class, () -> scoped.getDataIntegration(data.getId(), REGION));
        assertThrows(AwsException.class, () -> scoped.tagResource(REGION, first.path("Arn").asText(), Map.of("x", "y")));
        ObjectNode second = scoped.createApplication(request, REGION);
        assertTrue(second.path("Arn").asText().contains(":111111111111:application/"));
        assertNotEquals(first.path("Id"), second.path("Id"));
        scoped.createEventIntegration("shared-name", null, eventFilter("other"), "default", Map.of(), REGION);
        scoped.createDataIntegration("shared-name", null, "key", "s3://other", null, null, null, Map.of(), REGION);
        assertThrows(AwsException.class, () -> scoped.getEventIntegration(event.getEventIntegrationArn(), REGION));

        context.setAccountId("000000000000");
        assertEquals(first, scoped.createApplication(request, REGION));
        assertEquals("partner", scoped.getEventIntegration("shared-name", REGION).getEventFilterSource());
        assertEquals("s3://source", scoped.getDataIntegration(data.getId(), REGION).getSourceUri());
    }

    @Test
    void applicationsTagsAndIdempotencySurviveDiskReload(@TempDir Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("appintegrations")).thenReturn("persistent");
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        StorageFactory firstStorage = new StorageFactory(config, access);
        AppIntegrationsService first = new AppIntegrationsService(firstStorage, resolver);
        ObjectNode request = application("com.example.durable").put("ClientToken", "durable-token");
        ObjectNode created = first.createApplication(request, REGION);
        String arn = created.path("Arn").asText();
        first.updateApplication(arn, MAPPER.createObjectNode().put("Description", "persisted"), REGION);
        first.tagResource(REGION, arn, Map.of("durable", "true"));
        firstStorage.shutdownAll();

        StorageFactory secondStorage = new StorageFactory(config, access);
        AppIntegrationsService second = new AppIntegrationsService(secondStorage, resolver);
        assertEquals(created, second.createApplication(request, REGION));
        assertEquals("persisted", second.getApplication(arn, REGION).path("Description").asText());
        assertEquals(Map.of("durable", "true"), second.listTags(REGION, arn));
        second.deleteApplication(arn, REGION);
        secondStorage.shutdownAll();
        StorageFactory thirdStorage = new StorageFactory(config, access);
        AppIntegrationsService third = new AppIntegrationsService(thirdStorage, resolver);
        assertTrue(third.listApplications(REGION, null).isEmpty());
        assertThrows(AwsException.class, () -> third.getApplication(arn, REGION));
        thirdStorage.shutdownAll();
    }

    private ObjectNode application(String namespace) {
        ObjectNode request = MAPPER.createObjectNode().put("Name", "workspace").put("Namespace", namespace);
        request.putObject("ApplicationSourceConfig").putObject("ExternalUrlConfig")
                .put("AccessUrl", "https://example.com");
        return request;
    }

    private ObjectNode eventFilter(String source) {
        ObjectNode filter = MAPPER.createObjectNode();
        filter.put("Source", source);
        return filter;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();
        private final Instance<RequestContext> context;

        private SharedStorageFactory() {
            this(null);
        }

        @SuppressWarnings("unchecked")
        private SharedStorageFactory(RequestContext requestContext) {
            super(null, null);
            context = mock(Instance.class);
            when(context.get()).thenReturn(requestContext == null ? new RequestContext() : requestContext);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(
                    fileName, ignored -> new AccountAwareStorageBackend<>(new InMemoryStorage<>(),
                            context, "000000000000"));
        }
    }
}

package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailEventService;
import io.github.hectorvent.floci.services.detective.model.DetectiveState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DetectiveServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = MANAGEMENT_ACCOUNT;
    private static final String MEMBER_ACCOUNT = "333333333333";

    private RegionResolver regionResolver;
    private OrganizationsService organizationsService;
    private DetectiveService service;
    private CloudTrailEventService cloudTrailEvents;
    private ObjectMapper objectMapper;
    private AccountAwareStorageBackend<DetectiveState> storage;

    @BeforeEach
    void setUp() {
        regionResolver = mock(RegionResolver.class);
        organizationsService = mock(OrganizationsService.class);
        when(regionResolver.getAccountId()).thenReturn(MANAGEMENT_ACCOUNT);

        Organization organization = new Organization();
        organization.setMasterAccountId(MANAGEMENT_ACCOUNT);
        OrganizationAccount management = new OrganizationAccount();
        management.setId(MANAGEMENT_ACCOUNT);
        OrganizationAccount member = new OrganizationAccount();
        member.setId(MEMBER_ACCOUNT);
        when(organizationsService.describeOrganization(MANAGEMENT_ACCOUNT)).thenReturn(organization);
        when(organizationsService.listAccounts(MANAGEMENT_ACCOUNT)).thenReturn(java.util.List.of(management, member));

        cloudTrailEvents = mock(CloudTrailEventService.class);
        objectMapper = new ObjectMapper();
        storage = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        service = new DetectiveService(storage, regionResolver, organizationsService, cloudTrailEvents, objectMapper, true);
    }

    @Test
    void delegationCreatesGraphForAdministratorAccount() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        assertTrue(service.requireGraph(REGION).isGraph());
        assertEquals(ADMIN_ACCOUNT, service.requireGraph(REGION).getAdminAccountId());
    }

    @Test
    void organizationMemberDoesNotRequireEmailAddress() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        String graphArn = service.graphArn(REGION);

        var member = service.createMember(REGION, graphArn, MEMBER_ACCOUNT, null);

        assertEquals(MEMBER_ACCOUNT, member.getAccountId());
        assertNull(member.getEmailAddress());
        assertEquals("ACCEPTED_BUT_DISABLED", member.getStatus());
    }

    @Test
    void omittedAutoEnableLeavesConfigurationUnchanged() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        String graphArn = service.graphArn(REGION);
        service.updateOrganizationConfiguration(REGION, graphArn, true);

        service.updateOrganizationConfiguration(REGION, graphArn, null);

        assertTrue(service.requireGraph(REGION).isAutoEnable());
    }

    @Test
    void startMonitoringRejectsUnsupportedIngestionWithoutMutatingMember() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        String graphArn = service.graphArn(REGION);
        service.createMember(REGION, graphArn, MEMBER_ACCOUNT, null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.startMonitoring(REGION, MEMBER_ACCOUNT, graphArn));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("does not support Detective member data ingestion"));
        assertEquals("ACCEPTED_BUT_DISABLED", service.listMembers(REGION, graphArn).getFirst().getStatus());
    }

    @Test
    void nonManagementAccountCannotDesignateAdministrator() {
        String memberCaller = MEMBER_ACCOUNT;
        Organization organization = new Organization();
        organization.setMasterAccountId(MANAGEMENT_ACCOUNT);
        when(organizationsService.describeOrganization(memberCaller)).thenReturn(organization);

        AwsException error = assertThrows(AwsException.class,
                () -> service.enableAdmin(REGION, memberCaller, MANAGEMENT_ACCOUNT));

        assertEquals("AccessDeniedException", error.getErrorCode());
        assertNull(service.state(REGION).getAdminAccountId());
    }

    @Test
    void administratorMustBelongToOrganization() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, "999999999999"));

        assertEquals("ValidationException", error.getErrorCode());
        assertNull(service.state(REGION).getAdminAccountId());
    }

    @Test
    void graphIdentityAndTagsSurviveSerializedStateReload() throws Exception {
        String arn = service.createGraph(REGION, Map.of("team", "security"));
        String createdTime = service.requireGraph(REGION).getCreatedTime();
        service.createMember(REGION, arn, MEMBER_ACCOUNT, "member@example.com");
        ObjectMapper mapper = new ObjectMapper();
        DetectiveState restored = mapper.readValue(mapper.writeValueAsBytes(service.state(REGION)), DetectiveState.class);
        AccountAwareStorageBackend<DetectiveState> storage = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        storage.put(REGION, restored);
        DetectiveService reloaded = new DetectiveService(storage, regionResolver, organizationsService,
                cloudTrailEvents, objectMapper, true);

        assertEquals(arn, reloaded.graphArn(REGION));
        assertEquals(createdTime, reloaded.requireGraph(REGION).getCreatedTime());
        assertEquals(Map.of("team", "security"), reloaded.listTags(REGION, arn));
        assertEquals(1, reloaded.listMembers(REGION, arn).size());
        when(regionResolver.getAccountId()).thenReturn(MEMBER_ACCOUNT);
        assertEquals(arn, reloaded.listInvitations(REGION).getFirst().graphArn());
        assertEquals("INVITED", reloaded.listInvitations(REGION).getFirst().member().getStatus());
        assertEquals("INVITATION", reloaded.listInvitations(REGION).getFirst().member().getInvitationType());
        assertEquals(restored.getMembers().get(MEMBER_ACCOUNT).getInvitedTime(),
                reloaded.listInvitations(REGION).getFirst().member().getInvitedTime());
        when(regionResolver.getAccountId()).thenReturn(MANAGEMENT_ACCOUNT);
        reloaded.tagResource(REGION, arn, Map.of("team", "updated"));
        assertEquals(arn, reloaded.graphArn(REGION));
        reloaded.deleteGraph(REGION, arn);
        String replacement = reloaded.createGraph(REGION, Map.of());
        assertNotEquals(arn, replacement);
        assertTrue(reloaded.listTags(REGION, replacement).isEmpty());
        assertTrue(reloaded.listMembers(REGION, replacement).isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> reloaded.deleteGraph(REGION, arn)).getErrorCode());
        assertEquals(replacement, reloaded.graphArn(REGION));
    }

    @Test
    void legacyGraphKeepsItsPreviouslyExposedArn() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DetectiveState legacy = mapper.readValue("{\"graph\":true}", DetectiveState.class);
        AccountAwareStorageBackend<DetectiveState> storage = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        storage.put(REGION, legacy);
        DetectiveService reloaded = new DetectiveService(storage, regionResolver, organizationsService,
                cloudTrailEvents, objectMapper, true);
        String arn = "arn:aws:detective:" + REGION + ":" + MANAGEMENT_ACCOUNT
                + ":graph:00000000000000000000000000000001";
        assertEquals(arn, reloaded.graphArn(REGION));
        assertEquals(arn, storage.get(REGION).orElseThrow().getGraphArn());
        assertNull(reloaded.requireGraph(REGION).getCreatedTime());
        when(cloudTrailEvents.lookup(any(), eq(REGION)))
                .thenReturn(eventPage(event("old", MANAGEMENT_ACCOUNT, REGION, Instant.parse("2020-01-01T00:00:00Z"))));
        reloaded.listDatasourcePackages(REGION, arn);
        assertEquals(arn, reloaded.graphArn(REGION));
        assertNull(reloaded.requireGraph(REGION).getCreatedTime());
        assertTrue(reloaded.requireGraph(REGION).getCoreEvents().isEmpty());
        Instant.parse(reloaded.requireGraph(REGION).getCoreCollectionStartTime());
    }

    @Test
    void delegationPreservesAnExistingGraphAndItsTags() {
        String arn = service.createGraph(REGION, Map.of("team", "security"));
        String createdTime = service.requireGraph(REGION).getCreatedTime();
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        assertEquals(arn, service.graphArn(REGION));
        assertEquals(createdTime, service.requireGraph(REGION).getCreatedTime());
        assertEquals(Map.of("team", "security"), service.listTags(REGION, arn));
        assertEquals(ADMIN_ACCOUNT, service.requireGraph(REGION).getAdminAccountId());
    }

    @Test
    void coreDatasourceCollectsScopedEventsAcrossPagesAndSurvivesReload() throws Exception {
        String arn = service.createGraph(REGION, Map.of());
        Instant created = Instant.parse(service.requireGraph(REGION).getCreatedTime());
        Instant eventTime = Instant.now();
        ObjectNode first = eventPage(
                event("first", MANAGEMENT_ACCOUNT, REGION, eventTime),
                event("before-graph", MANAGEMENT_ACCOUNT, REGION, created.minusSeconds(1)),
                event("foreign", MEMBER_ACCOUNT, REGION, eventTime),
                event("other-region", MANAGEMENT_ACCOUNT, "us-west-2", eventTime));
        first.put("NextToken", "second-page");
        ObjectNode second = eventPage(
                event("second", MANAGEMENT_ACCOUNT, REGION, eventTime),
                event("first", MANAGEMENT_ACCOUNT, REGION, eventTime),
                event("future", MANAGEMENT_ACCOUNT, REGION, eventTime.plusSeconds(60)));
        when(cloudTrailEvents.lookup(any(), eq(REGION))).thenAnswer(invocation -> {
            ObjectNode request = invocation.getArgument(0);
            assertEquals(50, request.path("MaxResults").asInt());
            assertEquals(created.toEpochMilli() / 1000.0, request.path("StartTime").asDouble());
            return request.has("NextToken") ? second : first;
        });

        ObjectNode response = service.listDatasourcePackages(REGION, arn);
        assertEquals("STARTED", response.at("/DatasourcePackages/DETECTIVE_CORE/DatasourcePackageIngestState").asText());
        Instant.parse(response.at("/DatasourcePackages/DETECTIVE_CORE/LastIngestStateChange/STARTED/Timestamp").asText());
        assertEquals(Map.of("first", event("first", MANAGEMENT_ACCOUNT, REGION, eventTime).toString(),
                "second", event("second", MANAGEMENT_ACCOUNT, REGION, eventTime).toString()),
                service.requireGraph(REGION).getCoreEvents());
        assertEquals(response, service.listDatasourcePackages(REGION, arn));

        DetectiveState restored = objectMapper.readValue(
                objectMapper.writeValueAsBytes(service.requireGraph(REGION)), DetectiveState.class);
        AccountAwareStorageBackend<DetectiveState> reloadedStorage = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        reloadedStorage.put(REGION, restored);
        DetectiveService reloaded = new DetectiveService(reloadedStorage, regionResolver, organizationsService,
                cloudTrailEvents, objectMapper, true);
        assertEquals(response, reloaded.listDatasourcePackages(REGION, arn));
        assertEquals(2, reloaded.requireGraph(REGION).getCoreEvents().size());
        assertEquals(service.requireGraph(REGION).getCoreCollectionStartTime(), restored.getCoreCollectionStartTime());
        reloaded.deleteGraph(REGION, arn);
        String replacement = reloaded.createGraph(REGION, Map.of());
        doReturn(second).when(cloudTrailEvents).lookup(any(), eq(REGION));
        reloaded.listDatasourcePackages(REGION, replacement);
        assertTrue(reloaded.requireGraph(REGION).getCoreEvents().isEmpty());
        assertEquals(1, reloaded.requireGraph(REGION).getCoreIngestStateChanges().size());
    }

    @Test
    void datasourceCollectionRequiresOwnedExistingGraphBeforeReadingEvents() {
        String missing = "arn:aws:detective:" + REGION + ":" + MANAGEMENT_ACCOUNT
                + ":graph:ffffffffffffffffffffffffffffffff";
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listDatasourcePackages(REGION, missing)).getErrorCode());
        String arn = service.createGraph(REGION, Map.of());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listDatasourcePackages("us-west-2", arn)).getErrorCode());
        when(regionResolver.getAccountId()).thenReturn(MEMBER_ACCOUNT);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listDatasourcePackages(REGION, arn)).getErrorCode());
        verifyNoInteractions(cloudTrailEvents);
    }

    @Test
    void disabledCloudTrailCannotClaimStartedIngestion() {
        String arn = service.createGraph(REGION, Map.of());
        DetectiveService disabled = new DetectiveService(storage, regionResolver, organizationsService,
                cloudTrailEvents, objectMapper, false);
        ObjectNode response = disabled.listDatasourcePackages(REGION, arn);
        assertEquals("DISABLED", response.at("/DatasourcePackages/DETECTIVE_CORE/DatasourcePackageIngestState").asText());
        assertEquals(1, response.at("/DatasourcePackages/DETECTIVE_CORE/LastIngestStateChange").size());
        assertTrue(disabled.requireGraph(REGION).getCoreEvents().isEmpty());
        assertEquals(response, disabled.listDatasourcePackages(REGION, arn));
        verifyNoInteractions(cloudTrailEvents);

        when(cloudTrailEvents.lookup(any(), eq(REGION))).thenReturn(eventPage());
        ObjectNode started = service.listDatasourcePackages(REGION, arn);
        assertEquals("STARTED", started.at("/DatasourcePackages/DETECTIVE_CORE/DatasourcePackageIngestState").asText());
        assertEquals(response.at("/DatasourcePackages/DETECTIVE_CORE/LastIngestStateChange/DISABLED"),
                started.at("/DatasourcePackages/DETECTIVE_CORE/LastIngestStateChange/DISABLED"));
        assertEquals(2, started.at("/DatasourcePackages/DETECTIVE_CORE/LastIngestStateChange").size());
    }

    @Test
    void failedCollectionDoesNotCommitPartialEventsOrSuccessfulStatus() {
        String arn = service.createGraph(REGION, Map.of());
        ObjectNode first = eventPage(event("first", MANAGEMENT_ACCOUNT, REGION, Instant.now()));
        first.put("NextToken", "next");
        when(cloudTrailEvents.lookup(any(), eq(REGION))).thenReturn(first)
                .thenThrow(new AwsException("InvalidNextTokenException", "Expired cursor.", 400));
        assertEquals("InternalServerException", assertThrows(AwsException.class,
                () -> service.listDatasourcePackages(REGION, arn)).getErrorCode());
        assertTrue(service.requireGraph(REGION).getCoreEvents().isEmpty());
        assertTrue(service.requireGraph(REGION).getCoreIngestStateChanges().isEmpty());
        assertNull(service.requireGraph(REGION).getCoreIngestState());

        doReturn(first).when(cloudTrailEvents).lookup(any(), eq(REGION));
        assertEquals("InternalServerException", assertThrows(AwsException.class,
                () -> service.listDatasourcePackages(REGION, arn)).getErrorCode());
        assertTrue(service.requireGraph(REGION).getCoreEvents().isEmpty());
    }

    private ObjectNode event(String id, String account, String region, Instant time) {
        return objectMapper.createObjectNode().put("eventID", id).put("recipientAccountId", account)
                .put("awsRegion", region).put("eventCategory", "Management").put("eventTime", time.toString())
                .put("eventSource", "ssm.amazonaws.com").put("eventName", "PutParameter");
    }

    private ObjectNode eventPage(ObjectNode... events) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Events");
        for (ObjectNode event : events) {
            response.withArray("Events").addObject().put("EventId", event.path("eventID").asText())
                    .put("CloudTrailEvent", event.toString());
        }
        return response;
    }

    @Test
    void clearRemovesAllDetectiveState() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        assertEquals(ADMIN_ACCOUNT, service.state(REGION).getAdminAccountId());

        service.clear();

        assertNull(service.state(REGION).getAdminAccountId());
        assertFalse(service.state(REGION).isGraph());
    }
}

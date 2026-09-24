package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.macie2.model.MacieMember;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MacieServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";

    private MacieService service;

    @BeforeEach
    void setUp() {
        service = new MacieService(
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT));
    }

    @Test
    void resourceCreationRequiresAnEnabledSessionWithAccessDenied() {
        for (String kind : List.of("allow-list", "custom-data-identifier", "findings-filter")) {
            AwsException error = assertThrows(AwsException.class, () -> service.createResource(
                    REGION, MANAGEMENT_ACCOUNT, kind, new ObjectMapper().createObjectNode()));
            assertEquals("AccessDeniedException", error.getErrorCode());
            assertEquals(403, error.getHttpStatus());
            assertTrue(error.getMessage().contains("Macie is not enabled"));
        }
    }

    @Test
    void delegationEnablesMacieForAdministratorAccount() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        MacieState delegated = service.requireAdministratorSession(REGION, ADMIN_ACCOUNT);
        assertTrue(delegated.isEnabled());
        assertEquals(ADMIN_ACCOUNT, delegated.getAdminAccountId());
    }

    @Test
    void managementAccountCannotUpdateAdministratorConfiguration() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        service.enableMacie(REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, MANAGEMENT_ACCOUNT, true));
        assertEquals("AccessDeniedException", error.getErrorCode());
    }

    @Test
    void delegatedAdministratorCanUpdateConfiguration() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT, true);

        assertTrue(service.requireAdministratorSession(REGION, ADMIN_ACCOUNT).isAutoEnable());
    }

    @Test
    void conflictingAdministratorDesignationIsRejected() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        AwsException error = assertThrows(AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, "333333333333"));
        assertEquals("ConflictException", error.getErrorCode());
    }


    @Test
    void delegatedAdministratorCreatesEnabledMember() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        MacieMember member = service.createMember(
                REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of("team", "security"));

        assertEquals("Enabled", member.relationshipStatus());
        assertEquals("arn:aws:macie2:us-east-1:111111111111:member/333333333333", member.arn());
        assertEquals("security", member.tags().get("team"));
        List<MacieMember> members = service.listMembers(REGION, ADMIN_ACCOUNT, null, null, null).items();
        assertEquals(1, members.size());
        assertEquals("333333333333", members.getFirst().accountId());
    }

    @Test
    void standaloneAdministratorCreatesAssociationExcludedFromDefaultMemberList() {
        service.enableMacie(REGION);

        service.createMember(
                REGION, MANAGEMENT_ACCOUNT, "333333333333", "member@example.com", Map.of());

        assertTrue(service.listMembers(REGION, MANAGEMENT_ACCOUNT, null, null, null).items().isEmpty());
        List<MacieMember> all = service.listMembers(
                REGION, MANAGEMENT_ACCOUNT, null, null, "false").items();
        assertEquals(1, all.size());
        assertEquals("Created", all.getFirst().relationshipStatus());
    }

    @Test
    void duplicateMemberAssociationIsConflict() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.createMember(
                        REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of()));

        assertEquals("ConflictException", error.getErrorCode());
    }

    @Test
    void memberCannotBeAssociatedWithDifferentAdministrator() {
        AccountAwareStorageBackend<MacieState> states = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        AccountAwareStorageBackend<MacieMember> members = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        MacieService localService = new MacieService(states, members);

        localService.enableMacie(REGION);
        localService.createMember(
                REGION, MANAGEMENT_ACCOUNT, "333333333333", "member@example.com", Map.of());

        MacieState secondAdmin = new MacieState();
        secondAdmin.setEnabled(true);
        secondAdmin.setAdminAccountId(ADMIN_ACCOUNT);
        states.putForAccount(ADMIN_ACCOUNT, REGION, secondAdmin);

        AwsException error = assertThrows(AwsException.class,
                () -> localService.createMember(
                        REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of()));

        assertEquals("ConflictException", error.getErrorCode());
    }

    @Test
    void listMembersPaginatesAndValidatesNextToken() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333331", "one@example.com", Map.of());
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333332", "two@example.com", Map.of());
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333333", "three@example.com", Map.of());

        MacieService.Page<MacieMember> first = service.listMembers(
                REGION, ADMIN_ACCOUNT, "2", null, null);
        assertEquals(List.of("333333333331", "333333333332"),
                first.items().stream().map(MacieMember::accountId).toList());
        assertTrue(first.nextToken() != null && !first.nextToken().isBlank());

        MacieService.Page<MacieMember> second = service.listMembers(
                REGION, ADMIN_ACCOUNT, "2", first.nextToken(), null);
        assertEquals(List.of("333333333333"), second.items().stream().map(MacieMember::accountId).toList());
        assertNull(second.nextToken());

        AwsException error = assertThrows(AwsException.class,
                () -> service.listMembers(REGION, ADMIN_ACCOUNT, "2", "not-base64", null));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void listMembersDefaultsAndCapsMaxResultsAtTwentyFive() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        for (int i = 0; i < 26; i++) {
            String accountId = String.format("333333333%03d", i);
            service.createMember(REGION, ADMIN_ACCOUNT, accountId, "member" + i + "@example.com", Map.of());
        }

        MacieService.Page<MacieMember> defaultPage = service.listMembers(
                REGION, ADMIN_ACCOUNT, null, null, null);
        assertEquals(25, defaultPage.items().size());
        assertTrue(defaultPage.nextToken() != null && !defaultPage.nextToken().isBlank());

        AwsException error = assertThrows(AwsException.class,
                () -> service.listMembers(REGION, ADMIN_ACCOUNT, "26", null, null));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void createMemberValidatesAccountEmailAndTagQuota() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createMember(REGION, ADMIN_ACCOUNT, "bad", "member@example.com", Map.of()))
                .getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createMember(REGION, ADMIN_ACCOUNT, "333333333333", "not-an-email", Map.of()))
                .getErrorCode());
        Map<String, String> tooManyTags = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) {
            tooManyTags.put("k" + i, "v");
        }
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createMember(
                        REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", tooManyTags))
                .getErrorCode());
    }

    @Test
    void sessionMetadataSurvivesSerializedStateReload() throws Exception {
        service.enableMacie(REGION, "PAUSED", "ONE_HOUR");
        MacieState session = service.requireSession(REGION);
        String createdAt = session.getCreatedAt();
        ObjectMapper mapper = new ObjectMapper();
        MacieState restored = mapper.readValue(mapper.writeValueAsBytes(session), MacieState.class);
        AccountAwareStorageBackend<MacieState> states = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        states.put(REGION, restored);
        MacieService reloaded = new MacieService(states, AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT));

        assertEquals("PAUSED", reloaded.requireSession(REGION).getStatus());
        assertEquals("ONE_HOUR", reloaded.requireSession(REGION).getFindingPublishingFrequency());
        assertEquals(createdAt, reloaded.requireSession(REGION).getCreatedAt());
        assertEquals(session.getUpdatedAt(), reloaded.requireSession(REGION).getUpdatedAt());
        reloaded.updateMacieSession(REGION, "ENABLED", null);
        assertEquals(createdAt, reloaded.requireSession(REGION).getCreatedAt());
        assertEquals("ONE_HOUR", reloaded.requireSession(REGION).getFindingPublishingFrequency());
        reloaded.disableMacie(REGION, MANAGEMENT_ACCOUNT);
        assertFalse(reloaded.state(REGION).isEnabled());
        assertNull(reloaded.state(REGION).getCreatedAt());
        assertNull(reloaded.state(REGION).getUpdatedAt());
        reloaded.enableMacie(REGION);
        assertEquals("ENABLED", reloaded.requireSession(REGION).getStatus());
        assertEquals("SIX_HOURS", reloaded.requireSession(REGION).getFindingPublishingFrequency());
    }

    @Test
    void delegationPreservesAnExistingPausedSession() {
        service.enableMacie(REGION, "PAUSED", "ONE_HOUR");
        String createdAt = service.requireSession(REGION).getCreatedAt();
        service.enableOrganizationAdminAccount(REGION, MANAGEMENT_ACCOUNT);
        assertEquals("PAUSED", service.requireSession(REGION).getStatus());
        assertEquals("ONE_HOUR", service.requireSession(REGION).getFindingPublishingFrequency());
        assertEquals(createdAt, service.requireSession(REGION).getCreatedAt());
        service.updateOrganizationConfiguration(REGION, MANAGEMENT_ACCOUNT, true);
        assertTrue(service.requireSession(REGION).isAutoEnable());
    }

    @Test
    void membershipQueriesUseStoredAccountAndRegionRelationships() {
        AccountAwareStorageBackend<MacieState> states = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        AccountAwareStorageBackend<MacieMember> members = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        MacieService stored = new MacieService(states, members);
        stored.enableMacie(REGION);
        String now = "2026-09-21T00:00:00Z";
        MacieMember invitation = new MacieMember(MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT, ADMIN_ACCOUNT,
                "arn:aws:macie2:" + REGION + ":" + ADMIN_ACCOUNT + ":member/" + MANAGEMENT_ACCOUNT,
                "member@example.com", now, "Invited", Map.of(), now);
        members.putForAccount(ADMIN_ACCOUNT, REGION + "::" + MANAGEMENT_ACCOUNT, invitation);
        assertEquals(1, stored.invitationsCount(REGION, MANAGEMENT_ACCOUNT));
        assertEquals(List.of(invitation), stored.listInvitations(REGION, MANAGEMENT_ACCOUNT, "1", null).items());
        assertTrue(stored.administrator(REGION, MANAGEMENT_ACCOUNT).isEmpty());
        stored.enableMacie("us-west-2");
        assertEquals(0, stored.invitationsCount("us-west-2", MANAGEMENT_ACCOUNT));
        MacieState other = new MacieState();
        other.setEnabled(true);
        states.putForAccount("333333333333", REGION, other);
        assertEquals(0, stored.invitationsCount(REGION, "333333333333"));
        assertTrue(stored.administrator(REGION, "333333333333").isEmpty());
        MacieMember accepted = new MacieMember(invitation.accountId(), invitation.administratorAccountId(),
                invitation.masterAccountId(), invitation.arn(), invitation.email(), now, "Enabled", Map.of(), now);
        members.putForAccount(ADMIN_ACCOUNT, REGION + "::" + MANAGEMENT_ACCOUNT, accepted);
        assertEquals(0, stored.invitationsCount(REGION, MANAGEMENT_ACCOUNT));
        assertEquals(accepted, stored.administrator(REGION, MANAGEMENT_ACCOUNT).orElseThrow());
    }

    @Test
    void identifierKeywordDistanceUsesCharactersAndIncludesOverlappingKeywords() {
        service.enableMacie(REGION);
        assertEquals(1, service.testCustomDataIdentifier(REGION, "ID[0-9]", "ababa ID1",
                List.of("aba"), List.of(), 4));
        assertEquals(1, service.testCustomDataIdentifier(REGION, "ID[0-9]", "key😀ID1",
                List.of("key"), List.of(), 4));
        assertEquals(0, service.testCustomDataIdentifier(REGION, "ID[0-9]", "key😀ID1",
                List.of("key"), List.of(), 3));
        assertEquals(1, service.testCustomDataIdentifier(REGION, "(?:EMP|ID)-[0-9]+", "ID-12",
                List.of(), List.of(), null));
        assertEquals(1, service.testCustomDataIdentifier(REGION, "(?i)emp-[0-9]+", "EMP-12",
                List.of(), List.of(), null));
    }

    @Test
    void identifierRejectsMacieUnsupportedRegexConstructs() {
        service.enableMacie(REGION);
        for (String regex : List.of("(a)", "(?=a)a", "(?<=a)b", "\\1", "](?=a)a")) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.testCustomDataIdentifier(REGION, regex, "aab", List.of(), List.of(), null))
                    .getErrorCode());
        }
    }

    @Test
    void resourceStateAndIdempotencySurviveSerializationWithoutResurrectingDeletedIdentifiers() throws Exception {
        service.enableMacie(REGION);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("""
                {"name":"Employees","regex":"EMP-[0-9]{8}","clientToken":"employees",
                 "tags":{"env":"test"}}
                """);
        ObjectNode created = service.createResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", request);
        String id = created.path("customDataIdentifierId").asText();
        assertEquals(created, service.createResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", request));
        JsonNode changed = mapper.readTree("""
                {"name":"Employees","regex":"EMP-[0-9]{9}","clientToken":"employees"}
                """);
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.createResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", changed)).getErrorCode());
        String arn = service.getResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", id).path("arn").asText();
        service.changeTags(REGION, MANAGEMENT_ACCOUNT, arn, Map.of("phase", "two"), List.of("env"));
        service.configuration(REGION, MANAGEMENT_ACCOUNT, "discovery");
        JsonNode export = mapper.readTree("""
                {"configuration":{"s3Destination":{"bucketName":"exports","kmsKeyArn":"arn:aws:kms:us-east-1:222222222222:key/export"}}}
                """);
        service.putExportConfiguration(REGION, MANAGEMENT_ACCOUNT, export);
        service.createSampleFindings(REGION, MANAGEMENT_ACCOUNT, mapper.createObjectNode());
        service.deleteResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", id);
        MacieState restored = mapper.readValue(mapper.writeValueAsBytes(service.requireSession(REGION)), MacieState.class);
        AccountAwareStorageBackend<MacieState> states = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        states.put(REGION, restored);
        MacieService reloaded = new MacieService(states, AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT));
        assertTrue(reloaded.getResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", id).path("deleted").asBoolean());
        assertEquals("two", reloaded.getResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", id)
                .path("tags").path("phase").asText());
        assertEquals(0, reloaded.listResources(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", "items", null, null)
                .path("items").size());
        assertEquals(created, reloaded.createResource(REGION, MANAGEMENT_ACCOUNT, "custom-data-identifier", request));
        assertEquals(export, reloaded.configuration(REGION, MANAGEMENT_ACCOUNT, "export"));
        assertEquals(1, reloaded.listFindings(REGION, MANAGEMENT_ACCOUNT, mapper.createObjectNode()).path("findingIds").size());
        assertEquals(service.configuration(REGION, MANAGEMENT_ACCOUNT, "scope"),
                reloaded.configuration(REGION, MANAGEMENT_ACCOUNT, "scope"));
        reloaded.disableMacie(REGION, MANAGEMENT_ACCOUNT);
        reloaded.enableMacie(REGION);
        assertEquals(0, reloaded.listFindings(REGION, MANAGEMENT_ACCOUNT, mapper.createObjectNode()).path("findingIds").size());
        assertTrue(reloaded.requireSession(REGION).getCreateRequests().isEmpty());
    }

    @Test
    void listsPaginateAndRejectedUpdatesLeaveStoredDefinitionsUnchanged() throws Exception {
        service.enableMacie(REGION);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("""
                {"name":"Tickets","criteria":{"regex":"TICKET-[0-9]{6}"}}
                """);
        String id = service.createResource(REGION, MANAGEMENT_ACCOUNT, "allow-list", request).path("id").asText();
        service.createResource(REGION, MANAGEMENT_ACCOUNT, "allow-list",
                mapper.readTree("""
                        {"name":"OtherTickets","criteria":{"regex":"OTHER-[0-9]{6}"}}
                        """));
        ObjectNode first = service.listResources(REGION, MANAGEMENT_ACCOUNT, "allow-list", "allowLists", "1", null);
        assertEquals(1, first.path("allowLists").size());
        ObjectNode second = service.listResources(REGION, MANAGEMENT_ACCOUNT, "allow-list", "allowLists", "1",
                first.path("nextToken").asText());
        assertEquals(1, second.path("allowLists").size());
        assertFalse(second.has("nextToken"));
        assertFalse(first.path("allowLists").equals(second.path("allowLists")));
        assertThrows(AwsException.class, () -> service.listResources(REGION, MANAGEMENT_ACCOUNT,
                "allow-list", "allowLists", "1", "invalid"));
        JsonNode invalid = mapper.readTree("""
                {"name":"changed","criteria":{"regex":"["}}
                """);
        assertThrows(AwsException.class, () -> service.updateResource(REGION, MANAGEMENT_ACCOUNT, "allow-list", id, invalid));
        assertEquals("Tickets", service.getResource(REGION, MANAGEMENT_ACCOUNT, "allow-list", id).path("name").asText());
        assertThrows(AwsException.class, () -> service.createResource(REGION, MANAGEMENT_ACCOUNT, "allow-list",
                mapper.readTree("""
                        {"name":"words","criteria":{"s3WordsList":{"bucketName":"input","objectKey":"words.txt"}}}
                        """)));
        assertEquals(2, service.listResources(REGION, MANAGEMENT_ACCOUNT, "allow-list", "allowLists", null, null)
                .path("allowLists").size());
    }

    @Test
    void inventoryUsesStoredS3ObjectCountsAndFiltersWithoutInventingClassification() throws Exception {
        S3Service s3 = mock(S3Service.class);
        MacieService inventory = new MacieService(AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT), s3);
        inventory.enableMacie(REGION);
        Bucket east = new Bucket("east-data");
        east.setRegion(REGION);
        Bucket west = new Bucket("west-data");
        west.setRegion("us-west-2");
        S3Object object = new S3Object();
        object.setSize(123);
        when(s3.listBuckets()).thenReturn(List.of(east, west));
        when(s3.listObjects("east-data", null, null, Integer.MAX_VALUE)).thenReturn(List.of(object));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode stats = inventory.bucketStatistics(REGION, MANAGEMENT_ACCOUNT, mapper.createObjectNode());
        assertEquals(1, stats.path("bucketCount").asInt());
        assertEquals(1, stats.path("objectCount").asInt());
        assertEquals(123, stats.path("sizeInBytes").asLong());
        ObjectNode results = inventory.searchResources(REGION, MANAGEMENT_ACCOUNT, mapper.createObjectNode());
        assertEquals("east-data", results.path("matchingResources").get(0).path("matchingBucket").path("bucketName").asText());
        assertFalse(results.path("matchingResources").get(0).path("matchingBucket").has("sensitivityScore"));
        JsonNode excluded = mapper.readTree("""
                {"bucketCriteria":{"excludes":{"and":[{"simpleCriterion":{"key":"S3_BUCKET_NAME","comparator":"EQ","values":["east-data"]}}]}}}
                """);
        assertEquals(0, inventory.searchResources(REGION, MANAGEMENT_ACCOUNT, excluded).path("matchingResources").size());
    }

    @Test
    void clearRemovesMacieState() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        assertTrue(service.requireAdministratorSession(REGION, ADMIN_ACCOUNT).isEnabled());

        service.clear();

        assertFalse(service.state(REGION).isEnabled());
        AwsException error = assertThrows(AwsException.class,
                () -> service.requireAdministratorSession(REGION, ADMIN_ACCOUNT));
        assertEquals("AccessDeniedException", error.getErrorCode());
    }
}

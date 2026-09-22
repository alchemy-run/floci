package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.inspector2.model.InspectorFilter;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Inspector2ServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";
    private static final String MEMBER_ACCOUNT = "333333333333";
    private static final String OUTSIDE_ACCOUNT = "444444444444";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Inspector2Service service;

    @BeforeEach
    void setUp() {
        OrganizationsService organizations = mock(OrganizationsService.class);
        when(organizations.findManagementAccountForResource(MANAGEMENT_ACCOUNT))
                .thenReturn(Optional.of(MANAGEMENT_ACCOUNT));
        when(organizations.findManagementAccountForResource(ADMIN_ACCOUNT))
                .thenReturn(Optional.of(MANAGEMENT_ACCOUNT));
        when(organizations.findManagementAccountForResource(MEMBER_ACCOUNT))
                .thenReturn(Optional.of(MANAGEMENT_ACCOUNT));
        when(organizations.findManagementAccountForResource(OUTSIDE_ACCOUNT))
                .thenReturn(Optional.of(OUTSIDE_ACCOUNT));
        service = new Inspector2Service(
                AccountAwareStorageBackend.<InspectorState>inMemory(MANAGEMENT_ACCOUNT), organizations);
    }

    @Test
    void onlyManagementAccountCanDesignateDelegatedAdministrator() {
        AwsException denied = assertThrows(AwsException.class,
                () -> service.enableDelegatedAdmin(REGION, MEMBER_ACCOUNT, ADMIN_ACCOUNT));
        assertEquals("AccessDeniedException", denied.getErrorCode());

        AwsException outsider = assertThrows(AwsException.class,
                () -> service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, OUTSIDE_ACCOUNT));
        assertEquals("ResourceNotFoundException", outsider.getErrorCode());
    }

    @Test
    void delegatedAdministratorOwnsOrganizationConfiguration() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        InspectorState state = service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT,
                objectMapper.readTree("{\"autoEnable\":{\"ec2\":true,\"ecr\":true,\"codeRepository\":true}}"));

        assertTrue(state.isAutoEnableEc2());
        assertTrue(state.isAutoEnableEcr());
        assertTrue(state.isAutoEnableCodeRepository());
    }

    @Test
    void failedOrganizationConfigurationUpdateIsAtomic() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT,
                objectMapper.readTree("{\"autoEnable\":{\"ec2\":false,\"ecr\":false}}"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT,
                        objectMapper.readTree("{\"autoEnable\":{\"ec2\":true}}")));

        assertEquals("ValidationException", error.getErrorCode());
        InspectorState current = service.organizationConfiguration(REGION, ADMIN_ACCOUNT);
        assertFalse(current.isAutoEnableEc2());
        assertFalse(current.isAutoEnableEcr());
    }

    @Test
    void delegatedAdministratorEnablesMemberAndOnlyRequestedResourceTypes() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        service.enable(REGION, ADMIN_ACCOUNT, objectMapper.readTree(
                "{\"accountIds\":[\"" + MEMBER_ACCOUNT + "\"],\"resourceTypes\":[\"EC2\"]}"));

        InspectorState first = service.accountStatus(REGION, ADMIN_ACCOUNT, MEMBER_ACCOUNT);
        assertEquals("ENABLING", first.getStatus());
        assertEquals("ENABLING", first.getEc2Status());
        assertEquals("DISABLED", first.getEcrStatus());
        assertEquals("DISABLED", first.getLambdaStatus());
        InspectorState converged = service.accountStatus(REGION, ADMIN_ACCOUNT, MEMBER_ACCOUNT);
        assertEquals("ENABLED", converged.getStatus());
        assertEquals("ENABLED", converged.getEc2Status());
        assertEquals("DISABLED", converged.getEcrStatus());
    }

    @Test
    void memberMayEnableItselfButCannotManageAnotherAccount() throws Exception {
        service.enable(REGION, MEMBER_ACCOUNT, objectMapper.readTree(
                "{\"resourceTypes\":[\"ECR\"]}"));
        assertEquals("ENABLING", service.accountStatus(REGION, MEMBER_ACCOUNT, MEMBER_ACCOUNT).getEcrStatus());

        AwsException denied = assertThrows(AwsException.class,
                () -> service.enable(REGION, MEMBER_ACCOUNT, objectMapper.readTree(
                        "{\"accountIds\":[\"" + ADMIN_ACCOUNT + "\"],\"resourceTypes\":[\"EC2\"]}")));
        assertEquals("AccessDeniedException", denied.getErrorCode());
    }

    @Test
    void managementAccountCannotUpdateOrganizationConfiguration() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, MANAGEMENT_ACCOUNT,
                        objectMapper.readTree("{\"autoEnable\":{\"ec2\":true,\"ecr\":true}}")));

        assertEquals("AccessDeniedException", error.getErrorCode());
    }

    @Test
    void disableRequiresManagementAndCurrentAdministrator() {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        AwsException denied = assertThrows(AwsException.class,
                () -> service.disableDelegatedAdmin(REGION, ADMIN_ACCOUNT, ADMIN_ACCOUNT));
        assertEquals("AccessDeniedException", denied.getErrorCode());

        AwsException missing = assertThrows(AwsException.class,
                () -> service.disableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, MEMBER_ACCOUNT));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void filterLifecycleWorksWithoutInspectorEnablement() throws Exception {
        JsonNode create = filterRequest("informational");
        String arn = service.createFilter(REGION, MEMBER_ACCOUNT, create);
        InspectorFilter original = service.listFilters(REGION, MEMBER_ACCOUNT,
                objectMapper.readTree("{\"arns\":[\"" + arn + "\"]}")).items().getFirst();
        assertEquals("arn:aws:inspector2:" + REGION + ":" + MEMBER_ACCOUNT + ":owner/" + MEMBER_ACCOUNT
                + "/filter/", arn.substring(0, arn.lastIndexOf('/') + 1));
        assertEquals(MEMBER_ACCOUNT, original.getOwnerId());
        assertEquals("SUPPRESS", original.getAction());
        assertEquals(create.get("filterCriteria"), original.getCriteria());
        assertEquals("test", original.getTags().get("env"));
        assertTrue(original.getCreatedAt() > 0);
        assertEquals(original.getCreatedAt(), original.getUpdatedAt());

        assertEquals(arn, service.updateFilter(REGION, MEMBER_ACCOUNT,
                objectMapper.createObjectNode().put("filterArn", arn).put("name", "saved-view")
                        .put("action", "NONE").put("reason", "Keep visible")));
        InspectorFilter updated = service.listFilters(REGION, MEMBER_ACCOUNT,
                objectMapper.createObjectNode().put("action", "NONE")).items().getFirst();
        assertEquals(arn, updated.getArn());
        assertEquals("saved-view", updated.getName());
        assertEquals("Keep visible", updated.getReason());
        assertEquals(original.getCriteria(), updated.getCriteria());
        assertEquals(original.getCreatedAt(), updated.getCreatedAt());
        assertTrue(updated.getUpdatedAt() >= original.getUpdatedAt());
        JsonNode changedCriteria = objectMapper.readTree("""
                {"filterArn":"%s","description":"Updated description","filterCriteria":{
                  "severity":[{"comparison":"EQUALS","value":"HIGH"}]}}
                """.formatted(arn));
        service.updateFilter(REGION, MEMBER_ACCOUNT, changedCriteria);
        InspectorFilter changed = service.listFilters(REGION, MEMBER_ACCOUNT,
                objectMapper.createObjectNode()).items().getFirst();
        assertEquals(changedCriteria.get("filterCriteria"), changed.getCriteria());
        assertEquals("Updated description", changed.getDescription());
        assertEquals("NONE", changed.getAction());
        assertEquals("Keep visible", changed.getReason());

        JsonNode deletion = objectMapper.createObjectNode().put("arn", arn);
        assertEquals(arn, service.deleteFilter(REGION, MEMBER_ACCOUNT, deletion));
        assertTrue(service.listFilters(REGION, MEMBER_ACCOUNT, objectMapper.createObjectNode()).items().isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteFilter(REGION, MEMBER_ACCOUNT, deletion)).getErrorCode());
    }

    @Test
    void filterNamesAndDataAreIsolatedByAccountAndRegion() throws Exception {
        String arn = service.createFilter(REGION, MEMBER_ACCOUNT, filterRequest("shared-name"));
        service.createFilter(REGION, OUTSIDE_ACCOUNT, filterRequest("shared-name"));
        service.createFilter("us-west-2", MEMBER_ACCOUNT, filterRequest("shared-name"));
        JsonNode byArn = objectMapper.readTree("{\"arns\":[\"" + arn + "\"]}");
        assertTrue(service.listFilters(REGION, OUTSIDE_ACCOUNT, byArn).items().isEmpty());
        assertTrue(service.listFilters("us-west-2", MEMBER_ACCOUNT, byArn).items().isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.updateFilter(REGION, OUTSIDE_ACCOUNT,
                        objectMapper.createObjectNode().put("filterArn", arn).put("action", "NONE"))).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteFilter("us-west-2", MEMBER_ACCOUNT,
                        objectMapper.createObjectNode().put("arn", arn))).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.tagFilter(REGION, OUTSIDE_ACCOUNT, arn, Map.of("env", "other"))).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listFilterTags("us-west-2", MEMBER_ACCOUNT, arn)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.untagFilter(REGION, OUTSIDE_ACCOUNT, arn, List.of("env"))).getErrorCode());
        assertEquals("test", service.listFilterTags(REGION, MEMBER_ACCOUNT, arn).get("env"));
    }

    @Test
    void duplicateNameAndInvalidUpdatesDoNotMutateFilters() throws Exception {
        String arn = service.createFilter(REGION, MEMBER_ACCOUNT, filterRequest("first"));
        service.createFilter(REGION, MEMBER_ACCOUNT, filterRequest("second"));
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.createFilter(REGION, MEMBER_ACCOUNT, filterRequest("first"))).getErrorCode());
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.updateFilter(REGION, MEMBER_ACCOUNT,
                        objectMapper.createObjectNode().put("filterArn", arn).put("name", "second"))).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.updateFilter(REGION, MEMBER_ACCOUNT,
                        objectMapper.createObjectNode().put("filterArn", arn).put("name", "changed")
                                .put("action", "INVALID"))).getErrorCode());
        InspectorFilter filter = service.listFilters(REGION, MEMBER_ACCOUNT,
                objectMapper.readTree("{\"arns\":[\"" + arn + "\"]}")).items().getFirst();
        assertEquals("first", filter.getName());
        assertEquals("SUPPRESS", filter.getAction());
    }

    @Test
    void tagsMergeRemoveAndRemainVisibleInFilterLists() throws Exception {
        String arn = service.createFilter(REGION, MEMBER_ACCOUNT, filterRequest("tagged"));
        service.tagFilter(REGION, MEMBER_ACCOUNT, arn, Map.of("env", "updated", "alchemy::id", "Filter"));
        service.untagFilter(REGION, MEMBER_ACCOUNT, arn, List.of("env", "absent"));
        Map<String, String> expected = Map.of("alchemy::id", "Filter");
        assertEquals(expected, service.listFilterTags(REGION, MEMBER_ACCOUNT, arn));
        InspectorFilter filter = service.listFilters(REGION, MEMBER_ACCOUNT,
                objectMapper.createObjectNode()).items().getFirst();
        assertEquals(expected, filter.getTags());
        filter.getTags().clear();
        assertEquals(expected, service.listFilterTags(REGION, MEMBER_ACCOUNT, arn));
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.tagFilter(REGION, MEMBER_ACCOUNT, arn, Map.of("env", "new", "", "invalid")))
                .getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.untagFilter(REGION, MEMBER_ACCOUNT, arn, List.of("alchemy::id", "")))
                .getErrorCode());
        assertEquals(expected, service.listFilterTags(REGION, MEMBER_ACCOUNT, arn));
    }

    @Test
    void filterListsPaginateAndValidateRequests() throws Exception {
        service.createFilter(REGION, MEMBER_ACCOUNT, filterRequest("one"));
        service.createFilter(REGION, MEMBER_ACCOUNT, filterRequest("two"));
        PaginatedResult<InspectorFilter> first = service.listFilters(REGION, MEMBER_ACCOUNT,
                objectMapper.createObjectNode().put("maxResults", 1));
        PaginatedResult<InspectorFilter> second = service.listFilters(REGION, MEMBER_ACCOUNT,
                objectMapper.createObjectNode().put("maxResults", 1).put("nextToken", first.nextToken()));
        assertEquals(1, first.items().size());
        assertEquals(1, second.items().size());
        assertFalse(first.items().getFirst().getArn().equals(second.items().getFirst().getArn()));
        assertNull(second.nextToken());
        for (String body : List.of("{\"maxResults\":0}", "{\"maxResults\":101}", "{\"maxResults\":1.5}",
                "{\"nextToken\":\"%invalid\"}", "{\"arns\":\"not-a-list\"}", "{\"action\":\"bad\"}")) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.listFilters(REGION, MEMBER_ACCOUNT, objectMapper.readTree(body))).getErrorCode());
        }
        for (String body : List.of("{}", "null", "[]", "{\"name\":\"missing-criteria\",\"action\":\"NONE\"}")) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.createFilter(REGION, MEMBER_ACCOUNT, objectMapper.readTree(body))).getErrorCode());
        }
        assertEquals(2, service.listFilters(REGION, MEMBER_ACCOUNT, objectMapper.createObjectNode()).items().size());
    }

    @Test
    void filterStateSurvivesSerializationAndClearRemovesIt() throws Exception {
        service.createFilter(REGION, MANAGEMENT_ACCOUNT, filterRequest("persistent"));
        InspectorState restored = objectMapper.readValue(objectMapper.writeValueAsString(service.state(REGION)),
                InspectorState.class);
        AccountAwareStorageBackend<InspectorState> storage = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        storage.putForAccount(MANAGEMENT_ACCOUNT, REGION, restored);
        Inspector2Service reloaded = new Inspector2Service(storage, mock(OrganizationsService.class));
        InspectorFilter filter = reloaded.listFilters(REGION, MANAGEMENT_ACCOUNT,
                objectMapper.createObjectNode()).items().getFirst();
        assertEquals("persistent", filter.getName());
        assertEquals(filterRequest("persistent").get("filterCriteria"), filter.getCriteria());
        assertEquals("test", filter.getTags().get("env"));
        reloaded.clear();
        assertTrue(reloaded.listFilters(REGION, MANAGEMENT_ACCOUNT, objectMapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void deepInspectionRequiresActualEc2EnablementInTheCallingAccountAndRegion() throws Exception {
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.getEc2DeepInspectionConfiguration(REGION, MEMBER_ACCOUNT)).getErrorCode());
        enableAndConverge(MEMBER_ACCOUNT, "ECR");
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.getEc2DeepInspectionConfiguration(REGION, MEMBER_ACCOUNT)).getErrorCode());
        service.enable(REGION, MEMBER_ACCOUNT, objectMapper.readTree("{\"resourceTypes\":[\"EC2\"]}"));
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.getEc2DeepInspectionConfiguration(REGION, MEMBER_ACCOUNT)).getErrorCode());
        service.accountStatus(REGION, MEMBER_ACCOUNT, MEMBER_ACCOUNT);
        service.accountStatus(REGION, MEMBER_ACCOUNT, MEMBER_ACCOUNT);
        Map<String, Object> configuration = service.getEc2DeepInspectionConfiguration(REGION, MEMBER_ACCOUNT);
        assertEquals("ACTIVATED", configuration.get("status"));
        assertEquals(List.of(), configuration.get("packagePaths"));
        assertEquals(List.of(), configuration.get("orgPackagePaths"));
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.getEc2DeepInspectionConfiguration("us-west-2", MEMBER_ACCOUNT)).getErrorCode());
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.getEc2DeepInspectionConfiguration(REGION, OUTSIDE_ACCOUNT)).getErrorCode());
    }

    @Test
    void cisGuardRejectsOnlyNonEnabledAccountsWithoutFabricatingEnabledResults() throws Exception {
        JsonNode request = objectMapper.createObjectNode();
        AwsException disabled = assertThrows(AwsException.class,
                () -> service.listCisScanConfigurations(REGION, MEMBER_ACCOUNT, request));
        assertEquals("AccessDeniedException", disabled.getErrorCode());
        assertEquals("Invoking account is not enabled.", disabled.getMessage());
        service.enable(REGION, MEMBER_ACCOUNT, objectMapper.readTree("{\"resourceTypes\":[\"ECR\"]}"));
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.listCisScanConfigurations(REGION, MEMBER_ACCOUNT, request)).getErrorCode());
        service.accountStatus(REGION, MEMBER_ACCOUNT, MEMBER_ACCOUNT);
        service.accountStatus(REGION, MEMBER_ACCOUNT, MEMBER_ACCOUNT);
        assertEquals("NotImplementedException", assertThrows(AwsException.class,
                () -> service.listCisScanConfigurations(REGION, MEMBER_ACCOUNT, request)).getErrorCode());
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.listCisScanConfigurations("us-west-2", MEMBER_ACCOUNT, request)).getErrorCode());
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.listCisScanConfigurations(REGION, OUTSIDE_ACCOUNT, request)).getErrorCode());
    }

    private JsonNode filterRequest(String name) throws Exception {
        return objectMapper.readTree("""
                {"action":"SUPPRESS","name":"%s","filterCriteria":{
                  "severity":[{"comparison":"EQUALS","value":"INFORMATIONAL"}]},"tags":{"env":"test"}}
                """.formatted(name));
    }

    private void enableAndConverge(String accountId, String resourceType) throws Exception {
        service.enable(REGION, accountId, objectMapper.readTree("{\"resourceTypes\":[\"" + resourceType + "\"]}"));
        service.accountStatus(REGION, accountId, accountId);
        service.accountStatus(REGION, accountId, accountId);
    }

    @Test
    void clearRemovesState() {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        service.clear();

        assertNull(service.state(REGION).getAdminAccountId());
    }
}

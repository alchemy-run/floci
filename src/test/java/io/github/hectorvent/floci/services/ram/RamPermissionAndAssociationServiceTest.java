package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.ram.model.RamPermission;
import io.github.hectorvent.floci.services.ram.model.RamPermission.PermissionVersion;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.ResourceShareInvitation;
import io.github.hectorvent.floci.services.ram.model.ShareAssociation;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * GetResourceShareAssociations, ListPendingInvitationResources, GetResourcePolicies, and the
 * managed-permission operations (AWS managed catalog plus customer managed permission CRUD and
 * versioning), as driven by Alchemy's RAM ResourceShare and Permission providers.
 */
class RamPermissionAndAssociationServiceTest {

    private static final String OWNER = "111111111111";
    private static final String MEMBER = "222222222222";
    /** Outside the owner's organization. */
    private static final String EXTERNAL = "123456789012";
    private static final String OU_ARN = "arn:aws:organizations::111111111111:ou/o-abc123/ou-root-infra";
    private static final String SUBNET_ARN = "arn:aws:ec2:us-east-1:111111111111:subnet/subnet-1";
    private static final String SUBNET_DEFAULT =
            "arn:aws:ram::aws:permission/AWSRAMDefaultPermissionSubnet";
    private static final String APPSYNC_TEMPLATE =
            "{\"Effect\":\"Allow\",\"Action\":[\"appsync:SourceGraphQL\"]}";
    private static final ObjectMapper JSON = new ObjectMapper();

    private SharedStorageFactory storage;
    private RamService service;

    @BeforeEach
    void setUp() {
        storage = new SharedStorageFactory();
        service = newService(storage);
    }

    @Test
    void createResourceShareAppliesTagsSourcesAndPermissions() {
        ResourceShare share = service.createResourceShare("tagged", List.of(EXTERNAL), List.of(SUBNET_ARN),
                List.of(MEMBER), List.of(SUBNET_DEFAULT), Map.of("team", "platform"), true, "us-east-1", OWNER);

        assertEquals(Map.of("team", "platform"), share.getTags());
        ResourceShare stored = service.getResourceShares(OWNER, "SELF").getFirst();
        assertEquals(Map.of("team", "platform"), stored.getTags());
        assertEquals(List.of(MEMBER), stored.getSources());
        assertEquals(List.of(SUBNET_DEFAULT), stored.getPermissionArns());
    }

    @Test
    void createResourceShareRejectsAnUnknownPermission() {
        AwsException error = assertThrows(AwsException.class, () -> service.createResourceShare("bad",
                List.of(), List.of(), List.of(), List.of("arn:aws:ram::aws:permission/DoesNotExist"), Map.of(),
                true, "us-east-1", OWNER));
        assertEquals("UnknownResourceException", error.getErrorCode());
    }

    @Test
    void principalAssociationsFollowAssociateAndDisassociate() {
        ResourceShare share = service.createResourceShare(
                "assoc", List.of(EXTERNAL, OU_ARN), List.of(SUBNET_ARN), true, "us-east-1", OWNER);
        String arn = share.getResourceShareArn();

        List<ShareAssociation> principals = service.getResourceShareAssociations(
                OWNER, "PRINCIPAL", List.of(arn), null, null, null);
        assertEquals(2, principals.size());
        ShareAssociation external = principals.stream()
                .filter(a -> a.associatedEntity().equals(EXTERNAL)).findFirst().orElseThrow();
        assertEquals("ASSOCIATED", external.status());
        assertEquals("PRINCIPAL", external.associationType());
        assertEquals("assoc", external.resourceShareName());
        assertTrue(external.external());
        assertFalse(principals.stream()
                .filter(a -> a.associatedEntity().equals(OU_ARN)).findFirst().orElseThrow().external());

        List<ShareAssociation> resources = service.getResourceShareAssociations(
                OWNER, "RESOURCE", List.of(arn), SUBNET_ARN, null, null);
        assertEquals(1, resources.size());
        assertEquals(SUBNET_ARN, resources.getFirst().associatedEntity());

        service.disassociateResourceShare(arn, List.of(), List.of(EXTERNAL), OWNER);
        assertEquals(List.of(OU_ARN), service.getResourceShareAssociations(
                OWNER, "PRINCIPAL", List.of(arn), null, null, null)
                .stream().map(ShareAssociation::associatedEntity).toList());

        service.deleteResourceShare(arn, OWNER);
        List<ShareAssociation> afterDelete = service.getResourceShareAssociations(
                OWNER, "PRINCIPAL", List.of(arn), null, null, null);
        assertEquals(1, afterDelete.size());
        assertEquals("DISASSOCIATED", afterDelete.getFirst().status());
        assertTrue(service.getResourceShareAssociations(
                OWNER, "PRINCIPAL", List.of(arn), null, null, "ASSOCIATED").isEmpty());
    }

    @Test
    void associationsAreOwnerScopedAndValidated() {
        ResourceShare share = service.createResourceShare(
                "scoped", List.of(EXTERNAL), List.of(SUBNET_ARN), true, "us-east-1", OWNER);
        String arn = share.getResourceShareArn();

        // GetResourceShareAssociations only covers shares the caller owns.
        assertError("UnknownResourceException", () -> service.getResourceShareAssociations(
                EXTERNAL, "PRINCIPAL", List.of(arn), null, null, null));
        assertTrue(service.getResourceShareAssociations(
                EXTERNAL, "PRINCIPAL", List.of(), null, null, null).isEmpty());

        assertError("InvalidParameterException", () -> service.getResourceShareAssociations(
                OWNER, null, List.of(), null, null, null));
        assertError("InvalidParameterException", () -> service.getResourceShareAssociations(
                OWNER, "principal", List.of(), null, null, null));
        assertError("InvalidParameterException", () -> service.getResourceShareAssociations(
                OWNER, "PRINCIPAL", List.of(), SUBNET_ARN, null, null));
        assertError("InvalidParameterException", () -> service.getResourceShareAssociations(
                OWNER, "RESOURCE", List.of(), null, EXTERNAL, null));
        assertError("MalformedArnException", () -> service.getResourceShareAssociations(
                OWNER, "PRINCIPAL", List.of("not-an-arn"), null, null, null));
    }

    @Test
    void pendingInvitationResourcesAreReadableByTheInviteeOnly() {
        ResourceShare share = service.createResourceShare(
                "pending", List.of(EXTERNAL), List.of(SUBNET_ARN), true, "us-east-1", OWNER);
        ResourceShareInvitation invitation =
                service.getResourceShareInvitations(EXTERNAL, List.of(), List.of()).getFirst();

        List<SharedResource> resources = service.listPendingInvitationResources(
                EXTERNAL, invitation.resourceShareInvitationArn(), null);
        assertEquals(1, resources.size());
        assertEquals(SUBNET_ARN, resources.getFirst().arn());
        assertEquals("ec2:Subnet", resources.getFirst().type());
        assertEquals(share.getResourceShareArn(), resources.getFirst().resourceShareArn());

        assertError("ResourceShareInvitationArnNotFoundException", () -> service.listPendingInvitationResources(
                OWNER, invitation.resourceShareInvitationArn(), null));
        assertError("ResourceShareInvitationArnNotFoundException", () -> service.listPendingInvitationResources(
                EXTERNAL, "arn:aws:ram:us-east-1:111111111111:resource-share-invitation/nope", null));
        assertError("MalformedArnException", () -> service.listPendingInvitationResources(
                EXTERNAL, "nope", null));

        service.rejectResourceShareInvitation(invitation.resourceShareInvitationArn(), EXTERNAL);
        assertError("ResourceShareInvitationAlreadyRejectedException", () ->
                service.listPendingInvitationResources(EXTERNAL, invitation.resourceShareInvitationArn(), null));
    }

    @Test
    void resourcePoliciesGrantTheSharesPermissionToItsPrincipals() throws Exception {
        service.createResourceShare(
                "subnets", List.of(EXTERNAL), List.of(SUBNET_ARN), true, "us-east-1", OWNER);

        List<String> policies = service.getResourcePolicies(OWNER, List.of(SUBNET_ARN), null);
        assertEquals(1, policies.size());
        JsonNode statement = JSON.readTree(policies.getFirst()).path("Statement").get(0);
        assertEquals("Allow", statement.path("Effect").asText());
        assertEquals(EXTERNAL, statement.path("Principal").path("AWS").get(0).asText());
        assertEquals(SUBNET_ARN, statement.path("Resource").asText());
        assertEquals(List.of("ec2:RunInstances", "ec2:CreateNetworkInterface", "ec2:DescribeSubnets"),
                JSON.convertValue(statement.path("Action"), List.class));

        // An unshared resource has no RAM policy; nor does another account's view of it.
        assertTrue(service.getResourcePolicies(OWNER,
                List.of("arn:aws:ec2:us-east-1:111111111111:subnet/subnet-unshared"), null).isEmpty());
        assertTrue(service.getResourcePolicies(MEMBER, List.of(SUBNET_ARN), null).isEmpty());
        assertTrue(service.getResourcePolicies(OWNER, List.of(SUBNET_ARN), MEMBER).isEmpty());
        assertError("MalformedArnException", () -> service.getResourcePolicies(OWNER, List.of("x"), null));
    }

    @Test
    void awsManagedPermissionsAreListedAndReadable() {
        List<RamPermission> all = service.listPermissions(OWNER, "us-east-1", null, null);
        RamPermission subnet = all.stream()
                .filter(p -> p.arn().equals(SUBNET_DEFAULT)).findFirst().orElseThrow();
        assertEquals("AWSRAMDefaultPermissionSubnet", subnet.name());
        assertEquals("ec2:Subnet", subnet.resourceType());
        assertEquals("AWS_MANAGED", subnet.permissionType());
        assertTrue(subnet.resourceTypeDefault());

        assertEquals(List.of(SUBNET_DEFAULT), service.listPermissions(OWNER, "us-east-1", "EC2:SUBNET", "ALL")
                .stream().map(RamPermission::arn).toList());
        assertTrue(service.listPermissions(OWNER, "us-east-1", null, "CUSTOMER_MANAGED").isEmpty());
        assertError("InvalidParameterException",
                () -> service.listPermissions(OWNER, "us-east-1", null, "SOME"));

        Map.Entry<RamPermission, PermissionVersion> read = service.getPermission(OWNER, SUBNET_DEFAULT, null);
        assertTrue(read.getValue().policyTemplate().contains("ec2:DescribeSubnets"));
        assertEquals("ATTACHABLE", read.getKey().statusOf(read.getValue()));

        assertError("UnknownResourceException", () -> service.getPermission(
                OWNER, "arn:aws:ram::aws:permission/AWSRAMDefaultPermissionNope", null));
        assertError("MalformedArnException", () -> service.getPermission(OWNER, "nope", null));
        assertError("OperationNotPermittedException", () -> service.deletePermission(OWNER, SUBNET_DEFAULT));
    }

    @Test
    void customerManagedPermissionVersionsAdvanceTheDefault() {
        RamPermission created = service.createPermission(OWNER, "us-east-1", "SourceOnly", "appsync:Apis",
                APPSYNC_TEMPLATE, Map.of("team", "platform"));
        String arn = created.arn();
        assertEquals("arn:aws:ram:us-east-1:111111111111:permission/SourceOnly", arn);
        assertEquals("CUSTOMER_MANAGED", created.permissionType());
        assertEquals(1, created.defaultVersion());

        assertError("PermissionAlreadyExistsException", () -> service.createPermission(
                OWNER, "us-east-1", "SourceOnly", "appsync:Apis", APPSYNC_TEMPLATE, Map.of()));

        String grown = "{\"Effect\":\"Allow\",\"Action\":[\"appsync:SourceGraphQL\",\"appsync:GraphQL\"]}";
        RamPermission v2 = service.createPermissionVersion(OWNER, arn, grown);
        assertEquals(2, v2.defaultVersion());
        assertEquals("UNATTACHABLE", v2.statusOf(v2.findVersion(1).orElseThrow()));
        assertEquals(grown, service.getPermission(OWNER, arn, null).getValue().policyTemplate());
        assertEquals(APPSYNC_TEMPLATE, service.getPermission(OWNER, arn, 1).getValue().policyTemplate());

        assertError("OperationNotPermittedException", () -> service.deletePermissionVersion(OWNER, arn, 2));
        RamPermission afterDelete = service.deletePermissionVersion(OWNER, arn, 1);
        assertEquals("ATTACHABLE", afterDelete.statusOf(afterDelete.defaultVersionEntry()));

        // Deleted versions stay listed with status DELETED.
        List<PermissionVersion> versions = service.listPermissionVersions(OWNER, arn);
        assertEquals(List.of(1, 2), versions.stream().map(PermissionVersion::version).toList());
        RamPermission current = service.getPermission(OWNER, arn, null).getKey();
        assertEquals("DELETED", current.statusOf(versions.get(0)));
        assertEquals("ATTACHABLE", current.statusOf(versions.get(1)));

        service.tagPermission(arn, Map.of("env", "prod"), OWNER);
        service.untagPermission(arn, List.of("team"), OWNER);
        assertEquals(Map.of("env", "prod"), service.getPermission(OWNER, arn, null).getKey().tags());

        assertEquals(List.of(arn), service.listPermissions(OWNER, "us-east-1", null, "CUSTOMER_MANAGED")
                .stream().map(RamPermission::arn).toList());
        assertTrue(service.listPermissions(OWNER, "eu-west-1", null, "CUSTOMER_MANAGED").isEmpty());
        assertTrue(service.listPermissions(MEMBER, "us-east-1", null, "CUSTOMER_MANAGED").isEmpty());
        assertError("UnknownResourceException", () -> service.getPermission(MEMBER, arn, null));
    }

    @Test
    void permissionVersionsAreCappedAtFive() {
        String arn = service.createPermission(OWNER, "us-east-1", "Capped", "appsync:Apis",
                APPSYNC_TEMPLATE, Map.of()).arn();
        for (int i = 0; i < 4; i++) {
            service.createPermissionVersion(OWNER, arn, APPSYNC_TEMPLATE);
        }
        assertError("PermissionVersionsLimitExceededException",
                () -> service.createPermissionVersion(OWNER, arn, APPSYNC_TEMPLATE));
        service.deletePermissionVersion(OWNER, arn, 1);
        assertEquals(6, service.createPermissionVersion(OWNER, arn, APPSYNC_TEMPLATE).defaultVersion());
    }

    @Test
    void policyTemplatesAreValidated() {
        assertError("MalformedPolicyTemplateException", () -> service.createPermission(
                OWNER, "us-east-1", "Bad", "appsync:Apis", "not json", Map.of()));
        assertError("MalformedPolicyTemplateException", () -> service.createPermission(
                OWNER, "us-east-1", "Bad", "appsync:Apis",
                "{\"Effect\":\"Allow\",\"Action\":\"appsync:SourceGraphQL\",\"Resource\":\"*\"}", Map.of()));
        assertError("InvalidPolicyException", () -> service.createPermission(
                OWNER, "us-east-1", "Bad", "appsync:Apis",
                "{\"Effect\":\"Deny\",\"Action\":\"appsync:SourceGraphQL\"}", Map.of()));
        assertError("InvalidPolicyException", () -> service.createPermission(
                OWNER, "us-east-1", "Bad", "appsync:Apis",
                "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"]}", Map.of()));
        assertError("InvalidParameterException", () -> service.createPermission(
                OWNER, "us-east-1", "has space", "appsync:Apis", APPSYNC_TEMPLATE, Map.of()));
        assertError("InvalidParameterException", () -> service.createPermission(
                OWNER, "us-east-1", "Bad", "appsync", APPSYNC_TEMPLATE, Map.of()));
    }

    @Test
    void attachedPermissionCannotBeDeletedUntilTheShareIsGone() {
        String arn = service.createPermission(OWNER, "us-east-1", "Attached", "appsync:Apis",
                APPSYNC_TEMPLATE, Map.of()).arn();
        ResourceShare share = service.createResourceShare("uses-it", List.of(), List.of(), List.of(),
                List.of(arn), Map.of(), true, "us-east-1", OWNER);

        assertError("OperationNotPermittedException", () -> service.deletePermission(OWNER, arn));
        service.deleteResourceShare(share.getResourceShareArn(), OWNER);
        service.deletePermission(OWNER, arn);
        assertError("UnknownResourceException", () -> service.getPermission(OWNER, arn, null));
        assertError("UnknownResourceException", () -> service.deletePermission(OWNER, arn));
    }

    @Test
    void customerManagedPermissionsSurviveRestart() {
        String arn = service.createPermission(OWNER, "us-east-1", "Durable", "appsync:Apis",
                APPSYNC_TEMPLATE, Map.of("k", "v")).arn();
        service.createPermissionVersion(OWNER, arn, APPSYNC_TEMPLATE);

        RamService reloaded = newService(storage);
        RamPermission permission = reloaded.getPermission(OWNER, arn, null).getKey();
        assertEquals(2, permission.defaultVersion());
        assertEquals(Map.of("k", "v"), permission.tags());
    }

    @Test
    void paginationWalksEveryItemOnce() {
        List<Integer> all = List.of(1, 2, 3, 4, 5);
        RamService.Page<Integer> first = RamService.paginate(all, 2, null);
        assertEquals(List.of(1, 2), first.items());
        RamService.Page<Integer> second = RamService.paginate(all, 2, first.nextToken());
        assertEquals(List.of(3, 4), second.items());
        RamService.Page<Integer> last = RamService.paginate(all, 2, second.nextToken());
        assertEquals(List.of(5), last.items());
        assertNull(last.nextToken());
        assertEquals(all, RamService.paginate(all, null, null).items());

        assertError("InvalidNextTokenException", () -> RamService.paginate(all, 2, "garbage!"));
        assertError("InvalidParameterException", () -> RamService.paginate(all, 0, null));
    }

    private static RamService newService(StorageFactory storage) {
        OrganizationsService organizations = mock(OrganizationsService.class);
        Organization organization = new Organization();
        organization.setId("o-abc123");
        doThrow(new AwsException("AWSOrganizationsNotInUseException", "not a member", 400))
                .when(organizations).describeOrganization(anyString());
        doReturn(organization).when(organizations).describeOrganization(OWNER);
        doReturn(organization).when(organizations).describeOrganization(MEMBER);
        RamService service = new RamService(storage, organizations);
        service.initializeStorage();
        return service;
    }

    private static void assertError(String code, Executable call) {
        AwsException error = assertThrows(AwsException.class, call);
        assertEquals(code, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(
                    fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}

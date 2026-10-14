package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.AccessScope;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.AssociatedAccessPolicy;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksAccessEntryServiceTest {
    private static final String PRINCIPAL = "arn:aws:iam::123456789012:role/path/worker";
    private static final String VIEW_POLICY = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";
    private static final String ADMIN_POLICY = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy";

    @Test
    void nodeEntryUsesGeneratedIdentityAndCapturesStablePrincipalId() throws Exception {
        Fixture fixture = fixture();
        AccessEntry entry = fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", "retry"));
        assertEquals("system:node:{{EC2PrivateDNSName}}", entry.username());
        assertEquals(List.of("system:nodes"), entry.kubernetesGroups());
        assertEquals(Map.of("team", "platform"), entry.tags());
        assertTrue(entry.createdAt() > 0);
        EksAccessEntryService.StoredEntry stored = fixture.storage.scan(key -> true).getFirst();
        assertEquals("AROA-worker", stored.principalId());
        ObjectMapper mapper = new ObjectMapper();
        EksAccessEntryService.StoredEntry restored = mapper.readValue(mapper.writeValueAsBytes(stored),
                EksAccessEntryService.StoredEntry.class);
        assertEquals(stored, restored);
        assertFalse(mapper.writeValueAsString(entry).contains("principalId"));
        fixture.role.setRoleId("AROA-recreated");
        assertEquals("AROA-worker", fixture.storage.scan(key -> true).getFirst().principalId());
        assertEquals(entry, fixture.service.describe(fixture.cluster, PRINCIPAL));
    }

    @Test
    void retriesAreIdempotentButConflictingRequestsAndDuplicatesFail() {
        Fixture fixture = fixture();
        CreateAccessEntryRequest request = request(PRINCIPAL, "EC2_LINUX", "retry");
        AccessEntry first = fixture.service.create(fixture.cluster, request);
        assertEquals(first, fixture.service.create(fixture.cluster, request));
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, () -> fixture.service.create(
                fixture.cluster, request(PRINCIPAL, "STANDARD", "retry"))).getErrorCode());
        assertEquals(409, assertThrows(AwsException.class, () -> fixture.service.create(
                fixture.cluster, request(PRINCIPAL, "EC2_LINUX", "other"))).getHttpStatus());
    }

    @Test
    void standardRoleDefaultsStripTheIamPath() {
        Fixture fixture = fixture();
        AccessEntry entry = fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        assertEquals("STANDARD", entry.type());
        assertEquals("arn:aws:sts::123456789012:assumed-role/worker/{{SessionName}}", entry.username());
    }

    @Test
    void standardUserCanBelongToAnotherAccount() {
        Fixture fixture = fixture();
        String principal = "arn:aws:iam::999999999999:user/team/reader";
        when(fixture.iam.findUser("999999999999", "reader")).thenReturn(Optional.of(
                new IamUser("AIDA-reader", "reader", "/team/", principal)));
        AccessEntry entry = fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(principal, null, null, List.of("readers"), null, null));
        assertEquals(principal, entry.username());
        assertEquals(List.of("readers"), entry.kubernetesGroups());
        assertEquals("AIDA-reader", fixture.storage.scan(key -> true).getFirst().principalId());
    }

    @Test
    void nodeOverridesForeignAccountsAndMissingPrincipalsAreRejected() {
        Fixture fixture = fixture();
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(PRINCIPAL, "EC2_LINUX", "custom", null, null, null)));
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(PRINCIPAL, "EC2_LINUX", null, List.of(), null, null)));
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL.replace("123456789012", "999999999999"), "EC2_LINUX", null)));
        when(fixture.iam.findRole("123456789012", "worker")).thenReturn(Optional.empty());
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL, "STANDARD", null)));
    }

    @Test
    void paginationTokensCannotCrossClusterOrAccountBoundaries() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        String second = PRINCIPAL.replace("worker", "worker2");
        when(fixture.iam.findRole("123456789012", "worker2")).thenReturn(Optional.of(
                new IamRole("AROA-second", "worker2", "/path/", second, "{}")));
        fixture.service.create(fixture.cluster, request(second, "STANDARD", null));
        EksAccessEntryService.Page first = fixture.service.list(fixture.cluster, 1, null);
        assertEquals(List.of(PRINCIPAL), first.accessEntries());
        assertNotNull(first.nextToken());
        EksAccessEntryService.Page next = fixture.service.list(fixture.cluster, 1, first.nextToken());
        assertEquals(List.of(second), next.accessEntries());
        assertNull(next.nextToken());
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 0, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 101, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 1, "invalid"));
        fixture.cluster.setArn(fixture.cluster.getArn().replace("123456789012", "999999999999"));
        assertTrue(fixture.service.list(fixture.cluster, 100, null).accessEntries().isEmpty());
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 1, first.nextToken()));
    }

    @Test
    void clusterDeletionCleansEntriesAndRecreationCannotInheritThem() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        fixture.service.deleteClusterEntries(fixture.cluster);
        assertTrue(fixture.storage.scan(key -> true).isEmpty());
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        fixture.cluster.setCreatedAt(fixture.cluster.getCreatedAt().plusSeconds(1));
        assertTrue(fixture.service.list(fixture.cluster, null, null).accessEntries().isEmpty());
        assertEquals(404, assertThrows(AwsException.class,
                () -> fixture.service.describe(fixture.cluster, PRINCIPAL)).getHttpStatus());
    }

    @Test
    void configMapAndInactiveClustersRejectAccessEntryOperations() {
        Fixture fixture = fixture();
        fixture.cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", true));
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.list(fixture.cluster, null, null)).getErrorCode());
        fixture.cluster.setAccessConfig(new AccessConfig("API", false));
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        fixture.cluster.setStatus(ClusterStatus.CREATING);
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () -> fixture.service.create(
                fixture.cluster, request(PRINCIPAL, "EC2_LINUX", "other"))).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () -> fixture.service.update(
                fixture.cluster, PRINCIPAL, new UpdateAccessEntryRequest(List.of("g"), null, null))).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.delete(fixture.cluster, PRINCIPAL)).getErrorCode());
        // Reads keep answering while the cluster is not ACTIVE, as on EKS.
        assertEquals(List.of(PRINCIPAL), fixture.service.list(fixture.cluster, null, null).accessEntries());
        assertEquals(PRINCIPAL, fixture.service.describe(fixture.cluster, PRINCIPAL).principalArn());
        assertTrue(fixture.service.listAssociatedAccessPolicies(fixture.cluster, PRINCIPAL).isEmpty());
    }

    @Test
    void updateReplacesSuppliedFieldsAndKeepsOmittedOnes() {
        Fixture fixture = fixture();
        AccessEntry created = fixture.service.create(fixture.cluster, new CreateAccessEntryRequest(
                PRINCIPAL, null, null, List.of("viewers"), Map.of("team", "platform"), null));
        String defaultUsername = created.username();

        AccessEntry groupsOnly = fixture.service.update(fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(List.of("editors", "viewers"), "token", null));
        assertEquals(List.of("editors", "viewers"), groupsOnly.kubernetesGroups());
        assertEquals(defaultUsername, groupsOnly.username());
        assertEquals(created.accessEntryArn(), groupsOnly.accessEntryArn());
        assertEquals(created.createdAt(), groupsOnly.createdAt());
        assertEquals(Map.of("team", "platform"), groupsOnly.tags());

        AccessEntry renamed = fixture.service.update(fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(null, null, "admin:{{SessionName}}"));
        assertEquals("admin:{{SessionName}}", renamed.username());
        assertEquals(List.of("editors", "viewers"), renamed.kubernetesGroups());
        assertEquals(renamed, fixture.service.describe(fixture.cluster, PRINCIPAL));

        assertEquals("InvalidParameterException", assertThrows(AwsException.class, () -> fixture.service.update(
                fixture.cluster, PRINCIPAL, new UpdateAccessEntryRequest(null, null, "system:admin"))).getErrorCode());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, () -> fixture.service.update(
                fixture.cluster, PRINCIPAL, new UpdateAccessEntryRequest(List.of(" "), null, null))).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> fixture.service.update(
                fixture.cluster, PRINCIPAL + "-missing", new UpdateAccessEntryRequest(null, null, null)))
                .getErrorCode());
    }

    @Test
    void nodeEntriesRejectUsernameAndGroupUpdatesAndAccessPolicies() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, () -> fixture.service.update(
                fixture.cluster, PRINCIPAL, new UpdateAccessEntryRequest(List.of("g"), null, null))).getErrorCode());
        assertEquals("system:node:{{EC2PrivateDNSName}}", fixture.service.update(fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(null, null, null)).username());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> fixture.service.associateAccessPolicy(fixture.cluster, PRINCIPAL, new AssociateAccessPolicyRequest(
                        VIEW_POLICY, new AccessScope("cluster", null)))).getErrorCode());
    }

    @Test
    void accessPoliciesAssociateReplaceScopeAndDisassociate() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));

        AssociatedAccessPolicy view = fixture.service.associateAccessPolicy(fixture.cluster, PRINCIPAL,
                new AssociateAccessPolicyRequest(VIEW_POLICY, new AccessScope("namespace", List.of("dev", "test-*"))));
        assertEquals(new AccessScope("namespace", List.of("dev", "test-*")), view.accessScope());
        AssociatedAccessPolicy admin = fixture.service.associateAccessPolicy(fixture.cluster, PRINCIPAL,
                new AssociateAccessPolicyRequest(ADMIN_POLICY, new AccessScope("cluster", null)));
        assertEquals(new AccessScope("cluster", List.of()), admin.accessScope());
        assertEquals(List.of(ADMIN_POLICY, VIEW_POLICY), fixture.service.listAssociatedAccessPolicies(
                fixture.cluster, PRINCIPAL).stream().map(AssociatedAccessPolicy::policyArn).toList());

        AssociatedAccessPolicy rescoped = fixture.service.associateAccessPolicy(fixture.cluster, PRINCIPAL,
                new AssociateAccessPolicyRequest(VIEW_POLICY, new AccessScope("cluster", List.of())));
        assertEquals(view.associatedAt(), rescoped.associatedAt());
        assertEquals(2, fixture.service.listAssociatedAccessPolicies(fixture.cluster, PRINCIPAL).size());
        assertEquals("cluster", fixture.service.listAssociatedAccessPolicies(fixture.cluster, PRINCIPAL).stream()
                .filter(policy -> policy.policyArn().equals(VIEW_POLICY)).findFirst().orElseThrow()
                .accessScope().type());

        ObjectMapper mapper = new ObjectMapper();
        EksAccessEntryService.StoredEntry stored = fixture.storage.scan(key -> true).getFirst();
        assertEquals(stored, mapper.readValue(mapper.writeValueAsBytes(stored), EksAccessEntryService.StoredEntry.class));

        for (AssociateAccessPolicyRequest invalid : List.of(
                new AssociateAccessPolicyRequest("arn:aws:eks::aws:cluster-access-policy/NotAPolicy",
                        new AccessScope("cluster", null)),
                new AssociateAccessPolicyRequest(VIEW_POLICY, null),
                new AssociateAccessPolicyRequest(VIEW_POLICY, new AccessScope("namespace", List.of())),
                new AssociateAccessPolicyRequest(VIEW_POLICY, new AccessScope("cluster", List.of("dev"))),
                new AssociateAccessPolicyRequest(VIEW_POLICY, new AccessScope("account", null)))) {
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.associateAccessPolicy(fixture.cluster, PRINCIPAL, invalid)).getErrorCode());
        }

        fixture.service.disassociateAccessPolicy(fixture.cluster, PRINCIPAL, VIEW_POLICY);
        assertEquals(List.of(ADMIN_POLICY), fixture.service.listAssociatedAccessPolicies(
                fixture.cluster, PRINCIPAL).stream().map(AssociatedAccessPolicy::policyArn).toList());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.service.disassociateAccessPolicy(fixture.cluster, PRINCIPAL, VIEW_POLICY)).getErrorCode());
    }

    @Test
    void entriesPersistedBeforeAccessPoliciesDeserialize() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode legacy =
                mapper.valueToTree(fixture.storage.scan(key -> true).getFirst());
        legacy.remove("accessPolicies");
        EksAccessEntryService.StoredEntry restored = mapper.treeToValue(legacy, EksAccessEntryService.StoredEntry.class);
        String key = fixture.storage.keys().iterator().next();
        fixture.storage.put(key, restored);
        assertTrue(fixture.service.listAssociatedAccessPolicies(fixture.cluster, PRINCIPAL).isEmpty());
    }

    private static CreateAccessEntryRequest request(String principal, String type, String token) {
        return new CreateAccessEntryRequest(principal, type, null, null, Map.of("team", "platform"), token);
    }

    private static Fixture fixture() {
        Cluster cluster = new Cluster();
        cluster.setName("nodes");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/nodes");
        cluster.setCreatedAt(Instant.parse("2026-09-16T10:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API", false));
        IamService iam = mock(IamService.class);
        IamRole role = new IamRole("AROA-worker", "worker", "/path/", PRINCIPAL, "{}");
        when(iam.findRole("123456789012", "worker")).thenReturn(Optional.of(role));
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> storage = new InMemoryStorage<>();
        return new Fixture(cluster, iam, role, storage, new EksAccessEntryService(storage, iam));
    }

    private record Fixture(Cluster cluster, IamService iam, IamRole role,
                           InMemoryStorage<String, EksAccessEntryService.StoredEntry> storage,
                           EksAccessEntryService service) {}
}

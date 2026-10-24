package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
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
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Access-entry management metadata; Kubernetes authorization is handled separately. */
@ApplicationScoped
public class EksAccessEntryService {
    private final StorageBackend<String, StoredEntry> entries;
    private final IamService iam;

    @Inject
    public EksAccessEntryService(StorageFactory factory, IamService iam) {
        this(factory.create("eks", "eks-access-entries.json", new TypeReference<Map<String, StoredEntry>>() {}), iam);
    }

    EksAccessEntryService(StorageBackend<String, StoredEntry> entries, IamService iam) {
        this.entries = entries;
        this.iam = iam;
    }

    @RegisterForReflection
    public record StoredEntry(AccessEntry entry, String principalId, String clientRequestToken,
                              List<AssociatedAccessPolicy> accessPolicies) {
        StoredEntry(AccessEntry entry, String principalId, String clientRequestToken) {
            this(entry, principalId, clientRequestToken, List.of());
        }

        List<AssociatedAccessPolicy> policies() {
            return accessPolicies == null ? List.of() : accessPolicies;
        }
    }

    @RegisterForReflection
    public record Page(List<String> accessEntries, String nextToken) {}

    public synchronized AccessEntry create(Cluster cluster, CreateAccessEntryRequest request) {
        requireApiAccess(cluster);
        if (request == null || request.principalArn() == null) {
            throw invalid("principalArn is required");
        }
        String type = request.type() == null ? "STANDARD" : request.type();
        if (!"STANDARD".equals(type) && !"EC2_LINUX".equals(type)) {
            throw invalid("Floci currently supports STANDARD and EC2_LINUX access entries");
        }
        boolean node = "EC2_LINUX".equals(type);
        if (node && (request.username() != null || request.kubernetesGroups() != null)) {
            throw invalid("EC2_LINUX entries cannot specify username or kubernetesGroups");
        }
        String principal = request.principalArn();
        String[] arn = principal.split(":", 6);
        String[] clusterArn = cluster.getArn().split(":", 6);
        if (arn.length != 6 || !"arn".equals(arn[0]) || !arn[1].equals(clusterArn[1])
                || !"iam".equals(arn[2]) || !arn[3].isEmpty() || !arn[4].matches("[0-9]{12}")
                || !(arn[5].startsWith("role/") || (!node && arn[5].startsWith("user/")))
                || arn[5].startsWith("role/aws-service-role/")
                || principal.endsWith("/") || (node && !arn[4].equals(clusterArn[4]))) {
            throw invalid("principalArn must identify an IAM principal; EC2_LINUX requires a role in the cluster account");
        }
        String name = principal.substring(principal.lastIndexOf('/') + 1);
        boolean role = arn[5].startsWith("role/");
        String principalId = role
                ? iam.findRole(arn[4], name).filter(value -> principal.equals(value.getArn()))
                        .map(IamRole::getRoleId).orElseThrow(() -> invalid("IAM role does not exist"))
                : iam.findUser(arn[4], name).filter(value -> principal.equals(value.getArn()))
                        .map(IamUser::getUserId).orElseThrow(() -> invalid("IAM user does not exist"));
        String username = node ? "system:node:{{EC2PrivateDNSName}}" : request.username();
        if (username != null && !node) {
            validateStandardUsername(username);
        }
        if (username == null) {
            username = role ? "arn:" + arn[1] + ":sts::" + arn[4] + ":assumed-role/" + name + "/{{SessionName}}"
                    : principal;
        }
        List<String> groups = node ? List.of("system:nodes")
                : request.kubernetesGroups() == null ? List.of() : request.kubernetesGroups();
        validateGroups(groups);
        Map<String, String> tags = request.tags() == null ? Map.of() : request.tags();
        if (tags.size() > 50 || tags.entrySet().stream().anyMatch(tag -> tag.getKey() == null
                || tag.getKey().isEmpty() || tag.getKey().length() > 128 || tag.getValue() == null
                || tag.getValue().length() > 256)) {
            throw invalid("Invalid access entry tags");
        }
        String prefix = prefix(cluster);
        String token = request.clientRequestToken();
        if (token != null) {
            for (StoredEntry stored : entries.scan(key -> key.startsWith(prefix))) {
                if (token.equals(stored.clientRequestToken())) {
                    AccessEntry previous = stored.entry();
                    if (principal.equals(previous.principalArn()) && type.equals(previous.type())
                            && username.equals(previous.username()) && groups.equals(previous.kubernetesGroups())
                            && tags.equals(previous.tags())) {
                        return previous;
                    }
                    throw invalid("clientRequestToken was already used with different parameters");
                }
            }
        }
        String key = prefix + principal;
        if (entries.get(key).isPresent()) {
            throw new AwsException("ResourceInUseException", "Access entry already exists", 409);
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        AccessEntry entry = new AccessEntry("arn:" + clusterArn[1] + ":eks:" + clusterArn[3] + ":"
                + clusterArn[4] + ":access-entry/" + cluster.getName() + "/" + (role ? "role" : "user")
                + "/" + arn[4] + "/" + name + "/" + UUID.randomUUID(), cluster.getName(), principal,
                type, username, List.copyOf(groups), Map.copyOf(tags), now, now);
        entries.put(key, new StoredEntry(entry, principalId, token));
        return entry;
    }

    synchronized Optional<AccessEntry> workerEntry(Cluster cluster, String accountId, String principal, String roleId) {
        requireApiAccess(cluster);
        String key = prefix(cluster) + principal;
        Optional<StoredEntry> stored = entries instanceof AccountAwareStorageBackend<StoredEntry> aware
                ? aware.getForAccount(accountId, key) : entries.get(key);
        return stored.filter(value -> roleId != null && roleId.equals(value.principalId()))
                .map(StoredEntry::entry).filter(entry -> "EC2_LINUX".equals(entry.type()));
    }

    public synchronized AccessEntry describe(Cluster cluster, String principal) {
        return stored(cluster, principal).entry();
    }

    /**
     * UpdateAccessEntry: an omitted username or kubernetesGroups keeps its current value, a
     * supplied kubernetesGroups replaces the list. Node entries have neither to change.
     */
    public synchronized AccessEntry update(Cluster cluster, String principal, UpdateAccessEntryRequest request) {
        requireApiAccess(cluster);
        StoredEntry stored = stored(cluster, principal);
        AccessEntry current = stored.entry();
        String username = request == null ? null : request.username();
        List<String> groups = request == null ? null : request.kubernetesGroups();
        if (!"STANDARD".equals(current.type()) && (username != null || groups != null)) {
            throw invalid(current.type() + " entries cannot specify username or kubernetesGroups");
        }
        if (username != null) {
            validateStandardUsername(username);
        }
        if (groups != null) {
            validateGroups(groups);
        }
        AccessEntry updated = new AccessEntry(current.accessEntryArn(), current.clusterName(), current.principalArn(),
                current.type(), username != null ? username : current.username(),
                groups != null ? List.copyOf(groups) : current.kubernetesGroups(), current.tags(),
                current.createdAt(), Instant.now().toEpochMilli() / 1000.0);
        entries.put(prefix(cluster) + principal, new StoredEntry(updated, stored.principalId(),
                stored.clientRequestToken(), stored.policies()));
        return updated;
    }

    /** Associating a policy that is already associated replaces its access scope. */
    public synchronized AssociatedAccessPolicy associateAccessPolicy(Cluster cluster, String principal,
                                                                     AssociateAccessPolicyRequest request) {
        requireApiAccess(cluster);
        StoredEntry stored = stored(cluster, principal);
        if (!"STANDARD".equals(stored.entry().type())) {
            throw invalid("Access policies can only be associated with STANDARD access entries");
        }
        String policyArn = request == null ? null : request.policyArn();
        validatePolicyArn(cluster, policyArn);
        AccessScope scope = normalizeScope(request.accessScope());
        double now = Instant.now().toEpochMilli() / 1000.0;
        List<AssociatedAccessPolicy> policies = new ArrayList<>();
        double associatedAt = now;
        for (AssociatedAccessPolicy existing : stored.policies()) {
            if (existing.policyArn().equals(policyArn)) {
                associatedAt = existing.associatedAt();
            } else {
                policies.add(existing);
            }
        }
        AssociatedAccessPolicy association = new AssociatedAccessPolicy(policyArn, scope, associatedAt, now);
        policies.add(association);
        policies.sort(Comparator.comparing(AssociatedAccessPolicy::policyArn));
        entries.put(prefix(cluster) + principal, new StoredEntry(stored.entry(), stored.principalId(),
                stored.clientRequestToken(), List.copyOf(policies)));
        return association;
    }

    public synchronized void disassociateAccessPolicy(Cluster cluster, String principal, String policyArn) {
        requireApiAccess(cluster);
        StoredEntry stored = stored(cluster, principal);
        List<AssociatedAccessPolicy> remaining = stored.policies().stream()
                .filter(policy -> !policy.policyArn().equals(policyArn)).toList();
        if (remaining.size() == stored.policies().size()) {
            throw new AwsException("ResourceNotFoundException",
                    "The access policy " + policyArn + " is not associated with " + principal, 404);
        }
        entries.put(prefix(cluster) + principal, new StoredEntry(stored.entry(), stored.principalId(),
                stored.clientRequestToken(), remaining));
    }

    public synchronized List<AssociatedAccessPolicy> listAssociatedAccessPolicies(Cluster cluster, String principal) {
        return stored(cluster, principal).policies();
    }

    public synchronized void delete(Cluster cluster, String principal) {
        requireApiAccess(cluster);
        stored(cluster, principal);
        entries.delete(prefix(cluster) + principal);
    }

    public synchronized Page list(Cluster cluster, Integer maxResults, String nextToken) {
        requireApiAuthenticationMode(cluster);
        int limit = maxResults == null ? 100 : maxResults;
        if (limit < 1 || limit > 100) {
            throw invalid("maxResults must be between 1 and 100");
        }
        String prefix = prefix(cluster);
        String after = "";
        if (nextToken != null) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8);
                if (!decoded.startsWith(prefix + "\n")) {
                    throw invalid("nextToken does not belong to this cluster");
                }
                after = decoded.substring(prefix.length() + 1);
                if (after.isBlank()) {
                    throw invalid("Invalid nextToken");
                }
            } catch (IllegalArgumentException exception) {
                throw invalid("Invalid nextToken");
            }
        }
        String cursor = after;
        List<String> remaining = entries.scan(key -> key.startsWith(prefix)).stream()
                .map(stored -> stored.entry().principalArn()).filter(principal -> principal.compareTo(cursor) > 0)
                .sorted(Comparator.naturalOrder()).toList();
        List<String> page = remaining.stream().limit(limit).toList();
        String token = remaining.size() > limit ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (prefix + "\n" + page.getLast()).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(page, token);
    }

    public synchronized void deleteClusterEntries(Cluster cluster) {
        String prefix = prefix(cluster);
        for (StoredEntry stored : entries.scan(key -> key.startsWith(prefix))) {
            entries.delete(prefix + stored.entry().principalArn());
        }
    }

    private static String prefix(Cluster cluster) {
        // A recreated cluster must not inherit access entries or pagination tokens from its predecessor.
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private StoredEntry stored(Cluster cluster, String principal) {
        requireApiAuthenticationMode(cluster);
        return entries.get(prefix(cluster) + principal)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Access entry not found", 404));
    }

    private static void validateStandardUsername(String username) {
        if (username.isBlank() || username.matches("^(system|eks|aws|amazon|iam):.*")) {
            throw invalid("Invalid username");
        }
        for (String placeholder : List.of("{{SessionName}}", "{{SessionNameRaw}}")) {
            int position = username.indexOf(placeholder);
            if (position >= 0 && !username.substring(0, position).contains(":")) {
                throw invalid("A session-name placeholder must be preceded by a colon");
            }
        }
    }

    private static void validateGroups(List<String> groups) {
        if (groups.stream().anyMatch(group -> group == null || group.isBlank())) {
            throw invalid("kubernetesGroups must contain nonempty strings");
        }
    }

    private static void validatePolicyArn(Cluster cluster, String policyArn) {
        String partition = cluster.getArn() == null ? "aws" : cluster.getArn().split(":", 6)[1];
        String prefix = "arn:" + partition + ":eks::aws:cluster-access-policy/";
        if (policyArn == null || !policyArn.startsWith(prefix)
                || !EksCatalogService.ACCESS_POLICIES.contains(policyArn.substring(prefix.length()))) {
            throw invalid("The specified policyArn is not a valid EKS access policy: " + policyArn);
        }
    }

    private static AccessScope normalizeScope(AccessScope scope) {
        if (scope == null || scope.type() == null) {
            throw invalid("accessScope.type is required");
        }
        List<String> namespaces = scope.namespaces() == null ? List.of() : scope.namespaces();
        switch (scope.type()) {
            case "cluster" -> {
                if (!namespaces.isEmpty()) {
                    throw invalid("namespaces cannot be specified for a cluster access scope");
                }
            }
            case "namespace" -> {
                if (namespaces.isEmpty() || namespaces.stream().anyMatch(ns -> ns == null || ns.isBlank())) {
                    throw invalid("A namespace access scope requires at least one namespace");
                }
            }
            default -> throw invalid("accessScope.type must be cluster or namespace");
        }
        return new AccessScope(scope.type(), List.copyOf(namespaces));
    }

    /** Reads stay available while the cluster is CREATING or UPDATING, as they do on EKS. */
    private static void requireApiAuthenticationMode(Cluster cluster) {
        if (cluster.getAccessConfig() == null || "CONFIG_MAP".equals(cluster.getAccessConfig().authenticationMode())) {
            throw new AwsException("InvalidRequestException", "Cluster authentication mode must be API or API_AND_CONFIG_MAP", 400);
        }
    }

    private static void requireApiAccess(Cluster cluster) {
        requireApiAuthenticationMode(cluster);
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}

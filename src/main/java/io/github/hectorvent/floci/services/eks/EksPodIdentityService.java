package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Association management metadata, not a Pod Identity credential agent. */
@ApplicationScoped
public class EksPodIdentityService {
    private final StorageBackend<String, StoredAssociation> storage;
    private final IamService iam;
    private final ObjectMapper mapper;

    @Inject
    public EksPodIdentityService(StorageFactory factory, IamService iam, ObjectMapper mapper) {
        this(factory.create("eks", "eks-pod-identity-associations.json",
                new TypeReference<Map<String, StoredAssociation>>() {}), iam, mapper);
    }

    EksPodIdentityService(StorageBackend<String, StoredAssociation> storage, IamService iam, ObjectMapper mapper) {
        this.storage = storage;
        this.iam = iam;
        this.mapper = mapper;
    }

    @RegisterForReflection
    public record CreateRequest(String namespace, String serviceAccount, String roleArn, Boolean disableSessionTags,
                                String targetRoleArn, String policy, Map<String, String> tags,
                                String clientRequestToken) {}

    @RegisterForReflection
    public record UpdateRequest(String roleArn, Boolean disableSessionTags, String targetRoleArn,
                                String policy, String clientRequestToken) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Association(String clusterName, String namespace, String serviceAccount, String roleArn,
                              String associationArn, String associationId, Map<String, String> tags,
                              double createdAt, double modifiedAt, boolean disableSessionTags,
                              String targetRoleArn, String externalId, String policy) {}

    @RegisterForReflection
    public record Summary(String clusterName, String namespace, String serviceAccount,
                          String associationArn, String associationId) {}

    @RegisterForReflection
    public record Replay(UpdateRequest request, Association response) {}

    @RegisterForReflection
    public record StoredAssociation(Association association, CreateRequest request, Association created,
                                    Map<String, Replay> updates) {}

    @RegisterForReflection
    public record Page(List<Summary> associations, String nextToken) {}

    public synchronized Association create(Cluster cluster, CreateRequest request) {
        requireActive(cluster);
        if (request == null) {
            throw invalid("Request body is required");
        }
        validateName(request.namespace(), 63, "namespace");
        validateName(request.serviceAccount(), 253, "serviceAccount");
        validateRole(cluster, request.roleArn(), true);
        validateTargetRole(cluster, request.targetRoleArn());
        validatePolicy(request.policy());
        validateToken(request.clientRequestToken());
        Map<String, String> tags = request.tags() == null ? Map.of() : request.tags();
        validateTags(tags);
        CreateRequest normalized = new CreateRequest(request.namespace(), request.serviceAccount(), request.roleArn(),
                Boolean.TRUE.equals(request.disableSessionTags()), request.targetRoleArn(), request.policy(),
                Map.copyOf(tags), request.clientRequestToken());
        List<StoredAssociation> existing = storage.scan(key -> key.startsWith(prefix(cluster)));
        for (StoredAssociation stored : existing) {
            if (request.clientRequestToken() != null
                    && request.clientRequestToken().equals(stored.request().clientRequestToken())) {
                if (!normalized.equals(stored.request())) {
                    throw invalid("clientRequestToken was already used with different parameters");
                }
                return stored.created();
            }
        }
        if (existing.stream().anyMatch(stored -> stored.association().namespace().equals(request.namespace())
                && stored.association().serviceAccount().equals(request.serviceAccount()))) {
            throw new AwsException("ResourceInUseException", "Service account already has an association", 409);
        }
        String id = "a-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String arn = clusterArn(cluster).replace(":cluster/", ":podidentityassociation/") + "/" + id;
        double now = Instant.now().toEpochMilli() / 1000.0;
        Association association = new Association(cluster.getName(), request.namespace(), request.serviceAccount(),
                request.roleArn(), arn, id, Map.copyOf(tags), now, now,
                Boolean.TRUE.equals(request.disableSessionTags()), request.targetRoleArn(),
                UUID.randomUUID().toString(), request.policy());
        storage.put(prefix(cluster) + id, new StoredAssociation(association, normalized, association, Map.of()));
        return association;
    }

    public synchronized Association describe(Cluster cluster, String id) {
        return stored(cluster, id).association();
    }

    public synchronized Association update(Cluster cluster, String id, UpdateRequest request) {
        requireActive(cluster);
        StoredAssociation stored = stored(cluster, id);
        if (request == null || (request.roleArn() == null && request.disableSessionTags() == null
                && request.targetRoleArn() == null && request.policy() == null)) {
            throw invalid("At least one mutable association property is required");
        }
        validateToken(request.clientRequestToken());
        if (request.clientRequestToken() != null && stored.updates().containsKey(request.clientRequestToken())) {
            Replay replay = stored.updates().get(request.clientRequestToken());
            if (!request.equals(replay.request())) {
                throw invalid("clientRequestToken was already used with different parameters");
            }
            return replay.response();
        }
        if (request.roleArn() != null) {
            validateRole(cluster, request.roleArn(), true);
        }
        validateTargetRole(cluster, request.targetRoleArn());
        validatePolicy(request.policy());
        Association old = stored.association();
        Association updated = new Association(old.clusterName(), old.namespace(), old.serviceAccount(),
                request.roleArn() == null ? old.roleArn() : request.roleArn(), old.associationArn(), old.associationId(),
                old.tags(), old.createdAt(), Instant.now().toEpochMilli() / 1000.0,
                request.disableSessionTags() == null ? old.disableSessionTags() : request.disableSessionTags(),
                request.targetRoleArn() == null ? old.targetRoleArn() : request.targetRoleArn(), old.externalId(),
                request.policy() == null ? old.policy() : request.policy());
        Map<String, Replay> updates = new HashMap<>(stored.updates());
        if (request.clientRequestToken() != null) {
            updates.put(request.clientRequestToken(), new Replay(request, updated));
        }
        storage.put(prefix(cluster) + id, new StoredAssociation(updated, stored.request(), stored.created(), updates));
        return updated;
    }

    public synchronized Association delete(Cluster cluster, String id) {
        Association association = describe(cluster, id);
        storage.delete(prefix(cluster) + id);
        return association;
    }

    public synchronized Page list(Cluster cluster, String namespace, String serviceAccount,
                                  Integer maxResults, String nextToken) {
        int limit = maxResults == null ? 100 : maxResults;
        if (limit < 1 || limit > 100) {
            throw invalid("maxResults must be between 1 and 100");
        }
        if (namespace != null) {
            validateName(namespace, 63, "namespace");
        }
        if (serviceAccount != null) {
            validateName(serviceAccount, 253, "serviceAccount");
        }
        String scope = prefix(cluster) + "\n" + Objects.toString(namespace, "") + "\n"
                + Objects.toString(serviceAccount, "") + "\n";
        String after = "";
        if (nextToken != null) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8);
                if (!decoded.startsWith(scope) || decoded.substring(scope.length()).isBlank()) {
                    throw invalid("nextToken does not belong to this cluster and filter");
                }
                after = decoded.substring(scope.length());
            } catch (IllegalArgumentException exception) {
                throw invalid("Invalid nextToken");
            }
        }
        String cursor = after;
        List<Association> remaining = storage.scan(key -> key.startsWith(prefix(cluster))).stream()
                .map(StoredAssociation::association)
                .filter(value -> namespace == null || namespace.equals(value.namespace()))
                .filter(value -> serviceAccount == null || serviceAccount.equals(value.serviceAccount()))
                .filter(value -> value.associationId().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(Association::associationId)).toList();
        List<Summary> page = remaining.stream().limit(limit).map(value -> new Summary(value.clusterName(),
                value.namespace(), value.serviceAccount(), value.associationArn(), value.associationId())).toList();
        String token = remaining.size() > limit ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (scope + page.getLast().associationId()).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(page, token);
    }

    public synchronized void tag(Cluster cluster, String id, Map<String, String> tags, List<String> removed) {
        StoredAssociation stored = stored(cluster, id);
        Association old = stored.association();
        Map<String, String> updated = new HashMap<>(old.tags());
        if (tags != null) {
            updated.putAll(tags);
        }
        if (removed != null) {
            removed.forEach(updated::remove);
        }
        validateTags(updated);
        Association association = new Association(old.clusterName(), old.namespace(), old.serviceAccount(),
                old.roleArn(), old.associationArn(), old.associationId(), Map.copyOf(updated), old.createdAt(),
                old.modifiedAt(), old.disableSessionTags(), old.targetRoleArn(), old.externalId(), old.policy());
        storage.put(prefix(cluster) + id,
                new StoredAssociation(association, stored.request(), stored.created(), stored.updates()));
    }

    public synchronized Optional<Association> findAssociation(Cluster cluster, String namespace,
                                                                         String serviceAccount) {
        return storage.scan(key -> key.startsWith(prefix(cluster))).stream()
                .map(StoredAssociation::association)
                .filter(value -> value.namespace().equals(namespace) && value.serviceAccount().equals(serviceAccount))
                .findFirst();
    }

    public synchronized void deleteClusterAssociations(Cluster cluster) {
        for (StoredAssociation stored : storage.scan(key -> key.startsWith(prefix(cluster)))) {
            storage.delete(prefix(cluster) + stored.association().associationId());
        }
    }

    private StoredAssociation stored(Cluster cluster, String id) {
        return storage.get(prefix(cluster) + id).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Pod identity association not found: " + id, 404));
    }

    private static String clusterArn(Cluster cluster) {
        return cluster.getArn() != null ? cluster.getArn()
                : "arn:aws:eks:us-east-1:" + Objects.toString(cluster.getAccountId(), "000000000000")
                        + ":cluster/" + cluster.getName();
    }

    private static String prefix(Cluster cluster) {
        return clusterArn(cluster) + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private void validateRole(Cluster cluster, String role, boolean sameAccount) {
        String[] clusterArn = clusterArn(cluster).split(":", 6);
        String[] arn = role == null ? new String[0] : role.split(":", 6);
        if (arn.length != 6 || !"arn".equals(arn[0]) || !clusterArn[1].equals(arn[1])
                || !"iam".equals(arn[2]) || !arn[3].isEmpty() || !arn[4].matches("[0-9]{12}")
                || !arn[5].startsWith("role/") || role.endsWith("/")
                || (sameAccount && arn[5].startsWith("role/aws-service-role/"))
                || (sameAccount && cluster.getArn() != null && !clusterArn[4].equals(arn[4]))) {
            throw invalid("roleArn must identify an IAM role" + (sameAccount ? " in the cluster account" : ""));
        }
        String name = role.substring(role.lastIndexOf('/') + 1);
        if (sameAccount && iam.findRole(arn[4], name).filter(value -> role.equals(value.getArn())).isEmpty()) {
            throw invalid("IAM role does not exist");
        }
    }

    private void validateTargetRole(Cluster cluster, String role) {
        if (role != null) {
            validateRole(cluster, role, false);
        }
    }

    private void validatePolicy(String policy) {
        if (policy != null) {
            try {
                JsonNode document = mapper.readTree(policy);
                if (document == null || !document.isObject() || !document.has("Statement")) {
                    throw invalid("policy must be a JSON policy document");
                }
            } catch (JsonProcessingException exception) {
                throw invalid("policy must be a JSON policy document");
            }
        }
    }

    private static void validateName(String value, int max, String field) {
        String label = "[a-z0-9]([-a-z0-9]*[a-z0-9])?";
        String pattern = "serviceAccount".equals(field) ? label + "(\\." + label + ")*" : label;
        if (value == null || value.length() > max || !value.matches(pattern)) {
            throw invalid("Invalid " + field);
        }
    }

    private static void validateToken(String token) {
        if (token != null && (token.isBlank() || token.length() > 64)) {
            throw invalid("clientRequestToken must contain between 1 and 64 characters");
        }
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags.size() > 50 || tags.entrySet().stream().anyMatch(tag -> tag.getKey() == null
                || tag.getKey().isEmpty() || tag.getKey().length() > 128
                || tag.getKey().startsWith("aws:") || tag.getKey().startsWith("AWS:") || tag.getValue() == null
                || tag.getValue().length() > 256)) {
            throw invalid("Invalid association tags");
        }
    }

    private static void requireActive(Cluster cluster) {
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}

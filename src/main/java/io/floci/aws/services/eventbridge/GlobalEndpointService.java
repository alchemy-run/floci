package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.model.GlobalEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * EventBridge global endpoints (CreateEndpoint and its siblings). Floci runs one Region at a time
 * with no Route 53 health checks, so an endpoint is ACTIVE as soon as it is created and PutEvents
 * through it reaches its primary event bus, as on AWS while the primary is healthy.
 */
@ApplicationScoped
public class GlobalEndpointService {

    private static final Pattern NAME = Pattern.compile("[.\\-_A-Za-z0-9]{1,64}");
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, GlobalEndpoint> store;
    private final RegionResolver regionResolver;

    @Inject
    public GlobalEndpointService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("eventbridge", "eventbridge-endpoints.json",
                new TypeReference<Map<String, GlobalEndpoint>>() {}), regionResolver);
    }

    GlobalEndpointService(StorageBackend<String, GlobalEndpoint> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public synchronized GlobalEndpoint create(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        validateName(name);
        requireRouting(request.path("RoutingConfig"));
        requireEventBuses(request.path("EventBuses"));
        String key = key(region, name);
        if (store.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Endpoint " + name + " already exists.", 400);
        }
        GlobalEndpoint endpoint = new GlobalEndpoint();
        endpoint.setName(name);
        endpoint.setArn(regionResolver.buildArn("events", region, "endpoint/" + name));
        String id = randomLabel(5) + "." + randomLabel(3);
        endpoint.setEndpointId(id);
        endpoint.setEndpointUrl("https://" + id + ".endpoint.events.amazonaws.com");
        endpoint.setDescription(textOrNull(request.path("Description")));
        endpoint.setRoutingConfig(request.path("RoutingConfig").toString());
        endpoint.setReplicationConfig(request.has("ReplicationConfig")
                ? request.get("ReplicationConfig").toString() : "{\"State\":\"ENABLED\"}");
        endpoint.setEventBuses(request.path("EventBuses").toString());
        endpoint.setRoleArn(textOrNull(request.path("RoleArn")));
        endpoint.setState("ACTIVE");
        Instant now = Instant.now();
        endpoint.setCreationTime(now);
        endpoint.setLastModifiedTime(now);
        store.put(key, endpoint);
        return endpoint;
    }

    public GlobalEndpoint describe(String name, String region) {
        return store.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Endpoint " + name + " does not exist.", 400));
    }

    public synchronized GlobalEndpoint update(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        GlobalEndpoint endpoint = describe(name, region);
        if (request.has("Description")) {
            endpoint.setDescription(textOrNull(request.get("Description")));
        }
        if (request.has("RoutingConfig")) {
            requireRouting(request.get("RoutingConfig"));
            endpoint.setRoutingConfig(request.get("RoutingConfig").toString());
        }
        if (request.has("ReplicationConfig")) {
            endpoint.setReplicationConfig(request.get("ReplicationConfig").toString());
        }
        if (request.has("EventBuses")) {
            requireEventBuses(request.get("EventBuses"));
            endpoint.setEventBuses(request.get("EventBuses").toString());
        }
        if (request.has("RoleArn")) {
            endpoint.setRoleArn(textOrNull(request.get("RoleArn")));
        }
        endpoint.setLastModifiedTime(Instant.now());
        store.put(key(region, name), endpoint);
        return endpoint;
    }

    public synchronized void delete(String name, String region) {
        describe(name, region);
        store.delete(key(region, name));
    }

    public List<GlobalEndpoint> list(String namePrefix, String region) {
        String prefix = region + "/";
        return store.scan(k -> k.startsWith(prefix)).stream()
                .filter(e -> namePrefix == null || namePrefix.isEmpty() || e.getName().startsWith(namePrefix))
                .sorted(Comparator.comparing(GlobalEndpoint::getName))
                .toList();
    }

    /**
     * The endpoint PutEvents names by its EndpointId. An endpoint id is global: a publisher signing
     * for the secondary Region, as every publisher does after a failover, still reaches it.
     */
    public GlobalEndpoint byId(String endpointId) {
        return store.scan(k -> true).stream()
                .filter(e -> endpointId.equals(e.getEndpointId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Endpoint " + endpointId + " does not exist.", 400));
    }

    private static void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'name' failed to satisfy constraint: "
                            + "Member must satisfy regular expression pattern: [\\.\\-_A-Za-z0-9]+", 400);
        }
    }

    private static void requireRouting(JsonNode routing) {
        JsonNode failover = routing.path("FailoverConfig");
        if (failover.path("Primary").path("HealthCheck").asText("").isEmpty()
                || failover.path("Secondary").path("Route").asText("").isEmpty()) {
            throw new AwsException("ValidationException",
                    "RoutingConfig.FailoverConfig requires Primary.HealthCheck and Secondary.Route.", 400);
        }
    }

    private static void requireEventBuses(JsonNode buses) {
        if (!buses.isArray() || buses.size() != 2) {
            throw new AwsException("ValidationException",
                    "EventBuses must name exactly two event buses, the primary and the secondary.", 400);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static String randomLabel(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return out.toString();
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }
}

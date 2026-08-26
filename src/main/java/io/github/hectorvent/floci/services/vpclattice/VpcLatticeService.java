package io.github.hectorvent.floci.services.vpclattice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.vpclattice.model.AccessLogSubscription;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeListener;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeRule;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeService;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeTarget;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeTargetGroup;
import io.github.hectorvent.floci.services.vpclattice.model.ServiceAssociation;
import io.github.hectorvent.floci.services.vpclattice.model.ServiceNetwork;
import io.github.hectorvent.floci.services.vpclattice.model.VpcAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VPC Lattice restJson1 — service networks, services, listeners, rules,
 * target groups, and associations used by Alchemy. Tag APIs share
 * {@code /tags/{arn}} via {@link TagHandler} using ARN service
 * {@code vpc-lattice}.
 */
@ApplicationScoped
public class VpcLatticeService implements TagHandler, Resettable {

    static final String SERVICE = "vpc-lattice";

    private final StorageBackend<String, ServiceNetwork> networks;
    private final StorageBackend<String, LatticeService> services;
    private final StorageBackend<String, LatticeListener> listeners;
    private final StorageBackend<String, LatticeRule> rules;
    private final StorageBackend<String, LatticeTargetGroup> targetGroups;
    private final StorageBackend<String, ServiceAssociation> serviceAssociations;
    private final StorageBackend<String, VpcAssociation> vpcAssociations;
    private final StorageBackend<String, AccessLogSubscription> accessLogs;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public VpcLatticeService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(SERVICE, "vpc-lattice-networks.json",
                        new TypeReference<Map<String, ServiceNetwork>>() {
                        }),
                storageFactory.create(SERVICE, "vpc-lattice-services.json",
                        new TypeReference<Map<String, LatticeService>>() {
                        }),
                storageFactory.create(SERVICE, "vpc-lattice-listeners.json",
                        new TypeReference<Map<String, LatticeListener>>() {
                        }),
                storageFactory.create(SERVICE, "vpc-lattice-rules.json",
                        new TypeReference<Map<String, LatticeRule>>() {
                        }),
                storageFactory.create(SERVICE, "vpc-lattice-target-groups.json",
                        new TypeReference<Map<String, LatticeTargetGroup>>() {
                        }),
                storageFactory.create(SERVICE, "vpc-lattice-service-associations.json",
                        new TypeReference<Map<String, ServiceAssociation>>() {
                        }),
                storageFactory.create(SERVICE, "vpc-lattice-vpc-associations.json",
                        new TypeReference<Map<String, VpcAssociation>>() {
                        }),
                storageFactory.create(SERVICE, "vpc-lattice-access-logs.json",
                        new TypeReference<Map<String, AccessLogSubscription>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    VpcLatticeService(
            StorageBackend<String, ServiceNetwork> networks,
            StorageBackend<String, LatticeService> services,
            StorageBackend<String, LatticeListener> listeners,
            StorageBackend<String, LatticeRule> rules,
            StorageBackend<String, LatticeTargetGroup> targetGroups,
            StorageBackend<String, ServiceAssociation> serviceAssociations,
            StorageBackend<String, VpcAssociation> vpcAssociations,
            StorageBackend<String, AccessLogSubscription> accessLogs,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.networks = networks;
        this.services = services;
        this.listeners = listeners;
        this.rules = rules;
        this.targetGroups = targetGroups;
        this.serviceAssociations = serviceAssociations;
        this.vpcAssociations = vpcAssociations;
        this.accessLogs = accessLogs;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void clear() {
        networks.clear();
        services.clear();
        listeners.clear();
        rules.clear();
        targetGroups.clear();
        serviceAssociations.clear();
        vpcAssociations.clear();
        accessLogs.clear();
    }

    public synchronized ServiceNetwork createServiceNetwork(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        for (ServiceNetwork existing : networksIn(region)) {
            if (name.equals(existing.getName())) {
                throw conflict("A service network named '" + name + "' already exists.",
                        existing.getId(), "SERVICE_NETWORK");
            }
        }
        String now = now();
        String id = "sn-" + hexId(17);
        ServiceNetwork network = new ServiceNetwork();
        network.setId(id);
        network.setArn(arn(region, "servicenetwork/" + id));
        network.setName(name);
        network.setAuthType(textOr(request, "authType", "NONE"));
        network.setRegion(region);
        network.setCreatedAt(now);
        network.setLastUpdatedAt(now);
        network.setTags(readTags(request.get("tags")));
        networks.put(key(region, id), network);
        return network;
    }

    public ServiceNetwork getServiceNetwork(String region, String identifier) {
        return requireNetwork(region, identifier);
    }

    public List<ServiceNetwork> listServiceNetworks(String region) {
        List<ServiceNetwork> items = networksIn(region);
        items.sort(Comparator.comparing(ServiceNetwork::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized ServiceNetwork updateServiceNetwork(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        ServiceNetwork network = requireNetwork(region, identifier);
        if (request.hasNonNull("authType")) {
            network.setAuthType(request.get("authType").asText());
        }
        network.setLastUpdatedAt(now());
        networks.put(key(region, network.getId()), network);
        return network;
    }

    public synchronized void deleteServiceNetwork(String region, String identifier) {
        ServiceNetwork network = requireNetwork(region, identifier);
        long remaining = serviceAssociationsIn(region).stream()
                .filter(a -> network.getId().equals(a.getServiceNetworkId()))
                .count()
                + vpcAssociationsIn(region).stream()
                .filter(a -> network.getId().equals(a.getServiceNetworkId()))
                .count()
                + accessLogsIn(region).stream()
                .filter(sub -> network.getId().equals(sub.getResourceId()))
                .count();
        if (remaining > 0) {
            throw conflict("Service network has existing associations.", network.getId(), "SERVICE_NETWORK");
        }
        networks.delete(key(region, network.getId()));
    }

    public synchronized AccessLogSubscription createAccessLogSubscription(String region, JsonNode request) {
        requireObject(request, "Request body");
        LogResource resource = requireLogResource(region, requireText(request, "resourceIdentifier"));
        String destinationArn = normalizeDestinationArn(requireText(request, "destinationArn"));
        String destinationType = destinationType(destinationArn);
        String logType = textOr(request, "serviceNetworkLogType", "SERVICE");
        if (!"SERVICE".equals(logType) && !"RESOURCE".equals(logType)) {
            throw validation("serviceNetworkLogType must be SERVICE or RESOURCE.");
        }
        for (AccessLogSubscription existing : accessLogsIn(region)) {
            if (resource.id().equals(existing.getResourceId())
                    && destinationType.equals(destinationType(existing.getDestinationArn()))) {
                throw conflict(
                        "An access log subscription already exists for this destination type.",
                        existing.getId(),
                        "ACCESS_LOG_SUBSCRIPTION");
            }
        }
        String now = now();
        String id = "als-" + hexId(17);
        AccessLogSubscription subscription = new AccessLogSubscription();
        subscription.setId(id);
        subscription.setArn(arn(region, "accesslogsubscription/" + id));
        subscription.setResourceId(resource.id());
        subscription.setResourceArn(resource.arn());
        subscription.setDestinationArn(destinationArn);
        subscription.setServiceNetworkLogType(logType);
        subscription.setCreatedAt(now);
        subscription.setLastUpdatedAt(now);
        subscription.setTags(readTags(request.get("tags")));
        accessLogs.put(key(region, id), subscription);
        return subscription;
    }

    public AccessLogSubscription getAccessLogSubscription(String region, String identifier) {
        return requireAccessLog(region, identifier);
    }

    public List<AccessLogSubscription> listAccessLogSubscriptions(String region, String resourceIdentifier) {
        if (resourceIdentifier == null || resourceIdentifier.isBlank()) {
            throw validation("resourceIdentifier is required.");
        }
        LogResource resource = requireLogResource(region, resourceIdentifier);
        List<AccessLogSubscription> items = new ArrayList<>();
        for (AccessLogSubscription subscription : accessLogsIn(region)) {
            if (resource.id().equals(subscription.getResourceId())) {
                items.add(subscription);
            }
        }
        items.sort(Comparator.comparing(AccessLogSubscription::getArn, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized AccessLogSubscription updateAccessLogSubscription(
            String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        AccessLogSubscription subscription = requireAccessLog(region, identifier);
        String destinationArn = normalizeDestinationArn(requireText(request, "destinationArn"));
        if (!destinationType(subscription.getDestinationArn()).equals(destinationType(destinationArn))) {
            throw conflict(
                    "Cannot change the destination type of an access log subscription.",
                    subscription.getId(),
                    "ACCESS_LOG_SUBSCRIPTION");
        }
        subscription.setDestinationArn(destinationArn);
        subscription.setLastUpdatedAt(now());
        accessLogs.put(key(region, subscription.getId()), subscription);
        return subscription;
    }

    public synchronized void deleteAccessLogSubscription(String region, String identifier) {
        AccessLogSubscription subscription = requireAccessLog(region, identifier);
        accessLogs.delete(key(region, subscription.getId()));
    }

    public synchronized LatticeService createService(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        for (LatticeService existing : servicesIn(region)) {
            if (name.equals(existing.getName())) {
                throw conflict("A service named '" + name + "' already exists.",
                        existing.getId(), "SERVICE");
            }
        }
        String now = now();
        String id = "svc-" + hexId(17);
        LatticeService service = new LatticeService();
        service.setId(id);
        service.setArn(arn(region, "service/" + id));
        service.setName(name);
        service.setAuthType(textOr(request, "authType", "NONE"));
        service.setStatus("ACTIVE");
        service.setCustomDomainName(textOrNull(request, "customDomainName"));
        service.setCertificateArn(textOrNull(request, "certificateArn"));
        if (request.hasNonNull("idleTimeoutSeconds")) {
            service.setIdleTimeoutSeconds(request.get("idleTimeoutSeconds").asInt());
        }
        service.setDnsDomainName(id + "." + region + ".vpc-lattice-svcs.amazonaws.com");
        service.setRegion(region);
        service.setCreatedAt(now);
        service.setLastUpdatedAt(now);
        service.setTags(readTags(request.get("tags")));
        services.put(key(region, id), service);
        return service;
    }

    public LatticeService getService(String region, String identifier) {
        return requireService(region, identifier);
    }

    public List<LatticeService> listServices(String region) {
        List<LatticeService> items = servicesIn(region);
        items.sort(Comparator.comparing(LatticeService::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized LatticeService updateService(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        LatticeService service = requireService(region, identifier);
        if (request.hasNonNull("authType")) {
            service.setAuthType(request.get("authType").asText());
        }
        if (request.has("certificateArn")) {
            service.setCertificateArn(textOrNull(request, "certificateArn"));
        }
        if (request.has("idleTimeoutSeconds")) {
            service.setIdleTimeoutSeconds(request.get("idleTimeoutSeconds").isNull()
                    ? null
                    : request.get("idleTimeoutSeconds").asInt());
        }
        service.setLastUpdatedAt(now());
        services.put(key(region, service.getId()), service);
        return service;
    }

    public synchronized LatticeService deleteService(String region, String identifier) {
        LatticeService service = requireService(region, identifier);
        boolean hasListeners = listenersIn(region).stream()
                .anyMatch(l -> service.getId().equals(l.getServiceId()));
        if (hasListeners) {
            throw conflict("Service has existing listeners.", service.getId(), "SERVICE");
        }
        boolean associated = serviceAssociationsIn(region).stream()
                .anyMatch(a -> service.getId().equals(a.getServiceId()));
        if (associated) {
            throw conflict("Service has existing associations.", service.getId(), "SERVICE");
        }
        services.delete(key(region, service.getId()));
        service.setStatus("DELETE_IN_PROGRESS");
        return service;
    }

    public synchronized ServiceAssociation createServiceAssociation(String region, JsonNode request) {
        requireObject(request, "Request body");
        LatticeService service = requireService(region, requireText(request, "serviceIdentifier"));
        ServiceNetwork network = requireNetwork(region, requireText(request, "serviceNetworkIdentifier"));
        for (ServiceAssociation existing : serviceAssociationsIn(region)) {
            if (service.getId().equals(existing.getServiceId())
                    && network.getId().equals(existing.getServiceNetworkId())) {
                throw conflict("Service is already associated with the service network.",
                        existing.getId(), "SERVICE_NETWORK_SERVICE_ASSOCIATION");
            }
        }
        String now = now();
        String id = "snsa-" + hexId(17);
        ServiceAssociation association = new ServiceAssociation();
        association.setId(id);
        association.setArn(arn(region, "servicenetworkserviceassociation/" + id));
        association.setStatus("ACTIVE");
        association.setCreatedBy(accountId());
        association.setServiceId(service.getId());
        association.setServiceName(service.getName());
        association.setServiceArn(service.getArn());
        association.setServiceNetworkId(network.getId());
        association.setServiceNetworkName(network.getName());
        association.setServiceNetworkArn(network.getArn());
        association.setDnsName(service.getId() + "." + network.getId()
                + "." + region + ".vpc-lattice-svcs.amazonaws.com");
        association.setCustomDomainName(service.getCustomDomainName());
        association.setRegion(region);
        association.setCreatedAt(now);
        association.setTags(readTags(request.get("tags")));
        serviceAssociations.put(key(region, id), association);
        return association;
    }

    public ServiceAssociation getServiceAssociation(String region, String identifier) {
        return requireServiceAssociation(region, identifier);
    }

    public List<ServiceAssociation> listServiceAssociations(
            String region, String serviceNetworkIdentifier, String serviceIdentifier) {
        String networkId = serviceNetworkIdentifier == null || serviceNetworkIdentifier.isBlank()
                ? null
                : requireNetwork(region, serviceNetworkIdentifier).getId();
        String serviceId = serviceIdentifier == null || serviceIdentifier.isBlank()
                ? null
                : requireService(region, serviceIdentifier).getId();
        List<ServiceAssociation> items = new ArrayList<>();
        for (ServiceAssociation association : serviceAssociationsIn(region)) {
            if (networkId != null && !networkId.equals(association.getServiceNetworkId())) {
                continue;
            }
            if (serviceId != null && !serviceId.equals(association.getServiceId())) {
                continue;
            }
            items.add(association);
        }
        items.sort(Comparator.comparing(ServiceAssociation::getId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized ServiceAssociation deleteServiceAssociation(String region, String identifier) {
        ServiceAssociation association = requireServiceAssociation(region, identifier);
        serviceAssociations.delete(key(region, association.getId()));
        association.setStatus("DELETE_IN_PROGRESS");
        return association;
    }

    public synchronized VpcAssociation createVpcAssociation(String region, JsonNode request) {
        requireObject(request, "Request body");
        ServiceNetwork network = requireNetwork(region, requireText(request, "serviceNetworkIdentifier"));
        String vpcId = requireText(request, "vpcIdentifier");
        for (VpcAssociation existing : vpcAssociationsIn(region)) {
            if (network.getId().equals(existing.getServiceNetworkId()) && vpcId.equals(existing.getVpcId())) {
                throw conflict("VPC is already associated with the service network.",
                        existing.getId(), "SERVICE_NETWORK_VPC_ASSOCIATION");
            }
        }
        String now = now();
        String id = "snva-" + hexId(17);
        VpcAssociation association = new VpcAssociation();
        association.setId(id);
        association.setArn(arn(region, "servicenetworkvpcassociation/" + id));
        association.setStatus("ACTIVE");
        association.setCreatedBy(accountId());
        association.setServiceNetworkId(network.getId());
        association.setServiceNetworkName(network.getName());
        association.setServiceNetworkArn(network.getArn());
        association.setVpcId(vpcId);
        association.setSecurityGroupIds(readStringList(request.get("securityGroupIds")));
        association.setRegion(region);
        association.setCreatedAt(now);
        association.setTags(readTags(request.get("tags")));
        vpcAssociations.put(key(region, id), association);
        return association;
    }

    public VpcAssociation getVpcAssociation(String region, String identifier) {
        return requireVpcAssociation(region, identifier);
    }

    public List<VpcAssociation> listVpcAssociations(String region, String serviceNetworkIdentifier, String vpcId) {
        String networkId = serviceNetworkIdentifier == null || serviceNetworkIdentifier.isBlank()
                ? null
                : requireNetwork(region, serviceNetworkIdentifier).getId();
        List<VpcAssociation> items = new ArrayList<>();
        for (VpcAssociation association : vpcAssociationsIn(region)) {
            if (networkId != null && !networkId.equals(association.getServiceNetworkId())) {
                continue;
            }
            if (vpcId != null && !vpcId.isBlank() && !vpcId.equals(association.getVpcId())) {
                continue;
            }
            items.add(association);
        }
        items.sort(Comparator.comparing(VpcAssociation::getId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized VpcAssociation deleteVpcAssociation(String region, String identifier) {
        VpcAssociation association = requireVpcAssociation(region, identifier);
        vpcAssociations.delete(key(region, association.getId()));
        association.setStatus("DELETE_IN_PROGRESS");
        return association;
    }

    public AuthPolicyView getAuthPolicy(String region, String resourceIdentifier) {
        ServiceNetwork network = findNetwork(region, resourceIdentifier);
        if (network != null) {
            return new AuthPolicyView(network.getAuthPolicy(),
                    "AWS_IAM".equals(network.getAuthType()) ? "Active" : "Inactive",
                    network.getAuthPolicyCreatedAt(), network.getAuthPolicyUpdatedAt());
        }
        LatticeService service = findService(region, resourceIdentifier);
        if (service != null) {
            return new AuthPolicyView(service.getAuthPolicy(),
                    "AWS_IAM".equals(service.getAuthType()) ? "Active" : "Inactive",
                    service.getAuthPolicyCreatedAt(), service.getAuthPolicyUpdatedAt());
        }
        throw notFound(resourceIdentifier, "SERVICE_NETWORK");
    }

    public synchronized AuthPolicyView putAuthPolicy(String region, String resourceIdentifier, JsonNode request) {
        String policy = readPolicy(request);
        String timestamp = now();
        ServiceNetwork network = findNetwork(region, resourceIdentifier);
        if (network != null) {
            if (network.getAuthPolicyCreatedAt() == null) {
                network.setAuthPolicyCreatedAt(timestamp);
            }
            network.setAuthPolicy(policy);
            network.setAuthPolicyUpdatedAt(timestamp);
            networks.put(key(region, network.getId()), network);
            return new AuthPolicyView(policy,
                    "AWS_IAM".equals(network.getAuthType()) ? "Active" : "Inactive",
                    network.getAuthPolicyCreatedAt(), network.getAuthPolicyUpdatedAt());
        }
        LatticeService service = findService(region, resourceIdentifier);
        if (service != null) {
            if (service.getAuthPolicyCreatedAt() == null) {
                service.setAuthPolicyCreatedAt(timestamp);
            }
            service.setAuthPolicy(policy);
            service.setAuthPolicyUpdatedAt(timestamp);
            services.put(key(region, service.getId()), service);
            return new AuthPolicyView(policy,
                    "AWS_IAM".equals(service.getAuthType()) ? "Active" : "Inactive",
                    service.getAuthPolicyCreatedAt(), service.getAuthPolicyUpdatedAt());
        }
        throw notFound(resourceIdentifier, "SERVICE_NETWORK");
    }

    public synchronized void deleteAuthPolicy(String region, String resourceIdentifier) {
        ServiceNetwork network = findNetwork(region, resourceIdentifier);
        if (network != null) {
            network.setAuthPolicy(null);
            network.setAuthPolicyCreatedAt(null);
            network.setAuthPolicyUpdatedAt(null);
            networks.put(key(region, network.getId()), network);
            return;
        }
        LatticeService service = findService(region, resourceIdentifier);
        if (service != null) {
            service.setAuthPolicy(null);
            service.setAuthPolicyCreatedAt(null);
            service.setAuthPolicyUpdatedAt(null);
            services.put(key(region, service.getId()), service);
            return;
        }
        throw notFound(resourceIdentifier, "SERVICE_NETWORK");
    }

    public String getResourcePolicy(String region, String resourceArn) {
        ServiceNetwork network = findNetwork(region, resourceArn);
        if (network != null) {
            return network.getResourcePolicy();
        }
        LatticeService service = findService(region, resourceArn);
        if (service != null) {
            return service.getResourcePolicy();
        }
        throw notFound(resourceArn, "SERVICE_NETWORK");
    }

    public synchronized void putResourcePolicy(String region, String resourceArn, JsonNode request) {
        String policy = readPolicy(request);
        ServiceNetwork network = findNetwork(region, resourceArn);
        if (network != null) {
            network.setResourcePolicy(policy);
            networks.put(key(region, network.getId()), network);
            return;
        }
        LatticeService service = findService(region, resourceArn);
        if (service != null) {
            service.setResourcePolicy(policy);
            services.put(key(region, service.getId()), service);
            return;
        }
        throw notFound(resourceArn, "SERVICE_NETWORK");
    }

    public synchronized void deleteResourcePolicy(String region, String resourceArn) {
        ServiceNetwork network = findNetwork(region, resourceArn);
        if (network != null) {
            network.setResourcePolicy(null);
            networks.put(key(region, network.getId()), network);
            return;
        }
        LatticeService service = findService(region, resourceArn);
        if (service != null) {
            service.setResourcePolicy(null);
            services.put(key(region, service.getId()), service);
            return;
        }
        throw notFound(resourceArn, "SERVICE_NETWORK");
    }

    public synchronized LatticeTargetGroup createTargetGroup(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String type = requireText(request, "type");
        for (LatticeTargetGroup existing : targetGroupsIn(region)) {
            if (name.equals(existing.getName())) {
                throw conflict("A target group named '" + name + "' already exists.",
                        existing.getId(), "TARGET_GROUP");
            }
        }
        String now = now();
        String id = "tg-" + hexId(17);
        LatticeTargetGroup group = new LatticeTargetGroup();
        group.setId(id);
        group.setArn(arn(region, "targetgroup/" + id));
        group.setName(name);
        group.setType(type);
        group.setStatus("ACTIVE");
        group.setConfig(copyObject(request.get("config")));
        group.setRegion(region);
        group.setCreatedAt(now);
        group.setLastUpdatedAt(now);
        group.setTags(readTags(request.get("tags")));
        targetGroups.put(key(region, id), group);
        return group;
    }

    public LatticeTargetGroup getTargetGroup(String region, String identifier) {
        return requireTargetGroup(region, identifier);
    }

    public List<LatticeTargetGroup> listTargetGroups(String region, String vpcIdentifier, String type) {
        List<LatticeTargetGroup> items = new ArrayList<>();
        for (LatticeTargetGroup group : targetGroupsIn(region)) {
            if (type != null && !type.isBlank() && !type.equals(group.getType())) {
                continue;
            }
            if (vpcIdentifier != null && !vpcIdentifier.isBlank()) {
                JsonNode config = group.getConfig();
                String vpc = config != null && config.hasNonNull("vpcIdentifier")
                        ? config.get("vpcIdentifier").asText()
                        : null;
                if (!vpcIdentifier.equals(vpc)) {
                    continue;
                }
            }
            items.add(group);
        }
        items.sort(Comparator.comparing(LatticeTargetGroup::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized LatticeTargetGroup updateTargetGroup(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        LatticeTargetGroup group = requireTargetGroup(region, identifier);
        if (!request.hasNonNull("healthCheck")) {
            throw validation("healthCheck is required.");
        }
        ObjectNode config = group.getConfig() != null && group.getConfig().isObject()
                ? ((ObjectNode) group.getConfig()).deepCopy()
                : objectMapper.createObjectNode();
        config.set("healthCheck", request.get("healthCheck").deepCopy());
        group.setConfig(config);
        group.setLastUpdatedAt(now());
        targetGroups.put(key(region, group.getId()), group);
        return group;
    }

    public synchronized LatticeTargetGroup deleteTargetGroup(String region, String identifier) {
        LatticeTargetGroup group = requireTargetGroup(region, identifier);
        boolean inUse = rulesIn(region).stream().anyMatch(rule -> referencesTargetGroup(rule, group.getId()))
                || listenersIn(region).stream().anyMatch(listener -> referencesTargetGroup(listener, group.getId()));
        if (inUse) {
            throw conflict("Target group is in use by a listener or rule.", group.getId(), "TARGET_GROUP");
        }
        targetGroups.delete(key(region, group.getId()));
        group.setStatus("DELETE_IN_PROGRESS");
        return group;
    }

    public synchronized ObjectNode registerTargets(String region, String identifier, JsonNode request) {
        LatticeTargetGroup group = requireTargetGroup(region, identifier);
        ArrayNode successful = objectMapper.createArrayNode();
        ArrayNode unsuccessful = objectMapper.createArrayNode();
        JsonNode targetsNode = request == null ? null : request.get("targets");
        if (targetsNode == null || !targetsNode.isArray() || targetsNode.isEmpty()) {
            throw validation("targets is required.");
        }
        List<LatticeTarget> current = new ArrayList<>(group.getTargets());
        for (JsonNode node : targetsNode) {
            if (node == null || !node.hasNonNull("id")) {
                ObjectNode failure = unsuccessful.addObject();
                failure.put("failureCode", "InvalidParameter");
                failure.put("failureMessage", "Target id is required.");
                continue;
            }
            String targetId = node.get("id").asText();
            Integer port = node.hasNonNull("port") ? node.get("port").asInt() : null;
            LatticeTarget target = new LatticeTarget(targetId, port, "HEALTHY");
            current.removeIf(existing -> existing.key().equals(target.key()));
            current.add(target);
            ObjectNode ok = successful.addObject();
            ok.put("id", targetId);
            if (port != null) {
                ok.put("port", port);
            }
        }
        group.setTargets(current);
        group.setLastUpdatedAt(now());
        targetGroups.put(key(region, group.getId()), group);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("successful", successful);
        response.set("unsuccessful", unsuccessful);
        return response;
    }

    public synchronized ObjectNode deregisterTargets(String region, String identifier, JsonNode request) {
        LatticeTargetGroup group = requireTargetGroup(region, identifier);
        ArrayNode successful = objectMapper.createArrayNode();
        ArrayNode unsuccessful = objectMapper.createArrayNode();
        JsonNode targetsNode = request == null ? null : request.get("targets");
        if (targetsNode == null || !targetsNode.isArray() || targetsNode.isEmpty()) {
            throw validation("targets is required.");
        }
        List<LatticeTarget> current = new ArrayList<>(group.getTargets());
        for (JsonNode node : targetsNode) {
            if (node == null || !node.hasNonNull("id")) {
                continue;
            }
            String targetId = node.get("id").asText();
            Integer port = node.hasNonNull("port") ? node.get("port").asInt() : null;
            String targetKey = targetId + "#" + (port == null ? "" : port);
            current.removeIf(existing -> existing.key().equals(targetKey)
                    || (port == null && targetId.equals(existing.getId())));
            ObjectNode ok = successful.addObject();
            ok.put("id", targetId);
            if (port != null) {
                ok.put("port", port);
            }
        }
        group.setTargets(current);
        group.setLastUpdatedAt(now());
        targetGroups.put(key(region, group.getId()), group);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("successful", successful);
        response.set("unsuccessful", unsuccessful);
        return response;
    }

    public List<LatticeTarget> listTargets(String region, String identifier) {
        return new ArrayList<>(requireTargetGroup(region, identifier).getTargets());
    }

    public synchronized LatticeListener createListener(String region, String serviceIdentifier, JsonNode request) {
        requireObject(request, "Request body");
        LatticeService service = requireService(region, serviceIdentifier);
        String name = requireText(request, "name");
        String protocol = requireText(request, "protocol");
        for (LatticeListener existing : listenersFor(region, service.getId())) {
            if (name.equals(existing.getName())) {
                throw conflict("A listener named '" + name + "' already exists.",
                        existing.getId(), "LISTENER");
            }
        }
        int port = request.hasNonNull("port")
                ? request.get("port").asInt()
                : defaultPort(protocol);
        JsonNode defaultAction = request.get("defaultAction");
        if (defaultAction == null || defaultAction.isNull()) {
            throw validation("defaultAction is required.");
        }
        String now = now();
        String id = "listener-" + hexId(17);
        LatticeListener listener = new LatticeListener();
        listener.setId(id);
        listener.setArn(arn(region, "service/" + service.getId() + "/listener/" + id));
        listener.setName(name);
        listener.setProtocol(protocol);
        listener.setPort(port);
        listener.setServiceId(service.getId());
        listener.setServiceArn(service.getArn());
        listener.setDefaultAction(defaultAction.deepCopy());
        listener.setRegion(region);
        listener.setCreatedAt(now);
        listener.setLastUpdatedAt(now);
        listener.setTags(readTags(request.get("tags")));
        listeners.put(key(region, id), listener);
        return listener;
    }

    public LatticeListener getListener(String region, String serviceIdentifier, String listenerIdentifier) {
        LatticeService service = requireService(region, serviceIdentifier);
        LatticeListener listener = requireListener(region, listenerIdentifier);
        if (!service.getId().equals(listener.getServiceId())) {
            throw notFound(listenerIdentifier, "LISTENER");
        }
        return listener;
    }

    public List<LatticeListener> listListeners(String region, String serviceIdentifier) {
        LatticeService service = requireService(region, serviceIdentifier);
        List<LatticeListener> items = listenersFor(region, service.getId());
        items.sort(Comparator.comparing(LatticeListener::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized LatticeListener updateListener(
            String region, String serviceIdentifier, String listenerIdentifier, JsonNode request) {
        requireObject(request, "Request body");
        LatticeListener listener = getListener(region, serviceIdentifier, listenerIdentifier);
        if (!request.hasNonNull("defaultAction")) {
            throw validation("defaultAction is required.");
        }
        listener.setDefaultAction(request.get("defaultAction").deepCopy());
        listener.setLastUpdatedAt(now());
        listeners.put(key(region, listener.getId()), listener);
        return listener;
    }

    public synchronized void deleteListener(String region, String serviceIdentifier, String listenerIdentifier) {
        LatticeListener listener = getListener(region, serviceIdentifier, listenerIdentifier);
        for (LatticeRule rule : rulesFor(region, listener.getId())) {
            rules.delete(key(region, rule.getId()));
        }
        listeners.delete(key(region, listener.getId()));
    }

    public synchronized LatticeRule createRule(
            String region, String serviceIdentifier, String listenerIdentifier, JsonNode request) {
        requireObject(request, "Request body");
        LatticeListener listener = getListener(region, serviceIdentifier, listenerIdentifier);
        String name = requireText(request, "name");
        int priority = requireInt(request, "priority");
        JsonNode match = request.get("match");
        JsonNode action = request.get("action");
        if (match == null || match.isNull()) {
            throw validation("match is required.");
        }
        if (action == null || action.isNull()) {
            throw validation("action is required.");
        }
        for (LatticeRule existing : rulesFor(region, listener.getId())) {
            if (name.equals(existing.getName())) {
                throw conflict("A rule named '" + name + "' already exists.", existing.getId(), "RULE");
            }
            if (existing.getPriority() != null && existing.getPriority() == priority) {
                throw conflict("A rule with priority " + priority + " already exists.",
                        existing.getId(), "RULE");
            }
        }
        String now = now();
        String id = "rule-" + hexId(17);
        LatticeRule rule = new LatticeRule();
        rule.setId(id);
        rule.setArn(arn(region, "service/" + listener.getServiceId()
                + "/listener/" + listener.getId() + "/rule/" + id));
        rule.setName(name);
        rule.setServiceId(listener.getServiceId());
        rule.setListenerId(listener.getId());
        rule.setPriority(priority);
        rule.setDefault(false);
        rule.setMatch(match.deepCopy());
        rule.setAction(action.deepCopy());
        rule.setRegion(region);
        rule.setCreatedAt(now);
        rule.setLastUpdatedAt(now);
        rule.setTags(readTags(request.get("tags")));
        rules.put(key(region, id), rule);
        return rule;
    }

    public LatticeRule getRule(
            String region, String serviceIdentifier, String listenerIdentifier, String ruleIdentifier) {
        LatticeListener listener = getListener(region, serviceIdentifier, listenerIdentifier);
        LatticeRule rule = requireRule(region, ruleIdentifier);
        if (!listener.getId().equals(rule.getListenerId())) {
            throw notFound(ruleIdentifier, "RULE");
        }
        return rule;
    }

    public List<LatticeRule> listRules(String region, String serviceIdentifier, String listenerIdentifier) {
        LatticeListener listener = getListener(region, serviceIdentifier, listenerIdentifier);
        List<LatticeRule> items = rulesFor(region, listener.getId());
        items.sort(Comparator.comparing(LatticeRule::getPriority, Comparator.nullsLast(Integer::compareTo)));
        return items;
    }

    public synchronized LatticeRule updateRule(
            String region, String serviceIdentifier, String listenerIdentifier,
            String ruleIdentifier, JsonNode request) {
        requireObject(request, "Request body");
        LatticeRule rule = getRule(region, serviceIdentifier, listenerIdentifier, ruleIdentifier);
        if (request.hasNonNull("priority")) {
            int priority = request.get("priority").asInt();
            for (LatticeRule existing : rulesFor(region, rule.getListenerId())) {
                if (!existing.getId().equals(rule.getId())
                        && existing.getPriority() != null
                        && existing.getPriority() == priority) {
                    throw conflict("A rule with priority " + priority + " already exists.",
                            existing.getId(), "RULE");
                }
            }
            rule.setPriority(priority);
        }
        if (request.hasNonNull("match")) {
            rule.setMatch(request.get("match").deepCopy());
        }
        if (request.hasNonNull("action")) {
            rule.setAction(request.get("action").deepCopy());
        }
        rule.setLastUpdatedAt(now());
        rules.put(key(region, rule.getId()), rule);
        return rule;
    }

    public synchronized void deleteRule(
            String region, String serviceIdentifier, String listenerIdentifier, String ruleIdentifier) {
        LatticeRule rule = getRule(region, serviceIdentifier, listenerIdentifier, ruleIdentifier);
        rules.delete(key(region, rule.getId()));
    }

    ObjectNode accessLogNode(AccessLogSubscription subscription) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", subscription.getId());
        node.put("arn", subscription.getArn());
        node.put("resourceId", subscription.getResourceId());
        node.put("resourceArn", subscription.getResourceArn());
        node.put("destinationArn", subscription.getDestinationArn());
        if (subscription.getServiceNetworkLogType() != null) {
            node.put("serviceNetworkLogType", subscription.getServiceNetworkLogType());
        }
        putTime(node, "createdAt", subscription.getCreatedAt());
        putTime(node, "lastUpdatedAt", subscription.getLastUpdatedAt());
        return node;
    }

    ObjectNode accessLogCreateNode(AccessLogSubscription subscription) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", subscription.getId());
        node.put("arn", subscription.getArn());
        node.put("resourceId", subscription.getResourceId());
        node.put("resourceArn", subscription.getResourceArn());
        node.put("destinationArn", subscription.getDestinationArn());
        if (subscription.getServiceNetworkLogType() != null) {
            node.put("serviceNetworkLogType", subscription.getServiceNetworkLogType());
        }
        return node;
    }

    ObjectNode networkNode(ServiceNetwork network) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", network.getId());
        node.put("arn", network.getArn());
        node.put("name", network.getName());
        node.put("authType", network.getAuthType());
        putTime(node, "createdAt", network.getCreatedAt());
        putTime(node, "lastUpdatedAt", network.getLastUpdatedAt());
        return node;
    }

    ObjectNode networkSummary(ServiceNetwork network) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", network.getId());
        node.put("arn", network.getArn());
        node.put("name", network.getName());
        putTime(node, "createdAt", network.getCreatedAt());
        putTime(node, "lastUpdatedAt", network.getLastUpdatedAt());
        return node;
    }

    ObjectNode serviceNode(LatticeService service) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", service.getId());
        node.put("arn", service.getArn());
        node.put("name", service.getName());
        node.put("status", service.getStatus());
        node.put("authType", service.getAuthType());
        if (service.getCustomDomainName() != null) {
            node.put("customDomainName", service.getCustomDomainName());
        }
        if (service.getCertificateArn() != null) {
            node.put("certificateArn", service.getCertificateArn());
        }
        if (service.getIdleTimeoutSeconds() != null) {
            node.put("idleTimeoutSeconds", service.getIdleTimeoutSeconds());
        }
        if (service.getDnsDomainName() != null) {
            ObjectNode dns = node.putObject("dnsEntry");
            dns.put("domainName", service.getDnsDomainName());
        }
        putTime(node, "createdAt", service.getCreatedAt());
        putTime(node, "lastUpdatedAt", service.getLastUpdatedAt());
        return node;
    }

    ObjectNode serviceSummary(LatticeService service) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", service.getId());
        node.put("arn", service.getArn());
        node.put("name", service.getName());
        node.put("status", service.getStatus());
        if (service.getCustomDomainName() != null) {
            node.put("customDomainName", service.getCustomDomainName());
        }
        if (service.getDnsDomainName() != null) {
            ObjectNode dns = node.putObject("dnsEntry");
            dns.put("domainName", service.getDnsDomainName());
        }
        putTime(node, "createdAt", service.getCreatedAt());
        putTime(node, "lastUpdatedAt", service.getLastUpdatedAt());
        return node;
    }

    ObjectNode associationNode(ServiceAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", association.getId());
        node.put("arn", association.getArn());
        node.put("status", association.getStatus());
        node.put("createdBy", association.getCreatedBy());
        node.put("serviceId", association.getServiceId());
        node.put("serviceName", association.getServiceName());
        node.put("serviceArn", association.getServiceArn());
        node.put("serviceNetworkId", association.getServiceNetworkId());
        node.put("serviceNetworkName", association.getServiceNetworkName());
        node.put("serviceNetworkArn", association.getServiceNetworkArn());
        if (association.getCustomDomainName() != null) {
            node.put("customDomainName", association.getCustomDomainName());
        }
        if (association.getDnsName() != null) {
            ObjectNode dns = node.putObject("dnsEntry");
            dns.put("domainName", association.getDnsName());
        }
        putTime(node, "createdAt", association.getCreatedAt());
        return node;
    }

    ObjectNode vpcAssociationNode(VpcAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", association.getId());
        node.put("arn", association.getArn());
        node.put("status", association.getStatus());
        node.put("createdBy", association.getCreatedBy());
        node.put("serviceNetworkId", association.getServiceNetworkId());
        node.put("serviceNetworkName", association.getServiceNetworkName());
        node.put("serviceNetworkArn", association.getServiceNetworkArn());
        node.put("vpcId", association.getVpcId());
        ArrayNode sgs = node.putArray("securityGroupIds");
        for (String sg : association.getSecurityGroupIds()) {
            sgs.add(sg);
        }
        putTime(node, "createdAt", association.getCreatedAt());
        return node;
    }

    ObjectNode targetGroupNode(LatticeTargetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.getId());
        node.put("arn", group.getArn());
        node.put("name", group.getName());
        node.put("type", group.getType());
        node.put("status", group.getStatus());
        if (group.getConfig() != null) {
            node.set("config", group.getConfig());
        }
        putTime(node, "createdAt", group.getCreatedAt());
        putTime(node, "lastUpdatedAt", group.getLastUpdatedAt());
        return node;
    }

    ObjectNode targetGroupSummary(LatticeTargetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.getId());
        node.put("arn", group.getArn());
        node.put("name", group.getName());
        node.put("type", group.getType());
        node.put("status", group.getStatus());
        JsonNode config = group.getConfig();
        if (config != null) {
            if (config.hasNonNull("port")) {
                node.put("port", config.get("port").asInt());
            }
            if (config.hasNonNull("protocol")) {
                node.put("protocol", config.get("protocol").asText());
            }
            if (config.hasNonNull("ipAddressType")) {
                node.put("ipAddressType", config.get("ipAddressType").asText());
            }
            if (config.hasNonNull("vpcIdentifier")) {
                node.put("vpcIdentifier", config.get("vpcIdentifier").asText());
            }
        }
        putTime(node, "createdAt", group.getCreatedAt());
        putTime(node, "lastUpdatedAt", group.getLastUpdatedAt());
        return node;
    }

    ObjectNode listenerNode(LatticeListener listener) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", listener.getId());
        node.put("arn", listener.getArn());
        node.put("name", listener.getName());
        node.put("protocol", listener.getProtocol());
        if (listener.getPort() != null) {
            node.put("port", listener.getPort());
        }
        node.put("serviceId", listener.getServiceId());
        node.put("serviceArn", listener.getServiceArn());
        if (listener.getDefaultAction() != null) {
            node.set("defaultAction", listener.getDefaultAction());
        }
        putTime(node, "createdAt", listener.getCreatedAt());
        putTime(node, "lastUpdatedAt", listener.getLastUpdatedAt());
        return node;
    }

    ObjectNode listenerSummary(LatticeListener listener) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", listener.getId());
        node.put("arn", listener.getArn());
        node.put("name", listener.getName());
        node.put("protocol", listener.getProtocol());
        if (listener.getPort() != null) {
            node.put("port", listener.getPort());
        }
        putTime(node, "createdAt", listener.getCreatedAt());
        putTime(node, "lastUpdatedAt", listener.getLastUpdatedAt());
        return node;
    }

    ObjectNode ruleNode(LatticeRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", rule.getId());
        node.put("arn", rule.getArn());
        node.put("name", rule.getName());
        node.put("isDefault", rule.isDefault());
        if (rule.getPriority() != null) {
            node.put("priority", rule.getPriority());
        }
        if (rule.getMatch() != null) {
            node.set("match", rule.getMatch());
        }
        if (rule.getAction() != null) {
            node.set("action", rule.getAction());
        }
        putTime(node, "createdAt", rule.getCreatedAt());
        putTime(node, "lastUpdatedAt", rule.getLastUpdatedAt());
        return node;
    }

    ObjectNode ruleSummary(LatticeRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", rule.getId());
        node.put("arn", rule.getArn());
        node.put("name", rule.getName());
        node.put("isDefault", rule.isDefault());
        if (rule.getPriority() != null) {
            node.put("priority", rule.getPriority());
        }
        putTime(node, "createdAt", rule.getCreatedAt());
        putTime(node, "lastUpdatedAt", rule.getLastUpdatedAt());
        return node;
    }

    ObjectNode targetNode(LatticeTarget target) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", target.getId());
        if (target.getPort() != null) {
            node.put("port", target.getPort());
        }
        node.put("status", target.getStatus());
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.applyTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.applyTags(current);
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw notFound(arn, "RESOURCE");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
            throw notFound(arn, "RESOURCE");
        }
        String resource = parsed.resource();
        if (resource.startsWith("servicenetworkserviceassociation/")) {
            ServiceAssociation association = requireServiceAssociation(region, resource.substring(
                    "servicenetworkserviceassociation/".length()));
            return tagged(association.getTags(), tags -> {
                association.setTags(tags);
                serviceAssociations.put(key(region, association.getId()), association);
            });
        }
        if (resource.startsWith("servicenetworkvpcassociation/")) {
            VpcAssociation association = requireVpcAssociation(region,
                    resource.substring("servicenetworkvpcassociation/".length()));
            return tagged(association.getTags(), tags -> {
                association.setTags(tags);
                vpcAssociations.put(key(region, association.getId()), association);
            });
        }
        if (resource.startsWith("servicenetwork/")) {
            ServiceNetwork network = requireNetwork(region, resource.substring("servicenetwork/".length()));
            return tagged(network.getTags(), tags -> {
                network.setTags(tags);
                networks.put(key(region, network.getId()), network);
            });
        }
        if (resource.startsWith("accesslogsubscription/")) {
            AccessLogSubscription subscription = requireAccessLog(
                    region, resource.substring("accesslogsubscription/".length()));
            return tagged(subscription.getTags(), tags -> {
                subscription.setTags(tags);
                accessLogs.put(key(region, subscription.getId()), subscription);
            });
        }
        if (resource.startsWith("targetgroup/")) {
            LatticeTargetGroup group = requireTargetGroup(region, resource.substring("targetgroup/".length()));
            return tagged(group.getTags(), tags -> {
                group.setTags(tags);
                targetGroups.put(key(region, group.getId()), group);
            });
        }
        if (resource.contains("/rule/")) {
            String ruleId = resource.substring(resource.lastIndexOf('/') + 1);
            LatticeRule rule = requireRule(region, ruleId);
            return tagged(rule.getTags(), tags -> {
                rule.setTags(tags);
                rules.put(key(region, rule.getId()), rule);
            });
        }
        if (resource.contains("/listener/")) {
            String listenerId = resource.substring(resource.lastIndexOf('/') + 1);
            LatticeListener listener = requireListener(region, listenerId);
            return tagged(listener.getTags(), tags -> {
                listener.setTags(tags);
                listeners.put(key(region, listener.getId()), listener);
            });
        }
        if (resource.startsWith("service/")) {
            LatticeService service = requireService(region, resource.substring("service/".length()));
            return tagged(service.getTags(), tags -> {
                service.setTags(tags);
                services.put(key(region, service.getId()), service);
            });
        }
        throw notFound(arn, "RESOURCE");
    }

    private Tagged tagged(Map<String, String> current, TagApplier applier) {
        return new Tagged() {
            @Override
            public Map<String, String> tags() {
                return current;
            }

            @Override
            public void applyTags(Map<String, String> tags) {
                applier.apply(tags);
            }
        };
    }

    private interface Tagged {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }

    private interface TagApplier {
        void apply(Map<String, String> tags);
    }

    private AccessLogSubscription requireAccessLog(String region, String identifier) {
        String id = resourceId(identifier, "accesslogsubscription/");
        return accessLogs.get(key(region, id))
                .or(() -> accessLogsIn(region).stream()
                        .filter(s -> id.equals(s.getId()) || id.equals(s.getArn()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "ACCESS_LOG_SUBSCRIPTION"));
    }

    private LogResource requireLogResource(String region, String identifier) {
        try {
            ServiceNetwork network = requireNetwork(region, identifier);
            return new LogResource(network.getId(), network.getArn());
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
        }
        LatticeService service = requireService(region, identifier);
        return new LogResource(service.getId(), service.getArn());
    }

    private List<AccessLogSubscription> accessLogsIn(String region) {
        return accessLogs.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private static String normalizeDestinationArn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            throw validation("destinationArn must be a valid ARN.");
        }
        try {
            if ("logs".equals(AwsArnUtils.parse(arn).service()) && !arn.endsWith(":*")) {
                return arn + ":*";
            }
        } catch (IllegalArgumentException e) {
            throw validation("destinationArn must be a valid ARN.");
        }
        return arn;
    }

    private static String destinationType(String arn) {
        try {
            return AwsArnUtils.parse(arn).service();
        } catch (IllegalArgumentException e) {
            throw validation("destinationArn must be a valid ARN.");
        }
    }

    private record LogResource(String id, String arn) {
    }

    public record AuthPolicyView(String policy, String state, String createdAt, String lastUpdatedAt) {
    }

    private ServiceNetwork findNetwork(String region, String identifier) {
        try {
            return requireNetwork(region, identifier);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            return null;
        }
    }

    private LatticeService findService(String region, String identifier) {
        try {
            return requireService(region, identifier);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            return null;
        }
    }

    private static String readPolicy(JsonNode request) {
        requireObject(request, "Request body");
        JsonNode policy = request.get("policy");
        if (policy == null || policy.isNull()) {
            throw validation("policy is required.");
        }
        if (policy.isTextual()) {
            return policy.textValue();
        }
        if (policy.isObject() || policy.isArray()) {
            return policy.toString();
        }
        throw validation("policy must be a string or JSON document.");
    }

    private ServiceNetwork requireNetwork(String region, String identifier) {
        String id = resourceId(identifier, "servicenetwork/");
        return networks.get(key(region, id))
                .or(() -> networksIn(region).stream()
                        .filter(n -> id.equals(n.getId()) || id.equals(n.getArn()) || id.equals(n.getName()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "SERVICE_NETWORK"));
    }

    private LatticeService requireService(String region, String identifier) {
        String id = resourceId(identifier, "service/");
        return services.get(key(region, id))
                .or(() -> servicesIn(region).stream()
                        .filter(s -> id.equals(s.getId()) || id.equals(s.getArn()) || id.equals(s.getName()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "SERVICE"));
    }

    private ServiceAssociation requireServiceAssociation(String region, String identifier) {
        String id = resourceId(identifier, "servicenetworkserviceassociation/");
        return serviceAssociations.get(key(region, id))
                .or(() -> serviceAssociationsIn(region).stream()
                        .filter(a -> id.equals(a.getId()) || id.equals(a.getArn()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "SERVICE_NETWORK_SERVICE_ASSOCIATION"));
    }

    private VpcAssociation requireVpcAssociation(String region, String identifier) {
        String id = resourceId(identifier, "servicenetworkvpcassociation/");
        return vpcAssociations.get(key(region, id))
                .or(() -> vpcAssociationsIn(region).stream()
                        .filter(a -> id.equals(a.getId()) || id.equals(a.getArn()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "SERVICE_NETWORK_VPC_ASSOCIATION"));
    }

    private LatticeTargetGroup requireTargetGroup(String region, String identifier) {
        String id = resourceId(identifier, "targetgroup/");
        return targetGroups.get(key(region, id))
                .or(() -> targetGroupsIn(region).stream()
                        .filter(g -> id.equals(g.getId()) || id.equals(g.getArn()) || id.equals(g.getName()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "TARGET_GROUP"));
    }

    private LatticeListener requireListener(String region, String identifier) {
        String id = resourceId(identifier, "listener/");
        if (id.contains("/")) {
            id = id.substring(id.lastIndexOf('/') + 1);
        }
        String listenerId = id;
        return listeners.get(key(region, listenerId))
                .or(() -> listenersIn(region).stream()
                        .filter(l -> listenerId.equals(l.getId()) || identifier.equals(l.getArn()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "LISTENER"));
    }

    private LatticeRule requireRule(String region, String identifier) {
        String id = resourceId(identifier, "rule/");
        if (id.contains("/")) {
            id = id.substring(id.lastIndexOf('/') + 1);
        }
        String ruleId = id;
        return rules.get(key(region, ruleId))
                .or(() -> rulesIn(region).stream()
                        .filter(r -> ruleId.equals(r.getId()) || identifier.equals(r.getArn()))
                        .findFirst())
                .orElseThrow(() -> notFound(identifier, "RULE"));
    }

    private List<ServiceNetwork> networksIn(String region) {
        return networks.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private List<LatticeService> servicesIn(String region) {
        return services.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private List<LatticeListener> listenersIn(String region) {
        return listeners.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private List<LatticeListener> listenersFor(String region, String serviceId) {
        List<LatticeListener> items = new ArrayList<>();
        for (LatticeListener listener : listenersIn(region)) {
            if (serviceId.equals(listener.getServiceId())) {
                items.add(listener);
            }
        }
        return items;
    }

    private List<LatticeRule> rulesIn(String region) {
        return rules.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private List<LatticeRule> rulesFor(String region, String listenerId) {
        List<LatticeRule> items = new ArrayList<>();
        for (LatticeRule rule : rulesIn(region)) {
            if (listenerId.equals(rule.getListenerId())) {
                items.add(rule);
            }
        }
        return items;
    }

    private List<LatticeTargetGroup> targetGroupsIn(String region) {
        return targetGroups.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private List<ServiceAssociation> serviceAssociationsIn(String region) {
        return serviceAssociations.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private List<VpcAssociation> vpcAssociationsIn(String region) {
        return vpcAssociations.scan(storageKey -> storageKey.startsWith(region + "::"));
    }

    private boolean referencesTargetGroup(LatticeRule rule, String targetGroupId) {
        return containsTargetGroup(rule.getAction(), targetGroupId);
    }

    private boolean referencesTargetGroup(LatticeListener listener, String targetGroupId) {
        return containsTargetGroup(listener.getDefaultAction(), targetGroupId);
    }

    private static boolean containsTargetGroup(JsonNode action, String targetGroupId) {
        if (action == null || !action.has("forward")) {
            return false;
        }
        JsonNode groups = action.path("forward").path("targetGroups");
        if (!groups.isArray()) {
            return false;
        }
        for (JsonNode group : groups) {
            if (targetGroupId.equals(group.path("targetGroupIdentifier").asText(null))) {
                return true;
            }
        }
        return false;
    }

    private static String resourceId(String identifier, String prefix) {
        if (identifier == null) {
            return "";
        }
        if (identifier.startsWith("arn:")) {
            try {
                String resource = AwsArnUtils.parse(identifier).resource();
                if (resource != null && resource.startsWith(prefix)) {
                    return resource.substring(prefix.length());
                }
                if (resource != null && resource.contains("/")) {
                    return resource.substring(resource.lastIndexOf('/') + 1);
                }
                return resource == null ? identifier : resource;
            } catch (IllegalArgumentException e) {
                return identifier;
            }
        }
        return identifier;
    }

    private JsonNode copyObject(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return node.deepCopy();
    }

    private static void putTime(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private String accountId() {
        return regionResolver.getAccountId();
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static String hexId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private static int defaultPort(String protocol) {
        if ("HTTPS".equalsIgnoreCase(protocol) || "TLS_PASSTHROUGH".equalsIgnoreCase(protocol)) {
            return 443;
        }
        return 80;
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            throw validation(field + " is required.");
        }
        String value = request.get(field).asText();
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static int requireInt(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            throw validation(field + " is required.");
        }
        return request.get(field).asInt();
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    tags.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return tags;
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException("ResourceNotFoundException",
                "Resource not found: " + resourceId, 404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static AwsException conflict(String message, String resourceId, String resourceType) {
        return new AwsException("ConflictException", message, 409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400,
                Map.of("reason", "FIELD_VALIDATION_FAILED"));
    }
}

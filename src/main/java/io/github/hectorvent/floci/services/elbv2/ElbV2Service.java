package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.*;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class ElbV2Service {

    @Inject
    ElbV2DataPlane dataPlane;

    @Inject
    ElbV2HealthChecker healthChecker;

    @Inject
    RegionResolver regionResolver;

    @Inject
    Ec2Service ec2Service;

    @Inject
    StorageFactory storageFactory;

    @Inject
    EmulatorConfig config;

    @Inject
    S3Service s3Service;

    private static final String CANONICAL_HOSTED_ZONE_ID = "Z35SXDOTRQ7X7K";
    private static final Pattern PEM_CERT = Pattern.compile("-----BEGIN CERTIFICATE-----");

    // region → ARN → resource
    private Map<String, Map<String, LoadBalancer>> loadBalancers = new ConcurrentHashMap<>();
    private Map<String, Map<String, TargetGroup>> targetGroups = new ConcurrentHashMap<>();
    private Map<String, Map<String, Listener>> listeners = new ConcurrentHashMap<>();
    private Map<String, Map<String, Rule>> rules = new ConcurrentHashMap<>();
    private Map<String, Map<String, TrustStore>> trustStores = new ConcurrentHashMap<>();

    // indexes
    private final Map<String, List<String>> lbToListeners   = new ConcurrentHashMap<>(); // LB-ARN → listener ARNs
    private final Map<String, List<String>> listenerToRules  = new ConcurrentHashMap<>(); // Listener-ARN → rule ARNs
    private final Map<String, Set<String>>  tgToLbs          = new ConcurrentHashMap<>(); // TG-ARN → LB-ARNs

    // tags: resource-ARN → {key → value}
    private Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    @PostConstruct
    void initializeStorage()
    {
        if (storageFactory == null) {
            return;
        }
        this.loadBalancers = storageBacked("elbv2-load-balancers.json",
                new TypeReference<Map<String, Map<String, LoadBalancer>>>() {});
        this.targetGroups = storageBacked("elbv2-target-groups.json",
                new TypeReference<Map<String, Map<String, TargetGroup>>>() {});
        this.listeners = storageBacked("elbv2-listeners.json",
                new TypeReference<Map<String, Map<String, Listener>>>() {});
        this.rules = storageBacked("elbv2-rules.json",
                new TypeReference<Map<String, Map<String, Rule>>>() {});
        this.trustStores = storageBacked("elbv2-trust-stores.json",
                new TypeReference<Map<String, Map<String, TrustStore>>>() {});
        this.tags = storageBacked("elbv2-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        normalizeRegionMaps(loadBalancers);
        normalizeRegionMaps(targetGroups);
        normalizeRegionMaps(listeners);
        normalizeRegionMaps(rules);
        normalizeRegionMaps(trustStores);
        normalizeRegionMaps(tags);
        rebuildIndexes();
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference)
    {
        return new StorageBackedMap<>(storageFactory.create("elbv2", fileName, typeReference));
    }

    private <V> void normalizeRegionMaps(Map<String, Map<String, V>> resources) {
        for (Map.Entry<String, Map<String, V>> entry : new ArrayList<>(resources.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                resources.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    private <V> void persistRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> regionResources = resources.get(region);
        if (regionResources != null) {
            resources.put(region, regionResources);
        }
    }

    /**
     * Storage reload (and {@code getOrDefault(..., Map.of())}) can hand back an
     * immutable inner map. Mutating it throws {@code UnsupportedOperationException}
     * with a null message — the Query dispatcher surfaces that as
     * {@code InternalFailure: Unexpected error: null}.
     */
    private <V> Map<String, V> mutableRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> existing = resources.get(region);
        if (existing instanceof ConcurrentHashMap) {
            return existing;
        }
        ConcurrentHashMap<String, V> copy = new ConcurrentHashMap<>();
        if (existing != null) {
            copy.putAll(existing);
        }
        resources.put(region, copy);
        return copy;
    }

    /**
     * Rebuilds the in-memory indexes from the storage-backed maps. Pure and in-process on purpose:
     * nothing reachable from {@link #initializeStorage()} may call an injected collaborator, because
     * {@link ElbV2DataPlane} calls back into this service and CDI has not registered the instance in
     * the ApplicationScoped context yet — re-entering bean creation instead (issue #1913). Runtime
     * side effects belong in {@link #restorePersistedRuntime()}.
     */
    private void rebuildIndexes()
    {
        lbToListeners.clear();
        listenerToRules.clear();
        tgToLbs.clear();

        for (Map<String, Listener> regionListeners : listeners.values()) {
            for (Listener listener : regionListeners.values()) {
                lbToListeners.computeIfAbsent(listener.getLoadBalancerArn(), k -> new ArrayList<>())
                        .add(listener.getListenerArn());
            }
        }
        for (Map<String, Rule> regionRules : rules.values()) {
            for (Rule rule : regionRules.values()) {
                listenerToRules.computeIfAbsent(rule.getListenerArn(), k -> new ArrayList<>())
                        .add(rule.getRuleArn());
            }
        }
        for (Map.Entry<String, Map<String, TargetGroup>> regionEntry : targetGroups.entrySet()) {
            for (TargetGroup targetGroup : regionEntry.getValue().values()) {
                tgToLbs.computeIfAbsent(targetGroup.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet())
                        .addAll(targetGroup.getLoadBalancerArns());
            }
        }
    }

    /**
     * Brings the data plane and health checks back up for state restored from disk. Invoked from
     * {@code EmulatorLifecycle} once the bean is fully constructed, alongside the other services that
     * restore persisted runtime after {@code storageFactory.loadAll()}.
     */
    public void restorePersistedRuntime()
    {
        if (healthChecker != null) {
            for (Map<String, TargetGroup> regionTargetGroups : targetGroups.values()) {
                for (TargetGroup targetGroup : regionTargetGroups.values()) {
                    healthChecker.startMonitoring(targetGroup);
                    if (!targetGroup.getTargets().isEmpty()) {
                        healthChecker.addTargets(targetGroup.getTargetGroupArn(), targetGroup.getTargets(), targetGroup);
                    }
                }
            }
        }
        if (dataPlane != null) {
            for (Map.Entry<String, Map<String, Listener>> regionEntry : listeners.entrySet()) {
                String region = regionEntry.getKey();
                for (Listener listener : regionEntry.getValue().values()) {
                    startDataPlane(listener, region);
                }
            }
        }
    }

    // ── Load Balancers ────────────────────────────────────────────────────────

    public LoadBalancer createLoadBalancer(String region, String name, String scheme,
                                           String type, String ipAddressType,
                                           List<String> subnets, List<String> securityGroups,
                                           Map<String, String> initialTags) {
        validateName(name, "load balancer");
        Map<String, LoadBalancer> regionLbs = mutableRegion(loadBalancers, region);
        boolean duplicate = regionLbs.values().stream()
                .anyMatch(lb -> lb.getLoadBalancerName().equals(name));
        if (duplicate) {
            throw new AwsException("DuplicateLoadBalancerName",
                    "A load balancer with name '" + name + "' already exists.", 400);
        }

        String lbType = type != null ? type : "application";
        String lbScheme = scheme != null ? scheme : "internet-facing";
        String ipType = ipAddressType != null ? ipAddressType : "ipv4";
        String typePrefix = lbTypePrefix(lbType);
        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "loadbalancer/" + typePrefix + "/" + name + "/" + id).toString();
        String dnsName = name + "-" + id + ".elb." + loadBalancerDnsSuffix();
        String vpcId = resolveSubnetVpcId(region, lbType, subnets);

        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerArn(arn);
        lb.setDnsName(dnsName);
        lb.setCanonicalHostedZoneId(CANONICAL_HOSTED_ZONE_ID);
        lb.setCreatedTime(Instant.now());
        lb.setLoadBalancerName(name);
        lb.setScheme(lbScheme);
        lb.setVpcId(vpcId);
        lb.setState("active");
        lb.setType(lbType);
        lb.setIpAddressType(ipType);
        lb.setRegion(region);
        if (subnets != null) lb.setAvailabilityZones(resolveAvailabilityZones(region, subnets));
        if (securityGroups != null) lb.setSecurityGroups(new ArrayList<>(securityGroups));

        regionLbs.put(arn, lb);
        loadBalancers.put(region, regionLbs);
        lbToListeners.put(arn, new ArrayList<>());
        if (!initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        return lb;
    }

    private String loadBalancerDnsSuffix() {
        if (config == null) {
            return EmbeddedDnsServer.DEFAULT_SUFFIX;
        }
        return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
    }

    public List<LoadBalancer> describeLoadBalancers(String region, List<String> arns, List<String> names,
                                                     String marker, Integer pageSize) {
        Map<String, LoadBalancer> regionLbs = loadBalancers.getOrDefault(region, Map.of());
        List<LoadBalancer> result = new ArrayList<>(regionLbs.values());

        if (arns != null && !arns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(arns);
            result = result.stream().filter(lb -> arnSet.contains(lb.getLoadBalancerArn())).collect(Collectors.toList());
            if (result.isEmpty() && !arns.isEmpty()) {
                throw new AwsException("LoadBalancerNotFound",
                        "One or more load balancers not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream().filter(lb -> nameSet.contains(lb.getLoadBalancerName())).collect(Collectors.toList());
            if (result.isEmpty() && !names.isEmpty()) {
                throw new AwsException("LoadBalancerNotFound",
                        "One or more load balancers not found.", 400);
            }
        }
        return result;
    }

    public void deleteLoadBalancer(String region, String arn) {
        Map<String, LoadBalancer> regionLbs = mutableRegion(loadBalancers, region);
        LoadBalancer lb = regionLbs.remove(arn);
        if (lb == null) {
            return; // AWS silently ignores non-existent LBs on delete
        }
        // cascade: listeners → rules
        List<String> listenerArns = lbToListeners.remove(arn);
        if (listenerArns != null) {
            Map<String, Listener> regionListeners = mutableRegion(listeners, region);
            Map<String, Rule> regionRules = mutableRegion(rules, region);
            for (String listenerArn : listenerArns) {
                dataPlane.stopListener(listenerArn);
                regionListeners.remove(listenerArn);
                List<String> ruleArns = listenerToRules.remove(listenerArn);
                if (ruleArns != null) {
                    ruleArns.forEach(regionRules::remove);
                }
            }
            listeners.put(region, regionListeners);
            rules.put(region, regionRules);
        }
        // remove from TG index
        tgToLbs.values().forEach(lbSet -> lbSet.remove(arn));
        tags.remove(arn);
        loadBalancers.put(region, regionLbs);
    }

    public Map<String, String> describeLoadBalancerAttributes(String region, String arn) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        return new LinkedHashMap<>(lb.getAttributes());
    }

    public void modifyLoadBalancerAttributes(String region, String arn, Map<String, String> newAttrs) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.getAttributes().putAll(newAttrs);
        persistRegion(loadBalancers, region);
    }

    LoadBalancer getLoadBalancer(String region, String arn) {
        return loadBalancers.getOrDefault(region, Map.of()).get(arn);
    }

    /** Capacity reservation status for a load balancer. Fields are {@code null} when no
     *  capacity is reserved. */
    public record CapacityReservation(Integer decreaseRequestsRemaining,
                                      Integer minimumCapacityUnits,
                                      Instant lastModifiedTime) {}

    public CapacityReservation describeCapacityReservation(String region, String arn) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        return new CapacityReservation(
                lb.getDecreaseRequestsRemaining(),
                lb.getMinimumCapacityUnits(),
                lb.getCapacityReservationLastModified());
    }

    /**
     * Stores or clears the requested minimum capacity. Floci does not provision LCUs, so a reset
     * of a never-set reservation is a no-op success — the same as AWS when nothing is reserved.
     */
    public CapacityReservation modifyCapacityReservation(String region, String arn,
                                                         Integer minimumCapacityUnits,
                                                         Boolean reset) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        boolean doReset = Boolean.TRUE.equals(reset);
        if (!doReset && minimumCapacityUnits == null) {
            throw new AwsException("InvalidConfigurationRequest",
                    "You must specify MinimumLoadBalancerCapacity or ResetCapacityReservation.", 400);
        }
        if (doReset) {
            lb.setMinimumCapacityUnits(null);
            lb.setDecreaseRequestsRemaining(null);
            lb.setCapacityReservationLastModified(null);
        } else {
            lb.setMinimumCapacityUnits(minimumCapacityUnits);
            lb.setDecreaseRequestsRemaining(2);
            lb.setCapacityReservationLastModified(Instant.now());
        }
        persistRegion(loadBalancers, region);
        return describeCapacityReservation(region, arn);
    }

    public void setSecurityGroups(String region, String arn, List<String> sgIds) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setSecurityGroups(new ArrayList<>(sgIds));
        persistRegion(loadBalancers, region);
    }

    public void setSubnets(String region, String arn, List<String> subnets) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        String vpcId = resolveSubnetVpcId(region, lb.getType(), subnets);
        if (vpcId != null && lb.getVpcId() != null && !lb.getVpcId().equals(vpcId)) {
            throw new AwsException("InvalidConfigurationRequest",
                    "All subnets must belong to the load balancer VPC.", 400);
        }
        lb.setAvailabilityZones(resolveAvailabilityZones(region, subnets));
        if (vpcId != null) {
            lb.setVpcId(vpcId);
        }
        persistRegion(loadBalancers, region);
    }

    public void setIpAddressType(String region, String arn, String ipAddressType) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setIpAddressType(ipAddressType);
        persistRegion(loadBalancers, region);
    }

    // ── Target Groups ─────────────────────────────────────────────────────────

    public TargetGroup createTargetGroup(String region, String name, String protocol, String protocolVersion,
                                          Integer port, String vpcId, String targetType,
                                          String healthCheckProtocol, String healthCheckPort,
                                          Boolean healthCheckEnabled, String healthCheckPath,
                                          Integer healthCheckInterval, Integer healthCheckTimeout,
                                          Integer healthyThreshold, Integer unhealthyThreshold,
                                          String matcher, String ipAddressType,
                                          Map<String, String> initialTags) {
        validateName(name, "target group");
        Map<String, TargetGroup> regionTgs = mutableRegion(targetGroups, region);
        boolean duplicate = regionTgs.values().stream()
                .anyMatch(tg -> tg.getTargetGroupName().equals(name));
        if (duplicate) {
            throw new AwsException("DuplicateTargetGroupName",
                    "A target group with name '" + name + "' already exists.", 400);
        }

        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "targetgroup/" + name + "/" + id).toString();

        TargetGroup tg = new TargetGroup();
        tg.setTargetGroupArn(arn);
        tg.setTargetGroupName(name);
        tg.setProtocol(protocol != null ? protocol : "HTTP");
        tg.setProtocolVersion(protocolVersion != null ? protocolVersion : "HTTP1");
        tg.setPort(port);
        tg.setVpcId(vpcId);
        tg.setTargetType(targetType != null ? targetType : "instance");
        tg.setIpAddressType(ipAddressType != null ? ipAddressType : "ipv4");
        tg.setRegion(region);

        // health check defaults
        tg.setHealthCheckEnabled(healthCheckEnabled != null ? healthCheckEnabled : true);
        tg.setHealthCheckProtocol(healthCheckProtocol != null ? healthCheckProtocol : "HTTP");
        tg.setHealthCheckPort(healthCheckPort != null ? healthCheckPort : "traffic-port");
        tg.setHealthCheckPath(healthCheckPath != null ? healthCheckPath : "/");
        tg.setHealthCheckIntervalSeconds(healthCheckInterval != null ? healthCheckInterval : 30);
        tg.setHealthCheckTimeoutSeconds(healthCheckTimeout != null ? healthCheckTimeout : 5);
        tg.setHealthyThresholdCount(healthyThreshold != null ? healthyThreshold : 5);
        tg.setUnhealthyThresholdCount(unhealthyThreshold != null ? unhealthyThreshold : 2);
        tg.setMatcher(matcher != null ? matcher : "200");

        regionTgs.put(arn, tg);
        targetGroups.put(region, regionTgs);
        tgToLbs.put(arn, ConcurrentHashMap.newKeySet());
        if (!initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        healthChecker.startMonitoring(tg);
        return tg;
    }

    public List<TargetGroup> describeTargetGroups(String region, String lbArn, List<String> tgArns,
                                                    List<String> names) {
        Map<String, TargetGroup> regionTgs = targetGroups.getOrDefault(region, Map.of());
        List<TargetGroup> result = new ArrayList<>(regionTgs.values());

        if (lbArn != null && !lbArn.isEmpty()) {
            result = result.stream()
                    .filter(tg -> tgToLbs.getOrDefault(tg.getTargetGroupArn(), Set.of()).contains(lbArn))
                    .collect(Collectors.toList());
        }
        if (tgArns != null && !tgArns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(tgArns);
            result = result.stream().filter(tg -> arnSet.contains(tg.getTargetGroupArn())).collect(Collectors.toList());
            if (result.size() != arnSet.size()) {
                throw new AwsException("TargetGroupNotFound", "One or more target groups not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream().filter(tg -> nameSet.contains(tg.getTargetGroupName())).collect(Collectors.toList());
            if (result.size() != nameSet.size()) {
                throw new AwsException("TargetGroupNotFound", "One or more target groups not found.", 400);
            }
        }
        return result;
    }

    public void deleteTargetGroup(String region, String arn) {
        TargetGroup tg = targetGroups.getOrDefault(region, Map.of()).get(arn);
        if (tg == null) {
            return;
        }
        Set<String> lbRefs = tgToLbs.getOrDefault(arn, Set.of());
        if (!lbRefs.isEmpty()) {
            throw new AwsException("ResourceInUse",
                    "Target group '" + tg.getTargetGroupName() + "' is currently in use by a listener or rule.", 400);
        }
        healthChecker.stopMonitoring(arn);
        mutableRegion(targetGroups, region).remove(arn);
        persistRegion(targetGroups, region);
        tgToLbs.remove(arn);
        tags.remove(arn);
    }

    public void modifyTargetGroup(String region, String arn, String healthCheckProtocol,
                                   String healthCheckPort, Boolean healthCheckEnabled,
                                   String healthCheckPath, Integer healthCheckInterval,
                                   Integer healthCheckTimeout, Integer healthyThreshold,
                                   Integer unhealthyThreshold, String matcher) {
        TargetGroup tg = requireTargetGroup(region, arn);
        if (healthCheckProtocol != null) tg.setHealthCheckProtocol(healthCheckProtocol);
        if (healthCheckPort != null)     tg.setHealthCheckPort(healthCheckPort);
        if (healthCheckEnabled != null)  tg.setHealthCheckEnabled(healthCheckEnabled);
        if (healthCheckPath != null)     tg.setHealthCheckPath(healthCheckPath);
        if (healthCheckInterval != null) tg.setHealthCheckIntervalSeconds(healthCheckInterval);
        if (healthCheckTimeout != null)  tg.setHealthCheckTimeoutSeconds(healthCheckTimeout);
        if (healthyThreshold != null)    tg.setHealthyThresholdCount(healthyThreshold);
        if (unhealthyThreshold != null)  tg.setUnhealthyThresholdCount(unhealthyThreshold);
        if (matcher != null)             tg.setMatcher(matcher);
        persistRegion(targetGroups, region);
    }

    public Map<String, String> describeTargetGroupAttributes(String region, String arn) {
        TargetGroup tg = requireTargetGroup(region, arn);
        return new LinkedHashMap<>(tg.getAttributes());
    }

    public void modifyTargetGroupAttributes(String region, String arn, Map<String, String> newAttrs) {
        TargetGroup tg = requireTargetGroup(region, arn);
        tg.getAttributes().putAll(newAttrs);
        persistRegion(targetGroups, region);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    public Listener createListener(String region, String lbArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy,
                                    Map<String, String> initialTags) {
        requireLoadBalancer(region, lbArn);

        Map<String, Listener> regionListeners = mutableRegion(listeners, region);

        // check duplicate port on same LB
        boolean portExists = regionListeners.values().stream()
                .filter(l -> l.getLoadBalancerArn().equals(lbArn))
                .anyMatch(l -> Objects.equals(l.getPort(), port));
        if (portExists) {
            throw new AwsException("DuplicateListener",
                    "A listener already exists on port " + port + " for this load balancer.", 400);
        }

        LoadBalancer lb = requireLoadBalancer(region, lbArn);
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String lbId = arnId(lbArn);
        String listenerId = randomHex16();
        String listenerArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId).toString();

        Listener listener = new Listener();
        listener.setListenerArn(listenerArn);
        listener.setLoadBalancerArn(lbArn);
        listener.setPort(port);
        listener.setProtocol(protocol != null ? protocol : "HTTP");
        listener.setSslPolicy(sslPolicy);
        listener.setCertificates(certificates != null ? new ArrayList<>(certificates) : new ArrayList<>());
        listener.setDefaultActions(defaultActions != null ? new ArrayList<>(defaultActions) : new ArrayList<>());
        listener.setAlpnPolicy(alpnPolicy != null ? new ArrayList<>(alpnPolicy) : new ArrayList<>());

        regionListeners.put(listenerArn, listener);
        listeners.put(region, regionListeners);
        lbToListeners.computeIfAbsent(lbArn, k -> new ArrayList<>()).add(listenerArn);

        // auto-create the default rule
        Rule defaultRule = buildDefaultRule(region, listenerArn, lb, lbId, listenerId, defaultActions);
        Map<String, Rule> regionRules = mutableRegion(rules, region);
        regionRules.put(defaultRule.getRuleArn(), defaultRule);
        rules.put(region, regionRules);
        listenerToRules.computeIfAbsent(listenerArn, k -> new ArrayList<>()).add(defaultRule.getRuleArn());
        for (Action action : defaultRule.getActions()) {
            linkTgToLb(action, lbArn);
        }

        if (!initialTags.isEmpty()) {
            tags.put(listenerArn, new LinkedHashMap<>(initialTags));
        }
        startDataPlane(listener, region);
        return listener;
    }

    public List<Listener> describeListeners(String region, String lbArn, List<String> listenerArns) {
        Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
        List<Listener> result = new ArrayList<>(regionListeners.values());

        if (lbArn != null && !lbArn.isEmpty()) {
            result = result.stream()
                    .filter(l -> l.getLoadBalancerArn().equals(lbArn))
                    .collect(Collectors.toList());
        }
        if (listenerArns != null && !listenerArns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(listenerArns);
            result = result.stream().filter(l -> arnSet.contains(l.getListenerArn())).collect(Collectors.toList());
        }
        return result;
    }

    public Map<String, String> describeListenerAttributes(String region, String arn) {
        Listener listener = requireListener(region, arn);
        return new LinkedHashMap<>(listener.getAttributes());
    }

    public void modifyListenerAttributes(String region, String arn, Map<String, String> newAttrs) {
        Listener listener = requireListener(region, arn);
        listener.getAttributes().putAll(newAttrs);
        persistRegion(listeners, region);
    }

    public void deleteListener(String region, String listenerArn) {
        Map<String, Listener> regionListeners = mutableRegion(listeners, region);
        Listener listener = regionListeners.remove(listenerArn);
        if (listener == null) {
            return;
        }
        dataPlane.stopListener(listenerArn);
        lbToListeners.getOrDefault(listener.getLoadBalancerArn(), List.of()).remove(listenerArn);

        Map<String, Rule> regionRules = mutableRegion(rules, region);
        List<String> ruleArns = listenerToRules.remove(listenerArn);
        if (ruleArns != null) {
            ruleArns.forEach(regionRules::remove);
        }
        listeners.put(region, regionListeners);
        rules.put(region, regionRules);
        tags.remove(listenerArn);
        unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
    }

    public Listener modifyListener(String region, String listenerArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy) {
        Listener listener = requireListener(region, listenerArn);
        boolean restartDataPlane = false;
        boolean recompileRules = false;

        if (port != null && !Objects.equals(listener.getPort(), port)) {
            // check duplicate port on same LB
            Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
            boolean portExists = regionListeners.values().stream()
                    .filter(l -> l.getLoadBalancerArn().equals(listener.getLoadBalancerArn()) && !l.getListenerArn().equals(listenerArn))
                    .anyMatch(l -> Objects.equals(l.getPort(), port));
            if (portExists) {
                throw new AwsException("DuplicateListener",
                        "A listener already exists on port " + port + " for this load balancer.", 400);
            }
            listener.setPort(port);
            restartDataPlane = true;
        }
        if (protocol != null)      listener.setProtocol(protocol);
        if (sslPolicy != null)     listener.setSslPolicy(sslPolicy);
        if (certificates != null && !certificates.isEmpty()) {
            // ModifyListener replaces only the default certificate. SNI extras
            // added via AddListenerCertificates must survive (Alchemy's Listener
            // reconcile always resends the default cert).
            replaceDefaultCertificate(listener, certificates.getFirst());
        }
        if (alpnPolicy != null)    listener.setAlpnPolicy(new ArrayList<>(alpnPolicy));
        if (defaultActions != null) {
            listener.setDefaultActions(new ArrayList<>(defaultActions));
            // update the default rule's actions
            listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                    .map(ra -> rules.getOrDefault(region, Map.of()).get(ra))
                .filter(r -> r != null && r.isDefault())
                .forEach(r -> r.setActions(new ArrayList<>(defaultActions)));
            for (Action action : defaultActions) {
                linkTgToLb(action, listener.getLoadBalancerArn());
            }
            unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
            recompileRules = true;
        }
        persistRegion(listeners, region);
        persistRegion(rules, region);
        if (restartDataPlane) {
            restartDataPlane(requireListener(region, listenerArn), region);
        } else if (recompileRules) {
            dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
        }
        return listener;
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    public Rule createRule(String region, String listenerArn, List<RuleCondition> conditions,
                            int priority, List<Action> actions, Map<String, String> initialTags) {
        requireListener(region, listenerArn);
        if (priority < 1 || priority > 50000) {
            throw new AwsException("ValidationError", "Priority must be between 1 and 50000.", 400);
        }

        Map<String, Rule> regionRules = mutableRegion(rules, region);
        List<String> existingRuleArns = listenerToRules.getOrDefault(listenerArn, List.of());
        String priorityStr = String.valueOf(priority);
        boolean priorityTaken = existingRuleArns.stream()
                .map(regionRules::get)
                .filter(Objects::nonNull)
                .anyMatch(r -> priorityStr.equals(r.getPriority()));
        if (priorityTaken) {
            throw new AwsException("PriorityInUse",
                    "The specified priority is already in use.", 400);
        }

        Listener listener = requireListener(region, listenerArn);
        LoadBalancer lb = requireLoadBalancer(region, listener.getLoadBalancerArn());
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String lbId = arnId(listener.getLoadBalancerArn());
        String listenerId = arnId(listenerArn);
        String ruleId = randomHex16();
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

        Rule rule = new Rule();
        rule.setRuleArn(ruleArn);
        rule.setListenerArn(listenerArn);
        rule.setPriority(priorityStr);
        rule.setConditions(conditions != null ? new ArrayList<>(conditions) : new ArrayList<>());
        rule.setActions(actions != null ? new ArrayList<>(actions) : new ArrayList<>());
        rule.setDefault(false);

        regionRules.put(ruleArn, rule);
        rules.put(region, regionRules);
        listenerToRules.computeIfAbsent(listenerArn, k -> new ArrayList<>()).add(ruleArn);

        // update TG → LB index for all target group actions
        for (Action a : rule.getActions()) {
            linkTgToLb(a, listener.getLoadBalancerArn());
        }

        if (!initialTags.isEmpty()) {
            tags.put(ruleArn, new LinkedHashMap<>(initialTags));
        }
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
        return rule;
    }

    public List<Rule> describeRules(String region, String listenerArn, List<String> ruleArns) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());

        if (ruleArns != null && !ruleArns.isEmpty()) {
            return ruleArns.stream()
                    .map(regionRules::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        if (listenerArn != null && !listenerArn.isEmpty()) {
            return listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                    .map(regionRules::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(r -> prioritySortKey(r.getPriority())))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>(regionRules.values());
    }

    public void deleteRule(String region, String ruleArn) {
        Map<String, Rule> regionRules = mutableRegion(rules, region);
        Rule rule = regionRules.get(ruleArn);
        if (rule == null) {
            return;
        }
        if (rule.isDefault()) {
            throw new AwsException("OperationNotPermitted",
                    "The default rule for a listener cannot be deleted.", 400);
        }
        String listenerArn = rule.getListenerArn();
        regionRules.remove(ruleArn);
        rules.put(region, regionRules);
        listenerToRules.getOrDefault(listenerArn, List.of()).remove(ruleArn);
        tags.remove(ruleArn);
        Listener listener = listeners.getOrDefault(region, Map.of()).get(listenerArn);
        if (listener != null) {
            unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
        }
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
    }

    public Rule modifyRule(String region, String ruleArn, List<RuleCondition> conditions, List<Action> actions) {
        Rule rule = requireRule(region, ruleArn);
        String listenerArn = rule.getListenerArn();
        if (conditions != null) rule.setConditions(new ArrayList<>(conditions));
        if (actions != null)    rule.setActions(new ArrayList<>(actions));
        persistRegion(rules, region);
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
        return rule;
    }

    public void setRulePriorities(String region, Map<String, Integer> arnToPriority) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());

        // validate all rules exist and are not default before touching anything
        for (Map.Entry<String, Integer> e : arnToPriority.entrySet()) {
            Rule rule = regionRules.get(e.getKey());
            if (rule == null) {
                throw new AwsException("RuleNotFound", "Rule not found: " + e.getKey(), 400);
            }
            if (rule.isDefault()) {
                throw new AwsException("OperationNotPermitted", "Cannot change priority of the default rule.", 400);
            }
            int p = e.getValue();
            if (p < 1 || p > 50000) {
                throw new AwsException("ValidationError", "Priority must be between 1 and 50000.", 400);
            }
        }

        // check for collisions with rules NOT in the update set
        Set<String> updatingArns = arnToPriority.keySet();
        Set<Integer> newPriorities = new HashSet<>(arnToPriority.values());
        for (Rule existing : regionRules.values()) {
            if (!updatingArns.contains(existing.getRuleArn()) && !existing.isDefault()) {
                try {
                    int existingPriority = Integer.parseInt(existing.getPriority());
                    if (newPriorities.contains(existingPriority)) {
                        throw new AwsException("PriorityInUse",
                                "Priority " + existingPriority + " is already in use.", 400);
                    }
                } catch (NumberFormatException ignored) { /* default rule */ }
            }
        }

        // commit
        arnToPriority.forEach((arn, priority) -> regionRules.get(arn).setPriority(String.valueOf(priority)));
        rules.put(region, regionRules);

        Set<String> affectedListeners = arnToPriority.keySet().stream()
                .map(arn -> regionRules.get(arn).getListenerArn())
                .collect(Collectors.toSet());
        affectedListeners.forEach(la -> dataPlane.recompileRules(la, getListenerRules(region, la)));
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    public void registerTargets(String region, String tgArn, List<TargetDescription> targets) {
        TargetGroup tg = requireTargetGroup(region, tgArn);
        List<TargetDescription> existing = tg.getTargets();
        for (TargetDescription t : targets) {
            // replace if same id+port already registered
            existing.removeIf(e -> e.getId().equals(t.getId()) && Objects.equals(e.getPort(), t.getPort()));
            existing.add(t);
        }
        persistRegion(targetGroups, region);
        healthChecker.addTargets(tgArn, targets, tg);
    }

    public void deregisterTargets(String region, String tgArn, List<TargetDescription> targets) {
        TargetGroup tg = requireTargetGroup(region, tgArn);
        for (TargetDescription t : targets) {
            tg.getTargets().removeIf(e -> e.getId().equals(t.getId()) && Objects.equals(e.getPort(), t.getPort()));
        }
        persistRegion(targetGroups, region);
        healthChecker.removeTargets(tgArn, targets, tg);
    }

    public List<TargetHealth> describeTargetHealth(String region, String tgArn,
                                                     List<TargetDescription> filterTargets) {
        TargetGroup tg = requireTargetGroup(region, tgArn);
        boolean hasFilterTargets = filterTargets != null && !filterTargets.isEmpty();
        List<TargetDescription> candidates = hasFilterTargets ? filterTargets : tg.getTargets();

        boolean isLambdaTg = "lambda".equals(tg.getTargetType());
        return candidates.stream().map(t -> {
            TargetHealth th = new TargetHealth();
            th.setTarget(t);
            if (isLambdaTg) {
                th.setHealthCheckPort("N/A");
                th.setState("healthy");
                return th;
            }
            int port = ElbV2HealthChecker.effectivePort(t, tg);
            th.setHealthCheckPort(String.valueOf(port));
            if (hasFilterTargets && !isRegisteredTarget(tg, t, port)) {
                th.setState("unused");
                th.setReason("Target.NotRegistered");
                th.setDescription("Target is not registered to the target group");
                return th;
            }
            ElbV2HealthChecker.TargetHealthStatus health = healthChecker.getHealth(tgArn, t.getId(), port);
            th.setState(health.state());
            th.setReason(health.reason());
            th.setDescription(health.description());
            return th;
        }).collect(Collectors.toList());
    }

    private static boolean isRegisteredTarget(TargetGroup targetGroup, TargetDescription candidate, int candidatePort) {
        return targetGroup.getTargets().stream()
                .anyMatch(registered -> Objects.equals(registered.getId(), candidate.getId())
                        && ElbV2HealthChecker.effectivePort(registered, targetGroup) == candidatePort);
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public void addTags(List<String> resourceArns, Map<String, String> newTags) {
        for (String arn : resourceArns) {
            tags.computeIfAbsent(arn, k -> new LinkedHashMap<>()).putAll(newTags);
            tags.put(arn, tags.get(arn));
        }
    }

    public void removeTags(List<String> resourceArns, List<String> tagKeys) {
        for (String arn : resourceArns) {
            Map<String, String> resourceTags = tags.get(arn);
            if (resourceTags != null) {
                tagKeys.forEach(resourceTags::remove);
                tags.put(arn, resourceTags);
            }
        }
    }

    public Map<String, Map<String, String>> describeTags(List<String> resourceArns) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String arn : resourceArns) {
            result.put(arn, tags.getOrDefault(arn, Map.of()));
        }
        return result;
    }

    // ── Listener Certificates ─────────────────────────────────────────────────

    public void addListenerCertificates(String region, String listenerArn, List<String> certArns) {
        Listener listener = requireListener(region, listenerArn);
        List<String> certificates = listener.getCertificates();
        for (String certArn : certArns) {
            if (certArn != null && !certificates.contains(certArn)) {
                certificates.add(certArn);
            }
        }
        persistRegion(listeners, region);
    }

    public void removeListenerCertificates(String region, String listenerArn, List<String> certArns) {
        Listener listener = requireListener(region, listenerArn);
        List<String> certificates = listener.getCertificates();
        String defaultCert = certificates.isEmpty() ? null : certificates.getFirst();
        if (defaultCert != null && certArns != null && certArns.contains(defaultCert)) {
            throw new AwsException("OperationNotPermitted",
                    "The default certificate cannot be removed. Use ModifyListener to change it.", 400);
        }
        if (certArns != null) {
            certificates.removeAll(certArns);
        }
        persistRegion(listeners, region);
    }

    public List<String> describeListenerCertificates(String region, String listenerArn) {
        Listener listener = requireListener(region, listenerArn);
        return new ArrayList<>(listener.getCertificates());
    }

    /**
     * Index 0 is the default certificate (CreateListener / ModifyListener).
     * Subsequent entries are SNI extras (AddListenerCertificates).
     */
    private static void replaceDefaultCertificate(Listener listener, String defaultCertArn) {
        List<String> current = listener.getCertificates();
        List<String> extras = new ArrayList<>();
        for (int i = 1; i < current.size(); i++) {
            String extra = current.get(i);
            if (extra != null && !extra.equals(defaultCertArn)) {
                extras.add(extra);
            }
        }
        List<String> updated = new ArrayList<>();
        updated.add(defaultCertArn);
        updated.addAll(extras);
        listener.setCertificates(updated);
    }

    // ── Trust Stores ──────────────────────────────────────────────────────────

    public TrustStore createTrustStore(String region, String name,
                                       String caCertificatesBundleS3Bucket,
                                       String caCertificatesBundleS3Key,
                                       String caCertificatesBundleS3ObjectVersion,
                                       Map<String, String> initialTags) {
        validateName(name, "trust store");
        if (caCertificatesBundleS3Bucket == null || caCertificatesBundleS3Bucket.isEmpty()
                || caCertificatesBundleS3Key == null || caCertificatesBundleS3Key.isEmpty()) {
            throw new AwsException("ValidationError",
                    "CaCertificatesBundleS3Bucket and CaCertificatesBundleS3Key are required.", 400);
        }
        Map<String, TrustStore> regionStores = mutableRegion(trustStores, region);
        boolean duplicate = regionStores.values().stream().anyMatch(ts -> ts.getName().equals(name));
        if (duplicate) {
            throw new AwsException("DuplicateTrustStoreName",
                    "A trust store with name '" + name + "' already exists.", 400);
        }

        int certCount = loadCaBundleCertificateCount(
                caCertificatesBundleS3Bucket, caCertificatesBundleS3Key, caCertificatesBundleS3ObjectVersion);

        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of(
                "elasticloadbalancing", region, regionResolver.getAccountId(),
                "truststore/" + name + "/" + id).toString();

        TrustStore trustStore = new TrustStore();
        trustStore.setName(name);
        trustStore.setTrustStoreArn(arn);
        trustStore.setStatus("ACTIVE");
        trustStore.setNumberOfCaCertificates(certCount);
        trustStore.setTotalRevokedEntries(0);
        trustStore.setCaCertificatesBundleS3Bucket(caCertificatesBundleS3Bucket);
        trustStore.setCaCertificatesBundleS3Key(caCertificatesBundleS3Key);
        trustStore.setCaCertificatesBundleS3ObjectVersion(caCertificatesBundleS3ObjectVersion);
        trustStore.setRegion(region);

        regionStores.put(arn, trustStore);
        trustStores.put(region, regionStores);
        if (initialTags != null && !initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        return trustStore;
    }

    public List<TrustStore> describeTrustStores(String region, List<String> arns, List<String> names) {
        Map<String, TrustStore> regionStores = trustStores.getOrDefault(region, Map.of());
        List<TrustStore> result = new ArrayList<>(regionStores.values());

        if (arns != null && !arns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(arns);
            result = result.stream()
                    .filter(ts -> arnSet.contains(ts.getTrustStoreArn()))
                    .collect(Collectors.toList());
            if (result.size() != arnSet.size()) {
                throw new AwsException("TrustStoreNotFound",
                        "One or more trust stores not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream()
                    .filter(ts -> nameSet.contains(ts.getName()))
                    .collect(Collectors.toList());
            if (result.size() != nameSet.size()) {
                throw new AwsException("TrustStoreNotFound",
                        "One or more trust stores not found.", 400);
            }
        }
        return result;
    }

    public TrustStore modifyTrustStore(String region, String arn,
                                       String caCertificatesBundleS3Bucket,
                                       String caCertificatesBundleS3Key,
                                       String caCertificatesBundleS3ObjectVersion) {
        TrustStore trustStore = requireTrustStore(region, arn);
        if (caCertificatesBundleS3Bucket == null || caCertificatesBundleS3Bucket.isEmpty()
                || caCertificatesBundleS3Key == null || caCertificatesBundleS3Key.isEmpty()) {
            throw new AwsException("ValidationError",
                    "CaCertificatesBundleS3Bucket and CaCertificatesBundleS3Key are required.", 400);
        }
        int certCount = loadCaBundleCertificateCount(
                caCertificatesBundleS3Bucket, caCertificatesBundleS3Key, caCertificatesBundleS3ObjectVersion);
        trustStore.setCaCertificatesBundleS3Bucket(caCertificatesBundleS3Bucket);
        trustStore.setCaCertificatesBundleS3Key(caCertificatesBundleS3Key);
        trustStore.setCaCertificatesBundleS3ObjectVersion(caCertificatesBundleS3ObjectVersion);
        trustStore.setNumberOfCaCertificates(certCount);
        persistRegion(trustStores, region);
        return trustStore;
    }

    public void deleteTrustStore(String region, String arn) {
        TrustStore trustStore = requireTrustStore(region, arn);
        if (isTrustStoreInUse(trustStore.getTrustStoreArn())) {
            throw new AwsException("TrustStoreInUse",
                    "The specified trust store is currently in use.", 400);
        }
        Map<String, TrustStore> regionStores = mutableRegion(trustStores, region);
        regionStores.remove(arn);
        tags.remove(arn);
        persistRegion(trustStores, region);
    }

    public String getTrustStoreCaCertificatesBundleLocation(String region, String arn) {
        TrustStore trustStore = requireTrustStore(region, arn);
        return "https://s3." + region + ".amazonaws.com/"
                + trustStore.getCaCertificatesBundleS3Bucket() + "/"
                + trustStore.getCaCertificatesBundleS3Key();
    }

    public String getTrustStoreRevocationContentLocation(String region, String arn, Long revocationId) {
        TrustStore trustStore = requireTrustStore(region, arn);
        if (revocationId == null) {
            throw new AwsException("ValidationError", "RevocationId is required.", 400);
        }
        TrustStoreRevocation revocation = trustStore.getRevocations().stream()
                .filter(r -> r.getRevocationId() == revocationId)
                .findFirst()
                .orElseThrow(() -> new AwsException("RevocationIdNotFound",
                        "The specified revocation identifier was not found.", 400));
        return "https://s3." + region + ".amazonaws.com/"
                + revocation.getS3Bucket() + "/" + revocation.getS3Key();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LoadBalancer requireLoadBalancer(String region, String arn) {
        LoadBalancer lb = loadBalancers.getOrDefault(region, Map.of()).get(arn);
        if (lb == null) {
            throw new AwsException("LoadBalancerNotFound",
                    "One or more load balancers not found.", 400);
        }
        return lb;
    }

    private List<AvailabilityZone> resolveAvailabilityZones(String region, List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Subnet> subnetsById = resolveSubnetsById(region, subnetIds);
        List<AvailabilityZone> availabilityZones = new ArrayList<>();
        for (String subnetId : subnetIds) {
            Subnet subnet = subnetsById.get(subnetId);
            AvailabilityZone availabilityZone = new AvailabilityZone();
            availabilityZone.setSubnetId(subnet.getSubnetId());
            availabilityZone.setZoneName(subnet.getAvailabilityZone());
            availabilityZones.add(availabilityZone);
        }
        return availabilityZones;
    }

    private String resolveSubnetVpcId(String region, String lbType, List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            return null;
        }

        Map<String, Subnet> subnetsById = resolveSubnetsById(region, subnetIds);

        if ("application".equals(lbType)) {
            if (subnetIds.size() < 2) {
                throw new AwsException("InvalidConfigurationRequest",
                        "Application Load Balancers must be attached to subnets in at least two Availability Zones.", 400);
            }

            long distinctAvailabilityZones = subnetIds.stream()
                    .map(subnetsById::get)
                    .map(Subnet::getAvailabilityZone)
                    .distinct()
                    .count();
            if (distinctAvailabilityZones < 2) {
                throw new AwsException("InvalidConfigurationRequest",
                        "Application Load Balancers must be attached to subnets in at least two Availability Zones.", 400);
            }
        }

        Set<String> vpcIds = subnetIds.stream()
                .map(subnetsById::get)
                .map(Subnet::getVpcId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (vpcIds.size() > 1) {
            throw new AwsException("InvalidConfigurationRequest",
                    "All subnets must belong to the same VPC.", 400);
        }

        return vpcIds.iterator().next();
    }

    private Map<String, Subnet> resolveSubnetsById(String region, List<String> subnetIds) {
        Map<String, Subnet> subnetsById = new LinkedHashMap<>();
        int index = 0;
        for (String subnetId : subnetIds) {
            if (subnetId == null || subnetId.isBlank()) {
                throw new AwsException("SubnetNotFound",
                        "The subnet ID '" + subnetId + "' does not exist", 400);
            }
            Subnet subnet = lookupEc2Subnet(region, subnetId);
            if (subnet == null) {
                // Accept IDs the EC2 store cannot see under this RequestContext
                // (wrong account/region key, or a caller that described subnets
                // out of band). Real ELBv2 still returns SubnetNotFound for a
                // truly missing id; we only synthesize a valid-looking subnet-*
                // so CreateLoadBalancer can proceed. AZ is positional so two
                // IDs always span two zones (ALB requirement).
                subnet = synthesizeAcceptedSubnet(region, subnetId, index);
            }
            subnetsById.put(subnetId, subnet);
            index++;
        }
        return subnetsById;
    }

    private Subnet lookupEc2Subnet(String region, String subnetId) {
        if (ec2Service == null) {
            return null;
        }
        try {
            return ec2Service.findSubnetById(region, subnetId).orElse(null);
        } catch (AwsException e) {
            if ("InvalidSubnetID.NotFound".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private static Subnet synthesizeAcceptedSubnet(String region, String subnetId, int index) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId("vpc-default");
        subnet.setRegion(region);
        subnet.setState("available");
        subnet.setAvailabilityZone(region + (char) ('a' + Math.floorMod(index, 3)));
        return subnet;
    }
    private TargetGroup requireTargetGroup(String region, String arn) {
        TargetGroup tg = targetGroups.getOrDefault(region, Map.of()).get(arn);
        if (tg == null) {
            throw new AwsException("TargetGroupNotFound",
                    "One or more target groups not found.", 400);
        }
        return tg;
    }

    private Listener requireListener(String region, String arn) {
        Listener l = listeners.getOrDefault(region, Map.of()).get(arn);
        if (l == null) {
            throw new AwsException("ListenerNotFound",
                    "One or more listeners not found.", 400);
        }
        return l;
    }

    private Rule requireRule(String region, String arn) {
        Rule r = rules.getOrDefault(region, Map.of()).get(arn);
        if (r == null) {
            throw new AwsException("RuleNotFound", "One or more rules not found.", 400);
        }
        return r;
    }

    private TrustStore requireTrustStore(String region, String arn) {
        TrustStore trustStore = trustStores.getOrDefault(region, Map.of()).get(arn);
        if (trustStore == null) {
            throw new AwsException("TrustStoreNotFound",
                    "One or more trust stores not found.", 400);
        }
        return trustStore;
    }

    private boolean isTrustStoreInUse(String trustStoreArn) {
        for (Map<String, Listener> regionListeners : listeners.values()) {
            for (Listener listener : regionListeners.values()) {
                Map<String, String> attributes = listener.getAttributes();
                if (attributes == null) {
                    continue;
                }
                String mode = attributes.get("mutualAuthentication.trustStoreArn");
                if (trustStoreArn.equals(mode)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int loadCaBundleCertificateCount(String bucket, String key, String versionId) {
        if (s3Service == null) {
            throw new AwsException("CaCertificatesBundleNotFound",
                    "The specified CA certificates bundle could not be found.", 400);
        }
        S3Object object;
        try {
            object = versionId != null && !versionId.isEmpty()
                    ? s3Service.getObject(bucket, key, versionId)
                    : s3Service.getObject(bucket, key);
        } catch (AwsException e) {
            if ("NoSuchBucket".equals(e.getErrorCode())
                    || "NoSuchKey".equals(e.getErrorCode())
                    || "NoSuchVersion".equals(e.getErrorCode())) {
                throw new AwsException("CaCertificatesBundleNotFound",
                        "The specified CA certificates bundle could not be found.", 400);
            }
            throw e;
        }
        byte[] data = object.getData();
        if (data == null || data.length == 0) {
            throw new AwsException("InvalidCaCertificatesBundle",
                    "The CA certificates bundle is empty.", 400);
        }
        String pem = new String(data, StandardCharsets.UTF_8);
        Matcher matcher = PEM_CERT.matcher(pem);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        if (count == 0) {
            throw new AwsException("InvalidCaCertificatesBundle",
                    "The CA certificates bundle does not contain a valid certificate.", 400);
        }
        return count;
    }

    public TargetGroup getTargetGroup(String region, String arn) {
        return targetGroups.getOrDefault(region, Map.of()).get(arn);
    }

    public TargetGroup getTargetGroupByName(String region, String name) {
        return targetGroups.getOrDefault(region, Map.of()).values().stream()
                .filter(tg -> tg.getTargetGroupName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void shiftListenerForward(String region, String listenerArn,
                                     String blueTgArn, String greenTgArn, int greenWeightPct) {
        Rule defaultRule = listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                .map(arn -> rules.getOrDefault(region, Map.of()).get(arn))
                .filter(r -> r != null && r.isDefault())
                .findFirst()
                .orElse(null);
        if (defaultRule == null) {
            return;
        }
        Action action = new Action();
        action.setType("forward");
        if (greenWeightPct >= 100) {
            action.setTargetGroupArn(greenTgArn);
        } else {
            Action.TargetGroupTuple blueTuple = new Action.TargetGroupTuple();
            blueTuple.setTargetGroupArn(blueTgArn);
            blueTuple.setWeight(100 - greenWeightPct);
            Action.TargetGroupTuple greenTuple = new Action.TargetGroupTuple();
            greenTuple.setTargetGroupArn(greenTgArn);
            greenTuple.setWeight(greenWeightPct);
            action.setTargetGroups(List.of(blueTuple, greenTuple));
        }
        defaultRule.setActions(List.of(action));
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
    }

    /**
     * Data-plane bind is best-effort. A local HTTP proxy failure (null port,
     * NLB/TCP/TLS, port conflict, NPE) must not fail CreateListener — that
     * leaked as {@code InternalFailure: Unexpected error: null}.
     */
    private void startDataPlane(Listener listener, String region) {
        if (dataPlane == null || listener == null) {
            return;
        }
        try {
            dataPlane.startListener(listener, region, getListenerRules(region, listener.getListenerArn()));
        } catch (RuntimeException ignored) {
            // already logged inside ElbV2DataPlane when the real impl is used
        }
    }

    private void restartDataPlane(Listener listener, String region) {
        if (dataPlane == null || listener == null) {
            return;
        }
        try {
            dataPlane.restartListener(listener, region, getListenerRules(region, listener.getListenerArn()));
        } catch (RuntimeException ignored) {
        }
    }

    private List<Rule> getListenerRules(String region, String listenerArn) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());
        return listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                .map(regionRules::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(r -> {
                    if ("default".equals(r.getPriority())) return Integer.MAX_VALUE;
                    try { return Integer.parseInt(r.getPriority()); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
                }))
                .collect(Collectors.toList());
    }

    private static void validateName(String name, String resource) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("ValidationError", "Name is required for " + resource + ".", 400);
        }
        if (name.length() > 32) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' exceeds 32 characters.", 400);
        }
        if (!name.matches("[a-zA-Z0-9-]+")) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' contains invalid characters. Use alphanumeric characters and hyphens.", 400);
        }
        if (name.startsWith("-") || name.endsWith("-")) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' cannot start or end with a hyphen.", 400);
        }
    }

    private static String randomHex16() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String lbTypePrefix(String type) {
        return switch (type) {
            case "network" -> "net";
            case "gateway" -> "gwy";
            default -> "app";
        };
    }

    // extracts the last path segment of an ARN (the random hex ID)
    private static String arnId(String arn) {
        int last = arn.lastIndexOf('/');
        return last >= 0 ? arn.substring(last + 1) : arn;
    }

    private static int prioritySortKey(String priority) {
        if ("default".equals(priority)) return Integer.MAX_VALUE;
        try { return Integer.parseInt(priority); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    private Rule buildDefaultRule(String region, String listenerArn, LoadBalancer lb, String lbId,
                                   String listenerId, List<Action> defaultActions) {
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String ruleId = randomHex16();
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

        Rule rule = new Rule();
        rule.setRuleArn(ruleArn);
        rule.setListenerArn(listenerArn);
        rule.setPriority("default");
        rule.setConditions(new ArrayList<>());
        rule.setActions(defaultActions != null ? new ArrayList<>(defaultActions) : new ArrayList<>());
        rule.setDefault(true);
        return rule;
    }

    private void linkTgToLb(Action action, String lbArn) {
        if (action == null || !"forward".equals(action.getType())) {
            return;
        }
        if (action.getTargetGroupArn() != null) {
            tgToLbs.computeIfAbsent(action.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet()).add(lbArn);
            addLoadBalancerReference(action.getTargetGroupArn(), lbArn);
        }
        List<Action.TargetGroupTuple> tuples = action.getTargetGroups();
        if (tuples == null) {
            return;
        }
        for (Action.TargetGroupTuple t : tuples) {
            if (t != null && t.getTargetGroupArn() != null) {
                tgToLbs.computeIfAbsent(t.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet()).add(lbArn);
                addLoadBalancerReference(t.getTargetGroupArn(), lbArn);
            }
        }
    }

    private void addLoadBalancerReference(String targetGroupArn, String loadBalancerArn) {
        for (Map.Entry<String, Map<String, TargetGroup>> entry : targetGroups.entrySet()) {
            TargetGroup targetGroup = entry.getValue().get(targetGroupArn);
            if (targetGroup != null) {
                if (!targetGroup.getLoadBalancerArns().contains(loadBalancerArn)) {
                    targetGroup.getLoadBalancerArns().add(loadBalancerArn);
                    targetGroups.put(entry.getKey(), entry.getValue());
                }
                return;
            }
        }
    }

    /**
     * Drops {@code lbArn} from every target group that is no longer referenced by any of the
     * load balancer's remaining listeners or rules. Called after a listener or rule is removed
     * so a target group can be deleted once nothing routes to it (avoids a stale "in use" guard
     * during teardown).
     */
    private void unlinkUnreferencedTargetGroups(String region, String lbArn) {
        Set<String> referenced = new HashSet<>();
        Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
        for (Listener listener : regionListeners.values()) {
            if (lbArn.equals(listener.getLoadBalancerArn())) {
                listener.getDefaultActions().forEach(a -> collectActionTargetGroups(a, referenced));
            }
        }
        for (Rule rule : rules.getOrDefault(region, Map.of()).values()) {
            Listener listener = regionListeners.get(rule.getListenerArn());
            if (listener != null && lbArn.equals(listener.getLoadBalancerArn())) {
                rule.getActions().forEach(a -> collectActionTargetGroups(a, referenced));
            }
        }
        tgToLbs.forEach((tgArn, lbSet) -> {
            if (!referenced.contains(tgArn)) {
                lbSet.remove(lbArn);
                removeLoadBalancerReference(tgArn, lbArn);
            }
        });
    }

    private void removeLoadBalancerReference(String targetGroupArn, String loadBalancerArn) {
        for (Map.Entry<String, Map<String, TargetGroup>> entry : targetGroups.entrySet()) {
            TargetGroup targetGroup = entry.getValue().get(targetGroupArn);
            if (targetGroup != null && targetGroup.getLoadBalancerArns().remove(loadBalancerArn)) {
                targetGroups.put(entry.getKey(), entry.getValue());
                return;
            }
        }
    }

    private static void collectActionTargetGroups(Action action, Set<String> out) {
        if (action == null) {
            return;
        }
        if (action.getTargetGroupArn() != null) {
            out.add(action.getTargetGroupArn());
        }
        List<Action.TargetGroupTuple> tuples = action.getTargetGroups();
        if (tuples == null) {
            return;
        }
        for (Action.TargetGroupTuple t : tuples) {
            if (t != null && t.getTargetGroupArn() != null) {
                out.add(t.getTargetGroupArn());
            }
        }
    }
}

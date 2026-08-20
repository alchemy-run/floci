package io.github.hectorvent.floci.services.route53;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.route53.model.ChangeInfo;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.QueryLoggingConfig;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.ZoneVpc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class Route53Service {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record CreateZoneResult(HostedZone zone, ChangeInfo change) {}

    private final StorageBackend<String, HostedZone> zoneStore;
    private final StorageBackend<String, List<ResourceRecordSet>> recordStore;
    private final StorageBackend<String, HealthCheck> healthCheckStore;
    private final StorageBackend<String, ChangeInfo> changeStore;
    private final StorageBackend<String, Map<String, String>> tagStore;
    private final StorageBackend<String, QueryLoggingConfig> queryLogStore;
    private final StorageBackend<String, ZoneVpc> vpcAuthStore;
    private final List<String> nameServers;

    @Inject
    public Route53Service(StorageFactory factory, EmulatorConfig config) {
        this.zoneStore = factory.create("route53", "route53-zones.json",
                new TypeReference<Map<String, HostedZone>>() {});
        this.recordStore = factory.create("route53", "route53-records.json",
                new TypeReference<Map<String, List<ResourceRecordSet>>>() {});
        this.healthCheckStore = factory.create("route53", "route53-health-checks.json",
                new TypeReference<Map<String, HealthCheck>>() {});
        this.changeStore = factory.create("route53", "route53-changes.json",
                new TypeReference<Map<String, ChangeInfo>>() {});
        this.tagStore = factory.create("route53", "route53-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.queryLogStore = factory.create("route53", "route53-query-logs.json",
                new TypeReference<Map<String, QueryLoggingConfig>>() {});
        this.vpcAuthStore = factory.create("route53", "route53-vpc-auth.json",
                new TypeReference<Map<String, ZoneVpc>>() {});

        EmulatorConfig.Route53ServiceConfig r53 = config.services().route53();
        this.nameServers = List.of(
                r53.defaultNameserver1(),
                r53.defaultNameserver2(),
                r53.defaultNameserver3(),
                r53.defaultNameserver4()
        );
    }

    // ── Hosted Zones ──────────────────────────────────────────────────────────

    public synchronized CreateZoneResult createHostedZone(String name, String callerReference,
                                                           String comment, boolean privateZone,
                                                           String vpcId, String vpcRegion) {
        String normalizedName = normalizeName(name);

        for (HostedZone existing : zoneStore.scan(k -> true)) {
            if (existing.getCallerReference().equals(callerReference)) {
                throw new AwsException("HostedZoneAlreadyExists",
                        "A hosted zone with caller reference " + callerReference + " already exists.", 409);
            }
        }

        if (privateZone && (vpcId == null || vpcId.isBlank())) {
            throw new AwsException("InvalidInput",
                    "A VPC is required when creating a private hosted zone.", 400);
        }

        String id = generateZoneId();
        HostedZone zone = new HostedZone(id, normalizedName, callerReference, comment, privateZone);
        if (vpcId != null && !vpcId.isBlank()) {
            zone.getVpcs().add(new ZoneVpc(vpcId, vpcRegion != null ? vpcRegion : "us-east-1"));
            zone.setPrivateZone(true);
        }
        zoneStore.put(id, zone);
        recordStore.put(id, buildDefaultRecords(normalizedName));
        ChangeInfo change = newChange(null);
        return new CreateZoneResult(zone, change);
    }

    public HostedZone getHostedZone(String id) {
        String zoneId = normalizeZoneId(id);
        HostedZone zone = zoneStore.get(zoneId).orElseThrow(() ->
                new AwsException("NoSuchHostedZone",
                        "No hosted zone found with ID: " + zoneId, 404));
        zone.setResourceRecordSetCount(recordCount(zoneId));
        return zone;
    }

    public HostedZone updateHostedZoneComment(String id, String comment) {
        HostedZone zone = getHostedZone(id);
        zone.setComment(comment);
        zoneStore.put(zone.getId(), zone);
        return zone;
    }

    public synchronized ChangeInfo deleteHostedZone(String id) {
        HostedZone zone = getHostedZone(id);
        String zoneId = zone.getId();
        List<ResourceRecordSet> records = recordStore.get(zoneId).orElse(List.of());
        long nonDefault = records.stream()
                .filter(r -> !isApexSoaOrNs(r, zone.getName()))
                .count();
        if (nonDefault > 0) {
            throw new AwsException("HostedZoneNotEmpty",
                    "The hosted zone contains resource record sets in addition to the default NS and SOA records.", 400);
        }
        zoneStore.delete(zoneId);
        recordStore.delete(zoneId);
        tagStore.delete("hostedzone/" + zoneId);
        for (QueryLoggingConfig cfg : queryLogStore.scan(k -> true)) {
            if (zoneId.equals(cfg.getHostedZoneId())) {
                queryLogStore.delete(cfg.getId());
            }
        }
        for (String key : new ArrayList<>(vpcAuthStore.keys())) {
            if (key.startsWith(zoneId + "/")) {
                vpcAuthStore.delete(key);
            }
        }
        return newChange(null);
    }

    public ChangeInfo associateVpc(String id, String vpcId, String vpcRegion) {
        HostedZone zone = getHostedZone(id);
        if (!zone.isPrivateZone()) {
            throw new AwsException("PublicZoneVPCAssociation",
                    "Only private hosted zones can be associated with a VPC.", 400);
        }
        ZoneVpc vpc = new ZoneVpc(vpcId, vpcRegion != null ? vpcRegion : "us-east-1");
        if (zone.getVpcs().stream().noneMatch(v -> v.equals(vpc))) {
            zone.getVpcs().add(vpc);
            zoneStore.put(zone.getId(), zone);
        }
        return newChange(null);
    }

    public ChangeInfo disassociateVpc(String id, String vpcId, String vpcRegion) {
        HostedZone zone = getHostedZone(id);
        ZoneVpc vpc = new ZoneVpc(vpcId, vpcRegion != null ? vpcRegion : "us-east-1");
        if (zone.getVpcs().size() == 1 && zone.getVpcs().contains(vpc)) {
            throw new AwsException("LastVPCAssociation",
                    "The last VPC cannot be disassociated from a private hosted zone.", 400);
        }
        boolean removed = zone.getVpcs().removeIf(v -> v.getVpcId().equals(vpcId));
        if (!removed) {
            throw new AwsException("VPCAssociationNotFound",
                    "The VPC is not associated with the hosted zone.", 404);
        }
        zoneStore.put(zone.getId(), zone);
        return newChange(null);
    }

    public ZoneVpc createVpcAssociationAuthorization(String id, String vpcId, String vpcRegion) {
        HostedZone zone = getHostedZone(id);
        ZoneVpc vpc = new ZoneVpc(vpcId, vpcRegion != null ? vpcRegion : "us-east-1");
        vpcAuthStore.put(zone.getId() + "/" + vpc.getVpcId(), vpc);
        return vpc;
    }

    public void deleteVpcAssociationAuthorization(String id, String vpcId) {
        HostedZone zone = getHostedZone(id);
        String key = zone.getId() + "/" + vpcId;
        if (vpcAuthStore.get(key).isEmpty()) {
            throw new AwsException("VPCAssociationAuthorizationNotFound",
                    "No VPC association authorization found.", 404);
        }
        vpcAuthStore.delete(key);
    }

    public List<ZoneVpc> listVpcAssociationAuthorizations(String id) {
        HostedZone zone = getHostedZone(id);
        String prefix = zone.getId() + "/";
        List<ZoneVpc> result = new ArrayList<>();
        for (ZoneVpc vpc : vpcAuthStore.scan(k -> k.startsWith(prefix))) {
            result.add(vpc);
        }
        return result;
    }

    public List<HostedZone> listHostedZonesByVpc(String vpcId, String vpcRegion) {
        List<HostedZone> result = new ArrayList<>();
        for (HostedZone zone : zoneStore.scan(k -> true)) {
            zone.setResourceRecordSetCount(recordCount(zone.getId()));
            boolean match = zone.getVpcs().stream().anyMatch(v ->
                    v.getVpcId().equals(vpcId)
                            && (vpcRegion == null || vpcRegion.isBlank() || vpcRegion.equals(v.getVpcRegion())));
            if (match) {
                result.add(zone);
            }
        }
        return result;
    }

    public QueryLoggingConfig createQueryLoggingConfig(String hostedZoneId, String logGroupArn) {
        HostedZone zone = getHostedZone(hostedZoneId);
        for (QueryLoggingConfig existing : queryLogStore.scan(k -> true)) {
            if (zone.getId().equals(existing.getHostedZoneId())) {
                throw new AwsException("QueryLoggingConfigAlreadyExists",
                        "A query logging config already exists for this hosted zone.", 409);
            }
        }
        String id = UUID.randomUUID().toString();
        QueryLoggingConfig cfg = new QueryLoggingConfig(id, zone.getId(), logGroupArn);
        queryLogStore.put(id, cfg);
        return cfg;
    }

    public QueryLoggingConfig getQueryLoggingConfig(String id) {
        return queryLogStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchQueryLoggingConfig",
                        "No query logging config found with ID: " + id, 404));
    }

    public void deleteQueryLoggingConfig(String id) {
        getQueryLoggingConfig(id);
        queryLogStore.delete(id);
    }

    public List<QueryLoggingConfig> listQueryLoggingConfigs(String hostedZoneId) {
        if (hostedZoneId != null && !hostedZoneId.isBlank()) {
            String zoneId = normalizeZoneId(hostedZoneId);
            getHostedZone(zoneId);
            return queryLogStore.scan(k -> true).stream()
                    .filter(c -> zoneId.equals(c.getHostedZoneId()))
                    .toList();
        }
        return new ArrayList<>(queryLogStore.scan(k -> true));
    }

    public record DnsAnswer(String recordName, String recordType, long ttl, List<String> records) {}

    public DnsAnswer testDnsAnswer(String hostedZoneId, String recordName, String recordType) {
        HostedZone zone = getHostedZone(hostedZoneId);
        String name = normalizeName(recordName);
        List<ResourceRecordSet> records = recordStore.get(zone.getId()).orElse(List.of());
        ResourceRecordSet match = records.stream()
                .filter(r -> r.getName().equals(name) && r.getType().equals(recordType))
                .findFirst()
                .orElse(null);
        if (match == null) {
            return new DnsAnswer(name, recordType, 0L, List.of());
        }
        List<String> values = match.getRecords() == null ? List.of()
                : match.getRecords().stream().map(ResourceRecord::getValue).toList();
        return new DnsAnswer(match.getName(), match.getType(),
                match.getTtl() != null ? match.getTtl() : 0L, values);
    }

    public List<HostedZone> listHostedZones(String marker, int maxItems) {
        List<HostedZone> all = new ArrayList<>(zoneStore.scan(k -> true));
        all.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (HostedZone zone : all) {
            zone.setResourceRecordSetCount(recordCount(zone.getId()));
        }
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    public List<HostedZone> listHostedZonesByName(String dnsName, int maxItems) {
        return listHostedZonesByName(dnsName, null, maxItems);
    }

    /**
     * AWS {@code ListHostedZonesByName} order: labels reversed, then
     * lexicographic (so {@code example.com.} sorts as {@code com.example}).
     * Pagination starts at {@code dnsName} (inclusive). Same-name public zones
     * sort before private so {@code MaxItems=1} exact lookups used by Alchemy
     * ECS domain wiring and HostedZone adoption return the public zone.
     */
    public List<HostedZone> listHostedZonesByName(String dnsName, String hostedZoneId, int maxItems) {
        List<HostedZone> all = new ArrayList<>(zoneStore.scan(k -> true));
        for (HostedZone zone : all) {
            zone.setName(normalizeName(zone.getName()));
            zone.setResourceRecordSetCount(recordCount(zone.getId()));
        }
        all.sort(hostedZoneByNameOrder());

        if (dnsName != null && !dnsName.isEmpty()) {
            String normalized = normalizeName(dnsName);
            int start = 0;
            while (start < all.size() && compareDnsNames(all.get(start).getName(), normalized) < 0) {
                start++;
            }
            all = new ArrayList<>(all.subList(start, all.size()));
        }
        if (hostedZoneId != null && !hostedZoneId.isEmpty()) {
            String id = normalizeZoneId(hostedZoneId);
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (id.equals(all.get(i).getId())) {
                    idx = i;
                    break;
                }
            }
            all = new ArrayList<>(all.subList(idx, all.size()));
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    public long getHostedZoneCount() {
        return zoneStore.keys().size();
    }

    // ── Resource Record Sets ──────────────────────────────────────────────────

    public synchronized ChangeInfo changeResourceRecordSets(String zoneId,
                                                             List<Map<String, Object>> changes,
                                                             String comment) {
        HostedZone zone = getHostedZone(zoneId);
        String id = zone.getId();
        List<ResourceRecordSet> current = new ArrayList<>(
                recordStore.get(id).orElse(new ArrayList<>()));

        // Validate all changes before applying any
        for (Map<String, Object> change : changes) {
            String action = (String) change.get("action");
            ResourceRecordSet rrs = (ResourceRecordSet) change.get("rrs");
            validateChange(action, rrs, current, zone.getName());
        }

        // Apply all changes
        for (Map<String, Object> change : changes) {
            String action = (String) change.get("action");
            ResourceRecordSet rrs = (ResourceRecordSet) change.get("rrs");
            applyChange(action, rrs, current);
        }

        zone.setResourceRecordSetCount(current.size());
        zoneStore.put(id, zone);
        recordStore.put(id, current);
        return newChange(comment);
    }

    public List<ResourceRecordSet> listResourceRecordSets(String zoneId, String startName,
                                                           String startType, int maxItems) {
        HostedZone zone = getHostedZone(zoneId);
        List<ResourceRecordSet> records = new ArrayList<>(
                recordStore.get(zone.getId()).orElse(List.of()));

        records.sort((a, b) -> {
            int cmp = a.getName().compareTo(b.getName());
            if (cmp != 0) return cmp;
            return a.getType().compareTo(b.getType());
        });

        if (startName != null && !startName.isEmpty()) {
            String normalizedStart = normalizeName(startName);
            final String finalStartType = startType;
            records = records.stream()
                    .filter(r -> {
                        int cmp = r.getName().compareTo(normalizedStart);
                        if (cmp > 0) return true;
                        if (cmp == 0 && finalStartType != null && !finalStartType.isEmpty()) {
                            return r.getType().compareTo(finalStartType) >= 0;
                        }
                        return cmp == 0;
                    })
                    .toList();
            records = new ArrayList<>(records);
        }

        if (maxItems > 0 && records.size() > maxItems) {
            return records.subList(0, maxItems);
        }
        return records;
    }

    // ── Changes ───────────────────────────────────────────────────────────────

    public ChangeInfo getChange(String changeId) {
        String id = normalizeChangeId(changeId);
        return changeStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchChange",
                        "No change found with ID: " + id, 404));
    }

    // ── Health Checks ─────────────────────────────────────────────────────────

    public synchronized HealthCheck createHealthCheck(String callerReference, HealthCheckConfig cfg) {
        for (HealthCheck existing : healthCheckStore.scan(k -> true)) {
            if (existing.getCallerReference().equals(callerReference)) {
                throw new AwsException("HealthCheckAlreadyExists",
                        "A health check with caller reference " + callerReference + " already exists.", 409);
            }
        }
        String id = UUID.randomUUID().toString();
        HealthCheck hc = new HealthCheck(id, callerReference, cfg);
        healthCheckStore.put(id, hc);
        return hc;
    }

    public HealthCheck getHealthCheck(String id) {
        return healthCheckStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchHealthCheck",
                        "No health check found with ID: " + id, 404));
    }

    public void deleteHealthCheck(String id) {
        getHealthCheck(id);
        healthCheckStore.delete(id);
        tagStore.delete("healthcheck/" + id);
    }

    public List<HealthCheck> listHealthChecks(String marker, int maxItems) {
        List<HealthCheck> all = new ArrayList<>(healthCheckStore.scan(k -> true));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    public HealthCheck updateHealthCheck(String id, HealthCheckConfig cfg) {
        HealthCheck hc = getHealthCheck(id);
        hc.setConfig(cfg);
        hc.setHealthCheckVersion(hc.getHealthCheckVersion() + 1);
        healthCheckStore.put(id, hc);
        return hc;
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTagsForResource(String resourceType, String resourceId) {
        return tagStore.get(resourceType + "/" + resourceId).orElse(Collections.emptyMap());
    }

    public void changeTagsForResource(String resourceType, String resourceId,
                                      List<Map<String, String>> addTags, List<String> removeTagKeys) {
        String key = resourceType + "/" + resourceId;
        Map<String, String> tags = new LinkedHashMap<>(tagStore.get(key).orElse(new LinkedHashMap<>()));
        if (removeTagKeys != null) {
            removeTagKeys.forEach(tags::remove);
        }
        if (addTags != null) {
            addTags.forEach(t -> {
                if (t.get("Key") != null) {
                    tags.put(t.get("Key"), t.getOrDefault("Value", ""));
                }
            });
        }
        tagStore.put(key, tags);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public List<String> getNameServers() {
        return nameServers;
    }

    private static String normalizeName(String name) {
        if (name == null || name.isEmpty()) return name;
        return name.endsWith(".") ? name : name + ".";
    }

    static String normalizeZoneId(String id) {
        if (id == null) return null;
        String trimmed = id;
        if (trimmed.startsWith("/hostedzone/")) {
            trimmed = trimmed.substring("/hostedzone/".length());
        } else if (trimmed.startsWith("hostedzone/")) {
            trimmed = trimmed.substring("hostedzone/".length());
        }
        return trimmed;
    }

    static String normalizeChangeId(String id) {
        if (id == null) return null;
        if (id.startsWith("/change/")) {
            return id.substring("/change/".length());
        }
        if (id.startsWith("change/")) {
            return id.substring("change/".length());
        }
        return id;
    }

    static int compareDnsNames(String left, String right) {
        String[] a = dnsLabels(left);
        String[] b = dnsLabels(right);
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int cmp = a[i].compareTo(b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static String[] dnsLabels(String name) {
        String normalized = normalizeName(name).toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return new String[0];
        }
        String[] labels = normalized.split("\\.");
        for (int i = 0, j = labels.length - 1; i < j; i++, j--) {
            String tmp = labels[i];
            labels[i] = labels[j];
            labels[j] = tmp;
        }
        return labels;
    }

    private static Comparator<HostedZone> hostedZoneByNameOrder() {
        return (a, b) -> {
            int cmp = compareDnsNames(a.getName(), b.getName());
            if (cmp != 0) {
                return cmp;
            }
            if (a.isPrivateZone() != b.isPrivateZone()) {
                return a.isPrivateZone() ? 1 : -1;
            }
            return a.getId().compareTo(b.getId());
        };
    }

    private static String generateZoneId() {
        StringBuilder sb = new StringBuilder("Z");
        for (int i = 0; i < 14; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static String generateChangeId() {
        StringBuilder sb = new StringBuilder("C");
        for (int i = 0; i < 13; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private ChangeInfo newChange(String comment) {
        String id = generateChangeId();
        ChangeInfo change = new ChangeInfo(id, Instant.now().toString(), comment);
        changeStore.put(id, change);
        return change;
    }

    private List<ResourceRecordSet> buildDefaultRecords(String zoneName) {
        List<ResourceRecordSet> records = new ArrayList<>();

        ResourceRecordSet soa = new ResourceRecordSet();
        soa.setName(zoneName);
        soa.setType("SOA");
        soa.setTtl(900L);
        soa.setRecords(List.of(new ResourceRecord(
                nameServers.get(0) + " awsdns-hostmaster.amazon.com. 1 7200 900 1209600 86400")));
        records.add(soa);

        ResourceRecordSet ns = new ResourceRecordSet();
        ns.setName(zoneName);
        ns.setType("NS");
        ns.setTtl(172800L);
        ns.setRecords(nameServers.stream()
                .map(n -> new ResourceRecord(n + "."))
                .toList());
        records.add(ns);

        return records;
    }

    private boolean isApexSoaOrNs(ResourceRecordSet rrs, String zoneName) {
        return rrs.getName().equals(zoneName) &&
                ("SOA".equals(rrs.getType()) || "NS".equals(rrs.getType()));
    }

    private int recordCount(String zoneId) {
        return recordStore.get(zoneId).map(List::size).orElse(0);
    }

    private void validateChange(String action, ResourceRecordSet rrs,
                                List<ResourceRecordSet> current, String zoneName) {
        if ("DELETE".equals(action) && isApexSoaOrNs(rrs, zoneName)) {
            throw new AwsException("InvalidChangeBatch",
                    "Invalid resource record set: Deleting the SOA or NS record at the zone apex is not permitted.", 400);
        }
        if ("CREATE".equals(action)) {
            boolean exists = current.stream().anyMatch(r ->
                    r.getName().equals(rrs.getName()) &&
                    r.getType().equals(rrs.getType()) &&
                    equalOrNull(r.getSetIdentifier(), rrs.getSetIdentifier()));
            if (exists) {
                throw new AwsException("InvalidChangeBatch",
                        "Tried to create resource record set [name='" + rrs.getName() +
                        "', type='" + rrs.getType() + "'] but it already exists.", 400);
            }
        }
        if ("DELETE".equals(action)) {
            boolean found = current.stream().anyMatch(r ->
                    r.getName().equals(rrs.getName()) &&
                    r.getType().equals(rrs.getType()) &&
                    equalOrNull(r.getSetIdentifier(), rrs.getSetIdentifier()));
            if (!found) {
                throw new AwsException("InvalidChangeBatch",
                        "Tried to delete resource record set [name='" + rrs.getName() +
                        "', type='" + rrs.getType() + "'] but it was not found.", 400);
            }
        }
    }

    private void applyChange(String action, ResourceRecordSet rrs, List<ResourceRecordSet> current) {
        switch (action) {
            case "CREATE" -> current.add(rrs);
            case "DELETE" -> current.removeIf(r ->
                    r.getName().equals(rrs.getName()) && r.getType().equals(rrs.getType()) &&
                    equalOrNull(r.getSetIdentifier(), rrs.getSetIdentifier()));
            case "UPSERT" -> {
                current.removeIf(r ->
                        r.getName().equals(rrs.getName()) && r.getType().equals(rrs.getType()) &&
                        equalOrNull(r.getSetIdentifier(), rrs.getSetIdentifier()));
                current.add(rrs);
            }
        }
    }

    private static boolean equalOrNull(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}

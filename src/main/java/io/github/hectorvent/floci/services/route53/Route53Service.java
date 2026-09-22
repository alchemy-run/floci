package io.github.hectorvent.floci.services.route53;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ChangeInfo;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.QueryLoggingConfig;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class Route53Service {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_VPC_ASSOCIATIONS_PER_ZONE = 300;
    private static final int MAX_VPC_ASSOCIATION_AUTHORIZATIONS = 1000;

    public record CreateZoneResult(HostedZone zone, ChangeInfo change) {}
    private record OwnedZone(String accountId, HostedZone zone) {}

    private final StorageBackend<String, HostedZone> zoneStore;
    private final StorageBackend<String, List<ResourceRecordSet>> recordStore;
    private final StorageBackend<String, HealthCheck> healthCheckStore;
    private final StorageBackend<String, ChangeInfo> changeStore;
    private final StorageBackend<String, Map<String, String>> tagStore;
    private final StorageBackend<String, QueryLoggingConfig> queryLogStore;
    private final StorageBackend<String, List<VpcAssociation>> vpcAuthorizationStore;
    private final List<String> nameServers;
    private final String defaultAccountId;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;
    private final long vpcAssociationControlPlaneDelayMs;
    private final Map<String, Long> hostedZoneMutationBusyUntilNanos = new ConcurrentHashMap<>();
    private final Map<String, Long> authorizationMutationBusyUntilNanos = new ConcurrentHashMap<>();

    @Inject
    public Route53Service(StorageFactory factory, EmulatorConfig config, RegionResolver regionResolver,
                          Ec2Service ec2Service) {
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
        this.vpcAuthorizationStore = factory.create("route53", "route53-vpc-association-authorizations.json",
                new TypeReference<Map<String, List<VpcAssociation>>>() {});

        EmulatorConfig.Route53ServiceConfig r53 = config.services().route53();
        this.nameServers = List.of(
                r53.defaultNameserver1(),
                r53.defaultNameserver2(),
                r53.defaultNameserver3(),
                r53.defaultNameserver4()
        );
        this.defaultAccountId = config.defaultAccountId();
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
        this.vpcAssociationControlPlaneDelayMs = Math.max(0, r53.vpcAssociationControlPlaneDelayMs());
        migrateLegacyVpcAuthorizations(factory);
    }

    private void migrateLegacyVpcAuthorizations(StorageFactory factory) {
        AccountAwareStorageBackend<ZoneVpc> legacy = factory.create("route53", "route53-vpc-auth.json",
                new TypeReference<Map<String, ZoneVpc>>() {});
        AccountAwareStorageBackend<Boolean> migrated = factory.create("route53", "route53-vpc-auth-migrations.json",
                new TypeReference<Map<String, Boolean>>() {});
        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<List<VpcAssociation>> canonical =
                (AccountAwareStorageBackend<List<VpcAssociation>>) vpcAuthorizationStore;
        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<HostedZone> zones = (AccountAwareStorageBackend<HostedZone>) zoneStore;
        synchronized (canonical) {
            Map<String, HostedZone> rawZones = zones.scanAllAccountsWithRawKeys();
            for (AccountAwareStorageBackend.AccountEntry<ZoneVpc> entry : legacy.scanAllAccountEntries(k -> true)) {
                String accountId = entry.accountId();
                String key = entry.key();
                if (migrated.getForAccount(accountId, key).orElse(false)) {
                    continue;
                }
                ZoneVpc source = legacy.getForAccount(accountId, key).orElse(entry.value());
                int separator = key.indexOf('/');
                if (separator <= 0 || source.getVpcId() == null || source.getVpcId().isBlank()
                        || source.getVpcRegion() == null || source.getVpcRegion().isBlank()
                        || !key.substring(separator + 1).equals(source.getVpcId())) {
                    continue;
                }
                String zoneId = key.substring(0, separator);
                HostedZone zone = zones.getForAccount(accountId, zoneId)
                        .orElse(accountId.equals(defaultAccountId) ? rawZones.get(zoneId) : null);
                if (zone == null || !zoneId.equals(zone.getId())
                        || (zone.getOwnerAccountId() != null && !accountId.equals(zone.getOwnerAccountId()))) {
                    continue;
                }
                if (accountId.equals(defaultAccountId)) {
                    canonical.getForAccountMigratingLegacyKeys(accountId, zoneId, List.of(), authorizations -> true);
                }
                List<VpcAssociation> existing = canonical.getForAccount(accountId, zoneId).orElse(null);
                List<VpcAssociation> authorizations = new ArrayList<>(existing != null ? existing : List.of());
                VpcAssociation authorization = new VpcAssociation(source.getVpcId(), source.getVpcRegion());
                if (findAssociation(authorizations, authorization) == null) {
                    authorizations.add(authorization);
                    canonical.putForAccount(accountId, zoneId, authorizations);
                }
                // Mark each source once so deleting an authorization cannot resurrect it on restart.
                canonical.flush();
                migrated.putForAccount(accountId, key, true);
                migrated.flush();
            }
        }
    }

    // ── Hosted Zones ──────────────────────────────────────────────────────────

    public CreateZoneResult createHostedZone(String name, String callerReference, String comment,
                                              boolean privateZone, String vpcId, String vpcRegion) {
        if (privateZone && (vpcId == null || vpcId.isBlank())) {
            throw new AwsException("InvalidInput",
                    "A VPC is required when creating a private hosted zone.", 400);
        }
        VpcAssociation vpc = vpcId == null || vpcId.isBlank() ? null
                : new VpcAssociation(vpcId, vpcRegion != null ? vpcRegion : "us-east-1");
        return createHostedZone(name, callerReference, comment, vpc);
    }

    public synchronized CreateZoneResult createHostedZone(String name, String callerReference,
                                                           String comment, VpcAssociation vpcAssociation) {
        String normalizedName = normalizeName(name);

        for (HostedZone existing : zoneStore.scan(k -> true)) {
            if (existing.getCallerReference().equals(callerReference)) {
                throw new AwsException("HostedZoneAlreadyExists",
                        "A hosted zone with caller reference " + callerReference + " already exists.", 409);
            }
        }

        String id = generateZoneId();
        String callerAccountId = callerAccountId();
        validateVpcOwnershipIfKnown(vpcAssociation, callerAccountId);
        HostedZone zone = new HostedZone(id, normalizedName, callerReference, comment, vpcAssociation);
        zone.setOwnerAccountId(callerAccountId);
        if (vpcAssociation != null) {
            vpcAssociation.setOwnerAccountId(callerAccountId);
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
        vpcAuthorizationStore.delete(zoneId);
        return newChange(null);
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

    /**
     * The comment is cleared when the request carries none, which is what AWS does rather than
     * leaving the previous comment in place.
     */
    public synchronized HostedZone updateHostedZoneComment(String id, String comment) {
        HostedZone zone = getHostedZoneOwnedByCaller(id);
        zone.setComment(comment);
        zoneStore.put(id, zone);
        return zone;
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

    // ── VPC Associations ──────────────────────────────────────────────────────

    public synchronized VpcAssociation createVpcAssociationAuthorization(String zoneId, VpcAssociation vpc) {
        getHostedZoneOwnedByCaller(zoneId);
        rejectAuthorizationMutationOverlap(zoneId);
        resolveVpcOwnerAccount(vpc).ifPresent(vpc::setOwnerAccountId);
        List<VpcAssociation> authorizations = new ArrayList<>(vpcAuthorizationStore.get(zoneId).orElse(List.of()));
        if (findAssociation(authorizations, vpc) == null) {
            if (authorizations.size() >= MAX_VPC_ASSOCIATION_AUTHORIZATIONS) {
                throw new AwsException("TooManyVPCAssociationAuthorizations",
                        "The maximum number of VPC association authorizations has been reached for hosted zone " + zoneId + ".", 400);
            }
            authorizations.add(vpc);
            vpcAuthorizationStore.put(zoneId, authorizations);
            markMutationBusy(authorizationMutationBusyUntilNanos, zoneId);
        }
        return vpc;
    }

    public synchronized void deleteVpcAssociationAuthorization(String zoneId, VpcAssociation vpc) {
        getHostedZoneOwnedByCaller(zoneId);
        rejectAuthorizationMutationOverlap(zoneId);
        List<VpcAssociation> authorizations = new ArrayList<>(vpcAuthorizationStore.get(zoneId).orElse(List.of()));
        VpcAssociation existing = findAssociation(authorizations, vpc);
        if (existing == null) {
            throw new AwsException("VPCAssociationAuthorizationNotFound",
                    "The VPC that you specified is not authorized to be associated with the hosted zone.", 404);
        }
        authorizations.remove(existing);
        if (authorizations.isEmpty()) {
            vpcAuthorizationStore.delete(zoneId);
        } else {
            vpcAuthorizationStore.put(zoneId, authorizations);
        }
        markMutationBusy(authorizationMutationBusyUntilNanos, zoneId);
    }

    public List<VpcAssociation> listVpcAssociationAuthorizations(String zoneId) {
        getHostedZoneOwnedByCaller(zoneId);
        List<VpcAssociation> authorizations = new ArrayList<>(vpcAuthorizationStore.get(zoneId).orElse(List.of()));
        authorizations.sort((a, b) -> {
            int id = a.getVpcId().compareTo(b.getVpcId());
            return id != 0 ? id : a.getVpcRegion().compareTo(b.getVpcRegion());
        });
        return authorizations;
    }

    /** Associates a VPC with a private hosted zone. */
    public synchronized ChangeInfo associateVpcWithHostedZone(String zoneId, VpcAssociation vpc,
                                                              String comment) {
        OwnedZone ownedZone = getHostedZoneAcrossAccounts(zoneId);
        HostedZone zone = ownedZone.zone();
        if (!zone.isPrivateZone()) {
            throw new AwsException("PublicZoneVPCAssociation",
                    "You're trying to associate a VPC with a public hosted zone. "
                            + "Public hosted zones can't be associated with a VPC.", 400);
        }
        rejectHostedZoneMutationOverlap(zoneId);
        if (findAssociation(zone, vpc) == null) {
            String callerAccountId = callerAccountId();
            validateVpcOwnershipIfKnown(vpc, callerAccountId);
            List<VpcAssociation> authorizations = new ArrayList<>(
                    getVpcAuthorizationsForAccount(ownedZone.accountId(), zoneId));
            VpcAssociation authorization = findAssociation(authorizations, vpc);
            boolean crossAccount = !callerAccountId.equals(ownerAccountId(zone));
            if (crossAccount && authorization == null) {
                throw new AwsException("NotAuthorizedException",
                        "Associating the specified VPC with the specified hosted zone has not been authorized.", 401);
            }
            if (crossAccount && authorization.getOwnerAccountId() == null) {
                String resolvedOwner = resolveVpcOwnerAccount(vpc).orElseThrow(() ->
                        new AwsException("InvalidVPCId",
                                "The VPC ID that you specified either isn't a valid ID or the current account is not authorized to access this VPC.",
                                400));
                authorization.setOwnerAccountId(resolvedOwner);
                putVpcAuthorizationsForAccount(ownedZone.accountId(), zoneId, authorizations);
            }
            if (authorization != null && authorization.getOwnerAccountId() != null
                    && !authorization.getOwnerAccountId().equals(callerAccountId)) {
                throw new AwsException("NotAuthorizedException",
                        "Associating the specified VPC with the specified hosted zone has not been authorized.", 401);
            }
            if (zone.getVpcAssociations().size() >= MAX_VPC_ASSOCIATIONS_PER_ZONE) {
                throw new AwsException("LimitsExceeded",
                        "The maximum number of VPCs that can be associated with this hosted zone has been reached.", 400);
            }
            for (HostedZone other : listHostedZonesByVpc(vpc.getVpcId(), vpc.getVpcRegion())) {
                if (!other.getId().equals(zoneId) && other.getName().equals(zone.getName())) {
                    throw new AwsException("ConflictingDomainExists",
                            "The VPC that you chose, " + vpc.getVpcId() + " in region " + vpc.getVpcRegion()
                                    + ", is already associated with another hosted zone that has the same name.",
                            400);
                }
            }
            vpc.setOwnerAccountId(resolveVpcOwnerAccount(vpc).orElse(callerAccountId));
            zone.getVpcAssociations().add(vpc);
            putZoneForAccount(ownedZone.accountId(), zoneId, zone);
            markMutationBusy(hostedZoneMutationBusyUntilNanos, zoneId);
        }
        return newChange(comment);
    }

    /**
     * Disassociates a VPC from a private hosted zone. AWS refuses to remove the
     * only remaining association — the zone would become unresolvable.
     */
    public synchronized ChangeInfo disassociateVpcFromHostedZone(String zoneId, VpcAssociation vpc,
                                                                 String comment) {
        OwnedZone ownedZone = getHostedZoneAcrossAccounts(zoneId);
        HostedZone zone = ownedZone.zone();
        VpcAssociation existing = findAssociation(zone, vpc);
        if (existing == null) {
            throw new AwsException("VPCAssociationNotFound",
                    "The VPC " + vpc.getVpcId() + " in region " + vpc.getVpcRegion()
                            + " is not associated with hosted zone " + zoneId + ".", 404);
        }
        String associationOwner = existing.getOwnerAccountId();
        String callerAccountId = callerAccountId();
        String zoneOwner = ownerAccountId(zone);
        if (associationOwner == null && !zoneOwner.equals(callerAccountId)) {
            associationOwner = resolveVpcOwnerAccount(existing).orElseThrow(() ->
                    new AwsException("InvalidVPCId",
                            "The VPC ID that you specified either isn't a valid ID or the current account is not authorized to access this VPC.",
                            400));
            if (!associationOwner.equals(callerAccountId)) {
                throw new AwsException("InvalidVPCId",
                        "The VPC ID that you specified either isn't a valid ID or the current account is not authorized to access this VPC.",
                        400);
            }
            existing.setOwnerAccountId(associationOwner);
            putZoneForAccount(ownedZone.accountId(), zoneId, zone);
        }
        if (associationOwner != null
                && !associationOwner.equals(callerAccountId)
                && !zoneOwner.equals(callerAccountId)) {
            throw new AwsException("InvalidVPCId",
                    "The VPC ID that you specified either isn't a valid ID or the current account is not authorized to access this VPC.",
                    400);
        }
        if (zone.getVpcAssociations().size() == 1) {
            throw new AwsException("LastVPCAssociation",
                    "The VPC that you chose, " + vpc.getVpcId() + " in region " + vpc.getVpcRegion()
                            + ", is the last VPC that is associated with the hosted zone.", 400);
        }
        zone.getVpcAssociations().remove(existing);
        putZoneForAccount(ownedZone.accountId(), zoneId, zone);
        markMutationBusy(hostedZoneMutationBusyUntilNanos, zoneId);
        return newChange(comment);
    }

    /**
     * Returns every hosted zone associated with the given VPC, regardless of the
     * account that created the zone.
     */
    public List<HostedZone> listHostedZonesByVpc(String vpcId, String vpcRegion) {
        List<HostedZone> matches = new ArrayList<>();
        for (OwnedZone owned : allHostedZonesAcrossAccounts()) {
            HostedZone zone = owned.zone();
            if (findAssociation(zone, new VpcAssociation(vpcId, vpcRegion)) != null) {
                if (zone.getOwnerAccountId() == null) {
                    zone.setOwnerAccountId(owned.accountId());
                }
                matches.add(zone);
            }
        }
        // Tie-break by id: name alone leaves equal-named zones ordered by the backing store's
        // own iteration, which can differ between the page-1 and page-2 scans and desync the
        // id-based NextToken continuation point (skipping or repeating an entry).
        matches.sort((a, b) -> {
            int cmp = a.getName().compareTo(b.getName());
            return cmp != 0 ? cmp : a.getId().compareTo(b.getId());
        });
        return matches;
    }

    /**
     * {@link VpcAssociation} has no value equality, so associations are matched on
     * their ID and region rather than by object identity.
     */
    private static VpcAssociation findAssociation(HostedZone zone, VpcAssociation vpc) {
        if (zone.getVpcAssociations() == null) {
            return null;
        }
        return findAssociation(zone.getVpcAssociations(), vpc);
    }

    private static VpcAssociation findAssociation(List<VpcAssociation> associations, VpcAssociation vpc) {
        for (VpcAssociation candidate : associations) {
            if (vpc.getVpcId().equals(candidate.getVpcId())
                    && vpc.getVpcRegion().equals(candidate.getVpcRegion())) {
                return candidate;
            }
        }
        return null;
    }

    // ── Resource Record Sets ──────────────────────────────────────────────────

    public synchronized ChangeInfo changeResourceRecordSets(String zoneId,
                                                             List<Map<String, Object>> changes,
                                                             String comment) {
        HostedZone zone = getHostedZone(zoneId);
        String id = zone.getId();
        List<ResourceRecordSet> current = new ArrayList<>(
                recordStore.get(id).orElse(new ArrayList<>()));

        for (Map<String, Object> change : changes) {
            String action = (String) change.get("action");
            ResourceRecordSet rrs = (ResourceRecordSet) change.get("rrs");
            validateChange(action, rrs, current, zone.getName());
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

    public String getDefaultAccountId() {
        return defaultAccountId;
    }

    public String ownerAccountId(HostedZone zone) {
        return zone.getOwnerAccountId() != null ? zone.getOwnerAccountId() : defaultAccountId;
    }

    private OwnedZone getHostedZoneAcrossAccounts(String zoneId) {
        if (zoneStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<HostedZone> accountAware =
                    (AccountAwareStorageBackend<HostedZone>) rawAccountAware;
            AccountAwareStorageBackend.OwnedEntry<HostedZone> entry = accountAware.findAnyAccountEntry(zoneId)
                    .orElseThrow(() -> new AwsException("NoSuchHostedZone",
                            "No hosted zone found with ID: " + zoneId, 404));
            if (entry.value().getOwnerAccountId() == null) {
                entry.value().setOwnerAccountId(entry.account());
            }
            return new OwnedZone(entry.account(), entry.value());
        }
        HostedZone zone = getHostedZone(zoneId);
        return new OwnedZone(ownerAccountId(zone), zone);
    }

    private List<OwnedZone> allHostedZonesAcrossAccounts() {
        if (zoneStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<HostedZone> accountAware =
                    (AccountAwareStorageBackend<HostedZone>) rawAccountAware;
            return accountAware.scanAllAccountEntries(k -> true).stream()
                    .map(entry -> new OwnedZone(entry.accountId(), entry.value()))
                    .toList();
        }
        return zoneStore.scan(k -> true).stream()
                .map(zone -> new OwnedZone(ownerAccountId(zone), zone))
                .toList();
    }

    private void putZoneForAccount(String accountId, String zoneId, HostedZone zone) {
        if (zoneStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<HostedZone> accountAware =
                    (AccountAwareStorageBackend<HostedZone>) rawAccountAware;
            accountAware.putForAccount(accountId, zoneId, zone);
            return;
        }
        zoneStore.put(zoneId, zone);
    }

    private List<VpcAssociation> getVpcAuthorizationsForAccount(String accountId, String zoneId) {
        if (vpcAuthorizationStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<List<VpcAssociation>> accountAware =
                    (AccountAwareStorageBackend<List<VpcAssociation>>) rawAccountAware;
            return accountAware.getForAccount(accountId, zoneId).orElse(List.of());
        }
        return vpcAuthorizationStore.get(zoneId).orElse(List.of());
    }

    private void putVpcAuthorizationsForAccount(String accountId, String zoneId,
                                                        List<VpcAssociation> authorizations) {
        if (vpcAuthorizationStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<List<VpcAssociation>> accountAware =
                    (AccountAwareStorageBackend<List<VpcAssociation>>) rawAccountAware;
            accountAware.putForAccount(accountId, zoneId, authorizations);
            return;
        }
        vpcAuthorizationStore.put(zoneId, authorizations);
    }

    private HostedZone getHostedZoneOwnedByCaller(String zoneId) {
        String callerAccountId = callerAccountId();
        if (zoneStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<HostedZone> accountAware =
                    (AccountAwareStorageBackend<HostedZone>) rawAccountAware;
            HostedZone zone = accountAware.getForAccount(callerAccountId, zoneId).orElse(null);
            if (zone == null && callerAccountId.equals(defaultAccountId)) {
                // Only the configured default account can claim pre-account-isolation unscoped data.
                // AccountAwareStorageBackend#get migrates that legacy shape into the caller partition.
                zone = accountAware.get(zoneId).orElse(null);
            }
            if (zone == null) {
                throw new AwsException("NoSuchHostedZone",
                        "No hosted zone found with ID: " + zoneId, 404);
            }
            if (zone.getOwnerAccountId() == null) {
                zone.setOwnerAccountId(callerAccountId);
                accountAware.putForAccount(callerAccountId, zoneId, zone);
            }
            if (!callerAccountId.equals(zone.getOwnerAccountId())) {
                throw new AwsException("NoSuchHostedZone",
                        "No hosted zone found with ID: " + zoneId, 404);
            }
            return zone;
        }

        HostedZone zone = getHostedZone(zoneId);
        if (!ownerAccountId(zone).equals(callerAccountId)) {
            throw new AwsException("NoSuchHostedZone",
                    "No hosted zone found with ID: " + zoneId, 404);
        }
        return zone;
    }

    private String callerAccountId() {
        return regionResolver != null ? regionResolver.getAccountId() : defaultAccountId;
    }

    private java.util.Optional<String> resolveVpcOwnerAccount(VpcAssociation vpc) {
        if (vpc == null || ec2Service == null) {
            return java.util.Optional.empty();
        }
        return ec2Service.findVpcOwnerAccount(vpc.getVpcRegion(), vpc.getVpcId());
    }

    private void validateVpcOwnershipIfKnown(VpcAssociation vpc, String callerAccountId) {
        if (vpc == null) {
            return;
        }
        java.util.Optional<String> owner = resolveVpcOwnerAccount(vpc);
        if (owner.isPresent() && !owner.get().equals(callerAccountId)) {
            throw new AwsException("InvalidVPCId",
                    "The VPC ID that you specified either isn't a valid ID or the current account is not authorized to access this VPC.",
                    400);
        }
    }

    private void rejectHostedZoneMutationOverlap(String zoneId) {
        if (isMutationBusy(hostedZoneMutationBusyUntilNanos, zoneId)) {
            throw new AwsException("PriorRequestNotComplete",
                    "A previous request for this hosted zone is still being processed.", 400);
        }
    }

    private void rejectAuthorizationMutationOverlap(String zoneId) {
        if (isMutationBusy(authorizationMutationBusyUntilNanos, zoneId)) {
            throw new AwsException("ConcurrentModification",
                    "Another VPC association authorization mutation is still being processed.", 400);
        }
    }

    private boolean isMutationBusy(Map<String, Long> busyUntilNanos, String zoneId) {
        if (vpcAssociationControlPlaneDelayMs <= 0) {
            return false;
        }
        Long deadline = busyUntilNanos.get(zoneId);
        if (deadline == null) {
            return false;
        }
        if (System.nanoTime() >= deadline) {
            busyUntilNanos.remove(zoneId, deadline);
            return false;
        }
        return true;
    }

    private void markMutationBusy(Map<String, Long> busyUntilNanos, String zoneId) {
        if (vpcAssociationControlPlaneDelayMs <= 0) {
            return;
        }
        busyUntilNanos.put(zoneId, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(vpcAssociationControlPlaneDelayMs));
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
            ResourceRecordSet existing = findByNameTypeAndSetIdentifier(current, rrs);
            if (existing == null) {
                throw new AwsException("InvalidChangeBatch",
                        "Tried to delete resource record set " + deleteTargetDescription(rrs)
                                + " but it was not found", 400);
            }
            if (!recordSetsMatch(existing, rrs)) {
                throw new AwsException("InvalidChangeBatch",
                        "Tried to delete resource record set " + deleteTargetDescription(rrs)
                                + " but the values provided do not match the current values", 400);
            }
        }
    }

    private void applyChange(String action, ResourceRecordSet rrs, List<ResourceRecordSet> current) {
        switch (action) {
            case "CREATE" -> current.add(rrs);
            case "DELETE" -> current.removeIf(r -> recordSetsMatch(r, rrs));
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

    private static ResourceRecordSet findByNameTypeAndSetIdentifier(List<ResourceRecordSet> current,
                                                                     ResourceRecordSet rrs) {
        return current.stream()
                .filter(r -> r.getName().equals(rrs.getName())
                        && r.getType().equals(rrs.getType())
                        && Objects.equals(r.getSetIdentifier(), rrs.getSetIdentifier()))
                .findFirst()
                .orElse(null);
    }

    private static String deleteTargetDescription(ResourceRecordSet rrs) {
        String description = "[name='" + rrs.getName() + "', type='" + rrs.getType() + "'";
        if (rrs.getSetIdentifier() != null) {
            description += ", set-identifier='" + rrs.getSetIdentifier() + "'";
        }
        return description + "]";
    }

    private static boolean recordSetsMatch(ResourceRecordSet a, ResourceRecordSet b) {
        return a.getName().equals(b.getName())
                && a.getType().equals(b.getType())
                && Objects.equals(a.getSetIdentifier(), b.getSetIdentifier())
                && Objects.equals(a.getTtl(), b.getTtl())
                && Objects.equals(a.getWeight(), b.getWeight())
                && Objects.equals(a.getRegion(), b.getRegion())
                && Objects.equals(a.getFailover(), b.getFailover())
                && Objects.equals(a.getHealthCheckId(), b.getHealthCheckId())
                && aliasTargetsMatch(a.getAliasTarget(), b.getAliasTarget())
                && recordValuesMatch(a.getRecords(), b.getRecords());
    }

    private static boolean aliasTargetsMatch(AliasTarget a, AliasTarget b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.getHostedZoneId(), b.getHostedZoneId())
                && Objects.equals(a.getDnsName(), b.getDnsName())
                && a.isEvaluateTargetHealth() == b.isEvaluateTargetHealth();
    }

    private static boolean recordValuesMatch(List<ResourceRecord> a, List<ResourceRecord> b) {
        List<String> av = a == null ? List.of() : a.stream().map(ResourceRecord::getValue).sorted().toList();
        List<String> bv = b == null ? List.of() : b.stream().map(ResourceRecord::getValue).sorted().toList();
        return av.equals(bv);
    }
}

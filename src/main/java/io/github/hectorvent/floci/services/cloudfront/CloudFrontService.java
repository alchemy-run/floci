package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontOriginAccessIdentity;
import io.github.hectorvent.floci.services.cloudfront.model.ContinuousDeploymentPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionProfile;
import io.github.hectorvent.floci.services.cloudfront.model.Invalidation;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.KeyValueStore;
import io.github.hectorvent.floci.services.cloudfront.model.MonitoringSubscription;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.cloudfront.model.RealtimeLogConfig;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import io.github.hectorvent.floci.services.cloudfront.model.VpcOrigin;
import io.github.hectorvent.floci.services.cloudfront.edge.CloudFrontEdgePorts;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@ApplicationScoped
public class CloudFrontService {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, Distribution> distStore;
    private final StorageBackend<String, List<Invalidation>> invalidationStore;
    private final StorageBackend<String, CachePolicy> cachePolicyStore;
    private final StorageBackend<String, OriginRequestPolicy> orpStore;
    private final StorageBackend<String, ResponseHeadersPolicy> rhpStore;
    private final StorageBackend<String, OriginAccessControl> oacStore;
    private final StorageBackend<String, CloudFrontOriginAccessIdentity> oaiStore;
    private final StorageBackend<String, CloudFrontFunction> functionStore;
    private final StorageBackend<String, Map<String, String>> tagStore;
    private final StorageBackend<String, ContinuousDeploymentPolicy> cdpStore;
    private final StorageBackend<String, PublicKey> publicKeyStore;
    private final StorageBackend<String, KeyGroup> keyGroupStore;
    private final StorageBackend<String, RealtimeLogConfig> realtimeLogConfigStore;
    private final StorageBackend<String, StreamingDistribution> streamingDistStore;
    private final StorageBackend<String, FieldLevelEncryptionConfig> fleConfigStore;
    private final StorageBackend<String, FieldLevelEncryptionProfile> fleProfileStore;
    private final StorageBackend<String, MonitoringSubscription> monitoringStore;
    private final StorageBackend<String, VpcOrigin> vpcOriginStore;
    private final StorageBackend<String, KeyValueStore> kvsStore;
    /** KVS data-plane keys, keyed by store id → (key → value). */
    private final StorageBackend<String, Map<String, String>> kvsKeysStore;
    private final String accountId;
    private final String domainSuffix;
    private final ElbV2Service elbV2;
    /** Null in unit tests: a distribution without a local port is still Host-addressable. */
    private final CloudFrontEdgePorts edgePorts;

    public CloudFrontService(StorageFactory factory, EmulatorConfig config) {
        this(factory, config, null, null);
    }

    @Inject
    public CloudFrontService(StorageFactory factory, EmulatorConfig config, ElbV2Service elbV2,
                             CloudFrontEdgePorts edgePorts) {
        this.distStore = factory.create("cloudfront", "cloudfront-distributions.json",
                new TypeReference<Map<String, Distribution>>() {});
        this.invalidationStore = factory.create("cloudfront", "cloudfront-invalidations.json",
                new TypeReference<Map<String, List<Invalidation>>>() {});
        this.cachePolicyStore = factory.create("cloudfront", "cloudfront-cache-policies.json",
                new TypeReference<Map<String, CachePolicy>>() {});
        this.orpStore = factory.create("cloudfront", "cloudfront-origin-request-policies.json",
                new TypeReference<Map<String, OriginRequestPolicy>>() {});
        this.rhpStore = factory.create("cloudfront", "cloudfront-response-headers-policies.json",
                new TypeReference<Map<String, ResponseHeadersPolicy>>() {});
        this.oacStore = factory.create("cloudfront", "cloudfront-oac.json",
                new TypeReference<Map<String, OriginAccessControl>>() {});
        this.oaiStore = factory.create("cloudfront", "cloudfront-oai.json",
                new TypeReference<Map<String, CloudFrontOriginAccessIdentity>>() {});
        this.functionStore = factory.create("cloudfront", "cloudfront-functions.json",
                new TypeReference<Map<String, CloudFrontFunction>>() {});
        this.tagStore = factory.create("cloudfront", "cloudfront-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.cdpStore = factory.create("cloudfront", "cloudfront-continuous-deployment-policies.json",
                new TypeReference<Map<String, ContinuousDeploymentPolicy>>() {});
        this.publicKeyStore = factory.create("cloudfront", "cloudfront-public-keys.json",
                new TypeReference<Map<String, PublicKey>>() {});
        this.keyGroupStore = factory.create("cloudfront", "cloudfront-key-groups.json",
                new TypeReference<Map<String, KeyGroup>>() {});
        this.realtimeLogConfigStore = factory.create("cloudfront", "cloudfront-realtime-log-configs.json",
                new TypeReference<Map<String, RealtimeLogConfig>>() {});
        this.streamingDistStore = factory.create("cloudfront", "cloudfront-streaming-distributions.json",
                new TypeReference<Map<String, StreamingDistribution>>() {});
        this.fleConfigStore = factory.create("cloudfront", "cloudfront-fle-configs.json",
                new TypeReference<Map<String, FieldLevelEncryptionConfig>>() {});
        this.fleProfileStore = factory.create("cloudfront", "cloudfront-fle-profiles.json",
                new TypeReference<Map<String, FieldLevelEncryptionProfile>>() {});
        this.monitoringStore = factory.create("cloudfront", "cloudfront-monitoring-subscriptions.json",
                new TypeReference<Map<String, MonitoringSubscription>>() {});
        this.vpcOriginStore = factory.create("cloudfront", "cloudfront-vpc-origins.json",
                new TypeReference<Map<String, VpcOrigin>>() {});
        this.kvsStore = factory.create("cloudfront", "cloudfront-key-value-stores.json",
                new TypeReference<Map<String, KeyValueStore>>() {});
        this.kvsKeysStore = factory.create("cloudfront", "cloudfront-kvs-keys.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.accountId = config.defaultAccountId();
        this.domainSuffix = config.services().cloudfront().domainSuffix();
        this.elbV2 = elbV2;
        this.edgePorts = edgePorts;
    }

    // ── Distributions ─────────────────────────────────────────────────────────

    public synchronized Distribution createDistribution(Distribution dist, Map<String, String> tags) {
        String id = generateDistributionId();
        dist.setId(id);
        dist.setArn(AwsArnUtils.Arn.of("cloudfront", "", accountId, "distribution/" + id).toString());
        dist.setDomainName(id + "." + domainSuffix);
        dist.setStatus("Deployed");
        dist.setLastModifiedTime(Instant.now());
        dist.setEtag(UUID.randomUUID().toString());
        if (tags != null && !tags.isEmpty()) {
            dist.setTags(tags);
            tagStore.put("distribution/" + id, tags);
        }
        distStore.put(id, dist);
        bindEdgePort(id);
        return dist;
    }

    public Distribution getDistribution(String id) {
        return distStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchDistribution", "The specified distribution does not exist.", 404));
    }

    public synchronized Distribution updateDistribution(String id, String ifMatch, Distribution updated) {
        Distribution existing = getDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setArn(existing.getArn());
        updated.setDomainName(existing.getDomainName());
        updated.setStatus("Deployed");
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        updated.setTags(existing.getTags());
        distStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteDistribution(String id, String ifMatch) {
        Distribution existing = getDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        if (existing.getConfig() != null && existing.getConfig().isEnabled()) {
            throw new AwsException("DistributionNotDisabled",
                    "The distribution you are trying to delete has not been disabled.", 409);
        }
        distStore.delete(id);
        invalidationStore.delete(id);
        tagStore.delete("distribution/" + id);
        if (edgePorts != null) {
            edgePorts.release(id);
        }
    }

    /**
     * Serve this distribution's emulated edge on its own local port, so it is
     * reachable at {@code http://localhost:<port>} without a hosts entry. Never
     * fails the control-plane call — {@link CloudFrontEdgePorts#bind} reports and
     * swallows the failure, leaving the distribution Host-addressable.
     */
    public Integer bindEdgePort(String id) {
        return edgePorts == null ? null : edgePorts.bind(id);
    }

    /** The local URL this distribution's edge is served on, or {@code null}. */
    public String edgeUrl(String id) {
        return edgePorts == null ? null : edgePorts.urlOf(id);
    }

    /**
     * Find the distribution serving {@code host} — its CloudFront domain name or
     * one of its alternate domain names. Returns {@code null} when no
     * distribution claims the host, so the emulated edge can leave the request
     * alone. Hostnames are case-insensitive.
     */
    public Distribution findDistributionByHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        for (Distribution dist : distStore.scan(k -> true)) {
            if (host.equalsIgnoreCase(dist.getDomainName())) {
                return dist;
            }
            var config = dist.getConfig();
            if (config == null || config.getAliases() == null) {
                continue;
            }
            for (String alias : config.getAliases()) {
                if (host.equalsIgnoreCase(alias)) {
                    return dist;
                }
            }
        }
        return null;
    }

    public List<Distribution> listDistributions(String marker, int maxItems) {
        List<Distribution> all = new ArrayList<>(distStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
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

    public synchronized void associateAlias(String targetDistributionId, String alias) {
        Distribution dist = getDistribution(targetDistributionId);
        List<String> aliases = dist.getConfig() != null ? dist.getConfig().getAliases() : null;
        if (aliases == null) {
            aliases = new ArrayList<>();
        } else {
            aliases = new ArrayList<>(aliases);
        }
        if (!aliases.contains(alias)) {
            aliases.add(alias);
        }
        if (dist.getConfig() != null) {
            dist.getConfig().setAliases(aliases);
        }
        dist.setEtag(UUID.randomUUID().toString());
        distStore.put(targetDistributionId, dist);
    }

    // ── Invalidations ─────────────────────────────────────────────────────────

    public synchronized Invalidation createInvalidation(String distributionId, Invalidation inv) {
        getDistribution(distributionId);
        inv.setId(generateInvalidationId());
        inv.setStatus("Completed");
        inv.setCreateTime(Instant.now());
        List<Invalidation> list = new ArrayList<>(
                invalidationStore.get(distributionId).orElse(new ArrayList<>()));
        list.add(inv);
        invalidationStore.put(distributionId, list);
        return inv;
    }

    public Invalidation getInvalidation(String distributionId, String invId) {
        getDistribution(distributionId);
        List<Invalidation> list = invalidationStore.get(distributionId).orElse(List.of());
        return list.stream()
                .filter(i -> i.getId().equals(invId))
                .findFirst()
                .orElseThrow(() -> new AwsException("NoSuchInvalidation",
                        "The specified invalidation does not exist.", 404));
    }

    public List<Invalidation> listInvalidations(String distributionId, String marker, int maxItems) {
        getDistribution(distributionId);
        List<Invalidation> all = new ArrayList<>(
                invalidationStore.get(distributionId).orElse(List.of()));
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

    // ── Cache Policies ────────────────────────────────────────────────────────

    public synchronized CachePolicy createCachePolicy(CachePolicy policy) {
        policy.setId(UUID.randomUUID().toString());
        policy.setEtag(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        cachePolicyStore.put(policy.getId(), policy);
        return policy;
    }

    public CachePolicy getCachePolicy(String id) {
        return cachePolicyStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchCachePolicy", "The specified cache policy does not exist.", 404));
    }

    public synchronized CachePolicy updateCachePolicy(String id, String ifMatch, CachePolicy updated) {
        CachePolicy existing = getCachePolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        cachePolicyStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteCachePolicy(String id, String ifMatch) {
        CachePolicy existing = getCachePolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        cachePolicyStore.delete(id);
    }

    public List<CachePolicy> listCachePolicies(String marker, int maxItems) {
        List<CachePolicy> all = new ArrayList<>(cachePolicyStore.scan(k -> true));
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
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

    // ── Origin Request Policies ───────────────────────────────────────────────

    public synchronized OriginRequestPolicy createOriginRequestPolicy(OriginRequestPolicy policy) {
        policy.setId(UUID.randomUUID().toString());
        policy.setEtag(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        orpStore.put(policy.getId(), policy);
        return policy;
    }

    public OriginRequestPolicy getOriginRequestPolicy(String id) {
        return orpStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchOriginRequestPolicy",
                        "The specified origin request policy does not exist.", 404));
    }

    public synchronized OriginRequestPolicy updateOriginRequestPolicy(String id, String ifMatch,
                                                                       OriginRequestPolicy updated) {
        OriginRequestPolicy existing = getOriginRequestPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        orpStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteOriginRequestPolicy(String id, String ifMatch) {
        OriginRequestPolicy existing = getOriginRequestPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        orpStore.delete(id);
    }

    public List<OriginRequestPolicy> listOriginRequestPolicies(String marker, int maxItems) {
        List<OriginRequestPolicy> all = new ArrayList<>(orpStore.scan(k -> true));
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
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

    // ── Response Headers Policies ─────────────────────────────────────────────

    public synchronized ResponseHeadersPolicy createResponseHeadersPolicy(ResponseHeadersPolicy policy) {
        policy.setId(UUID.randomUUID().toString());
        policy.setEtag(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        rhpStore.put(policy.getId(), policy);
        return policy;
    }

    public ResponseHeadersPolicy getResponseHeadersPolicy(String id) {
        return rhpStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchResponseHeadersPolicy",
                        "The specified response headers policy does not exist.", 404));
    }

    public synchronized ResponseHeadersPolicy updateResponseHeadersPolicy(String id, String ifMatch,
                                                                           ResponseHeadersPolicy updated) {
        ResponseHeadersPolicy existing = getResponseHeadersPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        rhpStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteResponseHeadersPolicy(String id, String ifMatch) {
        ResponseHeadersPolicy existing = getResponseHeadersPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        rhpStore.delete(id);
    }

    public List<ResponseHeadersPolicy> listResponseHeadersPolicies(String marker, int maxItems) {
        List<ResponseHeadersPolicy> all = new ArrayList<>(rhpStore.scan(k -> true));
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
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

    // ── Origin Access Control ─────────────────────────────────────────────────

    public synchronized OriginAccessControl createOriginAccessControl(OriginAccessControl oac) {
        oac.setId(UUID.randomUUID().toString());
        oac.setEtag(UUID.randomUUID().toString());
        oac.setLastModifiedTime(Instant.now());
        oacStore.put(oac.getId(), oac);
        return oac;
    }

    public OriginAccessControl getOriginAccessControl(String id) {
        return oacStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchOriginAccessControl",
                        "The specified origin access control does not exist.", 404));
    }

    public synchronized OriginAccessControl updateOriginAccessControl(String id, String ifMatch,
                                                                       OriginAccessControl updated) {
        OriginAccessControl existing = getOriginAccessControl(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        oacStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteOriginAccessControl(String id, String ifMatch) {
        OriginAccessControl existing = getOriginAccessControl(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        oacStore.delete(id);
    }

    public List<OriginAccessControl> listOriginAccessControls(String marker, int maxItems) {
        List<OriginAccessControl> all = new ArrayList<>(oacStore.scan(k -> true));
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
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

    // ── Origin Access Identity (OAI) ──────────────────────────────────────────

    public synchronized CloudFrontOriginAccessIdentity createCloudFrontOriginAccessIdentity(
            CloudFrontOriginAccessIdentity oai) {
        for (CloudFrontOriginAccessIdentity existing : oaiStore.scan(k -> true)) {
            if (oai.getCallerReference() != null
                    && oai.getCallerReference().equals(existing.getCallerReference())) {
                throw new AwsException("CloudFrontOriginAccessIdentityAlreadyExists",
                        "An origin access identity with the caller reference already exists.", 409);
            }
        }
        oai.setId(generateDistributionId());
        oai.setS3CanonicalUserId(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        oai.setEtag(UUID.randomUUID().toString());
        oaiStore.put(oai.getId(), oai);
        return oai;
    }

    public CloudFrontOriginAccessIdentity getCloudFrontOriginAccessIdentity(String id) {
        return oaiStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchCloudFrontOriginAccessIdentity",
                        "The specified origin access identity does not exist.", 404));
    }

    public synchronized CloudFrontOriginAccessIdentity updateCloudFrontOriginAccessIdentity(
            String id, String ifMatch, CloudFrontOriginAccessIdentity updated) {
        CloudFrontOriginAccessIdentity existing = getCloudFrontOriginAccessIdentity(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setS3CanonicalUserId(existing.getS3CanonicalUserId());
        updated.setEtag(UUID.randomUUID().toString());
        oaiStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteCloudFrontOriginAccessIdentity(String id, String ifMatch) {
        CloudFrontOriginAccessIdentity existing = getCloudFrontOriginAccessIdentity(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        oaiStore.delete(id);
    }

    public List<CloudFrontOriginAccessIdentity> listCloudFrontOriginAccessIdentities(
            String marker, int maxItems) {
        List<CloudFrontOriginAccessIdentity> all = new ArrayList<>(oaiStore.scan(k -> true));
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

    // ── CloudFront Functions ──────────────────────────────────────────────────

    private static final String FUNCTION_STAGE_DEVELOPMENT = "DEVELOPMENT";
    private static final String FUNCTION_STAGE_LIVE = "LIVE";

    private static String functionKey(String name, String stage) {
        return name + ":" + stage;
    }

    public synchronized CloudFrontFunction createFunction(CloudFrontFunction fn) {
        if (fn.getName() != null && findFunction(fn.getName(), null).isPresent()) {
            throw new AwsException("FunctionAlreadyExists",
                    "A function with the specified name already exists.", 409);
        }
        fn.setStage(FUNCTION_STAGE_DEVELOPMENT);
        fn.setStatus("UNPUBLISHED");
        fn.setEtag(UUID.randomUUID().toString());
        fn.setCreatedTime(Instant.now());
        fn.setLastModifiedTime(Instant.now());
        functionStore.put(functionKey(fn.getName(), FUNCTION_STAGE_DEVELOPMENT), fn);
        return fn;
    }

    public CloudFrontFunction describeFunction(String name, String stage) {
        return findFunction(name, stage).orElseThrow(() ->
                new AwsException("NoSuchFunctionExists",
                        "The specified function does not exist.", 404));
    }

    private Optional<CloudFrontFunction> findFunction(String name, String stage) {
        if (stage != null && !stage.isEmpty()) {
            Optional<CloudFrontFunction> staged = functionStore.get(functionKey(name, stage));
            if (staged.isPresent()) {
                return staged;
            }
            return functionStore.get(name).filter(fn -> stage.equals(fn.getStage()));
        }
        Optional<CloudFrontFunction> live = functionStore.get(functionKey(name, FUNCTION_STAGE_LIVE));
        if (live.isPresent()) {
            return live;
        }
        Optional<CloudFrontFunction> development = functionStore.get(functionKey(name, FUNCTION_STAGE_DEVELOPMENT));
        if (development.isPresent()) {
            return development;
        }
        return functionStore.get(name);
    }

    public synchronized CloudFrontFunction updateFunction(String name, String ifMatch,
                                                          CloudFrontFunction updated) {
        CloudFrontFunction existing = describeFunction(name, FUNCTION_STAGE_DEVELOPMENT);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setName(name);
        updated.setStage(FUNCTION_STAGE_DEVELOPMENT);
        updated.setStatus(existing.getStatus());
        updated.setEtag(UUID.randomUUID().toString());
        updated.setCreatedTime(existing.getCreatedTime());
        updated.setLastModifiedTime(Instant.now());
        if (updated.getKeyValueStoreArns() == null || updated.getKeyValueStoreArns().isEmpty()) {
            updated.setKeyValueStoreArns(existing.getKeyValueStoreArns());
        }
        functionStore.put(functionKey(name, FUNCTION_STAGE_DEVELOPMENT), updated);
        functionStore.delete(name);
        return updated;
    }

    public synchronized CloudFrontFunction publishFunction(String name, String ifMatch) {
        CloudFrontFunction development = describeFunction(name, FUNCTION_STAGE_DEVELOPMENT);
        if (!development.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        CloudFrontFunction live = development.copy();
        live.setStage(FUNCTION_STAGE_LIVE);
        live.setStatus("DEPLOYED");
        live.setEtag(UUID.randomUUID().toString());
        live.setLastModifiedTime(Instant.now());
        functionStore.put(functionKey(name, FUNCTION_STAGE_LIVE), live);
        return live;
    }

    public synchronized void deleteFunction(String name, String ifMatch) {
        Optional<CloudFrontFunction> development = findFunction(name, FUNCTION_STAGE_DEVELOPMENT);
        Optional<CloudFrontFunction> live = findFunction(name, FUNCTION_STAGE_LIVE);
        Optional<CloudFrontFunction> legacy = functionStore.get(name);
        if (development.isEmpty() && live.isEmpty() && legacy.isEmpty()) {
            throw new AwsException("NoSuchFunctionExists",
                    "The specified function does not exist.", 404);
        }
        boolean match = development.map(CloudFrontFunction::getEtag).filter(ifMatch::equals).isPresent()
                || live.map(CloudFrontFunction::getEtag).filter(ifMatch::equals).isPresent()
                || legacy.map(CloudFrontFunction::getEtag).filter(ifMatch::equals).isPresent();
        if (!match) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        functionStore.delete(functionKey(name, FUNCTION_STAGE_DEVELOPMENT));
        functionStore.delete(functionKey(name, FUNCTION_STAGE_LIVE));
        functionStore.delete(name);
    }

    public List<CloudFrontFunction> listFunctions(String stage) {
        List<CloudFrontFunction> all = new ArrayList<>(functionStore.scan(k -> true));
        if (stage != null && !stage.isEmpty()) {
            all = all.stream().filter(f -> stage.equals(f.getStage())).toList();
            all = new ArrayList<>(all);
        }
        all.sort((a, b) -> {
            int names = a.getName().compareTo(b.getName());
            if (names != 0) {
                return names;
            }
            String left = a.getStage() != null ? a.getStage() : "";
            String right = b.getStage() != null ? b.getStage() : "";
            return left.compareTo(right);
        });
        return all;
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTagsForResource(String arn) {
        return tagStore.get(arn).orElse(new LinkedHashMap<>());
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        Map<String, String> existing = new LinkedHashMap<>(tagStore.get(arn).orElse(new LinkedHashMap<>()));
        existing.putAll(tags);
        tagStore.put(arn, existing);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        Map<String, String> existing = new LinkedHashMap<>(tagStore.get(arn).orElse(new LinkedHashMap<>()));
        tagKeys.forEach(existing::remove);
        tagStore.put(arn, existing);
    }

    // ── Continuous Deployment Policies ───────────────────────────────────────

    public synchronized ContinuousDeploymentPolicy createContinuousDeploymentPolicy(
            ContinuousDeploymentPolicy policy) {
        policy.setId(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        policy.setEtag(UUID.randomUUID().toString());
        cdpStore.put(policy.getId(), policy);
        return policy;
    }

    public ContinuousDeploymentPolicy getContinuousDeploymentPolicy(String id) {
        return cdpStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchContinuousDeploymentPolicy",
                        "The specified continuous deployment policy does not exist.", 404));
    }

    public synchronized ContinuousDeploymentPolicy updateContinuousDeploymentPolicy(
            String id, String ifMatch, ContinuousDeploymentPolicy updated) {
        ContinuousDeploymentPolicy existing = getContinuousDeploymentPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        cdpStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteContinuousDeploymentPolicy(String id, String ifMatch) {
        ContinuousDeploymentPolicy existing = getContinuousDeploymentPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        cdpStore.delete(id);
    }

    public List<ContinuousDeploymentPolicy> listContinuousDeploymentPolicies(String marker, int maxItems) {
        List<ContinuousDeploymentPolicy> all = new ArrayList<>(cdpStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, ContinuousDeploymentPolicy::getId);
    }

    // ── CopyDistribution ──────────────────────────────────────────────────────

    public synchronized Distribution copyDistribution(String primaryDistributionId, String callerReference,
                                                       Map<String, String> tags) {
        Distribution primary = getDistribution(primaryDistributionId);
        Distribution copy = new Distribution();
        copy.setConfig(primary.getConfig());
        if (copy.getConfig() != null) {
            copy.getConfig().setCallerReference(callerReference);
            copy.getConfig().setStaging(true);
        }
        return createDistribution(copy, tags);
    }

    // ── Public Keys ───────────────────────────────────────────────────────────

    public synchronized PublicKey createPublicKey(PublicKey key) {
        key.setId(UUID.randomUUID().toString());
        key.setCreatedTime(Instant.now());
        key.setEtag(UUID.randomUUID().toString());
        publicKeyStore.put(key.getId(), key);
        return key;
    }

    public PublicKey getPublicKey(String id) {
        return publicKeyStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchPublicKey", "The specified public key does not exist.", 404));
    }

    public synchronized PublicKey updatePublicKey(String id, String ifMatch, PublicKey updated) {
        PublicKey existing = getPublicKey(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setCreatedTime(existing.getCreatedTime());
        updated.setEtag(UUID.randomUUID().toString());
        publicKeyStore.put(id, updated);
        return updated;
    }

    public synchronized void deletePublicKey(String id, String ifMatch) {
        PublicKey existing = getPublicKey(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        publicKeyStore.delete(id);
    }

    public List<PublicKey> listPublicKeys(String marker, int maxItems) {
        List<PublicKey> all = new ArrayList<>(publicKeyStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, PublicKey::getId);
    }

    // ── Key Groups ────────────────────────────────────────────────────────────

    public synchronized KeyGroup createKeyGroup(KeyGroup group) {
        group.setId(UUID.randomUUID().toString());
        group.setLastModifiedTime(Instant.now());
        group.setEtag(UUID.randomUUID().toString());
        keyGroupStore.put(group.getId(), group);
        return group;
    }

    public KeyGroup getKeyGroup(String id) {
        return keyGroupStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchResource", "The specified key group does not exist.", 404));
    }

    public synchronized KeyGroup updateKeyGroup(String id, String ifMatch, KeyGroup updated) {
        KeyGroup existing = getKeyGroup(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        keyGroupStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteKeyGroup(String id, String ifMatch) {
        KeyGroup existing = getKeyGroup(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        keyGroupStore.delete(id);
    }

    public List<KeyGroup> listKeyGroups(String marker, int maxItems) {
        List<KeyGroup> all = new ArrayList<>(keyGroupStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, KeyGroup::getId);
    }

    // ── Realtime Log Configs ──────────────────────────────────────────────────

    public synchronized RealtimeLogConfig createRealtimeLogConfig(RealtimeLogConfig cfg) {
        String arn = AwsArnUtils.Arn.of("cloudfront", "", accountId, "realtime-log-config/" + cfg.getName()).toString();
        cfg.setArn(arn);
        realtimeLogConfigStore.put(cfg.getName(), cfg);
        return cfg;
    }

    public RealtimeLogConfig getRealtimeLogConfig(String nameOrArn) {
        if (nameOrArn != null && nameOrArn.startsWith("arn:")) {
            String name = nameOrArn.substring(nameOrArn.lastIndexOf('/') + 1);
            return realtimeLogConfigStore.get(name).orElseThrow(() ->
                    new AwsException("NoSuchRealtimeLogConfig",
                            "The specified realtime log configuration does not exist.", 404));
        }
        return realtimeLogConfigStore.get(nameOrArn).orElseThrow(() ->
                new AwsException("NoSuchRealtimeLogConfig",
                        "The specified realtime log configuration does not exist.", 404));
    }

    public synchronized RealtimeLogConfig updateRealtimeLogConfig(RealtimeLogConfig updated) {
        getRealtimeLogConfig(updated.getName());
        String arn = AwsArnUtils.Arn.of("cloudfront", "", accountId, "realtime-log-config/" + updated.getName()).toString();
        updated.setArn(arn);
        realtimeLogConfigStore.put(updated.getName(), updated);
        return updated;
    }

    public synchronized void deleteRealtimeLogConfig(String nameOrArn) {
        RealtimeLogConfig existing = getRealtimeLogConfig(nameOrArn);
        String name = existing.getName();
        realtimeLogConfigStore.delete(name);
    }

    public List<RealtimeLogConfig> listRealtimeLogConfigs(String marker, int maxItems) {
        List<RealtimeLogConfig> all = new ArrayList<>(realtimeLogConfigStore.scan(k -> true));
        all.sort((a, b) -> a.getName().compareTo(b.getName()));
        return paginate(all, marker, maxItems, RealtimeLogConfig::getName);
    }

    // ── Streaming Distributions ───────────────────────────────────────────────

    public synchronized StreamingDistribution createStreamingDistribution(StreamingDistribution sd) {
        String id = generateDistributionId();
        sd.setId(id);
        sd.setArn(AwsArnUtils.Arn.of("cloudfront", "", accountId, "streaming-distribution/" + id).toString());
        sd.setDomainName(id + "." + domainSuffix);
        sd.setStatus("Deployed");
        sd.setLastModifiedTime(Instant.now());
        sd.setEtag(UUID.randomUUID().toString());
        streamingDistStore.put(id, sd);
        return sd;
    }

    public StreamingDistribution getStreamingDistribution(String id) {
        return streamingDistStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchStreamingDistribution",
                        "The specified streaming distribution does not exist.", 404));
    }

    public synchronized StreamingDistribution updateStreamingDistribution(String id, String ifMatch,
                                                                           StreamingDistribution updated) {
        StreamingDistribution existing = getStreamingDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setArn(existing.getArn());
        updated.setDomainName(existing.getDomainName());
        updated.setStatus("Deployed");
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        streamingDistStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteStreamingDistribution(String id, String ifMatch) {
        StreamingDistribution existing = getStreamingDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        if (existing.isEnabled()) {
            throw new AwsException("StreamingDistributionNotDisabled",
                    "The streaming distribution you are trying to delete has not been disabled.", 409);
        }
        streamingDistStore.delete(id);
    }

    // ── Field-Level Encryption Configs ────────────────────────────────────────

    public synchronized FieldLevelEncryptionConfig createFieldLevelEncryptionConfig(
            FieldLevelEncryptionConfig cfg) {
        cfg.setId(UUID.randomUUID().toString());
        cfg.setLastModifiedTime(Instant.now());
        cfg.setEtag(UUID.randomUUID().toString());
        fleConfigStore.put(cfg.getId(), cfg);
        return cfg;
    }

    public FieldLevelEncryptionConfig getFieldLevelEncryptionConfig(String id) {
        return fleConfigStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchFieldLevelEncryptionConfig",
                        "The specified field-level encryption configuration does not exist.", 404));
    }

    public synchronized FieldLevelEncryptionConfig updateFieldLevelEncryptionConfig(
            String id, String ifMatch, FieldLevelEncryptionConfig updated) {
        FieldLevelEncryptionConfig existing = getFieldLevelEncryptionConfig(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        fleConfigStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteFieldLevelEncryptionConfig(String id, String ifMatch) {
        FieldLevelEncryptionConfig existing = getFieldLevelEncryptionConfig(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        fleConfigStore.delete(id);
    }

    public List<FieldLevelEncryptionConfig> listFieldLevelEncryptionConfigs(String marker, int maxItems) {
        List<FieldLevelEncryptionConfig> all = new ArrayList<>(fleConfigStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, FieldLevelEncryptionConfig::getId);
    }

    // ── Field-Level Encryption Profiles ──────────────────────────────────────

    public synchronized FieldLevelEncryptionProfile createFieldLevelEncryptionProfile(
            FieldLevelEncryptionProfile profile) {
        profile.setId(UUID.randomUUID().toString());
        profile.setLastModifiedTime(Instant.now());
        profile.setEtag(UUID.randomUUID().toString());
        fleProfileStore.put(profile.getId(), profile);
        return profile;
    }

    public FieldLevelEncryptionProfile getFieldLevelEncryptionProfile(String id) {
        return fleProfileStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchFieldLevelEncryptionProfile",
                        "The specified field-level encryption profile does not exist.", 404));
    }

    public synchronized FieldLevelEncryptionProfile updateFieldLevelEncryptionProfile(
            String id, String ifMatch, FieldLevelEncryptionProfile updated) {
        FieldLevelEncryptionProfile existing = getFieldLevelEncryptionProfile(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        fleProfileStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteFieldLevelEncryptionProfile(String id, String ifMatch) {
        FieldLevelEncryptionProfile existing = getFieldLevelEncryptionProfile(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        fleProfileStore.delete(id);
    }

    public List<FieldLevelEncryptionProfile> listFieldLevelEncryptionProfiles(String marker, int maxItems) {
        List<FieldLevelEncryptionProfile> all = new ArrayList<>(fleProfileStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, FieldLevelEncryptionProfile::getId);
    }

    // ── Monitoring Subscriptions ──────────────────────────────────────────────

    public synchronized MonitoringSubscription createMonitoringSubscription(
            String distributionId, MonitoringSubscription subscription) {
        getDistribution(distributionId);
        subscription.setDistributionId(distributionId);
        monitoringStore.put(distributionId, subscription);
        return subscription;
    }

    public MonitoringSubscription getMonitoringSubscription(String distributionId) {
        return monitoringStore.get(distributionId).orElseThrow(() ->
                new AwsException("NoSuchMonitoringSubscription",
                        "A monitoring subscription does not exist for the specified distribution.", 404));
    }

    public synchronized void deleteMonitoringSubscription(String distributionId) {
        getMonitoringSubscription(distributionId);
        monitoringStore.delete(distributionId);
    }

    // ── VPC Origins ───────────────────────────────────────────────────────────

    public synchronized VpcOrigin createVpcOrigin(VpcOrigin origin, Map<String, String> tags) {
        validateVpcOriginArn(origin.getOriginEndpointArn());
        for (VpcOrigin existing : vpcOriginStore.scan(k -> true)) {
            if (origin.getName() != null && origin.getName().equals(existing.getName())) {
                throw new AwsException("EntityAlreadyExists",
                        "A VPC origin with the specified name already exists.", 409);
            }
        }
        Instant now = Instant.now();
        String id = generateVpcOriginId();
        origin.setId(id);
        origin.setAccountId(accountId);
        origin.setArn(AwsArnUtils.Arn.of("cloudfront", "", accountId, "vpcorigin/" + id).toString());
        origin.setStatus("Deployed");
        origin.setCreatedTime(now);
        origin.setLastModifiedTime(now);
        origin.setEtag(UUID.randomUUID().toString());
        if (tags != null && !tags.isEmpty()) {
            tagStore.put(origin.getArn(), tags);
        }
        vpcOriginStore.put(id, origin);
        return origin;
    }

    public VpcOrigin getVpcOrigin(String id) {
        return vpcOriginStore.get(id).orElseThrow(() ->
                new AwsException("EntityNotFound", "The specified VPC origin does not exist.", 404));
    }

    public synchronized VpcOrigin updateVpcOrigin(String id, String ifMatch, VpcOrigin updated) {
        VpcOrigin existing = getVpcOrigin(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        validateVpcOriginArn(updated.getOriginEndpointArn());
        updated.setId(id);
        updated.setArn(existing.getArn());
        updated.setAccountId(existing.getAccountId());
        updated.setCreatedTime(existing.getCreatedTime());
        updated.setStatus("Deployed");
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        if (updated.getName() == null || updated.getName().isEmpty()) {
            updated.setName(existing.getName());
        }
        vpcOriginStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteVpcOrigin(String id, String ifMatch) {
        VpcOrigin existing = getVpcOrigin(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        vpcOriginStore.delete(id);
        if (existing.getArn() != null) {
            tagStore.delete(existing.getArn());
        }
    }

    public List<VpcOrigin> listVpcOrigins(String marker, int maxItems) {
        List<VpcOrigin> all = new ArrayList<>(vpcOriginStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, VpcOrigin::getId);
    }

    // ── Key Value Stores ──────────────────────────────────────────────────────

    public synchronized KeyValueStore createKeyValueStore(KeyValueStore store, Map<String, String> tags) {
        if (store.getName() == null || store.getName().isBlank()) {
            throw new AwsException("InvalidArgument", "KeyValueStore Name is required.", 400);
        }
        if (findKeyValueStoreByName(store.getName()) != null) {
            throw new AwsException("EntityAlreadyExists",
                    "A key value store with the specified name already exists.", 409);
        }
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        store.setId(id);
        store.setArn(AwsArnUtils.Arn.of("cloudfront", "", accountId, "key-value-store/" + id).toString());
        store.setStatus("READY");
        store.setCreatedTime(now);
        store.setLastModifiedTime(now);
        store.setEtag(UUID.randomUUID().toString());
        if (tags != null && !tags.isEmpty()) {
            tagStore.put(store.getArn(), tags);
        }
        kvsStore.put(id, store);
        return store;
    }

    public KeyValueStore describeKeyValueStore(String name) {
        KeyValueStore store = findKeyValueStoreByName(name);
        if (store == null) {
            throw new AwsException("EntityNotFound",
                    "The specified key value store does not exist.", 404);
        }
        return store;
    }

    public synchronized KeyValueStore updateKeyValueStore(String name, String ifMatch, String comment) {
        KeyValueStore existing = describeKeyValueStore(name);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        existing.setComment(comment != null ? comment : "");
        existing.setLastModifiedTime(Instant.now());
        existing.setEtag(UUID.randomUUID().toString());
        kvsStore.put(existing.getId(), existing);
        return existing;
    }

    public synchronized void deleteKeyValueStore(String name, String ifMatch) {
        KeyValueStore existing = describeKeyValueStore(name);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        kvsStore.delete(existing.getId());
        kvsKeysStore.delete(existing.getId());
        if (existing.getArn() != null) {
            tagStore.delete(existing.getArn());
        }
    }

    public List<KeyValueStore> listKeyValueStores(String marker, int maxItems, String status) {
        List<KeyValueStore> all = new ArrayList<>(kvsStore.scan(k -> true));
        if (status != null && !status.isEmpty()) {
            all = new ArrayList<>(all.stream().filter(s -> status.equals(s.getStatus())).toList());
        }
        all.sort((a, b) -> a.getName().compareTo(b.getName()));
        return paginate(all, marker, maxItems, KeyValueStore::getName);
    }

    // ── Key Value Store data plane (cloudfront-keyvaluestore) ────────────────

    /**
     * Resolves a store by its data-plane ARN
     * ({@code arn:aws:cloudfront::{account}:key-value-store/{id}}).
     * The data plane reports a missing store as {@code ResourceNotFoundException}
     * (404), not the control plane's {@code EntityNotFound}.
     */
    public KeyValueStore describeKeyValueStoreByArn(String kvsArn) {
        String id = kvsArn;
        int slash = kvsArn.lastIndexOf('/');
        if (slash >= 0) {
            id = kvsArn.substring(slash + 1);
        }
        return kvsStore.get(id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Resource was not found.", 404));
    }

    public Map<String, String> kvsKeys(String kvsArn) {
        KeyValueStore store = describeKeyValueStoreByArn(kvsArn);
        return kvsKeysStore.get(store.getId()).map(HashMap::new).orElseGet(HashMap::new);
    }

    public String kvsGetKey(String kvsArn, String key) {
        String value = kvsKeys(kvsArn).get(key);
        if (value == null) {
            throw new AwsException("ResourceNotFoundException", "Resource was not found.", 404);
        }
        return value;
    }

    public synchronized KeyValueStore kvsPutKey(String kvsArn, String key, String value, String ifMatch) {
        return kvsMutate(kvsArn, ifMatch, keys -> keys.put(key, value));
    }

    public synchronized KeyValueStore kvsDeleteKey(String kvsArn, String key, String ifMatch) {
        return kvsMutate(kvsArn, ifMatch, keys -> {
            if (keys.remove(key) == null) {
                throw new AwsException("ResourceNotFoundException", "Resource was not found.", 404);
            }
        });
    }

    /**
     * Bulk puts + deletes in one atomic step. Deletes of absent keys are
     * tolerated — UpdateKeys is the bulk-convergence operation, so a caller
     * re-driving a partially applied batch must not fail on already-gone keys.
     */
    public synchronized KeyValueStore kvsUpdateKeys(String kvsArn, String ifMatch,
                                                     Map<String, String> puts, List<String> deletes) {
        return kvsMutate(kvsArn, ifMatch, keys -> {
            if (puts != null) {
                keys.putAll(puts);
            }
            if (deletes != null) {
                deletes.forEach(keys::remove);
            }
        });
    }

    public long kvsTotalSizeInBytes(String kvsArn) {
        return kvsKeys(kvsArn).entrySet().stream()
                .mapToLong(e -> utf8Length(e.getKey()) + utf8Length(e.getValue()))
                .sum();
    }

    private KeyValueStore kvsMutate(String kvsArn, String ifMatch,
                                    java.util.function.Consumer<Map<String, String>> mutation) {
        KeyValueStore store = describeKeyValueStoreByArn(kvsArn);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new AwsException("ValidationException", "The If-Match version is required.", 400);
        }
        if (!ifMatch.equals(store.getEtag())) {
            throw new AwsException("ConflictException",
                    "Pre-Condition failed: the provided ETag does not match the current version.", 409);
        }
        Map<String, String> keys = kvsKeysStore.get(store.getId()).map(HashMap::new).orElseGet(HashMap::new);
        mutation.accept(keys);
        kvsKeysStore.put(store.getId(), keys);
        store.setLastModifiedTime(Instant.now());
        store.setEtag(UUID.randomUUID().toString());
        kvsStore.put(store.getId(), store);
        return store;
    }

    private static long utf8Length(String value) {
        return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public String getAccountId() {
        return accountId;
    }

    private static String generateDistributionId() {
        StringBuilder sb = new StringBuilder("E");
        for (int i = 0; i < 13; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static String generateInvalidationId() {
        StringBuilder sb = new StringBuilder("I");
        for (int i = 0; i < 13; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static String generateVpcOriginId() {
        StringBuilder sb = new StringBuilder("vo_");
        for (int i = 0; i < 14; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private KeyValueStore findKeyValueStoreByName(String name) {
        if (name == null) {
            return null;
        }
        for (KeyValueStore store : kvsStore.scan(k -> true)) {
            if (name.equals(store.getName())) {
                return store;
            }
        }
        return null;
    }

    private void validateVpcOriginArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidArgument", "The specified origin ARN is not valid.", 400);
        }
        if (arn.startsWith("arn:aws:elasticloadbalancing:")) {
            String[] parts = arn.split(":", 6);
            if (parts.length < 6 || parts[3].isEmpty() || !parts[5].startsWith("loadbalancer/")) {
                throw new AwsException("InvalidArgument", "The specified origin ARN is not valid.", 400);
            }
            if (elbV2 != null) {
                try {
                    List<LoadBalancer> found = elbV2.describeLoadBalancers(
                            parts[3], List.of(arn), null, null, null);
                    if (found.isEmpty()) {
                        throw new AwsException("InvalidArgument",
                                "The specified origin ARN is not valid.", 400);
                    }
                } catch (AwsException e) {
                    throw new AwsException("InvalidArgument",
                            "The specified origin ARN is not valid.", 400);
                }
            }
            return;
        }
        if (arn.startsWith("arn:aws:ec2:") && arn.contains(":instance/")) {
            return;
        }
        throw new AwsException("InvalidArgument", "The specified origin ARN is not valid.", 400);
    }

    private <T> List<T> paginate(List<T> all, String marker, int maxItems,
                                  Function<T, String> keyFn) {
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (marker.equals(keyFn.apply(all.get(i)))) {
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
}

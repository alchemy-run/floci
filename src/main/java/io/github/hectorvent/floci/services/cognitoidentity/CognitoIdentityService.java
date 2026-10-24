package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import io.github.hectorvent.floci.services.cognitoidentity.model.PrincipalTagAttributeMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Cognito Identity pool management and local federated identities.
 *
 * <p>Pool ids follow the AWS format {@code <region>:<uuid>} and pool ARNs the documented
 * {@code arn:aws:cognito-identity:<region>:<account>:identitypool/<poolId>} form, so a caller
 * that parses either gets the same structure it would from AWS.
 */
@ApplicationScoped
public class CognitoIdentityService {

    private static final Logger LOG = Logger.getLogger(CognitoIdentityService.class);

    private static final Pattern IDENTITY_POOL_NAME = Pattern.compile("[\\w\\s+=,.@-]+");
    private static final Pattern DEVELOPER_PROVIDER_NAME = Pattern.compile("[\\w._-]+");
    private static final Set<String> ROLE_TYPES = Set.of("authenticated", "unauthenticated");
    private static final Set<String> ROLE_MAPPING_TYPES = Set.of("Token", "Rules");
    private static final String ARN_RESOURCE_PREFIX = "identitypool/";
    private static final int MAX_LIST_RESULTS = 60;

    private final StorageBackend<String, IdentityPool> pools;
    private final StorageBackend<String, CognitoFederatedIdentity> identities;
    private final RegionResolver regionResolver;

    @Inject
    public CognitoIdentityService(StorageFactory storageFactory, RegionResolver regionResolver) {
        AccountAwareStorageBackend<IdentityPool> managedPools = storageFactory.create(
                "cognitoidentity", "cognitoidentity-pools.json", new TypeReference<Map<String, IdentityPool>>() {});
        AccountAwareStorageBackend<CognitoFederatedIdentity> managedIdentities = storageFactory.create(
                "cognitoidentity", "cognito-identities.json",
                new TypeReference<Map<String, CognitoFederatedIdentity>>() {});
        this.pools = managedPools;
        this.identities = managedIdentities;
        this.regionResolver = regionResolver;
        CognitoIdentityMigration.migrate(storageFactory, managedPools, managedIdentities);
    }

    public CognitoIdentityService(StorageBackend<String, IdentityPool> pools,
                                 StorageBackend<String, CognitoFederatedIdentity> identities,
                                 RegionResolver regionResolver) {
        this.pools = pools;
        this.identities = identities;
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Identity pools ────────────────────────────

    public IdentityPool createIdentityPool(IdentityPool spec, String region) {
        validatePoolConfiguration(spec);

        String identityPoolId = region + ":" + UUID.randomUUID();
        spec.setIdentityPoolId(identityPoolId);
        spec.setArn(regionResolver.buildArn("cognito-identity", region, ARN_RESOURCE_PREFIX + identityPoolId));
        spec.setAccountId(regionResolver.getAccountId());
        spec.setCreatedAt(Instant.now());
        spec.setIdentityPoolTags(ReservedTags.stripReservedTags(
                spec.getIdentityPoolTags() == null ? Map.of() : spec.getIdentityPoolTags()));

        pools.put(storageKey(region, identityPoolId), spec);
        LOG.infov("Created Cognito identity pool: {0}", identityPoolId);
        return spec;
    }

    public IdentityPool describeIdentityPool(String identityPoolId, String region) {
        requireIdentityPoolId(identityPoolId);
        return pools.get(storageKey(region, identityPoolId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "IdentityPool '" + identityPoolId + "' not found.", 404));
    }

    /**
     * {@code UpdateIdentityPool} takes the whole {@code IdentityPool} shape and replaces the
     * stored configuration with it: omitted configuration members reset to their defaults.
     * Tags survive when omitted, while an explicit tag map replaces them. Roles, role mappings
     * and principal-tag maps are set by their own operations and survive the replace.
     */
    public IdentityPool updateIdentityPool(IdentityPool spec, String region) {
        IdentityPool existing = describeIdentityPool(spec.getIdentityPoolId(), region);
        validatePoolConfiguration(spec);

        spec.setArn(existing.getArn());
        spec.setAccountId(existing.getAccountId());
        spec.setCreatedAt(existing.getCreatedAt());
        spec.setRoles(existing.getRoles());
        spec.setRoleMappings(existing.getRoleMappings());
        spec.setPrincipalTagAttributeMaps(existing.getPrincipalTagAttributeMaps());
        spec.setIdentityPoolTags(spec.getIdentityPoolTags() == null
                ? new LinkedHashMap<>(existing.getIdentityPoolTags())
                : ReservedTags.stripReservedTags(spec.getIdentityPoolTags()));

        pools.put(storageKey(region, spec.getIdentityPoolId()), spec);
        LOG.infov("Updated Cognito identity pool: {0}", spec.getIdentityPoolId());
        return spec;
    }

    public void deleteIdentityPool(String identityPoolId, String region) {
        describeIdentityPool(identityPoolId, region);
        identities.scan(id -> true).stream()
                .filter(identity -> identityPoolId.equals(identity.getIdentityPoolId()))
                .forEach(identity -> identities.delete(identity.getIdentityId()));
        pools.delete(storageKey(region, identityPoolId));
        LOG.infov("Deleted Cognito identity pool: {0}", identityPoolId);
    }

    /**
     * Returns one page of pools ordered by pool id. The pagination token is the index of the
     * first pool of the next page, which keeps the token stable for the common case of a pool
     * set that only grows.
     */
    public Page listIdentityPools(Integer maxResults, String nextToken, String region) {
        int limit = maxResults == null ? MAX_LIST_RESULTS : maxResults;
        if (limit < 1 || limit > MAX_LIST_RESULTS) {
            throw new AwsException("InvalidParameterException",
                    "MaxResults must be between 1 and " + MAX_LIST_RESULTS + ".", 400);
        }
        int offset = parseToken(nextToken);

        String regionPrefix = region + "::";
        List<IdentityPool> ordered = pools.scan(key -> key.startsWith(regionPrefix)).stream()
                .sorted(Comparator.comparing(IdentityPool::getIdentityPoolId))
                .toList();
        if (offset >= ordered.size()) {
            return new Page(List.of(), null);
        }
        int end = Math.min(offset + limit, ordered.size());
        return new Page(ordered.subList(offset, end), end < ordered.size() ? String.valueOf(end) : null);
    }

    public List<IdentityPool> listIdentityPools() {
        return pools.scan(key -> true).stream()
                .sorted(Comparator.comparing(IdentityPool::getIdentityPoolId))
                .toList();
    }

    // ──────────────────────────── Pool roles ────────────────────────────

    public IdentityPool setIdentityPoolRoles(String identityPoolId, Map<String, String> roles,
                                             JsonNode roleMappings, String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        if (roles == null) {
            throw new AwsException("InvalidParameterException", "Roles is required.", 400);
        }
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            if (!ROLE_TYPES.contains(entry.getKey())) {
                throw new AwsException("InvalidParameterException",
                        "Role key '" + entry.getKey() + "' must be one of authenticated, unauthenticated.", 400);
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new AwsException("InvalidParameterException",
                        "Role ARN for '" + entry.getKey() + "' must not be empty.", 400);
            }
        }
        validateRoleMappings(roleMappings);

        pool.setRoles(new LinkedHashMap<>(roles));
        pool.setRoleMappings(roleMappings != null && roleMappings.isObject() ? roleMappings : null);
        pools.put(storageKey(region, identityPoolId), pool);
        return pool;
    }

    public IdentityPool getIdentityPoolRoles(String identityPoolId, String region) {
        return describeIdentityPool(identityPoolId, region);
    }

    // ──────────────────────── Principal tag attribute maps ────────────────────────

    public PrincipalTagAttributeMap setPrincipalTagAttributeMap(String identityPoolId, String identityProviderName,
                                                                Boolean useDefaults, Map<String, String> principalTags,
                                                                String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        if (identityProviderName == null || identityProviderName.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityProviderName is required.", 400);
        }

        PrincipalTagAttributeMap attributeMap = new PrincipalTagAttributeMap();
        attributeMap.setIdentityProviderName(identityProviderName);
        attributeMap.setUseDefaults(useDefaults != null && useDefaults);
        attributeMap.setPrincipalTags(principalTags != null ? new LinkedHashMap<>(principalTags) : new LinkedHashMap<>());

        pool.getPrincipalTagAttributeMaps().put(identityProviderName, attributeMap);
        pools.put(storageKey(region, identityPoolId), pool);
        return attributeMap;
    }

    /**
     * A provider with no attribute map is not an error in AWS. The read returns the provider
     * with an empty map, so an unset provider yields defaults rather than a not-found.
     */
    public PrincipalTagAttributeMap getPrincipalTagAttributeMap(String identityPoolId, String identityProviderName,
                                                                String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        if (identityProviderName == null || identityProviderName.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityProviderName is required.", 400);
        }
        PrincipalTagAttributeMap attributeMap = pool.getPrincipalTagAttributeMaps().get(identityProviderName);
        if (attributeMap != null) {
            return attributeMap;
        }
        PrincipalTagAttributeMap empty = new PrincipalTagAttributeMap();
        empty.setIdentityProviderName(identityProviderName);
        return empty;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return findByArn(resourceArn, region).getIdentityPoolTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        if (tags == null || tags.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Tags is required.", 400);
        }
        ReservedTags.rejectUnknownReservedTags(tags, "InvalidParameterException");
        IdentityPool pool = findByArn(resourceArn, region);
        pool.getIdentityPoolTags().putAll(ReservedTags.stripReservedTags(tags));
        pools.put(storageKey(pool), pool);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("InvalidParameterException", "TagKeys is required.", 400);
        }
        IdentityPool pool = findByArn(resourceArn, region);
        tagKeys.forEach(pool.getIdentityPoolTags()::remove);
        pools.put(storageKey(pool), pool);
    }

    public CognitoFederatedIdentity getId(String identityPoolId, Map<String, String> logins, String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        boolean authenticated = logins != null && !logins.isEmpty();
        if (!authenticated && !pool.isAllowUnauthenticatedIdentities()) {
            throw new AwsException("NotAuthorizedException",
                    "Unauthenticated access is not supported for this identity pool.", 403);
        }
        CognitoFederatedIdentity identity = new CognitoFederatedIdentity();
        identity.setIdentityId(region + ":" + UUID.randomUUID());
        identity.setIdentityPoolId(identityPoolId);
        identity.setLogins(authenticated ? new ArrayList<>(logins.keySet()) : List.of());
        identities.put(identity.getIdentityId(), identity);
        return identity;
    }

    public Map<String, Object> getCredentialsForIdentity(String identityId, Map<String, String> logins,
                                                         String region) {
        CognitoFederatedIdentity identity = describeIdentity(identityId, region);
        IdentityPool pool = describeIdentityPool(identity.getIdentityPoolId(), region);
        boolean authenticated = (logins != null && !logins.isEmpty()) || !identity.getLogins().isEmpty();
        if (!authenticated && !pool.isAllowUnauthenticatedIdentities()) {
            throw new AwsException("NotAuthorizedException",
                    "Unauthenticated access is not supported for this identity pool.", 403);
        }
        String roleArn = pool.getRoles().get(authenticated ? "authenticated" : "unauthenticated");
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidIdentityPoolConfigurationException",
                    "Invalid identity pool configuration. Check assigned IAM roles for this pool.", 400);
        }
        // Local placeholder credentials preserve the fork's credential-vending contract.
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("AccessKeyId", "ASIA" + hex(16));
        credentials.put("SecretKey", hex(40));
        credentials.put("SessionToken", hex(64));
        credentials.put("Expiration", Instant.now().getEpochSecond() + 3600);
        return Map.of("IdentityId", identityId, "Credentials", credentials);
    }

    public Map<String, Object> getOpenIdToken(String identityId, String region) {
        CognitoFederatedIdentity identity = describeIdentity(identityId, region);
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String authentication = identity.getLogins().isEmpty() ? "unauthenticated" : "authenticated";
        String payload = base64Url("{\"sub\":\"" + identity.getIdentityId()
                + "\",\"aud\":\"" + identity.getIdentityPoolId()
                + "\",\"iss\":\"https://cognito-identity.amazonaws.com\",\"amr\":[\"" + authentication + "\"]}");
        return Map.of("IdentityId", identityId, "Token", header + "." + payload + ".");
    }

    public CognitoFederatedIdentity describeIdentity(String identityId, String region) {
        if (identityId == null || identityId.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityId is required.", 400);
        }
        CognitoFederatedIdentity identity = findIdentity(identityId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Identity '" + identityId + "' not found.", 404));
        describeIdentityPool(identity.getIdentityPoolId(), region);
        return identity;
    }

    public List<CognitoFederatedIdentity> listIdentities(String identityPoolId, String region) {
        describeIdentityPool(identityPoolId, region);
        return identities.scan(id -> true).stream()
                .filter(identity -> identityPoolId.equals(identity.getIdentityPoolId()))
                .sorted(Comparator.comparing(CognitoFederatedIdentity::getIdentityId))
                .toList();
    }

    public List<Map<String, String>> deleteIdentities(List<String> identityIds, String region) {
        List<Map<String, String>> unprocessed = new ArrayList<>();
        if (identityIds == null) {
            return unprocessed;
        }
        for (String identityId : identityIds) {
            if (identityId == null || identityId.isBlank()) {
                continue;
            }
            CognitoFederatedIdentity identity = findIdentity(identityId).orElse(null);
            if (identity == null || pools.get(storageKey(region, identity.getIdentityPoolId())).isEmpty()) {
                unprocessed.add(Map.of("IdentityId", identityId, "ErrorCode", "AccessDenied"));
            } else {
                identities.delete(identityId);
            }
        }
        return unprocessed;
    }

    private Optional<CognitoFederatedIdentity> findIdentity(String identityId) {
        if (identities instanceof AccountAwareStorageBackend<CognitoFederatedIdentity> managedIdentities) {
            // Migration retains unscoped source rows; fallback reads would resurrect deleted identities.
            return managedIdentities.getForAccount(managedIdentities.accountId(), identityId);
        }
        return identities.get(identityId);
    }

    private static String hex(int length) {
        String value = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        return value.substring(0, length);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /** One page of {@code ListIdentityPools} results plus the token for the page after it. */
    public record Page(List<IdentityPool> identityPools, String nextToken) {}

    private void validatePoolConfiguration(IdentityPool spec) {
        String name = spec.getIdentityPoolName();
        if (name == null || name.isBlank() || name.length() > 128 || !IDENTITY_POOL_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "IdentityPoolName must be 1-128 characters matching [\\w\\s+=,.@-]+.", 400);
        }
        String developerProviderName = spec.getDeveloperProviderName();
        if (developerProviderName != null
                && (developerProviderName.length() > 128
                    || !DEVELOPER_PROVIDER_NAME.matcher(developerProviderName).matches())) {
            throw new AwsException("InvalidParameterException",
                    "DeveloperProviderName must be 1-128 characters matching [\\w._-]+.", 400);
        }
    }

    private void validateRoleMappings(JsonNode roleMappings) {
        if (roleMappings == null || !roleMappings.isObject()) {
            return;
        }
        roleMappings.fields().forEachRemaining(entry -> {
            JsonNode mapping = entry.getValue();
            String type = mapping.path("Type").asText(null);
            if (type == null || !ROLE_MAPPING_TYPES.contains(type)) {
                throw new AwsException("InvalidParameterException",
                        "RoleMapping Type for '" + entry.getKey() + "' must be one of Token, Rules.", 400);
            }
            if ("Rules".equals(type)) {
                JsonNode rules = mapping.path("RulesConfiguration").path("Rules");
                if (!rules.isArray() || rules.isEmpty()) {
                    throw new AwsException("InvalidParameterException",
                            "RoleMapping '" + entry.getKey() + "' of type Rules requires RulesConfiguration.Rules.",
                            400);
                }
            }
        });
    }

    private void requireIdentityPoolId(String identityPoolId) {
        if (identityPoolId == null || identityPoolId.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityPoolId is required.", 400);
        }
    }

    private IdentityPool findByArn(String resourceArn, String region) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "Invalid ResourceArn: " + resourceArn, 400);
        }
        if (!"cognito-identity".equals(arn.service()) || !arn.resource().startsWith(ARN_RESOURCE_PREFIX)) {
            throw new AwsException("InvalidParameterException", "Invalid ResourceArn: " + resourceArn, 400);
        }
        String identityPoolId = arn.resource().substring(ARN_RESOURCE_PREFIX.length());
        String arnRegion = arn.region().isEmpty() ? region : arn.region();
        return pools.get(storageKey(arnRegion, identityPoolId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "IdentityPool '" + identityPoolId + "' not found.", 404));
    }

    private int parseToken(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(nextToken);
            if (offset < 0) {
                throw new NumberFormatException(nextToken);
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterException", "Invalid NextToken: " + nextToken, 400);
        }
    }

    private String storageKey(String region, String identityPoolId) {
        return region + "::" + identityPoolId;
    }

    private String storageKey(IdentityPool pool) {
        return storageKey(AwsArnUtils.parse(pool.getArn()).region(), pool.getIdentityPoolId());
    }
}

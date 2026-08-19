package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognito.model.CognitoIdentityPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cognito Identity (federated identities) — identity pools, guest GetId,
 * credentials, and identity administration. Role ARNs are stored as-is;
 * Floci does not enforce iam:PassRole / cross-account checks because the
 * emulator account (000000000000) is the only account that exists.
 */
@ApplicationScoped
public class CognitoIdentityService {
    private static final Logger LOG = Logger.getLogger(CognitoIdentityService.class);
    private static final String IDENTITY_POOL_RESOURCE_PREFIX = "identitypool/";

    private final StorageBackend<String, CognitoIdentityPool> poolStore;
    private final StorageBackend<String, CognitoFederatedIdentity> identityStore;
    private final RegionResolver regionResolver;

    @Inject
    public CognitoIdentityService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create("cognito", "cognito-identity-pools.json",
                        new TypeReference<Map<String, CognitoIdentityPool>>() {}),
                storageFactory.create("cognito", "cognito-identities.json",
                        new TypeReference<Map<String, CognitoFederatedIdentity>>() {}),
                regionResolver
        );
    }

    CognitoIdentityService(StorageBackend<String, CognitoIdentityPool> poolStore,
                           StorageBackend<String, CognitoFederatedIdentity> identityStore,
                           RegionResolver regionResolver) {
        this.poolStore = poolStore;
        this.identityStore = identityStore;
        this.regionResolver = regionResolver;
    }

    public CognitoIdentityPool createIdentityPool(String region, Map<String, Object> request) {
        String name = requiredString(request.get("IdentityPoolName"), "IdentityPoolName");
        CognitoIdentityPool pool = new CognitoIdentityPool();
        pool.setIdentityPoolId(region + ":" + UUID.randomUUID());
        applyConfig(pool, request, true);
        pool.setIdentityPoolName(name);
        Object tagsObj = request.get("IdentityPoolTags");
        if (tagsObj instanceof Map<?, ?> tags) {
            pool.setIdentityPoolTags(ReservedTags.stripReservedTags(stringMap(tags)));
        }
        poolStore.put(pool.getIdentityPoolId(), pool);
        LOG.infov("Created Identity Pool: {0}", pool.getIdentityPoolId());
        return pool;
    }

    public CognitoIdentityPool describeIdentityPool(String identityPoolId) {
        return requirePool(identityPoolId);
    }

    public CognitoIdentityPool updateIdentityPool(Map<String, Object> request) {
        String identityPoolId = requiredString(request.get("IdentityPoolId"), "IdentityPoolId");
        CognitoIdentityPool pool = requirePool(identityPoolId);
        applyConfig(pool, request, true);
        // UpdateIdentityPool is a full PUT of mutable config; tags stay on TagResource.
        poolStore.put(identityPoolId, pool);
        LOG.infov("Updated Identity Pool: {0}", identityPoolId);
        return pool;
    }

    public void deleteIdentityPool(String identityPoolId) {
        requirePool(identityPoolId);
        identityStore.scan(id -> true).stream()
                .filter(identity -> identityPoolId.equals(identity.getIdentityPoolId()))
                .forEach(identity -> identityStore.delete(identity.getIdentityId()));
        poolStore.delete(identityPoolId);
        LOG.infov("Deleted Identity Pool: {0}", identityPoolId);
    }

    public List<CognitoIdentityPool> listIdentityPools() {
        List<CognitoIdentityPool> pools = new ArrayList<>(poolStore.scan(id -> true));
        pools.sort(Comparator.comparing(CognitoIdentityPool::getIdentityPoolId));
        return pools;
    }

    public void setIdentityPoolRoles(String identityPoolId, Map<String, String> roles,
                                     Map<String, Object> roleMappings) {
        CognitoIdentityPool pool = requirePool(identityPoolId);
        // Store any role ARN. Real AWS calls iam:PassRole and rejects a role whose
        // account does not match the caller; the emulator has a single account and
        // must accept the local IAM role ARNs Alchemy attaches.
        pool.setRoles(roles == null ? Map.of() : roles);
        pool.setRoleMappings(roleMappings == null ? Map.of() : roleMappings);
        poolStore.put(identityPoolId, pool);
        LOG.infov("Set Identity Pool roles for {0}: {1}", identityPoolId, pool.getRoles().keySet());
    }

    public CognitoIdentityPool getIdentityPoolRoles(String identityPoolId) {
        return requirePool(identityPoolId);
    }

    public CognitoFederatedIdentity getId(String identityPoolId, Map<String, String> logins) {
        CognitoIdentityPool pool = requirePool(identityPoolId);
        boolean authenticated = logins != null && !logins.isEmpty();
        if (!authenticated && !pool.isAllowUnauthenticatedIdentities()) {
            throw new AwsException("NotAuthorizedException",
                    "Unauthenticated access is not supported for this identity pool.", 403);
        }
        String region = regionFromIdentityPoolId(identityPoolId);
        CognitoFederatedIdentity identity = new CognitoFederatedIdentity();
        identity.setIdentityId(region + ":" + UUID.randomUUID());
        identity.setIdentityPoolId(identityPoolId);
        identity.setLogins(authenticated ? new ArrayList<>(logins.keySet()) : List.of());
        identityStore.put(identity.getIdentityId(), identity);
        LOG.infov("Issued IdentityId {0} for pool {1}", identity.getIdentityId(), identityPoolId);
        return identity;
    }

    public Map<String, Object> getCredentialsForIdentity(String identityId, Map<String, String> logins) {
        CognitoFederatedIdentity identity = requireIdentity(identityId);
        CognitoIdentityPool pool = requirePool(identity.getIdentityPoolId());
        boolean authenticated = (logins != null && !logins.isEmpty())
                || (identity.getLogins() != null && !identity.getLogins().isEmpty());
        String roleKey = authenticated ? "authenticated" : "unauthenticated";
        String roleArn = pool.getRoles().get(roleKey);
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidIdentityPoolConfigurationException",
                    "Invalid identity pool configuration. Check assigned IAM roles for this pool.", 400);
        }
        long expiration = System.currentTimeMillis() / 1000L + 3600;
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("AccessKeyId", "ASIA" + hex(16));
        credentials.put("SecretKey", hex(40));
        credentials.put("SessionToken", hex(64));
        credentials.put("Expiration", expiration);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("IdentityId", identityId);
        response.put("Credentials", credentials);
        return response;
    }

    public Map<String, Object> getOpenIdToken(String identityId) {
        CognitoFederatedIdentity identity = requireIdentity(identityId);
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + identity.getIdentityId()
                + "\",\"aud\":\"" + identity.getIdentityPoolId()
                + "\",\"iss\":\"https://cognito-identity.amazonaws.com\",\"amr\":[\"unauthenticated\"]}");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("IdentityId", identityId);
        response.put("Token", header + "." + payload + ".");
        return response;
    }

    public CognitoFederatedIdentity describeIdentity(String identityId) {
        return requireIdentity(identityId);
    }

    public List<CognitoFederatedIdentity> listIdentities(String identityPoolId) {
        requirePool(identityPoolId);
        List<CognitoFederatedIdentity> identities = identityStore.scan(id -> true).stream()
                .filter(identity -> identityPoolId.equals(identity.getIdentityPoolId()))
                .sorted(Comparator.comparing(CognitoFederatedIdentity::getIdentityId))
                .toList();
        return new ArrayList<>(identities);
    }

    public List<Map<String, String>> deleteIdentities(List<String> identityIds) {
        List<Map<String, String>> unprocessed = new ArrayList<>();
        if (identityIds == null) {
            return unprocessed;
        }
        for (String identityId : identityIds) {
            if (identityId == null || identityId.isBlank()) {
                continue;
            }
            if (identityStore.get(identityId).isEmpty()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("IdentityId", identityId);
                item.put("ErrorCode", "AccessDenied");
                unprocessed.add(item);
                continue;
            }
            identityStore.delete(identityId);
        }
        return unprocessed;
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return new LinkedHashMap<>(requirePool(poolIdFromArn(resourceArn)).getIdentityPoolTags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Tags are required", 400);
        }
        ReservedTags.rejectUnknownReservedTags(tags, "InvalidParameterException");
        CognitoIdentityPool pool = requirePool(poolIdFromArn(resourceArn));
        Map<String, String> merged = new LinkedHashMap<>(pool.getIdentityPoolTags());
        merged.putAll(ReservedTags.stripReservedTags(tags));
        pool.setIdentityPoolTags(merged);
        poolStore.put(pool.getIdentityPoolId(), pool);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("InvalidParameterException", "TagKeys are required", 400);
        }
        CognitoIdentityPool pool = requirePool(poolIdFromArn(resourceArn));
        Map<String, String> tags = new LinkedHashMap<>(pool.getIdentityPoolTags());
        tagKeys.forEach(tags::remove);
        pool.setIdentityPoolTags(tags);
        poolStore.put(pool.getIdentityPoolId(), pool);
    }

    public String identityPoolArn(String identityPoolId) {
        String region = regionFromIdentityPoolId(identityPoolId);
        return regionResolver.buildArn("cognito-identity", region, IDENTITY_POOL_RESOURCE_PREFIX + identityPoolId);
    }

    private CognitoIdentityPool requirePool(String identityPoolId) {
        if (identityPoolId == null || identityPoolId.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityPoolId is required", 400);
        }
        return poolStore.get(identityPoolId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "IdentityPool '" + identityPoolId + "' not found.", 404));
    }

    private CognitoFederatedIdentity requireIdentity(String identityId) {
        if (identityId == null || identityId.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityId is required", 400);
        }
        return identityStore.get(identityId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Identity '" + identityId + "' not found.", 404));
    }

    @SuppressWarnings("unchecked")
    private void applyConfig(CognitoIdentityPool pool, Map<String, Object> request, boolean replaceCollections) {
        if (request.containsKey("IdentityPoolName")) {
            pool.setIdentityPoolName(requiredString(request.get("IdentityPoolName"), "IdentityPoolName"));
        }
        if (request.containsKey("AllowUnauthenticatedIdentities")) {
            pool.setAllowUnauthenticatedIdentities(asBoolean(request.get("AllowUnauthenticatedIdentities")));
        }
        if (request.containsKey("AllowClassicFlow")) {
            Object value = request.get("AllowClassicFlow");
            pool.setAllowClassicFlow(value == null ? null : asBoolean(value));
        } else if (replaceCollections) {
            pool.setAllowClassicFlow(null);
        }
        if (request.containsKey("SupportedLoginProviders") || replaceCollections) {
            Object value = request.get("SupportedLoginProviders");
            pool.setSupportedLoginProviders(value instanceof Map<?, ?> map ? stringMap(map) : Map.of());
        }
        if (request.containsKey("DeveloperProviderName") || replaceCollections) {
            Object value = request.get("DeveloperProviderName");
            pool.setDeveloperProviderName(value instanceof String s && !s.isBlank() ? s : null);
        }
        if (request.containsKey("OpenIdConnectProviderARNs") || replaceCollections) {
            Object value = request.get("OpenIdConnectProviderARNs");
            pool.setOpenIdConnectProviderARNs(value instanceof List<?> list ? stringList(list) : List.of());
        }
        if (request.containsKey("CognitoIdentityProviders") || replaceCollections) {
            Object value = request.get("CognitoIdentityProviders");
            pool.setCognitoIdentityProviders(value instanceof List<?> list ? objectMapList(list) : List.of());
        }
        if (request.containsKey("SamlProviderARNs") || replaceCollections) {
            Object value = request.get("SamlProviderARNs");
            pool.setSamlProviderARNs(value instanceof List<?> list ? stringList(list) : List.of());
        }
    }

    private String poolIdFromArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidParameterException", "ResourceArn is required", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "Invalid resource ARN: " + resourceArn, 400);
        }
        if (!"cognito-identity".equals(arn.service())) {
            throw new AwsException("InvalidParameterException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String resource = arn.resource();
        if (!resource.startsWith(IDENTITY_POOL_RESOURCE_PREFIX)) {
            throw new AwsException("InvalidParameterException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String poolId = resource.substring(IDENTITY_POOL_RESOURCE_PREFIX.length());
        if (poolId.isBlank()) {
            throw new AwsException("InvalidParameterException", "Invalid resource ARN: " + resourceArn, 400);
        }
        return poolId;
    }

    private String regionFromIdentityPoolId(String identityPoolId) {
        int colon = identityPoolId.indexOf(':');
        if (colon > 0) {
            return identityPoolId.substring(0, colon);
        }
        return regionResolver.getDefaultRegion();
    }

    private static String requiredString(Object value, String field) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new AwsException("InvalidParameterException", field + " is required", 400);
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        throw new AwsException("InvalidParameterException", "Expected a boolean value", 400);
    }

    private static Map<String, String> stringMap(Map<?, ?> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }

    private static List<String> stringList(List<?> raw) {
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMapList(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                out.add(copy);
            }
        }
        return out;
    }

    private static String hex(int chars) {
        String uuid = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        return uuid.substring(0, Math.min(chars, uuid.length()));
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

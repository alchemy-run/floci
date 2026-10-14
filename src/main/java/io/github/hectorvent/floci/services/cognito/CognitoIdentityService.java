package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognito.model.CognitoIdentityPool;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentityProvider;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compatibility adapter for callers of the original Cognito Identity Java API. */
@ApplicationScoped
public class CognitoIdentityService {

    // The legacy and canonical services share a class name, so qualify the delegate type.
    private final io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityService delegate;
    private final io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityJsonHandler handler;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public CognitoIdentityService(
            io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityService delegate,
            ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.handler = new io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityJsonHandler(
                delegate, objectMapper);
    }

    public CognitoIdentityService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(new io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityService(
                storageFactory, regionResolver), new ObjectMapper(), regionResolver);
    }

    CognitoIdentityService(StorageBackend<String, IdentityPool> pools,
                          StorageBackend<String, CognitoFederatedIdentity> identities,
                          RegionResolver regionResolver) {
        this(new io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityService(
                pools, identities, regionResolver), new ObjectMapper(), regionResolver);
    }

    public CognitoIdentityPool createIdentityPool(String region, Map<String, Object> request) {
        return managePool("CreateIdentityPool", request, region);
    }

    public CognitoIdentityPool describeIdentityPool(String identityPoolId) {
        return legacyPool(delegate.describeIdentityPool(identityPoolId, regionFromId(identityPoolId)));
    }

    public CognitoIdentityPool updateIdentityPool(Map<String, Object> request) {
        return managePool("UpdateIdentityPool", request, regionFromId((String) request.get("IdentityPoolId")));
    }

    public void deleteIdentityPool(String identityPoolId) {
        delegate.deleteIdentityPool(identityPoolId, regionFromId(identityPoolId));
    }

    public List<CognitoIdentityPool> listIdentityPools() {
        List<CognitoIdentityPool> result = new ArrayList<>();
        delegate.listIdentityPools().forEach(pool -> result.add(legacyPool(pool)));
        return result;
    }

    public void setIdentityPoolRoles(String identityPoolId, Map<String, String> roles,
                                     Map<String, Object> roleMappings) {
        delegate.setIdentityPoolRoles(identityPoolId, roles, objectMapper.valueToTree(roleMappings),
                regionFromId(identityPoolId));
    }

    public CognitoIdentityPool getIdentityPoolRoles(String identityPoolId) {
        return legacyPool(delegate.getIdentityPoolRoles(identityPoolId, regionFromId(identityPoolId)));
    }

    public CognitoFederatedIdentity getId(String identityPoolId, Map<String, String> logins) {
        return delegate.getId(identityPoolId, logins, regionFromId(identityPoolId));
    }

    public Map<String, Object> getCredentialsForIdentity(String identityId, Map<String, String> logins) {
        return delegate.getCredentialsForIdentity(identityId, logins, regionFromId(identityId));
    }

    public Map<String, Object> getOpenIdToken(String identityId) {
        return delegate.getOpenIdToken(identityId, regionFromId(identityId));
    }

    public CognitoFederatedIdentity describeIdentity(String identityId) {
        return delegate.describeIdentity(identityId, regionFromId(identityId));
    }

    public List<CognitoFederatedIdentity> listIdentities(String identityPoolId) {
        return new ArrayList<>(delegate.listIdentities(identityPoolId, regionFromId(identityPoolId)));
    }

    public List<Map<String, String>> deleteIdentities(List<String> identityIds) {
        List<Map<String, String>> unprocessed = new ArrayList<>();
        if (identityIds != null) {
            for (String identityId : identityIds) {
                if (identityId != null && !identityId.isBlank()) {
                    unprocessed.addAll(delegate.deleteIdentities(List.of(identityId), regionFromId(identityId)));
                }
            }
        }
        return unprocessed;
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return new LinkedHashMap<>(delegate.listTagsForResource(resourceArn, regionResolver.getDefaultRegion()));
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        delegate.tagResource(resourceArn, tags, regionResolver.getDefaultRegion());
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        delegate.untagResource(resourceArn, tagKeys, regionResolver.getDefaultRegion());
    }

    public String identityPoolArn(String identityPoolId) {
        return regionResolver.buildArn("cognito-identity", regionFromId(identityPoolId),
                "identitypool/" + identityPoolId);
    }

    io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityJsonHandler handler() {
        return handler;
    }

    private CognitoIdentityPool managePool(String action, Map<String, Object> request, String region) {
        JsonNode response = (JsonNode) handler.handle(action, objectMapper.valueToTree(request), region).getEntity();
        return legacyPool(delegate.describeIdentityPool(response.path("IdentityPoolId").asText(), region));
    }

    private CognitoIdentityPool legacyPool(IdentityPool pool) {
        CognitoIdentityPool legacy = new CognitoIdentityPool();
        legacy.setIdentityPoolId(pool.getIdentityPoolId());
        legacy.setIdentityPoolName(pool.getIdentityPoolName());
        legacy.setAllowUnauthenticatedIdentities(pool.isAllowUnauthenticatedIdentities());
        legacy.setAllowClassicFlow(pool.isAllowClassicFlow());
        legacy.setSupportedLoginProviders(pool.getSupportedLoginProviders());
        legacy.setDeveloperProviderName(pool.getDeveloperProviderName());
        legacy.setOpenIdConnectProviderARNs(pool.getOpenIdConnectProviderArns());
        legacy.setSamlProviderARNs(pool.getSamlProviderArns());
        legacy.setIdentityPoolTags(pool.getIdentityPoolTags());
        legacy.setRoles(pool.getRoles());
        legacy.setRoleMappings(pool.getRoleMappings() == null ? Map.of()
                : objectMapper.convertValue(pool.getRoleMappings(), new TypeReference<Map<String, Object>>() {}));
        List<Map<String, Object>> providers = new ArrayList<>();
        for (CognitoIdentityProvider provider : pool.getCognitoIdentityProviders()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("ProviderName", provider.getProviderName());
            values.put("ClientId", provider.getClientId());
            values.put("ServerSideTokenCheck", provider.isServerSideTokenCheck());
            providers.add(values);
        }
        legacy.setCognitoIdentityProviders(providers);
        return legacy;
    }

    private String regionFromId(String id) {
        int colon = id == null ? -1 : id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : regionResolver.getDefaultRegion();
    }
}

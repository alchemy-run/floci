package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognito.model.CognitoIdentityPool;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentityProvider;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies fork storage into the canonical control plane without modifying legacy pool rows. */
final class CognitoIdentityMigration {

    private static final Logger LOG = Logger.getLogger(CognitoIdentityMigration.class);

    private CognitoIdentityMigration() {
    }

    static void migrate(StorageFactory storageFactory, AccountAwareStorageBackend<IdentityPool> pools,
                        AccountAwareStorageBackend<CognitoFederatedIdentity> identities) {
        AccountAwareStorageBackend<CognitoIdentityPool> legacyPools = storageFactory.create(
                "cognito", "cognito-identity-pools.json", new TypeReference<Map<String, CognitoIdentityPool>>() {});
        AccountAwareStorageBackend<Boolean> migratedPools = storageFactory.create(
                "cognitoidentity", "cognitoidentity-migrated-pools.json", new TypeReference<Map<String, Boolean>>() {});
        synchronized (legacyPools) {
            Map<String, IdentityPool> existingPools = new LinkedHashMap<>();
            for (AccountEntry<IdentityPool> entry : pools.scanAllAccountEntries(key -> true)) {
                existingPools.put(entry.accountId() + "/" + entry.key(), entry.value());
            }
            Map<String, List<AccountEntry<CognitoFederatedIdentity>>> identitiesByPool = new LinkedHashMap<>();
            for (AccountEntry<CognitoFederatedIdentity> entry : identities.scanAllAccountEntries(key -> true)) {
                if (entry.key().equals(entry.value().getIdentityId())) {
                    identitiesByPool.computeIfAbsent(entry.accountId() + "/" + entry.value().getIdentityPoolId(),
                            key -> new ArrayList<>()).add(entry);
                } else {
                    LOG.warnv("Skipping Cognito identity migration with inconsistent key: {0}/{1}",
                            entry.accountId(), entry.key());
                }
            }
            ObjectMapper objectMapper = new ObjectMapper();
            for (AccountEntry<CognitoIdentityPool> entry : legacyPools.scanAllAccountEntries(key -> true)) {
                String accountId = entry.accountId();
                String poolId = entry.key();
                // An account-prefixed legacy row takes precedence over its pre-account counterpart.
                CognitoIdentityPool legacy = legacyPools.getForAccount(accountId, poolId).orElse(entry.value());
                int colon = poolId.indexOf(':');
                if (!poolId.equals(legacy.getIdentityPoolId()) || colon < 1 || poolId.contains("/")) {
                    LOG.warnv("Skipping Cognito identity pool migration with inconsistent key: {0}/{1}",
                            accountId, poolId);
                    continue;
                }
                String region = poolId.substring(0, colon);
                String key = region + "::" + poolId;
                if (migratedPools.getForAccount(accountId, key).orElse(false)) {
                    continue;
                }
                IdentityPool existing = pools.getForAccount(accountId, key)
                        .orElse(existingPools.get(accountId + "/" + key));
                if (pools.getForAccount(accountId, key).isEmpty()) {
                    pools.putForAccount(accountId, key, existing != null
                            ? existing : convertPool(legacy, accountId, region, objectMapper));
                }
                for (AccountEntry<CognitoFederatedIdentity> identity :
                        identitiesByPool.getOrDefault(accountId + "/" + poolId, List.of())) {
                    if (identities.getForAccount(accountId, identity.key()).isEmpty()) {
                        identities.putForAccount(accountId, identity.key(), identity.value());
                    }
                }
                // Flush destinations before recording completion, including in hybrid mode.
                pools.flush();
                identities.flush();
                migratedPools.putForAccount(accountId, key, true);
                migratedPools.flush();
                LOG.infov("Migrated Cognito identity pool storage: {0}/{1} (existing canonical pool: {2})",
                        accountId, poolId, existing != null);
            }
        }
    }

    private static IdentityPool convertPool(CognitoIdentityPool legacy, String accountId, String region,
                                            ObjectMapper objectMapper) {
        IdentityPool pool = new IdentityPool();
        pool.setIdentityPoolId(legacy.getIdentityPoolId());
        pool.setIdentityPoolName(legacy.getIdentityPoolName());
        pool.setAllowUnauthenticatedIdentities(legacy.isAllowUnauthenticatedIdentities());
        pool.setAllowClassicFlow(Boolean.TRUE.equals(legacy.getAllowClassicFlow()));
        pool.setSupportedLoginProviders(new LinkedHashMap<>(legacy.getSupportedLoginProviders()));
        pool.setDeveloperProviderName(legacy.getDeveloperProviderName());
        pool.setOpenIdConnectProviderArns(new ArrayList<>(legacy.getOpenIdConnectProviderARNs()));
        pool.setSamlProviderArns(new ArrayList<>(legacy.getSamlProviderARNs()));
        pool.setIdentityPoolTags(new LinkedHashMap<>(legacy.getIdentityPoolTags()));
        pool.setRoles(new LinkedHashMap<>(legacy.getRoles()));
        pool.setRoleMappings(objectMapper.valueToTree(legacy.getRoleMappings()));
        List<CognitoIdentityProvider> providers = new ArrayList<>();
        for (Map<String, Object> values : legacy.getCognitoIdentityProviders()) {
            JsonNode value = objectMapper.valueToTree(values);
            CognitoIdentityProvider provider = new CognitoIdentityProvider();
            provider.setProviderName(value.path("ProviderName").asText(null));
            provider.setClientId(value.path("ClientId").asText(null));
            provider.setServerSideTokenCheck(value.path("ServerSideTokenCheck").asBoolean(false));
            providers.add(provider);
        }
        pool.setCognitoIdentityProviders(providers);
        pool.setAccountId(accountId);
        pool.setArn(AwsArnUtils.Arn.of("cognito-identity", region, accountId,
                "identitypool/" + legacy.getIdentityPoolId()).toString());
        return pool;
    }
}

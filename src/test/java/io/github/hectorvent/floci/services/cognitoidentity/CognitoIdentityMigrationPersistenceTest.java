package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognito.model.CognitoIdentityPool;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CognitoIdentityMigrationPersistenceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String FOREIGN_ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String POOL_ID = REGION + ":11111111-1111-1111-1111-111111111111";
    private static final String IDENTITY_ID = REGION + ":22222222-2222-2222-2222-222222222222";
    private static final String SECOND_IDENTITY_ID = REGION + ":33333333-3333-3333-3333-333333333333";
    private static final String ROLE = "arn:aws:iam::391965393224:role/Guest";
    private static final String PROVIDER = "cognito-idp.us-east-1.amazonaws.com/us-east-1_example";
    private static final String LEGACY_FILE = "cognito-identity-pools.json";
    private static final String POOL_FILE = "cognitoidentity-pools.json";
    private static final String IDENTITY_FILE = "cognito-identities.json";
    private static final TypeReference<Map<String, CognitoIdentityPool>> LEGACY_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, IdentityPool>> POOL_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, CognitoFederatedIdentity>> IDENTITY_TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid"})
    void forkPoolsRolesTagsAndIdentitiesSurviveMigrationAndRestart(String mode) throws IOException {
        seed(LEGACY_FILE, Map.of(ACCOUNT + "/" + POOL_ID, legacyPool(POOL_ID, "fork/pool")), LEGACY_TYPE);
        seed(IDENTITY_FILE, Map.of(
                ACCOUNT + "/" + IDENTITY_ID, identity(IDENTITY_ID, POOL_ID, List.of()),
                ACCOUNT + "/" + SECOND_IDENTITY_ID, identity(SECOND_IDENTITY_ID, POOL_ID, List.of(PROVIDER))), IDENTITY_TYPE);
        JsonNode originalLegacyRows = file(LEGACY_FILE);
        StorageFactory firstFactory = factory(mode);
        try {
            firstFactory.loadAll();
            CognitoIdentityService service = new CognitoIdentityService(firstFactory, regionResolver);
            IdentityPool pool = service.describeIdentityPool(POOL_ID, REGION);
            assertEquals("fork/pool", pool.getIdentityPoolName());
            assertEquals(ACCOUNT, pool.getAccountId());
            assertEquals("arn:aws:cognito-identity:" + REGION + ":" + ACCOUNT + ":identitypool/" + POOL_ID, pool.getArn());
            assertTrue(pool.isAllowUnauthenticatedIdentities());
            assertTrue(pool.isAllowClassicFlow());
            assertEquals(Map.of("graph.facebook.com", "app-id"), pool.getSupportedLoginProviders());
            assertEquals("login.fork.test", pool.getDeveloperProviderName());
            assertEquals(List.of("arn:aws:iam::000000000000:oidc-provider/example.com"), pool.getOpenIdConnectProviderArns());
            assertEquals(List.of("arn:aws:iam::000000000000:saml-provider/example"), pool.getSamlProviderArns());
            assertEquals(PROVIDER, pool.getCognitoIdentityProviders().getFirst().getProviderName());
            assertEquals("client-id", pool.getCognitoIdentityProviders().getFirst().getClientId());
            assertTrue(pool.getCognitoIdentityProviders().getFirst().isServerSideTokenCheck());
            assertEquals("Identities", pool.getIdentityPoolTags().get("alchemy::id"));
            assertEquals(ROLE, pool.getRoles().get("unauthenticated"));
            assertEquals("Token", pool.getRoleMappings().path(PROVIDER).path("Type").asText());
            assertEquals(2, service.listIdentities(POOL_ID, REGION).size());
            assertEquals(1234L, service.describeIdentity(IDENTITY_ID, REGION).getCreationDate());
            assertEquals(5678L, service.describeIdentity(IDENTITY_ID, REGION).getLastModifiedDate());
            assertEquals(List.of(PROVIDER), service.describeIdentity(SECOND_IDENTITY_ID, REGION).getLogins());
            assertCredentials(service, IDENTITY_ID);
            assertCredentials(service, SECOND_IDENTITY_ID);
            assertEquals(IDENTITY_ID, service.getOpenIdToken(IDENTITY_ID, REGION).get("IdentityId"));
            service.tagResource(pool.getArn(), Map.of("afterMigration", "retained"), REGION);
            service.setPrincipalTagAttributeMap(POOL_ID, PROVIDER, false, Map.of("team", "custom:team"), REGION);
            assertEquals(originalLegacyRows, file(LEGACY_FILE));
        } finally {
            firstFactory.shutdownAll();
        }

        StorageFactory secondFactory = factory(mode);
        try {
            CognitoIdentityService reloaded = new CognitoIdentityService(secondFactory, regionResolver);
            assertCredentials(reloaded, IDENTITY_ID);
            assertCredentials(reloaded, SECOND_IDENTITY_ID);
            assertEquals("retained", reloaded.describeIdentityPool(POOL_ID, REGION).getIdentityPoolTags().get("afterMigration"));
            assertEquals(Map.of("team", "custom:team"),
                    reloaded.getPrincipalTagAttributeMap(POOL_ID, PROVIDER, REGION).getPrincipalTags());
            assertEquals(1, reloaded.listIdentityPools(60, null, REGION).identityPools().size());
            assertEquals(originalLegacyRows, file(LEGACY_FILE));
        } finally {
            secondFactory.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid"})
    void canonicalRowsWinWithoutCollapsingAccountOrRegionOwnership(String mode) throws IOException {
        String euPoolId = "eu-west-1:44444444-4444-4444-4444-444444444444";
        seed(LEGACY_FILE, Map.of(
                POOL_ID, legacyPool(POOL_ID, "unscoped stale fork pool"),
                ACCOUNT + "/" + POOL_ID, legacyPool(POOL_ID, "scoped fork pool"),
                FOREIGN_ACCOUNT + "/" + POOL_ID, legacyPool(POOL_ID, "foreign fork pool"),
                FOREIGN_ACCOUNT + "/" + euPoolId, legacyPool(euPoolId, "foreign eu pool")), LEGACY_TYPE);
        IdentityPool existing = new IdentityPool();
        existing.setIdentityPoolId(POOL_ID);
        existing.setIdentityPoolName("canonical winner");
        existing.setAccountId(ACCOUNT);
        existing.setArn("arn:aws:cognito-identity:" + REGION + ":" + ACCOUNT + ":identitypool/" + POOL_ID);
        existing.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        existing.setIdentityPoolTags(Map.of("canonical", "retained"));
        existing.setRoles(Map.of("authenticated", "arn:aws:iam::000000000000:role/Canonical"));
        seed(POOL_FILE, Map.of(ACCOUNT + "/" + REGION + "::" + POOL_ID, existing), POOL_TYPE);
        seed(IDENTITY_FILE, Map.of(
                IDENTITY_ID, identity(IDENTITY_ID, POOL_ID, List.of("stale")),
                ACCOUNT + "/" + IDENTITY_ID, identity(IDENTITY_ID, POOL_ID, List.of("canonical")),
                FOREIGN_ACCOUNT + "/" + IDENTITY_ID, identity(IDENTITY_ID, POOL_ID, List.of("foreign"))), IDENTITY_TYPE);
        JsonNode originalLegacyRows = file(LEGACY_FILE);
        StorageFactory factory = factory(mode);
        try {
            CognitoIdentityService service = new CognitoIdentityService(factory, regionResolver);
            IdentityPool observed = service.describeIdentityPool(POOL_ID, REGION);
            assertEquals("canonical winner", observed.getIdentityPoolName());
            assertEquals(existing.getCreatedAt(), observed.getCreatedAt());
            assertEquals(existing.getIdentityPoolTags(), observed.getIdentityPoolTags());
            assertEquals(existing.getRoles(), observed.getRoles());
            assertFalse(observed.isAllowUnauthenticatedIdentities());
            assertEquals(List.of("canonical"), service.describeIdentity(IDENTITY_ID, REGION).getLogins());
            assertTrue(service.listIdentityPools(60, null, "eu-west-1").identityPools().isEmpty());
            AccountAwareStorageBackend<IdentityPool> pools = factory.create("cognitoidentity", POOL_FILE, POOL_TYPE);
            IdentityPool foreign = pools.getForAccount(FOREIGN_ACCOUNT, REGION + "::" + POOL_ID).orElseThrow();
            assertEquals("foreign fork pool", foreign.getIdentityPoolName());
            assertEquals(FOREIGN_ACCOUNT, foreign.getAccountId());
            assertTrue(foreign.getArn().contains(":" + FOREIGN_ACCOUNT + ":identitypool/"));
            assertTrue(pools.getForAccount(FOREIGN_ACCOUNT, "eu-west-1::" + euPoolId).isPresent());
            assertTrue(pools.getForAccount(FOREIGN_ACCOUNT, REGION + "::" + euPoolId).isEmpty());
            AccountAwareStorageBackend<CognitoFederatedIdentity> identities =
                    factory.create("cognitoidentity", IDENTITY_FILE, IDENTITY_TYPE);
            assertEquals(List.of("foreign"), identities.getForAccount(FOREIGN_ACCOUNT, IDENTITY_ID).orElseThrow().getLogins());
            assertEquals(originalLegacyRows, file(LEGACY_FILE));
        } finally {
            factory.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid"})
    void retainedLegacyRowsDoNotResurrectDeletedIdentitiesOrPools(String mode) throws IOException {
        seed(LEGACY_FILE, Map.of(POOL_ID, legacyPool(POOL_ID, "pre-account fork pool")), LEGACY_TYPE);
        seed(IDENTITY_FILE, Map.of(
                IDENTITY_ID, identity(IDENTITY_ID, POOL_ID, List.of()),
                SECOND_IDENTITY_ID, identity(SECOND_IDENTITY_ID, POOL_ID, List.of())), IDENTITY_TYPE);
        JsonNode originalLegacyRows = file(LEGACY_FILE);
        StorageFactory firstFactory = factory(mode);
        try {
            CognitoIdentityService service = new CognitoIdentityService(firstFactory, regionResolver);
            assertEquals(2, service.listIdentities(POOL_ID, REGION).size());
            assertTrue(file(POOL_FILE).has(ACCOUNT + "/" + REGION + "::" + POOL_ID));
            assertTrue(file(IDENTITY_FILE).has(ACCOUNT + "/" + IDENTITY_ID));
            assertTrue(file("cognitoidentity-migrated-pools.json")
                    .path(ACCOUNT + "/" + REGION + "::" + POOL_ID).asBoolean());
            assertCredentials(service, IDENTITY_ID);
            assertTrue(service.deleteIdentities(List.of(IDENTITY_ID), REGION).isEmpty());
            assertMissingIdentity(service, IDENTITY_ID);
        } finally {
            firstFactory.shutdownAll();
        }
        StorageFactory secondFactory = factory(mode);
        try {
            CognitoIdentityService service = new CognitoIdentityService(secondFactory, regionResolver);
            assertMissingIdentity(service, IDENTITY_ID);
            assertCredentials(service, SECOND_IDENTITY_ID);
            service.deleteIdentityPool(POOL_ID, REGION);
        } finally {
            secondFactory.shutdownAll();
        }
        StorageFactory thirdFactory = factory(mode);
        try {
            CognitoIdentityService service = new CognitoIdentityService(thirdFactory, regionResolver);
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> service.describeIdentityPool(POOL_ID, REGION)).getErrorCode());
            assertMissingIdentity(service, SECOND_IDENTITY_ID);
            assertTrue(service.listIdentityPools(60, null, REGION).identityPools().isEmpty());
            assertEquals(originalLegacyRows, file(LEGACY_FILE));
            assertTrue(file(IDENTITY_FILE).has(IDENTITY_ID));
            assertTrue(file(IDENTITY_FILE).has(SECOND_IDENTITY_ID));
        } finally {
            thirdFactory.shutdownAll();
        }
    }

    private void assertCredentials(CognitoIdentityService service, String identityId) {
        CognitoIdentityJsonHandler handler = new CognitoIdentityJsonHandler(service, objectMapper);
        JsonNode response = (JsonNode) handler.handle("GetCredentialsForIdentity",
                objectMapper.valueToTree(Map.of("IdentityId", identityId)), REGION).getEntity();
        assertEquals(identityId, response.path("IdentityId").asText());
        assertTrue(response.path("Credentials").path("AccessKeyId").asText().startsWith("ASIA"));
    }

    private void assertMissingIdentity(CognitoIdentityService service, String identityId) {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describeIdentity(identityId, REGION)).getErrorCode());
    }

    private CognitoIdentityPool legacyPool(String poolId, String name) {
        CognitoIdentityPool pool = new CognitoIdentityPool();
        pool.setIdentityPoolId(poolId);
        pool.setIdentityPoolName(name);
        pool.setAllowUnauthenticatedIdentities(true);
        pool.setAllowClassicFlow(true);
        pool.setSupportedLoginProviders(Map.of("graph.facebook.com", "app-id"));
        pool.setDeveloperProviderName("login.fork.test");
        pool.setOpenIdConnectProviderARNs(List.of("arn:aws:iam::000000000000:oidc-provider/example.com"));
        pool.setSamlProviderARNs(List.of("arn:aws:iam::000000000000:saml-provider/example"));
        pool.setCognitoIdentityProviders(List.of(Map.of(
                "ProviderName", PROVIDER, "ClientId", "client-id", "ServerSideTokenCheck", true)));
        pool.setRoles(Map.of("unauthenticated", ROLE, "authenticated", ROLE));
        pool.setRoleMappings(Map.of(PROVIDER, Map.of("Type", "Token", "AmbiguousRoleResolution", "AuthenticatedRole")));
        pool.setIdentityPoolTags(Map.of("alchemy::id", "Identities", "team", "identity"));
        return pool;
    }

    private CognitoFederatedIdentity identity(String identityId, String poolId, List<String> logins) {
        CognitoFederatedIdentity identity = new CognitoFederatedIdentity();
        identity.setIdentityId(identityId);
        identity.setIdentityPoolId(poolId);
        identity.setLogins(logins);
        identity.setCreationDate(1234L);
        identity.setLastModifiedDate(5678L);
        return identity;
    }

    private <V> void seed(String fileName, Map<String, V> rows, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> storage = new PersistentStorage<>(directory.resolve(fileName), type);
        storage.load();
        rows.forEach(storage::put);
        storage.flush();
    }

    private JsonNode file(String fileName) throws IOException {
        return objectMapper.readTree(directory.resolve(fileName).toFile());
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.storageMode(anyString())).thenReturn(mode);
        when(serviceConfigAccess.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new StorageFactory(config, serviceConfigAccess);
    }
}

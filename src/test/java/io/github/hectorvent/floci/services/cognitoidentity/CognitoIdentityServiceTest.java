package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoIdentityServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::391965393224:role/Guest";

    private InMemoryStorage<String, IdentityPool> pools;
    private InMemoryStorage<String, CognitoFederatedIdentity> identities;
    private CognitoIdentityService service;
    private ObjectMapper objectMapper;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        pools = new InMemoryStorage<>();
        identities = new InMemoryStorage<>();
        regionResolver = new RegionResolver(REGION, "000000000000");
        objectMapper = new ObjectMapper();
        service = new CognitoIdentityService(pools, identities, regionResolver);
    }

    @Test
    void legacyServiceAndHandlerUseTheCanonicalStoreAndValidation() {
        // The legacy service and handler share their names with the canonical types.
        io.github.hectorvent.floci.services.cognito.CognitoIdentityService legacy =
                new io.github.hectorvent.floci.services.cognito.CognitoIdentityService(
                        service, objectMapper, regionResolver);
        io.github.hectorvent.floci.services.cognito.CognitoIdentityJsonHandler legacyHandler =
                new io.github.hectorvent.floci.services.cognito.CognitoIdentityJsonHandler(legacy, objectMapper);
        String poolId = legacy.createIdentityPool(REGION, Map.of(
                "IdentityPoolName", "shared pool", "AllowUnauthenticatedIdentities", true)).getIdentityPoolId();
        assertEquals(1, pools.keys().size());
        assertEquals(poolId, service.describeIdentityPool(poolId, REGION).getIdentityPoolId());
        legacyHandler.handle("SetPrincipalTagAttributeMap", objectMapper.valueToTree(Map.of(
                "IdentityPoolId", poolId, "IdentityProviderName", "example.com",
                "PrincipalTags", Map.of("team", "custom:team"))), REGION);
        assertEquals("custom:team", service.getPrincipalTagAttributeMap(poolId, "example.com", REGION)
                .getPrincipalTags().get("team"));
        service.setIdentityPoolRoles(poolId, Map.of("unauthenticated", ROLE), null, REGION);
        String identityId = legacy.getId(poolId, Map.of()).getIdentityId();
        JsonNode credentials = (JsonNode) legacyHandler.handle("GetCredentialsForIdentity",
                objectMapper.valueToTree(Map.of("IdentityId", identityId)), REGION).getEntity();
        assertEquals(identityId, credentials.path("IdentityId").asText());
        assertEquals(1, identities.keys().size());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> legacy.createIdentityPool(REGION, Map.of("IdentityPoolName", "bad/name",
                        "AllowUnauthenticatedIdentities", true))).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> legacy.createIdentityPool(REGION, Map.of("IdentityPoolName", "missing boolean")))
                .getErrorCode());
        assertEquals(1, pools.keys().size());
    }

    @Test
    void disablingGuestAccessAppliesToExistingIdentityCredentials() {
        IdentityPool pool = createPool(true);
        service.setIdentityPoolRoles(pool.getIdentityPoolId(), Map.of("unauthenticated", ROLE), null, REGION);
        String identityId = service.getId(pool.getIdentityPoolId(), Map.of(), REGION).getIdentityId();
        IdentityPool replacement = new IdentityPool();
        replacement.setIdentityPoolId(pool.getIdentityPoolId());
        replacement.setIdentityPoolName("guest disabled");
        replacement.setAllowUnauthenticatedIdentities(false);
        service.updateIdentityPool(replacement, REGION);
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.getCredentialsForIdentity(identityId, Map.of(), REGION)).getErrorCode());
    }

    @Test
    void deletingPoolRemovesItsIdentitiesButKeepsOtherPools() {
        IdentityPool pool = createPool(true);
        IdentityPool otherPool = createPool(true);
        String identityId = service.getId(pool.getIdentityPoolId(), Map.of(), REGION).getIdentityId();
        String otherId = service.getId(otherPool.getIdentityPoolId(), Map.of(), REGION).getIdentityId();
        service.deleteIdentityPool(pool.getIdentityPoolId(), REGION);
        assertFalse(identities.keys().contains(identityId));
        assertTrue(identities.keys().contains(otherId));
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getOpenIdToken(identityId, REGION)).getErrorCode());
        assertEquals(List.of(Map.of("IdentityId", identityId, "ErrorCode", "AccessDenied")),
                service.deleteIdentities(List.of(identityId), REGION));
    }

    @Test
    void explicitUpdateTagsReplaceTagsWhileOmittedTagsSurvive() {
        CognitoIdentityJsonHandler handler = new CognitoIdentityJsonHandler(service, objectMapper);
        IdentityPool pool = createPool(true);
        service.tagResource(pool.getArn(), Map.of("alchemy::id", "Identities"), REGION);
        JsonNode update = objectMapper.valueToTree(Map.of("IdentityPoolId", pool.getIdentityPoolId(),
                "IdentityPoolName", "updated pool", "AllowUnauthenticatedIdentities", true));
        handler.handle("UpdateIdentityPool", update, REGION);
        assertEquals("Identities", service.listTagsForResource(pool.getArn(), REGION).get("alchemy::id"));
        update = objectMapper.valueToTree(Map.of("IdentityPoolId", pool.getIdentityPoolId(),
                "IdentityPoolName", "updated pool", "AllowUnauthenticatedIdentities", true,
                "IdentityPoolTags", Map.of()));
        handler.handle("UpdateIdentityPool", update, REGION);
        assertTrue(service.listTagsForResource(pool.getArn(), REGION).isEmpty());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.tagResource(pool.getArn(), Map.of("floci:unknown", "value"), REGION)).getErrorCode());
    }

    private IdentityPool createPool(boolean allowUnauthenticated) {
        IdentityPool pool = new IdentityPool();
        pool.setIdentityPoolName("unit pool");
        pool.setAllowUnauthenticatedIdentities(allowUnauthenticated);
        return service.createIdentityPool(pool, REGION);
    }
}

package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognito.model.CognitoIdentityPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CognitoIdentityServiceTest {

    private CognitoIdentityService service;

    @BeforeEach
    void setUp() {
        service = new CognitoIdentityService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    @Test
    void identityPoolLifecycleWithTagsAndFederation() {
        CognitoIdentityPool created = service.createIdentityPool("us-east-1", Map.of(
                "IdentityPoolName", "AlchemyPool",
                "AllowUnauthenticatedIdentities", false,
                "IdentityPoolTags", Map.of("Environment", "test", "alchemy::id", "Identities")
        ));
        assertTrue(created.getIdentityPoolId().startsWith("us-east-1:"));
        assertEquals("AlchemyPool", created.getIdentityPoolName());
        assertFalse(created.isAllowUnauthenticatedIdentities());
        assertEquals("test", created.getIdentityPoolTags().get("Environment"));

        String arn = service.identityPoolArn(created.getIdentityPoolId());
        assertTrue(arn.contains(":identitypool/" + created.getIdentityPoolId()));
        assertEquals("test", service.listTagsForResource(arn).get("Environment"));
        assertEquals("Identities", service.listTagsForResource(arn).get("alchemy::id"));

        CognitoIdentityPool updated = service.updateIdentityPool(Map.of(
                "IdentityPoolId", created.getIdentityPoolId(),
                "IdentityPoolName", "AlchemyPool",
                "AllowUnauthenticatedIdentities", true,
                "CognitoIdentityProviders", List.of(Map.of(
                        "ProviderName", "cognito-idp.us-west-2.amazonaws.com/us-east-1_abc",
                        "ClientId", "client-1"
                ))
        ));
        assertEquals(created.getIdentityPoolId(), updated.getIdentityPoolId());
        assertTrue(updated.isAllowUnauthenticatedIdentities());
        assertEquals(1, updated.getCognitoIdentityProviders().size());
        assertEquals("test", updated.getIdentityPoolTags().get("Environment"),
                "UpdateIdentityPool must not wipe tags managed by TagResource");

        service.deleteIdentityPool(created.getIdentityPoolId());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeIdentityPool(created.getIdentityPoolId()));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void setIdentityPoolRolesAcceptsAnyAccountArn() {
        CognitoIdentityPool pool = service.createIdentityPool("us-east-1", Map.of(
                "IdentityPoolName", "RolesPool",
                "AllowUnauthenticatedIdentities", true
        ));
        String foreignRole = "arn:aws:iam::391965393224:role/CognitoAuthRole";
        service.setIdentityPoolRoles(pool.getIdentityPoolId(),
                Map.of("authenticated", foreignRole, "unauthenticated", foreignRole),
                Map.of());

        CognitoIdentityPool observed = service.getIdentityPoolRoles(pool.getIdentityPoolId());
        assertEquals(foreignRole, observed.getRoles().get("authenticated"));
        assertEquals(foreignRole, observed.getRoles().get("unauthenticated"));
    }

    @Test
    void guestGetIdVendsCredentialsAndOpenIdToken() {
        CognitoIdentityPool pool = service.createIdentityPool("us-east-1", Map.of(
                "IdentityPoolName", "GuestPool",
                "AllowUnauthenticatedIdentities", true
        ));
        service.setIdentityPoolRoles(pool.getIdentityPoolId(),
                Map.of("unauthenticated", "arn:aws:iam::000000000000:role/Guest"),
                Map.of());

        CognitoFederatedIdentity identity = service.getId(pool.getIdentityPoolId(), Map.of());
        assertTrue(identity.getIdentityId().startsWith("us-east-1:"));

        Map<String, Object> creds = service.getCredentialsForIdentity(identity.getIdentityId(), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> credentials = (Map<String, Object>) creds.get("Credentials");
        assertNotNull(credentials.get("AccessKeyId"));
        assertNotNull(credentials.get("SessionToken"));
        assertEquals(identity.getIdentityId(), creds.get("IdentityId"));

        Map<String, Object> openId = service.getOpenIdToken(identity.getIdentityId());
        assertTrue(openId.get("Token").toString().contains("."));
        assertEquals(identity.getIdentityId(), service.describeIdentity(identity.getIdentityId()).getIdentityId());
        assertTrue(service.listIdentities(pool.getIdentityPoolId()).stream()
                .anyMatch(item -> identity.getIdentityId().equals(item.getIdentityId())));

        service.deleteIdentities(List.of(identity.getIdentityId()));
        AwsException gone = assertThrows(AwsException.class,
                () -> service.describeIdentity(identity.getIdentityId()));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    @Test
    void guestGetIdRejectedWhenUnauthenticatedDisabled() {
        CognitoIdentityPool pool = service.createIdentityPool("us-east-1", Map.of(
                "IdentityPoolName", "AuthOnly",
                "AllowUnauthenticatedIdentities", false
        ));
        AwsException error = assertThrows(AwsException.class,
                () -> service.getId(pool.getIdentityPoolId(), Map.of()));
        assertEquals("NotAuthorizedException", error.getErrorCode());
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    void getCredentialsRequiresAttachedRole() {
        CognitoIdentityPool pool = service.createIdentityPool("us-east-1", Map.of(
                "IdentityPoolName", "NoRoles",
                "AllowUnauthenticatedIdentities", true
        ));
        CognitoFederatedIdentity identity = service.getId(pool.getIdentityPoolId(), Map.of());
        AwsException error = assertThrows(AwsException.class,
                () -> service.getCredentialsForIdentity(identity.getIdentityId(), Map.of()));
        assertEquals("InvalidIdentityPoolConfigurationException", error.getErrorCode());
    }

    @Test
    void tagAndUntagIdentityPool() {
        CognitoIdentityPool pool = service.createIdentityPool("us-east-1", Map.of(
                "IdentityPoolName", "Tagged",
                "AllowUnauthenticatedIdentities", false,
                "IdentityPoolTags", new HashMap<>(Map.of("Environment", "test"))
        ));
        String arn = service.identityPoolArn(pool.getIdentityPoolId());
        service.tagResource(arn, Map.of("Extra", "1"));
        assertEquals("1", service.listTagsForResource(arn).get("Extra"));
        service.untagResource(arn, List.of("Extra"));
        assertNull(service.listTagsForResource(arn).get("Extra"));
        assertEquals("test", service.listTagsForResource(arn).get("Environment"));
    }
}

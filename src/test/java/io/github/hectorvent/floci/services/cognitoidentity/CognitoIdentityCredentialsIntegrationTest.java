package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CognitoIdentityCredentialsIntegrationTest {

    private static final String EU_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20260101/eu-west-1/cognito-identity/aws4_request";
    private static final String ROLE = "arn:aws:iam::391965393224:role/Guest";

    @Inject
    ObjectMapper objectMapper;

    // The compatibility service has the same simple name as the canonical service.
    @Inject
    io.github.hectorvent.floci.services.cognito.CognitoIdentityService legacyService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void httpPoolAndIdentityOperationsShareTheCompatibilityStore() throws Exception {
        String poolId = action("CreateIdentityPool", Map.of(
                "IdentityPoolName", "credentials regression", "AllowUnauthenticatedIdentities", true,
                "IdentityPoolTags", Map.of("alchemy::id", "Identities")))
                .then().statusCode(200).extract().path("IdentityPoolId");
        try {
            assertEquals("Identities", legacyService.describeIdentityPool(poolId).getIdentityPoolTags().get("alchemy::id"));
            String identityId = action("GetId", Map.of("IdentityPoolId", poolId))
                    .then().statusCode(200)
                    .body("IdentityId", matchesPattern("us-east-1:[0-9a-f-]{36}"))
                    .extract().path("IdentityId");
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId))
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidIdentityPoolConfigurationException"));

            legacyService.setIdentityPoolRoles(poolId, Map.of("unauthenticated", ROLE), Map.of());
            Response credentials = action("GetCredentialsForIdentity", Map.of("IdentityId", identityId));
            credentials.then().statusCode(200)
                    .body("IdentityId", equalTo(identityId))
                    .body("Credentials.AccessKeyId", matchesPattern("ASIA[0-9a-f]{16}"))
                    .body("Credentials.SecretKey", matchesPattern("[0-9a-f]{40}"))
                    .body("Credentials.SessionToken", matchesPattern("[0-9a-f]{64}"));
            long expiration = credentials.jsonPath().getLong("Credentials.Expiration");
            assertTrue(expiration > Instant.now().getEpochSecond());
            assertTrue(expiration <= Instant.now().getEpochSecond() + 3600);
            assertEquals(identityId, legacyService.describeIdentity(identityId).getIdentityId());

            String token = action("GetOpenIdToken", Map.of("IdentityId", identityId))
                    .then().statusCode(200).body("IdentityId", equalTo(identityId)).extract().path("Token");
            String[] tokenParts = token.split("\\.", -1);
            assertEquals(3, tokenParts.length);
            JsonNode claims = objectMapper.readTree(new String(
                    Base64.getUrlDecoder().decode(tokenParts[1]), StandardCharsets.UTF_8));
            assertEquals(identityId, claims.path("sub").asText());
            assertEquals(poolId, claims.path("aud").asText());

            CognitoFederatedIdentity legacyIdentity = legacyService.getId(poolId, Map.of());
            action("DescribeIdentity", Map.of("IdentityId", legacyIdentity.getIdentityId()))
                    .then().statusCode(200)
                    .body("IdentityId", equalTo(legacyIdentity.getIdentityId()))
                    .body("Logins", emptyIterable());
            action("ListIdentities", Map.of("IdentityPoolId", poolId, "MaxResults", 60))
                    .then().statusCode(200).body("IdentityPoolId", equalTo(poolId))
                    .body("Identities.IdentityId", hasItem(identityId))
                    .body("Identities.IdentityId", hasItem(legacyIdentity.getIdentityId()));
            action("DeleteIdentities", Map.of("IdentityIdsToDelete", List.of(legacyIdentity.getIdentityId())))
                    .then().statusCode(200).body("UnprocessedIdentityIds", emptyIterable());
            action("DescribeIdentity", Map.of("IdentityId", legacyIdentity.getIdentityId()))
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

            action("DeleteIdentityPool", Map.of("IdentityPoolId", poolId)).then().statusCode(200);
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId))
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> legacyService.describeIdentityPool(poolId)).getErrorCode());
        } finally {
            action("DeleteIdentityPool", Map.of("IdentityPoolId", poolId));
        }
    }

    @Test
    void emptyRolesDetachAndCanBeReattachedOverJsonProtocol() {
        String poolId = action("CreateIdentityPool", Map.of(
                "IdentityPoolName", "role detach regression", "AllowUnauthenticatedIdentities", true))
                .then().statusCode(200).extract().path("IdentityPoolId");
        try {
            action("SetIdentityPoolRoles", Map.of("IdentityPoolId", poolId,
                    "Roles", Map.of("authenticated", ROLE, "unauthenticated", ROLE),
                    "RoleMappings", Map.of("example.com", Map.of("Type", "Token",
                            "AmbiguousRoleResolution", "AuthenticatedRole"))))
                    .then().statusCode(200);
            String identityId = action("GetId", Map.of("IdentityPoolId", poolId))
                    .then().statusCode(200).extract().path("IdentityId");
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId)).then().statusCode(200);

            for (int attempt = 0; attempt < 2; attempt++) {
                action("SetIdentityPoolRoles", Map.of("IdentityPoolId", poolId, "Roles", Map.of()))
                        .then().statusCode(200);
                action("GetIdentityPoolRoles", Map.of("IdentityPoolId", poolId))
                        .then().statusCode(200)
                        .body("IdentityPoolId", equalTo(poolId))
                        .body("Roles.size()", equalTo(0))
                        .body("RoleMappings.size()", equalTo(0));
            }
            assertTrue(legacyService.getIdentityPoolRoles(poolId).getRoles().isEmpty());
            action("DescribeIdentityPool", Map.of("IdentityPoolId", poolId)).then().statusCode(200);
            action("DescribeIdentity", Map.of("IdentityId", identityId)).then().statusCode(200);
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId))
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidIdentityPoolConfigurationException"));

            legacyService.setIdentityPoolRoles(poolId, Map.of("unauthenticated", ROLE), Map.of());
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId)).then().statusCode(200);
            legacyService.setIdentityPoolRoles(poolId, Map.of(), Map.of());
            action("GetIdentityPoolRoles", Map.of("IdentityPoolId", poolId))
                    .then().statusCode(200).body("Roles.size()", equalTo(0));
        } finally {
            action("DeleteIdentityPool", Map.of("IdentityPoolId", poolId)).then().statusCode(200);
        }
        action("SetIdentityPoolRoles", Map.of("IdentityPoolId", poolId, "Roles", Map.of()))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void missingNullAndMalformedRolesDoNotDetachExistingRoles() {
        String poolId = action("CreateIdentityPool", Map.of(
                "IdentityPoolName", "role validation regression", "AllowUnauthenticatedIdentities", true))
                .then().statusCode(200).extract().path("IdentityPoolId");
        try {
            action("SetIdentityPoolRoles", Map.of("IdentityPoolId", poolId,
                    "Roles", Map.of("unauthenticated", ROLE))).then().statusCode(200);
            action("SetIdentityPoolRoles", Map.of("IdentityPoolId", poolId))
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
            Map<String, String> invalidRoles = Map.of(
                    "null", "InvalidParameterException",
                    "[]", "SerializationException",
                    "\"invalid\"", "SerializationException",
                    "{\"unauthenticated\":123}", "SerializationException",
                    "{\"unauthenticated\":null}", "InvalidParameterException",
                    "{\"unauthenticated\":\"\"}", "InvalidParameterException");
            for (Map.Entry<String, String> invalid : invalidRoles.entrySet()) {
                String roles = invalid.getKey();
                given().header("X-Amz-Target", "AWSCognitoIdentityService.SetIdentityPoolRoles")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"IdentityPoolId\":\"" + poolId + "\",\"Roles\":" + roles + "}")
                        .post("/").then().statusCode(400)
                        .body("__type", equalTo(invalid.getValue()));
            }
            action("GetIdentityPoolRoles", Map.of("IdentityPoolId", poolId))
                    .then().statusCode(200).body("Roles.unauthenticated", equalTo(ROLE));
        } finally {
            action("DeleteIdentityPool", Map.of("IdentityPoolId", poolId)).then().statusCode(200);
        }
    }

    @Test
    void authenticatedCredentialsAndPrincipalTagsSurvivePoolUpdate() {
        String provider = "cognito-idp.us-east-1.amazonaws.com/us-east-1_example";
        String poolId = action("CreateIdentityPool", Map.of(
                "IdentityPoolName", "authenticated regression", "AllowUnauthenticatedIdentities", false,
                "IdentityPoolTags", Map.of("team", "identity")))
                .then().statusCode(200).extract().path("IdentityPoolId");
        try {
            action("GetId", Map.of("IdentityPoolId", poolId)).then().statusCode(403)
                    .body("__type", equalTo("NotAuthorizedException"));
            action("SetIdentityPoolRoles", Map.of("IdentityPoolId", poolId,
                    "Roles", Map.of("authenticated", ROLE))).then().statusCode(200);
            action("SetPrincipalTagAttributeMap", Map.of("IdentityPoolId", poolId,
                    "IdentityProviderName", provider, "PrincipalTags", Map.of("team", "custom:team")))
                    .then().statusCode(200);
            String identityId = action("GetId", Map.of("IdentityPoolId", poolId,
                    "Logins", Map.of(provider, "local-test-token")))
                    .then().statusCode(200).extract().path("IdentityId");
            action("UpdateIdentityPool", Map.of("IdentityPoolId", poolId,
                    "IdentityPoolName", "authenticated updated", "AllowUnauthenticatedIdentities", false))
                    .then().statusCode(200).body("IdentityPoolTags.team", equalTo("identity"));
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId)).then().statusCode(200)
                    .body("IdentityId", equalTo(identityId));
            action("GetPrincipalTagAttributeMap", Map.of("IdentityPoolId", poolId, "IdentityProviderName", provider))
                    .then().statusCode(200).body("PrincipalTags.team", equalTo("custom:team"));
            action("DescribeIdentity", Map.of("IdentityId", identityId)).then().statusCode(200)
                    .body("Logins", hasItem(provider));
        } finally {
            action("DeleteIdentityPool", Map.of("IdentityPoolId", poolId)).then().statusCode(200);
        }
    }

    @Test
    void identityCredentialsAndDeletionRespectThePoolRegion() {
        String poolId = action("CreateIdentityPool", Map.of("IdentityPoolName", "eu credentials",
                "AllowUnauthenticatedIdentities", true), EU_AUTHORIZATION)
                .then().statusCode(200).extract().path("IdentityPoolId");
        try {
            action("SetIdentityPoolRoles", Map.of("IdentityPoolId", poolId,
                    "Roles", Map.of("unauthenticated", ROLE)), EU_AUTHORIZATION).then().statusCode(200);
            action("GetId", Map.of("IdentityPoolId", poolId)).then().statusCode(404);
            String identityId = action("GetId", Map.of("IdentityPoolId", poolId), EU_AUTHORIZATION)
                    .then().statusCode(200).extract().path("IdentityId");
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId)).then().statusCode(404);
            action("GetOpenIdToken", Map.of("IdentityId", identityId)).then().statusCode(404);
            action("DeleteIdentities", Map.of("IdentityIdsToDelete", List.of(identityId)))
                    .then().statusCode(200).body("UnprocessedIdentityIds[0].IdentityId", equalTo(identityId));
            action("GetCredentialsForIdentity", Map.of("IdentityId", identityId), EU_AUTHORIZATION)
                    .then().statusCode(200).body("IdentityId", equalTo(identityId));
        } finally {
            action("DeleteIdentityPool", Map.of("IdentityPoolId", poolId), EU_AUTHORIZATION)
                    .then().statusCode(200);
        }
    }

    private Response action(String name, Map<String, Object> request) {
        return action(name, request,
                "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/cognito-identity/aws4_request");
    }

    private Response action(String name, Map<String, Object> request, String authorization) {
        return given().header("X-Amz-Target", "AWSCognitoIdentityService." + name)
                .header("Authorization", authorization)
                .contentType("application/x-amz-json-1.1").body(request).post("/");
    }
}

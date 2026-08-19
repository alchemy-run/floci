package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CognitoAlchemyParityIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void identityProviderDomainAndRiskConfigurationRoundTrip() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pool = cognitoJson("CreateUserPool", """
                { "PoolName": "AlchemyParity-%s" }
                """.formatted(suffix));
        String poolId = pool.path("UserPool").path("Id").asText();

        JsonNode createdIdp = cognitoJson("CreateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "corporate-oidc",
                  "ProviderType": "OIDC",
                  "ProviderDetails": {
                    "client_id": "alchemy-test-client",
                    "client_secret": "alchemy-test-secret",
                    "authorize_scopes": "openid email",
                    "oidc_issuer": "https://accounts.google.com",
                    "attributes_request_method": "GET"
                  },
                  "AttributeMapping": { "email": "email" }
                }
                """.formatted(poolId));
        assertEquals("OIDC", createdIdp.path("IdentityProvider").path("ProviderType").asText());

        JsonNode describedIdp = cognitoJson("DescribeIdentityProvider", """
                { "UserPoolId": "%s", "ProviderName": "corporate-oidc" }
                """.formatted(poolId));
        assertEquals("https://accounts.google.com",
                describedIdp.path("IdentityProvider").path("ProviderDetails").path("oidc_issuer").asText());

        JsonNode updatedIdp = cognitoJson("UpdateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "corporate-oidc",
                  "AttributeMapping": { "email": "email", "username": "sub" }
                }
                """.formatted(poolId));
        assertEquals("sub", updatedIdp.path("IdentityProvider").path("AttributeMapping").path("username").asText());

        String domain = "alchemy-parity-" + suffix;
        JsonNode createdDomain = cognitoJson("CreateUserPoolDomain", """
                { "UserPoolId": "%s", "Domain": "%s", "ManagedLoginVersion": 2 }
                """.formatted(poolId, domain));
        assertTrue(createdDomain.path("CloudFrontDomain").asText().endsWith(".cloudfront.net"));

        JsonNode describedDomain = cognitoJson("DescribeUserPoolDomain", """
                { "Domain": "%s" }
                """.formatted(domain));
        assertEquals(poolId, describedDomain.path("DomainDescription").path("UserPoolId").asText());
        assertEquals("ACTIVE", describedDomain.path("DomainDescription").path("Status").asText());

        JsonNode missingDomain = cognitoJson("DescribeUserPoolDomain", """
                { "Domain": "missing-%s" }
                """.formatted(suffix));
        assertTrue(missingDomain.path("DomainDescription").path("UserPoolId").isMissingNode());

        JsonNode setRisk = cognitoJson("SetRiskConfiguration", """
                {
                  "UserPoolId": "%s",
                  "CompromisedCredentialsRiskConfiguration": {
                    "Actions": { "EventAction": "BLOCK" }
                  }
                }
                """.formatted(poolId));
        assertEquals("BLOCK", setRisk.path("RiskConfiguration")
                .path("CompromisedCredentialsRiskConfiguration").path("Actions").path("EventAction").asText());

        JsonNode describedRisk = cognitoJson("DescribeRiskConfiguration", """
                { "UserPoolId": "%s" }
                """.formatted(poolId));
        assertEquals("BLOCK", describedRisk.path("RiskConfiguration")
                .path("CompromisedCredentialsRiskConfiguration").path("Actions").path("EventAction").asText());

        cognitoAction("DeleteIdentityProvider", """
                { "UserPoolId": "%s", "ProviderName": "corporate-oidc" }
                """.formatted(poolId)).then().statusCode(200);
        cognitoAction("DeleteUserPoolDomain", """
                { "UserPoolId": "%s", "Domain": "%s" }
                """.formatted(poolId, domain)).then().statusCode(200);
        cognitoAction("DeleteUserPool", """
                { "UserPoolId": "%s" }
                """.formatted(poolId)).then().statusCode(200);
    }

    @Test
    void identityPoolGuestCredentialsAndAdminRoundTrip() throws Exception {
        JsonNode created = identityJson("CreateIdentityPool", """
                {
                  "IdentityPoolName": "AlchemyIdentity-%s",
                  "AllowUnauthenticatedIdentities": true,
                  "IdentityPoolTags": { "Environment": "test", "alchemy::id": "Identities" }
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8)));
        String poolId = created.path("IdentityPoolId").asText();
        assertTrue(poolId.contains(":"));
        assertEquals("test", created.path("IdentityPoolTags").path("Environment").asText());

        identityAction("SetIdentityPoolRoles", """
                {
                  "IdentityPoolId": "%s",
                  "Roles": {
                    "unauthenticated": "arn:aws:iam::391965393224:role/Guest",
                    "authenticated": "arn:aws:iam::000000000000:role/Auth"
                  }
                }
                """.formatted(poolId)).then().statusCode(200);

        JsonNode roles = identityJson("GetIdentityPoolRoles", """
                { "IdentityPoolId": "%s" }
                """.formatted(poolId));
        assertEquals("arn:aws:iam::391965393224:role/Guest",
                roles.path("Roles").path("unauthenticated").asText());

        JsonNode got = identityJson("GetId", """
                { "IdentityPoolId": "%s" }
                """.formatted(poolId));
        String identityId = got.path("IdentityId").asText();
        assertTrue(identityId.contains(":"));

        JsonNode creds = identityJson("GetCredentialsForIdentity", """
                { "IdentityId": "%s" }
                """.formatted(identityId));
        assertFalse(creds.path("Credentials").path("AccessKeyId").asText().isBlank());
        assertFalse(creds.path("Credentials").path("SessionToken").asText().isBlank());

        JsonNode openId = identityJson("GetOpenIdToken", """
                { "IdentityId": "%s" }
                """.formatted(identityId));
        assertTrue(openId.path("Token").asText().contains("."));

        JsonNode described = identityJson("DescribeIdentity", """
                { "IdentityId": "%s" }
                """.formatted(identityId));
        assertEquals(identityId, described.path("IdentityId").asText());

        JsonNode listed = identityJson("ListIdentities", """
                { "IdentityPoolId": "%s", "MaxResults": 60 }
                """.formatted(poolId));
        assertEquals(identityId, listed.path("Identities").get(0).path("IdentityId").asText());

        identityAction("DeleteIdentities", """
                { "IdentityIdsToDelete": ["%s"] }
                """.formatted(identityId)).then().statusCode(200);
        identityAction("DeleteIdentityPool", """
                { "IdentityPoolId": "%s" }
                """.formatted(poolId)).then().statusCode(200);
    }

    private static JsonNode identityJson(String action, String body) throws Exception {
        return RestAssuredJsonUtils.awsActionJson("AWSCognitoIdentityService", action, body);
    }

    private static io.restassured.response.Response identityAction(String action, String body) {
        return RestAssuredJsonUtils.awsAction("AWSCognitoIdentityService", action, body);
    }
}

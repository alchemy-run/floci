package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A JWT authorizer whose issuer is a Cognito pool Floci hosts verifies the pool's tokens. The issuer is the pool's
 * {@code cognito-idp.<region>.amazonaws.com} URL, as on AWS, and its key is Floci's, not the internet's.
 */
@QuarkusTest
class JwtHostedCognitoIssuerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    JwtSignatureVerifier verifier;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aHostedPoolsTokensVerifyAgainstItsAwsIssuer() throws Exception {
        String poolId = cognitoJson("CreateUserPool", MAPPER.writeValueAsString(Map.of("PoolName", "jwt-hosted")))
                .path("UserPool").path("Id").asText();
        String other = cognitoJson("CreateUserPool", MAPPER.writeValueAsString(Map.of("PoolName", "jwt-other")))
                .path("UserPool").path("Id").asText();
        String clientId = cognitoJson("CreateUserPoolClient", MAPPER.writeValueAsString(Map.of(
                "UserPoolId", poolId, "ClientName", "app", "ExplicitAuthFlows",
                List.of("ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"))))
                .path("UserPoolClient").path("ClientId").asText();
        cognitoAction("AdminCreateUser", MAPPER.writeValueAsString(Map.of(
                "UserPoolId", poolId, "Username", "alice", "MessageAction", "SUPPRESS"))).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", MAPPER.writeValueAsString(Map.of(
                "UserPoolId", poolId, "Username", "alice", "Password", "Perm1234!", "Permanent", true)))
                .then().statusCode(200);
        String idToken = cognitoJson("AdminInitiateAuth", MAPPER.writeValueAsString(Map.of(
                "UserPoolId", poolId, "ClientId", clientId, "AuthFlow", "ADMIN_USER_PASSWORD_AUTH",
                "AuthParameters", Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"))))
                .path("AuthenticationResult").path("IdToken").asText();

        String region = poolId.substring(0, poolId.indexOf('_'));
        assertDoesNotThrow(() -> verifier.verify(idToken,
                "https://cognito-idp." + region + ".amazonaws.com/" + poolId));
        // Another hosted pool's key doesn't sign this pool's tokens.
        assertThrows(JwtSignatureVerifier.JwtVerificationException.class, () -> verifier.verify(idToken,
                "https://cognito-idp." + region + ".amazonaws.com/" + other));
    }
}

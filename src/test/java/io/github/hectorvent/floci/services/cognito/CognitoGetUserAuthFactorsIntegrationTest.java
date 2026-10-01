package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * GetUserAuthFactors over the JSON 1.1 wire protocol. The pools use email as a username
 * attribute, so each user's username is its generated sub, which is what AWS returns as
 * {@code Username} for such a pool.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoGetUserAuthFactorsIntegrationTest {

    private static final String PASSWORD = "Factors1234!";

    private static String passwordOnlyPoolId;
    private static String passwordOnlyClientId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setUpPoolsAndClients() throws Exception {
        passwordOnlyPoolId = createPool("AuthFactorsPasswordOnlyPool", "[\"PASSWORD\"]");
        passwordOnlyClientId = createClient(passwordOnlyPoolId);
    }

    @Test
    @Order(2)
    void passwordAndVerifiedEmailAreReportedEvenWhenThePoolAllowsOnlyPassword() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" },
                { "Name": "email_verified", "Value": "true" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);
        String username = cognitoJson("GetUser", accessTokenBody(accessToken)).path("Username").asText();

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("Username", equalTo(username))
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "EMAIL_OTP"))
                .body("$", not(hasKey("PreferredMfaSetting")))
                .body("$", not(hasKey("UserMFASettingList")));
        assertNotEquals(email, username, "an email-username pool keys the user by its sub");
    }

    @Test
    @Order(3)
    void unverifiedEmailIsNotAFactor() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD"));
    }

    @Test
    @Order(4)
    void verifiedPhoneNumberAddsSmsOtpAfterEmailOtp() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" },
                { "Name": "email_verified", "Value": "true" },
                { "Name": "phone_number", "Value": "+15555550123" },
                { "Name": "phone_number_verified", "Value": "true" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "EMAIL_OTP", "SMS_OTP"));
    }

    @Test
    @Order(6)
    void emailMfaPreferenceIsReportedAsMfaSettings() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" },
                { "Name": "email_verified", "Value": "true" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);

        cognitoAction("SetUserMFAPreference", """
                { "AccessToken": "%s", "EmailMfaSettings": { "Enabled": true } }
                """.formatted(accessToken))
                .then()
                .statusCode(200);
        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("UserMFASettingList", contains("EMAIL_OTP"))
                .body("$", not(hasKey("PreferredMfaSetting")));

        cognitoAction("SetUserMFAPreference", """
                { "AccessToken": "%s", "EmailMfaSettings": { "Enabled": true, "PreferredMfa": true } }
                """.formatted(accessToken))
                .then()
                .statusCode(200);
        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "EMAIL_OTP"))
                .body("PreferredMfaSetting", equalTo("EMAIL_OTP"))
                .body("UserMFASettingList", contains("EMAIL_OTP"));
    }

    @Test
    @Order(7)
    void invalidAccessTokenIsRejected() {
        cognitoAction("GetUserAuthFactors", accessTokenBody("not-a-valid-token"))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Invalid Access Token"));
    }

    @Test
    @Order(8)
    void globallySignedOutTokenIsRejectedLikeGetUser() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);
        cognitoAction("GlobalSignOut", accessTokenBody(accessToken)).then().statusCode(200);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Access Token has been revoked"));
        cognitoAction("GetUser", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Access Token has been revoked"));
    }

    @Test
    @Order(9)
    void deletedUserTokenIsRejectedLikeGetUser() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);
        cognitoAction("AdminDeleteUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(passwordOnlyPoolId, email))
                .then()
                .statusCode(200);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UserNotFoundException"));
        cognitoAction("GetUser", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UserNotFoundException"));
    }

    private static String createPool(String name, String allowedFirstAuthFactors) throws Exception {
        JsonNode response = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "%s",
                  "UsernameAttributes": ["email"],
                  "UserPoolTier": "ESSENTIALS",
                  "Policies": { "SignInPolicy": { "AllowedFirstAuthFactors": %s } }
                }
                """.formatted(name, allowedFirstAuthFactors));
        return response.path("UserPool").path("Id").asText();
    }

    private static String createClient(String poolId) throws Exception {
        JsonNode response = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "auth-factors-client",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_USER_AUTH"]
                }
                """.formatted(poolId));
        return response.path("UserPoolClient").path("ClientId").asText();
    }

    private static void createUserWithPassword(String poolId, String email, String attributes) {
        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "MessageAction": "SUPPRESS",
                  "UserAttributes": [ %s ]
                }
                """.formatted(poolId, email, attributes))
                .then()
                .statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, email, PASSWORD))
                .then()
                .statusCode(200);
    }

    private static String signInWithPassword(String clientId, String email) throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, email, PASSWORD));
        return auth.path("AuthenticationResult").path("AccessToken").asText();
    }

    private static String accessTokenBody(String accessToken) {
        return """
                { "AccessToken": "%s" }
                """.formatted(accessToken);
    }

    private static String uniqueEmail() {
        return "factors+" + UUID.randomUUID() + "@example.com";
    }
}

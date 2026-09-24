package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.OidcIssuerKeyLookup;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StsQueryHandlerTest {

    private static final String REGION = "us-east-1";
    private static final Pattern SECRET_ACCESS_KEY =
            Pattern.compile("<SecretAccessKey>([^<]+)</SecretAccessKey>");
    private static final Pattern SESSION_TOKEN =
            Pattern.compile("<SessionToken>([^<]+)</SessionToken>");

    private static StsQueryHandler newHandler() {
        return new StsQueryHandler(
                mock(IamService.class),
                mock(AccountResolver.class),
                new RegionResolver(REGION, "000000000000"),
                mock(EmulatorConfig.class),
                mock(AssumeRolePolicyEvaluator.class),
                mock(WebIdentityTrustPolicyEvaluator.class),
                mock(WebIdentityTokenVerifier.class),
                mock(OidcIssuerKeyLookup.class),
                mock(SAMLProviderService.class),
                mock(SAMLTrustPolicyEvaluator.class));
    }

    @Test
    void getSessionTokenIssuesFortyCharSecretAndTwoHundredCharToken() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        Response response = newHandler().handle("GetSessionToken", params);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertEquals(40, extract(SECRET_ACCESS_KEY, body).length());
        assertEquals(200, extract(SESSION_TOKEN, body).length());
    }

    @Test
    void getSessionTokensAreUniqueAcrossCalls() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        String firstBody = (String) newHandler().handle("GetSessionToken", params).getEntity();
        String secondBody = (String) newHandler().handle("GetSessionToken", params).getEntity();

        assertNotEquals(extract(SECRET_ACCESS_KEY, firstBody), extract(SECRET_ACCESS_KEY, secondBody));
        assertNotEquals(extract(SESSION_TOKEN, firstBody), extract(SESSION_TOKEN, secondBody));
    }

    private static String extract(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        assertTrue(matcher.find(), "expected " + pattern + " in " + body);
        return matcher.group(1);
    }
}

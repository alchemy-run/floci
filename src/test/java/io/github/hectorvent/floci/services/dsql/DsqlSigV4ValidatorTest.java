package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.services.dsql.proxy.DsqlSigV4Validator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DsqlSigV4ValidatorTest {

    private static final String HOST = "ac0a36b1677f42a68a3b84dde8.dsql.us-east-1.on.aws";

    @Test
    void rejectsMalformedTokens() {
        DsqlSigV4Validator validator = new DsqlSigV4Validator(mock(IamService.class));
        assertFalse(validator.validate("not-a-token", "admin"));
        assertFalse(validator.validate(
                "abc.dsql.us-east-1.on.aws/?Action=connect&X-Amz-Signature=deadbeef",
                "admin"));
    }

    @Test
    void acceptsHostOnlyDbConnectAdminToken() throws Exception {
        IamService iam = IamServiceTestHelper.iamServiceWithAccessKey("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
        DsqlSigV4Validator validator = new DsqlSigV4Validator(iam);

        String token = SigV4TokenTestHelper.createDsqlToken(
                HOST,
                "DbConnectAdmin",
                "AKIDEXAMPLE",
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                Instant.now(),
                900);
        assertTrue(validator.validate(token, "admin"));
    }

    @Test
    void acceptsAssumedRoleTokenWithSecurityToken() throws Exception {
        // Lambda execution-role sessions mint ASIA… keys plus a session token that
        // aws4fetch folds into X-Amz-Security-Token (and the token charset includes + /).
        String accessKey = "ASIAEXAMPLEKEY0001";
        String secret = "session-secret-value";
        String sessionToken = "FwoGZXIvYXdzEJr//////////wEaDN+example/session+token";
        IamService iam = IamServiceTestHelper.iamServiceWithSessionCredential(accessKey, secret);
        DsqlSigV4Validator validator = new DsqlSigV4Validator(iam);

        String token = SigV4TokenTestHelper.createDsqlToken(
                HOST,
                "DbConnectAdmin",
                accessKey,
                secret,
                Instant.now(),
                900,
                sessionToken);
        assertTrue(token.contains("X-Amz-Security-Token="), "presigned DSQL tokens must carry the session token");
        assertTrue(validator.validate(token, "admin"));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        IamService iam = IamServiceTestHelper.iamServiceWithAccessKey("AKIDEXAMPLE", "secret");
        DsqlSigV4Validator validator = new DsqlSigV4Validator(iam);

        String token = SigV4TokenTestHelper.createDsqlToken(
                HOST,
                "DbConnectAdmin",
                "AKIDEXAMPLE",
                "secret",
                Instant.now().minusSeconds(1200),
                900);
        assertFalse(validator.validate(token, "admin"));
    }
}

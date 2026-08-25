package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.services.dsql.proxy.DsqlSigV4Validator;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class DsqlSigV4ValidatorTest {

    @Test
    void rejectsMalformedTokens() {
        DsqlSigV4Validator validator = new DsqlSigV4Validator(mock(IamService.class));
        assertFalse(validator.validate("not-a-token", "admin"));
        assertFalse(validator.validate(
                "abc.dsql.us-east-1.on.aws/?Action=connect&X-Amz-Signature=deadbeef",
                "admin"));
    }
}

package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsApiGatewayIamIntegrationTest extends StepFunctionsApiGatewayIntegrationTest {
}

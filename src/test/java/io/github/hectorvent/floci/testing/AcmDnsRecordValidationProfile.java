package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Issues a DNS-validated ACM certificate only once its validation CNAME is published in Route 53,
 * the shipped default, which the shared test configuration turns off.
 */
public class AcmDnsRecordValidationProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.acm.dns-validation-requires-record", "true");
    }
}

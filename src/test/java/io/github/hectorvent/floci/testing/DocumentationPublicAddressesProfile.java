package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Gives mock EC2 instances due an automatic public address one from 203.0.113.0/24. */
public class DocumentationPublicAddressesProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.ec2.mock-public-addresses", "documentation");
    }
}

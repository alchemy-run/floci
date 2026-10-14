package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IotEndpointsTest {

    private static EmulatorConfig config(Optional<String> endpointAddress) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        when(config.services().iot().endpointAddress()).thenReturn(endpointAddress);
        when(config.iotEndpointAddress()).thenCallRealMethod();
        return config;
    }

    @Test
    void everyEndpointTypeHasItsAwsHostnameShape() {
        String prefix = IotEndpoints.accountPrefix("123456789012");
        EmulatorConfig config = config(Optional.empty());

        assertEquals(prefix + "-ats.iot.eu-west-1.amazonaws.com",
                IotEndpoints.address(config, "iot:Data-ATS", "123456789012", "eu-west-1"));
        assertEquals(prefix + ".iot.eu-west-1.amazonaws.com",
                IotEndpoints.address(config, "iot:Data", "123456789012", "eu-west-1"));
        assertEquals(prefix + ".credentials.iot.eu-west-1.amazonaws.com",
                IotEndpoints.address(config, "iot:CredentialProvider", "123456789012", "eu-west-1"));
        assertEquals(prefix + ".jobs.iot.eu-west-1.amazonaws.com",
                IotEndpoints.address(config, "iot:Jobs", "123456789012", "eu-west-1"));
        assertEquals(prefix + "-ats.iot.cn-north-1.amazonaws.com.cn",
                IotEndpoints.address(config, "iot:Data-ATS", "123456789012", "cn-north-1"));
    }

    @Test
    void prefixIsStablePerAccountAndDiffersAcrossAccounts() {
        String prefix = IotEndpoints.accountPrefix("000000000000");
        assertTrue(prefix.matches("[a-z0-9]{14}"), prefix);
        assertEquals(prefix, IotEndpoints.accountPrefix("000000000000"));
        assertNotEquals(prefix, IotEndpoints.accountPrefix("111111111111"));
    }

    @Test
    void configuredAddressReplacesEveryEndpointTypeVerbatim() {
        EmulatorConfig config = config(Optional.of(" iot.example.localhost.floci.io:8443 "));
        for (String type : IotEndpoints.TYPES) {
            assertEquals("iot.example.localhost.floci.io:8443",
                    IotEndpoints.address(config, type, "000000000000", "us-east-1"), type);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t\n"})
    void blankAddressCountsAsUnset(String blank) {
        assertEquals(IotEndpoints.awsAddress("iot:Data-ATS", "000000000000", "us-east-1"),
                IotEndpoints.address(config(Optional.of(blank)), "iot:Data-ATS", "000000000000", "us-east-1"));
    }

    @Test
    void unknownEndpointTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> IotEndpoints.awsAddress("iot:Nonsense", "000000000000", "us-east-1"));
    }
}

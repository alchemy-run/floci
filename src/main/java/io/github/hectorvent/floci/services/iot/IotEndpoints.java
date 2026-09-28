package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The account-specific hostnames IoT Core's DescribeEndpoint returns, shaped like AWS's:
 * {@code <prefix>-ats.iot.<region>.amazonaws.com} for {@code iot:Data-ATS},
 * {@code <prefix>.iot.<region>.amazonaws.com} for the legacy {@code iot:Data},
 * {@code <prefix>.credentials.iot.<region>.amazonaws.com} for {@code iot:CredentialProvider} and
 * {@code <prefix>.jobs.iot.<region>.amazonaws.com} for {@code iot:Jobs}. The 14-character prefix
 * is derived from the account ID, so it is stable per account as on AWS. Floci's embedded DNS
 * resolves these names to Floci for the containers it runs.
 *
 * <p>{@code floci.services.iot.endpoint-address}, when set, replaces every one of them verbatim.
 */
final class IotEndpoints {

    static final String DATA_ATS = "iot:Data-ATS";
    static final List<String> TYPES = List.of(DATA_ATS, "iot:Data", "iot:CredentialProvider", "iot:Jobs");

    private static final int PREFIX_LENGTH = 14;
    private static final String PREFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private IotEndpoints() {
    }

    static String address(EmulatorConfig config, String endpointType, String accountId, String region) {
        boolean overridden = config.services().iot().endpointAddress()
                .filter(address -> !address.isBlank())
                .isPresent();
        if (overridden) {
            return config.iotEndpointAddress();
        }
        return awsAddress(endpointType, accountId, region);
    }

    static String awsAddress(String endpointType, String accountId, String region) {
        String prefix = accountPrefix(accountId);
        String domain = "iot." + region + (region.startsWith("cn-") ? ".amazonaws.com.cn" : ".amazonaws.com");
        return switch (endpointType) {
            case DATA_ATS -> prefix + "-ats." + domain;
            case "iot:Data" -> prefix + "." + domain;
            case "iot:CredentialProvider" -> prefix + ".credentials." + domain;
            case "iot:Jobs" -> prefix + ".jobs." + domain;
            default -> throw new IllegalArgumentException("Unsupported endpoint type: " + endpointType);
        };
    }

    static String accountPrefix(String accountId) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(("iot-endpoint:" + accountId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
        StringBuilder prefix = new StringBuilder(PREFIX_LENGTH);
        for (int i = 0; i < PREFIX_LENGTH; i++) {
            prefix.append(PREFIX_ALPHABET.charAt((digest[i] & 0xFF) % PREFIX_ALPHABET.length()));
        }
        return prefix.toString();
    }
}

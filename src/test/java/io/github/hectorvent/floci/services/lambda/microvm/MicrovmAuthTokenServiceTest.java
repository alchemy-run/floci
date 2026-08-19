package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrovmAuthTokenServiceTest {

    private final MicrovmAuthTokenService service = new MicrovmAuthTokenService();

    @SuppressWarnings("unchecked")
    private String mint(String microvmId, Number minutes, List<Map<String, Object>> ports) {
        Map<String, Object> response = service.createToken(microvmId, minutes, ports);
        Map<String, String> authToken = (Map<String, String>) response.get("authToken");
        assertNotNull(authToken);
        String token = authToken.get(MicrovmAuthTokenService.HEADER);
        assertNotNull(token);
        return token;
    }

    @Test
    void mintedTokenValidatesForItsMicrovmAndPort() {
        String token = mint("mvm-abc", 5, List.of(Map.of("port", 8080)));
        assertDoesNotThrow(() -> service.validate(token, "mvm-abc", 8080));
    }

    @Test
    void tokenIsRejectedForAnotherMicrovm() {
        String token = mint("mvm-abc", 5, List.of(Map.of("port", 8080)));
        AwsException e = assertThrows(AwsException.class,
                () -> service.validate(token, "mvm-other", 8080));
        assertEquals(403, e.getHttpStatus());
    }

    @Test
    void tokenIsRejectedForDisallowedPort() {
        String token = mint("mvm-abc", 5, List.of(Map.of("port", 8080)));
        assertThrows(AwsException.class, () -> service.validate(token, "mvm-abc", 9000));
    }

    @Test
    void portRangeAndAllPortsSpecificationsWork() {
        String ranged = mint("mvm-abc", 5,
                List.of(Map.of("range", Map.of("startPort", 8000, "endPort", 8100))));
        assertDoesNotThrow(() -> service.validate(ranged, "mvm-abc", 8050));
        assertThrows(AwsException.class, () -> service.validate(ranged, "mvm-abc", 8101));

        String all = mint("mvm-abc", 5, List.of(Map.of("allPorts", Map.of())));
        assertDoesNotThrow(() -> service.validate(all, "mvm-abc", 12345));
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = mint("mvm-abc", 5, List.of(Map.of("port", 8080)));
        String tampered = token.substring(0, token.length() - 2);
        assertThrows(AwsException.class, () -> service.validate(tampered, "mvm-abc", 8080));
    }

    @Test
    void missingTokenIsRejected() {
        assertThrows(AwsException.class, () -> service.validate(null, "mvm-abc", 8080));
        assertThrows(AwsException.class, () -> service.validate("", "mvm-abc", 8080));
    }

    @Test
    void expirationMustBePositiveAndPortsRequired() {
        assertThrows(AwsException.class,
                () -> service.createToken("mvm-abc", 0, List.of(Map.of("port", 8080))));
        assertThrows(AwsException.class,
                () -> service.createToken("mvm-abc", null, List.of(Map.of("port", 8080))));
        AwsException e = assertThrows(AwsException.class,
                () -> service.createToken("mvm-abc", 5, List.of()));
        assertTrue(e.getMessage().contains("allowedPorts"));
    }

    @Test
    void tokensFromAnotherServiceInstanceAreRejected() {
        String token = mint("mvm-abc", 5, List.of(Map.of("port", 8080)));
        MicrovmAuthTokenService other = new MicrovmAuthTokenService();
        assertThrows(AwsException.class, () -> other.validate(token, "mvm-abc", 8080));
    }
}

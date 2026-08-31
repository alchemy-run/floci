package io.github.hectorvent.floci.services.route53domains;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53DomainsServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Route53DomainsService service;

    @BeforeEach
    void setUp() {
        service = new Route53DomainsService(mapper);
    }

    @Test
    void checkDomainAvailability_unregisteredName_returnsAvailable() {
        ObjectNode request = mapper.createObjectNode();
        request.put("DomainName", "alchemy-effect-r53d-probe-a32892.com");
        assertEquals("AVAILABLE", service.checkDomainAvailability(request).path("Availability").asText());
    }

    @Test
    void getDomainDetail_unknownDomain_invalidInputNotInAccount() {
        ObjectNode request = mapper.createObjectNode();
        request.put("DomainName", "example.com");
        AwsException error = assertThrows(AwsException.class, () -> service.getDomainDetail(request));
        assertEquals("InvalidInput", error.getErrorCode());
        assertTrue(error.getMessage().contains("not found in account"));
    }

    @Test
    void listPrices_com_returnsRegistrationPrice() {
        ObjectNode request = mapper.createObjectNode();
        request.put("Tld", "com");
        ObjectNode response = service.listPrices(request);
        assertEquals(1, response.path("Prices").size());
        assertTrue(response.path("Prices").get(0).path("RegistrationPrice").path("Price").asDouble() > 0);
    }

    @Test
    void registerDomain_unsupportedTld_throwsUnsupportedTLD() {
        ObjectNode request = mapper.createObjectNode();
        request.put("DomainName", "alchemy-effect-r53d-probe-a32892.invalidtld99");
        request.put("DurationInYears", 1);
        AwsException error = assertThrows(AwsException.class, () -> service.registerDomain(request));
        assertEquals("UnsupportedTLD", error.getErrorCode());
    }

    @Test
    void getOperationDetail_unknownId_invalidInput() {
        ObjectNode request = mapper.createObjectNode();
        request.put("OperationId", "00000000-0000-0000-0000-000000000000");
        AwsException error = assertThrows(AwsException.class, () -> service.getOperationDetail(request));
        assertEquals("InvalidInput", error.getErrorCode());
    }
}

package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceQuotasServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ServiceQuotasService service = new ServiceQuotasService(new ObjectMapper());

    @Test
    void everyListedQuotaResolvesThroughGetServiceQuota() {
        for (ServiceQuotasCatalog.ServiceDefinition definition : ServiceQuotasCatalog.services()) {
            for (ServiceQuotasCatalog.QuotaDefinition quota : service.quotasFor(definition.serviceCode())) {
                ObjectNode response = service.getServiceQuota(
                        definition.serviceCode(), quota.quotaCode(), REGION, ACCOUNT);
                assertEquals(quota.quotaCode(), response.path("Quota").path("QuotaCode").asText());
                assertEquals(quota.defaultValue(), response.path("Quota").path("Value").asDouble());
            }
        }
    }

    @Test
    void vpcDefaultQuotasMatchAws() {
        JsonNode vpcs = service.getAwsDefaultServiceQuota("vpc", "L-F678F1CE", REGION).path("Quota");
        assertEquals("VPCs per Region", vpcs.path("QuotaName").asText());
        assertEquals(5.0, vpcs.path("Value").asDouble());
        assertEquals("None", vpcs.path("Unit").asText());
        assertTrue(vpcs.path("Adjustable").asBoolean());
        assertFalse(vpcs.path("GlobalQuota").asBoolean());
        assertEquals("Amazon Virtual Private Cloud (Amazon VPC)", vpcs.path("ServiceName").asText());

        JsonNode subnets = service.getAwsDefaultServiceQuota("vpc", "L-407747CB", REGION).path("Quota");
        assertEquals("Subnets per VPC", subnets.path("QuotaName").asText());
        assertEquals(200.0, subnets.path("Value").asDouble());
    }

    @Test
    void defaultQuotaArnOmitsAccountWhileAppliedArnIncludesIt() {
        assertEquals("arn:aws:servicequotas:us-east-1::vpc/L-F678F1CE",
                service.getAwsDefaultServiceQuota("vpc", "L-F678F1CE", REGION).path("Quota").path("QuotaArn").asText());
        assertEquals("arn:aws:servicequotas:us-east-1:000000000000:vpc/L-F678F1CE",
                service.getServiceQuota("vpc", "L-F678F1CE", REGION, ACCOUNT).path("Quota").path("QuotaArn").asText());
    }

    @Test
    void lambdaConcurrentExecutionsUsesAwsDefault() {
        JsonNode quota = service.getServiceQuota("lambda", "L-B99A9384", REGION, ACCOUNT).path("Quota");
        assertEquals("Concurrent executions", quota.path("QuotaName").asText());
        assertEquals(1000.0, quota.path("Value").asDouble());
    }

    @Test
    void getServiceQuota_unknownCode_throwsNoSuchResource() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getServiceQuota("vpc", "L-00000000", REGION, ACCOUNT));
        assertEquals("NoSuchResourceException", e.getErrorCode());
        AwsException defaults = assertThrows(AwsException.class,
                () -> service.getAwsDefaultServiceQuota("vpc", "L-00000000", REGION));
        assertEquals("NoSuchResourceException", defaults.getErrorCode());
    }

    @Test
    void unknownServiceCodeIsNoSuchResource() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.listServiceQuotas("widgetfactory", null, null, null, null, REGION, ACCOUNT));
        assertEquals("NoSuchResourceException", e.getErrorCode());
    }

    @Test
    void malformedServiceCodeIsRejected() {
        for (String bad : List.of("!!!", "1codebuild", "-codebuild", "code build", "c", "a".repeat(64))) {
            AwsException listing = assertThrows(AwsException.class,
                    () -> service.listServiceQuotas(bad, null, null, null, null, REGION, ACCOUNT),
                    "expected ListServiceQuotas to reject " + bad);
            assertEquals("IllegalArgumentException", listing.getErrorCode(), bad);
            assertEquals(400, listing.getHttpStatus(), bad);
        }
    }

    @Test
    void listServicesPaginatesTheWholeCatalog() {
        List<String> codes = new ArrayList<>();
        String token = null;
        do {
            ObjectNode page = service.listServices(token, 3);
            assertTrue(page.withArray("Services").size() <= 3);
            page.withArray("Services").forEach(node -> codes.add(node.path("ServiceCode").asText()));
            token = page.hasNonNull("NextToken") ? page.path("NextToken").asText() : null;
        } while (token != null);
        assertEquals(ServiceQuotasCatalog.services().stream()
                .map(ServiceQuotasCatalog.ServiceDefinition::serviceCode).toList(), codes);
        assertTrue(codes.contains("vpc"));
        assertTrue(codes.contains("lambda"));
    }

    @Test
    void increaseRequestIsPersistedAndListedByQuota() {
        String id = service.requestServiceQuotaIncrease("vpc", "L-F678F1CE", 10.0, null, REGION, ACCOUNT)
                .path("RequestedQuota").path("Id").asText();

        JsonNode fetched = service.getRequestedServiceQuotaChange(id, REGION, ACCOUNT).path("RequestedQuota");
        assertEquals("PENDING", fetched.path("Status").asText());
        assertEquals(10.0, fetched.path("DesiredValue").asDouble());

        JsonNode history = service.listRequestedServiceQuotaChangeHistoryByQuota(
                "vpc", "L-F678F1CE", "PENDING", null, null, null, REGION, ACCOUNT).path("RequestedQuotas");
        assertEquals(1, history.size());
        assertEquals(id, history.get(0).path("Id").asText());

        assertEquals(0, service.listRequestedServiceQuotaChangeHistoryByQuota(
                "vpc", "L-F678F1CE", "APPROVED", null, null, null, REGION, ACCOUNT)
                .path("RequestedQuotas").size());
        assertEquals(0, service.listRequestedServiceQuotaChangeHistory(
                null, null, null, null, null, REGION, "111111111111").path("RequestedQuotas").size());
    }

    @Test
    void secondOpenRequestForSameQuotaIsRejected() {
        service.requestServiceQuotaIncrease("vpc", "L-407747CB", 250.0, null, REGION, ACCOUNT);
        AwsException e = assertThrows(AwsException.class,
                () -> service.requestServiceQuotaIncrease("vpc", "L-407747CB", 300.0, null, REGION, ACCOUNT));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
    }

    @Test
    void desiredValueAtOrBelowCurrentValueIsRejected() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.requestServiceQuotaIncrease("vpc", "L-F678F1CE", 5.0, null, REGION, ACCOUNT));
        assertEquals("IllegalArgumentException", e.getErrorCode());
    }

    @Test
    void clearDropsRecordedRequests() {
        String id = service.requestServiceQuotaIncrease("vpc", "L-F678F1CE", 10.0, null, REGION, ACCOUNT)
                .path("RequestedQuota").path("Id").asText();
        service.clear();
        AwsException e = assertThrows(AwsException.class,
                () -> service.getRequestedServiceQuotaChange(id, REGION, ACCOUNT));
        assertEquals("NoSuchResourceException", e.getErrorCode());
    }
}

package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Create/UpdateService must persist and return the control-plane fields Alchemy
 * asserts: {@code deploymentConfiguration.deploymentCircuitBreaker},
 * {@code healthCheckGracePeriodSeconds}, and {@code capacityProviderStrategy}.
 */
class EcsJsonHandlerServiceFieldsTest {

    private EcsService service;
    private ObjectMapper objectMapper;
    private EcsJsonHandler handler;
    private EcsServiceModel stored;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = mock(EcsService.class);
        stored = new EcsServiceModel();
        stored.setServiceName("svc");
        stored.setServiceArn("arn:aws:ecs:us-east-1:000000000000:service/cluster/svc");
        stored.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/cluster");
        stored.setTaskDefinition("family:1");
        stored.setDesiredCount(0);
        stored.setStatus("ACTIVE");
        when(service.createService(any(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), anyString()))
                .thenReturn(stored);
        when(service.updateService(any(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(stored);
        handler = new EcsJsonHandler(service, objectMapper);
    }

    @Test
    void createServiceRoundTripsCircuitBreakerGracePeriodAndStrategy() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "cluster": "cluster",
                  "serviceName": "svc",
                  "taskDefinition": "family:1",
                  "desiredCount": 0,
                  "deploymentConfiguration": {
                    "minimumHealthyPercent": 100,
                    "maximumPercent": 200,
                    "deploymentCircuitBreaker": {"enable": true, "rollback": true}
                  },
                  "healthCheckGracePeriodSeconds": 45,
                  "capacityProviderStrategy": [
                    {"capacityProvider": "FARGATE_SPOT", "weight": 1, "base": 0}
                  ]
                }
                """);

        Response response = handler.handle("CreateService", request, "us-east-1");
        JsonNode svc = objectMapper.valueToTree(response.getEntity()).path("service");

        assertTrue(svc.path("deploymentConfiguration").path("deploymentCircuitBreaker").path("enable").asBoolean());
        assertEquals(45, svc.path("healthCheckGracePeriodSeconds").asInt());
        assertEquals("FARGATE_SPOT",
                svc.path("capacityProviderStrategy").get(0).path("capacityProvider").asText());
        assertEquals(1, svc.path("capacityProviderStrategy").get(0).path("weight").asInt());
    }

    @Test
    void updateServiceAppliesCircuitBreaker() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "cluster": "cluster",
                  "service": "svc",
                  "deploymentConfiguration": {
                    "deploymentCircuitBreaker": {"enable": true, "rollback": true}
                  }
                }
                """);

        Response response = handler.handle("UpdateService", request, "us-east-1");
        JsonNode svc = objectMapper.valueToTree(response.getEntity()).path("service");
        assertTrue(svc.path("deploymentConfiguration").path("deploymentCircuitBreaker").path("enable").asBoolean());
    }
}

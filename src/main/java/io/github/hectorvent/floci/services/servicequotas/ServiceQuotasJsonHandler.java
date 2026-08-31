package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.servicequotas.ServiceQuotasService.QuotaSnapshot;
import io.github.hectorvent.floci.services.servicequotas.ServiceQuotasService.ServiceDef;
import io.github.hectorvent.floci.services.servicequotas.model.QuotaIncreaseRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Base64;
import java.util.List;

/**
 * JSON 1.1 handler for AWS Service Quotas. Dispatched from
 * {@code AwsJson11Controller} under the {@code ServiceQuotasV20190624.} target prefix.
 */
@ApplicationScoped
public class ServiceQuotasJsonHandler {

    static final String TARGET_PREFIX = "ServiceQuotasV20190624.";

    private final ServiceQuotasService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ServiceQuotasJsonHandler(ServiceQuotasService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "GetServiceQuota" -> getServiceQuota(body);
                case "GetAWSDefaultServiceQuota" -> getDefaultQuota(body);
                case "ListServices" -> listServices(body);
                case "ListServiceQuotas" -> listServiceQuotas(body);
                case "ListAWSDefaultServiceQuotas" -> listDefaultQuotas(body);
                case "ListRequestedServiceQuotaChangeHistory" -> listHistory(body, false);
                case "ListRequestedServiceQuotaChangeHistoryByQuota" -> listHistory(body, true);
                case "RequestServiceQuotaIncrease" -> requestIncrease(body);
                case "GetRequestedServiceQuotaChange" -> getRequest(body);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                        TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response getServiceQuota(JsonNode body) {
        QuotaSnapshot quota = service.getAppliedQuota(
                textOrNull(body, "ServiceCode"),
                textOrNull(body, "QuotaCode"),
                textOrNull(body, "ContextId"));
        ObjectNode response = objectMapper.createObjectNode();
        putQuota(response.putObject("Quota"), quota);
        return Response.ok(response).build();
    }

    private Response getDefaultQuota(JsonNode body) {
        QuotaSnapshot quota = service.getDefaultQuota(
                textOrNull(body, "ServiceCode"),
                textOrNull(body, "QuotaCode"));
        ObjectNode response = objectMapper.createObjectNode();
        putQuota(response.putObject("Quota"), quota);
        return Response.ok(response).build();
    }

    private Response listServices(JsonNode body) {
        Page<ServiceDef> page = paginate(service.listServices(), body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode services = response.putArray("Services");
        for (ServiceDef item : page.items()) {
            ObjectNode node = services.addObject();
            node.put("ServiceCode", item.serviceCode());
            node.put("ServiceName", item.serviceName());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response listServiceQuotas(JsonNode body) {
        Page<QuotaSnapshot> page = paginate(
                service.listAppliedQuotas(textOrNull(body, "ServiceCode")), body);
        return quotaList(page);
    }

    private Response listDefaultQuotas(JsonNode body) {
        Page<QuotaSnapshot> page = paginate(
                service.listDefaultQuotas(textOrNull(body, "ServiceCode")), body);
        return quotaList(page);
    }

    private Response quotaList(Page<QuotaSnapshot> page) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode quotas = response.putArray("Quotas");
        for (QuotaSnapshot quota : page.items()) {
            putQuota(quotas.addObject(), quota);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response listHistory(JsonNode body, boolean byQuota) {
        String serviceCode = textOrNull(body, "ServiceCode");
        String quotaCode = textOrNull(body, "QuotaCode");
        if (byQuota) {
            // Validate the quota exists the way GetServiceQuota does, then
            // still return an empty history when nothing has been requested.
            service.requireQuota(serviceCode, quotaCode);
        } else if (serviceCode != null && !serviceCode.isEmpty()) {
            service.requireService(serviceCode);
        }
        Page<QuotaIncreaseRequest> page = paginate(
                service.listHistory(serviceCode, quotaCode, textOrNull(body, "Status")),
                body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode requested = response.putArray("RequestedQuotas");
        for (QuotaIncreaseRequest item : page.items()) {
            putRequest(requested.addObject(), item);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response requestIncrease(JsonNode body) {
        QuotaIncreaseRequest request = service.requestIncrease(
                textOrNull(body, "ServiceCode"),
                textOrNull(body, "QuotaCode"),
                doubleOrNull(body, "DesiredValue"),
                textOrNull(body, "ContextId"));
        ObjectNode response = objectMapper.createObjectNode();
        putRequest(response.putObject("RequestedQuota"), request);
        return Response.ok(response).build();
    }

    private Response getRequest(JsonNode body) {
        QuotaIncreaseRequest request = service.getRequest(textOrNull(body, "RequestId"));
        ObjectNode response = objectMapper.createObjectNode();
        putRequest(response.putObject("RequestedQuota"), request);
        return Response.ok(response).build();
    }

    private void putQuota(ObjectNode node, QuotaSnapshot quota) {
        node.put("ServiceCode", quota.serviceCode());
        node.put("ServiceName", quota.serviceName());
        node.put("QuotaCode", quota.quotaCode());
        node.put("QuotaName", quota.quotaName());
        node.put("QuotaArn", quota.quotaArn());
        node.put("Value", quota.value());
        node.put("Unit", quota.unit());
        node.put("Adjustable", quota.adjustable());
        node.put("GlobalQuota", quota.globalQuota());
    }

    private void putRequest(ObjectNode node, QuotaIncreaseRequest request) {
        node.put("Id", request.getId());
        node.put("ServiceCode", request.getServiceCode());
        if (request.getServiceName() != null) {
            node.put("ServiceName", request.getServiceName());
        }
        node.put("QuotaCode", request.getQuotaCode());
        if (request.getQuotaName() != null) {
            node.put("QuotaName", request.getQuotaName());
        }
        if (request.getQuotaArn() != null) {
            node.put("QuotaArn", request.getQuotaArn());
        }
        if (request.getDesiredValue() != null) {
            node.put("DesiredValue", request.getDesiredValue());
        }
        node.put("Status", request.getStatus());
        if (request.getCreated() != null) {
            node.put("Created", request.getCreated());
        }
        if (request.getLastUpdated() != null) {
            node.put("LastUpdated", request.getLastUpdated());
        }
        if (request.getUnit() != null) {
            node.put("Unit", request.getUnit());
        }
        if (request.getGlobalQuota() != null) {
            node.put("GlobalQuota", request.getGlobalQuota());
        }
        if (request.getRequester() != null) {
            node.put("Requester", request.getRequester());
        }
    }

    private static <T> Page<T> paginate(List<T> items, JsonNode body) {
        int start = decodeToken(textOrNull(body, "NextToken"));
        if (start < 0 || start > items.size()) {
            throw new AwsException("InvalidPaginationTokenException", "Invalid NextToken.", 400);
        }
        Integer maxResults = integerOrNull(body, "MaxResults");
        int limit = maxResults == null ? 100 : maxResults;
        if (limit < 1 || limit > 100) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'MaxResults' failed to satisfy constraint: "
                            + "Member must have value less than or equal to 100.",
                    400);
        }
        int end = Math.min(items.size(), start + limit);
        String next = end < items.size() ? encodeToken(end) : null;
        return new Page<>(items.subList(start, end), next);
    }

    private static String encodeToken(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return Integer.parseInt(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isEmpty()) ? null : text;
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && value.isNumber()) ? value.asInt() : null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && value.isNumber()) ? value.asDouble() : null;
    }

    private record Page<T>(List<T> items, String nextToken) {
    }
}

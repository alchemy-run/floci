package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Service Quotas operations.
 * Dispatches {@code X-Amz-Target: ServiceQuotasV20190624.*} actions to {@link ServiceQuotasService}.
 *
 * @see <a href="https://docs.aws.amazon.com/servicequotas/2019-06-24/apireference/Welcome.html">Service Quotas API</a>
 */
@ApplicationScoped
public class ServiceQuotasJsonHandler {

    private static final Logger LOG = Logger.getLogger(ServiceQuotasJsonHandler.class);

    private final ServiceQuotasService service;

    @Inject
    public ServiceQuotasJsonHandler(ServiceQuotasService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) {
        LOG.debugv("ServiceQuotas action: {0}", action);
        return switch (action) {
            case "ListServices" -> Response.ok(service.listServices(
                    stringOrNull(request, "NextToken"), integerOrNull(request, "MaxResults"))).build();
            case "ListServiceQuotas" -> Response.ok(service.listServiceQuotas(
                    stringOrNull(request, "ServiceCode"),
                    stringOrNull(request, "QuotaCode"),
                    stringOrNull(request, "QuotaAppliedAtLevel"),
                    stringOrNull(request, "NextToken"),
                    integerOrNull(request, "MaxResults"),
                    region, accountId)).build();
            case "ListAWSDefaultServiceQuotas" -> Response.ok(service.listAwsDefaultServiceQuotas(
                    stringOrNull(request, "ServiceCode"),
                    stringOrNull(request, "NextToken"),
                    integerOrNull(request, "MaxResults"),
                    region)).build();
            case "GetServiceQuota" -> Response.ok(service.getServiceQuota(
                    stringOrNull(request, "ServiceCode"),
                    stringOrNull(request, "QuotaCode"),
                    region, accountId)).build();
            case "GetAWSDefaultServiceQuota" -> Response.ok(service.getAwsDefaultServiceQuota(
                    stringOrNull(request, "ServiceCode"),
                    stringOrNull(request, "QuotaCode"),
                    region)).build();
            case "RequestServiceQuotaIncrease" -> Response.ok(service.requestServiceQuotaIncrease(
                    stringOrNull(request, "ServiceCode"),
                    stringOrNull(request, "QuotaCode"),
                    doubleOrNull(request, "DesiredValue"),
                    stringOrNull(request, "ContextId"),
                    region, accountId)).build();
            case "GetRequestedServiceQuotaChange" -> Response.ok(service.getRequestedServiceQuotaChange(
                    stringOrNull(request, "RequestId"), region, accountId)).build();
            case "ListRequestedServiceQuotaChangeHistory" ->
                    Response.ok(service.listRequestedServiceQuotaChangeHistory(
                            stringOrNull(request, "ServiceCode"),
                            stringOrNull(request, "Status"),
                            stringOrNull(request, "QuotaRequestedAtLevel"),
                            stringOrNull(request, "NextToken"),
                            integerOrNull(request, "MaxResults"),
                            region, accountId)).build();
            case "ListRequestedServiceQuotaChangeHistoryByQuota" ->
                    Response.ok(service.listRequestedServiceQuotaChangeHistoryByQuota(
                            stringOrNull(request, "ServiceCode"),
                            stringOrNull(request, "QuotaCode"),
                            stringOrNull(request, "Status"),
                            stringOrNull(request, "QuotaRequestedAtLevel"),
                            stringOrNull(request, "NextToken"),
                            integerOrNull(request, "MaxResults"),
                            region, accountId)).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: ServiceQuotasV20190624." + action))
                    .build();
        };
    }

    private static String stringOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && value.isNumber()) ? value.asInt() : null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && value.isNumber()) ? value.asDouble() : null;
    }
}

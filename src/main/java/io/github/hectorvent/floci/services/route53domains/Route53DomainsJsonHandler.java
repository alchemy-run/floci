package io.github.hectorvent.floci.services.route53domains;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Route 53 Domains.
 * Dispatched from {@code AwsJson11Controller} under the
 * {@code Route53Domains_v20140515.} target prefix.
 *
 * @see <a href="https://docs.aws.amazon.com/Route53/latest/APIReference/API_Operations_Amazon_Route_53_Domains.html">Route 53 Domains API</a>
 */
@ApplicationScoped
public class Route53DomainsJsonHandler {

    private static final Logger LOG = Logger.getLogger(Route53DomainsJsonHandler.class);

    private final Route53DomainsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public Route53DomainsJsonHandler(Route53DomainsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Route53Domains action: {0} region={1}", action, region);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CheckDomainAvailability" -> ok(service.checkDomainAvailability(body));
            case "CheckDomainTransferability" -> ok(service.checkDomainTransferability(body));
            case "GetDomainDetail" -> ok(service.getDomainDetail(body));
            case "GetDomainSuggestions" -> ok(service.getDomainSuggestions(body));
            case "GetOperationDetail" -> ok(service.getOperationDetail(body));
            case "ListDomains" -> ok(service.listDomains(body));
            case "ListOperations" -> ok(service.listOperations(body));
            case "ListPrices" -> ok(service.listPrices(body));
            case "RegisterDomain" -> ok(service.registerDomain(body));
            case "RenewDomain" -> ok(service.renewDomain(body));
            case "RetrieveDomainAuthCode" -> ok(service.retrieveDomainAuthCode(body));
            case "UpdateDomainNameservers" -> ok(service.updateDomainNameservers(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                    Route53DomainsService.TARGET_PREFIX + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}

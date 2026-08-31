package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Route 53 Resolver. Dispatched from
 * {@code AwsJson11Controller} under the {@code Route53Resolver.} target prefix.
 */
@ApplicationScoped
public class Route53ResolverJsonHandler {

    private static final Logger LOG = Logger.getLogger(Route53ResolverJsonHandler.class);

    private final Route53ResolverService service;
    private final Route53ResolverFirewallService firewallService;
    private final ObjectMapper objectMapper;

    @Inject
    public Route53ResolverJsonHandler(
            Route53ResolverService service,
            Route53ResolverFirewallService firewallService,
            ObjectMapper objectMapper) {
        this.service = service;
        this.firewallService = firewallService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Route 53 Resolver action: {0} region={1}", action, region);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateResolverEndpoint" -> ok(service.createResolverEndpoint(body, region));
            case "GetResolverEndpoint" -> ok(service.getResolverEndpoint(body));
            case "ListResolverEndpoints" -> ok(service.listResolverEndpoints(body));
            case "UpdateResolverEndpoint" -> ok(service.updateResolverEndpoint(body));
            case "DeleteResolverEndpoint" -> ok(service.deleteResolverEndpoint(body));
            case "ListResolverEndpointIpAddresses" -> ok(service.listResolverEndpointIpAddresses(body));
            case "CreateResolverRule" -> ok(service.createResolverRule(body, region));
            case "GetResolverRule" -> ok(service.getResolverRule(body));
            case "ListResolverRules" -> ok(service.listResolverRules(body));
            case "UpdateResolverRule" -> ok(service.updateResolverRule(body));
            case "DeleteResolverRule" -> ok(service.deleteResolverRule(body));
            case "ListResolverRuleAssociations" -> ok(service.listResolverRuleAssociations(body));
            case "GetResolverRuleAssociation" -> ok(service.getResolverRuleAssociation(body));
            case "AssociateResolverRule" -> ok(service.associateResolverRule(body, region));
            case "DisassociateResolverRule" -> ok(service.disassociateResolverRule(body));
            case "CreateFirewallRuleGroup" -> ok(firewallService.createFirewallRuleGroup(body, region));
            case "ListFirewallRuleGroups" -> ok(firewallService.listFirewallRuleGroups(body));
            case "DeleteFirewallRuleGroup" -> ok(firewallService.deleteFirewallRuleGroup(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                    Route53ResolverService.TARGET_PREFIX + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}

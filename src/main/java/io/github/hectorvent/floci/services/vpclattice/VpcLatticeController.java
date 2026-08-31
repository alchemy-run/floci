package io.github.hectorvent.floci.services.vpclattice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeListener;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeRule;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeService;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeTarget;
import io.github.hectorvent.floci.services.vpclattice.model.LatticeTargetGroup;
import io.github.hectorvent.floci.services.vpclattice.model.ServiceAssociation;
import io.github.hectorvent.floci.services.vpclattice.model.ServiceNetwork;
import io.github.hectorvent.floci.services.vpclattice.model.VpcAssociation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * VPC Lattice restJson1. {@link VpcLatticeRoutingFilter} prefixes signed paths
 * onto {@link VpcLatticeRoutingFilter#INTERNAL_PREFIX} so they do not collide
 * with S3. Tag APIs share {@code /tags/{arn}} via {@code SharedTagsController}.
 */
@Path(VpcLatticeRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VpcLatticeController {

    private final VpcLatticeService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public VpcLatticeController(
            VpcLatticeService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/servicenetworks")
    public Response createServiceNetwork(@Context HttpHeaders headers, String body) {
        ServiceNetwork network = service.createServiceNetwork(region(headers), parse(body));
        return Response.ok(service.networkNode(network)).build();
    }

    @GET
    @Path("/servicenetworks")
    @Consumes(MediaType.WILDCARD)
    public Response listServiceNetworks(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (ServiceNetwork network : service.listServiceNetworks(region(headers))) {
            items.add(service.networkSummary(network));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/servicenetworks/{serviceNetworkIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getServiceNetwork(
            @Context HttpHeaders headers,
            @PathParam("serviceNetworkIdentifier") String serviceNetworkIdentifier) {
        ServiceNetwork network = service.getServiceNetwork(region(headers), decode(serviceNetworkIdentifier));
        return Response.ok(service.networkNode(network)).build();
    }

    @PATCH
    @Path("/servicenetworks/{serviceNetworkIdentifier}")
    public Response updateServiceNetwork(
            @Context HttpHeaders headers,
            @PathParam("serviceNetworkIdentifier") String serviceNetworkIdentifier,
            String body) {
        ServiceNetwork network = service.updateServiceNetwork(
                region(headers), decode(serviceNetworkIdentifier), parse(body));
        return Response.ok(service.networkNode(network)).build();
    }

    @DELETE
    @Path("/servicenetworks/{serviceNetworkIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteServiceNetwork(
            @Context HttpHeaders headers,
            @PathParam("serviceNetworkIdentifier") String serviceNetworkIdentifier) {
        service.deleteServiceNetwork(region(headers), decode(serviceNetworkIdentifier));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/accesslogsubscriptions")
    public Response createAccessLogSubscription(@Context HttpHeaders headers, String body) {
        var subscription = service.createAccessLogSubscription(region(headers), parse(body));
        return Response.ok(service.accessLogCreateNode(subscription)).build();
    }

    @GET
    @Path("/accesslogsubscriptions")
    @Consumes(MediaType.WILDCARD)
    public Response listAccessLogSubscriptions(
            @Context HttpHeaders headers,
            @QueryParam("resourceIdentifier") String resourceIdentifier) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (var subscription : service.listAccessLogSubscriptions(region(headers), resourceIdentifier)) {
            items.add(service.accessLogNode(subscription));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/accesslogsubscriptions/{accessLogSubscriptionIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getAccessLogSubscription(
            @Context HttpHeaders headers,
            @PathParam("accessLogSubscriptionIdentifier") String accessLogSubscriptionIdentifier) {
        return Response.ok(service.accessLogNode(
                service.getAccessLogSubscription(region(headers), decode(accessLogSubscriptionIdentifier)))).build();
    }

    @PATCH
    @Path("/accesslogsubscriptions/{accessLogSubscriptionIdentifier}")
    public Response updateAccessLogSubscription(
            @Context HttpHeaders headers,
            @PathParam("accessLogSubscriptionIdentifier") String accessLogSubscriptionIdentifier,
            String body) {
        var subscription = service.updateAccessLogSubscription(
                region(headers), decode(accessLogSubscriptionIdentifier), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", subscription.getId());
        response.put("arn", subscription.getArn());
        response.put("resourceId", subscription.getResourceId());
        response.put("resourceArn", subscription.getResourceArn());
        response.put("destinationArn", subscription.getDestinationArn());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/accesslogsubscriptions/{accessLogSubscriptionIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAccessLogSubscription(
            @Context HttpHeaders headers,
            @PathParam("accessLogSubscriptionIdentifier") String accessLogSubscriptionIdentifier) {
        service.deleteAccessLogSubscription(region(headers), decode(accessLogSubscriptionIdentifier));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/servicenetworkresourceassociations")
    @Consumes(MediaType.WILDCARD)
    public Response listServiceNetworkResourceAssociations() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("items");
        return Response.ok(response).build();
    }

    @GET
    @Path("/authpolicy/{resourceIdentifier:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getAuthPolicy(
            @Context HttpHeaders headers, @PathParam("resourceIdentifier") String resourceIdentifier) {
        VpcLatticeService.AuthPolicyView policy =
                service.getAuthPolicy(region(headers), decode(resourceIdentifier));
        ObjectNode response = objectMapper.createObjectNode();
        if (policy.policy() != null) {
            response.put("policy", policy.policy());
            if (policy.state() != null) {
                response.put("state", policy.state());
            }
            if (policy.createdAt() != null) {
                response.put("createdAt", policy.createdAt());
            }
            if (policy.lastUpdatedAt() != null) {
                response.put("lastUpdatedAt", policy.lastUpdatedAt());
            }
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/authpolicy/{resourceIdentifier:.+}")
    public Response putAuthPolicy(
            @Context HttpHeaders headers,
            @PathParam("resourceIdentifier") String resourceIdentifier,
            String body) {
        VpcLatticeService.AuthPolicyView policy =
                service.putAuthPolicy(region(headers), decode(resourceIdentifier), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policy", policy.policy());
        response.put("state", policy.state());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/authpolicy/{resourceIdentifier:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAuthPolicy(
            @Context HttpHeaders headers, @PathParam("resourceIdentifier") String resourceIdentifier) {
        service.deleteAuthPolicy(region(headers), decode(resourceIdentifier));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/resourcepolicy/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getResourcePolicy(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        String policy = service.getResourcePolicy(region(headers), decode(resourceArn));
        ObjectNode response = objectMapper.createObjectNode();
        if (policy != null) {
            response.put("policy", policy);
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/resourcepolicy/{resourceArn:.+}")
    public Response putResourcePolicy(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn, String body) {
        service.putResourcePolicy(region(headers), decode(resourceArn), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/resourcepolicy/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourcePolicy(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        service.deleteResourcePolicy(region(headers), decode(resourceArn));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/services")
    public Response createService(@Context HttpHeaders headers, String body) {
        LatticeService created = service.createService(region(headers), parse(body));
        return Response.ok(service.serviceNode(created)).build();
    }

    @GET
    @Path("/services")
    @Consumes(MediaType.WILDCARD)
    public Response listServices(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (LatticeService created : service.listServices(region(headers))) {
            items.add(service.serviceSummary(created));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/services/{serviceIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getService(
            @Context HttpHeaders headers, @PathParam("serviceIdentifier") String serviceIdentifier) {
        LatticeService created = service.getService(region(headers), decode(serviceIdentifier));
        return Response.ok(service.serviceNode(created)).build();
    }

    @PATCH
    @Path("/services/{serviceIdentifier}")
    public Response updateService(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            String body) {
        LatticeService updated = service.updateService(region(headers), decode(serviceIdentifier), parse(body));
        return Response.ok(service.serviceNode(updated)).build();
    }

    @DELETE
    @Path("/services/{serviceIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteService(
            @Context HttpHeaders headers, @PathParam("serviceIdentifier") String serviceIdentifier) {
        LatticeService deleted = service.deleteService(region(headers), decode(serviceIdentifier));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", deleted.getId());
        response.put("arn", deleted.getArn());
        response.put("name", deleted.getName());
        response.put("status", deleted.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/servicenetworkserviceassociations")
    public Response createServiceAssociation(@Context HttpHeaders headers, String body) {
        ServiceAssociation association = service.createServiceAssociation(region(headers), parse(body));
        return Response.ok(service.associationNode(association)).build();
    }

    @GET
    @Path("/servicenetworkserviceassociations")
    @Consumes(MediaType.WILDCARD)
    public Response listServiceAssociations(
            @Context HttpHeaders headers,
            @QueryParam("serviceNetworkIdentifier") String serviceNetworkIdentifier,
            @QueryParam("serviceIdentifier") String serviceIdentifier) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (ServiceAssociation association : service.listServiceAssociations(
                region(headers), serviceNetworkIdentifier, serviceIdentifier)) {
            items.add(service.associationNode(association));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/servicenetworkserviceassociations/{serviceNetworkServiceAssociationIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getServiceAssociation(
            @Context HttpHeaders headers,
            @PathParam("serviceNetworkServiceAssociationIdentifier") String identifier) {
        ServiceAssociation association = service.getServiceAssociation(region(headers), decode(identifier));
        return Response.ok(service.associationNode(association)).build();
    }

    @DELETE
    @Path("/servicenetworkserviceassociations/{serviceNetworkServiceAssociationIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteServiceAssociation(
            @Context HttpHeaders headers,
            @PathParam("serviceNetworkServiceAssociationIdentifier") String identifier) {
        ServiceAssociation association = service.deleteServiceAssociation(region(headers), decode(identifier));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", association.getId());
        response.put("arn", association.getArn());
        response.put("status", association.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/servicenetworkvpcassociations")
    public Response createVpcAssociation(@Context HttpHeaders headers, String body) {
        VpcAssociation association = service.createVpcAssociation(region(headers), parse(body));
        return Response.ok(service.vpcAssociationNode(association)).build();
    }

    @GET
    @Path("/servicenetworkvpcassociations")
    @Consumes(MediaType.WILDCARD)
    public Response listVpcAssociations(
            @Context HttpHeaders headers,
            @QueryParam("serviceNetworkIdentifier") String serviceNetworkIdentifier,
            @QueryParam("vpcIdentifier") String vpcIdentifier) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (VpcAssociation association : service.listVpcAssociations(
                region(headers), serviceNetworkIdentifier, vpcIdentifier)) {
            items.add(service.vpcAssociationNode(association));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/servicenetworkvpcassociations/{serviceNetworkVpcAssociationIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getVpcAssociation(
            @Context HttpHeaders headers,
            @PathParam("serviceNetworkVpcAssociationIdentifier") String identifier) {
        VpcAssociation association = service.getVpcAssociation(region(headers), decode(identifier));
        return Response.ok(service.vpcAssociationNode(association)).build();
    }

    @DELETE
    @Path("/servicenetworkvpcassociations/{serviceNetworkVpcAssociationIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteVpcAssociation(
            @Context HttpHeaders headers,
            @PathParam("serviceNetworkVpcAssociationIdentifier") String identifier) {
        VpcAssociation association = service.deleteVpcAssociation(region(headers), decode(identifier));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", association.getId());
        response.put("arn", association.getArn());
        response.put("status", association.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/targetgroups")
    public Response createTargetGroup(@Context HttpHeaders headers, String body) {
        LatticeTargetGroup group = service.createTargetGroup(region(headers), parse(body));
        return Response.ok(service.targetGroupNode(group)).build();
    }

    @GET
    @Path("/targetgroups")
    @Consumes(MediaType.WILDCARD)
    public Response listTargetGroups(
            @Context HttpHeaders headers,
            @QueryParam("vpcIdentifier") String vpcIdentifier,
            @QueryParam("targetGroupType") String targetGroupType) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (LatticeTargetGroup group : service.listTargetGroups(region(headers), vpcIdentifier, targetGroupType)) {
            items.add(service.targetGroupSummary(group));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/targetgroups/{targetGroupIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getTargetGroup(
            @Context HttpHeaders headers, @PathParam("targetGroupIdentifier") String targetGroupIdentifier) {
        LatticeTargetGroup group = service.getTargetGroup(region(headers), decode(targetGroupIdentifier));
        return Response.ok(service.targetGroupNode(group)).build();
    }

    @PATCH
    @Path("/targetgroups/{targetGroupIdentifier}")
    public Response updateTargetGroup(
            @Context HttpHeaders headers,
            @PathParam("targetGroupIdentifier") String targetGroupIdentifier,
            String body) {
        LatticeTargetGroup group = service.updateTargetGroup(
                region(headers), decode(targetGroupIdentifier), parse(body));
        return Response.ok(service.targetGroupNode(group)).build();
    }

    @DELETE
    @Path("/targetgroups/{targetGroupIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTargetGroup(
            @Context HttpHeaders headers, @PathParam("targetGroupIdentifier") String targetGroupIdentifier) {
        LatticeTargetGroup group = service.deleteTargetGroup(region(headers), decode(targetGroupIdentifier));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", group.getId());
        response.put("arn", group.getArn());
        response.put("status", group.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/targetgroups/{targetGroupIdentifier}/registertargets")
    public Response registerTargets(
            @Context HttpHeaders headers,
            @PathParam("targetGroupIdentifier") String targetGroupIdentifier,
            String body) {
        return Response.ok(service.registerTargets(
                region(headers), decode(targetGroupIdentifier), parse(body))).build();
    }

    @POST
    @Path("/targetgroups/{targetGroupIdentifier}/deregistertargets")
    public Response deregisterTargets(
            @Context HttpHeaders headers,
            @PathParam("targetGroupIdentifier") String targetGroupIdentifier,
            String body) {
        return Response.ok(service.deregisterTargets(
                region(headers), decode(targetGroupIdentifier), parse(body))).build();
    }

    @POST
    @Path("/targetgroups/{targetGroupIdentifier}/listtargets")
    @Consumes(MediaType.WILDCARD)
    public Response listTargets(
            @Context HttpHeaders headers, @PathParam("targetGroupIdentifier") String targetGroupIdentifier) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (LatticeTarget target : service.listTargets(region(headers), decode(targetGroupIdentifier))) {
            items.add(service.targetNode(target));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/services/{serviceIdentifier}/listeners")
    public Response createListener(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            String body) {
        LatticeListener listener = service.createListener(region(headers), decode(serviceIdentifier), parse(body));
        return Response.ok(service.listenerNode(listener)).build();
    }

    @GET
    @Path("/services/{serviceIdentifier}/listeners")
    @Consumes(MediaType.WILDCARD)
    public Response listListeners(
            @Context HttpHeaders headers, @PathParam("serviceIdentifier") String serviceIdentifier) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (LatticeListener listener : service.listListeners(region(headers), decode(serviceIdentifier))) {
            items.add(service.listenerSummary(listener));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getListener(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier) {
        LatticeListener listener = service.getListener(
                region(headers), decode(serviceIdentifier), decode(listenerIdentifier));
        return Response.ok(service.listenerNode(listener)).build();
    }

    @PATCH
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}")
    public Response updateListener(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier,
            String body) {
        LatticeListener listener = service.updateListener(
                region(headers), decode(serviceIdentifier), decode(listenerIdentifier), parse(body));
        return Response.ok(service.listenerNode(listener)).build();
    }

    @DELETE
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteListener(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier) {
        service.deleteListener(region(headers), decode(serviceIdentifier), decode(listenerIdentifier));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}/rules")
    public Response createRule(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier,
            String body) {
        LatticeRule rule = service.createRule(
                region(headers), decode(serviceIdentifier), decode(listenerIdentifier), parse(body));
        return Response.ok(service.ruleNode(rule)).build();
    }

    @GET
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}/rules")
    @Consumes(MediaType.WILDCARD)
    public Response listRules(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (LatticeRule rule : service.listRules(
                region(headers), decode(serviceIdentifier), decode(listenerIdentifier))) {
            items.add(service.ruleSummary(rule));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}/rules/{ruleIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getRule(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier,
            @PathParam("ruleIdentifier") String ruleIdentifier) {
        LatticeRule rule = service.getRule(
                region(headers), decode(serviceIdentifier), decode(listenerIdentifier), decode(ruleIdentifier));
        return Response.ok(service.ruleNode(rule)).build();
    }

    @PATCH
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}/rules/{ruleIdentifier}")
    public Response updateRule(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier,
            @PathParam("ruleIdentifier") String ruleIdentifier,
            String body) {
        LatticeRule rule = service.updateRule(
                region(headers),
                decode(serviceIdentifier),
                decode(listenerIdentifier),
                decode(ruleIdentifier),
                parse(body));
        return Response.ok(service.ruleNode(rule)).build();
    }

    @DELETE
    @Path("/services/{serviceIdentifier}/listeners/{listenerIdentifier}/rules/{ruleIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRule(
            @Context HttpHeaders headers,
            @PathParam("serviceIdentifier") String serviceIdentifier,
            @PathParam("listenerIdentifier") String listenerIdentifier,
            @PathParam("ruleIdentifier") String ruleIdentifier) {
        service.deleteRule(
                region(headers), decode(serviceIdentifier), decode(listenerIdentifier), decode(ruleIdentifier));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw VpcLatticeService.validation("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw VpcLatticeService.validation("Request body is not valid JSON.");
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}

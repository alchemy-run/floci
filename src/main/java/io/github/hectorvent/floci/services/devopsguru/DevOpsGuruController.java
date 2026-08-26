package io.github.hectorvent.floci.services.devopsguru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.devopsguru.model.DevOpsGuruAccount;
import io.github.hectorvent.floci.services.devopsguru.model.NotificationChannel;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Amazon DevOps Guru restJson1.
 *
 * <p>{@link DevOpsGuruRoutingFilter} prefixes requests signed for {@code devops-guru}
 * so paths such as {@code /insights}, {@code /channels}, and
 * {@code /resource-collections} do not collide with SES, Audit Manager, or S3's catch-all.
 */
@Path("/devops-guru")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DevOpsGuruController {

    private final DevOpsGuruService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DevOpsGuruController(
            DevOpsGuruService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/accounts/health")
    @Consumes(MediaType.WILDCARD)
    public Response describeAccountHealth() {
        return Response.ok(service.describeAccountHealth()).build();
    }

    @POST
    @Path("/accounts/overview")
    public Response describeAccountOverview(String body) {
        return Response.ok(service.describeAccountOverview(parse(body))).build();
    }

    @GET
    @Path("/accounts/health/resource-collection/{resourceCollectionType}")
    @Consumes(MediaType.WILDCARD)
    public Response describeResourceCollectionHealth(
            @Context HttpHeaders headers,
            @PathParam("resourceCollectionType") String resourceCollectionType) {
        return Response.ok(service.describeResourceCollectionHealth(
                region(headers), resourceCollectionType)).build();
    }

    @GET
    @Path("/anomalies/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response describeAnomaly(@PathParam("id") String id) {
        return Response.ok(service.describeAnomaly(id)).build();
    }

    @POST
    @Path("/anomalies/insight/{insightId}")
    public Response listAnomaliesForInsight(@PathParam("insightId") String insightId) {
        return Response.ok(service.listAnomaliesForInsight(insightId)).build();
    }

    @PUT
    @Path("/channels")
    public Response addNotificationChannel(@Context HttpHeaders headers, String body) {
        NotificationChannel channel = service.addNotificationChannel(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", channel.getId());
        return Response.ok(response).build();
    }

    @POST
    @Path("/channels")
    @Consumes(MediaType.WILDCARD)
    public Response listNotificationChannels(@Context HttpHeaders headers, String body) {
        parse(body);
        List<NotificationChannel> channels = service.listNotificationChannels(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Channels");
        for (NotificationChannel channel : channels) {
            list.add(toChannel(channel));
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/channels/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response removeNotificationChannel(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.removeNotificationChannel(region(headers), id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/cost-estimation")
    @Consumes(MediaType.WILDCARD)
    public Response getCostEstimation(@Context HttpHeaders headers) {
        return Response.ok(service.getCostEstimation(region(headers))).build();
    }

    @PUT
    @Path("/cost-estimation")
    public Response startCostEstimation(@Context HttpHeaders headers, String body) {
        return Response.ok(service.startCostEstimation(region(headers), parse(body))).build();
    }

    @POST
    @Path("/event-sources")
    @Consumes(MediaType.WILDCARD)
    public Response describeEventSourcesConfig(@Context HttpHeaders headers, String body) {
        return Response.ok(toEventSources(service.describeEventSources(region(headers)))).build();
    }

    @PUT
    @Path("/event-sources")
    public Response updateEventSourcesConfig(@Context HttpHeaders headers, String body) {
        service.updateEventSources(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/events")
    public Response listEvents() {
        return Response.ok(service.listEvents()).build();
    }

    @POST
    @Path("/feedback")
    public Response describeFeedback(@Context HttpHeaders headers, String body) {
        return Response.ok(service.describeFeedback(region(headers), parse(body))).build();
    }

    @PUT
    @Path("/feedback")
    public Response putFeedback(@Context HttpHeaders headers, String body) {
        return Response.ok(service.putFeedback(region(headers), parse(body))).build();
    }

    @POST
    @Path("/insights")
    public Response listInsights(String body) {
        return Response.ok(service.listInsights(parse(body))).build();
    }

    @POST
    @Path("/insights/search")
    public Response searchInsights(String body) {
        return Response.ok(service.searchInsights(parse(body))).build();
    }

    @GET
    @Path("/insights/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response describeInsight(@PathParam("id") String id) {
        service.describeInsight(id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/insights/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInsight(@PathParam("id") String id) {
        return Response.ok(service.deleteInsight(id)).build();
    }

    @POST
    @Path("/list-log-anomalies")
    public Response listAnomalousLogGroups(String body) {
        return Response.ok(service.listAnomalousLogGroups(parse(body))).build();
    }

    @POST
    @Path("/monitoredResources")
    public Response listMonitoredResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.listMonitoredResources(region(headers), parse(body))).build();
    }

    @POST
    @Path("/organization/health")
    public Response describeOrganizationHealth() {
        return Response.ok(service.describeOrganizationHealth()).build();
    }

    @POST
    @Path("/organization/overview")
    public Response describeOrganizationOverview(String body) {
        return Response.ok(service.describeOrganizationOverview(parse(body))).build();
    }

    @POST
    @Path("/organization/health/resource-collection")
    public Response describeOrganizationResourceCollectionHealth(String body) {
        return Response.ok(service.describeOrganizationResourceCollectionHealth(parse(body))).build();
    }

    @POST
    @Path("/organization/insights")
    public Response listOrganizationInsights(String body) {
        return Response.ok(service.listOrganizationInsights(parse(body))).build();
    }

    @POST
    @Path("/organization/insights/search")
    public Response searchOrganizationInsights(String body) {
        return Response.ok(service.searchOrganizationInsights(parse(body))).build();
    }

    @POST
    @Path("/recommendations")
    public Response listRecommendations() {
        return Response.ok(service.listRecommendations()).build();
    }

    @GET
    @Path("/resource-collections/{resourceCollectionType}")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceCollection(
            @Context HttpHeaders headers,
            @PathParam("resourceCollectionType") String resourceCollectionType) {
        return Response.ok(service.getResourceCollection(region(headers), resourceCollectionType)).build();
    }

    @PUT
    @Path("/resource-collections")
    public Response updateResourceCollection(@Context HttpHeaders headers, String body) {
        return Response.ok(service.updateResourceCollection(region(headers), parse(body))).build();
    }

    @GET
    @Path("/service-integrations")
    @Consumes(MediaType.WILDCARD)
    public Response describeServiceIntegration(@Context HttpHeaders headers) {
        return Response.ok(toIntegration(service.describeServiceIntegration(region(headers)))).build();
    }

    @PUT
    @Path("/service-integrations")
    public Response updateServiceIntegration(@Context HttpHeaders headers, String body) {
        service.updateServiceIntegration(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toEventSources(DevOpsGuruAccount account) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode eventSources = response.putObject("EventSources");
        eventSources.putObject("AmazonCodeGuruProfiler")
                .put("Status", account.getProfilerStatus() == null ? "DISABLED" : account.getProfilerStatus());
        return response;
    }

    private ObjectNode toIntegration(DevOpsGuruAccount account) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode config = response.putObject("ServiceIntegration");
        config.putObject("OpsCenter")
                .put("OptInStatus", account.getOpsCenterStatus() == null ? "DISABLED" : account.getOpsCenterStatus());
        config.putObject("LogsAnomalyDetection")
                .put("OptInStatus",
                        account.getLogsAnomalyStatus() == null ? "DISABLED" : account.getLogsAnomalyStatus());
        ObjectNode kms = config.putObject("KMSServerSideEncryption");
        String type = account.getEncryptionType() == null ? "AWS_OWNED_KMS_KEY" : account.getEncryptionType();
        kms.put("Type", type);
        kms.put("OptInStatus", "CUSTOMER_MANAGED_KEY".equals(type) ? "ENABLED" : "DISABLED");
        if (account.getKmsKeyId() != null) {
            kms.put("KMSKeyId", account.getKmsKeyId());
        }
        return response;
    }

    private ObjectNode toChannel(NotificationChannel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", channel.getId());
        ObjectNode config = node.putObject("Config");
        config.putObject("Sns").put("TopicArn", channel.getTopicArn());
        boolean hasSeverities = channel.getSeverities() != null;
        boolean hasMessageTypes = channel.getMessageTypes() != null;
        if (hasSeverities || hasMessageTypes) {
            ObjectNode filters = config.putObject("Filters");
            if (hasSeverities) {
                ArrayNode severities = filters.putArray("Severities");
                channel.getSeverities().forEach(severities::add);
            }
            if (hasMessageTypes) {
                ArrayNode messageTypes = filters.putArray("MessageTypes");
                channel.getMessageTypes().forEach(messageTypes::add);
            }
        }
        return node;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }
}

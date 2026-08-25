package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfiguration;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfigurationRevision;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon MQ control plane (REST JSON). Paths and wire keys mirror the AWS
 * {@code mq} API (camelCase bodies under {@code /v1/brokers}).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmazonMqController {

    private final AmazonMqService service;

    @Inject
    public AmazonMqController(AmazonMqService service) {
        this.service = service;
    }

    @POST
    @Path("/v1/brokers")
    public Response createBroker(Map<String, Object> request) {
        CreateBrokerParams params = new CreateBrokerParams(
                str(request, "brokerName"),
                str(request, "engineType"),
                str(request, "engineVersion"),
                str(request, "deploymentMode"),
                str(request, "hostInstanceType"),
                bool(request, "publiclyAccessible"),
                bool(request, "autoMinorVersionUpgrade"),
                parseUsers(request.get("users")),
                tags(request.get("tags")));
        Broker broker = service.createBroker(params);
        return Response.ok(Map.of(
                "brokerArn", broker.getBrokerArn(),
                "brokerId", broker.getBrokerId())).build();
    }

    @GET
    @Path("/v1/brokers")
    public Response listBrokers() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Broker b : service.listBrokers()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("brokerArn", b.getBrokerArn());
            summary.put("brokerId", b.getBrokerId());
            summary.put("brokerName", b.getBrokerName());
            summary.put("brokerState", b.getBrokerState());
            summary.put("created", b.getCreated());
            summary.put("deploymentMode", b.getDeploymentMode());
            summary.put("engineType", b.getEngineType());
            summary.put("hostInstanceType", b.getHostInstanceType());
            summaries.add(summary);
        }
        return Response.ok(Map.of("brokerSummaries", summaries)).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}")
    public Response describeBroker(@PathParam("broker-id") String brokerId) {
        return Response.ok(brokerResponse(service.describeBroker(brokerId))).build();
    }

    // Builds the DescribeBroker response explicitly. The Broker model persists
    // internal bookkeeping (containerId, accountId, volumeId) so the broker can be
    // managed after a restart, but those fields are not part of the AWS shape — hand-
    // building the response keeps them out of the client-facing payload.
    private static Map<String, Object> brokerResponse(Broker b) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", b.getBrokerId());
        body.put("brokerArn", b.getBrokerArn());
        body.put("brokerName", b.getBrokerName());
        body.put("brokerState", b.getBrokerState());
        body.put("engineType", b.getEngineType());
        body.put("engineVersion", b.getEngineVersion());
        body.put("deploymentMode", b.getDeploymentMode());
        body.put("hostInstanceType", b.getHostInstanceType());
        body.put("publiclyAccessible", b.isPubliclyAccessible());
        body.put("autoMinorVersionUpgrade", b.isAutoMinorVersionUpgrade());
        body.put("created", b.getCreated());
        body.put("brokerInstances", b.getBrokerInstances());
        body.put("users", b.getUsers());
        body.put("tags", b.getTags());
        return body;
    }

    @DELETE
    @Path("/v1/brokers/{broker-id}")
    public Response deleteBroker(@PathParam("broker-id") String brokerId) {
        service.deleteBroker(brokerId);
        return Response.ok(Map.of("brokerId", brokerId)).build();
    }

    @POST
    @Path("/v1/brokers/{broker-id}/reboot")
    public Response rebootBroker(@PathParam("broker-id") String brokerId) {
        service.rebootBroker(brokerId);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response createUser(@PathParam("broker-id") String brokerId,
                               @PathParam("username") String username,
                               Map<String, Object> request) {
        MqUser user = new MqUser(
                username,
                str(request, "password"),
                bool(request, "consoleAccess"),
                strList(request.get("groups")));
        service.createUser(brokerId, user);
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response describeUser(@PathParam("broker-id") String brokerId,
                                 @PathParam("username") String username) {
        MqUser user = service.describeUser(brokerId, username);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", brokerId);
        body.put("username", user.getUsername());
        body.put("consoleAccess", user.isConsoleAccess());
        body.put("groups", user.getGroups());
        return Response.ok(body).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}/users")
    public Response listUsers(@PathParam("broker-id") String brokerId) {
        List<Map<String, Object>> users = new ArrayList<>();
        for (MqUser u : service.listUsers(brokerId)) {
            users.add(Map.of("username", u.getUsername()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", brokerId);
        body.put("users", users);
        return Response.ok(body).build();
    }

    @DELETE
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response deleteUser(@PathParam("broker-id") String brokerId,
                               @PathParam("username") String username) {
        service.deleteUser(brokerId, username);
        return Response.ok(Map.of()).build();
    }

    // --- broker engine types ---

    @GET
    @Path("/v1/broker-engine-types")
    public Response describeBrokerEngineTypes(@QueryParam("engineType") String engineType,
                                              @QueryParam("maxResults") Integer maxResults) {
        List<Map<String, Object>> types = service.describeBrokerEngineTypes(engineType);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerEngineTypes", types);
        if (maxResults != null) {
            body.put("maxResults", maxResults);
        }
        return Response.ok(body).build();
    }

    // --- configurations ---

    @POST
    @Path("/v1/configurations")
    public Response createConfiguration(Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        CreateConfigurationParams params = new CreateConfigurationParams(
                str(body, "name"),
                str(body, "engineType"),
                str(body, "engineVersion"),
                str(body, "authenticationStrategy"),
                tags(body.get("tags")));
        MqConfiguration configuration = service.createConfiguration(params);
        return Response.ok(createConfigurationResponse(configuration)).build();
    }

    @GET
    @Path("/v1/configurations")
    public Response listConfigurations() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (MqConfiguration configuration : service.listConfigurations()) {
            summaries.add(configurationResponse(configuration));
        }
        return Response.ok(Map.of("configurations", summaries)).build();
    }

    @GET
    @Path("/v1/configurations/{configuration-id}/revisions/{revision}")
    public Response describeConfigurationRevision(
            @PathParam("configuration-id") String configurationId,
            @PathParam("revision") String revision) {
        MqConfigurationRevision rev =
                service.describeConfigurationRevision(configurationId, revision);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurationId", configurationId);
        body.put("created", timestamp(rev.getCreated()));
        body.put("data", AmazonMqService.encodeConfigurationData(rev.getData()));
        if (rev.getDescription() != null) {
            body.put("description", rev.getDescription());
        }
        return Response.ok(body).build();
    }

    @GET
    @Path("/v1/configurations/{configuration-id}/revisions")
    public Response listConfigurationRevisions(
            @PathParam("configuration-id") String configurationId) {
        List<Map<String, Object>> revisions = new ArrayList<>();
        for (MqConfigurationRevision rev : service.listConfigurationRevisions(configurationId)) {
            revisions.add(revisionSummary(rev));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurationId", configurationId);
        body.put("revisions", revisions);
        return Response.ok(body).build();
    }

    @GET
    @Path("/v1/configurations/{configuration-id}")
    public Response describeConfiguration(
            @PathParam("configuration-id") String configurationId) {
        return Response.ok(configurationResponse(service.describeConfiguration(configurationId)))
                .build();
    }

    @PUT
    @Path("/v1/configurations/{configuration-id}")
    public Response updateConfiguration(@PathParam("configuration-id") String configurationId,
                                        Map<String, Object> request) {
        Map<String, Object> input = request == null ? Map.of() : request;
        MqConfiguration configuration = service.updateConfiguration(
                configurationId,
                str(input, "data"),
                str(input, "description"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", configuration.getArn());
        body.put("created", timestamp(configuration.getCreated()));
        body.put("id", configuration.getId());
        body.put("latestRevision", revisionSummary(configuration.latestRevision()));
        body.put("name", configuration.getName());
        body.put("warnings", List.of());
        return Response.ok(body).build();
    }

    @DELETE
    @Path("/v1/configurations/{configuration-id}")
    public Response deleteConfiguration(
            @PathParam("configuration-id") String configurationId) {
        service.deleteConfiguration(configurationId);
        return Response.ok(Map.of("configurationId", configurationId)).build();
    }

    // --- tags (rewritten from /v1/tags/{arn} by AmazonMqRoutingFilter) ---

    @POST
    @Path(AmazonMqRoutingFilter.INTERNAL_PREFIX + "/v1/tags/{resourceArn:.+}")
    public Response createTags(@PathParam("resourceArn") String resourceArn,
                               Map<String, Object> request) {
        service.createTags(resourceArn, tags(request == null ? null : request.get("tags")));
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path(AmazonMqRoutingFilter.INTERNAL_PREFIX + "/v1/tags/{resourceArn:.+}")
    public Response listTags(@PathParam("resourceArn") String resourceArn) {
        return Response.ok(Map.of("tags", service.listTags(resourceArn))).build();
    }

    @DELETE
    @Path(AmazonMqRoutingFilter.INTERNAL_PREFIX + "/v1/tags/{resourceArn:.+}")
    public Response deleteTags(@PathParam("resourceArn") String resourceArn,
                               @QueryParam("tagKeys") List<String> tagKeys) {
        service.deleteTags(resourceArn, tagKeys);
        return Response.ok(Map.of()).build();
    }

    private static Map<String, Object> createConfigurationResponse(MqConfiguration c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", c.getArn());
        body.put("authenticationStrategy", c.getAuthenticationStrategy());
        body.put("created", timestamp(c.getCreated()));
        body.put("id", c.getId());
        body.put("latestRevision", revisionSummary(c.latestRevision()));
        body.put("name", c.getName());
        return body;
    }

    private static Map<String, Object> configurationResponse(MqConfiguration c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", c.getArn());
        body.put("authenticationStrategy", c.getAuthenticationStrategy());
        body.put("created", timestamp(c.getCreated()));
        MqConfigurationRevision latest = c.latestRevision();
        if (latest != null && latest.getDescription() != null) {
            body.put("description", latest.getDescription());
        }
        body.put("engineType", c.getEngineType());
        body.put("engineVersion", c.getEngineVersion());
        body.put("id", c.getId());
        body.put("latestRevision", revisionSummary(latest));
        body.put("name", c.getName());
        body.put("tags", c.getTags() == null ? Map.of() : c.getTags());
        return body;
    }

    private static Map<String, Object> revisionSummary(MqConfigurationRevision rev) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (rev == null) {
            return body;
        }
        body.put("created", timestamp(rev.getCreated()));
        if (rev.getDescription() != null) {
            body.put("description", rev.getDescription());
        }
        body.put("revision", rev.getRevision());
        return body;
    }

    private static String timestamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    // --- request parsing helpers ---

    private static String str(Map<String, Object> request, String key) {
        Object value = request.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> request, String key) {
        return Boolean.TRUE.equals(request.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> tags(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<MqUser> parseUsers(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<MqUser> users = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> u = (Map<String, Object>) map;
                users.add(new MqUser(
                        str(u, "username"),
                        str(u, "password"),
                        bool(u, "consoleAccess"),
                        strList(u.get("groups"))));
            }
        }
        return users;
    }
}

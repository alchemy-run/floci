package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfiguration;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

    /** {@link AmazonMqRouteFilter} rewrites mq-signed {@code /v1/configurations} requests here. */
    private static final String CONFIGURATIONS = AmazonMqRouteFilter.INTERNAL_PREFIX + "/v1/configurations";

    /**
     * Engine versions DescribeBrokerEngineTypes reports, mirroring the AWS catalog. Only
     * RabbitMQ brokers are provisioned; ActiveMQ is listed because configurations for it
     * are real control-plane documents here.
     */
    private static final Map<String, List<String>> ENGINE_VERSIONS = engineVersions();

    private final AmazonMqService service;
    private final AmazonMqConfigurationService configurations;

    @Inject
    public AmazonMqController(AmazonMqService service, AmazonMqConfigurationService configurations) {
        this.service = service;
        this.configurations = configurations;
    }

    @POST
    @Path("/v1/brokers")
    public Response createBroker(Map<String, Object> request) {
        if (request.get("configuration") instanceof Map<?, ?> reference) {
            configurations.validateBrokerReference(
                    reference.get("id") == null ? null : String.valueOf(reference.get("id")),
                    integer(reference.get("revision")),
                    str(request, "engineType"));
        }
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
        body.put("tags", b.getTags());
        body.put("authenticationStrategy", AmazonMqService.effectiveAuthenticationStrategy(b));
        body.put("dataReplicationMode", "NONE");
        putIfPresent(body, "pendingEngineVersion", b.getPendingEngineVersion());
        putIfPresent(body, "pendingHostInstanceType", b.getPendingHostInstanceType());
        putIfPresent(body, "pendingAuthenticationStrategy", b.getPendingAuthenticationStrategy());
        putIfPresent(body, "maintenanceWindowStartTime", b.getMaintenanceWindowStartTime());
        putIfPresent(body, "logs", b.getLogs());
        putIfPresent(body, "securityGroups", b.getSecurityGroups());
        if (b.getConfigurationId() != null || b.getPendingConfigurationId() != null) {
            Map<String, Object> configurations = new LinkedHashMap<>();
            if (b.getConfigurationId() != null) {
                configurations.put("current", configurationRef(b.getConfigurationId(), b.getConfigurationRevision()));
            }
            if (b.getPendingConfigurationId() != null) {
                configurations.put("pending",
                        configurationRef(b.getPendingConfigurationId(), b.getPendingConfigurationRevision()));
            }
            body.put("configurations", configurations);
        }
        return body;
    }

    private static Map<String, Object> configurationRef(String id, Integer revision) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", id);
        if (revision != null) {
            ref.put("revision", revision);
        }
        return ref;
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    @PUT
    @Path("/v1/brokers/{broker-id}")
    public Response updateBroker(@PathParam("broker-id") String brokerId, Map<String, Object> request) {
        Broker existing = service.describeBroker(brokerId);
        String configurationId = null;
        Integer configurationRevision = null;
        if (request.get("configuration") instanceof Map<?, ?> reference) {
            configurationId = reference.get("id") == null ? null : String.valueOf(reference.get("id"));
            configurationRevision = integer(reference.get("revision"));
            configurations.validateBrokerReference(configurationId, configurationRevision, existing.getEngineType());
        }
        Broker broker = service.updateBroker(brokerId, new AmazonMqService.BrokerUpdate(
                request.get("autoMinorVersionUpgrade") instanceof Boolean flag ? flag : null,
                str(request, "engineVersion"),
                str(request, "hostInstanceType"),
                str(request, "authenticationStrategy"),
                configurationId,
                configurationRevision,
                objectMap(request.get("maintenanceWindowStartTime")),
                objectMap(request.get("logs")),
                strList(request.get("securityGroups")),
                str(request, "dataReplicationMode")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", broker.getBrokerId());
        body.put("autoMinorVersionUpgrade", broker.isAutoMinorVersionUpgrade());
        body.put("authenticationStrategy", broker.getPendingAuthenticationStrategy() != null
                ? broker.getPendingAuthenticationStrategy() : AmazonMqService.effectiveAuthenticationStrategy(broker));
        body.put("engineVersion", broker.getPendingEngineVersion() != null
                ? broker.getPendingEngineVersion() : broker.getEngineVersion());
        body.put("hostInstanceType", broker.getPendingHostInstanceType() != null
                ? broker.getPendingHostInstanceType() : broker.getHostInstanceType());
        body.put("dataReplicationMode", "NONE");
        if (configurationId != null) {
            body.put("configuration", configurationRef(configurationId, configurationRevision));
        }
        putIfPresent(body, "maintenanceWindowStartTime", broker.getMaintenanceWindowStartTime());
        putIfPresent(body, "logs", broker.getLogs());
        putIfPresent(body, "securityGroups", broker.getSecurityGroups());
        return Response.ok(body).build();
    }

    @POST
    @Path("/v1/brokers/{broker-id}/promote")
    public Response promote(@PathParam("broker-id") String brokerId, Map<String, Object> request) {
        service.promote(brokerId, request == null ? null : str(request, "mode"));
        return Response.ok(Map.of("brokerId", brokerId)).build();
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

    @PUT
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response updateUser(@PathParam("broker-id") String brokerId,
                               @PathParam("username") String username) {
        service.updateUser(brokerId, username);
        return Response.ok(Map.of()).build();
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
                                              @QueryParam("maxResults") String maxResultsParam,
                                              @QueryParam("nextToken") String nextToken) {
        Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "BadRequestException");
        if (maxResults != null && (maxResults < 5 || maxResults > 100)) {
            throw new AwsException("BadRequestException", "maxResults must be between 5 and 100", 400);
        }
        List<Map<String, Object>> engines = new ArrayList<>();
        ENGINE_VERSIONS.forEach((engine, versions) -> {
            if (engineType != null && !engineType.isBlank() && !engine.equalsIgnoreCase(engineType)) {
                return;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("engineType", engine);
            entry.put("engineVersions", versions.stream().map(v -> Map.of("name", v)).toList());
            engines.add(entry);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerEngineTypes", engines);
        body.put("maxResults", maxResults != null ? maxResults : 100);
        return Response.ok(body).build();
    }

    private static Map<String, List<String>> engineVersions() {
        Map<String, List<String>> versions = new LinkedHashMap<>();
        versions.put(AmazonMqConfigurationService.ENGINE_ACTIVEMQ, List.of("5.18", "5.17.6", "5.16.7", "5.15.16"));
        versions.put(AmazonMqConfigurationService.ENGINE_RABBITMQ, AmazonMqService.RABBITMQ_ENGINE_VERSIONS);
        return versions;
    }

    // --- configurations ---

    @POST
    @Path(CONFIGURATIONS)
    public Response createConfiguration(Map<String, Object> request) {
        MqConfiguration c = configurations.createConfiguration(
                str(request, "name"),
                str(request, "engineType"),
                str(request, "engineVersion"),
                str(request, "authenticationStrategy"),
                tags(request.get("tags")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", c.getArn());
        body.put("authenticationStrategy", c.getAuthenticationStrategy());
        body.put("created", c.getCreated().toString());
        body.put("id", c.getId());
        body.put("latestRevision", revisionSummary(c.latestRevision()));
        body.put("name", c.getName());
        return Response.ok(body).build();
    }

    @GET
    @Path(CONFIGURATIONS)
    public Response listConfigurations(@QueryParam("maxResults") String maxResultsParam,
                                       @QueryParam("nextToken") String nextToken) {
        PaginatedResult<MqConfiguration> page = configurations.listConfigurations(
                Pagination.parseMaxResults(maxResultsParam, "BadRequestException"), nextToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurations", page.items().stream().map(AmazonMqController::configurationView).toList());
        if (maxResultsParam != null && !maxResultsParam.isBlank()) {
            body.put("maxResults", Integer.parseInt(maxResultsParam));
        }
        if (page.nextToken() != null) {
            body.put("nextToken", page.nextToken());
        }
        return Response.ok(body).build();
    }

    @GET
    @Path(CONFIGURATIONS + "/{configuration-id}")
    public Response describeConfiguration(@PathParam("configuration-id") String configurationId) {
        return Response.ok(configurationView(configurations.describeConfiguration(configurationId))).build();
    }

    @PUT
    @Path(CONFIGURATIONS + "/{configuration-id}")
    public Response updateConfiguration(@PathParam("configuration-id") String configurationId,
                                        Map<String, Object> request) {
        Map<String, Object> safeRequest = request != null ? request : Map.of();
        MqConfiguration c = configurations.updateConfiguration(configurationId,
                str(safeRequest, "data"), str(safeRequest, "description"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", c.getArn());
        body.put("created", c.getCreated().toString());
        body.put("id", c.getId());
        body.put("latestRevision", revisionSummary(c.latestRevision()));
        body.put("name", c.getName());
        // Floci does not sanitize ActiveMQ documents, so there is never anything to warn about.
        body.put("warnings", List.of());
        return Response.ok(body).build();
    }

    @DELETE
    @Path(CONFIGURATIONS + "/{configuration-id}")
    public Response deleteConfiguration(@PathParam("configuration-id") String configurationId) {
        configurations.deleteConfiguration(configurationId);
        return Response.ok(Map.of("configurationId", configurationId)).build();
    }

    @GET
    @Path(CONFIGURATIONS + "/{configuration-id}/revisions")
    public Response listConfigurationRevisions(@PathParam("configuration-id") String configurationId,
                                               @QueryParam("maxResults") String maxResultsParam,
                                               @QueryParam("nextToken") String nextToken) {
        PaginatedResult<MqConfiguration.Revision> page = configurations.listConfigurationRevisions(
                configurationId, Pagination.parseMaxResults(maxResultsParam, "BadRequestException"), nextToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurationId", configurationId);
        body.put("revisions", page.items().stream().map(AmazonMqController::revisionSummary).toList());
        if (maxResultsParam != null && !maxResultsParam.isBlank()) {
            body.put("maxResults", Integer.parseInt(maxResultsParam));
        }
        if (page.nextToken() != null) {
            body.put("nextToken", page.nextToken());
        }
        return Response.ok(body).build();
    }

    @GET
    @Path(CONFIGURATIONS + "/{configuration-id}/revisions/{configuration-revision}")
    public Response describeConfigurationRevision(@PathParam("configuration-id") String configurationId,
                                                  @PathParam("configuration-revision") String revision) {
        MqConfiguration.Revision r = configurations.describeConfigurationRevision(configurationId, revision);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurationId", configurationId);
        body.put("created", r.getCreated().toString());
        body.put("data", r.getData());
        if (r.getDescription() != null) {
            body.put("description", r.getDescription());
        }
        return Response.ok(body).build();
    }

    private static Map<String, Object> configurationView(MqConfiguration c) {
        MqConfiguration.Revision latest = c.latestRevision();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", c.getArn());
        body.put("authenticationStrategy", c.getAuthenticationStrategy());
        body.put("created", c.getCreated().toString());
        body.put("description", latest != null && latest.getDescription() != null ? latest.getDescription() : "");
        // AWS echoes the display casing ("ActiveMQ"), not the request enum.
        body.put("engineType", AmazonMqConfigurationService.displayEngine(c.getEngineType()));
        body.put("engineVersion", c.getEngineVersion());
        body.put("id", c.getId());
        body.put("latestRevision", revisionSummary(latest));
        body.put("name", c.getName());
        body.put("tags", c.getTags());
        return body;
    }

    private static Map<String, Object> revisionSummary(MqConfiguration.Revision r) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (r == null) {
            return body;
        }
        body.put("created", r.getCreated().toString());
        if (r.getDescription() != null) {
            body.put("description", r.getDescription());
        }
        body.put("revision", r.getRevision());
        return body;
    }

    // --- request parsing helpers ---

    private static Integer integer(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            throw new AwsException("BadRequestException",
                    "The configuration revision [" + raw + "] is not a valid revision number.", 400);
        }
    }

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

    private static Map<String, Object> objectMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
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

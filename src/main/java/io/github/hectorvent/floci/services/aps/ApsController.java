package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.aps.ApsService.ConfigurationKind;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import io.github.hectorvent.floci.services.aps.model.RuleGroupsNamespace;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Amazon Managed Service for Prometheus (Smithy restJson1, SigV4 scope {@code aps}) — the
 * workspace lifecycle, rule and alert definitions, logging, policies, and anomaly detector metadata.
 * Configuration storage does not enable rule evaluation, alert delivery, or anomaly execution.
 * Tagging goes through the shared
 * {@code /tags/{resourceArn}} dispatcher, which routes {@code arn:aws:aps:...} to
 * {@link ApsService}'s TagHandler implementation.
 *
 * <p>The literal {@code /workspaces} path segment takes JAX-RS precedence over S3's
 * {@code /{bucket}} template route, so these routes need no extra routing wiring (see
 * {@link io.github.hectorvent.floci.services.controltower.ControlTowerController}).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApsController {

    private final ApsService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ApsController(ApsService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/scraperconfiguration")
    public Response getDefaultScraperConfiguration() {
        String configuration = """
                global:
                  scrape_interval: 30s
                scrape_configs:
                  - job_name: kubernetes-apiservers
                    kubernetes_sd_configs:
                      - role: endpoints
                    scheme: https
                    tls_config:
                      ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
                    bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
                    relabel_configs:
                      - source_labels: [__meta_kubernetes_namespace, __meta_kubernetes_service_name, __meta_kubernetes_endpoint_port_name]
                        action: keep
                        regex: default;kubernetes;https
                """;
        return Response.ok(Map.of("configuration",
                Base64.getEncoder().encodeToString(configuration.getBytes(StandardCharsets.UTF_8)))).build();
    }

    @POST
    @Path("/workspaces")
    public Response createWorkspace(@Context HttpHeaders headers, Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        String alias = asString(body.get("alias"), "alias");
        String kmsKeyArn = asString(body.get("kmsKeyArn"), "kmsKeyArn");
        Map<String, String> tags = asStringMap(body.get("tags"), "tags");
        // clientToken is accepted and ignored: creates are not deduplicated.

        PrometheusWorkspace workspace =
                service.createWorkspace(regionResolver.resolveRegion(headers), alias, tags, kmsKeyArn);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("workspaceId", workspace.getWorkspaceId());
        response.put("arn", workspace.getArn());
        response.set("status", statusNode(workspace));
        response.set("tags", objectMapper.valueToTree(workspace.getTags()));
        if (workspace.getKmsKeyArn() != null) {
            response.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return Response.status(202).entity(response).build();
    }

    @GET
    @Path("/workspaces")
    public Response listWorkspaces(@Context HttpHeaders headers,
                                   @QueryParam("alias") String alias,
                                   @QueryParam("maxResults") String maxResultsParam,
                                   @QueryParam("nextToken") String nextToken) {
        PaginatedResult<PrometheusWorkspace> result = service.listWorkspaces(
                regionResolver.resolveRegion(headers), alias,
                Pagination.parseMaxResults(maxResultsParam, "ValidationException"), nextToken);

        Map<String, Object> response = new HashMap<>();
        response.put("workspaces", result.items().stream().map(this::toWorkspaceSummary).toList());
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}")
    public Response describeWorkspace(@Context HttpHeaders headers,
                                      @PathParam("workspaceId") String workspaceId) {
        PrometheusWorkspace workspace =
                service.describeWorkspace(regionResolver.resolveRegion(headers), workspaceId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workspace", toWorkspaceDescription(workspace));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}")
    public Response deleteWorkspace(@Context HttpHeaders headers,
                                    @PathParam("workspaceId") String workspaceId) {
        service.deleteWorkspace(regionResolver.resolveRegion(headers), workspaceId);
        // The model documents "an HTTP 202 response with an empty HTTP body".
        return Response.status(202).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/alias")
    public Response updateWorkspaceAlias(@Context HttpHeaders headers,
                                         @PathParam("workspaceId") String workspaceId,
                                         Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        String alias = asString(body.get("alias"), "alias");
        service.updateWorkspaceAlias(regionResolver.resolveRegion(headers), workspaceId, alias);
        return Response.status(204).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/rulegroupsnamespaces")
    public Response createRuleGroupsNamespace(@Context HttpHeaders headers,
                                              @PathParam("workspaceId") String workspaceId,
                                              Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        RuleGroupsNamespace namespace = service.createRuleGroupsNamespace(
                regionResolver.resolveRegion(headers), workspaceId,
                asString(body.get("name"), "name"), asString(body.get("data"), "data"),
                asStringMap(body.get("tags"), "tags"));
        return Response.status(202).entity(toNamespaceMutationResponse(namespace)).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/rulegroupsnamespaces")
    public Response listRuleGroupsNamespaces(@Context HttpHeaders headers,
                                             @PathParam("workspaceId") String workspaceId,
                                             @QueryParam("name") String name,
                                             @QueryParam("maxResults") String maxResultsParam,
                                             @QueryParam("nextToken") String nextToken) {
        PaginatedResult<RuleGroupsNamespace> result = service.listRuleGroupsNamespaces(
                regionResolver.resolveRegion(headers), workspaceId, name,
                Pagination.parseMaxResults(maxResultsParam, "ValidationException"), nextToken);

        Map<String, Object> response = new HashMap<>();
        response.put("ruleGroupsNamespaces", result.items().stream().map(this::toNamespaceSummary).toList());
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}")
    public Response describeRuleGroupsNamespace(@Context HttpHeaders headers,
                                                @PathParam("workspaceId") String workspaceId,
                                                @PathParam("name") String name) {
        RuleGroupsNamespace namespace = service.describeRuleGroupsNamespace(
                regionResolver.resolveRegion(headers), workspaceId, name);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode description = toNamespaceSummary(namespace);
        description.put("data", namespace.getEncodedData());
        response.set("ruleGroupsNamespace", description);
        return Response.ok(response).build();
    }

    @PUT
    @Path("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}")
    public Response putRuleGroupsNamespace(@Context HttpHeaders headers,
                                           @PathParam("workspaceId") String workspaceId,
                                           @PathParam("name") String name,
                                           Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        RuleGroupsNamespace namespace = service.putRuleGroupsNamespace(
                regionResolver.resolveRegion(headers), workspaceId, name,
                asString(body.get("data"), "data"));
        return Response.status(202).entity(toNamespaceMutationResponse(namespace)).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}")
    public Response deleteRuleGroupsNamespace(@Context HttpHeaders headers,
                                              @PathParam("workspaceId") String workspaceId,
                                              @PathParam("name") String name) {
        service.deleteRuleGroupsNamespace(regionResolver.resolveRegion(headers), workspaceId, name);
        return Response.status(202).build();
    }

    @GET
    @Path("/scrapers/{scraperId}")
    public Response describeScraper(@Context HttpHeaders headers, @PathParam("scraperId") String scraperId) {
        return Response.ok(Map.of("scraper", service.describeScraper(regionResolver.resolveRegion(headers), scraperId))).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/{configurationPath:alertmanager/definition|logging/query|logging}")
    public Response describeConfiguration(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                          @PathParam("configurationPath") String configurationPath) {
        ConfigurationKind kind = configurationKind(configurationPath);
        return Response.ok(Map.of(kind.responseKey(),
                service.describeConfiguration(regionResolver.resolveRegion(headers), workspaceId, kind))).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/{configurationPath:alertmanager/definition|logging/query|logging}")
    public Response createConfiguration(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                        @PathParam("configurationPath") String configurationPath, Map<String, Object> request) {
        return configurationMutation(headers, workspaceId, configurationKind(configurationPath), request, true);
    }

    @PUT
    @Path("/workspaces/{workspaceId}/{configurationPath:alertmanager/definition|logging/query|logging}")
    public Response updateConfiguration(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                        @PathParam("configurationPath") String configurationPath, Map<String, Object> request) {
        return configurationMutation(headers, workspaceId, configurationKind(configurationPath), request, false);
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/{configurationPath:alertmanager/definition|logging/query|logging}")
    public Response deleteConfiguration(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                        @PathParam("configurationPath") String configurationPath) {
        service.deleteConfiguration(regionResolver.resolveRegion(headers), workspaceId,
                configurationKind(configurationPath), null);
        return Response.status(202).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/configuration")
    public Response describeWorkspaceConfiguration(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(Map.of(ConfigurationKind.WORKSPACE.responseKey(), service.describeConfiguration(
                regionResolver.resolveRegion(headers), workspaceId, ConfigurationKind.WORKSPACE))).build();
    }

    @PATCH
    @Path("/workspaces/{workspaceId}/configuration")
    public Response updateWorkspaceConfiguration(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                                 Map<String, Object> request) {
        return configurationMutation(headers, workspaceId, ConfigurationKind.WORKSPACE, request, false);
    }

    @GET
    @Path("/workspaces/{workspaceId}/policy")
    public Response describeResourcePolicy(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.describeConfiguration(regionResolver.resolveRegion(headers), workspaceId,
                ConfigurationKind.POLICY)).build();
    }

    @PUT
    @Path("/workspaces/{workspaceId}/policy")
    public Response putResourcePolicy(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                      Map<String, Object> request) {
        ObjectNode policy = service.writeConfiguration(regionResolver.resolveRegion(headers), workspaceId,
                ConfigurationKind.POLICY, request, false);
        policy.remove("policyDocument");
        return Response.status(202).entity(policy).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/policy")
    public Response deleteResourcePolicy(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                         @QueryParam("revisionId") String revisionId) {
        service.deleteConfiguration(regionResolver.resolveRegion(headers), workspaceId, ConfigurationKind.POLICY, revisionId);
        return Response.status(202).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/anomalydetectors")
    public Response createAnomalyDetector(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                          Map<String, Object> request) {
        return Response.status(202).entity(detectorMutation(service.createAnomalyDetector(
                regionResolver.resolveRegion(headers), workspaceId, request))).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/anomalydetectors")
    public Response listAnomalyDetectors(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                        @QueryParam("alias") String alias, @QueryParam("maxResults") String maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        PaginatedResult<ObjectNode> result = service.listAnomalyDetectors(regionResolver.resolveRegion(headers),
                workspaceId, alias, Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("anomalyDetectors", objectMapper.valueToTree(result.items()));
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/anomalydetectors/{anomalyDetectorId}")
    public Response describeAnomalyDetector(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                            @PathParam("anomalyDetectorId") String detectorId) {
        return Response.ok(Map.of("anomalyDetector", service.describeAnomalyDetector(
                regionResolver.resolveRegion(headers), workspaceId, detectorId))).build();
    }

    @PUT
    @Path("/workspaces/{workspaceId}/anomalydetectors/{anomalyDetectorId}")
    public Response putAnomalyDetector(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                       @PathParam("anomalyDetectorId") String detectorId, Map<String, Object> request) {
        return Response.status(202).entity(detectorMutation(service.putAnomalyDetector(
                regionResolver.resolveRegion(headers), workspaceId, detectorId, request))).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/anomalydetectors/{anomalyDetectorId}")
    public Response deleteAnomalyDetector(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId,
                                          @PathParam("anomalyDetectorId") String detectorId) {
        service.deleteAnomalyDetector(regionResolver.resolveRegion(headers), workspaceId, detectorId);
        return Response.status(202).build();
    }

    private ObjectNode detectorMutation(ObjectNode description) {
        return description.retain("anomalyDetectorId", "arn", "status", "tags");
    }

    private Response configurationMutation(HttpHeaders headers, String workspaceId, ConfigurationKind kind,
                                           Map<String, Object> request, boolean create) {
        ObjectNode configuration = service.writeConfiguration(regionResolver.resolveRegion(headers), workspaceId,
                kind, request, create);
        return Response.status(202).entity(Map.of("status", configuration.get("status"))).build();
    }

    private ConfigurationKind configurationKind(String path) {
        return switch (path) {
            case "alertmanager/definition" -> ConfigurationKind.ALERT_MANAGER;
            case "logging" -> ConfigurationKind.LOGGING;
            case "logging/query" -> ConfigurationKind.QUERY_LOGGING;
            default -> throw new AwsException("ValidationException", "Unknown workspace configuration", 400);
        };
    }

    private ObjectNode toNamespaceMutationResponse(RuleGroupsNamespace namespace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", namespace.getName());
        node.put("arn", namespace.getArn());
        node.set("status", namespaceStatusNode(namespace));
        node.set("tags", objectMapper.valueToTree(namespace.getTags()));
        return node;
    }

    private ObjectNode toNamespaceSummary(RuleGroupsNamespace namespace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", namespace.getArn());
        node.put("name", namespace.getName());
        node.set("status", namespaceStatusNode(namespace));
        node.set("tags", objectMapper.valueToTree(namespace.getTags()));
        node.put("createdAt", namespace.getCreatedAt().toEpochMilli() / 1000.0);
        node.put("modifiedAt", namespace.getModifiedAt().toEpochMilli() / 1000.0);
        return node;
    }

    private ObjectNode namespaceStatusNode(RuleGroupsNamespace namespace) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("statusCode", namespace.getStatus());
        status.put("statusReason", "Rule definition stored only; local Prometheus does not evaluate these rules.");
        return status;
    }

    private ObjectNode toWorkspaceDescription(PrometheusWorkspace workspace) {
        ObjectNode node = toWorkspaceSummary(workspace);
        node.put("prometheusEndpoint", workspace.getPrometheusEndpoint());
        return node;
    }

    // AWS's WorkspaceSummary shape (ListWorkspaces) carries every WorkspaceDescription member
    // except prometheusEndpoint.
    private ObjectNode toWorkspaceSummary(PrometheusWorkspace workspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workspaceId", workspace.getWorkspaceId());
        if (workspace.getAlias() != null) {
            node.put("alias", workspace.getAlias());
        }
        node.put("arn", workspace.getArn());
        node.set("status", statusNode(workspace));
        // Smithy restJson1 timestamps with no timestampFormat trait are epoch-seconds; an ISO
        // string here fails deserialization inside the AWS SDKs.
        node.put("createdAt", workspace.getCreatedAt().toEpochMilli() / 1000.0);
        node.set("tags", objectMapper.valueToTree(workspace.getTags()));
        if (workspace.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return node;
    }

    private ObjectNode statusNode(PrometheusWorkspace workspace) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("statusCode", workspace.getStatus());
        return status;
    }

    private String asString(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new AwsException("ValidationException", fieldName + " must be a string.", 400);
        }
        return s;
    }

    private Map<String, String> asStringMap(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)
                || map.entrySet().stream().anyMatch(e -> !(e.getKey() instanceof String) || !(e.getValue() instanceof String))) {
            throw new AwsException("ValidationException", fieldName + " must be a map of strings.", 400);
        }
        Map<String, String> result = new HashMap<>();
        map.forEach((k, v) -> result.put((String) k, (String) v));
        return result;
    }
}

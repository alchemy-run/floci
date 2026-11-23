package io.floci.aws.services.lambdaruntimes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.lambdaruntimes.LambdaRuntimesService.Alias;
import io.floci.aws.services.lambdaruntimes.LambdaRuntimesService.Lifecycle;
import io.floci.aws.services.lambdaruntimes.LambdaRuntimesService.Restored;
import io.floci.aws.services.lambdaruntimes.LambdaRuntimesService.Runtime;
import io.floci.aws.services.lambdaruntimes.LambdaRuntimesService.Version;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lambda customer-managed runtimes ("Lambda Core", apiVersion 2026-10-01, rest-json, signs as
 * {@code lambda}). Members are PascalCase, like the network connector half of the same model.
 */
@Path("/2026-10-01")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaRuntimesController {

    private static final String RECOVERY_WINDOW_HEADER = "x-amzn-runtime-recovery-window-in-days";

    private final LambdaRuntimesService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaRuntimesController(LambdaRuntimesService service, RegionResolver regionResolver,
                                    ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------- runtimes

    @POST
    @Path("/runtimes")
    public Response createRuntime(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        Runtime runtime = service.createRuntime(region(headers), text(request, "Name"),
                text(request, "Description"), text(request, "LicenseInfo"),
                lifecycle(request.get("ReleaseLifecycle")), tags(request.get("Tags")));
        ObjectNode node = runtimeNode(runtime);
        node.remove("State");
        node.remove("Id");
        return Response.status(201).entity(node).build();
    }

    @GET
    @Path("/runtimes")
    public Response listRuntimes(@Context HttpHeaders headers) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        for (Runtime runtime : service.listRuntimes(region(headers))) {
            ObjectNode node = runtimeNode(runtime);
            node.remove("Id");
            items.add(node);
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/runtimes/{runtimeName}")
    public Response getRuntime(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName) {
        return Response.ok(runtimeNode(service.getRuntime(region(headers), runtimeName))).build();
    }

    @PUT
    @Path("/runtimes/{runtimeName}")
    public Response updateRuntime(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                  String body) {
        JsonNode request = parse(body);
        Runtime runtime = service.updateRuntime(region(headers), runtimeName, text(request, "Description"),
                text(request, "LicenseInfo"), lifecycle(request.get("ReleaseLifecycle")));
        ObjectNode node = runtimeNode(runtime);
        node.remove("State");
        node.remove("PendingDeletionAt");
        node.remove("Id");
        return Response.ok(node).build();
    }

    @DELETE
    @Path("/runtimes/{runtimeName}")
    public Response deleteRuntime(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                  @HeaderParam(RECOVERY_WINDOW_HEADER) Integer recoveryWindowInDays) {
        service.deleteRuntime(region(headers), runtimeName, recoveryWindowInDays);
        return Response.status(202).build();
    }

    @POST
    @Path("/runtimes/{runtimeName}/restore")
    public Response restoreRuntime(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                   @QueryParam("RuntimeVersion") String runtimeVersion,
                                   @QueryParam("RuntimeAlias") String runtimeAlias) {
        Restored restored = service.restore(region(headers), runtimeName, runtimeVersion, runtimeAlias);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RuntimeName", restored.runtimeName());
        if (restored.runtimeVersion() != null) node.put("RuntimeVersion", restored.runtimeVersion());
        if (restored.runtimeAlias() != null) node.put("RuntimeAlias", restored.runtimeAlias());
        node.put("Arn", restored.arn());
        node.put("State", restored.state());
        node.put("LastModified", iso(restored.lastModified()));
        return Response.ok(node).build();
    }

    // -------------------------------------------------------------- versions

    @POST
    @Path("/runtimes/{runtimeName}/versions")
    public Response createRuntimeVersion(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                  String body) {
        JsonNode request = parse(body);
        boolean dryRun = request.path("DryRun").asBoolean(false);
        Version version = service.createVersion(region(headers), runtimeName, text(request, "Name"),
                text(request, "ImageUri"), text(request, "ReleaseNotes"), dryRun);
        ObjectNode node = versionNode(version);
        node.remove("PendingDeletionAt");
        // The create response is a snapshot taken while Lambda ingests the image.
        node.put("State", "CREATING");
        return Response.status(201).entity(node).build();
    }

    @GET
    @Path("/runtimes/{runtimeName}/versions")
    public Response listRuntimeVersions(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        for (Version version : service.listVersions(region(headers), runtimeName)) {
            ObjectNode node = versionNode(version);
            node.remove("Platforms");
            items.add(node);
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/runtimes/{runtimeName}/versions/{name}")
    public Response getRuntimeVersion(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                               @PathParam("name") String name) {
        return Response.ok(versionNode(service.getVersion(region(headers), runtimeName, name))).build();
    }

    @PATCH
    @Path("/runtimes/{runtimeName}/versions/{name}")
    public Response updateRuntimeVersion(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                  @PathParam("name") String name, String body) {
        JsonNode request = parse(body);
        Version version = service.updateVersion(region(headers), runtimeName, name, text(request, "ReleaseNotes"));
        ObjectNode node = versionNode(version);
        node.remove("PendingDeletionAt");
        return Response.ok(node).build();
    }

    @DELETE
    @Path("/runtimes/{runtimeName}/versions/{name}")
    public Response deleteRuntimeVersion(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                  @PathParam("name") String name,
                                  @HeaderParam(RECOVERY_WINDOW_HEADER) Integer recoveryWindowInDays) {
        service.deleteVersion(region(headers), runtimeName, name, recoveryWindowInDays);
        return Response.status(202).build();
    }

    // --------------------------------------------------------------- aliases

    @POST
    @Path("/runtimes/{runtimeName}/aliases")
    public Response createRuntimeAlias(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                String body) {
        JsonNode request = parse(body);
        String region = region(headers);
        Alias alias = service.createAlias(region, runtimeName, text(request, "Name"),
                text(request, "RuntimeVersion"), text(request, "Description"),
                lifecycle(request.get("ReleaseLifecycle")));
        ObjectNode node = aliasNode(region, alias);
        node.remove("PendingDeletionAt");
        node.remove("Compatibility");
        node.put("State", "CREATING");
        return Response.status(201).entity(node).build();
    }

    @GET
    @Path("/runtimes/{runtimeName}/aliases")
    public Response listRuntimeAliases(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName) {
        String region = region(headers);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        for (Alias alias : service.listAliases(region, runtimeName)) {
            items.add(aliasNode(region, alias));
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/runtimes/{runtimeName}/aliases/{name}")
    public Response getRuntimeAlias(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                             @PathParam("name") String name) {
        String region = region(headers);
        return Response.ok(aliasNode(region, service.getAlias(region, runtimeName, name))).build();
    }

    @PUT
    @Path("/runtimes/{runtimeName}/aliases/{name}")
    public Response updateRuntimeAlias(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                @PathParam("name") String name, String body) {
        JsonNode request = parse(body);
        String region = region(headers);
        Alias alias = service.updateAlias(region, runtimeName, name, text(request, "RuntimeVersion"),
                text(request, "Description"), lifecycle(request.get("ReleaseLifecycle")));
        ObjectNode node = aliasNode(region, alias);
        node.remove("PendingDeletionAt");
        node.put("RuntimeName", alias.runtimeName);
        return Response.ok(node).build();
    }

    @DELETE
    @Path("/runtimes/{runtimeName}/aliases/{name}")
    public Response deleteRuntimeAlias(@Context HttpHeaders headers, @PathParam("runtimeName") String runtimeName,
                                @PathParam("name") String name,
                                @HeaderParam(RECOVERY_WINDOW_HEADER) Integer recoveryWindowInDays) {
        service.deleteAlias(region(headers), runtimeName, name, recoveryWindowInDays);
        return Response.status(202).build();
    }

    // ---------------------------------------------------------------- shapes

    private ObjectNode runtimeNode(Runtime runtime) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", runtime.arn);
        node.put("Name", runtime.name);
        if (runtime.description != null) node.put("Description", runtime.description);
        if (runtime.licenseInfo != null) node.put("LicenseInfo", runtime.licenseInfo);
        putLifecycle(node, runtime.releaseLifecycle);
        node.put("State", runtime.state);
        if (runtime.pendingDeletionAt != null) node.put("PendingDeletionAt", iso(runtime.pendingDeletionAt));
        node.put("Created", iso(runtime.created));
        node.put("LastModified", iso(runtime.lastModified));
        node.put("Id", runtime.id);
        return node;
    }

    private ObjectNode versionNode(Version version) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", version.name);
        node.put("Arn", version.arn);
        node.put("ResolvedUri", version.resolvedUri);
        node.put("State", version.state);
        if (version.pendingDeletionAt != null) node.put("PendingDeletionAt", iso(version.pendingDeletionAt));
        if (version.releaseNotes != null) node.put("ReleaseNotes", version.releaseNotes);
        ArrayNode platforms = node.putArray("Platforms");
        String digest = version.resolvedUri.substring(version.resolvedUri.indexOf('@') + 1);
        for (String architecture : version.architectures) {
            platforms.addObject()
                    .put("Architecture", "x86_64".equals(architecture) ? "amd64" : architecture)
                    .put("Os", "linux")
                    .put("Digest", digest);
        }
        putCompatibility(node, version.architectures);
        node.put("Created", iso(version.created));
        node.put("LastModified", iso(version.lastModified));
        return node;
    }

    private ObjectNode aliasNode(String region, Alias alias) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", alias.name);
        node.put("Arn", alias.arn);
        node.put("RuntimeVersion", alias.runtimeVersion);
        String runtimeArn = alias.arn.substring(0, alias.arn.lastIndexOf("/alias/"));
        node.put("RuntimeVersionArn", runtimeArn + "/version/" + alias.runtimeVersion);
        node.put("State", alias.state);
        if (alias.pendingDeletionAt != null) node.put("PendingDeletionAt", iso(alias.pendingDeletionAt));
        if (alias.description != null) node.put("Description", alias.description);
        Version version;
        try {
            version = service.getVersion(region, alias.runtimeName, alias.runtimeVersion);
        } catch (AwsException e) {
            version = null;
        }
        putCompatibility(node, version == null ? List.of() : version.architectures);
        // An alias without its own lifecycle reports its runtime's.
        Lifecycle lifecycle = alias.releaseLifecycle;
        if (lifecycle == null) {
            try {
                lifecycle = service.getRuntime(region, alias.runtimeName).releaseLifecycle;
            } catch (AwsException ignored) {
                // the runtime is gone; report none
            }
        }
        putLifecycle(node, lifecycle);
        node.put("Created", iso(alias.created));
        node.put("LastModified", iso(alias.lastModified));
        return node;
    }

    private static void putCompatibility(ObjectNode node, List<String> architectures) {
        ObjectNode compatibility = node.putObject("Compatibility");
        compatibility.putArray("IntegrationModels").add("RuntimeAPI");
        ArrayNode archs = compatibility.putArray("Architectures");
        architectures.forEach(archs::add);
        compatibility.put("ManagedInstances", "NotSupported");
    }

    private static void putLifecycle(ObjectNode node, Lifecycle lifecycle) {
        if (lifecycle == null) {
            return;
        }
        ObjectNode out = node.putObject("ReleaseLifecycle");
        if (lifecycle.releaseState != null) out.put("ReleaseState", lifecycle.releaseState);
        if (lifecycle.deprecationDate != null) out.put("DeprecationDate", lifecycle.deprecationDate);
        if (lifecycle.functionCreateBlockDate != null) out.put("FunctionCreateBlockDate", lifecycle.functionCreateBlockDate);
        if (lifecycle.functionUpdateBlockDate != null) out.put("FunctionUpdateBlockDate", lifecycle.functionUpdateBlockDate);
    }

    private static Lifecycle lifecycle(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        Lifecycle lifecycle = new Lifecycle();
        lifecycle.releaseState = text(node, "ReleaseState");
        lifecycle.deprecationDate = text(node, "DeprecationDate");
        lifecycle.functionCreateBlockDate = text(node, "FunctionCreateBlockDate");
        lifecycle.functionUpdateBlockDate = text(node, "FunctionUpdateBlockDate");
        return lifecycle;
    }

    private static Map<String, String> tags(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        node.properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        return tags;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static String iso(Instant when) {
        return DateTimeFormatter.ISO_INSTANT.format(
                (when == null ? Instant.now() : when).truncatedTo(ChronoUnit.MILLIS));
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", "Malformed request body", 400);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

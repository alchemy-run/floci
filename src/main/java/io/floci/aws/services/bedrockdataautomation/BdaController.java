package io.floci.aws.services.bedrockdataautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Bedrock Data Automation control plane REST API: {@code /blueprints}, {@code /data-automation-projects},
 * {@code /data-automation-libraries} and blueprint optimization. Resource ARNs travel as one path segment, and
 * contain slashes once decoded, so each route reads its ARN with the resource type's pattern. Tags go through
 * Bedrock's {@code /tagResource}, {@code /untagResource} and {@code /listTagsForResource}, which hand Data
 * Automation ARNs to {@link BdaService}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class BdaController {
    private static final String ARN = "arn:[^/:]+:bedrock:[^/:]*:[^/:]+:";
    private static final Pattern BLUEPRINT = Pattern.compile("^/?(" + ARN + "blueprint/[A-Za-z0-9_-]+)(/.*)?$");
    private static final Pattern PROJECT = Pattern.compile("^/?(" + ARN + "data-automation-project/[A-Za-z0-9_-]+)"
            + "(/.*)?$");
    private static final Pattern LIBRARY = Pattern.compile("^/?(" + ARN + "data-automation-library/[A-Za-z0-9_-]+)"
            + "(/.*)?$");
    private static final Pattern INGESTION_JOB = Pattern.compile("^/library-ingestion-jobs/(" + ARN
            + "data-automation-library-ingestion-job/[A-Za-z0-9_-]+)/?$");
    private static final Pattern ENTITIES = Pattern.compile("^/entityType/([A-Z_]+)/entities(?:/([^/]+))?/?$");

    private final BdaService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public BdaController(BdaService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @PUT
    @Path("/blueprints{rest: (/.*)?}")
    public Response putBlueprints(@Context HttpHeaders headers, @PathParam("rest") String rest, String body) {
        return route("PUT", "blueprints", rest, null, body, headers);
    }

    @POST
    @Path("/blueprints{rest: (/.*)?}")
    public Response postBlueprints(@Context HttpHeaders headers, @PathParam("rest") String rest, String body) {
        return route("POST", "blueprints", rest, null, body, headers);
    }

    @DELETE
    @Path("/blueprints{rest: (/.*)?}")
    public Response deleteBlueprints(@Context HttpHeaders headers, @PathParam("rest") String rest,
                                 @QueryParam("blueprintVersion") String version) {
        return route("DELETE", "blueprints", rest, version, null, headers);
    }

    @PUT
    @Path("/data-automation-projects{rest: (/.*)?}")
    public Response putProjects(@Context HttpHeaders headers, @PathParam("rest") String rest, String body) {
        return route("PUT", "data-automation-projects", rest, null, body, headers);
    }

    @POST
    @Path("/data-automation-projects{rest: (/.*)?}")
    public Response postProjects(@Context HttpHeaders headers, @PathParam("rest") String rest, String body) {
        return route("POST", "data-automation-projects", rest, null, body, headers);
    }

    @DELETE
    @Path("/data-automation-projects{rest: (/.*)?}")
    public Response deleteProjects(@Context HttpHeaders headers, @PathParam("rest") String rest,
                                 @QueryParam("blueprintVersion") String version) {
        return route("DELETE", "data-automation-projects", rest, version, null, headers);
    }

    @PUT
    @Path("/data-automation-libraries{rest: (/.*)?}")
    public Response putLibraries(@Context HttpHeaders headers, @PathParam("rest") String rest, String body) {
        return route("PUT", "data-automation-libraries", rest, null, body, headers);
    }

    @POST
    @Path("/data-automation-libraries{rest: (/.*)?}")
    public Response postLibraries(@Context HttpHeaders headers, @PathParam("rest") String rest, String body) {
        return route("POST", "data-automation-libraries", rest, null, body, headers);
    }

    @DELETE
    @Path("/data-automation-libraries{rest: (/.*)?}")
    public Response deleteLibraries(@Context HttpHeaders headers, @PathParam("rest") String rest,
                                 @QueryParam("blueprintVersion") String version) {
        return route("DELETE", "data-automation-libraries", rest, version, null, headers);
    }

    @POST
    @Path("/invokeBlueprintOptimizationAsync")
    public Response optimize(@Context HttpHeaders headers, String body) {
        return dispatch("InvokeBlueprintOptimizationAsync", null, null, null, null, body, headers);
    }

    @POST
    @Path("/getBlueprintOptimizationStatus/{arn: .+}")
    public Response optimizationStatus(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return dispatch("GetBlueprintOptimizationStatus", arn, null, null, null, null, headers);
    }

    /** Resolves a request on a resource collection to its operation, and the ARNs and segments its path names. */
    private Response route(String method, String root, String rest, String version, String body,
                           HttpHeaders headers) {
        if (isRoot(rest)) {
            String operation = switch (root + " " + method) {
                case "blueprints PUT" -> "CreateBlueprint";
                case "blueprints POST" -> "ListBlueprints";
                case "data-automation-projects PUT" -> "CreateDataAutomationProject";
                case "data-automation-projects POST" -> "ListDataAutomationProjects";
                case "data-automation-libraries PUT" -> "CreateDataAutomationLibrary";
                case "data-automation-libraries POST" -> "ListDataAutomationLibraries";
                default -> throw notFound("/" + root);
            };
            return dispatch(operation, null, null, null, null, body, headers);
        }
        Pattern pattern = switch (root) {
            case "blueprints" -> BLUEPRINT;
            case "data-automation-projects" -> PROJECT;
            default -> LIBRARY;
        };
        Matcher m = match(pattern, rest);
        String arn = m.group(1);
        String tail = tail(m);
        String key = root + " " + method + " " + (tail.isEmpty() ? "/" : tail);
        String operation = switch (key) {
            case "blueprints PUT /" -> "UpdateBlueprint";
            case "blueprints POST /" -> "GetBlueprint";
            case "blueprints DELETE /" -> "DeleteBlueprint";
            case "blueprints PUT /copy-stage" -> "CopyBlueprintStage";
            case "blueprints POST /versions" -> "CreateBlueprintVersion";
            case "data-automation-projects PUT /" -> "UpdateDataAutomationProject";
            case "data-automation-projects POST /" -> "GetDataAutomationProject";
            case "data-automation-projects DELETE /" -> "DeleteDataAutomationProject";
            case "data-automation-libraries PUT /" -> "UpdateDataAutomationLibrary";
            case "data-automation-libraries POST /" -> "GetDataAutomationLibrary";
            case "data-automation-libraries DELETE /" -> "DeleteDataAutomationLibrary";
            case "data-automation-libraries PUT /library-ingestion-jobs" -> "InvokeDataAutomationLibraryIngestionJob";
            case "data-automation-libraries POST /library-ingestion-jobs" -> "ListDataAutomationLibraryIngestionJobs";
            default -> null;
        };
        if (operation != null) {
            return dispatch(operation, arn, version, null, null, body, headers);
        }
        if ("data-automation-libraries".equals(root) && "POST".equals(method)) {
            Matcher job = INGESTION_JOB.matcher(tail);
            if (job.matches()) {
                return dispatch("GetDataAutomationLibraryIngestionJob", arn, null, job.group(1), null, body, headers);
            }
            Matcher entities = ENTITIES.matcher(tail);
            if (entities.matches()) {
                return dispatch(entities.group(2) == null ? "ListDataAutomationLibraryEntities"
                        : "GetDataAutomationLibraryEntity", arn, null, entities.group(1), entities.group(2), body,
                        headers);
            }
        }
        throw notFound(rest);
    }

    /** Runs an operation: {@code arn} is the resource's, {@code a} and {@code b} the further path segments. */
    private Response dispatch(String operation, String arn, String version, String a, String b, String body,
                              HttpHeaders headers) {
        String region = region(headers);
        return switch (operation) {
            case "CreateBlueprint" -> ok(service.createBlueprint(parse(body), region));
            case "ListBlueprints" -> ok(service.listBlueprints(parse(body), region));
            case "GetBlueprint" -> ok(service.getBlueprint(arn, parse(body), region));
            case "UpdateBlueprint" -> ok(service.updateBlueprint(arn, parse(body), region));
            case "CreateBlueprintVersion" -> ok(service.createBlueprintVersion(arn, parse(body), region));
            case "CopyBlueprintStage" -> {
                service.copyBlueprintStage(arn, parse(body), region);
                yield empty();
            }
            case "DeleteBlueprint" -> {
                service.deleteBlueprint(arn, version, region);
                yield empty();
            }
            case "CreateDataAutomationProject" -> ok(service.createProject(parse(body), region));
            case "ListDataAutomationProjects" -> ok(service.listProjects(parse(body), region));
            case "GetDataAutomationProject" -> ok(service.getProject(arn, parse(body), region));
            case "UpdateDataAutomationProject" -> ok(service.updateProject(arn, parse(body), region));
            case "DeleteDataAutomationProject" -> ok(service.deleteProject(arn, region));
            case "CreateDataAutomationLibrary" -> ok(service.createLibrary(parse(body), region));
            case "ListDataAutomationLibraries" -> ok(service.listLibraries(parse(body), region));
            case "GetDataAutomationLibrary" -> ok(service.getLibrary(arn, region));
            case "UpdateDataAutomationLibrary" -> ok(service.updateLibrary(arn, parse(body), region));
            case "DeleteDataAutomationLibrary" -> ok(service.deleteLibrary(arn, region));
            case "InvokeDataAutomationLibraryIngestionJob" -> ok(service.startIngestion(arn, parse(body), region));
            case "ListDataAutomationLibraryIngestionJobs" -> ok(service.listIngestionJobs(arn, parse(body), region));
            case "GetDataAutomationLibraryIngestionJob" -> ok(service.getIngestionJob(arn, a, region));
            case "ListDataAutomationLibraryEntities" -> ok(service.listEntities(arn, a, parse(body), region));
            case "GetDataAutomationLibraryEntity" -> ok(service.getEntity(arn, a, b, region));
            case "InvokeBlueprintOptimizationAsync" -> ok(service.startOptimization(parse(body), region));
            case "GetBlueprintOptimizationStatus" -> ok(service.optimizationStatus(arn, region));
            default -> throw notFound(operation);
        };
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static boolean isRoot(String rest) {
        return rest == null || rest.isEmpty() || "/".equals(rest);
    }

    private static Matcher match(Pattern pattern, String rest) {
        Matcher m = pattern.matcher(rest == null ? "" : rest);
        if (!m.matches()) {
            throw new AwsException("ValidationException", "The resource ARN in the path " + rest + " isn't valid.",
                    400);
        }
        return m;
    }

    private static String tail(Matcher m) {
        String tail = m.group(2) == null ? "" : m.group(2);
        return tail.endsWith("/") ? tail.substring(0, tail.length() - 1) : tail;
    }

    private static AwsException notFound(String path) {
        return new AwsException("UnknownOperationException", "No Bedrock Data Automation operation at " + path + ".",
                404);
    }

    private String region(HttpHeaders headers) {
        return regions.resolveRegion(headers);
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private Response empty() {
        return Response.ok(mapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode parsed = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            if (!parsed.isObject()) {
                throw new AwsException("ValidationException", "The request body must be a JSON object.", 400);
            }
            return parsed;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }
}

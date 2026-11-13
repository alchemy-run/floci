package io.floci.aws.services.observabilityadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/**
 * The CloudWatch Observability Admin REST API, routed here by {@link ObservabilityAdminRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(ObservabilityAdminRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class ObservabilityAdminController {

    /** Observability Admin's request constraints, worded as a ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/observabilityadmin-request-shapes.json", "Observability Admin request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return ObservabilityAdminStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return ObservabilityAdminStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return ObservabilityAdminStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return ObservabilityAdminStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return ObservabilityAdminStore.validation(path + " is required.");
                }
            });

    private final ObservabilityAdminService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public ObservabilityAdminController(ObservabilityAdminService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "observabilityadmin");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(ObservabilityAdminRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "CreateCentralizationRuleForOrganization" -> service.createCentralizationRuleForOrganization(input, region);
            case "CreateDatasetIntegration" -> service.createDatasetIntegration(input, region);
            case "CreateS3TableIntegration" -> service.createS3TableIntegration(input, region);
            case "CreateTelemetryPipeline" -> service.createTelemetryPipeline(input, region);
            case "CreateTelemetryRule" -> service.createTelemetryRule(input, region);
            case "CreateTelemetryRuleForOrganization" -> service.createTelemetryRuleForOrganization(input, region);
            case "DeleteCentralizationRuleForOrganization" -> service.deleteCentralizationRuleForOrganization(input, region);
            case "DeleteDatasetIntegration" -> service.deleteDatasetIntegration(input, region);
            case "DeleteS3TableIntegration" -> service.deleteS3TableIntegration(input, region);
            case "DeleteTelemetryPipeline" -> service.deleteTelemetryPipeline(input, region);
            case "DeleteTelemetryRule" -> service.deleteTelemetryRule(input, region);
            case "DeleteTelemetryRuleForOrganization" -> service.deleteTelemetryRuleForOrganization(input, region);
            case "GetCentralizationRuleForOrganization" -> service.getCentralizationRuleForOrganization(input, region);
            case "GetDatasetIntegration" -> service.getDatasetIntegration(input, region);
            case "GetS3TableIntegration" -> service.getS3TableIntegration(input, region);
            case "GetTelemetryEnrichmentStatus" -> service.getTelemetryEnrichmentStatus(input, region);
            case "GetTelemetryEvaluationStatus" -> service.getTelemetryEvaluationStatus(input, region);
            case "GetTelemetryEvaluationStatusForOrganization" -> service.getTelemetryEvaluationStatusForOrganization(input, region);
            case "GetTelemetryPipeline" -> service.getTelemetryPipeline(input, region);
            case "GetTelemetryRule" -> service.getTelemetryRule(input, region);
            case "GetTelemetryRuleForOrganization" -> service.getTelemetryRuleForOrganization(input, region);
            case "ListCentralizationRulesForOrganization" -> service.listCentralizationRulesForOrganization(input, region);
            case "ListDatasetIntegrations" -> service.listDatasetIntegrations(input, region);
            case "ListResourceTelemetry" -> service.listResourceTelemetry(input, region);
            case "ListResourceTelemetryForOrganization" -> service.listResourceTelemetryForOrganization(input, region);
            case "ListS3TableIntegrations" -> service.listS3TableIntegrations(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "ListTelemetryPipelines" -> service.listTelemetryPipelines(input, region);
            case "ListTelemetryRules" -> service.listTelemetryRules(input, region);
            case "ListTelemetryRulesForOrganization" -> service.listTelemetryRulesForOrganization(input, region);
            case "StartTelemetryEnrichment" -> service.startTelemetryEnrichment(input, region);
            case "StartTelemetryEvaluation" -> service.startTelemetryEvaluation(input, region);
            case "StartTelemetryEvaluationForOrganization" -> service.startTelemetryEvaluationForOrganization(input, region);
            case "StopTelemetryEnrichment" -> service.stopTelemetryEnrichment(input, region);
            case "StopTelemetryEvaluation" -> service.stopTelemetryEvaluation(input, region);
            case "StopTelemetryEvaluationForOrganization" -> service.stopTelemetryEvaluationForOrganization(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "TestTelemetryPipeline" -> service.testTelemetryPipeline(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateCentralizationRuleForOrganization" -> service.updateCentralizationRuleForOrganization(input, region);
            case "UpdateDatasetIntegration" -> service.updateDatasetIntegration(input, region);
            case "UpdateTelemetryPipeline" -> service.updateTelemetryPipeline(input, region);
            case "UpdateTelemetryRule" -> service.updateTelemetryRule(input, region);
            case "UpdateTelemetryRuleForOrganization" -> service.updateTelemetryRuleForOrganization(input, region);
            case "ValidateTelemetryPipelineConfiguration" -> service.validateTelemetryPipelineConfiguration(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}

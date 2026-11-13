package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.util.List;
import java.util.Set;

/** JSON 1.0 dispatch for AWS HealthLake ({@code X-Amz-Target: HealthLake.<Action>}). */
@ApplicationScoped
public class HealthLakeJsonHandler {

    /** HealthLake's request constraints; it reports a violation as ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/healthlake-request-shapes.json",
            "HealthLake request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long").contains(type)
                            ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private final HealthLakeService service;
    private final HealthLakeTransformService transform;
    private final ObjectMapper mapper;

    @Inject
    public HealthLakeJsonHandler(HealthLakeService service, HealthLakeTransformService transform,
                                 ObjectMapper mapper) {
        this.service = service;
        this.transform = transform;
        this.mapper = mapper;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return HealthLakeService.validation("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        return switch (action) {
            case "UpdateProfileWithAgent" -> agent(request, region);
            case "CreateFHIRDatastore" -> ok(service.createDatastore(request, region));
            case "DescribeFHIRDatastore" -> ok(service.describeDatastore(request, region));
            case "ListFHIRDatastores" -> ok(service.listDatastores(request, region));
            case "UpdateFHIRDatastore" -> ok(service.updateDatastore(request, region));
            case "DeleteFHIRDatastore" -> ok(service.deleteDatastore(request, region));
            case "RestoreFHIRDatastore" -> ok(service.restoreDatastore(request, region));
            case "StartFHIRImportJob" -> ok(service.startImportJob(request, region));
            case "StartFHIRExportJob" -> ok(service.startExportJob(request, region));
            case "DescribeFHIRImportJob" -> ok(service.describeImportJob(request, region));
            case "DescribeFHIRExportJob" -> ok(service.describeExportJob(request, region));
            case "ListFHIRImportJobs" -> ok(service.listImportJobs(request, region));
            case "ListFHIRExportJobs" -> ok(service.listExportJobs(request, region));
            case "ListTagsForResource" -> ok(service.listTags(request, region));
            case "TagResource" -> ok(service.tagResource(request, region));
            case "UntagResource" -> ok(service.untagResource(request, region));
            case "CreateDataTransformationProfile" -> ok(transform.createProfile(request, region));
            case "GetDataTransformationProfile" -> ok(transform.getProfile(request, region));
            case "UpdateDataTransformationProfile" -> ok(transform.updateProfile(request, region));
            case "PublishDataTransformationProfile" -> ok(transform.publishProfile(request, region));
            case "ListDataTransformationProfiles" -> ok(transform.listProfiles(request, region));
            case "ListDataTransformationProfileVersions" -> ok(transform.listProfileVersions(request, region));
            case "DeleteDataTransformationProfile" -> ok(transform.deleteProfile(request, region));
            case "StartDataTransformationJob" -> ok(transform.startJob(request, region));
            case "DescribeDataTransformationJob" -> ok(transform.describeJob(request, region));
            case "ListDataTransformationJobs" -> ok(transform.listJobs(request, region));
            default -> Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: HealthLake." + action)).build();
        };
    }

    private static Response ok(ObjectNode result) {
        return Response.ok(result).build();
    }

    /** An agent turn, streamed as the AgentResponse event stream: progress and text events, then its end. */
    private Response agent(JsonNode request, String region) {
        HealthLakeTransformService.AgentTurn turn = transform.updateProfileWithAgent(request, region);
        StreamingOutput stream = out -> turn.events().forEach(event ->
                AwsEventStreamWriter.writeEvent(mapper, out, event.getKey(), event.getValue()));
        return Response.ok(stream).type("application/vnd.amazon.eventstream").build();
    }
}

package io.floci.aws.services.mediaconvert;

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
 * The AWS Elemental MediaConvert REST API, routed here by {@link MediaConvertRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}), which also rename its lowerCamel wire members to the model's.
 */
@Path(MediaConvertRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MediaConvertController {

    /** MediaConvert's request constraints, worded as a BadRequestException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/mediaconvert-request-shapes.json", "MediaConvert request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return MediaConvertStore.badRequest(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return MediaConvertStore.badRequest(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return MediaConvertStore.badRequest(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return MediaConvertStore.badRequest(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return MediaConvertStore.badRequest(path + " is required.");
                }
            });

    private final MediaConvertResources resources;
    private final MediaConvertJobs jobs;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public MediaConvertController(MediaConvertResources resources, MediaConvertJobs jobs, RegionResolver regions,
                                  ObjectMapper mapper) {
        this.resources = resources;
        this.jobs = jobs;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "mediaconvert");
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
        String path = uri.getRequestUri().getRawPath().substring(MediaConvertRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "AssociateCertificate" -> resources.associateCertificate(input, region);
            case "CancelJob" -> jobs.cancelJob(input, region);
            case "CreateJob" -> jobs.createJob(input, region);
            case "CreateJobTemplate" -> resources.createJobTemplate(input, region);
            case "CreatePreset" -> resources.createPreset(input, region);
            case "CreateQueue" -> resources.createQueue(input, region);
            case "CreateResourceShare" -> jobs.createResourceShare(input, region);
            case "DeleteJobTemplate" -> resources.deleteJobTemplate(input, region);
            case "DeletePolicy" -> resources.deletePolicy(input, region);
            case "DeletePreset" -> resources.deletePreset(input, region);
            case "DeleteQueue" -> resources.deleteQueue(input, region);
            case "DescribeEndpoints" -> resources.describeEndpoints(input, region);
            case "DisassociateCertificate" -> resources.disassociateCertificate(input, region);
            case "GetJob" -> jobs.getJob(input, region);
            case "GetJobTemplate" -> resources.getJobTemplate(input, region);
            case "GetJobsQueryResults" -> jobs.getJobsQueryResults(input, region);
            case "GetPolicy" -> resources.getPolicy(input, region);
            case "GetPreset" -> resources.getPreset(input, region);
            case "GetQueue" -> resources.getQueue(input, region);
            case "ListJobTemplates" -> resources.listJobTemplates(input, region);
            case "ListJobs" -> jobs.listJobs(input, region);
            case "ListPresets" -> resources.listPresets(input, region);
            case "ListQueues" -> resources.listQueues(input, region);
            case "ListTagsForResource" -> resources.listTagsForResource(input, region);
            case "ListVersions" -> resources.listVersions(input, region);
            case "Probe" -> jobs.probe(input, region);
            case "PutPolicy" -> resources.putPolicy(input, region);
            case "SearchJobs" -> jobs.searchJobs(input, region);
            case "StartJobsQuery" -> jobs.startJobsQuery(input, region);
            case "TagResource" -> resources.tagResource(input, region);
            case "UntagResource" -> resources.untagResource(input, region);
            case "UpdateJobTemplate" -> resources.updateJobTemplate(input, region);
            case "UpdatePreset" -> resources.updatePreset(input, region);
            case "UpdateQueue" -> resources.updateQueue(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}

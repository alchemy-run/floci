package io.floci.aws.services.medicalimaging;

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
 * The AWS HealthImaging REST API, routed here by {@link MedicalImagingRouteFilter} and matched to operations by its
 * HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(MedicalImagingRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MedicalImagingController {

    /** HealthImaging's request constraints, worded as a ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/medicalimaging-request-shapes.json", "HealthImaging request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return MedicalImagingStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return MedicalImagingStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return MedicalImagingStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return MedicalImagingStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return MedicalImagingStore.validation(path + " is required.");
                }
            });

    private final MedicalImagingService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public MedicalImagingController(MedicalImagingService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "medicalimaging");
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
        String path = uri.getRequestUri().getRawPath().substring(MedicalImagingRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "CopyImageSet" -> service.copyImageSet(input, region);
            case "CreateDatastore" -> service.createDatastore(input, region);
            case "DeleteDatastore" -> service.deleteDatastore(input, region);
            case "DeleteImageSet" -> service.deleteImageSet(input, region);
            case "GetDICOMImportJob" -> service.getDICOMImportJob(input, region);
            case "GetDatastore" -> service.getDatastore(input, region);
            case "GetImageFrame" -> service.getImageFrame(input, region);
            case "GetImageSet" -> service.getImageSet(input, region);
            case "GetImageSetMetadata" -> service.getImageSetMetadata(input, region);
            case "ListDICOMImportJobs" -> service.listDICOMImportJobs(input, region);
            case "ListDatastores" -> service.listDatastores(input, region);
            case "ListImageSetVersions" -> service.listImageSetVersions(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "SearchImageSets" -> service.searchImageSets(input, region);
            case "StartDICOMImportJob" -> service.startDICOMImportJob(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateImageSetMetadata" -> service.updateImageSetMetadata(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}

package io.github.hectorvent.floci.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for AWS HealthLake. Dispatched from {@code AwsJsonController}
 * under the {@code HealthLake.} target prefix.
 *
 * @see <a href="https://docs.aws.amazon.com/healthlake/latest/APIReference/API_Operations.html">HealthLake API</a>
 */
@ApplicationScoped
public class HealthLakeJsonHandler {

    private static final String TARGET_PREFIX = "HealthLake.";

    private final HealthLakeService service;
    private final ObjectMapper objectMapper;

    @Inject
    public HealthLakeJsonHandler(HealthLakeService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateFHIRDatastore" -> ok(service.createDatastore(body, region));
            case "DescribeFHIRDatastore" -> ok(service.describeDatastore(body));
            case "UpdateFHIRDatastore" -> ok(service.updateDatastore(body));
            case "DeleteFHIRDatastore" -> ok(service.deleteDatastore(body));
            case "ListFHIRDatastores" -> ok(service.listDatastores(body));
            case "StartFHIRImportJob" -> ok(service.startImportJob(body, region));
            case "DescribeFHIRImportJob" -> ok(service.describeImportJob(body));
            case "ListFHIRImportJobs" -> ok(service.listImportJobs(body));
            case "StartFHIRExportJob" -> ok(service.startExportJob(body, region));
            case "DescribeFHIRExportJob" -> ok(service.describeExportJob(body));
            case "ListFHIRExportJobs" -> ok(service.listExportJobs(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}

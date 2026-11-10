package io.floci.aws.services.comprehendmedical;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** Amazon Comprehend Medical JSON 1.1 handler, under the {@code ComprehendMedical_20181030.} target prefix. */
@ApplicationScoped
public class ComprehendMedicalJsonHandler {
    private final ComprehendMedicalService service;

    @Inject
    public ComprehendMedicalJsonHandler(ComprehendMedicalService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            return switch (action) {
                case "DetectEntities", "DetectEntitiesV2", "DetectPHI", "InferICD10CM", "InferRxNorm",
                     "InferSNOMEDCT" -> Response.ok(service.analyze(action, request)).build();
                case "StartEntitiesDetectionV2Job", "StartICD10CMInferenceJob", "StartPHIDetectionJob",
                     "StartRxNormInferenceJob", "StartSNOMEDCTInferenceJob" ->
                        Response.ok(service.start(ComprehendMedicalService.Family.of(action), request, region)).build();
                case "DescribeEntitiesDetectionV2Job", "DescribeICD10CMInferenceJob", "DescribePHIDetectionJob",
                     "DescribeRxNormInferenceJob", "DescribeSNOMEDCTInferenceJob" ->
                        Response.ok(service.describe(ComprehendMedicalService.Family.of(action), request)).build();
                case "ListEntitiesDetectionV2Jobs", "ListICD10CMInferenceJobs", "ListPHIDetectionJobs",
                     "ListRxNormInferenceJobs", "ListSNOMEDCTInferenceJobs" ->
                        Response.ok(service.list(ComprehendMedicalService.Family.of(action), request, region)).build();
                case "StopEntitiesDetectionV2Job", "StopICD10CMInferenceJob", "StopPHIDetectionJob",
                     "StopRxNormInferenceJob", "StopSNOMEDCTInferenceJob" ->
                        Response.ok(service.stop(ComprehendMedicalService.Family.of(action), request)).build();
                default -> Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                        "Operation " + action + " is not supported.")).build();
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }
}

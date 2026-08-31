package io.github.hectorvent.floci.services.comprehendmedical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.comprehendmedical.model.ComprehendMedicalJob.Family;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for Amazon Comprehend Medical.
 * Dispatches {@code X-Amz-Target: ComprehendMedical_20181030.*} actions.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend-medical/latest/api/API_Operations.html">Comprehend Medical API</a>
 */
@ApplicationScoped
public class ComprehendMedicalJsonHandler {

    private final ComprehendMedicalService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ComprehendMedicalJsonHandler(ComprehendMedicalService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "DetectEntities", "DetectEntitiesV2" -> service.detectEntitiesV2(body);
            case "DetectPHI" -> service.detectPhi(body);
            case "InferICD10CM" -> service.inferIcd10cm(body);
            case "InferRxNorm" -> service.inferRxNorm(body);
            case "InferSNOMEDCT" -> service.inferSnomedCt(body);
            case "StartEntitiesDetectionV2Job" -> service.startJob(Family.ENTITIES_V2, body);
            case "StartICD10CMInferenceJob" -> service.startJob(Family.ICD10CM, body);
            case "StartPHIDetectionJob" -> service.startJob(Family.PHI, body);
            case "StartRxNormInferenceJob" -> service.startJob(Family.RXNORM, body);
            case "StartSNOMEDCTInferenceJob" -> service.startJob(Family.SNOMEDCT, body);
            case "DescribeEntitiesDetectionV2Job" -> service.describeJob(Family.ENTITIES_V2, body);
            case "DescribeICD10CMInferenceJob" -> service.describeJob(Family.ICD10CM, body);
            case "DescribePHIDetectionJob" -> service.describeJob(Family.PHI, body);
            case "DescribeRxNormInferenceJob" -> service.describeJob(Family.RXNORM, body);
            case "DescribeSNOMEDCTInferenceJob" -> service.describeJob(Family.SNOMEDCT, body);
            case "ListEntitiesDetectionV2Jobs" -> service.listJobs(Family.ENTITIES_V2, body);
            case "ListICD10CMInferenceJobs" -> service.listJobs(Family.ICD10CM, body);
            case "ListPHIDetectionJobs" -> service.listJobs(Family.PHI, body);
            case "ListRxNormInferenceJobs" -> service.listJobs(Family.RXNORM, body);
            case "ListSNOMEDCTInferenceJobs" -> service.listJobs(Family.SNOMEDCT, body);
            case "StopEntitiesDetectionV2Job" -> service.stopJob(Family.ENTITIES_V2, body);
            case "StopICD10CMInferenceJob" -> service.stopJob(Family.ICD10CM, body);
            case "StopPHIDetectionJob" -> service.stopJob(Family.PHI, body);
            case "StopRxNormInferenceJob" -> service.stopJob(Family.RXNORM, body);
            case "StopSNOMEDCTInferenceJob" -> service.stopJob(Family.SNOMEDCT, body);
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                    "ComprehendMedical_20181030." + action);
        };
    }
}

package io.github.hectorvent.floci.services.rekognition;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Rekognition.
 * Dispatches {@code X-Amz-Target: RekognitionService.*} actions to {@link RekognitionService}.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/Welcome.html">Rekognition API</a>
 */
@ApplicationScoped
public class RekognitionJsonHandler {

    private static final Logger LOG = Logger.getLogger(RekognitionJsonHandler.class);

    private final RekognitionService service;

    @Inject
    public RekognitionJsonHandler(RekognitionService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Rekognition action: {0}", action);
        JsonNode body = request == null ? null : request;
        return switch (action) {
            case "DetectLabels" -> ok(service.detectLabels(body));
            case "DetectFaces" -> ok(service.detectFaces(body));
            case "DetectModerationLabels" -> ok(service.detectModerationLabels(body));
            case "DetectText" -> ok(service.detectText(body));
            case "DetectProtectiveEquipment" -> ok(service.detectProtectiveEquipment(body));
            case "RecognizeCelebrities" -> ok(service.recognizeCelebrities(body));
            case "CompareFaces" -> ok(service.compareFaces(body));
            case "GetCelebrityInfo" -> ok(service.getCelebrityInfo(body));
            case "CreateCollection" -> ok(service.createCollection(body, region));
            case "DeleteCollection" -> ok(service.deleteCollection(body));
            case "DescribeCollection" -> ok(service.describeCollection(body));
            case "ListCollections" -> ok(service.listCollections());
            case "IndexFaces" -> ok(service.indexFaces(body));
            case "ListFaces" -> ok(service.listFaces(body));
            case "DeleteFaces" -> ok(service.deleteFaces(body));
            case "SearchFaces" -> ok(service.searchFaces(body));
            case "SearchFacesByImage" -> ok(service.searchFacesByImage(body));
            case "CreateUser" -> ok(service.createUser(body));
            case "DeleteUser" -> ok(service.deleteUser(body));
            case "ListUsers" -> ok(service.listUsers(body));
            case "AssociateFaces" -> ok(service.associateFaces(body));
            case "DisassociateFaces" -> ok(service.disassociateFaces(body));
            case "SearchUsers" -> ok(service.searchUsers(body));
            case "SearchUsersByImage" -> ok(service.searchUsersByImage(body));
            case "CreateFaceLivenessSession" -> ok(service.createFaceLivenessSession());
            case "GetFaceLivenessSessionResults" -> ok(service.getFaceLivenessSessionResults(body));
            case "StartCelebrityRecognition" -> ok(service.startVideoJob("CELEBRITY_RECOGNITION", body));
            case "GetCelebrityRecognition" -> ok(service.getVideoJob("CELEBRITY_RECOGNITION", body));
            case "StartContentModeration" -> ok(service.startVideoJob("CONTENT_MODERATION", body));
            case "GetContentModeration" -> ok(service.getVideoJob("CONTENT_MODERATION", body));
            case "StartFaceDetection" -> ok(service.startVideoJob("FACE_DETECTION", body));
            case "GetFaceDetection" -> ok(service.getVideoJob("FACE_DETECTION", body));
            case "StartFaceSearch" -> ok(service.startFaceSearch(body));
            case "GetFaceSearch" -> ok(service.getVideoJob("FACE_SEARCH", body));
            case "StartLabelDetection" -> ok(service.startVideoJob("LABEL_DETECTION", body));
            case "GetLabelDetection" -> ok(service.getVideoJob("LABEL_DETECTION", body));
            case "StartPersonTracking" -> ok(service.startVideoJob("PERSON_TRACKING", body));
            case "GetPersonTracking" -> ok(service.getVideoJob("PERSON_TRACKING", body));
            case "StartSegmentDetection" -> ok(service.startVideoJob("SEGMENT_DETECTION", body));
            case "GetSegmentDetection" -> ok(service.getVideoJob("SEGMENT_DETECTION", body));
            case "StartTextDetection" -> ok(service.startVideoJob("TEXT_DETECTION", body));
            case "GetTextDetection" -> ok(service.getVideoJob("TEXT_DETECTION", body));
            case "StartMediaAnalysisJob" -> ok(service.startMediaAnalysisJob(body));
            case "GetMediaAnalysisJob" -> ok(service.getMediaAnalysisJob(body));
            case "ListMediaAnalysisJobs" -> ok(service.listMediaAnalysisJobs());
            case "ListStreamProcessors" -> ok(service.listStreamProcessors());
            case "DescribeStreamProcessor" -> ok(service.describeStreamProcessor(body));
            case "StartStreamProcessor" -> ok(service.startStreamProcessor(body));
            case "StopStreamProcessor" -> ok(service.stopStreamProcessor(body));
            case "DetectCustomLabels" -> ok(service.detectCustomLabels(body));
            case "DescribeProjects" -> ok(service.describeProjects());
            case "DescribeProjectVersions" -> ok(service.describeProjectVersions(body));
            case "StartProjectVersion" -> ok(service.startProjectVersion(body));
            case "StopProjectVersion" -> ok(service.stopProjectVersion(body));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: RekognitionService." + action))
                    .build();
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}


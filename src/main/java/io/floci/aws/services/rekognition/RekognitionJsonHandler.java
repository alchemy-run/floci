package io.floci.aws.services.rekognition;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.function.Predicate;
/**
 * JSON 1.1 handler for Amazon Rekognition API operations.
 * Dispatches X-Amz-Target: RekognitionService.* actions to {@link RekognitionService}.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/Welcome.html">Rekognition API Reference</a>
 */
@ApplicationScoped
public class RekognitionJsonHandler {
    private static final Logger LOG = Logger.getLogger(RekognitionJsonHandler.class);
    private final RekognitionService rekognitionService;
    private final RekognitionImageValidator imageValidator;
    private final RekognitionCollectionService collections;
    private final RekognitionVideoService video;
    @Inject
    public RekognitionJsonHandler(RekognitionService rekognitionService, RekognitionImageValidator imageValidator,
                                  RekognitionCollectionService collections, RekognitionVideoService video) {
        this.rekognitionService = rekognitionService;
        this.imageValidator = imageValidator;
        this.collections = collections;
        this.video = video;
    }
    /**
     * Dispatches Rekognition actions received via the AwsJson11Controller: image
     * analysis to {@link RekognitionService}, collections, users, liveness sessions,
     * projects, stream processors, and tags to {@link RekognitionCollectionService},
     * and stored-video and media analysis jobs to {@link RekognitionVideoService}.
     * Model training (datasets, project versions) is not emulated.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Rekognition action: {0}", action);
        return switch (action) {
            case "DetectLabels" -> rekognitionService.detectLabels(validImage(action, request, region));
            case "DetectFaces" -> rekognitionService.detectFaces(validImage(action, request, region));
            case "DetectText" -> rekognitionService.detectText(validImage(action, request, region));
            case "CompareFaces" -> {
                String sourceKey = requireImage(request, "SourceImage");
                requireImage(request, "TargetImage");
                imageValidator.validate(action, request, region, "SourceImage", "TargetImage");
                yield rekognitionService.compareFaces(sourceKey);
            }
            case "DetectModerationLabels" -> rekognitionService.detectModerationLabels(validImage(action, request, region));
            case "DetectProtectiveEquipment" -> rekognitionService.detectProtectiveEquipment(
                    validImage(action, request, region));
            case "RecognizeCelebrities" -> rekognitionService.recognizeCelebrities(validImage(action, request, region));
            case "GetCelebrityInfo" -> rekognitionService.getCelebrityInfo(RekognitionCollectionService.text(request, "Id"));
            case "CreateCollection" -> ok(collections.createCollection(request, region));
            case "DescribeCollection" -> ok(collections.describeCollection(request, region));
            case "ListCollections" -> ok(collections.listCollections(request, region));
            case "DeleteCollection" -> ok(collections.deleteCollection(request, region));
            case "IndexFaces" -> ok(collections.indexFaces(request, region, validImage(action, request, region)));
            case "ListFaces" -> ok(collections.listFaces(request, region));
            case "DeleteFaces" -> ok(collections.deleteFaces(request, region));
            case "SearchFaces" -> ok(collections.searchFaces(request, region));
            case "SearchFacesByImage" -> ok(collections.searchFacesByImage(request, region,
                    validImage(action, request, region)));
            case "CreateUser" -> ok(collections.createUser(request, region));
            case "DeleteUser" -> ok(collections.deleteUser(request, region));
            case "ListUsers" -> ok(collections.listUsers(request, region));
            case "AssociateFaces" -> ok(collections.associateFaces(request, region));
            case "DisassociateFaces" -> ok(collections.disassociateFaces(request, region));
            case "SearchUsers" -> ok(collections.searchUsers(request, region));
            case "SearchUsersByImage" -> ok(collections.searchUsersByImage(request, region,
                    validImage(action, request, region)));
            case "CreateFaceLivenessSession" -> ok(collections.createFaceLivenessSession(request, region));
            case "GetFaceLivenessSessionResults" -> ok(collections.getFaceLivenessSessionResults(request));
            case "CreateProject" -> ok(collections.createProject(request, region));
            case "DeleteProject" -> ok(collections.deleteProject(request, region));
            case "DescribeProjects" -> ok(collections.describeProjects(request, region));
            case "DescribeProjectVersions" -> ok(collections.describeProjectVersions(request, region));
            case "DetectCustomLabels" -> {
                validImage(action, request, region);
                yield ok(collections.unknownProjectVersion(request));
            }
            case "StartProjectVersion", "StopProjectVersion" -> ok(collections.unknownProjectVersion(request));
            case "CreateStreamProcessor" -> ok(collections.createStreamProcessor(request, region));
            case "DescribeStreamProcessor" -> ok(collections.describeStreamProcessor(request, region));
            case "ListStreamProcessors" -> ok(collections.listStreamProcessors(request, region));
            case "StartStreamProcessor" -> ok(collections.startStreamProcessor(request, region));
            case "StopStreamProcessor" -> ok(collections.stopStreamProcessor(request, region));
            case "DeleteStreamProcessor" -> ok(collections.deleteStreamProcessor(request, region));
            case "TagResource" -> ok(collections.tagResource(request));
            case "UntagResource" -> ok(collections.untagResource(request));
            case "ListTagsForResource" -> ok(collections.listTagsForResource(request));
            case "StartCelebrityRecognition", "StartContentModeration", "StartFaceDetection", "StartFaceSearch",
                    "StartLabelDetection", "StartPersonTracking", "StartSegmentDetection", "StartTextDetection"
                    -> ok(video.start(RekognitionVideoService.Family.of(action), request, region));
            case "GetCelebrityRecognition", "GetContentModeration", "GetFaceDetection", "GetFaceSearch",
                    "GetLabelDetection", "GetPersonTracking", "GetSegmentDetection", "GetTextDetection"
                    -> ok(video.get(RekognitionVideoService.Family.of(action), request));
            case "StartMediaAnalysisJob" -> ok(video.startMediaAnalysisJob(request, region));
            case "GetMediaAnalysisJob" -> ok(video.getMediaAnalysisJob(request));
            case "ListMediaAnalysisJobs" -> ok(video.listMediaAnalysisJobs(request, region));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: RekognitionService." + action))
                    .build();
        };
    }
    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    /** The image's mock-response key, once the image has the shape and content Rekognition accepts. */
    private String validImage(String action, JsonNode request, String region) {
        String mockKey = requireImage(request, "Image");
        imageValidator.validate(action, request, region, "Image");
        return mockKey;
    }

    /**
     * The Image shape is required and must carry Bytes or S3Object, matching AWS's own required-member
     * and one-of-Bytes-or-S3Object modeling for Image/SourceImage/TargetImage. Returns
     * an optional "Bucket/Name" mock-response lookup key when S3Object is present (null
     * for a Bytes-backed image, which has no such key — mock lookup then simply doesn't
     * apply, same as always).
     */
    private String requireImage(JsonNode request, String field) {
        JsonNode image = request == null ? null : request.get(field);
        if (image == null || image.isNull()) {
            throw new AwsException("InvalidParameterException", field + " is a required field.", 400);
        }
        if (!image.isObject()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "' is not a structure.", 400);
        }
        requireCorrectTypeIfPresent(image, "Bytes", JsonNode::isTextual, field);
        requireCorrectTypeIfPresent(image, "S3Object", JsonNode::isObject, field);
        if (!image.hasNonNull("Bytes") && !image.hasNonNull("S3Object")) {
            throw new AwsException("InvalidParameterException",
                    field + " must specify Bytes or S3Object.", 400);
        }
        return extractS3Key(image.get("S3Object"));
    }
    private String extractS3Key(JsonNode s3Object) {
        if (s3Object == null || !s3Object.isObject()) {
            return null;
        }
        JsonNode bucket = s3Object.get("Bucket");
        JsonNode name = s3Object.get("Name");
        if (bucket == null || !bucket.isTextual() || name == null || !name.isTextual()) {
            return null;
        }
        return bucket.asText() + "/" + name.asText();
    }
    /**
     * Bytes (a blob shape, base64-encoded string on the wire) and S3Object (a structure)
     * must each carry their modeled JSON type when present. Content-level correctness
     * (valid base64, S3Object.Bucket/Name presence: S3Object has no required members
     * in the modeled shape) is left to {@link RekognitionImageValidator}, which checks the
     * image itself the way Rekognition does.
     */
    private void requireCorrectTypeIfPresent(JsonNode image, String member, Predicate<JsonNode> isCorrectType, String field) {
        JsonNode value = image.get(member);
        if (value != null && !value.isNull() && !isCorrectType.test(value)) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "." + member + "' has the wrong type.", 400);
        }
    }
}

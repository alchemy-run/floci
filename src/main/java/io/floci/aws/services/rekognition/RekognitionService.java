package io.floci.aws.services.rekognition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Optional;
/**
 * Dummy response builder for Amazon Rekognition. Stateless: every action returns a fixed but
 * AWS-shaped response by default, whatever the image shows. The image itself has already been
 * checked by {@link RekognitionImageValidator}, so an image Rekognition would refuse never
 * reaches this class.
 * <p>
 * DetectLabels always returns one canned label, as every photo has at least one.
 * Text, face, person, celebrity, and moderation operations return nothing detected
 * by default — an honest response,
 * rather than fabricating biometric attributes or a moderation flag for content that
 * was never analyzed. Where Rekognition refuses an image without a face
 * (CompareFaces), so does Floci.
 * <p>
 * Callers can override the default stub per exact "Bucket/Name" S3Object key via
 * {@link AiMockConfigLoader} — see {@code docs/services/rekognition.md}
 * "Mock Responses". CompareFaces keys off SourceImage only (TargetImage has no
 * independent key in this scheme).
 * <p>
 * Real detection logic is a planned follow-up; see the tracking issue for scope.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/Welcome.html">Rekognition API Reference</a>
 */
@ApplicationScoped
public class RekognitionService {
    static final String MODEL_VERSION = "1.0";
    private static final String SERVICE_KEY = "rekognition";
    private final ObjectMapper objectMapper;
    private final AiMockConfigLoader mockConfigLoader;
    @Inject
    public RekognitionService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader) {
        this.objectMapper = objectMapper;
        this.mockConfigLoader = mockConfigLoader;
    }
    /**
     * DetectLabels — always returns a single stub label.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectLabels.html
     */
    public Response detectLabels(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectLabels");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode labels = root.putArray("Labels");
        ObjectNode label = labels.addObject();
        label.put("Name", "Floci");
        label.put("Confidence", 99.9);
        root.put("LabelModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    /**
     * DetectFaces — always reports no faces found.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectFaces.html
     */
    public Response detectFaces(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectFaces");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FaceDetails");
        return Response.ok(root).build();
    }
    /**
     * DetectText — reports no text unless mocked: Floci reads no pixels, and
     * inventing a line would claim text the image may not have.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectText.html
     */
    public Response detectText(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectText");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("TextDetections");
        root.put("TextModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    /**
     * CompareFaces — Floci finds no face in an image it isn't told about, and
     * Rekognition refuses to compare when the source image has no face, so an
     * unmocked comparison is {@code InvalidParameterException}.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_CompareFaces.html
     */
    public Response compareFaces(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "CompareFaces");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        throw new AwsException("InvalidParameterException", "There are no faces in the source image.", 400);
    }

    /**
     * DetectProtectiveEquipment — no persons unless mocked.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectProtectiveEquipment.html
     */
    public Response detectProtectiveEquipment(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectProtectiveEquipment");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("ProtectiveEquipmentModelVersion", MODEL_VERSION);
        root.putArray("Persons");
        ObjectNode summary = root.putObject("Summary");
        summary.putArray("PersonsWithRequiredEquipment");
        summary.putArray("PersonsWithoutRequiredEquipment");
        summary.putArray("PersonsIndeterminate");
        return Response.ok(root).build();
    }

    /**
     * RecognizeCelebrities — no celebrities unless mocked.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_RecognizeCelebrities.html
     */
    public Response recognizeCelebrities(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "RecognizeCelebrities");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("CelebrityFaces");
        root.putArray("UnrecognizedFaces");
        return Response.ok(root).build();
    }

    /**
     * GetCelebrityInfo — a celebrity id is answered from the mock config
     * (keyed by the id); Floci knows no celebrities otherwise.
     */
    public Response getCelebrityInfo(String id) {
        if (id == null || id.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Id is a required field.", 400);
        }
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, id, "GetCelebrityInfo");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        throw new AwsException("ResourceNotFoundException", "Celebrity " + id + " was not found.", 400);
    }
    /**
     * DetectModerationLabels — always reports no moderation labels found.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectModerationLabels.html
     */
    public Response detectModerationLabels(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectModerationLabels");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("ModerationLabels");
        root.put("ModerationModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
}

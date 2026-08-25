package io.github.hectorvent.floci.services.b2bi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.b2bi.model.B2biCapability;
import io.github.hectorvent.floci.services.b2bi.model.B2biPartnership;
import io.github.hectorvent.floci.services.b2bi.model.B2biProfile;
import io.github.hectorvent.floci.services.b2bi.model.B2biTransformer;
import io.github.hectorvent.floci.services.b2bi.model.TransformerJob;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS B2B Data Interchange (B2BI) — transformers, capabilities, profiles, partnerships, mapping helpers, and jobs.
 */
@ApplicationScoped
public class B2biService {

    private static final Logger LOG = Logger.getLogger(B2biService.class);
    static final String SERVICE = "b2bi";
    private static final int MAX_PROFILES = 5;

    private final StorageBackend<String, B2biTransformer> transformers;
    private final StorageBackend<String, B2biCapability> capabilities;
    private final StorageBackend<String, TransformerJob> jobs;
    private final StorageBackend<String, B2biProfile> profiles;
    private final StorageBackend<String, B2biPartnership> partnerships;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final S3Service s3Service;
    private final EventBridgeService eventBridgeService;

    @Inject
    Instance<CloudWatchLogsService> logsService;

    @Inject
    public B2biService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            ObjectMapper mapper,
            S3Service s3Service,
            EventBridgeService eventBridgeService) {
        this(storageFactory.create("b2bi", "b2bi-transformers.json",
                        new TypeReference<Map<String, B2biTransformer>>() {}),
                storageFactory.create("b2bi", "b2bi-capabilities.json",
                        new TypeReference<Map<String, B2biCapability>>() {}),
                storageFactory.create("b2bi", "b2bi-transformer-jobs.json",
                        new TypeReference<Map<String, TransformerJob>>() {}),
                storageFactory.create("b2bi", "b2bi-profiles.json",
                        new TypeReference<Map<String, B2biProfile>>() {}),
                storageFactory.create("b2bi", "b2bi-partnerships.json",
                        new TypeReference<Map<String, B2biPartnership>>() {}),
                regionResolver, mapper, s3Service, eventBridgeService);
    }

    B2biService(StorageBackend<String, B2biTransformer> transformers,
                StorageBackend<String, B2biCapability> capabilities,
                RegionResolver regionResolver) {
        this(transformers, capabilities, null, null, null, regionResolver, new ObjectMapper(), null, null);
    }

    B2biService(StorageBackend<String, B2biTransformer> transformers,
                StorageBackend<String, B2biCapability> capabilities,
                StorageBackend<String, TransformerJob> jobs,
                StorageBackend<String, B2biProfile> profiles,
                StorageBackend<String, B2biPartnership> partnerships,
                RegionResolver regionResolver,
                ObjectMapper mapper,
                S3Service s3Service,
                EventBridgeService eventBridgeService) {
        this.transformers = transformers;
        this.capabilities = capabilities;
        this.jobs = jobs;
        this.profiles = profiles;
        this.partnerships = partnerships;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.s3Service = s3Service;
        this.eventBridgeService = eventBridgeService;
    }

    public B2biTransformer createTransformer(String region, String name, JsonNode request) {
        require(name, "name");
        String now = now();
        String transformerId = newId("tr-");
        B2biTransformer transformer = new B2biTransformer();
        transformer.setTransformerId(transformerId);
        transformer.setTransformerArn(arn(region, "transformer/" + transformerId));
        transformer.setName(name);
        transformer.setStatus("inactive");
        transformer.setCreatedAt(now);
        applyTransformerFields(transformer, request);
        transformer.setTags(parseTags(request.path("tags")));
        transformers.put(transformerId, transformer);
        return transformer;
    }

    public B2biTransformer getTransformer(String transformerId) {
        require(transformerId, "transformerId");
        return transformers.get(transformerId).orElseThrow(() -> notFound("Transformer", transformerId));
    }

    public B2biTransformer updateTransformer(String transformerId, JsonNode request) {
        B2biTransformer transformer = getTransformer(transformerId);
        if ("active".equals(transformer.getStatus())) {
            throw new AwsException("ValidationException",
                    "An active Transformer cannot be updated", 400);
        }
        applyTransformerFields(transformer, request);
        if (request.hasNonNull("name")) {
            transformer.setName(request.get("name").asText());
        }
        if (request.hasNonNull("status")) {
            transformer.setStatus(request.get("status").asText());
        }
        transformer.setModifiedAt(now());
        transformers.put(transformerId, transformer);
        return transformer;
    }

    public void deleteTransformer(String transformerId) {
        getTransformer(transformerId);
        transformers.delete(transformerId);
    }

    public List<B2biTransformer> listTransformers() {
        List<B2biTransformer> all = new ArrayList<>(transformers.scan(k -> true));
        all.sort(Comparator.comparing(B2biTransformer::getTransformerId));
        return all;
    }

    public B2biCapability createCapability(String region, String name, String type, JsonNode request) {
        require(name, "name");
        JsonNode configuration = copy(request.get("configuration"));
        if (configuration == null || configuration.isNull()) {
            throw new AwsException("ValidationException", "configuration is required.", 400);
        }
        String now = now();
        String capabilityId = newId("ca-");
        B2biCapability capability = new B2biCapability();
        capability.setCapabilityId(capabilityId);
        capability.setCapabilityArn(arn(region, "capability/" + capabilityId));
        capability.setName(name);
        capability.setType(type == null || type.isBlank() ? "edi" : type);
        capability.setConfiguration(configuration);
        capability.setInstructionsDocuments(copy(request.get("instructionsDocuments")));
        capability.setCreatedAt(now);
        capability.setTags(parseTags(request.path("tags")));
        capabilities.put(capabilityId, capability);
        return capability;
    }

    public B2biCapability getCapability(String capabilityId) {
        require(capabilityId, "capabilityId");
        return capabilities.get(capabilityId).orElseThrow(() -> notFound("Capability", capabilityId));
    }

    public B2biCapability updateCapability(String capabilityId, JsonNode request) {
        B2biCapability capability = getCapability(capabilityId);
        if (request.hasNonNull("name")) {
            capability.setName(request.get("name").asText());
        }
        if (request.has("configuration") && !request.get("configuration").isNull()) {
            capability.setConfiguration(copy(request.get("configuration")));
        }
        if (request.has("instructionsDocuments")) {
            capability.setInstructionsDocuments(copy(request.get("instructionsDocuments")));
        }
        capability.setModifiedAt(now());
        capabilities.put(capabilityId, capability);
        return capability;
    }

    public void deleteCapability(String capabilityId) {
        getCapability(capabilityId);
        capabilities.delete(capabilityId);
    }

    public List<B2biCapability> listCapabilities() {
        List<B2biCapability> all = new ArrayList<>(capabilities.scan(k -> true));
        all.sort(Comparator.comparing(B2biCapability::getCapabilityId));
        return all;
    }

    public B2biProfile createProfile(String region, JsonNode request) {
        String name = textOrNull(request, "name");
        String businessName = textOrNull(request, "businessName");
        String phone = textOrNull(request, "phone");
        require(name, "name");
        require(businessName, "businessName");
        require(phone, "phone");
        if (listProfiles().size() >= MAX_PROFILES) {
            throw new AwsException("ServiceQuotaExceededException",
                    "You can have at most 5 profiles per account.", 400);
        }
        String logging = textOrNull(request, "logging");
        if (logging == null || logging.isBlank()) {
            logging = "ENABLED";
        }
        String now = now();
        String profileId = newId("p-");
        B2biProfile profile = new B2biProfile();
        profile.setProfileId(profileId);
        profile.setProfileArn(arn(region, "profile/" + profileId));
        profile.setName(name);
        profile.setBusinessName(businessName);
        profile.setPhone(phone);
        profile.setEmail(textOrNull(request, "email"));
        profile.setLogging(logging);
        if ("ENABLED".equals(logging)) {
            profile.setLogGroupName("/aws/vendedlogs/b2bi/profile/" + profileId);
        }
        profile.setCreatedAt(now);
        profile.setTags(parseTags(request.path("tags")));
        requireStore(profiles, "Profile");
        profiles.put(profileId, profile);
        ensureProfileLogGroups(profile, region);
        return profile;
    }

    public B2biProfile getProfile(String profileId) {
        require(profileId, "profileId");
        requireStore(profiles, "Profile");
        return profiles.get(profileId).orElseThrow(() -> notFound("Profile", profileId));
    }

    public B2biProfile updateProfile(String profileId, JsonNode request) {
        B2biProfile profile = getProfile(profileId);
        if (request.hasNonNull("name")) {
            profile.setName(request.get("name").asText());
        }
        if (request.hasNonNull("businessName")) {
            profile.setBusinessName(request.get("businessName").asText());
        }
        if (request.hasNonNull("phone")) {
            profile.setPhone(request.get("phone").asText());
        }
        if (request.has("email") && !request.get("email").isNull()) {
            profile.setEmail(request.get("email").asText());
        }
        profile.setModifiedAt(now());
        profiles.put(profileId, profile);
        return profile;
    }

    public void deleteProfile(String profileId) {
        getProfile(profileId);
        for (B2biPartnership partnership : listPartnerships(null)) {
            if (profileId.equals(partnership.getProfileId())) {
                throw new AwsException("ConflictException",
                        "Profile " + profileId + " has associated partnerships.", 409);
            }
        }
        profiles.delete(profileId);
    }

    public List<B2biProfile> listProfiles() {
        if (profiles == null) {
            return List.of();
        }
        List<B2biProfile> all = new ArrayList<>(profiles.scan(k -> true));
        all.sort(Comparator.comparing(B2biProfile::getProfileId));
        return all;
    }

    public B2biPartnership createPartnership(String region, JsonNode request) {
        String profileId = textOrNull(request, "profileId");
        String name = textOrNull(request, "name");
        String email = textOrNull(request, "email");
        require(profileId, "profileId");
        require(name, "name");
        require(email, "email");
        getProfile(profileId);
        List<String> capabilityIds = stringList(request.get("capabilities"));
        for (String capabilityId : capabilityIds) {
            getCapability(capabilityId);
        }
        requireStore(partnerships, "Partnership");
        String now = now();
        String partnershipId = newId("ps-");
        B2biPartnership partnership = new B2biPartnership();
        partnership.setPartnershipId(partnershipId);
        partnership.setPartnershipArn(arn(region, "partnership/" + partnershipId));
        partnership.setProfileId(profileId);
        partnership.setName(name);
        partnership.setEmail(email);
        partnership.setPhone(textOrNull(request, "phone"));
        partnership.setCapabilities(capabilityIds);
        partnership.setCapabilityOptions(copy(request.get("capabilityOptions")));
        partnership.setTradingPartnerId(newId("tp-"));
        partnership.setCreatedAt(now);
        partnership.setTags(parseTags(request.path("tags")));
        partnerships.put(partnershipId, partnership);
        return partnership;
    }

    public B2biPartnership getPartnership(String partnershipId) {
        require(partnershipId, "partnershipId");
        requireStore(partnerships, "Partnership");
        return partnerships.get(partnershipId).orElseThrow(() -> notFound("Partnership", partnershipId));
    }

    public B2biPartnership updatePartnership(String partnershipId, JsonNode request) {
        B2biPartnership partnership = getPartnership(partnershipId);
        if (request.hasNonNull("name")) {
            partnership.setName(request.get("name").asText());
        }
        if (request.has("capabilities")) {
            List<String> capabilityIds = stringList(request.get("capabilities"));
            for (String capabilityId : capabilityIds) {
                getCapability(capabilityId);
            }
            partnership.setCapabilities(capabilityIds);
        }
        if (request.has("capabilityOptions")) {
            partnership.setCapabilityOptions(copy(request.get("capabilityOptions")));
        }
        partnership.setModifiedAt(now());
        partnerships.put(partnershipId, partnership);
        return partnership;
    }

    public void deletePartnership(String partnershipId) {
        getPartnership(partnershipId);
        partnerships.delete(partnershipId);
    }

    public List<B2biPartnership> listPartnerships(String profileId) {
        if (partnerships == null) {
            return List.of();
        }
        List<B2biPartnership> all = new ArrayList<>();
        for (B2biPartnership partnership : partnerships.scan(k -> true)) {
            if (profileId == null || profileId.isBlank() || profileId.equals(partnership.getProfileId())) {
                all.add(partnership);
            }
        }
        all.sort(Comparator.comparing(B2biPartnership::getPartnershipId));
        return all;
    }

    public Map<String, String> listTags(String resourceArn) {
        require(resourceArn, "ResourceARN");
        return new LinkedHashMap<>(tagged(resourceArn).getTags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        require(resourceArn, "ResourceARN");
        Tagged tagged = tagged(resourceArn);
        tagged.getTags().putAll(tags);
        persist(tagged);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        require(resourceArn, "ResourceARN");
        Tagged tagged = tagged(resourceArn);
        if (tagKeys != null) {
            tagKeys.forEach(tagged.getTags()::remove);
        }
        persist(tagged);
    }

    public ObjectNode testMapping(JsonNode request) {
        String input = requireText(request, "inputFileContent");
        String template = requireText(request, "mappingTemplate");
        JsonNode inputNode;
        try {
            inputNode = mapper.readTree(input);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "inputFileContent is not valid JSON", 400);
        }
        JsonNode mapped;
        try {
            mapped = B2biJsonata.evaluate(template, inputNode, mapper);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("mappedFileContent", mapped.toPrettyString());
        return response;
    }

    public ObjectNode testParsing(JsonNode request) {
        JsonNode inputFile = objectField(request, "inputFile");
        String bucket = requireText(inputFile, "bucketName");
        String key = requireText(inputFile, "key");
        String edi = readS3Utf8(bucket, key);
        ObjectNode parsed = B2biX12.parse(edi, request.get("ediType"), mapper);
        ObjectNode response = mapper.createObjectNode();
        response.put("parsedFileContent", parsed.toString());
        response.putArray("validationMessages");
        return response;
    }

    public ObjectNode testConversion(JsonNode request) {
        JsonNode source = objectField(request, "source");
        JsonNode inputFile = objectField(source, "inputFile");
        String content = requireText(inputFile, "fileContent");
        String converted = B2biX12.toX12(content, request.get("target"), mapper);
        ObjectNode response = mapper.createObjectNode();
        response.put("convertedFileContent", converted);
        response.putArray("validationMessages");
        return response;
    }

    public ObjectNode createStarterMappingTemplate(JsonNode request) {
        JsonNode details = request.get("templateDetails");
        JsonNode x12 = details == null ? null : details.get("x12");
        String transactionSet = textOrNull(x12, "transactionSet");
        if (transactionSet == null) {
            transactionSet = "X12_850";
        }
        String version = textOrNull(x12, "version");
        if (version == null) {
            version = "VERSION_4010";
        }
        String template = """
                {
                  "transactionSet": "%s",
                  "version": "%s",
                  "purchaseOrderNumber": BEG.BEG03,
                  "items": PO1.{
                    "line": PO101,
                    "qty": PO102,
                    "uom": PO103,
                    "price": PO104
                  }
                }
                """.formatted(transactionSet, version);
        ObjectNode response = mapper.createObjectNode();
        response.put("mappingTemplate", template);
        return response;
    }

    public ObjectNode generateMapping(JsonNode request) {
        JsonNode input;
        JsonNode output;
        try {
            input = mapper.readTree(requireText(request, "inputFileContent"));
            output = mapper.readTree(requireText(request, "outputFileContent"));
        } catch (Exception e) {
            throw new AwsException("ValidationException",
                    "inputFileContent and outputFileContent must be valid JSON", 400);
        }
        StringBuilder template = new StringBuilder("{\n");
        boolean first = true;
        if (output != null && output.isObject()) {
            var fields = output.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!first) {
                    template.append(",\n");
                }
                first = false;
                String source = matchInputPath(input, field.getValue());
                template.append("  \"").append(escape(field.getKey())).append("\": ");
                if (source != null) {
                    template.append(source);
                } else if (field.getValue().isTextual()) {
                    template.append('"').append(escape(field.getValue().asText())).append('"');
                } else {
                    template.append(field.getValue().toString());
                }
            }
        }
        if (first) {
            template.append("  \"result\": $");
        }
        template.append("\n}");
        ObjectNode response = mapper.createObjectNode();
        response.put("mappingTemplate", template.toString());
        response.put("mappingAccuracy", 1.0);
        return response;
    }

    public synchronized ObjectNode startTransformerJob(JsonNode request, String region) {
        if (jobs == null || s3Service == null) {
            throw new AwsException("InternalServerException", "Transformer jobs are not available.", 500);
        }
        B2biTransformer transformer = getTransformer(requireText(request, "transformerId"));
        JsonNode inputFile = objectField(request, "inputFile");
        JsonNode outputLocation = objectField(request, "outputLocation");
        String inBucket = requireText(inputFile, "bucketName");
        String inKey = requireText(inputFile, "key");
        String outBucket = requireText(outputLocation, "bucketName");
        String outPrefix = textOrNull(outputLocation, "key");
        if (outPrefix == null) {
            outPrefix = "";
        }
        String now = now();
        String jobId = UUID.randomUUID().toString();
        String edi = readS3Utf8(inBucket, inKey);
        ObjectNode parsed = B2biX12.parse(edi, transformer.getEdiType(), mapper);
        JsonNode mapped = parsed;
        String template = mappingTemplate(transformer);
        if (template != null && !template.isBlank()) {
            try {
                mapped = B2biJsonata.evaluate(template, parsed, mapper);
            } catch (IllegalArgumentException e) {
                throw new AwsException("ValidationException", e.getMessage(), 400);
            }
        }
        byte[] outputBytes = mapped.toPrettyString().getBytes(StandardCharsets.UTF_8);
        String outKey = outPrefix.endsWith("/") || outPrefix.isBlank()
                ? outPrefix + jobId + ".json"
                : outPrefix;
        S3Object written = s3Service.putObject(outBucket, outKey, outputBytes, "application/json", Map.of());

        TransformerJob job = new TransformerJob();
        job.setTransformerJobId(jobId);
        job.setTransformerId(transformer.getTransformerId());
        job.setStatus("succeeded");
        job.setStartTimestamp(now);
        job.setEndTimestamp(now());
        job.setRegion(region);
        job.setOutputFiles(List.of(new TransformerJob.S3Ref(outBucket, outKey, (long) outputBytes.length)));
        jobs.put(transformer.getTransformerId() + "::" + jobId, job);
        publishTransformationCompleted(transformer, job, inBucket, inKey, edi.length(), written, region);
        return mapper.createObjectNode().put("transformerJobId", jobId);
    }

    public ObjectNode getTransformerJob(JsonNode request) {
        if (jobs == null) {
            throw notFound("Transformer job", requireText(request, "transformerJobId"));
        }
        String jobId = requireText(request, "transformerJobId");
        String transformerId = requireText(request, "transformerId");
        TransformerJob job = jobs.get(transformerId + "::" + jobId)
                .orElseThrow(() -> notFound("Transformer job", jobId));
        ObjectNode response = mapper.createObjectNode();
        response.put("status", job.getStatus());
        if (job.getMessage() != null) {
            response.put("message", job.getMessage());
        }
        ArrayNode files = response.putArray("outputFiles");
        for (TransformerJob.S3Ref file : job.getOutputFiles()) {
            ObjectNode node = files.addObject();
            node.put("bucketName", file.getBucketName());
            node.put("key", file.getKey());
        }
        return response;
    }

    private void publishTransformationCompleted(
            B2biTransformer transformer,
            TransformerJob job,
            String inputBucket,
            String inputKey,
            int inputSize,
            S3Object output,
            String region) {
        if (eventBridgeService == null) {
            return;
        }
        try {
            ObjectNode detail = mapper.createObjectNode();
            detail.put("transformer-job-id", job.getTransformerJobId());
            detail.put("start-timestamp", job.getStartTimestamp());
            detail.put("end-timestamp", job.getEndTimestamp());
            detail.put("validation-status", "SUCCEEDED");
            JsonNode x12 = transformer.getInputConversion() == null
                    ? null
                    : transformer.getInputConversion().path("formatOptions").path("x12");
            if (x12 != null && x12.isObject()) {
                if (x12.has("transactionSet")) {
                    detail.put("x12-transaction-set", x12.get("transactionSet").asText());
                }
                if (x12.has("version")) {
                    detail.put("x12-version", x12.get("version").asText());
                }
            }
            ObjectNode inputAttrs = detail.putObject("input-file-s3-attributes");
            inputAttrs.put("bucket", inputBucket);
            inputAttrs.put("object-key", inputKey);
            inputAttrs.put("object-size-bytes", inputSize);
            if (!job.getOutputFiles().isEmpty()) {
                TransformerJob.S3Ref out = job.getOutputFiles().get(0);
                ObjectNode outputAttrs = detail.putObject("output-file-s3-attributes");
                outputAttrs.put("bucket", out.getBucketName());
                outputAttrs.put("object-key", out.getKey());
                outputAttrs.put("object-size-bytes",
                        out.getObjectSizeBytes() != null ? out.getObjectSizeBytes()
                                : output == null ? 0 : output.getSize());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Source", "aws.b2bi");
            entry.put("DetailType", "Transformation Completed");
            entry.put("Detail", mapper.writeValueAsString(detail));
            entry.put("Resources", List.of(transformer.getTransformerArn()));
            eventBridgeService.putEvents(List.of(entry), region);
        } catch (Exception e) {
            LOG.warnv("Failed to publish B2BI Transformation Completed for {0}: {1}",
                    job.getTransformerJobId(), e.getMessage());
        }
    }

    private String mappingTemplate(B2biTransformer transformer) {
        if (transformer.getMapping() != null && transformer.getMapping().has("template")) {
            return transformer.getMapping().get("template").asText();
        }
        return transformer.getMappingTemplate();
    }

    private String readS3Utf8(String bucket, String key) {
        if (s3Service == null) {
            throw new AwsException("InternalServerException", "S3 is not available.", 500);
        }
        try {
            S3Object object = s3Service.getObject(bucket, key);
            byte[] data = object.getData() == null ? new byte[0] : object.getData();
            return new String(data, StandardCharsets.UTF_8);
        } catch (AwsException e) {
            if ("NoSuchKey".equals(e.getErrorCode()) || "NoSuchBucket".equals(e.getErrorCode())) {
                throw new AwsException("ResourceNotFoundException",
                        "Access denied when getting object attributes from s3://" + bucket + "/" + key,
                        404);
            }
            throw e;
        }
    }

    private static String matchInputPath(JsonNode input, JsonNode value) {
        if (input == null || !input.isObject() || value == null) {
            return null;
        }
        var fields = input.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getValue().equals(value)) {
                return field.getKey();
            }
        }
        return null;
    }

    private JsonNode objectField(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || !node.isObject()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return node;
    }

    private static String requireText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        require(value, field);
        return value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void applyTransformerFields(B2biTransformer transformer, JsonNode request) {
        if (request.hasNonNull("fileFormat")) {
            transformer.setFileFormat(request.get("fileFormat").asText());
        }
        if (request.hasNonNull("mappingTemplate")) {
            transformer.setMappingTemplate(request.get("mappingTemplate").asText());
        }
        if (request.has("ediType")) {
            transformer.setEdiType(copy(request.get("ediType")));
        }
        if (request.hasNonNull("sampleDocument")) {
            transformer.setSampleDocument(request.get("sampleDocument").asText());
        }
        if (request.has("inputConversion")) {
            transformer.setInputConversion(copy(request.get("inputConversion")));
        }
        if (request.has("mapping")) {
            transformer.setMapping(copy(request.get("mapping")));
        }
        if (request.has("outputConversion")) {
            transformer.setOutputConversion(copy(request.get("outputConversion")));
        }
        if (request.has("sampleDocuments")) {
            transformer.setSampleDocuments(copy(request.get("sampleDocuments")));
        }
    }

    private void ensureProfileLogGroups(B2biProfile profile, String region) {
        if (!"ENABLED".equals(profile.getLogging())) {
            return;
        }
        if (logsService == null || !logsService.isResolvable()) {
            return;
        }
        CloudWatchLogsService logs = logsService.get();
        for (String name : List.of(
                "/aws/vendedlogs/b2bi/default",
                "/aws/vendedlogs/b2bi/profile/" + profile.getProfileId())) {
            try {
                logs.createLogGroup(name, null, null, region);
            } catch (AwsException ignored) {
                // already exists
            }
        }
    }

    private Tagged tagged(String resourceArn) {
        for (B2biTransformer transformer : transformers.scan(k -> true)) {
            if (resourceArn.equals(transformer.getTransformerArn())) {
                return new Tagged(transformer, null, null, null);
            }
        }
        for (B2biCapability capability : capabilities.scan(k -> true)) {
            if (resourceArn.equals(capability.getCapabilityArn())) {
                return new Tagged(null, capability, null, null);
            }
        }
        if (profiles != null) {
            for (B2biProfile profile : profiles.scan(k -> true)) {
                if (resourceArn.equals(profile.getProfileArn())) {
                    return new Tagged(null, null, profile, null);
                }
            }
        }
        if (partnerships != null) {
            for (B2biPartnership partnership : partnerships.scan(k -> true)) {
                if (resourceArn.equals(partnership.getPartnershipArn())) {
                    return new Tagged(null, null, null, partnership);
                }
            }
        }
        throw notFound("Resource", resourceArn);
    }

    private void persist(Tagged tagged) {
        if (tagged.transformer != null) {
            transformers.put(tagged.transformer.getTransformerId(), tagged.transformer);
        } else if (tagged.capability != null) {
            capabilities.put(tagged.capability.getCapabilityId(), tagged.capability);
        } else if (tagged.profile != null) {
            profiles.put(tagged.profile.getProfileId(), tagged.profile);
        } else {
            partnerships.put(tagged.partnership.getPartnershipId(), tagged.partnership);
        }
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static JsonNode copy(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.deepCopy();
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            String key = textOrNull(tag, "Key");
            if (key == null) {
                key = textOrNull(tag, "key");
            }
            String value = textOrNull(tag, "Value");
            if (value == null) {
                value = textOrNull(tag, "value");
            }
            if (key != null) {
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isEmpty() ? null : value;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
    }

    private static AwsException notFound(String kind, String id) {
        return new AwsException("ResourceNotFoundException", kind + " " + id + " not found.", 404);
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static void requireStore(StorageBackend<?, ?> store, String kind) {
        if (store == null) {
            throw new AwsException("InternalServerException", kind + " store is not available.", 500);
        }
    }

    private record Tagged(B2biTransformer transformer, B2biCapability capability,
                          B2biProfile profile, B2biPartnership partnership) {
        Map<String, String> getTags() {
            if (transformer != null) {
                return transformer.getTags();
            }
            if (capability != null) {
                return capability.getTags();
            }
            if (profile != null) {
                return profile.getTags();
            }
            return partnership.getTags();
        }
    }
}

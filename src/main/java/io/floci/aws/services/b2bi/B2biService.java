package io.floci.aws.services.b2bi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.s3.model.S3ObjectUpdatedEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AWS B2B Data Interchange: profiles, transformers, capabilities and partnerships, the test operations that parse,
 * map and convert X12 ({@link B2biX12}, {@link B2biMappings}), transformer jobs, and the service's own processing.
 * A file a trading partner puts under an inbound capability's input location, in the partner's folder, is
 * transformed into the output location with the partnership's acknowledgments; a file put under an outbound
 * capability's input location is converted to X12 with the partnership's envelope. Each transformation is
 * reported to EventBridge as {@code aws.b2bi} {@code Transformation Completed} or {@code Transformation Failed}.
 */
@ApplicationScoped
public class B2biService implements Resettable {
    private static final Logger LOG = Logger.getLogger(B2biService.class);
    static final String EVENT_SOURCE = "aws.b2bi";
    private static final long JOB_RUN_MILLIS = 500;

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/b2bi-request-shapes.json", "B2BI request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return validation(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return validation(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return validation(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> profiles;
    private final AccountAwareStorageBackend<ObjectNode> capabilities;
    private final AccountAwareStorageBackend<ObjectNode> partnerships;
    private final AccountAwareStorageBackend<ObjectNode> transformers;
    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final Instance<S3Service> s3;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<EventBridgeService> events;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final ObjectMapper templateWriter;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "b2bi-transformations");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public B2biService(StorageFactory storageFactory, Instance<S3Service> s3, Instance<CloudWatchLogsService> logs,
                       Instance<EventBridgeService> events, RegionResolver regionResolver, ObjectMapper mapper) {
        this.profiles = store(storageFactory, "b2bi-profiles.json");
        this.capabilities = store(storageFactory, "b2bi-capabilities.json");
        this.partnerships = store(storageFactory, "b2bi-partnerships.json");
        this.transformers = store(storageFactory, "b2bi-transformers.json");
        this.jobs = store(storageFactory, "b2bi-transformer-jobs.json");
        this.s3 = s3;
        this.logs = logs;
        this.events = events;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.templateWriter = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static AccountAwareStorageBackend<ObjectNode> store(StorageFactory factory, String file) {
        return factory.create("b2bi", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    static void check(String operation, JsonNode request) {
        SHAPES.check(operation, request);
    }

    // ── Profiles ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createProfile(JsonNode in, String region) {
        check("CreateProfile", in);
        Optional<ObjectNode> repeated = byClientToken(profiles, in, region);
        if (repeated.isPresent()) {
            return renderProfile(repeated.get());
        }
        String id = id("p-");
        ObjectNode profile = mapper.createObjectNode();
        profile.put("profileId", id);
        profile.put("profileArn", arn(region, "profile/" + id));
        copy(in, profile, "name", "email", "phone", "businessName", "logging");
        if ("ENABLED".equals(in.path("logging").asText())) {
            String group = "/aws/vendedlogs/b2bi/profile/" + id;
            ensureLogGroup("/aws/vendedlogs/b2bi/default", region);
            ensureLogGroup(group, region);
            profile.put("logGroupName", group);
        }
        profile.put("createdAt", now());
        profile.set("tags", tags(in.path("tags")));
        token(profile, in);
        profiles.put(region + "/" + id, profile);
        return renderProfile(profile);
    }

    public synchronized ObjectNode getProfile(JsonNode in, String region) {
        check("GetProfile", in);
        return renderProfile(require(profiles, region, in.path("profileId").asText(), "profile"));
    }

    public synchronized ObjectNode updateProfile(JsonNode in, String region) {
        check("UpdateProfile", in);
        ObjectNode profile = require(profiles, region, in.path("profileId").asText(), "profile");
        copy(in, profile, "name", "email", "phone", "businessName");
        profile.put("modifiedAt", now());
        profiles.put(region + "/" + profile.path("profileId").asText(), profile);
        return renderProfile(profile);
    }

    /** Deletes a profile; its log group stays, as AWS leaves it. */
    public synchronized ObjectNode deleteProfile(JsonNode in, String region) {
        check("DeleteProfile", in);
        String id = in.path("profileId").asText();
        require(profiles, region, id, "profile");
        if (scan(partnerships, region).stream().anyMatch(p -> id.equals(p.path("profileId").asText()))) {
            throw new AwsException("ConflictException", "The profile " + id + " has partnerships; delete them first.",
                    409);
        }
        profiles.delete(region + "/" + id);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listProfiles(JsonNode in, String region) {
        check("ListProfiles", in);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("profiles");
        for (ObjectNode profile : page(scan(profiles, region), in, out)) {
            ObjectNode summary = list.addObject();
            copy(profile, summary, "profileId", "name", "businessName", "logging", "logGroupName", "createdAt",
                    "modifiedAt");
        }
        return out;
    }

    private ObjectNode renderProfile(ObjectNode profile) {
        ObjectNode out = mapper.createObjectNode();
        copy(profile, out, "profileId", "profileArn", "name", "email", "phone", "businessName", "logging",
                "logGroupName", "createdAt", "modifiedAt");
        return out;
    }

    // ── Transformers ───────────────────────────────────────────────────────

    public synchronized ObjectNode createTransformer(JsonNode in, String region) {
        check("CreateTransformer", in);
        Optional<ObjectNode> repeated = byClientToken(transformers, in, region);
        if (repeated.isPresent()) {
            return renderTransformer(repeated.get());
        }
        ObjectNode transformer = mapper.createObjectNode();
        String id = id("tr-");
        transformer.put("transformerId", id);
        transformer.put("transformerArn", arn(region, "transformer/" + id));
        transformer.put("name", in.path("name").asText());
        transformer.put("status", "inactive");
        applyTransformerConfig(transformer, in);
        transformer.put("createdAt", now());
        transformer.set("tags", tags(in.path("tags")));
        token(transformer, in);
        ensureLogGroup("/aws/vendedlogs/b2bi/transformers", region);
        transformers.put(region + "/" + id, transformer);
        return renderTransformer(transformer);
    }

    public synchronized ObjectNode getTransformer(JsonNode in, String region) {
        check("GetTransformer", in);
        return renderTransformer(require(transformers, region, in.path("transformerId").asText(), "transformer"));
    }

    /** Updates an inactive transformer; an active one rejects every update, as AWS does. */
    public synchronized ObjectNode updateTransformer(JsonNode in, String region) {
        check("UpdateTransformer", in);
        ObjectNode transformer = require(transformers, region, in.path("transformerId").asText(), "transformer");
        if ("active".equals(transformer.path("status").asText())) {
            throw validation("An active Transformer cannot be updated");
        }
        ObjectNode merged = transformer.deepCopy();
        if (in.hasNonNull("name")) {
            merged.put("name", in.path("name").asText());
        }
        boolean configChanged = false;
        for (String field : List.of("fileFormat", "mappingTemplate", "ediType", "sampleDocument", "inputConversion",
                "mapping", "outputConversion", "sampleDocuments")) {
            configChanged |= in.has(field);
        }
        if (configChanged) {
            ObjectNode config = mapper.createObjectNode();
            for (String field : List.of("fileFormat", "mappingTemplate", "ediType", "sampleDocument",
                    "inputConversion", "mapping", "outputConversion", "sampleDocuments")) {
                if (in.has(field)) {
                    config.set(field, in.get(field));
                } else if (merged.has(field)) {
                    config.set(field, merged.get(field));
                }
            }
            for (String field : List.of("fileFormat", "mappingTemplate", "ediType", "sampleDocument",
                    "inputConversion", "mapping", "outputConversion", "sampleDocuments")) {
                merged.remove(field);
            }
            applyTransformerConfig(merged, config);
        }
        if (in.hasNonNull("status")) {
            if ("active".equals(in.path("status").asText())) {
                requireComplete(merged);
            }
            merged.put("status", in.path("status").asText());
        }
        merged.put("modifiedAt", now());
        transformers.put(region + "/" + merged.path("transformerId").asText(), merged);
        return renderTransformer(merged);
    }

    public synchronized ObjectNode deleteTransformer(JsonNode in, String region) {
        check("DeleteTransformer", in);
        String id = in.path("transformerId").asText();
        require(transformers, region, id, "transformer");
        if (scan(capabilities, region).stream().anyMatch(c -> id.equals(c.path("configuration").path("edi")
                .path("transformerId").asText()))) {
            throw new AwsException("ConflictException", "The transformer " + id + " is used by a capability; delete "
                    + "or update the capability first.", 409);
        }
        transformers.delete(region + "/" + id);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listTransformers(JsonNode in, String region) {
        check("ListTransformers", in);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("transformers");
        for (ObjectNode transformer : page(scan(transformers, region), in, out)) {
            ObjectNode summary = renderTransformer(transformer);
            summary.remove("transformerArn");
            list.add(summary);
        }
        return out;
    }

    /**
     * A transformer's configuration, in either of the API's shapes: the original one ({@code fileFormat},
     * {@code mappingTemplate}, {@code ediType}) or the current one (an {@code inputConversion} for inbound or an
     * {@code outputConversion} for outbound, with a {@code mapping}).
     */
    private void applyTransformerConfig(ObjectNode transformer, JsonNode in) {
        boolean legacy = in.has("fileFormat") || in.has("mappingTemplate") || in.has("ediType");
        boolean current = in.has("inputConversion") || in.has("outputConversion") || in.has("mapping");
        if (legacy && current) {
            throw validation("A transformer uses either fileFormat, mappingTemplate and ediType, or inputConversion "
                    + "or outputConversion with mapping, not both.");
        }
        if (in.has("inputConversion") && in.has("outputConversion")) {
            throw validation("A transformer has an inputConversion (inbound) or an outputConversion (outbound), not "
                    + "both.");
        }
        if (legacy) {
            String fileFormat = in.path("fileFormat").asText("JSON");
            if (in.hasNonNull("mappingTemplate")) {
                B2biMappings.checkTemplate("XML".equals(fileFormat) ? "XSLT" : "JSONATA",
                        in.path("mappingTemplate").asText());
            }
        }
        if (in.has("mapping")) {
            JsonNode mapping = in.path("mapping");
            B2biMappings.checkTemplate(mapping.path("templateLanguage").asText(), mapping.path("template").asText(null));
        }
        for (String field : List.of("fileFormat", "mappingTemplate", "ediType", "sampleDocument", "inputConversion",
                "mapping", "outputConversion", "sampleDocuments")) {
            if (in.hasNonNull(field)) {
                transformer.set(field, in.get(field).deepCopy());
            }
        }
    }

    private static void requireComplete(ObjectNode transformer) {
        if (transformer.has("inputConversion")) {
            if (!transformer.path("inputConversion").path("formatOptions").path("x12").has("transactionSet")) {
                throw validation("An inbound transformer needs inputConversion.formatOptions.x12 to be active.");
            }
            return;
        }
        if (transformer.has("outputConversion")) {
            if (!transformer.path("outputConversion").path("formatOptions").path("x12").has("transactionSet")) {
                throw validation("An outbound transformer needs outputConversion.formatOptions.x12 to be active.");
            }
            return;
        }
        if (!transformer.path("ediType").path("x12Details").has("transactionSet")
                || !transformer.hasNonNull("mappingTemplate")) {
            throw validation("A transformer needs an ediType and a mappingTemplate to be active.");
        }
    }

    private ObjectNode renderTransformer(ObjectNode transformer) {
        ObjectNode out = mapper.createObjectNode();
        copy(transformer, out, "transformerId", "transformerArn", "name", "status", "createdAt", "modifiedAt",
                "fileFormat", "mappingTemplate", "ediType", "sampleDocument", "inputConversion", "mapping",
                "outputConversion", "sampleDocuments");
        return out;
    }

    // ── Capabilities ───────────────────────────────────────────────────────

    public synchronized ObjectNode createCapability(JsonNode in, String region) {
        check("CreateCapability", in);
        Optional<ObjectNode> repeated = byClientToken(capabilities, in, region);
        if (repeated.isPresent()) {
            return renderCapability(repeated.get());
        }
        checkCapabilityConfig(in.path("configuration"), region);
        ObjectNode capability = mapper.createObjectNode();
        String id = id("ca-");
        capability.put("capabilityId", id);
        capability.put("capabilityArn", arn(region, "capability/" + id));
        capability.put("name", in.path("name").asText());
        capability.put("type", in.path("type").asText("edi"));
        capability.set("configuration", in.path("configuration").deepCopy());
        if (in.has("instructionsDocuments")) {
            capability.set("instructionsDocuments", in.path("instructionsDocuments").deepCopy());
        }
        capability.put("createdAt", now());
        capability.set("tags", tags(in.path("tags")));
        token(capability, in);
        capabilities.put(region + "/" + id, capability);
        return renderCapability(capability);
    }

    public synchronized ObjectNode getCapability(JsonNode in, String region) {
        check("GetCapability", in);
        return renderCapability(require(capabilities, region, in.path("capabilityId").asText(), "capability"));
    }

    public synchronized ObjectNode updateCapability(JsonNode in, String region) {
        check("UpdateCapability", in);
        ObjectNode capability = require(capabilities, region, in.path("capabilityId").asText(), "capability");
        if (in.hasNonNull("name")) {
            capability.put("name", in.path("name").asText());
        }
        if (in.has("configuration")) {
            checkCapabilityConfig(in.path("configuration"), region);
            capability.set("configuration", in.path("configuration").deepCopy());
        }
        if (in.has("instructionsDocuments")) {
            capability.set("instructionsDocuments", in.path("instructionsDocuments").deepCopy());
        }
        capability.put("modifiedAt", now());
        capabilities.put(region + "/" + capability.path("capabilityId").asText(), capability);
        return renderCapability(capability);
    }

    public synchronized ObjectNode deleteCapability(JsonNode in, String region) {
        check("DeleteCapability", in);
        String id = in.path("capabilityId").asText();
        require(capabilities, region, id, "capability");
        boolean used = scan(partnerships, region).stream().anyMatch(p -> {
            for (JsonNode capability : p.path("capabilities")) {
                if (id.equals(capability.asText())) {
                    return true;
                }
            }
            return false;
        });
        if (used) {
            throw new AwsException("ConflictException", "The capability " + id + " is used by a partnership; remove it "
                    + "from the partnership first.", 409);
        }
        capabilities.delete(region + "/" + id);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listCapabilities(JsonNode in, String region) {
        check("ListCapabilities", in);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("capabilities");
        for (ObjectNode capability : page(scan(capabilities, region), in, out)) {
            ObjectNode summary = list.addObject();
            copy(capability, summary, "capabilityId", "name", "type", "createdAt", "modifiedAt");
        }
        return out;
    }

    private void checkCapabilityConfig(JsonNode configuration, String region) {
        JsonNode edi = configuration.path("edi");
        if (!edi.isObject()) {
            throw validation("An edi capability needs configuration.edi.");
        }
        require(transformers, region, edi.path("transformerId").asText(), "transformer");
        for (String location : List.of("inputLocation", "outputLocation")) {
            String bucket = edi.path(location).path("bucketName").asText("");
            if (bucket.isEmpty()) {
                throw validation("configuration.edi." + location + " needs a bucketName.");
            }
            if (s3.isResolvable() && !s3.get().bucketExists(bucket)) {
                throw new AwsException("ResourceNotFoundException", "The S3 bucket " + bucket + " doesn't exist.", 404);
            }
        }
    }

    private ObjectNode renderCapability(ObjectNode capability) {
        ObjectNode out = mapper.createObjectNode();
        copy(capability, out, "capabilityId", "capabilityArn", "name", "type", "configuration",
                "instructionsDocuments", "createdAt", "modifiedAt");
        return out;
    }

    // ── Partnerships ───────────────────────────────────────────────────────

    /**
     * Creates a partnership and its trading partner ID, and the partner's folder in each inbound capability's input
     * location: files the partner puts there are processed for this partnership.
     */
    public synchronized ObjectNode createPartnership(JsonNode in, String region) {
        check("CreatePartnership", in);
        Optional<ObjectNode> repeated = byClientToken(partnerships, in, region);
        if (repeated.isPresent()) {
            return renderPartnership(repeated.get());
        }
        require(profiles, region, in.path("profileId").asText(), "profile");
        checkCapabilities(in.path("capabilities"), region);
        ObjectNode partnership = mapper.createObjectNode();
        String id = id("ps-");
        partnership.put("profileId", in.path("profileId").asText());
        partnership.put("partnershipId", id);
        partnership.put("partnershipArn", arn(region, "partnership/" + id));
        copy(in, partnership, "name", "email", "phone", "capabilities", "capabilityOptions");
        partnership.put("tradingPartnerId", id("tp-"));
        partnership.put("createdAt", now());
        partnership.set("tags", tags(in.path("tags")));
        token(partnership, in);
        partnerships.put(region + "/" + id, partnership);
        createPartnerFolders(partnership, region);
        return renderPartnership(partnership);
    }

    public synchronized ObjectNode getPartnership(JsonNode in, String region) {
        check("GetPartnership", in);
        return renderPartnership(require(partnerships, region, in.path("partnershipId").asText(), "partnership"));
    }

    public synchronized ObjectNode updatePartnership(JsonNode in, String region) {
        check("UpdatePartnership", in);
        ObjectNode partnership = require(partnerships, region, in.path("partnershipId").asText(), "partnership");
        if (in.has("capabilities")) {
            checkCapabilities(in.path("capabilities"), region);
        }
        copy(in, partnership, "name", "capabilities", "capabilityOptions");
        partnership.put("modifiedAt", now());
        partnerships.put(region + "/" + partnership.path("partnershipId").asText(), partnership);
        createPartnerFolders(partnership, region);
        return renderPartnership(partnership);
    }

    public synchronized ObjectNode deletePartnership(JsonNode in, String region) {
        check("DeletePartnership", in);
        String id = in.path("partnershipId").asText();
        require(partnerships, region, id, "partnership");
        partnerships.delete(region + "/" + id);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listPartnerships(JsonNode in, String region) {
        check("ListPartnerships", in);
        String profileId = in.path("profileId").asText(null);
        if (profileId != null) {
            require(profiles, region, profileId, "profile");
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("partnerships");
        List<ObjectNode> matching = scan(partnerships, region).stream()
                .filter(p -> profileId == null || profileId.equals(p.path("profileId").asText())).toList();
        for (ObjectNode partnership : page(matching, in, out)) {
            ObjectNode summary = list.addObject();
            copy(partnership, summary, "profileId", "partnershipId", "name", "capabilities", "capabilityOptions",
                    "tradingPartnerId", "createdAt", "modifiedAt");
        }
        return out;
    }

    private void checkCapabilities(JsonNode ids, String region) {
        for (JsonNode id : ids) {
            require(capabilities, region, id.asText(), "capability");
        }
    }

    private void createPartnerFolders(ObjectNode partnership, String region) {
        if (!s3.isResolvable()) {
            return;
        }
        for (JsonNode capabilityId : partnership.path("capabilities")) {
            capabilities.get(region + "/" + capabilityId.asText()).ifPresent(capability -> {
                JsonNode input = capability.path("configuration").path("edi").path("inputLocation");
                String folder = joinKey(input.path("key").asText(""), partnership.path("tradingPartnerId").asText())
                        + "/";
                try {
                    s3.get().putObject(input.path("bucketName").asText(), folder, new byte[0],
                            "application/x-directory", Map.of());
                } catch (AwsException e) {
                    LOG.debugv("Could not create the B2BI partner folder {0}: {1}", folder, e.getMessage());
                }
            });
        }
    }

    private ObjectNode renderPartnership(ObjectNode partnership) {
        ObjectNode out = mapper.createObjectNode();
        copy(partnership, out, "profileId", "partnershipId", "partnershipArn", "name", "email", "phone",
                "capabilities", "capabilityOptions", "tradingPartnerId", "createdAt", "modifiedAt");
        return out;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode listTags(JsonNode in, String region) {
        check("ListTagsForResource", in);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagged(in.path("ResourceARN").asText(), region).getValue().path("tags").deepCopy());
        return out;
    }

    public synchronized ObjectNode tag(JsonNode in, String region) {
        check("TagResource", in);
        Map.Entry<String, ObjectNode> tagged = tagged(in.path("ResourceARN").asText(), region);
        ArrayNode current = tagged.getValue().withArray("tags");
        for (JsonNode tag : in.path("Tags")) {
            for (int i = current.size() - 1; i >= 0; i--) {
                if (current.get(i).path("Key").asText().equals(tag.path("Key").asText())) {
                    current.remove(i);
                }
            }
            current.add(tag.deepCopy());
        }
        if (current.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
        storeOf(tagged.getKey()).put(tagged.getKey().substring(tagged.getKey().indexOf(':') + 1), tagged.getValue());
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode untag(JsonNode in, String region) {
        check("UntagResource", in);
        Map.Entry<String, ObjectNode> tagged = tagged(in.path("ResourceARN").asText(), region);
        ArrayNode current = tagged.getValue().withArray("tags");
        for (JsonNode key : in.path("TagKeys")) {
            for (int i = current.size() - 1; i >= 0; i--) {
                if (current.get(i).path("Key").asText().equals(key.asText())) {
                    current.remove(i);
                }
            }
        }
        storeOf(tagged.getKey()).put(tagged.getKey().substring(tagged.getKey().indexOf(':') + 1), tagged.getValue());
        return mapper.createObjectNode();
    }

    /** The resource an ARN names, keyed {@code <kind>:<store key>}. */
    private Map.Entry<String, ObjectNode> tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "b2bi")) {
            throw validation("The ARN " + arn + " isn't a B2BI ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        String kind = slash < 0 ? resource : resource.substring(0, slash);
        String key = (parsed.region().isEmpty() ? region : parsed.region()) + "/" + resource.substring(slash + 1);
        AccountAwareStorageBackend<ObjectNode> store = switch (kind) {
            case "profile", "capability", "partnership", "transformer" -> storeOf(kind + ":");
            default -> null;
        };
        if (store == null || slash < 0) {
            throw validation("The ARN " + arn + " doesn't name a B2BI resource.");
        }
        return Map.entry(kind + ":" + key, store.get(key).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The resource " + arn + " doesn't exist.", 404)));
    }

    private AccountAwareStorageBackend<ObjectNode> storeOf(String key) {
        return switch (key.substring(0, key.indexOf(':'))) {
            case "profile" -> profiles;
            case "capability" -> capabilities;
            case "partnership" -> partnerships;
            default -> transformers;
        };
    }

    // ── Test operations ────────────────────────────────────────────────────

    /** Parses an X12 file in S3 into B2BI's JSON or XML representation, split by transaction when asked. */
    public ObjectNode testParsing(JsonNode in, String region) {
        check("TestParsing", in);
        String edi = readS3(in.path("inputFile"));
        JsonNode x12 = in.path("ediType").path("x12Details");
        B2biX12.Parsed parsed = B2biX12.parse(edi, x12.path("transactionSet").asText(), x12.path("version").asText(),
                rules(in.path("advancedOptions")));
        boolean xml = "XML".equals(in.path("fileFormat").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("parsedFileContent", xml ? B2biX12.toXml(parsed.document()) : B2biMappings.pretty(parsed.document()));
        if ("TRANSACTION".equals(in.path("advancedOptions").path("x12").path("splitOptions").path("splitBy").asText())) {
            ArrayNode splits = out.putArray("parsedSplitFileContents");
            parsed.splits().forEach(s -> splits.add(xml ? B2biX12.toXml(s) : B2biMappings.pretty(s)));
        }
        if (!parsed.messages().isEmpty()) {
            ArrayNode messages = out.putArray("validationMessages");
            parsed.messages().forEach(messages::add);
        }
        return out;
    }

    /** Converts B2BI's JSON or XML representation into X12, checking the result as validation messages. */
    public ObjectNode testConversion(JsonNode in, String region) {
        check("TestConversion", in);
        JsonNode source = in.path("source");
        JsonNode target = in.path("target");
        String content = source.path("inputFile").path("fileContent").asText("");
        JsonNode document = "XML".equals(source.path("fileFormat").asText()) ? B2biX12.fromXml(content)
                : B2biMappings.parseJson(content);
        JsonNode x12 = target.path("formatDetails").path("x12");
        String transactionSet = x12.path("transactionSet").asText(null);
        String version = x12.path("version").asText(null);
        B2biX12.Envelope envelope = B2biX12.Envelope.standard();
        if (target.path("outputSampleFile").path("fileLocation").isObject()) {
            String sample = readS3(target.path("outputSampleFile").path("fileLocation"));
            envelope = envelope.withDelimiters(B2biX12.tokenize(sample).delimiters());
        }
        String converted = B2biX12.write(document, transactionSet, version, envelope);
        ObjectNode out = mapper.createObjectNode();
        out.put("convertedFileContent", converted);
        ArrayNode messages = out.putArray("validationMessages");
        if (transactionSet != null) {
            B2biX12.parse(converted, transactionSet, version, rules(target.path("advancedOptions"))).messages()
                    .forEach(messages::add);
        }
        return out;
    }

    /**
     * Applies a mapping template to content: JSONata to JSON (the result comes back as a JSON string of the
     * mapped document, as AWS returns it), XSLT to XML.
     */
    public ObjectNode testMapping(JsonNode in, String region) {
        check("TestMapping", in);
        boolean xml = "XML".equals(in.path("fileFormat").asText());
        String mapped = B2biMappings.apply(xml ? "XSLT" : "JSONATA", in.path("mappingTemplate").asText(),
                in.path("inputFileContent").asText(), in.path("fileFormat").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("mappedFileContent", xml ? mapped : mapper.getNodeFactory().textNode(mapped).toString());
        return out;
    }

    /**
     * A starter template for a transaction set: its full structure with placeholders, or, from a sample X12 file,
     * the sample's parsed document. JSONata templates are the JSON representation; XSLT templates write the XML one.
     */
    public ObjectNode createStarterTemplate(JsonNode in, String region) {
        check("CreateStarterMappingTemplate", in);
        JsonNode x12 = in.path("templateDetails").path("x12");
        String transactionSet = x12.path("transactionSet").asText();
        String version = x12.path("version").asText();
        ObjectNode document;
        if (in.path("outputSampleLocation").isObject()) {
            document = B2biX12.parse(readS3(in.path("outputSampleLocation")), transactionSet, version, List.of())
                    .document();
        } else {
            document = B2biX12.starter(transactionSet, version);
        }
        String template;
        if ("XSLT".equals(in.path("mappingType").asText())) {
            String xml = B2biX12.toXml(document).replaceFirst("^<\\?xml[^>]*>\\n", "") + "\n";
            template = "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">\n"
                    + "  <xsl:output method=\"xml\" indent=\"yes\"/>\n  <xsl:template match=\"/\">\n" + xml
                    + "  </xsl:template>\n</xsl:stylesheet>\n";
        } else {
            try {
                template = templateWriter.writeValueAsString(document);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return mapper.createObjectNode().put("mappingTemplate", template);
    }

    public ObjectNode generateMapping(JsonNode in, String region) {
        check("GenerateMapping", in);
        B2biMappings.Generated generated = B2biMappings.generate(in.path("inputFileContent").asText(),
                in.path("outputFileContent").asText(), in.path("mappingType").asText());
        return mapper.createObjectNode().put("mappingTemplate", generated.template())
                .put("mappingAccuracy", (float) generated.accuracy());
    }

    private List<JsonNode> rules(JsonNode advancedOptions) {
        List<JsonNode> out = new ArrayList<>();
        advancedOptions.path("x12").path("validationOptions").path("validationRules").forEach(out::add);
        return out;
    }

    // ── Transformer jobs ───────────────────────────────────────────────────

    public synchronized ObjectNode startJob(JsonNode in, String region) {
        check("StartTransformerJob", in);
        ObjectNode transformer = require(transformers, region, in.path("transformerId").asText(), "transformer");
        if (!"active".equals(transformer.path("status").asText())) {
            throw new AwsException("ConflictException", "The transformer " + transformer.path("transformerId").asText()
                    + " is inactive; activate it to run jobs.", 409);
        }
        readS3(in.path("inputFile"));
        if (s3.isResolvable() && !s3.get().bucketExists(in.path("outputLocation").path("bucketName").asText())) {
            throw new AwsException("ResourceNotFoundException", "The output bucket "
                    + in.path("outputLocation").path("bucketName").asText() + " doesn't exist.", 404);
        }
        String id = jobId();
        ObjectNode job = mapper.createObjectNode();
        job.put("transformerJobId", id);
        job.put("transformerId", transformer.path("transformerId").asText());
        job.put("status", "running");
        job.set("inputFile", in.path("inputFile").deepCopy());
        job.set("outputLocation", in.path("outputLocation").deepCopy());
        job.put("startedAt", Instant.now().toString());
        jobs.put(region + "/" + id, job);
        String account = regionResolver.getAccountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> runJob(id, region)), JOB_RUN_MILLIS,
                TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("transformerJobId", id);
    }

    public synchronized ObjectNode getJob(JsonNode in, String region) {
        check("GetTransformerJob", in);
        ObjectNode job = jobs.get(region + "/" + in.path("transformerJobId").asText())
                .filter(j -> in.path("transformerId").asText().equals(j.path("transformerId").asText()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "The transformer job "
                        + in.path("transformerJobId").asText() + " doesn't exist.", 404));
        ObjectNode out = mapper.createObjectNode();
        copy(job, out, "status", "outputFiles", "message");
        return out;
    }

    private void runJob(String id, String region) {
        ObjectNode job;
        ObjectNode transformer;
        synchronized (this) {
            job = jobs.get(region + "/" + id).orElse(null);
            if (job == null) {
                return;
            }
            transformer = transformers.get(region + "/" + job.path("transformerId").asText()).orElse(null);
        }
        JsonNode input = job.path("inputFile");
        Run run = transform(transformer, input, job.path("outputLocation"), null, id, region);
        synchronized (this) {
            job.put("status", run.failure() == null ? "succeeded" : "failed");
            ArrayNode outputs = job.putArray("outputFiles");
            run.outputs().forEach(o -> outputs.addObject().put("bucketName", o[0]).put("key", o[1]));
            if (run.failure() != null) {
                job.put("message", run.failure());
            } else if (!run.messages().isEmpty()) {
                job.put("message", String.join(" ", run.messages()));
            }
            jobs.put(region + "/" + id, job);
        }
    }

    /** The result of one transformation: the output objects written, validation messages, or why it failed. */
    record Run(List<String[]> outputs, List<String> messages, String failure) {
    }

    /**
     * Runs a transformer on one S3 object: inbound, X12 is parsed and mapped into JSON or XML; outbound, JSON or
     * XML is mapped and converted into X12 with the partnership's envelope. Writes the result under the output
     * location, reports it to EventBridge, and logs it to the transformers' log group.
     */
    private Run transform(ObjectNode transformer, JsonNode inputFile, JsonNode outputLocation, ObjectNode partnership,
                          String jobId, String region) {
        String start = Instant.now().toString();
        String bucket = inputFile.path("bucketName").asText();
        String key = inputFile.path("key").asText();
        String base = key.substring(key.lastIndexOf('/') + 1);
        List<String[]> outputs = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        ObjectNode detail = mapper.createObjectNode();
        detail.put("transformer-job-id", jobId);
        if (partnership != null) {
            detail.put("trading-partner-id", partnership.path("tradingPartnerId").asText());
        }
        detail.put("start-timestamp", start);
        byte[] inputBytes = new byte[0];
        try {
            if (transformer == null) {
                throw validation("The transformer no longer exists.");
            }
            S3Object object = s3.get().getObject(bucket, key);
            inputBytes = object.getData();
            String content = new String(inputBytes, StandardCharsets.UTF_8);
            String outputBucket = outputLocation.path("bucketName").asText();
            String outputPrefix = outputLocation.path("key").asText("");
            JsonNode x12 = x12Of(transformer);
            detail.put("x12-transaction-set", x12.path("transactionSet").asText());
            detail.put("x12-version", x12.path("version").asText());
            List<byte[]> results = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (transformer.has("outputConversion")) {
                String language = transformer.path("mapping").path("templateLanguage").asText("JSONATA");
                String template = transformer.path("mapping").path("template").asText(null);
                boolean xml = "XSLT".equals(language);
                String mapped = template == null ? content : B2biMappings.apply(language, template, content,
                        xml ? "XML" : "JSON");
                JsonNode document = xml ? B2biX12.fromXml(mapped) : B2biMappings.parseJson(mapped);
                String written = B2biX12.write(document, x12.path("transactionSet").asText(),
                        x12.path("version").asText(), envelope(partnership, x12, region));
                JsonNode headers = partnership == null ? mapper.createObjectNode()
                        : partnership.path("capabilityOptions").path("outboundEdi").path("x12").path("common");
                if (headers.path("validateEdi").asBoolean(true)) {
                    messages.addAll(B2biX12.parse(written, x12.path("transactionSet").asText(),
                            x12.path("version").asText(), rules(transformer.path("outputConversion")
                                    .path("advancedOptions"))).messages());
                }
                results.add(written.getBytes(StandardCharsets.UTF_8));
                names.add(stripExtension(base) + ".x12");
                detail.put("input-format", xml ? "XML" : "JSON");
                detail.put("output-format", "X12");
            } else {
                JsonNode advanced = transformer.path("inputConversion").path("advancedOptions");
                B2biX12.Parsed parsed = B2biX12.parse(content, x12.path("transactionSet").asText(),
                        x12.path("version").asText(), rules(advanced));
                messages.addAll(parsed.messages());
                String language = transformer.has("mapping")
                        ? transformer.path("mapping").path("templateLanguage").asText("JSONATA")
                        : "XML".equals(transformer.path("fileFormat").asText()) ? "XSLT" : "JSONATA";
                String template = transformer.has("mapping") ? transformer.path("mapping").path("template").asText(null)
                        : transformer.path("mappingTemplate").asText(null);
                boolean xml = "XSLT".equals(language);
                boolean split = "TRANSACTION".equals(advanced.path("x12").path("splitOptions").path("splitBy")
                        .asText());
                List<ObjectNode> documents = split ? parsed.splits() : List.of(parsed.document());
                for (int n = 0; n < documents.size(); n++) {
                    String text = xml ? B2biX12.toXml(documents.get(n)) : B2biMappings.pretty(documents.get(n));
                    String result = template == null || template.isBlank() ? text
                            : B2biMappings.apply(language, template, text, xml ? "XML" : "JSON");
                    results.add(result.getBytes(StandardCharsets.UTF_8));
                    names.add(stripExtension(base) + (split ? "_" + (n + 1) : "") + (xml ? ".xml" : ".json"));
                }
                if (partnership != null) {
                    acknowledge(parsed, partnership, x12, outputLocation, base, region);
                }
            }
            String folder = partnership == null ? outputPrefix
                    : joinKey(outputPrefix, partnership.path("tradingPartnerId").asText()) + "/";
            for (int n = 0; n < results.size(); n++) {
                String outputKey = joinKey(folder, names.get(n));
                s3.get().putObject(outputBucket, outputKey, results.get(n), names.get(n).endsWith(".json")
                        ? "application/json" : names.get(n).endsWith(".xml") ? "application/xml" : "text/plain",
                        Map.of());
                outputs.add(new String[] {outputBucket, outputKey});
            }
            detail.set("input-file-s3-attributes", s3Attributes(bucket, key, inputBytes.length));
            detail.put("validation-status", messages.isEmpty() ? "SUCCEEDED" : "FAILED");
            for (int n = 0; n < outputs.size(); n++) {
                ObjectNode each = detail.deepCopy();
                each.put("end-timestamp", Instant.now().toString());
                each.set("output-file-s3-attributes", s3Attributes(outputs.get(n)[0], outputs.get(n)[1],
                        results.get(n).length));
                if (outputs.size() > 1) {
                    each.putObject("split-attributes").put("split-number", n + 1).put("total-split-count",
                            outputs.size()).put("split-is-valid", messages.isEmpty());
                }
                publish("Transformation Completed", each, transformer.path("transformerArn").asText(), region);
            }
            log(region, "Transformer job " + jobId + " transformed s3://" + bucket + "/" + key + " into "
                    + outputs.size() + " file(s)" + (messages.isEmpty() ? "." : ": " + String.join(" ", messages)));
            return new Run(outputs, messages, null);
        } catch (AwsException | IllegalStateException e) {
            detail.put("end-timestamp", Instant.now().toString());
            detail.set("input-file-s3-attributes", s3Attributes(bucket, key, inputBytes.length));
            detail.put("failure-message", e.getMessage());
            detail.put("failure-code", e instanceof AwsException aws ? aws.getErrorCode() : "InternalServerException");
            publish("Transformation Failed", detail, transformer == null ? null
                    : transformer.path("transformerArn").asText(), region);
            log(region, "Transformer job " + jobId + " failed on s3://" + bucket + "/" + key + ": " + e.getMessage());
            return new Run(outputs, messages, e.getMessage());
        }
    }

    private static JsonNode x12Of(ObjectNode transformer) {
        if (transformer.has("inputConversion")) {
            return transformer.path("inputConversion").path("formatOptions").path("x12");
        }
        if (transformer.has("outputConversion")) {
            return transformer.path("outputConversion").path("formatOptions").path("x12");
        }
        return transformer.path("ediType").path("x12Details");
    }

    /** The envelope an outbound conversion writes: the partnership's X12 headers, delimiters and control numbers. */
    private B2biX12.Envelope envelope(ObjectNode partnership, JsonNode x12, String region) {
        B2biX12.Envelope standard = B2biX12.Envelope.standard();
        if (partnership == null) {
            return standard;
        }
        JsonNode options = partnership.path("capabilityOptions").path("outboundEdi").path("x12");
        JsonNode common = options.path("common");
        JsonNode delimiters = common.path("delimiters");
        B2biX12.Delimiters d = new B2biX12.Delimiters(
                delimiters.path("dataElementSeparator").asText("*").charAt(0),
                delimiters.path("componentSeparator").asText(">").charAt(0),
                delimiters.path("segmentTerminator").asText("~").charAt(0));
        JsonNode wrap = options.path("wrapOptions");
        String lineTerminator = switch (wrap.path("lineTerminator").asText("LF")) {
            case "CRLF" -> "\r\n";
            case "CR" -> "\r";
            default -> "\n";
        };
        Long[] numbers = nextControlNumbers(partnership, common.path("controlNumbers"), region);
        return new B2biX12.Envelope(common.path("interchangeControlHeaders"), common.path("functionalGroupHeaders"), d,
                lineTerminator, wrap.path("wrapBy").asText("SEGMENT"), wrap.path("lineLength").asInt(80),
                common.path("gs05TimeFormat").asText(null), numbers[0], numbers[1], numbers[2]);
    }

    /** The next interchange, group and transaction control numbers of a partnership, from its starting numbers. */
    private synchronized Long[] nextControlNumbers(ObjectNode partnership, JsonNode configured, String region) {
        String key = region + "/" + partnership.path("partnershipId").asText();
        ObjectNode stored = partnerships.get(key).orElse(partnership);
        ObjectNode counters = stored.withObject("controlNumberCounters");
        Long[] out = new Long[3];
        String[] names = {"startingInterchangeControlNumber", "startingFunctionalGroupControlNumber",
            "startingTransactionSetControlNumber"};
        for (int i = 0; i < 3; i++) {
            long next = counters.path(names[i]).asLong(configured.path(names[i]).asLong(1));
            out[i] = next;
            counters.put(names[i], next + 1);
        }
        partnerships.put(key, stored);
        return out;
    }

    /** Writes the acknowledgments an inbound partnership asks for under the output location's partner folder. */
    private void acknowledge(B2biX12.Parsed parsed, ObjectNode partnership, JsonNode x12, JsonNode outputLocation,
                             String base, String region) {
        JsonNode options = partnership.path("capabilityOptions").path("inboundEdi").path("x12")
                .path("acknowledgmentOptions");
        String functional = options.path("functionalAcknowledgment").asText("DO_NOT_GENERATE");
        String technical = options.path("technicalAcknowledgment").asText("DO_NOT_GENERATE");
        String folder = joinKey(joinKey(outputLocation.path("key").asText(""),
                partnership.path("tradingPartnerId").asText()), "acknowledgments") + "/";
        String bucket = outputLocation.path("bucketName").asText();
        long control = 1 + random.nextInt(99_999_999);
        String version = x12.path("version").asText();
        if (!"DO_NOT_GENERATE".equals(functional)) {
            String type = version.startsWith("VERSION_5010") ? "999" : "997";
            String ack = B2biX12.functionalAck(parsed, version, functional.endsWith("WITHOUT_TRANSACTION_SET_RESPONSE_"
                    + "LOOP"), control);
            writeAck(bucket, joinKey(folder, stripExtension(base) + "." + type), ack, parsed, partnership, type, x12,
                    region);
        }
        if (!"DO_NOT_GENERATE".equals(technical)) {
            writeAck(bucket, joinKey(folder, stripExtension(base) + ".ta1"), B2biX12.technicalAck(parsed, control),
                    parsed, partnership, "TA1", x12, region);
        }
    }

    private void writeAck(String bucket, String key, String ack, B2biX12.Parsed parsed, ObjectNode partnership,
                          String type, JsonNode x12, String region) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("trading-partner-id", partnership.path("tradingPartnerId").asText());
        detail.put("start-timestamp", Instant.now().toString());
        detail.put("input-x12-transaction-set", x12.path("transactionSet").asText());
        detail.put("input-x12-version", x12.path("version").asText());
        detail.put("ack-x12-type", type);
        detail.put("ack-x12-version", x12.path("version").asText());
        try {
            byte[] bytes = ack.getBytes(StandardCharsets.UTF_8);
            s3.get().putObject(bucket, key, bytes, "text/plain", Map.of());
            detail.set("ack-file-s3-attributes", s3Attributes(bucket, key, bytes.length));
            detail.put("ack-error-code-detected", parsed.transactions().stream().anyMatch(t -> !t.errors().isEmpty()));
            detail.put("end-timestamp", Instant.now().toString());
            publish("Acknowledgement Completed", detail, null, region);
        } catch (AwsException e) {
            detail.put("failure-message", e.getMessage());
            detail.put("failure-code", e.getErrorCode());
            detail.put("end-timestamp", Instant.now().toString());
            publish("Acknowledgement Failed", detail, null, region);
        }
    }

    private ObjectNode s3Attributes(String bucket, String key, long size) {
        return mapper.createObjectNode().put("bucket", bucket).put("object-key", key).put("object-size-bytes", size);
    }

    private void publish(String detailType, ObjectNode detail, String resourceArn, String region) {
        if (events.isResolvable()) {
            events.get().publishServiceEvent(EVENT_SOURCE, detailType, detail.toString(), resourceArn, region);
        }
    }

    private void log(String region, String message) {
        if (!logs.isResolvable()) {
            return;
        }
        String group = "/aws/vendedlogs/b2bi/transformers";
        try {
            ensureLogGroup(group, region);
            try {
                logs.get().createLogStream(group, "transformer-jobs", region);
            } catch (AwsException e) {
                // The stream already exists.
            }
            logs.get().putLogEvents(group, "transformer-jobs", List.of(Map.of("timestamp",
                    Instant.now().toEpochMilli(), "message", message)), region);
        } catch (RuntimeException e) {
            LOG.debugv("Could not log a B2BI transformation: {0}", e.getMessage());
        }
    }

    private void ensureLogGroup(String name, String region) {
        if (!logs.isResolvable()) {
            return;
        }
        try {
            logs.get().createLogGroup(name, null, Map.of(), region);
        } catch (AwsException e) {
            // Already there: B2BI's log groups are created once and shared.
        }
    }

    // ── The service's own processing ───────────────────────────────────────

    /**
     * Picks up a file put under a capability's input location in a trading partner's folder
     * ({@code <input key>/<trading partner ID>/<file>}) and transforms it for that partnership, as B2BI does
     * for the S3 locations it watches.
     */
    void onObjectCreated(@Observes S3ObjectUpdatedEvent event) {
        if (event.key().endsWith("/")) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : capabilities.scanAllAccountEntries(k -> true)) {
            JsonNode edi = entry.value().path("configuration").path("edi");
            JsonNode input = edi.path("inputLocation");
            if (!event.bucketName().equals(input.path("bucketName").asText())) {
                continue;
            }
            String prefix = input.path("key").asText("");
            String folder = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
            if (!event.key().startsWith(folder)) {
                continue;
            }
            String rest = event.key().substring(folder.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String partnerId = rest.substring(0, slash);
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            String account = entry.accountId();
            String capabilityId = entry.value().path("capabilityId").asText();
            Optional<ObjectNode> partnership = partnerships.scanAllAccountEntries(k -> k.startsWith(region + "/"))
                    .stream().filter(p -> p.accountId().equals(account))
                    .map(AccountAwareStorageBackend.AccountEntry::value)
                    .filter(p -> partnerId.equals(p.path("tradingPartnerId").asText()))
                    .filter(p -> {
                        for (JsonNode id : p.path("capabilities")) {
                            if (capabilityId.equals(id.asText())) {
                                return true;
                            }
                        }
                        return false;
                    }).findFirst();
            if (partnership.isEmpty()) {
                continue;
            }
            ObjectNode inputFile = mapper.createObjectNode().put("bucketName", event.bucketName())
                    .put("key", event.key());
            JsonNode output = edi.path("outputLocation");
            String transformerId = edi.path("transformerId").asText();
            worker.schedule(() -> RequestScopes.runAs(account, region, () -> {
                ObjectNode transformer = transformers.get(region + "/" + transformerId).orElse(null);
                transform(transformer, inputFile, output, partnership.get(),
                        jobId(), region);
            }), JOB_RUN_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String readS3(JsonNode location) {
        String bucket = location.path("bucketName").asText("");
        String key = location.path("key").asText("");
        if (bucket.isEmpty() || key.isEmpty()) {
            throw validation("An S3 location needs a bucketName and a key.");
        }
        try {
            return new String(s3.get().getObject(bucket, key).getData(), StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "The S3 object s3://" + bucket + "/" + key
                    + " doesn't exist or can't be read.", 404);
        }
    }

    static String joinKey(String prefix, String name) {
        if (prefix == null || prefix.isEmpty()) {
            return name;
        }
        return prefix.endsWith("/") ? prefix + name : prefix + "/" + name;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Optional<ObjectNode> byClientToken(AccountAwareStorageBackend<ObjectNode> store, JsonNode in,
                                               String region) {
        String token = in.path("clientToken").asText(null);
        if (token == null) {
            return Optional.empty();
        }
        return scan(store, region).stream().filter(r -> token.equals(r.path("clientToken").asText())).findFirst();
    }

    private static void token(ObjectNode resource, JsonNode in) {
        if (in.hasNonNull("clientToken")) {
            resource.put("clientToken", in.path("clientToken").asText());
        }
    }

    private ObjectNode require(AccountAwareStorageBackend<ObjectNode> store, String region, String id, String kind) {
        return store.get(region + "/" + id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The " + kind + " " + id + " doesn't exist.", 404));
    }

    private List<ObjectNode> scan(AccountAwareStorageBackend<ObjectNode> store, String region) {
        return store.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(r -> r.path("createdAt").asDouble())).toList();
    }

    private List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("maxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(in.path("nextToken").asText());
            } catch (NumberFormatException e) {
                throw validation("The nextToken isn't valid.");
            }
        }
        if (start < 0 || start > all.size()) {
            throw validation("The nextToken isn't valid.");
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return all.subList(start, end);
    }

    private ArrayNode tags(JsonNode requested) {
        ArrayNode out = mapper.createArrayNode();
        requested.forEach(t -> out.add(t.deepCopy()));
        return out;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("b2bi", region, regionResolver.getAccountId(), resource).toString();
    }

    private String id(String prefix) {
        return prefix + HexFormat.of().formatHex(bytes(9)).substring(0, 17);
    }

    /** A transformer job ID: 25 characters, as the API requires. */
    private String jobId() {
        return HexFormat.of().formatHex(bytes(13)).substring(0, 25);
    }

    private byte[] bytes(int n) {
        byte[] out = new byte[n];
        random.nextBytes(out);
        return out;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @Override
    public void clear() {
        profiles.clear();
        capabilities.clear();
        partnerships.clear();
        transformers.clear();
        jobs.clear();
    }
}

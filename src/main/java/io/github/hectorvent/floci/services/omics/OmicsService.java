package io.github.hectorvent.floci.services.omics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.omics.model.ReferenceStore;
import io.github.hectorvent.floci.services.omics.model.RunGroup;
import io.github.hectorvent.floci.services.omics.model.SequenceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Amazon HealthOmics restJson1 — sequence stores, reference stores, run
 * groups, empty read-set/reference listings, and typed not-found for missing
 * runs.
 *
 * @see <a href="https://docs.aws.amazon.com/omics/latest/api/Welcome.html">HealthOmics API</a>
 */
@ApplicationScoped
public class OmicsService implements Resettable, TagHandler {

    static final String SERVICE = "omics";

    private final StorageBackend<String, SequenceStore> sequenceStores;
    private final StorageBackend<String, ReferenceStore> referenceStores;
    private final StorageBackend<String, RunGroup> runGroups;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public OmicsService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("omics", "omics-sequence-stores.json",
                        new TypeReference<Map<String, SequenceStore>>() {
                        }),
                storageFactory.create("omics", "omics-reference-stores.json",
                        new TypeReference<Map<String, ReferenceStore>>() {
                        }),
                storageFactory.create("omics", "omics-run-groups.json",
                        new TypeReference<Map<String, RunGroup>>() {
                        }),
                regionResolver, objectMapper);
    }

    OmicsService(
            StorageBackend<String, SequenceStore> sequenceStores,
            StorageBackend<String, ReferenceStore> referenceStores,
            StorageBackend<String, RunGroup> runGroups,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.sequenceStores = sequenceStores;
        this.referenceStores = referenceStores;
        this.runGroups = runGroups;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        sequenceStores.clear();
        referenceStores.clear();
        runGroups.clear();
    }

    public synchronized SequenceStore createSequenceStore(String region, JsonNode request) {
        requireObject(request, "Request body");
        String token = optionalText(request, "clientToken");
        if (token != null) {
            SequenceStore existing = findSequenceStoreByClientToken(token);
            if (existing != null) {
                return existing;
            }
        }
        String name = requireText(request, "name");
        String now = now();
        String id = newId();
        SequenceStore store = new SequenceStore();
        store.setId(id);
        store.setArn(regionResolver.buildArn(SERVICE, region, "sequenceStore/" + id));
        store.setName(name);
        store.setDescription(optionalText(request, "description"));
        applySse(store, request.get("sseConfig"));
        store.setFallbackLocation(optionalText(request, "fallbackLocation"));
        store.setETagAlgorithmFamily(optionalText(request, "eTagAlgorithmFamily"));
        store.setPropagatedSetLevelTags(readStringList(request.get("propagatedSetLevelTags")));
        store.setStatus("ACTIVE");
        store.setCreationTime(now);
        store.setUpdateTime(now);
        store.setRegion(region);
        store.setClientToken(token);
        store.setTags(readTags(request.get("tags")));
        sequenceStores.put(id, store);
        return store;
    }

    public SequenceStore getSequenceStore(String id) {
        return requireSequenceStore(id);
    }

    public List<SequenceStore> listSequenceStores() {
        List<SequenceStore> result = new ArrayList<>();
        for (SequenceStore store : sequenceStores.scan(k -> true)) {
            result.add(store);
        }
        result.sort(Comparator.comparing(SequenceStore::getCreationTime).reversed());
        return result;
    }

    public synchronized void deleteSequenceStore(String id) {
        requireSequenceStore(id);
        sequenceStores.delete(id);
    }

    public ObjectNode listReadSets(String sequenceStoreId) {
        requireSequenceStore(sequenceStoreId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("readSets");
        return response;
    }

    public ObjectNode getReadSetMetadata(String sequenceStoreId, String readSetId) {
        requireSequenceStore(sequenceStoreId);
        throw notFound("The specified read set " + readSetId + " does not exist.");
    }

    public synchronized ReferenceStore createReferenceStore(String region, JsonNode request) {
        requireObject(request, "Request body");
        String token = optionalText(request, "clientToken");
        if (token != null) {
            ReferenceStore existing = findReferenceStoreByClientToken(token);
            if (existing != null) {
                return existing;
            }
        }
        String name = requireText(request, "name");
        String now = now();
        String id = newId();
        ReferenceStore store = new ReferenceStore();
        store.setId(id);
        store.setArn(regionResolver.buildArn(SERVICE, region, "referenceStore/" + id));
        store.setName(name);
        store.setDescription(optionalText(request, "description"));
        applySse(store, request.get("sseConfig"));
        store.setCreationTime(now);
        store.setRegion(region);
        store.setClientToken(token);
        store.setTags(readTags(request.get("tags")));
        referenceStores.put(id, store);
        return store;
    }

    public ReferenceStore getReferenceStore(String id) {
        return requireReferenceStore(id);
    }

    public List<ReferenceStore> listReferenceStores() {
        List<ReferenceStore> result = new ArrayList<>();
        for (ReferenceStore store : referenceStores.scan(k -> true)) {
            result.add(store);
        }
        result.sort(Comparator.comparing(ReferenceStore::getCreationTime).reversed());
        return result;
    }

    public synchronized void deleteReferenceStore(String id) {
        requireReferenceStore(id);
        referenceStores.delete(id);
    }

    public ObjectNode listReferences(String referenceStoreId) {
        requireReferenceStore(referenceStoreId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("references");
        return response;
    }

    public ObjectNode getReferenceMetadata(String referenceStoreId, String referenceId) {
        requireReferenceStore(referenceStoreId);
        throw notFound("The specified reference " + referenceId + " does not exist.");
    }

    public synchronized RunGroup createRunGroup(String region, JsonNode request) {
        requireObject(request, "Request body");
        String token = optionalText(request, "requestId");
        if (token != null) {
            RunGroup existing = findRunGroupByRequestId(token);
            if (existing != null) {
                return existing;
            }
        }
        String id = newId();
        RunGroup group = new RunGroup();
        group.setId(id);
        group.setArn(regionResolver.buildArn(SERVICE, region, "runGroup/" + id));
        group.setName(optionalText(request, "name"));
        group.setMaxCpus(optionalInt(request, "maxCpus"));
        group.setMaxRuns(optionalInt(request, "maxRuns"));
        group.setMaxDuration(optionalInt(request, "maxDuration"));
        group.setMaxGpus(optionalInt(request, "maxGpus"));
        group.setCreationTime(now());
        group.setRegion(region);
        group.setRequestId(token);
        group.setTags(readTags(request.get("tags")));
        runGroups.put(id, group);
        return group;
    }

    public RunGroup getRunGroup(String id) {
        return requireRunGroup(id);
    }

    public List<RunGroup> listRunGroups() {
        List<RunGroup> result = new ArrayList<>();
        for (RunGroup group : runGroups.scan(k -> true)) {
            result.add(group);
        }
        result.sort(Comparator.comparing(RunGroup::getCreationTime).reversed());
        return result;
    }

    public synchronized RunGroup updateRunGroup(String id, JsonNode request) {
        requireObject(request, "Request body");
        RunGroup group = requireRunGroup(id);
        String name = optionalText(request, "name");
        if (name != null) {
            group.setName(name);
        }
        Integer maxCpus = optionalInt(request, "maxCpus");
        if (maxCpus != null) {
            group.setMaxCpus(maxCpus);
        }
        Integer maxRuns = optionalInt(request, "maxRuns");
        if (maxRuns != null) {
            group.setMaxRuns(maxRuns);
        }
        Integer maxDuration = optionalInt(request, "maxDuration");
        if (maxDuration != null) {
            group.setMaxDuration(maxDuration);
        }
        Integer maxGpus = optionalInt(request, "maxGpus");
        if (maxGpus != null) {
            group.setMaxGpus(maxGpus);
        }
        runGroups.put(group.getId(), group);
        return group;
    }

    public synchronized void deleteRunGroup(String id) {
        requireRunGroup(id);
        runGroups.delete(id);
    }

    public ObjectNode getRun(String id) {
        if (id == null || id.isBlank()) {
            throw validation("id is required.");
        }
        throw notFound("The specified run does not exist.");
    }

    public ObjectNode listRuns() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("items");
        return response;
    }

    public ObjectNode toSequenceStore(SequenceStore store) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", store.getId());
        node.put("arn", store.getArn());
        putOptional(node, "name", store.getName());
        putOptional(node, "description", store.getDescription());
        putSse(node, store.getSseType(), store.getSseKeyArn());
        node.put("creationTime", store.getCreationTime());
        putOptional(node, "fallbackLocation", store.getFallbackLocation());
        putOptional(node, "eTagAlgorithmFamily", store.getETagAlgorithmFamily());
        putOptional(node, "status", store.getStatus());
        if (store.getPropagatedSetLevelTags() != null && !store.getPropagatedSetLevelTags().isEmpty()) {
            ArrayNode tags = node.putArray("propagatedSetLevelTags");
            for (String tag : store.getPropagatedSetLevelTags()) {
                tags.add(tag);
            }
        }
        putOptional(node, "updateTime", store.getUpdateTime());
        return node;
    }

    public ObjectNode toSequenceStoreSummary(SequenceStore store) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", store.getArn());
        node.put("id", store.getId());
        putOptional(node, "name", store.getName());
        putOptional(node, "description", store.getDescription());
        putSse(node, store.getSseType(), store.getSseKeyArn());
        node.put("creationTime", store.getCreationTime());
        putOptional(node, "fallbackLocation", store.getFallbackLocation());
        putOptional(node, "eTagAlgorithmFamily", store.getETagAlgorithmFamily());
        putOptional(node, "status", store.getStatus());
        putOptional(node, "updateTime", store.getUpdateTime());
        return node;
    }

    public ObjectNode toReferenceStore(ReferenceStore store) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", store.getId());
        node.put("arn", store.getArn());
        putOptional(node, "name", store.getName());
        putOptional(node, "description", store.getDescription());
        putSse(node, store.getSseType(), store.getSseKeyArn());
        node.put("creationTime", store.getCreationTime());
        return node;
    }

    public ObjectNode toCreateRunGroup(RunGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        putOptional(node, "arn", group.getArn());
        putOptional(node, "id", group.getId());
        putTags(node, group.getTags());
        return node;
    }

    public ObjectNode toRunGroup(RunGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        putOptional(node, "arn", group.getArn());
        putOptional(node, "id", group.getId());
        putOptional(node, "name", group.getName());
        putOptional(node, "maxCpus", group.getMaxCpus());
        putOptional(node, "maxRuns", group.getMaxRuns());
        putOptional(node, "maxDuration", group.getMaxDuration());
        putOptional(node, "creationTime", group.getCreationTime());
        putTags(node, group.getTags());
        putOptional(node, "maxGpus", group.getMaxGpus());
        return node;
    }

    public ObjectNode toRunGroupSummary(RunGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        putOptional(node, "arn", group.getArn());
        putOptional(node, "id", group.getId());
        putOptional(node, "name", group.getName());
        putOptional(node, "maxCpus", group.getMaxCpus());
        putOptional(node, "maxRuns", group.getMaxRuns());
        putOptional(node, "maxDuration", group.getMaxDuration());
        putOptional(node, "creationTime", group.getCreationTime());
        putOptional(node, "maxGpus", group.getMaxGpus());
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.setTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.setTags(current);
    }

    private SequenceStore requireSequenceStore(String id) {
        if (id == null || id.isBlank()) {
            throw validation("sequenceStoreId is required.");
        }
        SequenceStore store = sequenceStores.get(id).orElse(null);
        if (store == null) {
            throw notFound("The specified sequence store does not exist.");
        }
        return store;
    }

    private ReferenceStore requireReferenceStore(String id) {
        if (id == null || id.isBlank()) {
            throw validation("referenceStoreId is required.");
        }
        ReferenceStore store = referenceStores.get(id).orElse(null);
        if (store == null) {
            throw notFound("The specified reference store does not exist.");
        }
        return store;
    }

    private RunGroup requireRunGroup(String id) {
        if (id == null || id.isBlank()) {
            throw validation("id is required.");
        }
        RunGroup group = runGroups.get(id).orElse(null);
        if (group == null) {
            throw notFound("The specified run group does not exist.");
        }
        return group;
    }

    private SequenceStore findSequenceStoreByClientToken(String token) {
        for (SequenceStore store : sequenceStores.scan(k -> true)) {
            if (token.equals(store.getClientToken())) {
                return store;
            }
        }
        return null;
    }

    private ReferenceStore findReferenceStoreByClientToken(String token) {
        for (ReferenceStore store : referenceStores.scan(k -> true)) {
            if (token.equals(store.getClientToken())) {
                return store;
            }
        }
        return null;
    }

    private RunGroup findRunGroupByRequestId(String token) {
        for (RunGroup group : runGroups.scan(k -> true)) {
            if (token.equals(group.getRequestId())) {
                return group;
            }
        }
        return null;
    }

    private Tagged requireTagged(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw notFound("The specified resource does not exist.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound("The specified resource does not exist.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("sequenceStore/")) {
            SequenceStore store = requireSequenceStore(resource.substring("sequenceStore/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return store.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    store.setTags(tags);
                    store.setUpdateTime(now());
                    sequenceStores.put(store.getId(), store);
                }
            };
        }
        if (resource.startsWith("referenceStore/")) {
            ReferenceStore store = requireReferenceStore(resource.substring("referenceStore/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return store.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    store.setTags(tags);
                    referenceStores.put(store.getId(), store);
                }
            };
        }
        if (resource.startsWith("runGroup/")) {
            RunGroup group = requireRunGroup(resource.substring("runGroup/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return group.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    group.setTags(tags);
                    runGroups.put(group.getId(), group);
                }
            };
        }
        throw notFound("The specified resource does not exist.");
    }

    private void applySse(SequenceStore store, JsonNode sseConfig) {
        if (sseConfig == null || !sseConfig.isObject()) {
            return;
        }
        store.setSseType(optionalText(sseConfig, "type"));
        store.setSseKeyArn(optionalText(sseConfig, "keyArn"));
    }

    private void applySse(ReferenceStore store, JsonNode sseConfig) {
        if (sseConfig == null || !sseConfig.isObject()) {
            return;
        }
        store.setSseType(optionalText(sseConfig, "type"));
        store.setSseKeyArn(optionalText(sseConfig, "keyArn"));
    }

    private void putSse(ObjectNode node, String type, String keyArn) {
        if (type == null || type.isBlank()) {
            return;
        }
        ObjectNode sse = node.putObject("sseConfig");
        sse.put("type", type);
        putOptional(sse, "keyArn", keyArn);
    }

    private static void putOptional(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static void putOptional(ObjectNode node, String field, Integer value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private void putTags(ObjectNode node, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode tagNode = node.putObject("tags");
        tags.forEach(tagNode::put);
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText("")));
        return tags;
    }

    private static List<String> readStringList(JsonNode node) {
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

    private static void requireObject(JsonNode request, String field) {
        if (request == null || !request.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer optionalInt(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isNumber() || value.isTextual()) {
            return value.asInt();
        }
        throw validation(field + " must be a number.");
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private interface Tagged {
        Map<String, String> tags();

        void setTags(Map<String, String> tags);
    }
}

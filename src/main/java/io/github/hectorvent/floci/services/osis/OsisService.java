package io.github.hectorvent.floci.services.osis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.osis.model.OsisPipeline;
import io.github.hectorvent.floci.services.osis.model.OsisPipelineEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon OpenSearch Ingestion (OSIS) restJson1 — pipelines, resource policies,
 * VPC endpoints, ValidatePipeline, and blueprint catalog.
 */
@ApplicationScoped
public class OsisService {

    static final String SERVICE = "osis";
    private static final Pattern PIPELINE_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,26}[a-z0-9]$");
    private static final String EMPTY_POLICY = "{}";
    private static final int MIN_BODY_LENGTH = 1;
    private static final int MAX_BODY_LENGTH = 100_000;
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS_LIMIT = 100;
    private static final Set<String> META_KEYS = Set.of("version", "extension");
    private static final Set<String> SOURCE_PLUGINS = Set.of(
            "http",
            "http_source",
            "s3",
            "kafka",
            "kinesis",
            "sqs",
            "documentdb",
            "dynamodb",
            "opensearch",
            "otel_trace_source",
            "otel_metrics_source",
            "otel_logs_source",
            "otel_trace",
            "file",
            "stdin",
            "gcs",
            "microsoft_o365",
            "rds",
            "mongodb",
            "cloudwatch_logs",
            "security_lake");
    private static final Set<String> SINK_PLUGINS = Set.of(
            "opensearch",
            "s3",
            "s3_sink",
            "kafka",
            "stdout",
            "file",
            "pipeline",
            "personalize_sink",
            "otel_trace_raw",
            "otel_metrics",
            "otel_logs",
            "http",
            "cloudwatch_logs");

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final StorageBackend<String, OsisPipeline> pipelines;
    private final StorageBackend<String, OsisPipelineEndpoint> endpoints;
    private final RegionResolver regionResolver;

    @Inject
    public OsisService(ObjectMapper jsonMapper, StorageFactory storageFactory, RegionResolver regionResolver) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.pipelines = storageFactory.create(SERVICE, "osis-pipelines.json",
                new TypeReference<Map<String, OsisPipeline>>() {
                });
        this.endpoints = storageFactory.create(SERVICE, "osis-endpoints.json",
                new TypeReference<Map<String, OsisPipelineEndpoint>>() {
                });
        this.regionResolver = regionResolver;
    }

    OsisService(
            ObjectMapper jsonMapper,
            ObjectMapper yamlMapper,
            StorageBackend<String, OsisPipeline> pipelines,
            StorageBackend<String, OsisPipelineEndpoint> endpoints,
            RegionResolver regionResolver) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = yamlMapper;
        this.pipelines = pipelines;
        this.endpoints = endpoints;
        this.regionResolver = regionResolver;
    }

    public synchronized OsisPipeline createPipeline(String region, JsonNode request) {
        requireObject(request);
        String name = requireText(request, "PipelineName");
        validatePipelineName(name);
        int minUnits = requireInt(request, "MinUnits");
        int maxUnits = requireInt(request, "MaxUnits");
        validateUnits(minUnits, maxUnits);
        String body = requireText(request, "PipelineConfigurationBody");
        if (body.isBlank()) {
            throw validation("PipelineConfigurationBody must not be empty.");
        }

        String key = pipelineKey(region, name);
        if (pipelines.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Pipeline " + name + " already exists.", 409);
        }

        long now = Instant.now().getEpochSecond();
        String accountId = regionResolver.getAccountId();
        OsisPipeline pipeline = new OsisPipeline();
        pipeline.setPipelineName(name);
        pipeline.setPipelineArn(arn(region, accountId, name));
        pipeline.setMinUnits(minUnits);
        pipeline.setMaxUnits(maxUnits);
        pipeline.setStatus("ACTIVE");
        pipeline.setPipelineConfigurationBody(body);
        pipeline.setCreatedAt(now);
        pipeline.setLastUpdatedAt(now);
        pipeline.setIngestEndpointUrls(List.of(name + "." + region + ".osis.amazonaws.com"));
        pipeline.setLogPublishingOptions(copyObject(request, "LogPublishingOptions"));
        pipeline.setVpcOptions(copyObject(request, "VpcOptions"));
        pipeline.setBufferOptions(copyObject(request, "BufferOptions"));
        pipeline.setEncryptionAtRestOptions(copyObject(request, "EncryptionAtRestOptions"));
        if (request.has("PipelineRoleArn")) {
            pipeline.setPipelineRoleArn(requireText(request, "PipelineRoleArn"));
        }
        pipeline.setTags(readTagList(request.get("Tags")));
        pipelines.put(key, pipeline);
        return pipeline;
    }

    public OsisPipeline getPipeline(String region, String pipelineName) {
        validatePipelineName(pipelineName);
        return pipelines.get(pipelineKey(region, pipelineName))
                .orElseThrow(() -> notFound("Pipeline " + pipelineName + " not found."));
    }

    public synchronized OsisPipeline updatePipeline(String region, String pipelineName, JsonNode request) {
        validatePipelineName(pipelineName);
        requireObject(request);
        String key = pipelineKey(region, pipelineName);
        OsisPipeline current = pipelines.get(key)
                .orElseThrow(() -> notFound("Pipeline " + pipelineName + " not found."));

        int minUnits = request.has("MinUnits") ? requireInt(request, "MinUnits") : current.getMinUnits();
        int maxUnits = request.has("MaxUnits") ? requireInt(request, "MaxUnits") : current.getMaxUnits();
        validateUnits(minUnits, maxUnits);
        current.setMinUnits(minUnits);
        current.setMaxUnits(maxUnits);
        if (request.has("PipelineConfigurationBody")) {
            String body = requireText(request, "PipelineConfigurationBody");
            if (body.isBlank()) {
                throw validation("PipelineConfigurationBody must not be empty.");
            }
            current.setPipelineConfigurationBody(body);
        }
        if (request.has("LogPublishingOptions")) {
            current.setLogPublishingOptions(copyObject(request, "LogPublishingOptions"));
        }
        if (request.has("BufferOptions")) {
            current.setBufferOptions(copyObject(request, "BufferOptions"));
        }
        if (request.has("EncryptionAtRestOptions")) {
            current.setEncryptionAtRestOptions(copyObject(request, "EncryptionAtRestOptions"));
        }
        if (request.has("PipelineRoleArn")) {
            current.setPipelineRoleArn(requireText(request, "PipelineRoleArn"));
        }
        current.setLastUpdatedAt(Instant.now().getEpochSecond());
        current.setStatus("ACTIVE");
        pipelines.put(key, current);
        return current;
    }

    public synchronized void deletePipeline(String region, String pipelineName) {
        validatePipelineName(pipelineName);
        String key = pipelineKey(region, pipelineName);
        OsisPipeline pipeline = pipelines.get(key).orElse(null);
        if (pipeline == null) {
            throw notFound("Pipeline " + pipelineName + " not found.");
        }
        String pipelineArn = pipeline.getPipelineArn();
        for (OsisPipelineEndpoint endpoint : endpoints.scan(k -> k.startsWith(region + "::"))) {
            if (pipelineArn.equals(endpoint.getPipelineArn())) {
                endpoints.delete(endpointKey(region, endpoint.getEndpointId()));
            }
        }
        pipelines.delete(key);
    }

    public List<OsisPipeline> listPipelines(String region) {
        List<OsisPipeline> result = pipelines.scan(key -> key.startsWith(region + "::"));
        result.sort(Comparator.comparing(OsisPipeline::getPipelineName));
        return result;
    }

    public List<Map.Entry<String, String>> listTags(String region, String arn) {
        return new ArrayList<>(requirePipelineByArn(region, arn).getTags().entrySet());
    }

    public synchronized void tagResource(String region, String arn, JsonNode request) {
        requireObject(request);
        OsisPipeline pipeline = requirePipelineByArn(region, arn);
        Map<String, String> tags = pipeline.getTags();
        tags.putAll(readTagList(request.get("Tags")));
        pipeline.setTags(tags);
        pipeline.setLastUpdatedAt(Instant.now().getEpochSecond());
        pipelines.put(pipelineKey(region, pipeline.getPipelineName()), pipeline);
    }

    public synchronized void untagResource(String region, String arn, JsonNode request) {
        requireObject(request);
        OsisPipeline pipeline = requirePipelineByArn(region, arn);
        JsonNode keys = request.get("TagKeys");
        if (keys == null || !keys.isArray()) {
            throw validation("TagKeys must be an array of strings.");
        }
        Map<String, String> tags = pipeline.getTags();
        for (JsonNode key : keys) {
            if (!key.isTextual()) {
                throw validation("TagKeys members must be strings.");
            }
            tags.remove(key.textValue());
        }
        pipeline.setTags(tags);
        pipeline.setLastUpdatedAt(Instant.now().getEpochSecond());
        pipelines.put(pipelineKey(region, pipeline.getPipelineName()), pipeline);
    }

    /**
     * AWS reports a missing resource policy as HTTP 200 with document {@code "{}"},
     * including when the pipeline itself does not exist.
     */
    public Map<String, String> getResourcePolicy(String region, String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw validation("ResourceArn is required.");
        }
        String policy = pipelines.get(pipelineKeyFromArn(region, resourceArn))
                .map(OsisPipeline::getPolicy)
                .filter(value -> value != null && !value.isBlank())
                .orElse(EMPTY_POLICY);
        return Map.of("ResourceArn", resourceArn, "Policy", policy);
    }

    public synchronized Map<String, String> putResourcePolicy(String region, String resourceArn, JsonNode request) {
        requireObject(request);
        String policy = requireText(request, "Policy");
        OsisPipeline pipeline = requirePipelineByArn(region, resourceArn);
        pipeline.setPolicy(policy);
        pipeline.setLastUpdatedAt(Instant.now().getEpochSecond());
        pipelines.put(pipelineKey(region, pipeline.getPipelineName()), pipeline);
        return Map.of("ResourceArn", pipeline.getPipelineArn(), "Policy", policy);
    }

    public synchronized void deleteResourcePolicy(String region, String resourceArn) {
        OsisPipeline pipeline = requirePipelineByArn(region, resourceArn);
        String policy = pipeline.getPolicy();
        if (policy == null || policy.isBlank() || EMPTY_POLICY.equals(policy.strip())) {
            throw notFound("Resource policy for " + resourceArn + " not found.");
        }
        pipeline.setPolicy(null);
        pipeline.setLastUpdatedAt(Instant.now().getEpochSecond());
        pipelines.put(pipelineKey(region, pipeline.getPipelineName()), pipeline);
    }

    public synchronized OsisPipelineEndpoint createPipelineEndpoint(String region, JsonNode request) {
        requireObject(request);
        String pipelineArn = requireText(request, "PipelineArn");
        requirePipelineByArn(region, pipelineArn);
        JsonNode vpcOptions = request.get("VpcOptions");
        requireNamedObject(vpcOptions, "VpcOptions");
        List<String> subnetIds = readStringArray(vpcOptions, "SubnetIds");
        List<String> securityGroupIds = vpcOptions.has("SecurityGroupIds")
                ? readStringArray(vpcOptions, "SecurityGroupIds")
                : List.of();
        if (subnetIds.isEmpty()) {
            throw validation("VpcOptions.SubnetIds must contain at least one subnet.");
        }

        String endpointId = "pe-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        OsisPipelineEndpoint endpoint = new OsisPipelineEndpoint();
        endpoint.setPipelineArn(pipelineArn);
        endpoint.setEndpointId(endpointId);
        endpoint.setStatus("ACTIVE");
        endpoint.setVpcId("vpc-floci");
        endpoint.setSubnetIds(subnetIds);
        endpoint.setSecurityGroupIds(securityGroupIds);
        endpoint.setIngestEndpointUrl(endpointId + "." + region + ".osis.amazonaws.com");
        endpoints.put(endpointKey(region, endpointId), endpoint);
        return endpoint;
    }

    public synchronized void deletePipelineEndpoint(String region, String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            throw validation("EndpointId is required.");
        }
        String key = endpointKey(region, endpointId);
        if (endpoints.get(key).isEmpty()) {
            throw notFound("Pipeline endpoint " + endpointId + " not found.");
        }
        endpoints.delete(key);
    }

    public List<OsisPipelineEndpoint> listPipelineEndpoints(String region) {
        List<OsisPipelineEndpoint> result = endpoints.scan(key -> key.startsWith(region + "::"));
        result.sort(Comparator.comparing(OsisPipelineEndpoint::getEndpointId));
        return result;
    }

    public ObjectNode validatePipeline(JsonNode request) {
        requireObject(request);
        JsonNode bodyNode = request.get("PipelineConfigurationBody");
        if (bodyNode == null || bodyNode.isNull()) {
            throw validation("Value null at 'pipelineConfigurationBody' failed to satisfy constraint: Member must not be null");
        }
        if (!bodyNode.isTextual()) {
            throw validation("PipelineConfigurationBody must be a YAML string.");
        }
        String body = bodyNode.asText();
        if (body.length() < MIN_BODY_LENGTH || body.length() > MAX_BODY_LENGTH) {
            throw validation(
                    "Value at 'pipelineConfigurationBody' failed to satisfy constraint: Member must have length between "
                            + MIN_BODY_LENGTH + " and " + MAX_BODY_LENGTH);
        }

        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = yamlMapper.readTree(body);
        } catch (Exception e) {
            errors.add("Pipeline configuration is not valid YAML: " + e.getMessage());
            return validateResponse(false, errors);
        }
        if (root == null || !root.isObject()) {
            errors.add("Pipeline configuration must be a YAML mapping.");
            return validateResponse(false, errors);
        }

        int pipelines = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (META_KEYS.contains(entry.getKey())) {
                continue;
            }
            JsonNode pipeline = entry.getValue();
            if (pipeline == null || !pipeline.isObject()) {
                errors.add("Pipeline '" + entry.getKey() + "' must be a mapping.");
                continue;
            }
            pipelines++;
            validateSource(entry.getKey(), pipeline.get("source"), errors);
            validateSinks(entry.getKey(), pipeline.get("sink"), errors);
        }
        if (pipelines == 0) {
            errors.add("Pipeline configuration must define at least one pipeline.");
        }
        return validateResponse(errors.isEmpty(), errors);
    }

    public ObjectNode listPipelineBlueprints() {
        ObjectNode response = jsonMapper.createObjectNode();
        ArrayNode blueprints = response.putArray("Blueprints");
        for (OsisBlueprints.Blueprint blueprint : OsisBlueprints.all()) {
            ObjectNode summary = blueprints.addObject();
            summary.put("BlueprintName", blueprint.blueprintName());
            summary.put("DisplayName", blueprint.displayName());
            summary.put("DisplayDescription", blueprint.displayDescription());
            summary.put("Service", blueprint.service());
            summary.put("UseCase", blueprint.useCase());
        }
        return response;
    }

    public ObjectNode getPipelineBlueprint(String blueprintName, String format) {
        if (blueprintName == null || blueprintName.isBlank()) {
            throw validation("Value null at 'blueprintName' failed to satisfy constraint: Member must not be null");
        }
        OsisBlueprints.Blueprint blueprint = OsisBlueprints.get(blueprintName);
        if (blueprint == null) {
            throw new AwsException(
                    "ResourceNotFoundException",
                    "Blueprint " + blueprintName + " not found.",
                    404);
        }
        String resolvedFormat = format == null || format.isBlank() ? "YAML" : format;
        ObjectNode response = jsonMapper.createObjectNode();
        ObjectNode node = response.putObject("Blueprint");
        node.put("BlueprintName", blueprint.blueprintName());
        node.put("PipelineConfigurationBody", blueprint.pipelineConfigurationBody());
        node.put("DisplayName", blueprint.displayName());
        node.put("DisplayDescription", blueprint.displayDescription());
        node.put("Service", blueprint.service());
        node.put("UseCase", blueprint.useCase());
        response.put("Format", resolvedFormat);
        return response;
    }

    public ObjectNode listPipelineEndpointConnections(String region, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS_LIMIT)) {
            throw validation("Value at 'maxResults' failed to satisfy constraint: Member must have value less than or equal to "
                    + MAX_RESULTS_LIMIT);
        }
        if (nextToken != null && !nextToken.isBlank()) {
            throw new AwsException("InvalidPaginationTokenException", "The specified nextToken is invalid.", 400);
        }
        int limit = maxResults == null ? DEFAULT_MAX_RESULTS : maxResults;
        ObjectNode response = jsonMapper.createObjectNode();
        ArrayNode list = response.putArray("PipelineEndpointConnections");
        List<OsisPipelineEndpoint> items = endpoints.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(OsisPipelineEndpoint::getEndpointId, Comparator.nullsLast(String::compareTo)));
        int count = 0;
        for (OsisPipelineEndpoint endpoint : items) {
            if (count >= limit) {
                break;
            }
            ObjectNode item = list.addObject();
            item.put("PipelineArn", endpoint.getPipelineArn());
            item.put("EndpointId", endpoint.getEndpointId());
            item.put("Status", endpoint.getStatus());
            item.put("VpcEndpointOwner", regionResolver.getAccountId());
            count++;
        }
        return response;
    }

    private void validateSource(String pipelineName, JsonNode source, List<String> errors) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            errors.add("Pipeline '" + pipelineName + "' is missing a source plugin.");
            return;
        }
        if (!source.isObject() || source.size() == 0) {
            errors.add("Pipeline '" + pipelineName + "' source must be a mapping of one plugin.");
            return;
        }
        String plugin = source.fieldNames().next();
        if (!SOURCE_PLUGINS.contains(plugin.toLowerCase(Locale.ROOT))) {
            errors.add("Unknown source plugin type: " + plugin);
        }
    }

    private void validateSinks(String pipelineName, JsonNode sink, List<String> errors) {
        if (sink == null || sink.isNull() || sink.isMissingNode()) {
            errors.add("Pipeline '" + pipelineName + "' is missing a sink plugin.");
            return;
        }
        if (!sink.isArray() || sink.isEmpty()) {
            errors.add("Pipeline '" + pipelineName + "' sink must be a non-empty list.");
            return;
        }
        for (JsonNode item : sink) {
            if (item == null || !item.isObject() || item.size() == 0) {
                errors.add("Pipeline '" + pipelineName + "' sink entries must be a mapping of one plugin.");
                continue;
            }
            String plugin = item.fieldNames().next();
            if (!SINK_PLUGINS.contains(plugin.toLowerCase(Locale.ROOT))) {
                errors.add("Unknown sink plugin type: " + plugin);
            }
        }
    }

    private ObjectNode validateResponse(boolean valid, List<String> errors) {
        ObjectNode response = jsonMapper.createObjectNode();
        response.put("isValid", valid);
        ArrayNode messages = response.putArray("Errors");
        for (String error : errors) {
            messages.addObject().put("Message", error);
        }
        return response;
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw validation("Request body must be a JSON object.");
        }
    }

    private OsisPipeline requirePipelineByArn(String region, String arn) {
        if (arn == null || arn.isBlank()) {
            throw validation("Resource ARN is required.");
        }
        return pipelines.get(pipelineKeyFromArn(region, arn))
                .orElseThrow(() -> notFound("Pipeline " + arn + " not found."));
    }

    private String pipelineKeyFromArn(String region, String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String resource = parsed.resource();
            String prefix = "pipeline/";
            if (!SERVICE.equals(parsed.service()) || resource == null || !resource.startsWith(prefix)) {
                throw validation("ResourceArn is not an OSIS pipeline ARN.");
            }
            String name = resource.substring(prefix.length());
            validatePipelineName(name);
            return pipelineKey(region, name);
        } catch (IllegalArgumentException e) {
            throw validation("ResourceArn is not a valid ARN.");
        }
    }

    private static String pipelineKey(String region, String name) {
        return region + "::" + name;
    }

    private static String endpointKey(String region, String endpointId) {
        return region + "::" + endpointId;
    }

    private static String arn(String region, String accountId, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId, "pipeline/" + name).toString();
    }

    private static void validatePipelineName(String name) {
        if (name == null || name.length() < 3 || name.length() > 28 || !PIPELINE_NAME.matcher(name).matches()) {
            throw validation("PipelineName must be 3-28 characters of lowercase letters, numbers, and hyphens.");
        }
    }

    private static void validateUnits(int minUnits, int maxUnits) {
        if (minUnits < 1) {
            throw validation("MinUnits must be at least 1.");
        }
        if (maxUnits < minUnits) {
            throw validation("MaxUnits must be greater than or equal to MinUnits.");
        }
    }

    private static Map<String, String> readTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray()) {
            throw validation("Tags must be an array of {Key, Value} objects.");
        }
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject()) {
                throw validation("Tags members must be objects.");
            }
            JsonNode key = tag.get("Key");
            JsonNode value = tag.get("Value");
            if (key == null || !key.isTextual() || value == null || !value.isTextual()) {
                throw validation("Each tag must have string Key and Value.");
            }
            tags.put(key.textValue(), value.textValue());
        }
        return tags;
    }

    private static List<String> readStringArray(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            values.add(item.textValue());
        }
        return values;
    }

    private static JsonNode copyObject(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        requireNamedObject(value, field);
        return value.deepCopy();
    }

    private static void requireNamedObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static int requireInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber() || !value.canConvertToInt()) {
            throw validation(field + " must be an integer.");
        }
        return value.intValue();
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private OsisPipeline requirePipeline(String region, String pipelineName) {
        return pipelines.get(pipelineKey(region, pipelineName)).orElseThrow(() ->
                notFound("Pipeline " + pipelineName + " not found."));
    }

    private static JsonNode optionalObject(JsonNode request, String field) {
        JsonNode value = request.get(field);
        return value != null && value.isObject() ? value : null;
    }
}

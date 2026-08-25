package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.fis.model.Experiment;
import io.github.hectorvent.floci.services.fis.model.ExperimentTemplate;
import io.github.hectorvent.floci.services.fis.model.SafetyLever;
import io.github.hectorvent.floci.services.fis.model.TargetAccountConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Fault Injection Service restJson1 — experiment templates and the
 * target-account configurations multi-account templates attach.
 */
@ApplicationScoped
public class FisService implements TagHandler {

    static final String SERVICE = "fis";
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern TEMPLATE_ID = Pattern.compile("EXT[0-9a-z]+");
    private static final String RESOURCE_PREFIX = "experiment-template/";

    private final StorageBackend<String, ExperimentTemplate> templates;
    private final StorageBackend<String, TargetAccountConfiguration> accounts;
    private final StorageBackend<String, Experiment> experiments;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public FisService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("fis", "fis-experiment-templates.json",
                        new TypeReference<Map<String, ExperimentTemplate>>() {
                        }),
                storageFactory.create("fis", "fis-target-account-configurations.json",
                        new TypeReference<Map<String, TargetAccountConfiguration>>() {
                        }),
                storageFactory.create("fis", "fis-experiments.json",
                        new TypeReference<Map<String, Experiment>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    FisService(StorageBackend<String, ExperimentTemplate> templates,
               StorageBackend<String, TargetAccountConfiguration> accounts,
               StorageBackend<String, Experiment> experiments,
               RegionResolver regionResolver,
               ObjectMapper objectMapper) {
        this.templates = templates;
        this.accounts = accounts;
        this.experiments = experiments;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ExperimentTemplate createExperimentTemplate(String region, JsonNode request) {
        requireObject(request, "Request body");
        String description = requireText(request, "description");
        String roleArn = requireText(request, "roleArn");
        Map<String, Object> actions = requireObjectMap(request, "actions");
        if (actions.isEmpty()) {
            throw validation("actions must contain at least one action.");
        }
        List<Map<String, Object>> stopConditions = requireObjectList(request, "stopConditions");
        if (stopConditions.isEmpty()) {
            throw validation("stopConditions must contain at least one stop condition.");
        }
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null && !clientToken.isBlank()) {
            ExperimentTemplate existing = findByClientToken(region, clientToken);
            if (existing != null) {
                return existing;
            }
        } else {
            clientToken = UUID.randomUUID().toString();
        }

        long now = Instant.now().getEpochSecond();
        String id = newTemplateId();
        String accountId = regionResolver.getAccountId();
        ExperimentTemplate template = new ExperimentTemplate();
        template.setId(id);
        template.setArn(templateArn(region, accountId, id));
        template.setDescription(description);
        template.setRoleArn(roleArn);
        template.setRegion(region);
        template.setAccountId(accountId);
        template.setClientToken(clientToken);
        template.setCreationTime(now);
        template.setLastUpdateTime(now);
        template.setTags(readTags(request));
        template.setTargets(optionalObjectMap(request, "targets"));
        template.setActions(actions);
        template.setStopConditions(stopConditions);
        template.setLogConfiguration(optionalObjectMapOrNull(request, "logConfiguration"));
        template.setExperimentOptions(readExperimentOptions(request, true));
        template.setExperimentReportConfiguration(optionalObjectMapOrNull(request, "experimentReportConfiguration"));
        templates.put(storageKey(region, id), template);
        return template;
    }

    public ExperimentTemplate getExperimentTemplate(String region, String id) {
        return requireTemplate(region, id);
    }

    public synchronized ExperimentTemplate updateExperimentTemplate(String region, String id, JsonNode request) {
        requireObject(request, "Request body");
        ExperimentTemplate template = requireTemplate(region, id);
        if (request.has("description") && !request.get("description").isNull()) {
            template.setDescription(requireText(request, "description"));
        }
        if (request.has("roleArn") && !request.get("roleArn").isNull()) {
            template.setRoleArn(requireText(request, "roleArn"));
        }
        if (request.has("actions") && !request.get("actions").isNull()) {
            Map<String, Object> actions = requireObjectMap(request, "actions");
            if (actions.isEmpty()) {
                throw validation("actions must contain at least one action.");
            }
            template.setActions(actions);
        }
        if (request.has("targets")) {
            template.setTargets(optionalObjectMap(request, "targets"));
        }
        if (request.has("stopConditions") && !request.get("stopConditions").isNull()) {
            List<Map<String, Object>> stopConditions = requireObjectList(request, "stopConditions");
            if (stopConditions.isEmpty()) {
                throw validation("stopConditions must contain at least one stop condition.");
            }
            template.setStopConditions(stopConditions);
        }
        if (request.has("logConfiguration")) {
            template.setLogConfiguration(optionalObjectMapOrNull(request, "logConfiguration"));
        }
        if (request.has("experimentOptions") && !request.get("experimentOptions").isNull()) {
            Map<String, Object> current = template.getExperimentOptions() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(template.getExperimentOptions());
            Map<String, Object> update = requireObjectMap(request, "experimentOptions");
            if (update.containsKey("emptyTargetResolutionMode")) {
                current.put("emptyTargetResolutionMode", update.get("emptyTargetResolutionMode"));
            }
            template.setExperimentOptions(current);
        }
        if (request.has("experimentReportConfiguration")) {
            template.setExperimentReportConfiguration(
                    optionalObjectMapOrNull(request, "experimentReportConfiguration"));
        }
        template.setLastUpdateTime(Instant.now().getEpochSecond());
        templates.put(storageKey(region, id), template);
        return template;
    }

    public synchronized ExperimentTemplate deleteExperimentTemplate(String region, String id) {
        ExperimentTemplate template = requireTemplate(region, id);
        templates.delete(storageKey(region, id));
        String prefix = region + "::" + id + "::";
        for (TargetAccountConfiguration config : accounts.scan(key -> key.startsWith(prefix))) {
            accounts.delete(accountKey(region, id, config.getAccountId()));
        }
        return template;
    }

    public List<ExperimentTemplate> listExperimentTemplates(String region) {
        List<ExperimentTemplate> matches = new ArrayList<>(templates.scan(key -> key.startsWith(region + "::")));
        matches.sort(Comparator.comparing(ExperimentTemplate::getCreationTime)
                .thenComparing(ExperimentTemplate::getId));
        return matches;
    }

    public synchronized TargetAccountConfiguration createTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId, JsonNode request) {
        requireObject(request, "Request body");
        ExperimentTemplate template = requireTemplate(region, experimentTemplateId);
        requireMultiAccount(template);
        validateAccountId(accountId);
        String roleArn = requireText(request, "roleArn");
        String key = accountKey(region, experimentTemplateId, accountId);
        if (accounts.get(key).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "A target account configuration already exists for account " + accountId + ".",
                    409);
        }
        TargetAccountConfiguration config = new TargetAccountConfiguration();
        config.setExperimentTemplateId(experimentTemplateId);
        config.setAccountId(accountId);
        config.setRoleArn(roleArn);
        config.setDescription(optionalText(request, "description"));
        config.setRegion(region);
        accounts.put(key, config);
        return config;
    }

    public TargetAccountConfiguration getTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId) {
        requireTemplate(region, experimentTemplateId);
        validateAccountId(accountId);
        return accounts.get(accountKey(region, experimentTemplateId, accountId)).orElseThrow(
                () -> notFound("Target account configuration for account " + accountId + " does not exist."));
    }

    public synchronized TargetAccountConfiguration updateTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId, JsonNode request) {
        requireObject(request, "Request body");
        TargetAccountConfiguration config = getTargetAccountConfiguration(region, experimentTemplateId, accountId);
        if (request.has("roleArn") && !request.get("roleArn").isNull()) {
            config.setRoleArn(requireText(request, "roleArn"));
        }
        if (request.has("description") && !request.get("description").isNull()) {
            config.setDescription(requireText(request, "description"));
        }
        accounts.put(accountKey(region, experimentTemplateId, accountId), config);
        return config;
    }

    public synchronized TargetAccountConfiguration deleteTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId) {
        TargetAccountConfiguration config = getTargetAccountConfiguration(region, experimentTemplateId, accountId);
        accounts.delete(accountKey(region, experimentTemplateId, accountId));
        return config;
    }

    public List<TargetAccountConfiguration> listTargetAccountConfigurations(
            String region, String experimentTemplateId) {
        requireTemplate(region, experimentTemplateId);
        String prefix = region + "::" + experimentTemplateId + "::";
        List<TargetAccountConfiguration> matches =
                new ArrayList<>(accounts.scan(key -> key.startsWith(prefix)));
        matches.sort(Comparator.comparing(TargetAccountConfiguration::getAccountId));
        return matches;
    }

    public synchronized Experiment startExperiment(String region, JsonNode request) {
        requireObject(request, "Request body");
        String templateId = requireText(request, "experimentTemplateId");
        ExperimentTemplate template = requireTemplate(region, templateId);
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null && !clientToken.isBlank()) {
            Experiment existing = findExperimentByClientToken(region, clientToken);
            if (existing != null) {
                return existing;
            }
        } else {
            clientToken = UUID.randomUUID().toString();
        }
        long now = Instant.now().getEpochSecond();
        String id = "EXP" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        Experiment experiment = new Experiment();
        experiment.setId(id);
        experiment.setArn(AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(),
                "experiment/" + id).toString());
        experiment.setExperimentTemplateId(templateId);
        experiment.setRoleArn(template.getRoleArn());
        experiment.setClientToken(clientToken);
        experiment.setStatus("running");
        experiment.setActions(objectMapper.valueToTree(template.getActions()));
        experiment.setTargets(objectMapper.valueToTree(template.getTargets()));
        experiment.setStopConditions(objectMapper.valueToTree(template.getStopConditions()));
        experiment.setExperimentOptions(objectMapper.valueToTree(template.getExperimentOptions()));
        experiment.setLogConfiguration(objectMapper.valueToTree(template.getLogConfiguration()));
        experiment.setExperimentReportConfiguration(
                objectMapper.valueToTree(template.getExperimentReportConfiguration()));
        experiment.setTags(readTags(request));
        experiment.setCreationTime(now);
        experiment.setStartTime(now);
        experiments.put(storageKey(region, id), experiment);
        return experiment;
    }

    public Experiment getExperiment(String region, String id) {
        return requireExperiment(region, id);
    }

    public synchronized Experiment stopExperiment(String region, String id) {
        Experiment experiment = requireExperiment(region, id);
        if (!"stopping".equals(experiment.getStatus()) && !"stopped".equals(experiment.getStatus())) {
            experiment.setStatus("stopping");
            experiment.setEndTime(Instant.now().getEpochSecond());
            experiments.put(storageKey(region, id), experiment);
        }
        return experiment;
    }

    public List<Experiment> listExperiments(String region, String experimentTemplateId) {
        List<Experiment> matches = new ArrayList<>(experiments.scan(key -> key.startsWith(region + "::")));
        if (experimentTemplateId != null && !experimentTemplateId.isBlank()) {
            matches.removeIf(experiment -> !experimentTemplateId.equals(experiment.getExperimentTemplateId()));
        }
        matches.sort(Comparator.comparing(Experiment::getCreationTime).thenComparing(Experiment::getId));
        return matches;
    }

    public ObjectNode toPublicExperiment(Experiment experiment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", experiment.getId());
        node.put("arn", experiment.getArn());
        node.put("experimentTemplateId", experiment.getExperimentTemplateId());
        node.put("roleArn", experiment.getRoleArn());
        ObjectNode state = node.putObject("state");
        state.put("status", experiment.getStatus());
        if (experiment.getStatusReason() != null) {
            state.put("reason", experiment.getStatusReason());
        }
        node.put("creationTime", experiment.getCreationTime());
        if (experiment.getStartTime() != null) {
            node.put("startTime", experiment.getStartTime());
        }
        if (experiment.getEndTime() != null) {
            node.put("endTime", experiment.getEndTime());
        }
        if (experiment.getActions() != null && !experiment.getActions().isNull()) {
            node.set("actions", experiment.getActions());
        }
        if (experiment.getTargets() != null && !experiment.getTargets().isNull()) {
            node.set("targets", experiment.getTargets());
        }
        if (experiment.getStopConditions() != null && !experiment.getStopConditions().isNull()) {
            node.set("stopConditions", experiment.getStopConditions());
        }
        node.set("tags", objectMapper.valueToTree(experiment.getTags() == null
                ? Map.of() : experiment.getTags()));
        return node;
    }

    public ArrayNode toPublicExperimentSummaries(List<Experiment> experiments) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Experiment experiment : experiments) {
            ObjectNode node = array.addObject();
            node.put("id", experiment.getId());
            node.put("arn", experiment.getArn());
            node.put("experimentTemplateId", experiment.getExperimentTemplateId());
            ObjectNode state = node.putObject("state");
            state.put("status", experiment.getStatus());
            node.put("creationTime", experiment.getCreationTime());
            node.set("tags", objectMapper.valueToTree(experiment.getTags() == null
                    ? Map.of() : experiment.getTags()));
        }
        return array;
    }

    public ObjectNode getAction(String id) {
        String actionId = decode(id);
        if (!"aws:fis:wait".equals(actionId)) {
            throw notFound("Action " + actionId + " does not exist.");
        }
        ObjectNode action = objectMapper.createObjectNode();
        action.put("id", "aws:fis:wait");
        action.put("description", "Wait for the specified duration.");
        ObjectNode parameters = action.putObject("parameters");
        ObjectNode duration = parameters.putObject("duration");
        duration.put("description", "The duration of the wait, in ISO-8601 format.");
        duration.put("required", true);
        return action;
    }

    public ArrayNode listActions() {
        ArrayNode actions = objectMapper.createArrayNode();
        ObjectNode wait = actions.addObject();
        wait.put("id", "aws:fis:wait");
        wait.put("description", "Wait for the specified duration.");
        return actions;
    }

    public ObjectNode getTargetResourceType(String resourceType) {
        String decoded = decode(resourceType);
        if (!"aws:ec2:instance".equals(decoded)) {
            throw notFound("Target resource type " + decoded + " does not exist.");
        }
        ObjectNode type = objectMapper.createObjectNode();
        type.put("resourceType", "aws:ec2:instance");
        type.put("description", "An Amazon EC2 instance.");
        return type;
    }

    public ArrayNode listTargetResourceTypes() {
        ArrayNode types = objectMapper.createArrayNode();
        ObjectNode instance = types.addObject();
        instance.put("resourceType", "aws:ec2:instance");
        instance.put("description", "An Amazon EC2 instance.");
        return types;
    }

    public SafetyLever getSafetyLever(String region, String id) {
        if (id == null || !"default".equals(id)) {
            throw notFound("Safety lever " + id + " does not exist.");
        }
        SafetyLever lever = new SafetyLever();
        lever.setId("default");
        lever.setArn(AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(),
                "safety-lever/default").toString());
        lever.setStatus("disengaged");
        lever.setReason("The safety lever is disengaged.");
        return lever;
    }

    public ObjectNode toPublicSafetyLever(SafetyLever lever) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", lever.getId());
        node.put("arn", lever.getArn());
        ObjectNode state = node.putObject("state");
        state.put("status", lever.getStatus());
        if (lever.getReason() != null) {
            state.put("reason", lever.getReason());
        }
        return node;
    }

    public ObjectNode toPublicTemplate(ExperimentTemplate template) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", template.getId());
        node.put("arn", template.getArn());
        node.put("description", template.getDescription());
        node.put("roleArn", template.getRoleArn());
        node.put("creationTime", template.getCreationTime());
        node.put("lastUpdateTime", template.getLastUpdateTime());
        node.set("targets", objectMapper.valueToTree(template.getTargets() == null
                ? Map.of() : template.getTargets()));
        node.set("actions", objectMapper.valueToTree(template.getActions() == null
                ? Map.of() : template.getActions()));
        node.set("stopConditions", objectMapper.valueToTree(template.getStopConditions() == null
                ? List.of() : template.getStopConditions()));
        node.set("tags", objectMapper.valueToTree(template.getTags() == null
                ? Map.of() : template.getTags()));
        if (template.getLogConfiguration() != null) {
            node.set("logConfiguration", objectMapper.valueToTree(template.getLogConfiguration()));
        }
        if (template.getExperimentOptions() != null) {
            node.set("experimentOptions", objectMapper.valueToTree(template.getExperimentOptions()));
        }
        if (template.getExperimentReportConfiguration() != null) {
            node.set("experimentReportConfiguration",
                    objectMapper.valueToTree(template.getExperimentReportConfiguration()));
        }
        node.put("targetAccountConfigurationsCount",
                listTargetAccountsUnchecked(template.getRegion(), template.getId()).size());
        return node;
    }

    public ObjectNode toPublicTemplateSummary(ExperimentTemplate template) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", template.getId());
        node.put("arn", template.getArn());
        node.put("description", template.getDescription());
        node.put("creationTime", template.getCreationTime());
        node.put("lastUpdateTime", template.getLastUpdateTime());
        node.set("tags", objectMapper.valueToTree(template.getTags() == null
                ? Map.of() : template.getTags()));
        return node;
    }

    public ObjectNode toPublicTargetAccount(TargetAccountConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("accountId", config.getAccountId());
        node.put("roleArn", config.getRoleArn());
        if (config.getDescription() != null) {
            node.put("description", config.getDescription());
        }
        return node;
    }

    public ArrayNode toPublicTargetAccounts(List<TargetAccountConfiguration> configs) {
        ArrayNode array = objectMapper.createArrayNode();
        for (TargetAccountConfiguration config : configs) {
            array.add(toPublicTargetAccount(config));
        }
        return array;
    }

    public ArrayNode toPublicTemplateSummaries(List<ExperimentTemplate> templates) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ExperimentTemplate template : templates) {
            array.add(toPublicTemplateSummary(template));
        }
        return array;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(requireByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        ExperimentTemplate template = requireByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(template.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        template.setTags(current);
        template.setLastUpdateTime(Instant.now().getEpochSecond());
        templates.put(storageKey(region, template.getId()), template);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        ExperimentTemplate template = requireByArn(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(template.getTags()::remove);
        }
        template.setLastUpdateTime(Instant.now().getEpochSecond());
        templates.put(storageKey(region, template.getId()), template);
    }

    private ExperimentTemplate requireByArn(String region, String arn) {
        return requireTemplate(region, templateIdFromArn(arn));
    }

    static String templateIdFromArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service())) {
                throw validation("Invalid resource ARN.");
            }
            String resource = parsed.resource();
            if (resource == null || !resource.startsWith(RESOURCE_PREFIX)) {
                throw validation("Invalid resource ARN.");
            }
            String id = resource.substring(RESOURCE_PREFIX.length());
            if (id.isBlank()) {
                throw validation("Invalid resource ARN.");
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw validation("Invalid resource ARN.");
        }
    }

    private ExperimentTemplate requireTemplate(String region, String id) {
        if (id == null || id.isBlank() || !TEMPLATE_ID.matcher(id).matches()) {
            throw validation("Invalid experiment template ID.");
        }
        return templates.get(storageKey(region, id)).orElseThrow(
                () -> notFound("Experiment template " + id + " does not exist."));
    }

    private List<TargetAccountConfiguration> listTargetAccountsUnchecked(String region, String templateId) {
        String prefix = region + "::" + templateId + "::";
        return new ArrayList<>(accounts.scan(key -> key.startsWith(prefix)));
    }

    private ExperimentTemplate findByClientToken(String region, String clientToken) {
        for (ExperimentTemplate template : templates.scan(key -> key.startsWith(region + "::"))) {
            if (clientToken.equals(template.getClientToken())) {
                return template;
            }
        }
        return null;
    }

    private Experiment findExperimentByClientToken(String region, String clientToken) {
        for (Experiment experiment : experiments.scan(key -> key.startsWith(region + "::"))) {
            if (clientToken.equals(experiment.getClientToken())) {
                return experiment;
            }
        }
        return null;
    }

    private Experiment requireExperiment(String region, String id) {
        if (id == null || id.isBlank() || !id.startsWith("EXP")) {
            throw validation("Invalid experiment ID.");
        }
        return experiments.get(storageKey(region, id)).orElseThrow(
                () -> notFound("Experiment " + id + " does not exist."));
    }

    private Map<String, Object> readExperimentOptions(JsonNode request, boolean creating) {
        Map<String, Object> options = optionalObjectMapOrNull(request, "experimentOptions");
        if (options == null) {
            options = new LinkedHashMap<>();
        } else {
            options = new LinkedHashMap<>(options);
        }
        if (creating && !options.containsKey("accountTargeting")) {
            options.put("accountTargeting", "single-account");
        }
        if (creating && !options.containsKey("emptyTargetResolutionMode")) {
            options.put("emptyTargetResolutionMode", "fail");
        }
        Object targeting = options.get("accountTargeting");
        if (targeting != null && !"single-account".equals(targeting) && !"multi-account".equals(targeting)) {
            throw validation("accountTargeting must be single-account or multi-account.");
        }
        Object emptyMode = options.get("emptyTargetResolutionMode");
        if (emptyMode != null && !"fail".equals(emptyMode) && !"skip".equals(emptyMode)) {
            throw validation("emptyTargetResolutionMode must be fail or skip.");
        }
        return options;
    }

    private void requireMultiAccount(ExperimentTemplate template) {
        Map<String, Object> options = template.getExperimentOptions();
        Object targeting = options == null ? null : options.get("accountTargeting");
        if (!"multi-account".equals(targeting)) {
            throw validation(
                    "Target account configurations require experimentOptions.accountTargeting of multi-account.");
        }
    }

    private static void validateAccountId(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw validation("accountId must be a 12-digit AWS account ID.");
        }
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String accountKey(String region, String templateId, String accountId) {
        return region + "::" + templateId + "::" + accountId;
    }

    private static String templateArn(String region, String accountId, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId, RESOURCE_PREFIX + id).toString();
    }

    private static String newTemplateId() {
        return "EXT" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    private Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("tags values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private Map<String, Object> requireObjectMap(JsonNode request, String field) {
        JsonNode node = request.get(field);
        requireObject(node, field);
        return objectMapper.convertValue(node, new TypeReference<>() {
        });
    }

    private Map<String, Object> optionalObjectMap(JsonNode request, String field) {
        Map<String, Object> value = optionalObjectMapOrNull(request, field);
        return value == null ? new LinkedHashMap<>() : value;
    }

    private Map<String, Object> optionalObjectMapOrNull(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        return requireObjectMap(request, field);
    }

    private List<Map<String, Object>> requireObjectList(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || !node.isArray()) {
            throw validation(field + " must be a JSON array.");
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode item : node) {
            requireObject(item, field + " members");
            values.add(objectMapper.convertValue(item, new TypeReference<>() {
            }));
        }
        return values;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " is a required string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}

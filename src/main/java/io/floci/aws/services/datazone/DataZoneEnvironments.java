package io.floci.aws.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.athena.AthenaService;
import io.floci.aws.services.athena.model.CreateWorkGroupRequest;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.lakeformation.LakeFormationService;
import io.floci.aws.services.lakeformation.model.DataLakePrincipal;
import io.floci.aws.services.lakeformation.model.DataLakeSettings;
import io.floci.aws.services.lakeformation.model.GetDataLakeSettingsRequest;
import io.floci.aws.services.lakeformation.model.PutDataLakeSettingsRequest;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.datazone.DataZoneStore.invalid;
import static io.floci.aws.services.datazone.DataZoneStore.now;
import static io.floci.aws.services.datazone.DataZoneStore.optional;
import static io.floci.aws.services.datazone.DataZoneStore.present;
import static io.floci.aws.services.datazone.DataZoneStore.text;

/**
 * DataZone environment blueprints, their per-domain configurations, environment profiles, environments, environment
 * actions, credentials and subscription targets.
 * <p>
 * Every domain lists DataZone's managed blueprints ({@code DefaultDataLake}, {@code DefaultDataWarehouse} and the
 * V2 blueprints) and the custom blueprints created in it. Deploying an environment provisions what its blueprint
 * stack provisions, in the environment's account Region, with the blueprint configuration's provisioning role (which
 * must trust {@code datazone.amazonaws.com}): an environment role for the project's users, and for
 * {@code DefaultDataLake} a producer and a consumer Glue database, an Athena workgroup and a log group, after making
 * the configuration's manage-access role a Lake Formation administrator. As the blueprint stack does, deleting the
 * environment retains the Glue databases, workgroup and log group, and removes the environment role.
 */
@ApplicationScoped
public class DataZoneEnvironments {

    private static final Logger LOG = Logger.getLogger(DataZoneEnvironments.class);
    static final String BLUEPRINTS = "environment-blueprints";
    static final String CONFIGURATIONS = "blueprint-configurations";
    static final String PROFILES = "environment-profiles";
    static final String ENVIRONMENTS = "environments";
    static final String ACTIONS = "environment-actions";
    static final String TARGETS = "subscription-targets";
    static final String PROVIDER = "Amazon DataZone";
    static final String SERVICE_PRINCIPAL = "datazone.amazonaws.com"; // partition-literal: a service principal

    /** DataZone's managed blueprints: name, description and category. */
    private static final List<String[]> MANAGED = List.of(
            new String[] {"DefaultDataLake", "Data lake environment: Glue databases and an Athena workgroup over S3",
                "DataLake"},
            new String[] {"DefaultDataWarehouse", "Data warehouse environment over Amazon Redshift", "DataWarehouse"},
            new String[] {"Tooling", "Project tooling: the project role, its S3 location and project resources",
                "Tooling"},
            new String[] {"LakehouseCatalog", "Lakehouse catalog: a Glue catalog over S3 tables", "DataLake"},
            new String[] {"MLExperiments", "Amazon SageMaker AI experiments and MLflow tracking", "ML"},
            new String[] {"Workflows", "Amazon MWAA workflows for the project", "Workflows"},
            new String[] {"RedshiftServerless", "An Amazon Redshift Serverless workgroup for the project",
                "DataWarehouse"},
            new String[] {"EmrServerless", "An Amazon EMR Serverless application for the project", "Compute"});

    private final DataZoneStore store;
    private final DataZoneService core;
    private final ObjectMapper mapper;
    private final Instance<IamService> iam;
    private final Instance<GlueService> glue;
    private final Instance<AthenaService> athena;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<LakeFormationService> lakeFormation;
    private final Instance<S3Service> s3;
    private final long transitionMillis;

    @Inject
    public DataZoneEnvironments(DataZoneStore store, DataZoneService core, ObjectMapper mapper,
                                Instance<IamService> iam, Instance<GlueService> glue, Instance<AthenaService> athena,
                                Instance<CloudWatchLogsService> logs, Instance<LakeFormationService> lakeFormation,
                                Instance<S3Service> s3, EmulatorConfig config) {
        this.store = store;
        this.core = core;
        this.mapper = mapper;
        this.iam = iam;
        this.glue = glue;
        this.athena = athena;
        this.logs = logs;
        this.lakeFormation = lakeFormation;
        this.s3 = s3;
        this.transitionMillis = config.services().datazone().transitionMillis();
    }

    // ---------------------------------------------------------------- blueprints

    /** The managed blueprints, with identifiers stable per domain. */
    private List<ObjectNode> managed(String region, String domainId) {
        List<ObjectNode> result = new ArrayList<>();
        for (String[] definition : MANAGED) {
            ObjectNode blueprint = store.object();
            blueprint.put("id", stableId(domainId + "/" + definition[0]));
            blueprint.put("name", definition[0]);
            blueprint.put("description", definition[1]);
            blueprint.put("provider", PROVIDER);
            blueprint.putObject("provisioningProperties").putObject("cloudFormation").put("templateUrl",
                    "https://datazone-blueprints-" + region + ".s3." + region + "." + AwsRegions.dnsSuffixFor(region)
                            + "/" + definition[0] + ".template.yaml");
            blueprint.putObject("deploymentProperties").put("startTimeoutMinutes", 60).put("endTimeoutMinutes", 60);
            ArrayNode parameters = blueprint.putArray("userParameters");
            if ("DefaultDataLake".equals(definition[0])) {
                parameters.addObject().put("keyName", "producerGlueDbName").put("fieldType", "String")
                        .put("isEditable", true).put("isOptional", true)
                        .put("description", "Name of the Glue database the environment publishes from");
                parameters.addObject().put("keyName", "consumerGlueDbName").put("fieldType", "String")
                        .put("isEditable", true).put("isOptional", true)
                        .put("description", "Name of the Glue database subscriptions are granted into");
                parameters.addObject().put("keyName", "workgroupName").put("fieldType", "String")
                        .put("isEditable", true).put("isOptional", true)
                        .put("description", "Name of the Athena workgroup");
            } else if ("DefaultDataWarehouse".equals(definition[0])) {
                parameters.addObject().put("keyName", "connectionName").put("fieldType", "String")
                        .put("isEditable", true).put("isOptional", false)
                        .put("description", "Name of the Redshift connection");
                parameters.addObject().put("keyName", "dbName").put("fieldType", "String")
                        .put("isEditable", true).put("isOptional", false).put("description", "Redshift database");
            }
            blueprint.putArray("glossaryTerms");
            blueprint.put("blueprintCategory", definition[2]);
            blueprint.put("createdAt", 1700000000.0);
            blueprint.put("updatedAt", 1700000000.0);
            blueprint.putObject("_").put("managed", true);
            result.add(blueprint);
        }
        return result;
    }

    private List<ObjectNode> blueprints(String region, String domainId) {
        List<ObjectNode> result = managed(region, domainId);
        result.addAll(store.list(region, domainId, BLUEPRINTS));
        return result;
    }

    ObjectNode blueprint(String region, String domainId, String identifier) {
        for (ObjectNode blueprint : blueprints(region, domainId)) {
            if (identifier.equals(blueprint.path("id").asText())
                    || identifier.equals(blueprint.path("name").asText())) {
                return blueprint;
            }
        }
        throw DataZoneStore.notFound("Environment blueprint " + identifier + " was not found.");
    }

    public synchronized ObjectNode listEnvironmentBlueprints(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListEnvironmentBlueprints")
                .path("id").asText();
        String name = optional(input, "name");
        Boolean managed = present(input, "managed") ? input.get("managed").asBoolean() : null;
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode blueprint : blueprints(region, domainId)) {
            boolean isManaged = blueprint.path("_").path("managed").asBoolean(false);
            if ((name == null || name.equals(blueprint.path("name").asText()))
                    && (managed == null || managed == isManaged)) {
                items.add(store.view(blueprint, "id", "name", "description", "provider", "provisioningProperties",
                        "createdAt", "updatedAt", "blueprintCategory"));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    public synchronized ObjectNode getEnvironmentBlueprint(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetEnvironmentBlueprint")
                .path("id").asText();
        return store.view(blueprint(region, domainId, text(input, "identifier", 64)));
    }

    public synchronized ObjectNode createEnvironmentBlueprint(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateEnvironmentBlueprint")
                .path("id").asText();
        String name = text(input, "name", 64);
        for (ObjectNode existing : blueprints(region, domainId)) {
            if (name.equals(existing.path("name").asText())) {
                throw DataZoneStore.conflict("An environment blueprint named " + name + " already exists.");
            }
        }
        String template = text(input.path("provisioningProperties").path("cloudFormation"), "templateUrl", 2048);
        String id = store.id();
        double now = now();
        ObjectNode blueprint = store.object();
        blueprint.put("id", id);
        blueprint.put("name", name);
        if (present(input, "description")) {
            blueprint.put("description", input.get("description").asText());
        }
        blueprint.put("provider", "Custom");
        blueprint.putObject("provisioningProperties").putObject("cloudFormation").put("templateUrl", template);
        blueprint.set("userParameters", input.path("userParameters").isArray()
                ? input.get("userParameters").deepCopy() : store.array());
        if (present(input, "blueprintCategory")) {
            blueprint.put("blueprintCategory", input.get("blueprintCategory").asText());
        }
        blueprint.put("createdAt", now);
        blueprint.put("updatedAt", now);
        store.put(region, domainId, BLUEPRINTS, id, blueprint);
        return store.view(blueprint);
    }

    public synchronized ObjectNode updateEnvironmentBlueprint(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "UpdateEnvironmentBlueprint")
                .path("id").asText();
        String id = text(input, "identifier", 64);
        ObjectNode blueprint = store.require(region, domainId, BLUEPRINTS, id, "Environment blueprint");
        if (present(input, "description")) {
            blueprint.put("description", input.get("description").asText());
        }
        if (input.has("provisioningProperties")) {
            blueprint.putObject("provisioningProperties").putObject("cloudFormation").put("templateUrl",
                    text(input.path("provisioningProperties").path("cloudFormation"), "templateUrl", 2048));
        }
        if (input.has("userParameters")) {
            blueprint.set("userParameters", input.get("userParameters").deepCopy());
        }
        blueprint.put("updatedAt", now());
        store.put(region, domainId, BLUEPRINTS, id, blueprint);
        return store.view(blueprint);
    }

    public synchronized ObjectNode deleteEnvironmentBlueprint(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteEnvironmentBlueprint")
                .path("id").asText();
        String id = text(input, "identifier", 64);
        store.require(region, domainId, BLUEPRINTS, id, "Environment blueprint");
        for (ObjectNode profile : store.list(region, domainId, PROFILES)) {
            if (id.equals(profile.path("environmentBlueprintId").asText())) {
                throw DataZoneStore.conflict("Environment blueprint " + id + " is used by environment profile "
                        + profile.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, BLUEPRINTS, id);
        store.delete(region, domainId, CONFIGURATIONS, id);
        return store.object();
    }

    // ---------------------------------------------------------------- configurations

    public synchronized ObjectNode putEnvironmentBlueprintConfiguration(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64),
                "PutEnvironmentBlueprintConfiguration").path("id").asText();
        ObjectNode blueprint = blueprint(region, domainId, text(input, "environmentBlueprintIdentifier", 64));
        String blueprintId = blueprint.path("id").asText();
        JsonNode regions = input.path("enabledRegions");
        if (!regions.isArray()) {
            throw invalid("enabledRegions is required.");
        }
        for (JsonNode enabled : regions) {
            if (!enabled.asText().matches("^[a-z]{2}-?(iso|gov)?-{1}[a-z]*-{1}[0-9]$")) {
                throw invalid("enabledRegions holds an invalid Region: " + enabled.asText() + ".");
            }
        }
        for (String field : List.of("provisioningRoleArn", "manageAccessRoleArn")) {
            if (present(input, field)) {
                requireRole(input.get(field).asText(), field);
            }
        }
        ObjectNode existing = store.get(region, domainId, CONFIGURATIONS, blueprintId).orElse(null);
        double now = now();
        ObjectNode configuration = store.object();
        configuration.put("domainId", domainId);
        configuration.put("environmentBlueprintId", blueprintId);
        for (String field : List.of("provisioningRoleArn", "manageAccessRoleArn",
                "environmentRolePermissionBoundary")) {
            if (present(input, field)) {
                configuration.put(field, input.get(field).asText());
            }
        }
        configuration.set("enabledRegions", regions.deepCopy());
        configuration.set("regionalParameters", input.path("regionalParameters").isObject()
                ? input.get("regionalParameters").deepCopy() : store.object());
        if (input.has("allowUserProvidedConfigurations")) {
            configuration.put("allowUserProvidedConfigurations", input.get("allowUserProvidedConfigurations")
                    .asBoolean());
        }
        if (input.has("resourceConfigurations")) {
            configuration.set("resourceConfigurations", input.get("resourceConfigurations").deepCopy());
        }
        if (input.has("provisioningConfigurations")) {
            configuration.set("provisioningConfigurations", input.get("provisioningConfigurations").deepCopy());
        }
        configuration.put("createdAt", existing == null ? now : existing.path("createdAt").asDouble(now));
        configuration.put("updatedAt", now);
        if (input.has("globalParameters")) {
            configuration.putObject("_").set("globalParameters", input.get("globalParameters").deepCopy());
        }
        store.put(region, domainId, CONFIGURATIONS, blueprintId, configuration);
        return store.view(configuration);
    }

    public synchronized ObjectNode getEnvironmentBlueprintConfiguration(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64),
                "GetEnvironmentBlueprintConfiguration").path("id").asText();
        String blueprintId = blueprint(region, domainId, text(input, "environmentBlueprintIdentifier", 64)).path("id")
                .asText();
        return store.view(store.require(region, domainId, CONFIGURATIONS, blueprintId,
                "Environment blueprint configuration"));
    }

    public synchronized ObjectNode deleteEnvironmentBlueprintConfiguration(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64),
                "DeleteEnvironmentBlueprintConfiguration").path("id").asText();
        String blueprintId = blueprint(region, domainId, text(input, "environmentBlueprintIdentifier", 64)).path("id")
                .asText();
        store.require(region, domainId, CONFIGURATIONS, blueprintId, "Environment blueprint configuration");
        List<String> using = new ArrayList<>();
        for (ObjectNode environment : store.list(region, domainId, ENVIRONMENTS)) {
            settle(region, domainId, environment);
            if (blueprintId.equals(environment.path("environmentBlueprintId").asText())
                    && store.get(region, domainId, ENVIRONMENTS, environment.path("id").asText()).isPresent()) {
                using.add(environment.path("id").asText());
            }
        }
        if (!using.isEmpty()) {
            throw invalid("The environment blueprint configuration is still referenced by the following environments: "
                    + String.join(", ", using));
        }
        store.delete(region, domainId, CONFIGURATIONS, blueprintId);
        return store.object();
    }

    public synchronized ObjectNode listEnvironmentBlueprintConfigurations(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64),
                "ListEnvironmentBlueprintConfigurations").path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode configuration : store.list(region, domainId, CONFIGURATIONS)) {
            items.add(store.view(configuration));
        }
        return store.page(items, input, "items", item -> item.path("environmentBlueprintId").asText());
    }

    // ---------------------------------------------------------------- profiles

    public synchronized ObjectNode createEnvironmentProfile(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateEnvironmentProfile")
                .path("id").asText();
        String name = text(input, "name", 64);
        ObjectNode blueprint = blueprint(region, domainId, text(input, "environmentBlueprintIdentifier", 64));
        String projectId = text(input, "projectIdentifier", 64);
        core.project(region, domainId, projectId);
        for (ObjectNode existing : store.list(region, domainId, PROFILES)) {
            if (name.equals(existing.path("name").asText()) && projectId.equals(existing.path("projectId").asText())) {
                throw DataZoneStore.conflict("An environment profile named " + name + " already exists.");
            }
        }
        String accountId = present(input, "awsAccountId") ? input.get("awsAccountId").asText() : store.account();
        String accountRegion = present(input, "awsAccountRegion") ? input.get("awsAccountRegion").asText() : region;
        requireEnabled(region, domainId, blueprint, accountId, accountRegion);
        String id = store.id();
        double now = now();
        ObjectNode profile = store.object();
        profile.put("id", id);
        profile.put("domainId", domainId);
        profile.put("awsAccountId", accountId);
        profile.put("awsAccountRegion", accountRegion);
        profile.put("createdBy", core.ensureProfile(region, domainId, core.callerPrincipal(), "IAM_ROLE").path("id")
                .asText());
        profile.put("createdAt", now);
        profile.put("updatedAt", now);
        profile.put("name", name);
        if (present(input, "description")) {
            profile.put("description", input.get("description").asText());
        }
        profile.put("environmentBlueprintId", blueprint.path("id").asText());
        profile.put("projectId", projectId);
        profile.set("userParameters", parameters(blueprint, input.path("userParameters")));
        store.put(region, domainId, PROFILES, id, profile);
        return store.view(profile);
    }

    public synchronized ObjectNode getEnvironmentProfile(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetEnvironmentProfile").path("id")
                .asText();
        return store.view(store.require(region, domainId, PROFILES, text(input, "identifier", 64),
                "Environment profile"));
    }

    public synchronized ObjectNode updateEnvironmentProfile(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "UpdateEnvironmentProfile")
                .path("id").asText();
        String id = text(input, "identifier", 64);
        ObjectNode profile = store.require(region, domainId, PROFILES, id, "Environment profile");
        for (String field : List.of("name", "description", "awsAccountId", "awsAccountRegion")) {
            if (present(input, field)) {
                profile.put(field, input.get(field).asText());
            }
        }
        if (input.has("userParameters")) {
            profile.set("userParameters", parameters(blueprint(region, domainId,
                    profile.path("environmentBlueprintId").asText()), input.get("userParameters")));
        }
        profile.put("updatedAt", now());
        store.put(region, domainId, PROFILES, id, profile);
        return store.view(profile);
    }

    public synchronized ObjectNode deleteEnvironmentProfile(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "DeleteEnvironmentProfile")
                .path("id").asText();
        String id = text(input, "identifier", 64);
        store.require(region, domainId, PROFILES, id, "Environment profile");
        for (ObjectNode environment : store.list(region, domainId, ENVIRONMENTS)) {
            if (id.equals(environment.path("environmentProfileId").asText())
                    && !"DELETING".equals(environment.path("status").asText())) {
                throw DataZoneStore.conflict("Environment profile " + id + " is used by environment "
                        + environment.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, PROFILES, id);
        return store.object();
    }

    public synchronized ObjectNode listEnvironmentProfiles(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListEnvironmentProfiles")
                .path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : store.list(region, domainId, PROFILES)) {
            if (matches(input, "awsAccountId", profile, "awsAccountId")
                    && matches(input, "awsAccountRegion", profile, "awsAccountRegion")
                    && matches(input, "environmentBlueprintIdentifier", profile, "environmentBlueprintId")
                    && matches(input, "projectIdentifier", profile, "projectId")
                    && matches(input, "name", profile, "name")) {
                ObjectNode summary = store.view(profile);
                summary.remove("userParameters");
                items.add(summary);
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    private static boolean matches(JsonNode input, String field, JsonNode value, String valueField) {
        return !present(input, field) || input.get(field).asText().equals(value.path(valueField).asText());
    }

    private ArrayNode parameters(ObjectNode blueprint, JsonNode values) {
        Map<String, String> given = new LinkedHashMap<>();
        if (values.isArray()) {
            for (JsonNode value : values) {
                given.put(value.path("name").asText(), value.path("value").asText());
            }
        }
        ArrayNode result = store.array();
        for (JsonNode definition : blueprint.path("userParameters")) {
            ObjectNode parameter = ((ObjectNode) definition).deepCopy();
            String key = definition.path("keyName").asText();
            if (given.containsKey(key)) {
                parameter.put("defaultValue", given.remove(key));
            } else if (!definition.path("isOptional").asBoolean(true) && !definition.hasNonNull("defaultValue")) {
                throw invalid("Parameter " + key + " of blueprint " + blueprint.path("name").asText()
                        + " is required.");
            }
            result.add(parameter);
        }
        if (!given.isEmpty()) {
            throw invalid("Blueprint " + blueprint.path("name").asText() + " has no parameter "
                    + String.join(", ", given.keySet()) + ".");
        }
        return result;
    }

    private ObjectNode requireEnabled(String region, String domainId, ObjectNode blueprint, String accountId,
                                      String accountRegion) {
        ObjectNode configuration = store.get(region, domainId, CONFIGURATIONS, blueprint.path("id").asText())
                .orElseThrow(() -> invalid("Environment blueprint " + blueprint.path("name").asText()
                        + " is not enabled in domain " + domainId + "."));
        boolean enabled = false;
        for (JsonNode value : configuration.path("enabledRegions")) {
            enabled |= accountRegion.equals(value.asText());
        }
        if (!enabled) {
            throw invalid("Environment blueprint " + blueprint.path("name").asText() + " is not enabled in "
                    + accountRegion + ".");
        }
        if (!accountId.equals(store.account())) {
            throw invalid("Account " + accountId + " is not associated with domain " + domainId + ".");
        }
        return configuration;
    }

    // ---------------------------------------------------------------- environments

    public synchronized ObjectNode createEnvironment(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateEnvironment").path("id")
                .asText();
        String projectId = text(input, "projectIdentifier", 64);
        ObjectNode project = core.project(region, domainId, projectId);
        String name = text(input, "name", 64);
        for (ObjectNode existing : store.list(region, domainId, ENVIRONMENTS)) {
            if (name.equals(existing.path("name").asText()) && projectId.equals(existing.path("projectId").asText())
                    && !"DELETING".equals(existing.path("status").asText())) {
                throw DataZoneStore.conflict("An environment named " + name + " already exists in project "
                        + projectId + ".");
            }
        }
        ObjectNode profile = null;
        ObjectNode blueprint;
        String accountId;
        String accountRegion;
        if (present(input, "environmentProfileIdentifier")) {
            profile = store.require(region, domainId, PROFILES, input.get("environmentProfileIdentifier").asText(),
                    "Environment profile");
            blueprint = blueprint(region, domainId, profile.path("environmentBlueprintId").asText());
            accountId = profile.path("awsAccountId").asText();
            accountRegion = profile.path("awsAccountRegion").asText();
        } else {
            blueprint = blueprint(region, domainId, text(input, "environmentBlueprintIdentifier", 64));
            accountId = present(input, "environmentAccountIdentifier")
                    ? input.get("environmentAccountIdentifier").asText() : store.account();
            accountRegion = present(input, "environmentAccountRegion")
                    ? input.get("environmentAccountRegion").asText() : region;
        }
        ObjectNode configuration = requireEnabled(region, domainId, blueprint, accountId, accountRegion);
        ArrayNode parameters = parameters(blueprint, input.path("userParameters"));
        if (profile != null) {
            for (JsonNode inherited : profile.path("userParameters")) {
                boolean given = false;
                for (JsonNode value : input.path("userParameters")) {
                    given |= inherited.path("keyName").asText().equals(value.path("name").asText());
                }
                if (!given && inherited.hasNonNull("defaultValue")) {
                    for (JsonNode parameter : parameters) {
                        if (parameter.path("keyName").asText().equals(inherited.path("keyName").asText())) {
                            ((ObjectNode) parameter).put("defaultValue", inherited.get("defaultValue").asText());
                        }
                    }
                }
            }
        }
        String id = store.id();
        double now = now();
        ObjectNode environment = store.object();
        environment.put("projectId", projectId);
        environment.put("id", id);
        environment.put("domainId", domainId);
        environment.put("createdBy", core.ensureProfile(region, domainId, core.callerPrincipal(), "IAM_ROLE")
                .path("id").asText());
        environment.put("createdAt", now);
        environment.put("updatedAt", now);
        environment.put("name", name);
        if (present(input, "description")) {
            environment.put("description", input.get("description").asText());
        }
        if (profile != null) {
            environment.put("environmentProfileId", profile.path("id").asText());
        }
        environment.put("awsAccountId", accountId);
        environment.put("awsAccountRegion", accountRegion);
        environment.put("provider", PROVIDER);
        environment.set("environmentActions", store.array());
        environment.set("glossaryTerms", input.path("glossaryTerms").isArray() ? input.get("glossaryTerms").deepCopy()
                : store.array());
        environment.set("userParameters", parameters);
        environment.set("provisioningProperties", blueprint.path("provisioningProperties").deepCopy());
        environment.set("deploymentProperties", blueprint.path("deploymentProperties").isObject()
                ? blueprint.get("deploymentProperties").deepCopy() : store.object());
        environment.put("environmentBlueprintId", blueprint.path("id").asText());
        String deploymentId = store.id();
        ObjectNode deployment = environment.putObject("lastDeployment");
        deployment.put("deploymentId", deploymentId);
        deployment.put("deploymentType", "CREATE");
        deployment.put("deploymentStatus", "IN_PROGRESS");
        deployment.putArray("messages");
        deployment.put("isDeploymentComplete", false);
        ArrayNode resources = environment.putArray("provisionedResources");
        String failure = provision(accountRegion, domainId, project, environment, blueprint, configuration, resources);
        if (failure != null) {
            environment.put("status", "CREATE_FAILED");
            deployment.put("deploymentStatus", "FAILED");
            deployment.putObject("failureReason").put("code", "ProvisioningFailed").put("message", failure);
            deployment.put("isDeploymentComplete", true);
        } else {
            environment.put("status", transitionMillis <= 0 ? "ACTIVE" : "CREATING");
            if (transitionMillis <= 0) {
                completeDeployment(environment);
            }
        }
        environment.putObject("_").put("readyAt", System.currentTimeMillis() + transitionMillis);
        store.put(region, domainId, ENVIRONMENTS, id, environment);
        return store.view(environment);
    }

    /** Provisions what the blueprint's stack provisions; returns why it failed, or {@code null}. */
    private String provision(String accountRegion, String domainId, ObjectNode project, ObjectNode environment,
                             ObjectNode blueprint, ObjectNode configuration, ArrayNode resources) {
        String provisioningRole = configuration.path("provisioningRoleArn").asText(null);
        if (provisioningRole == null) {
            return "The blueprint configuration has no provisioning role.";
        }
        try {
            core.role(provisioningRole, "provisioningRoleArn");
        } catch (AwsException e) {
            return e.getMessage();
        }
        String id = environment.path("id").asText();
        String slug = slug(environment.path("name").asText());
        Map<String, String> tags = Map.of("AmazonDataZoneDomain", domainId,
                "AmazonDataZoneProject", project.path("id").asText(), "AmazonDataZoneEnvironment", id);
        String userRole = "datazone_usr_role_" + project.path("id").asText() + "_" + id;
        String userRoleArn;
        try {
            String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                    + "{\"Service\":\"" + SERVICE_PRINCIPAL + "\"},"
                    + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}";
            userRoleArn = iam.get().createRole(userRole.length() > 64 ? userRole.substring(0, 64) : userRole, "/",
                    trust, "DataZone environment role for " + environment.path("name").asText(), 3600, tags)
                    .getArn();
        } catch (AwsException e) {
            return "Could not create the environment role: " + e.getMessage();
        }
        environment.putObject("_");
        resources.addObject().put("provider", PROVIDER).put("name", "userRoleArn").put("value", userRoleArn)
                .put("type", "IAM_ROLE");
        if (!"DefaultDataLake".equals(blueprint.path("name").asText())) {
            return null;
        }
        String location = configuration.path("regionalParameters").path(accountRegion).path("S3Location").asText(null);
        if (location != null) {
            String bucket = location.replaceFirst("^s3://", "").split("/", 2)[0];
            if (s3.isResolvable() && !s3.get().bucketExists(bucket)) {
                return "The S3Location bucket " + bucket + " does not exist.";
            }
            resources.addObject().put("provider", PROVIDER).put("name", "s3BucketPath")
                    .put("value", location.replaceAll("/+$", "") + "/" + domainId + "/" + project.path("id").asText()
                            + "/" + id).put("type", "S3_PATH");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode parameter : environment.path("userParameters")) {
            if (parameter.hasNonNull("defaultValue")) {
                values.put(parameter.path("keyName").asText(), parameter.get("defaultValue").asText());
            }
        }
        String suffix = id.substring(0, 6);
        String producer = values.getOrDefault("producerGlueDbName", slug + "_pub_db_" + suffix);
        String consumer = values.getOrDefault("consumerGlueDbName", slug + "_sub_db_" + suffix);
        String workgroup = values.getOrDefault("workgroupName", slug + "-wg-" + suffix);
        String logGroup = "datazone-" + id;
        addLakeFormationAdmin(accountRegion, configuration.path("manageAccessRoleArn").asText(null));
        try {
            for (String database : List.of(producer, consumer)) {
                Database glueDatabase = new Database();
                glueDatabase.setName(database);
                glueDatabase.setDescription("DataZone environment " + id);
                glueDatabase.setCreateTime(Instant.now());
                glue.get().createDatabase(glueDatabase, tags, accountRegion);
            }
            ObjectNode workgroupRequest = store.object();
            workgroupRequest.put("Name", workgroup);
            workgroupRequest.put("Description", "DataZone environment " + id);
            if (location != null) {
                workgroupRequest.putObject("Configuration").putObject("ResultConfiguration")
                        .put("OutputLocation", location.replaceAll("/+$", "") + "/athena-results/" + id + "/");
            }
            athena.get().createWorkGroup(mapper.convertValue(workgroupRequest, CreateWorkGroupRequest.class),
                    accountRegion);
            logs.get().createLogGroup(logGroup, null, tags, accountRegion);
        } catch (AwsException e) {
            return "Could not provision the data lake: " + e.getMessage();
        }
        resources.addObject().put("provider", PROVIDER).put("name", "glueProducerDBName").put("value", producer)
                .put("type", "GLUE_DATABASE");
        resources.addObject().put("provider", PROVIDER).put("name", "glueConsumerDBName").put("value", consumer)
                .put("type", "GLUE_DATABASE");
        resources.addObject().put("provider", PROVIDER).put("name", "athenaWorkGroupName").put("value", workgroup)
                .put("type", "ATHENA_WORKGROUP");
        resources.addObject().put("provider", PROVIDER).put("name", "logGroupArn")
                .put("value", store.regions().buildArn("logs", accountRegion, store.account(),
                        "log-group:" + logGroup)).put("type", "CLOUDWATCH_LOG_GROUP");
        return null;
    }

    /** DataZone makes the blueprint's manage-access role a Lake Formation administrator to grant subscriptions. */
    private void addLakeFormationAdmin(String region, String roleArn) {
        if (roleArn == null || !lakeFormation.isResolvable()) {
            return;
        }
        try {
            DataLakeSettings settings = lakeFormation.get().getDataLakeSettings(region,
                    new GetDataLakeSettingsRequest()).getDataLakeSettings();
            List<DataLakePrincipal> admins = settings.getDataLakeAdmins() == null ? new ArrayList<>()
                    : new ArrayList<>(settings.getDataLakeAdmins());
            for (DataLakePrincipal admin : admins) {
                if (roleArn.equals(admin.getDataLakePrincipalIdentifier())) {
                    return;
                }
            }
            DataLakePrincipal principal = new DataLakePrincipal();
            principal.setDataLakePrincipalIdentifier(roleArn);
            admins.add(principal);
            settings.setDataLakeAdmins(admins);
            PutDataLakeSettingsRequest request = new PutDataLakeSettingsRequest();
            request.setDataLakeSettings(settings);
            lakeFormation.get().putDataLakeSettings(region, request);
        } catch (RuntimeException e) {
            LOG.debugv("Could not add a Lake Formation administrator: {0}", e.getMessage());
        }
    }

    private static void completeDeployment(ObjectNode environment) {
        ObjectNode deployment = (ObjectNode) environment.get("lastDeployment");
        deployment.put("deploymentStatus", "SUCCESSFUL");
        deployment.put("isDeploymentComplete", true);
    }

    /** Applies elapsed transitions; returns null once a deleted environment is gone. */
    ObjectNode settle(String region, String domainId, ObjectNode environment) {
        if (System.currentTimeMillis() < environment.path("_").path("readyAt").asLong()) {
            return environment;
        }
        String id = environment.path("id").asText();
        switch (environment.path("status").asText()) {
            case "CREATING", "UPDATING" -> {
                environment.put("status", "ACTIVE");
                completeDeployment(environment);
                store.put(region, domainId, ENVIRONMENTS, id, environment);
            }
            case "DELETING" -> {
                store.delete(region, domainId, ENVIRONMENTS, id);
                for (ObjectNode action : store.list(region, domainId, ACTIONS)) {
                    if (id.equals(action.path("environmentId").asText())) {
                        store.delete(region, domainId, ACTIONS, action.path("id").asText());
                    }
                }
                return null;
            }
            default -> {
            }
        }
        return environment;
    }

    ObjectNode environment(String region, String domainId, String id) {
        ObjectNode environment = store.get(region, domainId, ENVIRONMENTS, id)
                .map(value -> settle(region, domainId, value)).orElse(null);
        if (environment == null) {
            throw DataZoneStore.notFound("Environment " + id + " was not found.");
        }
        return environment;
    }

    public synchronized ObjectNode getEnvironment(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetEnvironment").path("id")
                .asText();
        return store.view(environment(region, domainId, text(input, "identifier", 64)));
    }

    public synchronized ObjectNode updateEnvironment(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "UpdateEnvironment").path("id")
                .asText();
        ObjectNode environment = environment(region, domainId, text(input, "identifier", 64));
        if (!"ACTIVE".equals(environment.path("status").asText())) {
            throw DataZoneStore.conflict("Environment " + environment.path("id").asText() + " is "
                    + environment.path("status").asText() + ".");
        }
        for (String field : List.of("name", "description")) {
            if (present(input, field)) {
                environment.put(field, input.get(field).asText());
            }
        }
        if (input.has("glossaryTerms")) {
            environment.set("glossaryTerms", input.get("glossaryTerms").deepCopy());
        }
        if (input.has("userParameters")) {
            ObjectNode blueprint = blueprint(region, domainId, environment.path("environmentBlueprintId").asText());
            for (JsonNode value : input.get("userParameters")) {
                boolean known = false;
                for (JsonNode parameter : environment.path("userParameters")) {
                    if (parameter.path("keyName").asText().equals(value.path("name").asText())) {
                        known = true;
                        if (!parameter.path("isUpdateSupported").asBoolean(false)
                                && !"Custom".equals(blueprint.path("provider").asText())) {
                            throw invalid("Parameter " + value.path("name").asText() + " cannot be updated.");
                        }
                        ((ObjectNode) parameter).put("defaultValue", value.path("value").asText());
                    }
                }
                if (!known) {
                    throw invalid("Environment " + environment.path("id").asText() + " has no parameter "
                            + value.path("name").asText() + ".");
                }
            }
        }
        environment.put("updatedAt", now());
        store.put(region, domainId, ENVIRONMENTS, environment.path("id").asText(), environment);
        return store.view(environment);
    }

    public synchronized ObjectNode deleteEnvironment(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "DeleteEnvironment").path("id")
                .asText();
        ObjectNode environment = environment(region, domainId, text(input, "identifier", 64));
        String status = environment.path("status").asText();
        if (Set.of("CREATING", "UPDATING").contains(status)) {
            throw DataZoneStore.conflict("Environment " + environment.path("id").asText() + " is " + status + ".");
        }
        if ("DELETING".equals(status)) {
            return store.object();
        }
        for (JsonNode resource : environment.path("provisionedResources")) {
            if ("IAM_ROLE".equals(resource.path("type").asText()) && iam.isResolvable()) {
                String roleArn = resource.path("value").asText();
                try {
                    iam.get().deleteRole(roleArn.substring(roleArn.lastIndexOf('/') + 1));
                } catch (AwsException e) {
                    LOG.debugv("Could not delete environment role {0}: {1}", roleArn, e.getMessage());
                }
            }
        }
        environment.put("status", "DELETING");
        ObjectNode deployment = environment.putObject("lastDeployment");
        deployment.put("deploymentId", store.id());
        deployment.put("deploymentType", "DELETE");
        deployment.put("deploymentStatus", "IN_PROGRESS");
        deployment.put("isDeploymentComplete", false);
        ((ObjectNode) environment.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
        store.put(region, domainId, ENVIRONMENTS, environment.path("id").asText(), environment);
        settle(region, domainId, environment);
        return store.object();
    }

    public synchronized ObjectNode listEnvironments(ObjectNode input, String region) {
        String domainId;
        try {
            domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListEnvironments").path("id")
                    .asText();
        } catch (AwsException e) {
            throw DataZoneStore.notFound("Domain " + optional(input, "domainIdentifier") + " was not found.");
        }
        String projectId = text(input, "projectIdentifier", 64);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, domainId, ENVIRONMENTS)) {
            ObjectNode environment = settle(region, domainId, stored);
            if (environment == null || !projectId.equals(environment.path("projectId").asText())) {
                continue;
            }
            if (matches(input, "awsAccountId", environment, "awsAccountId")
                    && matches(input, "status", environment, "status")
                    && matches(input, "awsAccountRegion", environment, "awsAccountRegion")
                    && matches(input, "environmentProfileIdentifier", environment, "environmentProfileId")
                    && matches(input, "environmentBlueprintIdentifier", environment, "environmentBlueprintId")
                    && matches(input, "provider", environment, "provider")
                    && matches(input, "name", environment, "name")) {
                items.add(store.view(environment, "projectId", "id", "domainId", "createdBy", "createdAt",
                        "updatedAt", "name", "description", "environmentProfileId", "awsAccountId",
                        "awsAccountRegion", "provider", "status"));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    public synchronized ObjectNode getEnvironmentCredentials(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetEnvironmentCredentials")
                .path("id").asText();
        ObjectNode environment = environment(region, domainId, text(input, "environmentIdentifier", 64));
        if (!"ACTIVE".equals(environment.path("status").asText())) {
            throw DataZoneStore.conflict("Environment " + environment.path("id").asText() + " is not ACTIVE.");
        }
        String roleArn = null;
        for (JsonNode resource : environment.path("provisionedResources")) {
            if ("userRoleArn".equals(resource.path("name").asText())) {
                roleArn = resource.path("value").asText();
            }
        }
        if (roleArn == null || !iam.isResolvable()) {
            throw DataZoneStore.notFound("Environment " + environment.path("id").asText()
                    + " has no environment role.");
        }
        IamService.RoleSessionCredentials credentials = iam.get().mintRoleSession(roleArn);
        return store.object().put("accessKeyId", credentials.accessKeyId())
                .put("secretAccessKey", credentials.secretAccessKey()).put("sessionToken", credentials.sessionToken())
                .put("expiration", now() + 3600);
    }

    // ---------------------------------------------------------------- environment actions

    public synchronized ObjectNode createEnvironmentAction(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateEnvironmentAction")
                .path("id").asText();
        String environmentId = environment(region, domainId, text(input, "environmentIdentifier", 64)).path("id")
                .asText();
        String uri = text(input.path("parameters").path("awsConsoleLink"), "uri", 2048);
        String id = store.id();
        ObjectNode action = store.object();
        action.put("domainId", domainId);
        action.put("environmentId", environmentId);
        action.put("id", id);
        action.put("name", text(input, "name", 64));
        action.putObject("parameters").putObject("awsConsoleLink").put("uri", uri);
        if (present(input, "description")) {
            action.put("description", input.get("description").asText());
        }
        action.put("createdAt", now());
        store.put(region, domainId, ACTIONS, id, action);
        return actionView(action);
    }

    private ObjectNode action(ObjectNode input, String region, String operation) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), operation).path("id").asText();
        String environmentId = text(input, "environmentIdentifier", 64);
        ObjectNode action = store.require(region, domainId, ACTIONS, text(input, "identifier", 64),
                "Environment action");
        if (!environmentId.equals(action.path("environmentId").asText())) {
            throw DataZoneStore.notFound("Environment action " + action.path("id").asText() + " was not found.");
        }
        return action;
    }

    public synchronized ObjectNode getEnvironmentAction(ObjectNode input, String region) {
        return actionView(action(input, region, "GetEnvironmentAction"));
    }

    public synchronized ObjectNode updateEnvironmentAction(ObjectNode input, String region) {
        ObjectNode action = action(input, region, "UpdateEnvironmentAction");
        if (present(input, "name")) {
            action.put("name", input.get("name").asText());
        }
        if (present(input, "description")) {
            action.put("description", input.get("description").asText());
        }
        if (input.has("parameters")) {
            action.putObject("parameters").putObject("awsConsoleLink").put("uri",
                    text(input.path("parameters").path("awsConsoleLink"), "uri", 2048));
        }
        store.put(region, action.path("domainId").asText(), ACTIONS, action.path("id").asText(), action);
        return actionView(action);
    }

    public synchronized ObjectNode deleteEnvironmentAction(ObjectNode input, String region) {
        ObjectNode action = action(input, region, "DeleteEnvironmentAction");
        store.delete(region, action.path("domainId").asText(), ACTIONS, action.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listEnvironmentActions(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListEnvironmentActions")
                .path("id").asText();
        String environmentId = environment(region, domainId, text(input, "environmentIdentifier", 64)).path("id")
                .asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode action : store.list(region, domainId, ACTIONS)) {
            if (environmentId.equals(action.path("environmentId").asText())) {
                items.add(actionView(action));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    private ObjectNode actionView(ObjectNode action) {
        ObjectNode view = store.view(action);
        view.remove("createdAt");
        return view;
    }

    // ---------------------------------------------------------------- subscription targets

    public synchronized ObjectNode createSubscriptionTarget(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateSubscriptionTarget")
                .path("id").asText();
        ObjectNode environment = environment(region, domainId, text(input, "environmentIdentifier", 64));
        requireRole(text(input, "manageAccessRole", 2048), "manageAccessRole");
        String id = store.id();
        double now = now();
        ObjectNode target = store.object();
        target.put("id", id);
        target.set("authorizedPrincipals", input.path("authorizedPrincipals").deepCopy());
        target.put("domainId", domainId);
        target.put("projectId", environment.path("projectId").asText());
        target.put("environmentId", environment.path("id").asText());
        target.put("name", text(input, "name", 256));
        target.put("type", text(input, "type", 256));
        String creator = core.ensureProfile(region, domainId, core.callerPrincipal(), "IAM_ROLE").path("id").asText();
        target.put("createdBy", creator);
        target.put("createdAt", now);
        target.put("updatedAt", now);
        target.put("manageAccessRole", input.get("manageAccessRole").asText());
        target.set("applicableAssetTypes", input.path("applicableAssetTypes").deepCopy());
        target.set("subscriptionTargetConfig", input.path("subscriptionTargetConfig").deepCopy());
        target.put("provider", present(input, "provider") ? input.get("provider").asText() : PROVIDER);
        target.put("subscriptionGrantCreationMode", present(input, "subscriptionGrantCreationMode")
                ? input.get("subscriptionGrantCreationMode").asText() : "AUTOMATIC");
        store.put(region, domainId, TARGETS, id, target);
        return store.view(target);
    }

    private ObjectNode target(ObjectNode input, String region, String operation) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), operation).path("id").asText();
        ObjectNode target = store.require(region, domainId, TARGETS, text(input, "identifier", 64),
                "Subscription target");
        if (!text(input, "environmentIdentifier", 64).equals(target.path("environmentId").asText())) {
            throw DataZoneStore.notFound("Subscription target " + target.path("id").asText() + " was not found.");
        }
        return target;
    }

    public synchronized ObjectNode getSubscriptionTarget(ObjectNode input, String region) {
        return store.view(target(input, region, "GetSubscriptionTarget"));
    }

    public synchronized ObjectNode updateSubscriptionTarget(ObjectNode input, String region) {
        ObjectNode target = target(input, region, "UpdateSubscriptionTarget");
        for (String field : List.of("name", "provider", "subscriptionGrantCreationMode")) {
            if (present(input, field)) {
                target.put(field, input.get(field).asText());
            }
        }
        if (present(input, "manageAccessRole")) {
            target.put("manageAccessRole", requireRole(input.get("manageAccessRole").asText(), "manageAccessRole"));
        }
        for (String field : List.of("authorizedPrincipals", "applicableAssetTypes", "subscriptionTargetConfig")) {
            if (input.has(field)) {
                target.set(field, input.get(field).deepCopy());
            }
        }
        target.put("updatedAt", now());
        store.put(region, target.path("domainId").asText(), TARGETS, target.path("id").asText(), target);
        return store.view(target);
    }

    public synchronized ObjectNode deleteSubscriptionTarget(ObjectNode input, String region) {
        ObjectNode target = target(input, region, "DeleteSubscriptionTarget");
        store.delete(region, target.path("domainId").asText(), TARGETS, target.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listSubscriptionTargets(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListSubscriptionTargets")
                .path("id").asText();
        String environmentId = text(input, "environmentIdentifier", 64);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode target : store.list(region, domainId, TARGETS)) {
            if (environmentId.equals(target.path("environmentId").asText())) {
                items.add(store.view(target));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    List<ObjectNode> targetsOf(String region, String domainId, String environmentId) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode target : store.list(region, domainId, TARGETS)) {
            if (environmentId.equals(target.path("environmentId").asText())) {
                result.add(target);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    /** A role ARN in the account must name an existing role. */
    private String requireRole(String roleArn, String field) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be an IAM role ARN.");
        }
        if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
            throw invalid(field + " must be an IAM role ARN.");
        }
        if (iam.isResolvable() && arn.accountId().equals(store.account())) {
            try {
                iam.get().getRole(roleArn.substring(roleArn.lastIndexOf('/') + 1));
            } catch (AwsException e) {
                throw invalid("Role " + roleArn + " does not exist.");
            }
        }
        return roleArn;
    }

    private static String slug(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "env" : slug.length() > 32 ? slug.substring(0, 32) : slug;
    }

    private static String stableId(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            StringBuilder id = new StringBuilder();
            for (int index = 0; index < 14; index++) {
                int value = Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16) % 36;
                id.append("abcdefghijklmnopqrstuvwxyz0123456789".charAt(value));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

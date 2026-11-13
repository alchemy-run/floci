package io.floci.aws.services.greengrassv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iot.IotService;
import io.floci.aws.services.iot.IotTopicListener;
import io.floci.aws.services.iot.model.IotJob;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.s3.PreSignedUrlGenerator;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS IoT Greengrass V2's cloud side. Components are made from recipes (JSON or YAML, their S3 artifacts checked and
 * digested) or imported from Lambda function versions. A deployment to a thing is written to the thing's
 * {@code AWSManagedGreengrassV2Deployment} shadow, and one to a thing group becomes an IoT job, each carrying the
 * fleet configuration the nucleus applies. Core devices are the ones that report: a nucleus's Fleet Status Service
 * messages on {@code $aws/things/<thing>/greengrassv2/health/json} register the device and record its installed
 * components and the status of its effective deployments, and status changes are published to EventBridge.
 */
@ApplicationScoped
public class GreengrassV2Service implements Resettable, IotTopicListener {

    private static final Logger LOG = Logger.getLogger(GreengrassV2Service.class);
    private static final Pattern COMPONENT_NAME = Pattern.compile("[a-zA-Z0-9\\-_.]{1,128}");
    private static final Pattern FLEET_STATUS = Pattern.compile("\\$aws/things/([^/]+)/greengrassv2/health/json");
    private static final String DEPLOYMENT_SHADOW = "AWSManagedGreengrassV2Deployment";
    private static final String RECIPE_FORMAT = "2020-01-25";
    /** The account public components' ARNs name. */
    private static final String PUBLIC_ACCOUNT = "aws"; // partition-literal: an ARN account, not a partition
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    /** The AWS-provided components floci lists as public, at the versions it lists. */
    private static final Map<String, List<String>> PUBLIC_COMPONENTS = new LinkedHashMap<>();

    static {
        PUBLIC_COMPONENTS.put("aws.greengrass.Nucleus", List.of("2.14.3", "2.15.0"));
        PUBLIC_COMPONENTS.put("aws.greengrass.Cli", List.of("2.14.3", "2.15.0"));
        PUBLIC_COMPONENTS.put("aws.greengrass.TokenExchangeService", List.of("2.0.3"));
        PUBLIC_COMPONENTS.put("aws.greengrass.LogManager", List.of("2.3.9"));
        PUBLIC_COMPONENTS.put("aws.greengrass.ShadowManager", List.of("2.3.10"));
        PUBLIC_COMPONENTS.put("aws.greengrass.LambdaLauncher", List.of("2.0.13"));
        PUBLIC_COMPONENTS.put("aws.greengrass.LambdaRuntimes", List.of("2.0.8"));
        PUBLIC_COMPONENTS.put("aws.greengrass.LambdaManager", List.of("2.3.4"));
        PUBLIC_COMPONENTS.put("aws.greengrass.clientdevices.Auth", List.of("2.5.3"));
        PUBLIC_COMPONENTS.put("aws.greengrass.clientdevices.mqtt.Moquette", List.of("2.3.7"));
        PUBLIC_COMPONENTS.put("aws.greengrass.clientdevices.mqtt.Bridge", List.of("2.3.2"));
        PUBLIC_COMPONENTS.put("aws.greengrass.clientdevices.IPDetector", List.of("2.2.0"));
        PUBLIC_COMPONENTS.put("aws.greengrass.StreamManager", List.of("2.2.1"));
        PUBLIC_COMPONENTS.put("aws.greengrass.DockerApplicationManager", List.of("2.0.12"));
        PUBLIC_COMPONENTS.put("aws.greengrass.SecretManager", List.of("2.2.2"));
    }

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Instance<IotService> iot;
    private final Instance<S3Service> s3;
    private final Instance<LambdaService> lambda;
    private final Instance<EventBridgeService> events;
    private final Instance<PreSignedUrlGenerator> presigner;

    @Inject
    public GreengrassV2Service(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                               Instance<IotService> iot, Instance<S3Service> s3, Instance<LambdaService> lambda,
                               Instance<EventBridgeService> events, Instance<PreSignedUrlGenerator> presigner) {
        this.storage = storageFactory.create("greengrassv2", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.iot = iot;
        this.s3 = s3;
        this.lambda = lambda;
        this.events = events;
        this.presigner = presigner;
    }

    @Override
    public void clear() {
        storage.clear();
    }

    private String account() {
        return storage.accountId();
    }

    private Optional<ObjectNode> get(String key) {
        return storage.getForAccount(account(), key);
    }

    private void put(String key, ObjectNode record) {
        storage.putForAccount(account(), key, record);
    }

    private void delete(String key) {
        storage.deleteForAccount(account(), key);
    }

    private List<ObjectNode> scan(String prefix) {
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(prefix)));
        found.sort((a, b) -> Double.compare(a.path("_seq").asDouble(), b.path("_seq").asDouble()));
        return found;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("greengrass", region, account(), resource).toString();
    }

    // ---------------------------------------------------------------- components

    public synchronized ObjectNode createComponentVersion(JsonNode input, String region) {
        String clientToken = input.path("clientToken").asText(null);
        if (clientToken != null) {
            for (ObjectNode component : scan(region + "/component/")) {
                if (clientToken.equals(component.path("_clientToken").asText(null))) {
                    return componentCreated(component);
                }
            }
        }
        boolean inline = input.hasNonNull("inlineRecipe");
        boolean fromLambda = input.has("lambdaFunction");
        if (inline == fromLambda) {
            throw validation("Give exactly one of inlineRecipe and lambdaFunction.");
        }
        ObjectNode recipe;
        String format = "JSON";
        if (inline) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(input.get("inlineRecipe").asText());
            } catch (IllegalArgumentException e) {
                throw validation("inlineRecipe is not base64.");
            }
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            format = text.startsWith("{") ? "JSON" : "YAML";
            recipe = parseRecipe(text, format);
        } else {
            recipe = lambdaRecipe(input.get("lambdaFunction"), region);
        }
        String name = recipe.path("ComponentName").asText();
        String version = recipe.path("ComponentVersion").asText();
        String key = componentKey(region, name, version);
        if (get(key).isPresent()) {
            throw new AwsException("ConflictException", "Component " + name + " version " + version
                    + " already exists.", 409, Map.of("resourceId", name + ":" + version, "resourceType",
                    "COMPONENT_VERSION"));
        }
        ObjectNode component = mapper.createObjectNode();
        double created = now();
        component.put("arn", arn(region, "components:" + name + ":versions:" + version))
                .put("componentName", name).put("componentVersion", version).put("creationTimestamp", created)
                .put("publisher", recipe.path("ComponentPublisher").asText(""))
                .put("description", recipe.path("ComponentDescription").asText(""))
                .put("_seq", created).put("_format", format);
        if (clientToken != null) {
            component.put("_clientToken", clientToken);
        }
        component.set("platforms", platforms(recipe));
        Map<String, String> errors = checkArtifacts(recipe);
        component.set("_recipe", recipe);
        ObjectNode status = component.putObject("status");
        if (errors.isEmpty()) {
            status.put("componentState", "DEPLOYABLE").put("message", "NONE");
        } else {
            status.put("componentState", "FAILED").put("message", "The component's artifacts could not be read.");
            status.set("errors", mapper.valueToTree(errors));
        }
        status.put("vendorGuidance", "ACTIVE");
        component.put("_settleAt", System.currentTimeMillis() + config.services().greengrassv2().transitionMillis());
        component.set("tags", tags(input.path("tags"), mapper.createObjectNode()));
        put(key, component);
        return componentCreated(component);
    }

    private ObjectNode componentCreated(ObjectNode component) {
        ObjectNode response = mapper.createObjectNode();
        response.put("arn", component.path("arn").asText()).put("componentName", component.path("componentName")
                .asText()).put("componentVersion", component.path("componentVersion").asText())
                .put("creationTimestamp", component.path("creationTimestamp").asDouble());
        response.set("status", visibleStatus(component));
        return response;
    }

    /** A new version is REQUESTED, then INITIATED, until it settles a transition after it was made. */
    private JsonNode visibleStatus(ObjectNode component) {
        if (component.path("_settleAt").asLong() <= System.currentTimeMillis()) {
            return component.path("status").deepCopy();
        }
        ObjectNode status = component.path("status").deepCopy();
        status.put("componentState", "REQUESTED").put("message", "NONE");
        status.remove("errors");
        return status;
    }

    private ObjectNode parseRecipe(String text, String format) {
        JsonNode parsed;
        try {
            parsed = "JSON".equals(format) ? mapper.readTree(text) : YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw validation("The recipe is not valid " + format + ": " + e.getOriginalMessage());
        }
        if (parsed == null || !parsed.isObject()) {
            throw validation("The recipe must be a " + format + " object.");
        }
        ObjectNode recipe = (ObjectNode) parsed;
        String formatVersion = recipe.path("RecipeFormatVersion").asText(null);
        if (!RECIPE_FORMAT.equals(formatVersion)) {
            throw validation("RecipeFormatVersion must be " + RECIPE_FORMAT + ".");
        }
        String name = recipe.path("ComponentName").asText(null);
        if (name == null || !COMPONENT_NAME.matcher(name).matches()) {
            throw validation("ComponentName is required: 1 to 128 letters, digits, '-', '_' and '.'.");
        }
        if (name.startsWith("aws.")) {
            throw validation("Component names beginning with aws. are reserved for AWS-provided components.");
        }
        String version = recipe.path("ComponentVersion").asText(null);
        if (!GreengrassSemver.valid(version)) {
            throw validation("ComponentVersion must be a semantic version, major.minor.patch.");
        }
        recipe.path("ComponentDependencies").fields().forEachRemaining(entry -> {
            String requirement = entry.getValue().path("VersionRequirement").asText("*");
            if (!GreengrassSemver.validRequirement(requirement)) {
                throw validation("The version requirement of dependency " + entry.getKey() + ", " + requirement
                        + ", is not valid.");
            }
            String type = entry.getValue().path("DependencyType").asText("HARD");
            if (!Set.of("HARD", "SOFT").contains(type)) {
                throw validation("DependencyType of " + entry.getKey() + " must be HARD or SOFT.");
            }
        });
        JsonNode manifests = recipe.path("Manifests");
        if (!manifests.isMissingNode() && !manifests.isArray()) {
            throw validation("Manifests must be a list.");
        }
        return recipe;
    }

    /** The recipe Greengrass makes for a Lambda function version, run by the Lambda launcher. */
    private ObjectNode lambdaRecipe(JsonNode source, String region) {
        String lambdaArn = source.path("lambdaArn").asText(null);
        if (lambdaArn == null || !AwsArnUtils.isArnFor(lambdaArn, "lambda")) {
            throw validation("lambdaFunction.lambdaArn must be a Lambda function ARN with a version.");
        }
        String resource = AwsArnUtils.parse(lambdaArn).resource();
        String[] parts = resource.split(":");
        if (parts.length != 3 || !parts[2].matches("\\d+")) {
            throw validation("lambdaFunction.lambdaArn must name a published version of the function; aliases and "
                    + "$LATEST are not imported.");
        }
        try {
            lambda.get().getFunction(AwsArnUtils.parse(lambdaArn).region(), parts[1], parts[2]);
        } catch (AwsException e) {
            throw validation("Lambda function " + lambdaArn + " could not be read: " + e.getMessage());
        }
        String name = source.path("componentName").asText(parts[1]);
        String version = source.path("componentVersion").asText(parts[2] + ".0.0");
        ObjectNode recipe = mapper.createObjectNode();
        recipe.put("RecipeFormatVersion", RECIPE_FORMAT).put("ComponentName", name).put("ComponentVersion", version)
                .put("ComponentType", "aws.greengrass.lambda").put("ComponentDescription", "Lambda function "
                + lambdaArn).put("ComponentPublisher", "AWS Lambda").put("ComponentSource", lambdaArn);
        ObjectNode dependencies = recipe.putObject("ComponentDependencies");
        for (String dependency : List.of("aws.greengrass.LambdaLauncher", "aws.greengrass.TokenExchangeService",
                "aws.greengrass.LambdaRuntimes")) {
            dependencies.putObject(dependency).put("VersionRequirement", ">=2.0.0 <3.0.0")
                    .put("DependencyType", "HARD");
        }
        source.path("componentDependencies").fields().forEachRemaining(entry -> dependencies.putObject(entry.getKey())
                .put("VersionRequirement", entry.getValue().path("versionRequirement").asText("*"))
                .put("DependencyType", entry.getValue().path("dependencyType").asText("HARD")));
        ObjectNode defaults = recipe.putObject("ComponentConfiguration").putObject("DefaultConfiguration");
        defaults.putObject("lambdaExecutionParameters").set("parameters",
                source.path("componentLambdaParameters").deepCopy());
        defaults.put("containerMode", source.path("componentLambdaParameters").path("linuxProcessParams")
                .path("isolationMode").asText("GreengrassContainer"));
        ArrayNode manifests = recipe.putArray("Manifests");
        JsonNode requested = source.path("componentPlatforms");
        List<JsonNode> platforms = new ArrayList<>();
        requested.forEach(platforms::add);
        if (platforms.isEmpty()) {
            platforms.add(mapper.createObjectNode().put("name", "Linux").set("attributes",
                    mapper.createObjectNode().put("os", "linux")));
        }
        for (JsonNode platform : platforms) {
            ObjectNode manifest = manifests.addObject();
            ObjectNode attributes = manifest.putObject("Platform");
            platform.path("attributes").fields().forEachRemaining(entry -> attributes.put(entry.getKey(),
                    entry.getValue().asText()));
            manifest.putObject("Lifecycle");
            manifest.putArray("Artifacts").addObject().put("Uri", "greengrass:lambda-artifact.zip")
                    .put("Algorithm", "SHA-256");
        }
        return recipe;
    }

    /** The platforms a recipe's manifests name, as ComponentPlatform entries. */
    private ArrayNode platforms(JsonNode recipe) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode manifest : recipe.path("Manifests")) {
            JsonNode platform = manifest.path("Platform");
            ObjectNode entry = out.addObject();
            entry.put("name", platform.path("os").asText("all"));
            ObjectNode attributes = entry.putObject("attributes");
            platform.fields().forEachRemaining(field -> attributes.put(field.getKey(), field.getValue().asText()));
        }
        return out;
    }

    /** Reads every S3 artifact of a recipe, recording its digest; returns the URIs that could not be read. */
    private Map<String, String> checkArtifacts(ObjectNode recipe) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (JsonNode manifest : recipe.path("Manifests")) {
            for (JsonNode artifact : manifest.path("Artifacts")) {
                String uri = artifact.path("Uri").asText("");
                if (!uri.startsWith("s3://")) {
                    if (!uri.startsWith("greengrass:") && !uri.startsWith("docker:")) {
                        errors.put(uri, "Artifact URIs are s3://, greengrass: or docker: URIs.");
                    }
                    continue;
                }
                String[] location = uri.substring(5).split("/", 2);
                if (location.length < 2 || location[1].isEmpty()) {
                    errors.put(uri, "The S3 URI names no object.");
                    continue;
                }
                try {
                    S3Object object = s3.get().getObject(location[0], location[1]);
                    byte[] data = object.getData() == null ? new byte[0] : object.getData();
                    if (!artifact.has("Digest")) {
                        ((ObjectNode) artifact).put("Digest", Base64.getEncoder().encodeToString(sha256(data)))
                                .put("Algorithm", "SHA-256");
                    }
                } catch (AwsException e) {
                    errors.put(uri, "Failed to read the artifact from S3: " + e.getMessage());
                }
            }
        }
        return errors;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String componentKey(String region, String name, String version) {
        return region + "/component/" + name + "/" + version;
    }

    /** A component version by its ARN, private or public. */
    private ObjectNode componentVersion(String componentArn, String region) {
        String[] parts = parseComponentArn(componentArn);
        if (parts[2] == null) {
            throw validation("The ARN " + componentArn + " names a component, not a component version.");
        }
        if (PUBLIC_ACCOUNT.equals(parts[0])) {
            return publicComponent(region, parts[1], parts[2]).orElseThrow(() -> componentNotFound(componentArn));
        }
        if (!account().equals(parts[0])) {
            throw componentNotFound(componentArn);
        }
        return get(componentKey(region, parts[1], parts[2])).orElseThrow(() -> componentNotFound(componentArn));
    }

    /** The account, name and version (null for a component ARN) a component ARN names. */
    private String[] parseComponentArn(String componentArn) {
        if (!AwsArnUtils.isArnFor(componentArn, "greengrass")) {
            throw validation("The ARN " + componentArn + " is not a Greengrass component ARN.");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(componentArn);
        String[] segments = arn.resource().split(":");
        if (segments.length == 2 && "components".equals(segments[0])) {
            return new String[] {arn.accountId(), segments[1], null};
        }
        if (segments.length == 4 && "components".equals(segments[0]) && "versions".equals(segments[2])) {
            return new String[] {arn.accountId(), segments[1], segments[3]};
        }
        throw validation("The ARN " + componentArn + " is not a Greengrass component ARN.");
    }

    private Optional<ObjectNode> publicComponent(String region, String name, String version) {
        List<String> versions = PUBLIC_COMPONENTS.get(name);
        if (versions == null || !versions.contains(version)) {
            return Optional.empty();
        }
        ObjectNode component = mapper.createObjectNode();
        component.put("arn", AwsArnUtils.Arn.of("greengrass", region, PUBLIC_ACCOUNT, "components:" + name
                + ":versions:" + version).toString()).put("componentName", name).put("componentVersion", version)
                .put("creationTimestamp", 1.7e9).put("publisher", "AWS").put("description", "AWS-provided component "
                + name).put("_format", "JSON");
        component.putObject("status").put("componentState", "DEPLOYABLE").put("message", "NONE")
                .put("vendorGuidance", "ACTIVE");
        ArrayNode platforms = component.putArray("platforms");
        platforms.addObject().put("name", "linux").putObject("attributes").put("os", "linux");
        ObjectNode recipe = mapper.createObjectNode();
        recipe.put("RecipeFormatVersion", RECIPE_FORMAT).put("ComponentName", name).put("ComponentVersion", version)
                .put("ComponentPublisher", "AWS").put("ComponentType", name.equals("aws.greengrass.Nucleus")
                ? "aws.greengrass.nucleus" : "aws.greengrass.plugin");
        recipe.putArray("Manifests").addObject().putObject("Platform").put("os", "*");
        component.set("_recipe", recipe);
        component.putObject("tags");
        return Optional.of(component);
    }

    private static AwsException componentNotFound(String componentArn) {
        return new AwsException("ResourceNotFoundException", "Component version " + componentArn + " not found.",
                404, Map.of("resourceId", componentArn, "resourceType", "COMPONENT_VERSION"));
    }

    public synchronized ObjectNode describeComponent(String componentArn, String region) {
        ObjectNode component = componentVersion(componentArn, region);
        ObjectNode response = mapper.createObjectNode();
        for (String field : List.of("arn", "componentName", "componentVersion", "creationTimestamp", "publisher",
                "description", "platforms", "tags")) {
            response.set(field, component.path(field).deepCopy());
        }
        response.set("status", visibleStatus(component));
        return response;
    }

    public synchronized ObjectNode getComponent(String componentArn, String recipeOutputFormat, String region) {
        ObjectNode component = componentVersion(componentArn, region);
        String format = recipeOutputFormat == null ? "JSON" : recipeOutputFormat;
        if (!Set.of("JSON", "YAML").contains(format)) {
            throw validation("recipeOutputFormat must be JSON or YAML.");
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("recipeOutputFormat", format).put("recipe", Base64.getEncoder()
                .encodeToString(render(component.path("_recipe"), format)));
        response.set("tags", component.path("tags").deepCopy());
        return response;
    }

    private byte[] render(JsonNode recipe, String format) {
        try {
            return "YAML".equals(format) ? YAML.writeValueAsBytes(recipe) : mapper.writeValueAsBytes(recipe);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalServerException", e.getMessage(), 500);
        }
    }

    /** A presigned URL for one of a component version's artifacts, named by its URI without the scheme. */
    public synchronized ObjectNode getComponentVersionArtifact(String componentArn, String artifactName,
                                                               String region) {
        ObjectNode component = componentVersion(componentArn, region);
        for (JsonNode manifest : component.path("_recipe").path("Manifests")) {
            for (JsonNode artifact : manifest.path("Artifacts")) {
                String uri = artifact.path("Uri").asText("");
                String name = uri.contains(":") ? uri.substring(uri.indexOf(':') + 1).replaceFirst("^//", "") : uri;
                if (!name.equals(artifactName)) {
                    continue;
                }
                if (!uri.startsWith("s3://")) {
                    throw new AwsException("ResourceNotFoundException", "Artifact " + artifactName
                            + " is not stored by floci; only S3 artifacts can be downloaded.", 404,
                            Map.of("resourceId", artifactName, "resourceType", "ARTIFACT"));
                }
                String[] location = uri.substring(5).split("/", 2);
                ObjectNode response = mapper.createObjectNode();
                response.put("preSignedUrl", presigner.get().generatePresignedUrl(config.effectiveBaseUrl(),
                        location[0], location[1], "GET", 900, region, account()));
                return response;
            }
        }
        throw new AwsException("ResourceNotFoundException", "Component version " + componentArn
                + " has no artifact " + artifactName + ".", 404, Map.of("resourceId", artifactName,
                "resourceType", "ARTIFACT"));
    }

    public synchronized ObjectNode listComponents(String scope, String maxResults, String nextToken, String region) {
        List<ObjectNode> items = new ArrayList<>();
        if ("PUBLIC".equals(scope)) {
            for (Map.Entry<String, List<String>> entry : PUBLIC_COMPONENTS.entrySet()) {
                List<String> versions = entry.getValue();
                items.add(componentSummary(publicComponent(region, entry.getKey(), versions.get(versions.size() - 1))
                        .orElseThrow()));
            }
        } else {
            Map<String, ObjectNode> latest = new LinkedHashMap<>();
            for (ObjectNode component : scan(region + "/component/")) {
                ObjectNode current = latest.get(component.path("componentName").asText());
                if (current == null || GreengrassSemver.parse(component.path("componentVersion").asText())
                        .compareTo(GreengrassSemver.parse(current.path("componentVersion").asText())) > 0) {
                    latest.put(component.path("componentName").asText(), component);
                }
            }
            latest.values().forEach(component -> items.add(componentSummary(component)));
        }
        return page(items, maxResults, nextToken, "components");
    }

    private ObjectNode componentSummary(ObjectNode component) {
        ObjectNode summary = mapper.createObjectNode();
        String versionArn = component.path("arn").asText();
        summary.put("arn", versionArn.substring(0, versionArn.lastIndexOf(":versions:")))
                .put("componentName", component.path("componentName").asText());
        ObjectNode latest = summary.putObject("latestVersion");
        latest.put("arn", versionArn).put("componentVersion", component.path("componentVersion").asText())
                .put("creationTimestamp", component.path("creationTimestamp").asDouble())
                .put("description", component.path("description").asText())
                .put("publisher", component.path("publisher").asText());
        latest.set("platforms", component.path("platforms").deepCopy());
        return summary;
    }

    public synchronized ObjectNode listComponentVersions(String componentArn, String maxResults, String nextToken,
                                                         String region) {
        String[] parts = parseComponentArn(componentArn);
        List<ObjectNode> versions = new ArrayList<>();
        if (PUBLIC_ACCOUNT.equals(parts[0])) {
            for (String version : PUBLIC_COMPONENTS.getOrDefault(parts[1], List.of())) {
                versions.add(publicComponent(region, parts[1], version).orElseThrow());
            }
        } else if (account().equals(parts[0])) {
            versions.addAll(scan(region + "/component/" + parts[1] + "/"));
        }
        if (versions.isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "Component " + componentArn + " not found.", 404,
                    Map.of("resourceId", componentArn, "resourceType", "COMPONENT"));
        }
        versions.sort((a, b) -> GreengrassSemver.parse(b.path("componentVersion").asText())
                .compareTo(GreengrassSemver.parse(a.path("componentVersion").asText())));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode version : versions) {
            items.add(mapper.createObjectNode().put("componentName", version.path("componentName").asText())
                    .put("componentVersion", version.path("componentVersion").asText())
                    .put("arn", version.path("arn").asText()));
        }
        return page(items, maxResults, nextToken, "componentVersions");
    }

    public synchronized void deleteComponent(String componentArn, String region) {
        String[] parts = parseComponentArn(componentArn);
        if (PUBLIC_ACCOUNT.equals(parts[0])) {
            throw new AwsException("AccessDeniedException", "AWS-provided components cannot be deleted.", 403);
        }
        ObjectNode component = componentVersion(componentArn, region);
        delete(componentKey(region, component.path("componentName").asText(),
                component.path("componentVersion").asText()));
    }

    // ---------------------------------------------------------------- deployments

    public synchronized ObjectNode createDeployment(JsonNode input, String region) {
        String clientToken = input.path("clientToken").asText(null);
        if (clientToken != null) {
            for (ObjectNode existing : scan(region + "/deployment/")) {
                if (clientToken.equals(existing.path("_clientToken").asText(null))) {
                    return deploymentCreated(existing);
                }
            }
        }
        String targetArn = text(input, "targetArn");
        Target target = target(targetArn, region);
        if (input.hasNonNull("parentTargetArn")) {
            Target parent = target(input.get("parentTargetArn").asText(), region);
            if (!parent.group()) {
                throw validation("parentTargetArn must be a thing group ARN.");
            }
        }
        ObjectNode components = input.path("components").isObject() ? input.get("components").deepCopy()
                : mapper.createObjectNode();
        components.fields().forEachRemaining(entry -> {
            String version = entry.getValue().path("componentVersion").asText(null);
            if (version == null || !GreengrassSemver.valid(version)) {
                throw validation("Component " + entry.getKey() + " needs a componentVersion, a semantic version.");
            }
            boolean exists = entry.getKey().startsWith("aws.")
                    || get(componentKey(region, entry.getKey(), version)).isPresent();
            if (!exists) {
                throw new AwsException("ResourceNotFoundException", "Component " + entry.getKey() + " version "
                        + version + " not found.", 404, Map.of("resourceId", entry.getKey() + ":" + version,
                        "resourceType", "COMPONENT_VERSION"));
            }
        });
        String deploymentId = UUID.randomUUID().toString();
        double created = now();
        int revision = nextRevision(region, targetArn);
        ObjectNode deployment = mapper.createObjectNode();
        deployment.put("targetArn", targetArn).put("revisionId", Integer.toString(revision))
                .put("deploymentId", deploymentId)
                .put("deploymentName", input.path("deploymentName").asText("Deployment for " + target.name()))
                .put("deploymentStatus", "ACTIVE").put("creationTimestamp", created).put("isLatestForTarget", true)
                .put("_seq", created);
        deployment.set("components", components);
        if (input.has("deploymentPolicies")) {
            deployment.set("deploymentPolicies", input.get("deploymentPolicies").deepCopy());
        } else {
            ObjectNode policies = deployment.putObject("deploymentPolicies");
            policies.put("failureHandlingPolicy", "ROLLBACK");
            policies.putObject("componentUpdatePolicy").put("timeoutInSeconds", 60)
                    .put("action", "NOTIFY_COMPONENTS");
            policies.putObject("configurationValidationPolicy").put("timeoutInSeconds", 30);
        }
        if (input.has("iotJobConfiguration")) {
            deployment.set("iotJobConfiguration", input.get("iotJobConfiguration").deepCopy());
        }
        if (input.hasNonNull("parentTargetArn")) {
            deployment.put("parentTargetArn", input.get("parentTargetArn").asText());
        }
        if (clientToken != null) {
            deployment.put("_clientToken", clientToken);
        }
        String configurationArn = arn(region, "configuration:" + (target.group() ? "thinggroup/" : "thing/")
                + target.name() + ":" + revision);
        deployment.put("_configurationArn", configurationArn);
        deployment.set("tags", tags(input.path("tags"), mapper.createObjectNode()));

        for (ObjectNode previous : scan(region + "/deployment/")) {
            if (targetArn.equals(previous.path("targetArn").asText()) && previous.path("isLatestForTarget")
                    .asBoolean()) {
                previous.put("isLatestForTarget", false);
                if ("ACTIVE".equals(previous.path("deploymentStatus").asText())) {
                    previous.put("deploymentStatus", "INACTIVE");
                    cancelJob(previous, region);
                }
                put(deploymentKey(region, previous.path("deploymentId").asText()), previous);
            }
        }
        ObjectNode fleetConfig = fleetConfiguration(deployment, configurationArn, created);
        if (target.group()) {
            JsonNode jobConfig = deployment.path("iotJobConfiguration");
            ObjectNode job = mapper.createObjectNode();
            job.putArray("targets").add(targetArn);
            job.put("document", fleetConfig.toString()).put("targetSelection", "CONTINUOUS")
                    .put("description", "Greengrass deployment " + deploymentId);
            for (String field : List.of("jobExecutionsRolloutConfig", "abortConfig", "timeoutConfig")) {
                if (jobConfig.has(field)) {
                    job.set(field, jobConfig.get(field).deepCopy());
                }
            }
            IotJob created2 = iot.get().createJob("greengrass-" + deploymentId, job, region);
            deployment.put("iotJobId", created2.getJobId()).put("iotJobArn", created2.getJobArn());
        } else {
            ObjectNode shadow = mapper.createObjectNode();
            shadow.putObject("state").putObject("desired").set("fleetConfig", fleetConfig);
            iot.get().updateThingShadow(target.name(), DEPLOYMENT_SHADOW, shadow, region);
        }
        put(deploymentKey(region, deploymentId), deployment);
        for (String thing : targetThings(target, region)) {
            get(coreDeviceKey(region, thing)).ifPresent(device -> {
                effective(device, deployment, "QUEUED", null);
                put(coreDeviceKey(region, thing), device);
            });
        }
        return deploymentCreated(deployment);
    }

    private ObjectNode deploymentCreated(ObjectNode deployment) {
        ObjectNode response = mapper.createObjectNode();
        response.put("deploymentId", deployment.path("deploymentId").asText());
        if (deployment.has("iotJobId")) {
            response.put("iotJobId", deployment.path("iotJobId").asText())
                    .put("iotJobArn", deployment.path("iotJobArn").asText());
        }
        return response;
    }

    /** The fleet configuration a nucleus applies: a deployment's components and policies. */
    private ObjectNode fleetConfiguration(ObjectNode deployment, String configurationArn, double created) {
        ObjectNode fleet = mapper.createObjectNode();
        fleet.put("configurationArn", configurationArn).put("creationTimestamp", (long) (created * 1000))
                .put("deploymentId", deployment.path("deploymentId").asText());
        ObjectNode components = fleet.putObject("components");
        deployment.path("components").fields().forEachRemaining(entry -> {
            ObjectNode component = components.putObject(entry.getKey());
            component.put("version", entry.getValue().path("componentVersion").asText());
            if (entry.getValue().has("configurationUpdate")) {
                component.set("configurationUpdate", entry.getValue().get("configurationUpdate").deepCopy());
            }
            if (entry.getValue().has("runWith")) {
                component.set("runWith", entry.getValue().get("runWith").deepCopy());
            }
        });
        JsonNode policies = deployment.path("deploymentPolicies");
        fleet.put("failureHandlingPolicy", policies.path("failureHandlingPolicy").asText("ROLLBACK"));
        ObjectNode update = fleet.putObject("componentUpdatePolicy");
        update.put("timeout", policies.path("componentUpdatePolicy").path("timeoutInSeconds").asInt(60))
                .put("action", policies.path("componentUpdatePolicy").path("action").asText("NOTIFY_COMPONENTS"));
        fleet.putObject("configurationValidationPolicy").put("timeout", policies
                .path("configurationValidationPolicy").path("timeoutInSeconds").asInt(30));
        return fleet;
    }

    private record Target(String name, boolean group) {
    }

    private Target target(String targetArn, String region) {
        if (!AwsArnUtils.isArnFor(targetArn, "iot")) {
            throw validation("targetArn must be an IoT thing or thing group ARN.");
        }
        String resource = AwsArnUtils.parse(targetArn).resource();
        try {
            if (resource.startsWith("thing/")) {
                iot.get().describeThing(resource.substring(6), region);
                return new Target(resource.substring(6), false);
            }
            if (resource.startsWith("thinggroup/")) {
                iot.get().describeThingGroup(resource.substring(11), region);
                return new Target(resource.substring(11), true);
            }
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "The target " + targetArn + " does not exist.", 404,
                    Map.of("resourceId", targetArn, "resourceType", "TARGET"));
        }
        throw validation("targetArn must be an IoT thing or thing group ARN.");
    }

    private Set<String> targetThings(Target target, String region) {
        if (!target.group()) {
            return Set.of(target.name());
        }
        try {
            return iot.get().listThingsInThingGroup(target.name(), region);
        } catch (AwsException e) {
            return Set.of();
        }
    }

    private int nextRevision(String region, String targetArn) {
        String key = region + "/revision/" + targetArn;
        ObjectNode counter = get(key).orElseGet(mapper::createObjectNode);
        int next = counter.path("revision").asInt() + 1;
        counter.put("revision", next);
        put(key, counter);
        return next;
    }

    private static String deploymentKey(String region, String deploymentId) {
        return region + "/deployment/" + deploymentId;
    }

    private ObjectNode deployment(String deploymentId, String region) {
        return get(deploymentKey(region, deploymentId)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Deployment " + deploymentId + " not found.", 404,
                Map.of("resourceId", String.valueOf(deploymentId), "resourceType", "DEPLOYMENT")));
    }

    private void cancelJob(ObjectNode deployment, String region) {
        if (!deployment.hasNonNull("iotJobId")) {
            return;
        }
        try {
            iot.get().cancelJob(deployment.get("iotJobId").asText(), null, null, false, region);
        } catch (AwsException e) {
            LOG.debugv("The IoT job of deployment {0} could not be canceled: {1}",
                    deployment.path("deploymentId").asText(), e.getMessage());
        }
    }

    public synchronized ObjectNode getDeployment(String deploymentId, String region) {
        return view(deployment(deploymentId, region));
    }

    public synchronized ObjectNode listDeployments(String targetArn, String historyFilter, String parentTargetArn,
                                                   String maxResults, String nextToken, String region) {
        String filter = historyFilter == null ? "LATEST_ONLY" : historyFilter;
        if (!Set.of("ALL", "LATEST_ONLY").contains(filter)) {
            throw validation("historyFilter must be ALL or LATEST_ONLY.");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode deployment : scan(region + "/deployment/")) {
            if (targetArn != null && !targetArn.equals(deployment.path("targetArn").asText())) {
                continue;
            }
            if (parentTargetArn != null && !parentTargetArn.equals(deployment.path("parentTargetArn").asText())) {
                continue;
            }
            if ("LATEST_ONLY".equals(filter) && !deployment.path("isLatestForTarget").asBoolean()) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            for (String field : List.of("targetArn", "revisionId", "deploymentId", "deploymentName",
                    "creationTimestamp", "deploymentStatus", "isLatestForTarget", "parentTargetArn")) {
                if (deployment.has(field)) {
                    summary.set(field, deployment.get(field));
                }
            }
            items.add(summary);
        }
        return page(items, maxResults, nextToken, "deployments");
    }

    public synchronized ObjectNode cancelDeployment(String deploymentId, String region) {
        ObjectNode deployment = deployment(deploymentId, region);
        if (!"ACTIVE".equals(deployment.path("deploymentStatus").asText())) {
            throw new AwsException("ConflictException", "Deployment " + deploymentId + " is "
                    + deployment.path("deploymentStatus").asText() + "; only an ACTIVE deployment can be canceled.",
                    409, Map.of("resourceId", deploymentId, "resourceType", "DEPLOYMENT"));
        }
        deployment.put("deploymentStatus", "CANCELED");
        cancelJob(deployment, region);
        put(deploymentKey(region, deploymentId), deployment);
        ObjectNode response = mapper.createObjectNode();
        response.put("message", "Deployment " + deploymentId + " was canceled.");
        return response;
    }

    public synchronized void deleteDeployment(String deploymentId, String region) {
        ObjectNode deployment = deployment(deploymentId, region);
        if ("ACTIVE".equals(deployment.path("deploymentStatus").asText())) {
            throw new AwsException("ConflictException", "Deployment " + deploymentId + " is ACTIVE; cancel it "
                    + "before deleting it.", 409, Map.of("resourceId", deploymentId, "resourceType", "DEPLOYMENT"));
        }
        if (deployment.hasNonNull("iotJobId")) {
            try {
                iot.get().deleteJob(deployment.get("iotJobId").asText(), true, region);
            } catch (AwsException e) {
                LOG.debugv("The IoT job of deployment {0} could not be deleted: {1}", deploymentId, e.getMessage());
            }
        }
        delete(deploymentKey(region, deploymentId));
    }

    // ---------------------------------------------------------------- core devices

    private static String coreDeviceKey(String region, String thing) {
        return region + "/coredevice/" + thing;
    }

    private ObjectNode coreDevice(String thing, String region) {
        return get(coreDeviceKey(region, thing)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Core device " + thing + " not found.", 404, Map.of("resourceId", String.valueOf(thing),
                "resourceType", "CORE_DEVICE")));
    }

    /** Reads a nucleus's Fleet Status Service report: the device, its components and its deployments' status. */
    @Override
    public void onPublish(String topic, byte[] payload, String region) {
        Matcher matcher = FLEET_STATUS.matcher(topic);
        if (!matcher.matches()) {
            return;
        }
        JsonNode report;
        try {
            report = mapper.readTree(payload);
        } catch (IOException e) {
            LOG.debugv("A Fleet Status report on {0} is not JSON: {1}", topic, e.getMessage());
            return;
        }
        if (report == null || !report.isObject()) {
            return;
        }
        synchronized (this) {
            report(matcher.group(1), report, region);
        }
    }

    private void report(String thing, JsonNode report, String region) {
        String key = coreDeviceKey(region, thing);
        ObjectNode device = get(key).orElse(null);
        double reported = report.hasNonNull("timestamp") ? report.get("timestamp").asLong() / 1000.0 : now();
        if (device == null) {
            device = mapper.createObjectNode();
            device.put("coreDeviceThingName", thing).put("_seq", reported);
            device.putObject("tags");
            device.putObject("_installed");
            device.putObject("_effective");
            device.putObject("_clients");
            for (ObjectNode deployment : scan(region + "/deployment/")) {
                if (deployment.path("isLatestForTarget").asBoolean() && "ACTIVE".equals(deployment
                        .path("deploymentStatus").asText()) && targets(deployment, thing, region)) {
                    effective(device, deployment, "QUEUED", null);
                }
            }
        }
        String status = report.path("overallDeviceStatus").asText(device.path("status").asText("HEALTHY"));
        device.put("coreVersion", report.path("ggcVersion").asText(device.path("coreVersion").asText("")))
                .put("platform", report.path("platform").asText(device.path("platform").asText("linux")))
                .put("architecture", report.path("architecture").asText(device.path("architecture").asText("")))
                .put("runtime", report.path("runtime").asText(device.path("runtime").asText("aws_nucleus_classic")))
                .put("status", "UNHEALTHY".equals(status) ? "UNHEALTHY" : "HEALTHY")
                .put("lastStatusUpdateTimestamp", reported);
        ObjectNode installed = device.withObject("/_installed");
        if ("COMPLETE".equals(report.path("messageType").asText("COMPLETE"))) {
            installed.removeAll();
        }
        List<ObjectNode> changed = new ArrayList<>();
        for (JsonNode component : report.path("components")) {
            String name = component.path("componentName").asText();
            String state = component.path("lifecycleState").asText(component.path("state").asText("RUNNING"));
            JsonNode previous = installed.get(name);
            ObjectNode entry = mapper.createObjectNode();
            entry.put("componentName", name).put("componentVersion", component.path("version").asText())
                    .put("lifecycleState", state).put("isRoot", component.path("isRoot").asBoolean(false))
                    .put("lastReportedTimestamp", reported)
                    .put("lastStatusChangeTimestamp", previous != null && state.equals(previous.path("lifecycleState")
                            .asText()) ? previous.path("lastStatusChangeTimestamp").asDouble() : reported);
            if (component.has("lastInstallationSource")) {
                entry.put("lastInstallationSource", component.get("lastInstallationSource").asText());
            }
            JsonNode details = component.path("componentStatusDetails");
            if (details.hasNonNull("statusReason")) {
                entry.put("lifecycleStateDetails", details.get("statusReason").asText());
            }
            if (details.has("statusCodes")) {
                entry.set("lifecycleStatusCodes", details.get("statusCodes").deepCopy());
            }
            installed.set(name, entry);
            if (previous == null || !state.equals(previous.path("lifecycleState").asText())) {
                changed.add(entry);
            }
        }
        JsonNode information = report.path("deploymentInformation");
        ObjectNode changedDeployment = null;
        String executionStatus = null;
        if (information.isObject()) {
            ObjectNode deployment = deploymentFor(information, region);
            if (deployment != null) {
                executionStatus = executionStatus(information.path("status").asText());
                effective(device, deployment, executionStatus, information.path("statusDetails"));
                if (!deployment.path("targetArn").asText().contains(":thinggroup/")) {
                    if ("SUCCEEDED".equals(executionStatus) && "ACTIVE".equals(deployment
                            .path("deploymentStatus").asText())) {
                        deployment.put("deploymentStatus", "COMPLETED");
                    } else if (Set.of("FAILED", "REJECTED", "TIMED_OUT").contains(executionStatus)
                            && "ACTIVE".equals(deployment.path("deploymentStatus").asText())) {
                        deployment.put("deploymentStatus", "FAILED");
                    }
                    put(deploymentKey(region, deployment.path("deploymentId").asText()), deployment);
                }
                changedDeployment = deployment;
            }
        }
        put(key, device);
        for (ObjectNode component : changed) {
            ObjectNode detail = component.deepCopy();
            detail.put("coreDeviceThingName", thing);
            publish("Greengrass V2 Installed Component Status Change", detail, region);
        }
        if (changedDeployment != null) {
            ObjectNode detail = mapper.createObjectNode();
            detail.put("coreDeviceThingName", thing)
                    .put("deploymentId", changedDeployment.path("deploymentId").asText())
                    .put("deploymentName", changedDeployment.path("deploymentName").asText())
                    .put("targetArn", changedDeployment.path("targetArn").asText())
                    .put("coreDeviceExecutionStatus", executionStatus);
            if (information.path("statusDetails").hasNonNull("failureCause")) {
                detail.put("reason", information.path("statusDetails").get("failureCause").asText());
            }
            publish("Greengrass V2 Effective Deployment Status Change", detail, region);
        }
    }

    private boolean targets(ObjectNode deployment, String thing, String region) {
        try {
            return targetThings(target(deployment.path("targetArn").asText(), region), region).contains(thing);
        } catch (AwsException e) {
            return false;
        }
    }

    private ObjectNode deploymentFor(JsonNode information, String region) {
        String configurationArn = information.path("fleetConfigurationArnForStatus").asText(null);
        String deploymentId = information.path("deploymentId").asText(null);
        for (ObjectNode deployment : scan(region + "/deployment/")) {
            if (deployment.path("deploymentId").asText().equals(deploymentId)
                    || deployment.path("_configurationArn").asText().equals(configurationArn)) {
                return deployment;
            }
        }
        return null;
    }

    /** A deployment status a nucleus reports, as the execution status Greengrass shows for it. */
    private static String executionStatus(String reported) {
        return switch (reported) {
            case "SUCCEEDED", "SUCCESSFUL" -> "SUCCEEDED";
            case "IN_PROGRESS" -> "IN_PROGRESS";
            case "QUEUED" -> "QUEUED";
            case "REJECTED" -> "REJECTED";
            case "CANCELED" -> "CANCELED";
            case "TIMED_OUT" -> "TIMED_OUT";
            case "COMPLETED" -> "COMPLETED";
            default -> reported.startsWith("FAILED") ? "FAILED" : "IN_PROGRESS";
        };
    }

    private void effective(ObjectNode device, ObjectNode deployment, String status, JsonNode details) {
        ObjectNode entry = device.withObject("/_effective").putObject(deployment.path("deploymentId").asText());
        double now = now();
        entry.put("deploymentId", deployment.path("deploymentId").asText())
                .put("deploymentName", deployment.path("deploymentName").asText())
                .put("targetArn", deployment.path("targetArn").asText()).put("coreDeviceExecutionStatus", status)
                .put("creationTimestamp", deployment.path("creationTimestamp").asDouble()).put("modifiedTimestamp", now);
        if (deployment.hasNonNull("iotJobId")) {
            entry.put("iotJobId", deployment.get("iotJobId").asText())
                    .put("iotJobArn", deployment.path("iotJobArn").asText());
        }
        if (details != null && details.isObject()) {
            if (details.hasNonNull("failureCause")) {
                entry.put("reason", details.get("failureCause").asText());
            }
            ObjectNode statusDetails = entry.putObject("statusDetails");
            statusDetails.set("errorStack", details.path("errorStack").isArray() ? details.get("errorStack")
                    .deepCopy() : mapper.createArrayNode());
            statusDetails.set("errorTypes", details.path("errorTypes").isArray() ? details.get("errorTypes")
                    .deepCopy() : mapper.createArrayNode());
        }
    }

    private void publish(String detailType, ObjectNode detail, String region) {
        try {
            events.get().publishServiceEvent("aws.greengrass", detailType, detail.toString(),
                    arn(region, "coreDevices:" + detail.path("coreDeviceThingName").asText()), region);
        } catch (RuntimeException e) {
            LOG.debugv("Could not publish {0}: {1}", detailType, e.getMessage());
        }
    }

    public synchronized ObjectNode getCoreDevice(String thing, String region) {
        ObjectNode device = coreDevice(thing, region);
        ObjectNode response = mapper.createObjectNode();
        for (String field : List.of("coreDeviceThingName", "coreVersion", "platform", "architecture", "runtime",
                "status", "lastStatusUpdateTimestamp", "tags")) {
            if (device.has(field)) {
                response.set(field, device.get(field).deepCopy());
            }
        }
        return response;
    }

    public synchronized ObjectNode listCoreDevices(String thingGroupArn, String status, String runtime,
                                                   String maxResults, String nextToken, String region) {
        Set<String> members = null;
        if (thingGroupArn != null) {
            Target group = target(thingGroupArn, region);
            members = targetThings(group, region);
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode device : scan(region + "/coredevice/")) {
            if ((members != null && !members.contains(device.path("coreDeviceThingName").asText()))
                    || (status != null && !status.equals(device.path("status").asText()))
                    || (runtime != null && !runtime.equals(device.path("runtime").asText()))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            for (String field : List.of("coreDeviceThingName", "status", "lastStatusUpdateTimestamp", "platform",
                    "architecture", "runtime")) {
                summary.set(field, device.path(field).deepCopy());
            }
            items.add(summary);
        }
        return page(items, maxResults, nextToken, "coreDevices");
    }

    public synchronized void deleteCoreDevice(String thing, String region) {
        coreDevice(thing, region);
        delete(coreDeviceKey(region, thing));
    }

    public synchronized ObjectNode listEffectiveDeployments(String thing, String maxResults, String nextToken,
                                                            String region) {
        ObjectNode device = coreDevice(thing, region);
        List<ObjectNode> items = new ArrayList<>();
        device.path("_effective").forEach(entry -> items.add(((ObjectNode) entry).deepCopy()));
        return page(items, maxResults, nextToken, "effectiveDeployments");
    }

    public synchronized ObjectNode listInstalledComponents(String thing, String topologyFilter, String maxResults,
                                                           String nextToken, String region) {
        ObjectNode device = coreDevice(thing, region);
        String filter = topologyFilter == null ? "ROOT" : topologyFilter;
        if (!Set.of("ALL", "ROOT").contains(filter)) {
            throw validation("topologyFilter must be ALL or ROOT.");
        }
        List<ObjectNode> items = new ArrayList<>();
        device.path("_installed").forEach(entry -> {
            if ("ALL".equals(filter) || entry.path("isRoot").asBoolean()) {
                items.add(((ObjectNode) entry).deepCopy());
            }
        });
        return page(items, maxResults, nextToken, "installedComponents");
    }

    // ---------------------------------------------------------------- client devices

    public synchronized ObjectNode associateClientDevices(String thing, JsonNode input, String region) {
        ObjectNode device = coreDevice(thing, region);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("errorEntries");
        JsonNode entries = input.path("entries");
        if (entries.size() > 100) {
            throw validation("entries holds at most 100 client devices.");
        }
        ObjectNode clients = device.withObject("/_clients");
        for (JsonNode entry : entries) {
            String client = entry.path("thingName").asText();
            try {
                iot.get().describeThing(client, region);
            } catch (AwsException e) {
                errors.addObject().put("thingName", client).put("code", "ResourceNotFoundException")
                        .put("message", "Thing " + client + " does not exist.");
                continue;
            }
            if (!clients.has(client)) {
                clients.putObject(client).put("thingName", client).put("associationTimestamp", now());
            }
        }
        put(coreDeviceKey(region, thing), device);
        return response;
    }

    public synchronized ObjectNode disassociateClientDevices(String thing, JsonNode input, String region) {
        ObjectNode device = coreDevice(thing, region);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("errorEntries");
        ObjectNode clients = device.withObject("/_clients");
        for (JsonNode entry : input.path("entries")) {
            String client = entry.path("thingName").asText();
            if (clients.remove(client) == null) {
                errors.addObject().put("thingName", client).put("code", "ResourceNotFoundException")
                        .put("message", "Client device " + client + " is not associated with " + thing + ".");
            }
        }
        put(coreDeviceKey(region, thing), device);
        return response;
    }

    public synchronized ObjectNode listClientDevices(String thing, String maxResults, String nextToken,
                                                     String region) {
        ObjectNode device = coreDevice(thing, region);
        List<ObjectNode> items = new ArrayList<>();
        device.path("_clients").forEach(entry -> items.add(((ObjectNode) entry).deepCopy()));
        return page(items, maxResults, nextToken, "associatedClientDevices");
    }

    // ---------------------------------------------------------------- resolution (nucleus API)

    /**
     * Picks, for each candidate, the newest DEPLOYABLE version that satisfies every requirement on it and runs on the
     * device's platform. A nucleus calls this with its certificate; a call signed with IAM credentials is refused.
     */
    public synchronized ObjectNode resolveComponentCandidates(JsonNode input, boolean iamSigned, String region) {
        if (iamSigned) {
            throw new AwsException("AccessDeniedException", "ResolveComponentCandidates is called by a Greengrass "
                    + "core device with its AWS IoT certificate, not with IAM credentials.", 403);
        }
        JsonNode platform = input.path("platform");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode resolved = response.putArray("resolvedComponentVersions");
        for (JsonNode candidate : input.path("componentCandidates")) {
            String name = candidate.path("componentName").asText();
            List<String> requirements = new ArrayList<>();
            candidate.path("versionRequirements").fields().forEachRemaining(entry -> requirements.add(entry.getValue()
                    .asText()));
            List<ObjectNode> versions = new ArrayList<>();
            if (PUBLIC_COMPONENTS.containsKey(name)) {
                for (String version : PUBLIC_COMPONENTS.get(name)) {
                    versions.add(publicComponent(region, name, version).orElseThrow());
                }
            } else {
                versions.addAll(scan(region + "/component/" + name + "/"));
            }
            ObjectNode best = null;
            for (ObjectNode version : versions) {
                String number = version.path("componentVersion").asText();
                if (!"DEPLOYABLE".equals(visibleStatus(version).path("componentState").asText())
                        || !requirements.stream().allMatch(requirement -> GreengrassSemver.satisfies(number,
                        requirement)) || !runsOn(version, platform)) {
                    continue;
                }
                if (best == null || GreengrassSemver.parse(number).compareTo(GreengrassSemver.parse(best
                        .path("componentVersion").asText())) > 0) {
                    best = version;
                }
            }
            if (best == null) {
                throw new AwsException("ResourceNotFoundException", "No version of component " + name
                        + " satisfies " + String.join(" and ", requirements) + " on the device's platform.", 404,
                        Map.of("resourceId", name, "resourceType", "COMPONENT"));
            }
            resolved.addObject().put("arn", best.path("arn").asText()).put("componentName", name)
                    .put("componentVersion", best.path("componentVersion").asText())
                    .put("recipe", Base64.getEncoder().encodeToString(render(best.path("_recipe"), "JSON")))
                    .put("vendorGuidance", best.path("status").path("vendorGuidance").asText("ACTIVE"));
        }
        return response;
    }

    /** Whether one of a component's manifests runs on a platform: each attribute it names matches, or is '*'. */
    private static boolean runsOn(ObjectNode component, JsonNode platform) {
        JsonNode manifests = component.path("_recipe").path("Manifests");
        if (!manifests.isArray() || manifests.isEmpty()) {
            return true;
        }
        Map<String, String> device = new LinkedHashMap<>();
        platform.path("attributes").fields().forEachRemaining(entry -> device.put(entry.getKey(),
                entry.getValue().asText()));
        for (JsonNode manifest : manifests) {
            boolean matches = true;
            Iterator<Map.Entry<String, JsonNode>> fields = manifest.path("Platform").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> required = fields.next();
                String value = required.getValue().asText();
                String actual = device.get(required.getKey());
                if (!"*".equals(value) && !("os".equals(required.getKey()) && "all".equals(value))
                        && (actual == null || !actual.equalsIgnoreCase(value))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- connectivity and the service role
    // These operations come from Greengrass V1, and their members are capitalized on the wire.

    public synchronized ObjectNode getConnectivityInfo(String thing, String region) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode stored = get(region + "/connectivity/" + thing).orElse(null);
        response.set("ConnectivityInfo", stored == null ? mapper.createArrayNode()
                : stored.path("ConnectivityInfo").deepCopy());
        return response;
    }

    public synchronized ObjectNode updateConnectivityInfo(String thing, JsonNode input, String region) {
        try {
            iot.get().describeThing(thing, region);
        } catch (AwsException e) {
            throw validation("Thing " + thing + " does not exist.");
        }
        if (!input.path("ConnectivityInfo").isArray()) {
            throw validation("ConnectivityInfo is required.");
        }
        ArrayNode entries = mapper.createArrayNode();
        for (JsonNode entry : input.path("ConnectivityInfo")) {
            String host = entry.path("HostAddress").asText(null);
            int port = entry.path("PortNumber").asInt(8883);
            if (host == null || host.isBlank()) {
                throw validation("Each connectivity entry needs a HostAddress.");
            }
            if (port < 1 || port > 65535) {
                throw validation("PortNumber must be 1 to 65535.");
            }
            ObjectNode stored = entries.addObject();
            stored.put("Id", entry.path("Id").asText(host)).put("HostAddress", host).put("PortNumber", port)
                    .put("Metadata", entry.path("Metadata").asText(""));
        }
        ObjectNode record = mapper.createObjectNode();
        String version = UUID.randomUUID().toString();
        record.put("Version", version);
        record.set("ConnectivityInfo", entries);
        put(region + "/connectivity/" + thing, record);
        ObjectNode response = mapper.createObjectNode();
        response.put("Version", version).put("Message", "Connectivity information was updated.");
        return response;
    }

    public synchronized ObjectNode associateServiceRole(JsonNode input, String region) {
        String roleArn = text(input, "RoleArn");
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.parse(roleArn).resource().startsWith("role/")) {
            throw validation("RoleArn must be an IAM role ARN.");
        }
        ObjectNode record = mapper.createObjectNode();
        String at = Instant.now().toString();
        record.put("RoleArn", roleArn).put("AssociatedAt", at);
        put(region + "/servicerole", record);
        ObjectNode response = mapper.createObjectNode();
        response.put("AssociatedAt", at);
        return response;
    }

    public synchronized ObjectNode getServiceRole(String region) {
        return get(region + "/servicerole").map(ObjectNode::deepCopy).orElseGet(mapper::createObjectNode);
    }

    public synchronized ObjectNode disassociateServiceRole(String region) {
        delete(region + "/servicerole");
        ObjectNode response = mapper.createObjectNode();
        response.put("DisassociatedAt", Instant.now().toString());
        return response;
    }

    // ---------------------------------------------------------------- tags

    private String taggedKey(String resourceArn, String region) {
        if (!AwsArnUtils.isArnFor(resourceArn, "greengrass")) {
            throw validation("resourceArn must be a Greengrass ARN.");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String[] segments = arn.resource().split(":");
        String key = null;
        if (segments.length == 4 && "components".equals(segments[0]) && "versions".equals(segments[2])) {
            key = componentKey(arn.region(), segments[1], segments[3]);
        } else if (segments.length == 2 && "deployments".equals(segments[0])) {
            key = deploymentKey(arn.region(), segments[1]);
        } else if (segments.length == 2 && "coreDevices".equals(segments[0])) {
            key = coreDeviceKey(arn.region(), segments[1]);
        }
        if (key == null || !account().equals(arn.accountId()) || get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "Resource " + resourceArn + " not found.", 404,
                    Map.of("resourceId", resourceArn, "resourceType", "RESOURCE"));
        }
        return key;
    }

    public synchronized ObjectNode listTags(String resourceArn, String region) {
        ObjectNode record = get(taggedKey(resourceArn, region)).orElseThrow();
        ObjectNode response = mapper.createObjectNode();
        response.set("tags", record.path("tags").deepCopy());
        return response;
    }

    public synchronized void tagResource(String resourceArn, JsonNode input, String region) {
        String key = taggedKey(resourceArn, region);
        ObjectNode record = get(key).orElseThrow();
        record.set("tags", tags(input.path("tags"), (ObjectNode) record.path("tags")));
        put(key, record);
    }

    public synchronized void untagResource(String resourceArn, List<String> keys, String region) {
        String key = taggedKey(resourceArn, region);
        ObjectNode record = get(key).orElseThrow();
        ObjectNode tags = (ObjectNode) record.path("tags");
        keys.forEach(tags::remove);
        put(key, record);
    }

    private ObjectNode tags(JsonNode given, ObjectNode existing) {
        ObjectNode tags = existing.deepCopy();
        given.fields().forEachRemaining(entry -> {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 128 || entry.getKey().startsWith("aws:")) {
                throw validation("Tag keys are 1 to 128 characters and do not begin with aws:.");
            }
            if (entry.getValue().asText().length() > 256) {
                throw validation("Tag values are at most 256 characters.");
            }
            tags.put(entry.getKey(), entry.getValue().asText());
        });
        if (tags.size() > 50) {
            throw validation("A resource has at most 50 tags.");
        }
        return tags;
    }

    // ---------------------------------------------------------------- shared

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        List<String> hidden = new ArrayList<>();
        view.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("_")) {
                hidden.add(field);
            }
        });
        view.remove(hidden);
        return view;
    }

    private ObjectNode page(List<ObjectNode> items, String maxResults, String nextToken, String field) {
        int limit = 100;
        if (maxResults != null && !maxResults.isEmpty()) {
            try {
                limit = Integer.parseInt(maxResults);
            } catch (NumberFormatException e) {
                limit = 0;
            }
            if (limit < 1 || limit > 100) {
                throw validation("maxResults is 1 to 100.");
            }
        }
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("nextToken is not valid.");
            }
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray(field);
        int end = Math.min(items.size(), start + limit);
        for (int i = start; i < end; i++) {
            list.add(items.get(i));
        }
        if (end < items.size()) {
            response.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw validation(field + " is required.");
        }
        return value.asText();
    }

    static AwsException validation(String message) {
        Map<String, Object> extended = new LinkedHashMap<>();
        extended.put("reason", "FIELD_VALIDATION_FAILED");
        return new AwsException("ValidationException", message, 400, extended);
    }
}

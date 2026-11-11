package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Image;
import io.floci.aws.services.ec2.model.LaunchTemplate;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.ssm.SsmService;
import io.floci.aws.services.stepfunctions.StepFunctionsService;
import io.floci.aws.services.stepfunctions.model.Execution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.imagebuilder.ImageBuilderStore.key;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.meta;

/**
 * The workflow step actions, on Floci's own services: {@code LaunchInstance} runs an EC2 instance from the recipe's
 * parent AMI with the infrastructure configuration's settings, {@code ExecuteComponents} runs the recipe's components
 * with AWSTOE over SSM, {@code CreateImage} makes the AMI from the instance, {@code DistributeImage} copies it to each
 * distribution's Region and accounts, and so on through {@code RunCommand}, {@code ExecuteStateMachine} and
 * {@code TerminateInstance}. Without a distribution workflow, the distribution configuration is applied as the
 * default distribution does: AMIs copied, named and tagged, or container images pushed to ECR.
 */
@ApplicationScoped
public class ImageBuilderActions {

    private static final Logger LOG = Logger.getLogger(ImageBuilderActions.class);
    private static final Pattern BUILD_DATE = Pattern.compile("\\{\\{\\s*imagebuilder:buildDate(?::([^}\\s]+))?\\s*}}");
    private static final Pattern BUILD_VERSION = Pattern.compile("\\{\\{\\s*imagebuilder:buildVersion\\s*}}");
    private static final String SANITIZE = String.join("; ", List.of(
            "rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub",
            "rm -rf /var/lib/cloud/instances/* /var/lib/cloud/instance",
            "rm -f /root/.bash_history /home/*/.bash_history",
            "find /var/log -type f -exec truncate -s 0 {} + 2>/dev/null",
            "rm -rf /tmp/imagebuilder /var/lib/amazon/toe/TOE_*",
            "true"));

    private final ImageBuilderStore store;
    private final ImageBuilderToe toe;
    private final Instance<ImageBuilderService> service;
    private final Instance<ImageBuilderImages> images;
    private final Instance<ImageBuilderEngine> engine;
    private final Instance<Ec2Service> ec2;
    private final Instance<EcrService> ecr;
    private final Instance<SsmService> ssm;
    private final Instance<SnsService> sns;
    private final Instance<LambdaService> lambda;
    private final Instance<StepFunctionsService> states;

    @Inject
    public ImageBuilderActions(ImageBuilderStore store, ImageBuilderToe toe, Instance<ImageBuilderService> service,
                               Instance<ImageBuilderImages> images, Instance<ImageBuilderEngine> engine,
                               Instance<Ec2Service> ec2, Instance<EcrService> ecr, Instance<SsmService> ssm,
                               Instance<SnsService> sns, Instance<LambdaService> lambda,
                               Instance<StepFunctionsService> states) {
        this.store = store;
        this.toe = toe;
        this.service = service;
        this.images = images;
        this.engine = engine;
        this.ec2 = ec2;
        this.ecr = ecr;
        this.ssm = ssm;
        this.sns = sns;
        this.lambda = lambda;
        this.states = states;
    }

    ImageBuilderImages images() {
        return images.get();
    }

    ObjectNode workflow(String arn, String region) {
        return service.get().workflow(arn, region);
    }

    /** The default timeout of a step action, in seconds. */
    static int defaultTimeout(String action) {
        return switch (action) {
            case "LaunchInstance" -> 75 * 60;
            case "BootstrapInstanceForContainer", "RunSysPrep", "SanitizeInstance", "WaitForSSMAgent" -> 60 * 60;
            case "CollectImageMetadata", "TerminateInstance" -> 30 * 60;
            case "CollectImageScanFindings" -> 15 * 60;
            case "DistributeImage", "ApplyImageConfigurations" -> 360 * 60;
            case "DistributeContainerImage", "ModifyImageAttributes" -> 120 * 60;
            case "RegisterImage" -> 540 * 60;
            case "ExecuteStateMachine" -> 6 * 3600;
            case "WaitForAction" -> 3 * 86400;
            default -> 720 * 60;
        };
    }

    private ObjectNode image(ImageBuilderEngine.Build build) {
        return store.get(build.account, key(build.region, build.imageKey)).orElseThrow(() ->
                new IllegalStateException("The image was deleted."));
    }

    private void saveImage(ImageBuilderEngine.Build build, ObjectNode image) {
        store.put(build.account, key(build.region, build.imageKey), image);
    }

    private ObjectNode object() {
        return store.mapper().createObjectNode();
    }

    /** Runs a step action with its resolved inputs: its outputs. */
    ObjectNode run(ImageBuilderEngine.Build build, String action, ObjectNode inputs, ObjectNode context) {
        return switch (action) {
            case "LaunchInstance" -> launch(build, inputs, context);
            case "BootstrapInstanceForContainer" -> object().put("runCommandId", UUID.randomUUID().toString())
                    .put("status", "Success").put("output", "Docker and the AWS CLI are available to the build.");
            case "ExecuteComponents" -> executeComponents(build, inputs, context);
            case "CollectImageMetadata" -> collectMetadata(build, inputs);
            case "SanitizeInstance" -> command(build, inputs, List.of(SANITIZE), "SanitizeInstance");
            case "RunSysPrep" -> throw new IllegalStateException("Floci runs Linux instances only; Sysprep needs a"
                    + " Windows instance.");
            case "CreateImage" -> createImage(build, inputs);
            case "RegisterImage" -> registerImage(build, inputs);
            case "CreateImageFromIso" -> throw new IllegalStateException("Floci cannot convert ISO disk images.");
            case "RunCommand" -> runCommand(build, inputs);
            case "WaitForSSMAgent" -> waitForAgent(inputs);
            case "ExecuteStateMachine" -> executeStateMachine(build, inputs);
            case "TerminateInstance" -> {
                terminate(build, inputs.path("instanceId").asText());
                yield object();
            }
            case "CollectImageScanFindings" -> {
                images.get().collectFindings(build, inputs.path("instanceId").asText());
                yield object();
            }
            case "DistributeImage" -> distributeImage(build, inputs.path("distributions"));
            case "ModifyImageAttributes" -> modifyAttributes(build, inputs.path("distributedImages"),
                    inputs.path("attributes"));
            case "ApplyImageConfigurations" -> applyConfigurations(build, inputs.path("distributedImages"),
                    inputs.path("configurations"));
            case "DistributeContainerImage" -> distributeContainer(build, null);
            default -> throw new IllegalStateException("The step action " + action + " is not supported.");
        };
    }

    // ---------------------------------------------------------------- instances

    private ObjectNode launch(ImageBuilderEngine.Build build, ObjectNode inputs, ObjectNode context) {
        ObjectNode image = image(build);
        JsonNode infrastructure = image.path("infrastructureConfiguration");
        if (inputs.hasNonNull("parentInstanceId") && !inputs.get("parentInstanceId").asText().isEmpty()) {
            String instanceId = inputs.get("parentInstanceId").asText();
            ec2.get().createTags(build.region, List.of(instanceId), List.of(new Tag("CreatedBy",
                    "EC2 Image Builder")));
            return object().put("instanceId", instanceId);
        }
        boolean docker = "DOCKER".equals(image.path("type").asText());
        String phase = context.path("imagebuilder").path("workflowPhase").asText("Build");
        String ami = inputs.path("imageIdOverride").asText("");
        if (ami.isEmpty()) {
            if (docker) {
                ami = "ami-0abcdef1234567891";
            } else if ("Test".equals(phase)) {
                ami = meta(image).path("buildAmi").asText("");
                if (ami.isEmpty()) {
                    JsonNode output = context.path("workflowOutputs").path("ImageId");
                    ami = output.asText("");
                }
            } else {
                ami = engine.get().parentAmi(image.path("imageRecipe").path("parentImage").asText(), build.region);
            }
        }
        List<String> types = new ArrayList<>();
        (inputs.has("instanceTypesOverride") ? inputs.get("instanceTypesOverride")
                : infrastructure.path("instanceTypes")).forEach(type -> types.add(type.asText()));
        if (types.isEmpty()) {
            types.add("m5.large");
        }
        List<String> groups = new ArrayList<>();
        infrastructure.path("securityGroupIds").forEach(group -> groups.add(group.asText()));
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag("Name", ("Test".equals(phase) ? "Test" : "Build") + " instance for "
                + image.path("name").asText() + " " + image.path("version").asText()));
        tags.add(new Tag("CreatedBy", "EC2 Image Builder"));
        tags.add(new Tag("Ec2ImageBuilderArn", image.path("arn").asText()));
        infrastructure.path("resourceTags").fields().forEachRemaining(entry -> tags.add(new Tag(entry.getKey(),
                entry.getValue().asText())));
        String profile = infrastructure.path("instanceProfileName").asText("");
        String profileArn = profile.isEmpty() ? null : AwsArnUtils.Arn.global(AwsRegions.partitionFor(build.region),
                "iam", build.account, "instance-profile/" + profile).toString();
        String userData = image.path("imageRecipe").path("additionalInstanceConfiguration")
                .path("userDataOverride").asText(null);
        RuntimeException failure = null;
        for (String type : types) {
            try {
                Reservation reservation = ec2.get().runInstances(build.region, ami, type, 1, 1,
                        infrastructure.path("keyPair").asText(null), groups.isEmpty() ? null : groups,
                        infrastructure.path("subnetId").asText(null), null, tags, null, profileArn, null, null, 0,
                        infrastructure.path("placement").path("availabilityZone").asText(null), null, null, userData,
                        false, null, null, null, false, null);
                String instanceId = reservation.getInstances().get(0).getInstanceId();
                build.instances.add(instanceId);
                awaitReady(instanceId, TimeUnit.MINUTES.toMillis(10));
                return object().put("instanceId", instanceId);
            } catch (AwsException e) {
                failure = e;
            }
        }
        throw failure == null ? new IllegalStateException("No instance type could be launched.") : failure;
    }

    private void awaitReady(String instanceId, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (toe.ready(instanceId)) {
                return;
            }
            if (toe.mockInstance(instanceId)) {
                throw new IllegalStateException("The instance " + instanceId + " runs without a container "
                        + "(floci.services.ec2.mock); builds need instances that run.");
            }
            sleep(1000);
        }
        throw new IllegalStateException("The instance " + instanceId + " did not become reachable through SSM.");
    }

    private ObjectNode waitForAgent(ObjectNode inputs) {
        awaitReady(inputs.path("instanceId").asText(), TimeUnit.MINUTES.toMillis(30));
        return object().put("status", "Online");
    }

    void terminate(ImageBuilderEngine.Build build, String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }
        try {
            ec2.get().terminateInstances(build.region, List.of(instanceId));
        } catch (RuntimeException e) {
            LOG.debugv("Could not terminate {0}: {1}", instanceId, e.getMessage());
        }
        build.instances.remove(instanceId);
    }

    /** Terminates every instance a failed or cancelled build launched. */
    void terminateAll(ImageBuilderEngine.Build build) {
        for (String instanceId : new ArrayList<>(build.instances)) {
            RequestScopes.runAs(build.account, build.region, () -> terminate(build, instanceId));
        }
    }

    // ---------------------------------------------------------------- components

    /** The components of the image's recipe that a stage runs, with their documents and parameters. */
    private List<ObjectNode> components(ImageBuilderEngine.Build build, ObjectNode image, List<String> parameters) {
        JsonNode recipe = "DOCKER".equals(image.path("type").asText()) ? image.path("containerRecipe")
                : image.path("imageRecipe");
        List<ObjectNode> components = new ArrayList<>();
        for (JsonNode reference : recipe.path("components")) {
            ObjectNode component = service.get().component(reference.path("componentArn").asText(), build.region);
            components.add(component);
            for (JsonNode parameter : reference.path("parameters")) {
                List<String> values = new ArrayList<>();
                parameter.path("value").forEach(value -> values.add(value.asText()));
                parameters.add(parameter.path("name").asText() + "=" + String.join(",", values));
            }
        }
        return components;
    }

    private ObjectNode executeComponents(ImageBuilderEngine.Build build, ObjectNode inputs, ObjectNode context) {
        ObjectNode image = image(build);
        String phase = context.path("imagebuilder").path("workflowPhase").asText("Build");
        boolean test = "Test".equals(phase);
        List<String> parameters = new ArrayList<>();
        List<ObjectNode> components = components(build, image, parameters);
        List<byte[]> documents = new ArrayList<>();
        for (ObjectNode component : components) {
            boolean testComponent = "TEST".equals(component.path("type").asText());
            if (test || !testComponent) {
                documents.add(component.path("data").asText().getBytes(StandardCharsets.UTF_8));
            }
        }
        if (documents.isEmpty()) {
            return object().put("runCommandId", "").put("status", "Success").put("output", "No components to run.");
        }
        int timeout = defaultTimeout("ExecuteComponents");
        boolean docker = "DOCKER".equals(image.path("type").asText());
        ImageBuilderToe.Result result;
        if (docker && !test) {
            ObjectNode recipe = (ObjectNode) image.path("containerRecipe");
            // Docker repository names are lowercase; it refuses any other before it reads the build context.
            String tag = "floci-imagebuilder/" + image.path("name").asText().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9._-]", "-") + ":" + image.path("version").asText().replace('/', '-');
            String imageId = toe.buildContainer(recipe.path("dockerfileTemplateData").asText(),
                    containerParent(recipe.path("parentImage").asText()), documents, parameters, tag, build.region);
            image = image(build);
            meta(image).put("containerImage", tag).put("containerImageId", imageId);
            saveImage(build, image);
            result = new ImageBuilderToe.Result("Success", 0, "Built " + tag);
        } else if (docker) {
            result = toe.testContainer(meta(image).path("containerImage").asText(), documents, parameters, timeout,
                    build.region);
        } else {
            result = toe.runComponents(inputs.path("instanceId").asText(), documents, parameters,
                    test ? "test" : "build,validate", timeout, build.region);
        }
        engine.get().log(build, "Components (" + (test ? "test" : "build") + "): " + result.output());
        uploadLogs(build, image, result.output());
        if (!result.success()) {
            throw new IllegalStateException("The components failed (exit code " + result.exitCode() + "): "
                    + lastLines(result.output()));
        }
        return object().put("runCommandId", UUID.randomUUID().toString()).put("status", result.status())
                .put("output", result.output());
    }

    private static final String AMAZON_LINUX_IMAGE =
            "public.ecr.aws/amazonlinux/amazonlinux:latest"; // partition-literal: the public ECR Gallery is global
    private static final String UBUNTU_IMAGE =
            "public.ecr.aws/docker/library/ubuntu:latest"; // partition-literal: the public ECR Gallery is global

    /** A container recipe's parent image as Docker pulls it; Amazon's managed container images are the public ECR Gallery's. */
    private static String containerParent(String parent) {
        if (AwsArnUtils.isArn(parent)) {
            String name = parent.substring(parent.indexOf(":image/") + ":image/".length()).split("/")[0];
            return name.startsWith("ubuntu") ? UBUNTU_IMAGE : AMAZON_LINUX_IMAGE;
        }
        return parent;
    }

    private void uploadLogs(ImageBuilderEngine.Build build, ObjectNode image, String output) {
        JsonNode s3Logs = image.path("infrastructureConfiguration").path("logging").path("s3Logs");
        String bucket = s3Logs.path("s3BucketName").asText("");
        if (bucket.isEmpty()) {
            return;
        }
        String prefix = s3Logs.path("s3KeyPrefix").asText("");
        String logKey = (prefix.isEmpty() ? "" : prefix.replaceAll("/+$", "") + "/") + image.path("name").asText()
                + "/" + image.path("version").asText() + "/" + "TOE_" + Instant.now().toString().replace(':', '-')
                + "/console.log";
        try {
            images.get().s3().putObject(bucket, logKey, output.getBytes(StandardCharsets.UTF_8), "text/plain",
                    Map.of());
        } catch (RuntimeException e) {
            LOG.debugv("Could not upload Image Builder logs to {0}: {1}", bucket, e.getMessage());
        }
    }

    private static String lastLines(String output) {
        String[] lines = output.split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 15); i < lines.length; i++) {
            tail.append(lines[i]).append('\n');
        }
        return tail.toString().trim();
    }

    private ObjectNode command(ImageBuilderEngine.Build build, ObjectNode inputs, List<String> commands,
                               String what) {
        ImageBuilderToe.Result result = toe.runShell(inputs.path("instanceId").asText(), commands,
                defaultTimeout(what), build.region);
        if (!result.success()) {
            throw new IllegalStateException(what + " failed: " + lastLines(result.output()));
        }
        return object().put("runCommandId", UUID.randomUUID().toString()).put("status", result.status())
                .put("output", result.output());
    }

    /** Collects the instance's OS version and packages: the image's {@code osVersion} and its package list. */
    private ObjectNode collectMetadata(ImageBuilderEngine.Build build, ObjectNode inputs) {
        String instanceId = inputs.path("instanceId").asText();
        ImageBuilderToe.Result os = toe.runShell(instanceId, List.of(". /etc/os-release && echo \"$NAME $VERSION_ID\""),
                600, build.region);
        ImageBuilderToe.Result packages = toe.runShell(instanceId, List.of("if command -v rpm >/dev/null; then "
                + "rpm -qa --qf '%{NAME}\\t%{VERSION}-%{RELEASE}\\n'; elif command -v dpkg-query >/dev/null; then "
                + "dpkg-query -W -f='${Package}\\t${Version}\\n'; fi"), 600, build.region);
        String osVersion = os.output().trim();
        ObjectNode image = image(build);
        if (!osVersion.isEmpty()) {
            image.put("osVersion", osVersion);
        }
        ArrayNode list = meta(image).putArray("packages");
        for (String line : packages.output().split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length == 2 && !parts[0].isBlank()) {
                list.addObject().put("packageName", parts[0].trim()).put("packageVersion", parts[1].trim());
            }
        }
        saveImage(build, image);
        return object().put("osVersion", osVersion).put("associationId", UUID.randomUUID().toString());
    }

    // ---------------------------------------------------------------- AMIs

    private ObjectNode createImage(ImageBuilderEngine.Build build, ObjectNode inputs) {
        ObjectNode image = image(build);
        String name = image.path("name").asText() + " " + image.path("version").asText().replace('/', '-') + " "
                + Instant.now().toEpochMilli();
        Image ami = ec2.get().createImage(build.region, inputs.path("instanceId").asText(), name,
                "Created by EC2 Image Builder for " + image.path("arn").asText(), true);
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag("CreatedBy", "EC2 Image Builder"));
        tags.add(new Tag("Ec2ImageBuilderArn", image.path("arn").asText()));
        image.path("imageRecipe").path("amiTags").fields().forEachRemaining(entry ->
                tags.add(new Tag(entry.getKey(), entry.getValue().asText())));
        ec2.get().createTags(build.region, List.of(ami.getImageId()), tags);
        image = image(build);
        meta(image).put("buildAmi", ami.getImageId()).put("buildAmiName", name);
        saveImage(build, image);
        return object().put("imageId", ami.getImageId());
    }

    private ObjectNode registerImage(ImageBuilderEngine.Build build, ObjectNode inputs) {
        ObjectNode image = image(build);
        Image ami = ec2.get().registerImage(build.region, image.path("name").asText() + " "
                        + Instant.now().toEpochMilli(), inputs.path("description").asText(null),
                inputs.path("architecture").asText("x86_64"), inputs.path("rootDeviceName").asText("/dev/xvda"),
                List.of());
        image = image(build);
        meta(image).put("buildAmi", ami.getImageId());
        saveImage(build, image);
        return object().put("imageId", ami.getImageId());
    }

    /** Renders an AMI name: {@code {{ imagebuilder:buildDate[:format] }}} and {@code {{ imagebuilder:buildVersion }}}. */
    static String render(String template, ObjectNode image, Instant at) {
        Matcher date = BUILD_DATE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (date.find()) {
            String pattern = date.group(1) == null ? "yyyy-MM-dd'T'HH-mm-ss.SSS'Z'" : date.group(1);
            String value;
            try {
                value = DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC).format(at);
            } catch (IllegalArgumentException e) {
                value = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'").withZone(ZoneOffset.UTC)
                        .format(at);
            }
            date.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        date.appendTail(out);
        String[] version = image.path("version").asText().split("/");
        return BUILD_VERSION.matcher(out.toString()).replaceAll(version.length > 1 ? version[1] : "1");
    }

    /** The default distribution: the distribution configuration's AMIs or container images. */
    void distributeDefault(ImageBuilderEngine.Build build, ObjectNode context) {
        ObjectNode image = image(build);
        if ("DOCKER".equals(image.path("type").asText())) {
            distributeContainer(build, image.path("distributionConfiguration").path("distributions"));
            return;
        }
        JsonNode distributions = image.path("distributionConfiguration").path("distributions");
        ObjectNode distributed = distributeImage(build, distributions);
        modifyAttributes(build, distributed.path("distributedImages"), null);
        applyConfigurations(build, distributed.path("distributedImages"), null);
    }

    private ObjectNode distributeImage(ImageBuilderEngine.Build build, JsonNode given) {
        ObjectNode image = image(build);
        String buildAmi = meta(image).path("buildAmi").asText("");
        if (buildAmi.isEmpty()) {
            throw new IllegalStateException("The build created no AMI to distribute.");
        }
        JsonNode distributions = given == null || given.isMissingNode() || given.isEmpty()
                ? image.path("distributionConfiguration").path("distributions") : given;
        ArrayNode amis = store.mapper().createArrayNode();
        ArrayNode distributed = store.mapper().createArrayNode();
        Instant buildDate = Instant.parse(image.path("dateCreated").asText(Instant.now().toString()));
        boolean copied = false;
        if (distributions.isMissingNode() || distributions.isEmpty()) {
            amis.add(ami(build.region, buildAmi, meta(image).path("buildAmiName").asText(), "", build.account));
            distributed.add(distributedImage(build.region, buildAmi, meta(image).path("buildAmiName").asText(),
                    build.account));
        }
        for (JsonNode distribution : distributions) {
            String region = distribution.path("region").asText(build.region);
            JsonNode configuration = distribution.has("amiDistributionConfiguration")
                    ? distribution.path("amiDistributionConfiguration") : distribution;
            String name = render(configuration.path("name").asText(image.path("name").asText()
                    + " {{ imagebuilder:buildDate }}"), image, buildDate);
            String description = configuration.path("description").asText("");
            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag("CreatedBy", "EC2 Image Builder"));
            tags.add(new Tag("Ec2ImageBuilderArn", image.path("arn").asText()));
            configuration.path("amiTags").fields().forEachRemaining(entry -> tags.add(new Tag(entry.getKey(),
                    render(entry.getValue().asText(), image, buildDate))));
            List<String> accounts = new ArrayList<>();
            configuration.path("targetAccountIds").forEach(account -> accounts.add(account.asText()));
            if (accounts.isEmpty()) {
                accounts.add(build.account);
            }
            for (String account : accounts) {
                String amiId = RequestScopes.callAs(account, region, () -> {
                    String copy = ec2.get().copyImage(region, build.region, buildAmi, name,
                            description.isEmpty() ? null : description, false, tags).getImageId();
                    ec2.get().createTags(region, List.of(copy), tags);
                    return copy;
                });
                copied = true;
                amis.add(ami(region, amiId, name, description, account));
                distributed.add(distributedImage(region, amiId, name, account));
            }
        }
        if (copied) {
            try {
                ec2.get().deregisterImage(build.region, buildAmi, false);
            } catch (RuntimeException e) {
                LOG.debugv("Could not deregister the intermediate AMI {0}: {1}", buildAmi, e.getMessage());
            }
        }
        ObjectNode current = image(build);
        current.putObject("outputResources").set("amis", amis);
        saveImage(build, current);
        ObjectNode outputs = object();
        outputs.set("distributedImages", distributed);
        return outputs;
    }

    private ObjectNode ami(String region, String amiId, String name, String description, String account) {
        ObjectNode ami = object().put("region", region).put("image", amiId).put("name", name)
                .put("description", description).put("accountId", account);
        ami.putObject("state").put("status", "AVAILABLE");
        return ami;
    }

    private ObjectNode distributedImage(String region, String amiId, String name, String account) {
        return object().put("region", region).put("name", name).put("amiId", amiId).put("accountId", account)
                .put("dateStarted", Instant.now().toString()).put("dateStopped", Instant.now().toString())
                .put("status", "Completed").put("step", "Completed");
    }

    /** Records the launch permissions a distribution grants on each distributed AMI. */
    private ObjectNode modifyAttributes(ImageBuilderEngine.Build build, JsonNode distributedImages,
                                        JsonNode attributes) {
        ObjectNode image = image(build);
        ArrayNode modified = store.mapper().createArrayNode();
        for (JsonNode distributed : distributedImages) {
            JsonNode permission = null;
            String region = distributed.path("region").asText();
            if (attributes != null && attributes.isArray()) {
                for (JsonNode attribute : attributes) {
                    if (region.equals(attribute.path("region").asText())) {
                        permission = attribute.path("launchPermission");
                    }
                }
            } else {
                for (JsonNode distribution : image.path("distributionConfiguration").path("distributions")) {
                    if (region.equals(distribution.path("region").asText())) {
                        permission = distribution.path("amiDistributionConfiguration").path("launchPermission");
                    }
                }
            }
            if (permission != null && !permission.isMissingNode()) {
                meta(image).withObject("/launchPermissions").set(distributed.path("amiId").asText(),
                        permission.deepCopy());
                modified.add(((ObjectNode) distributed.deepCopy()).put("step", "Completed"));
            }
        }
        saveImage(build, image);
        ObjectNode outputs = object();
        outputs.set("modifiedImages", modified);
        return outputs;
    }

    /** Applies launch template and SSM parameter configurations to the distributed AMIs. */
    private ObjectNode applyConfigurations(ImageBuilderEngine.Build build, JsonNode distributedImages,
                                           JsonNode configurations) {
        ObjectNode image = image(build);
        ArrayNode configured = store.mapper().createArrayNode();
        for (JsonNode distributed : distributedImages) {
            String region = distributed.path("region").asText();
            JsonNode configuration = null;
            if (configurations != null && configurations.isArray()) {
                for (JsonNode candidate : configurations) {
                    if (region.equals(candidate.path("region").asText())) {
                        configuration = candidate;
                    }
                }
            } else {
                for (JsonNode distribution : image.path("distributionConfiguration").path("distributions")) {
                    if (region.equals(distribution.path("region").asText())) {
                        configuration = distribution;
                    }
                }
            }
            if (configuration == null) {
                continue;
            }
            String amiId = distributed.path("amiId").asText();
            for (JsonNode template : configuration.path("launchTemplateConfigurations")) {
                RequestScopes.runAs(template.path("accountId").asText(build.account), region, () -> {
                    LaunchTemplateData data = new LaunchTemplateData();
                    data.setImageId(amiId);
                    LaunchTemplate updated = ec2.get().createLaunchTemplateVersion(region,
                            template.path("launchTemplateId").asText(), null, "$Latest", data);
                    if (template.path("setDefaultVersion").asBoolean(false)) {
                        ec2.get().modifyLaunchTemplate(region, template.path("launchTemplateId").asText(), null,
                                updated.getLatestVersionNumber());
                    }
                });
            }
            for (JsonNode parameter : configuration.path("ssmParameterConfigurations")) {
                RequestScopes.runAs(parameter.path("amiAccountId").asText(build.account), region, () ->
                        ssm.get().putParameter(parameter.path("parameterName").asText(), amiId, "String",
                                "AMI distributed by EC2 Image Builder", true, Map.of(), region));
            }
            configured.add(((ObjectNode) distributed.deepCopy()).put("step", "Completed"));
        }
        ObjectNode outputs = object();
        outputs.set("configuredImages", configured);
        return outputs;
    }

    // ---------------------------------------------------------------- containers

    /** Pushes the built container image to each distribution's ECR repository, tagged with the image version. */
    private ObjectNode distributeContainer(ImageBuilderEngine.Build build, JsonNode given) {
        ObjectNode image = image(build);
        String local = meta(image).path("containerImage").asText("");
        if (local.isEmpty()) {
            throw new IllegalStateException("The build created no container image to distribute.");
        }
        JsonNode recipe = image.path("containerRecipe");
        JsonNode distributions = given == null || given.isMissingNode() || given.isEmpty()
                ? image.path("distributionConfiguration").path("distributions") : given;
        List<JsonNode> targets = new ArrayList<>();
        distributions.forEach(targets::add);
        if (targets.isEmpty()) {
            ObjectNode target = object().put("region", build.region);
            target.putObject("containerDistributionConfiguration").set("targetRepository",
                    recipe.path("targetRepository").deepCopy());
            targets.add(target);
        }
        ArrayNode containers = store.mapper().createArrayNode();
        ArrayNode succeeded = store.mapper().createArrayNode();
        String version = image.path("version").asText().replace('/', '-');
        for (JsonNode target : targets) {
            String region = target.path("region").asText(build.region);
            JsonNode configuration = target.path("containerDistributionConfiguration");
            JsonNode repository = configuration.has("targetRepository") ? configuration.get("targetRepository")
                    : recipe.path("targetRepository");
            String repositoryName = repository.path("repositoryName").asText();
            String uri = RequestScopes.callAs(build.account, region, () -> {
                List<Repository> found = ecr.get().describeRepositories(List.of(repositoryName), null, region);
                if (found.isEmpty()) {
                    throw new IllegalStateException("The ECR repository " + repositoryName + " does not exist in "
                            + region + ".");
                }
                return found.get(0).getRepositoryUri();
            });
            String token = RequestScopes.callAs(build.account, region, () -> ecr.get().getAuthorizationToken()
                    .getAuthorizationToken());
            List<String> tags = new ArrayList<>();
            configuration.path("containerTags").forEach(tag -> tags.add(render(tag.asText(), image, Instant.now())));
            if (tags.isEmpty()) {
                tags.add(version);
            }
            ArrayNode uris = store.mapper().createArrayNode();
            for (String tag : tags) {
                toe.push(local, uri, tag, token);
                uris.add(uri + ":" + tag);
            }
            containers.addObject().put("region", region).set("imageUris", uris);
            succeeded.add(region);
        }
        ObjectNode current = image(build);
        current.putObject("outputResources").set("containers", containers);
        saveImage(build, current);
        ObjectNode outputs = object().put("status", "Success");
        outputs.set("succeededRegions", succeeded);
        outputs.putArray("failedRegions");
        return outputs;
    }

    // ---------------------------------------------------------------- integrations

    private ObjectNode runCommand(ImageBuilderEngine.Build build, ObjectNode inputs) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        inputs.path("parameters").fields().forEachRemaining(entry -> {
            List<String> values = new ArrayList<>();
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(value -> values.add(value.asText()));
            } else {
                values.add(entry.getValue().asText());
            }
            parameters.put(entry.getKey(), values);
        });
        ImageBuilderToe.Result result = toe.runDocument(inputs.path("instanceId").asText(),
                inputs.path("documentName").asText(), parameters, defaultTimeout("RunCommand"), build.region);
        if (!result.success()) {
            throw new IllegalStateException("The command failed: " + lastLines(result.output()));
        }
        ObjectNode outputs = object().put("runCommandId", UUID.randomUUID().toString())
                .put("status", result.status());
        ArrayNode lines = outputs.putArray("output");
        for (String line : result.output().split("\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return outputs;
    }

    private ObjectNode executeStateMachine(ImageBuilderEngine.Build build, ObjectNode inputs) {
        Execution execution = states.get().startExecution(inputs.path("stateMachineArn").asText(),
                "imagebuilder-" + UUID.randomUUID(), inputs.path("input").asText("{}"), build.region);
        while (true) {
            Execution current = states.get().describeExecution(execution.getExecutionArn());
            String status = current.getStatus();
            if ("SUCCEEDED".equals(status)) {
                return object().put("executionArn", current.getExecutionArn())
                        .put("output", current.getOutput() == null ? "" : current.getOutput());
            }
            if (!"RUNNING".equals(status) && !"PENDING_REDRIVE".equals(status)) {
                throw new IllegalStateException("The state machine execution " + current.getExecutionArn()
                        + " ended " + status + ".");
            }
            sleep(1000);
        }
    }

    /** Sends a waiting step's SNS notification and Lambda invocation, as WaitForAction does. */
    void notifyWaiting(ImageBuilderEngine.Build build, ObjectNode inputs, ObjectNode message) {
        RequestScopes.runAs(build.account, build.region, () -> {
            if (inputs.hasNonNull("snsTopicArn")) {
                sns.get().publish(inputs.get("snsTopicArn").asText(), null, message.toString(), null, build.region);
            }
            if (inputs.hasNonNull("lambdaFunctionName")) {
                String function = inputs.get("lambdaFunctionName").asText();
                if (AwsArnUtils.isArn(function)) {
                    lambda.get().invokeArn(function, message.toString().getBytes(StandardCharsets.UTF_8),
                            InvocationType.Event);
                } else {
                    lambda.get().invoke(build.region, function, message.toString().getBytes(StandardCharsets.UTF_8),
                            InvocationType.Event);
                }
            }
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted.");
        }
    }
}

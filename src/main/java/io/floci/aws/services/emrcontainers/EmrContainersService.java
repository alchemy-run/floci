package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.eks.EksAccessEntryService;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.emrcontainers.EmrContainersStore.ENDPOINT;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.SECURITY_CONFIGURATION;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.TEMPLATE;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.VIRTUAL_CLUSTER;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.invalid;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.meta;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.notFound;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.now;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.optional;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.text;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.view;

/**
 * Amazon EMR on EKS: virtual clusters registered against floci EKS clusters' namespaces, job templates with
 * parameters, interactive managed endpoints, security configurations and tags. Job runs are
 * {@link EmrContainersJobRuns}'.
 *
 * <p>A virtual cluster needs an ACTIVE EKS cluster. On a cluster that authenticates with access entries, EMR on
 * EKS's service-linked role gets an access entry the first time a virtual cluster is registered, as AWS's
 * integration adds one; a virtual cluster whose EKS cluster is deleted is {@code ARRESTED}.
 */
@ApplicationScoped
public class EmrContainersService {

    private static final Logger LOG = Logger.getLogger(EmrContainersService.class);
    static final Pattern RELEASE_LABEL = Pattern.compile("emr-(\\d+)\\.(\\d+)\\.(\\d+)(-latest|-\\d{8})?");
    private static final Pattern PARAMETER = Pattern.compile("\\$\\{([^}]*)}");
    private static final String SERVICE_LINKED_ROLE = "AWSServiceRoleForAmazonEMRContainers";
    private static final String ACCESS_ENTRY_USERNAME = "emr-containers";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> TERMINAL_ENDPOINT = Set.of("TERMINATED", "TERMINATED_WITH_ERRORS");

    private final EmrContainersStore store;
    private final EmulatorConfig config;
    private final IamService iam;
    private final Instance<EksService> eks;
    private final Instance<EksAccessEntryService> accessEntries;
    private final Instance<KmsService> kms;
    private final Instance<SecretsManagerService> secrets;
    private final Instance<AcmService> acm;
    private final Instance<RequestContext> requestContext;
    private final Instance<EmrContainersJobRuns> jobRuns;

    @Inject
    public EmrContainersService(EmrContainersStore store, EmulatorConfig config, IamService iam,
                                Instance<EksService> eks, Instance<EksAccessEntryService> accessEntries,
                                Instance<KmsService> kms, Instance<SecretsManagerService> secrets,
                                Instance<AcmService> acm, Instance<RequestContext> requestContext,
                                Instance<EmrContainersJobRuns> jobRuns) {
        this.store = store;
        this.config = config;
        this.iam = iam;
        this.eks = eks;
        this.accessEntries = accessEntries;
        this.kms = kms;
        this.secrets = secrets;
        this.acm = acm;
        this.requestContext = requestContext;
        this.jobRuns = jobRuns;
    }

    // ---------------------------------------------------------------- virtual clusters

    public synchronized ObjectNode createVirtualCluster(JsonNode input, String region) {
        String name = text(input, "name");
        String token = text(input, "clientToken");
        JsonNode provider = input.path("containerProvider");
        if (!"EKS".equals(provider.path("type").asText())) {
            throw invalid("containerProvider.type must be EKS.");
        }
        String clusterName = text(provider, "id");
        String namespace = provider.path("info").path("eksInfo").path("namespace").asText("default");
        for (ObjectNode existing : store.list(region, VIRTUAL_CLUSTER, "", vc -> true)) {
            if (token.equals(meta(existing).path("clientToken").asText(null))) {
                return identity(existing);
            }
        }
        Cluster cluster = eksCluster(clusterName);
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw invalid("EKS cluster " + clusterName + " is not ACTIVE.");
        }
        for (ObjectNode existing : store.list(region, VIRTUAL_CLUSTER, "", vc -> "RUNNING".equals(state(region, vc)))) {
            JsonNode existingProvider = existing.path("containerProvider");
            if (clusterName.equals(existingProvider.path("id").asText()) && namespace.equals(existingProvider
                    .path("info").path("eksInfo").path("namespace").asText("default"))) {
                throw invalid("A virtual cluster already exists in the given namespace");
            }
        }
        String securityConfigurationId = optional(input, "securityConfigurationId");
        if (securityConfigurationId != null && store.get(region, SECURITY_CONFIGURATION, securityConfigurationId)
                .isEmpty()) {
            throw invalid("Security configuration " + securityConfigurationId + " does not exist.");
        }
        IamRole serviceRole = serviceLinkedRole();
        boolean integrated = false;
        if (accessEntries.isResolvable() && EksAccessEntryService.usesAccessEntries(cluster)) {
            String principal = AwsArnUtils.Arn.global(AwsArnUtils.parse(cluster.getArn()).partition(), "iam",
                    store.account(), "role/" + SERVICE_LINKED_ROLE).toString();
            accessEntries.get().serviceEntry(cluster, principal, serviceRole.getRoleId(), ACCESS_ENTRY_USERNAME);
            integrated = true;
        }
        String id = store.id(25);
        ObjectNode vc = store.record();
        vc.put("id", id).put("name", name).put("arn", store.arn(region, "virtualclusters/" + id))
                .put("state", "RUNNING");
        ObjectNode containerProvider = vc.putObject("containerProvider");
        containerProvider.put("type", "EKS").put("id", clusterName);
        containerProvider.putObject("info").putObject("eksInfo").put("namespace", namespace);
        String nodeLabel = provider.path("info").path("eksInfo").path("nodeLabel").asText(null);
        if (nodeLabel != null) {
            containerProvider.withObject("/info/eksInfo").put("nodeLabel", nodeLabel);
        }
        vc.put("createdAt", now());
        vc.set("tags", tags(input.get("tags")));
        if (securityConfigurationId != null) {
            vc.put("securityConfigurationId", securityConfigurationId);
        }
        vc.put("sessionEnabled", input.path("sessionEnabled").asBoolean(false));
        if (input.hasNonNull("schedulerConfiguration")) {
            vc.set("schedulerConfiguration", schedulerConfiguration(input.get("schedulerConfiguration")));
        }
        meta(vc).put("clientToken", token).put("eksAccessEntryIntegrated", integrated)
                .put("clusterArn", cluster.getArn());
        store.put(region, VIRTUAL_CLUSTER, id, vc);
        LOG.infov("EMR on EKS virtual cluster {0} registered on {1}/{2}", id, clusterName, namespace);
        return identity(vc);
    }

    public synchronized ObjectNode describeVirtualCluster(JsonNode input, String region) {
        ObjectNode vc = virtualCluster(region, text(input, "id"));
        ObjectNode response = store.mapper().createObjectNode();
        response.set("virtualCluster", virtualClusterView(region, vc));
        return response;
    }

    public synchronized ObjectNode listVirtualClusters(JsonNode input, String region) {
        Set<String> states = values(input.get("states"));
        String providerId = optional(input, "containerProviderId");
        String providerType = optional(input, "containerProviderType");
        Boolean integrated = input.hasNonNull("eksAccessEntryIntegrated")
                ? input.get("eksAccessEntryIntegrated").asBoolean() : null;
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode vc : store.list(region, VIRTUAL_CLUSTER, "", record -> true)) {
            String state = state(region, vc);
            if ((states.isEmpty() || states.contains(state)) && EmrContainersStore.createdWithin(vc, input)
                    && (providerId == null || providerId.equals(vc.path("containerProvider").path("id").asText()))
                    && (providerType == null || providerType.equals(vc.path("containerProvider").path("type")
                            .asText()))
                    && (integrated == null || integrated == meta(vc).path("eksAccessEntryIntegrated")
                            .asBoolean(false))) {
                items.add(virtualClusterView(region, vc));
            }
        }
        return store.page(items, input, "virtualClusters");
    }

    public synchronized ObjectNode updateVirtualCluster(JsonNode input, String region) {
        String id = text(input, "id");
        ObjectNode vc = virtualCluster(region, id);
        if (!"RUNNING".equals(state(region, vc))) {
            throw invalid("Virtual cluster " + id + " is not RUNNING.");
        }
        if (input.has("schedulerConfiguration")) {
            vc.set("schedulerConfiguration", schedulerConfiguration(input.get("schedulerConfiguration")));
        }
        store.put(region, VIRTUAL_CLUSTER, id, vc);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("virtualCluster", virtualClusterView(region, vc));
        return response;
    }

    /**
     * Terminates a virtual cluster: its jobs that have not ended are cancelled and its managed endpoints terminated,
     * and it stays listed as {@code TERMINATED}.
     */
    public synchronized ObjectNode deleteVirtualCluster(JsonNode input, String region) {
        String id = text(input, "id");
        ObjectNode vc = store.get(region, VIRTUAL_CLUSTER, id).orElseThrow(() -> invalid(
                "Virtual cluster " + id + " doesn't exist."));
        if (Set.of("TERMINATING", "TERMINATED").contains(state(region, vc))) {
            throw invalid("Virtual cluster " + id + " is already " + state(region, vc) + ".");
        }
        if (jobRuns.isResolvable()) {
            jobRuns.get().cancelAll(region, id);
        }
        for (ObjectNode endpoint : store.list(region, ENDPOINT, id + "/", record -> true)) {
            if (!TERMINAL_ENDPOINT.contains(endpoint.path("state").asText())) {
                endpoint.put("state", "TERMINATED").put("stateDetails", "The virtual cluster was deleted.");
                store.put(region, ENDPOINT, id + "/" + endpoint.path("id").asText(), endpoint);
            }
        }
        vc.put("state", "TERMINATED");
        store.put(region, VIRTUAL_CLUSTER, id, vc);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", id);
        return response;
    }

    /** A virtual cluster, whatever its state; ResourceNotFoundException when there is none. */
    ObjectNode virtualCluster(String region, String id) {
        return store.get(region, VIRTUAL_CLUSTER, id).orElseThrow(() -> notFound(
                "Virtual cluster " + id + " doesn't exist."));
    }

    /**
     * A virtual cluster's state: a RUNNING one whose EKS cluster no longer exists is ARRESTED, as EMR on EKS
     * reports a virtual cluster whose cluster it cannot reach.
     */
    String state(String region, ObjectNode vc) {
        String state = vc.path("state").asText();
        if (!"RUNNING".equals(state) || !eks.isResolvable()) {
            return state;
        }
        try {
            Cluster cluster = eks.get().describeCluster(vc.path("containerProvider").path("id").asText());
            String clusterArn = meta(vc).path("clusterArn").asText(null);
            if (clusterArn == null || clusterArn.equals(cluster.getArn())) {
                return state;
            }
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                return state;
            }
        }
        vc.put("state", "ARRESTED");
        store.put(region, VIRTUAL_CLUSTER, vc.path("id").asText(), vc);
        return "ARRESTED";
    }

    private ObjectNode virtualClusterView(String region, ObjectNode vc) {
        vc.put("state", state(region, vc));
        ObjectNode view = view(vc);
        if (vc.has("schedulerConfiguration") && jobRuns.isResolvable()) {
            view.set("schedulerStatus", jobRuns.get().schedulerStatus(region, vc.path("id").asText()));
        }
        return view;
    }

    private ObjectNode schedulerConfiguration(JsonNode input) {
        ObjectNode configuration = store.mapper().createObjectNode();
        if (input == null || input.isNull()) {
            return configuration;
        }
        if (input.hasNonNull("maxConcurrentJobRuns")) {
            configuration.put("maxConcurrentJobRuns", input.get("maxConcurrentJobRuns").asInt());
        }
        if (input.hasNonNull("maxInQueueJobRuns")) {
            configuration.put("maxInQueueJobRuns", input.get("maxInQueueJobRuns").asInt());
        }
        return configuration;
    }

    private Cluster eksCluster(String name) {
        if (!eks.isResolvable()) {
            throw invalid("EKS cluster " + name + " does not exist.");
        }
        try {
            return eks.get().describeCluster(name);
        } catch (AwsException e) {
            throw invalid("EKS cluster " + name + " does not exist.");
        }
    }

    /** EMR on EKS's service-linked role, which it creates in the account the first time it is needed. */
    private IamRole serviceLinkedRole() {
        Optional<IamRole> existing = iam.findRole(store.account(), SERVICE_LINKED_ROLE);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return iam.createServiceLinkedRole("emr-containers.amazonaws.com", null, null); // partition-literal: a service principal
        } catch (AwsException e) {
            return iam.findRole(store.account(), SERVICE_LINKED_ROLE).orElseThrow(() -> e);
        }
    }

    // ---------------------------------------------------------------- job templates

    public synchronized ObjectNode createJobTemplate(JsonNode input, String region) {
        String name = text(input, "name");
        String token = text(input, "clientToken");
        for (ObjectNode existing : store.list(region, TEMPLATE, "", template -> true)) {
            if (token.equals(meta(existing).path("clientToken").asText(null))) {
                return identity(existing);
            }
            if (name.equals(existing.path("name").asText())) {
                throw invalid("A job template with name " + name + " already exists.");
            }
        }
        JsonNode data = input.path("jobTemplateData");
        validateTemplateData(data);
        String kmsKeyArn = optional(input, "kmsKeyArn");
        if (kmsKeyArn != null) {
            kmsKeyArn = kmsKey(kmsKeyArn, region);
        }
        String id = store.id(19);
        ObjectNode template = store.record();
        template.put("name", name).put("id", id).put("arn", store.arn(region, "jobtemplates/" + id))
                .put("createdAt", now()).put("createdBy", caller(region));
        template.set("tags", tags(input.get("tags")));
        template.set("jobTemplateData", data.deepCopy());
        if (kmsKeyArn != null) {
            template.put("kmsKeyArn", kmsKeyArn);
        }
        meta(template).put("clientToken", token);
        store.put(region, TEMPLATE, id, template);
        return identity(template);
    }

    public synchronized ObjectNode describeJobTemplate(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("jobTemplate", view(jobTemplate(region, text(input, "id"))));
        return response;
    }

    public synchronized ObjectNode listJobTemplates(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode template : store.list(region, TEMPLATE, "", record -> true)) {
            if (EmrContainersStore.createdWithin(template, input)) {
                items.add(view(template));
            }
        }
        return store.page(items, input, "templates");
    }

    public synchronized ObjectNode deleteJobTemplate(JsonNode input, String region) {
        String id = text(input, "id");
        if (store.get(region, TEMPLATE, id).isEmpty()) {
            throw invalid("Job template " + id + " doesn't exist.");
        }
        store.delete(region, TEMPLATE, id);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", id);
        return response;
    }

    ObjectNode jobTemplate(String region, String id) {
        return store.get(region, TEMPLATE, id).orElseThrow(() -> notFound("Job template " + id + " doesn't exist."));
    }

    /**
     * A job template's data: a driver, and parameters that are declared once and used as {@code ${name}} in its
     * values, whose defaults, when given, are of their declared type.
     */
    private void validateTemplateData(JsonNode data) {
        text(data, "executionRoleArn");
        String release = text(data, "releaseLabel");
        if (!release.contains("${")) {
            releaseLabel(release);
        }
        driver(data.path("jobDriver"));
        JsonNode parameters = data.path("parameterConfiguration");
        parameters.fields().forEachRemaining(entry -> {
            String type = entry.getValue().path("type").asText("STRING");
            String fallback = entry.getValue().path("defaultValue").asText(null);
            if ("NUMBER".equals(type) && fallback != null && !isNumber(fallback)) {
                throw invalid("The default value of parameter " + entry.getKey() + " is not a NUMBER.");
            }
        });
        Matcher matcher = PARAMETER.matcher(data.toString());
        while (matcher.find()) {
            if (!parameters.has(matcher.group(1))) {
                throw invalid("Parameter " + matcher.group(1) + " is used but not declared in parameterConfiguration.");
            }
        }
    }

    /**
     * A job template's data with its parameters replaced by the run's values, or their defaults; a parameter the
     * template does not declare, or one with no value and no default, is refused.
     */
    ObjectNode resolveTemplate(ObjectNode template, JsonNode values) {
        JsonNode data = template.path("jobTemplateData");
        JsonNode parameters = data.path("parameterConfiguration");
        Map<String, String> resolved = new LinkedHashMap<>();
        if (values != null) {
            values.fields().forEachRemaining(entry -> {
                if (!parameters.has(entry.getKey())) {
                    throw invalid("Parameter " + entry.getKey() + " is not declared in the job template.");
                }
                resolved.put(entry.getKey(), entry.getValue().asText());
            });
        }
        parameters.fields().forEachRemaining(entry -> {
            String value = resolved.get(entry.getKey());
            if (value == null) {
                value = entry.getValue().path("defaultValue").asText(null);
            }
            if (value == null) {
                throw invalid("Parameter " + entry.getKey() + " has no value and no default.");
            }
            if ("NUMBER".equals(entry.getValue().path("type").asText()) && !isNumber(value)) {
                throw invalid("Parameter " + entry.getKey() + " must be a NUMBER.");
            }
            resolved.put(entry.getKey(), value);
        });
        ObjectNode copy = data.deepCopy();
        copy.remove("parameterConfiguration");
        return (ObjectNode) substitute(copy, resolved);
    }

    private JsonNode substitute(JsonNode node, Map<String, String> values) {
        if (node.isTextual()) {
            Matcher matcher = PARAMETER.matcher(node.asText());
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
            }
            matcher.appendTail(out);
            return store.mapper().getNodeFactory().textNode(out.toString());
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String field : names) {
                object.set(field, substitute(object.get(field), values));
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, substitute(array.get(index), values));
            }
        }
        return node;
    }

    private static boolean isNumber(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A release label EMR on EKS offers, {@code emr-<major>.<minor>.<patch>[-latest|-<yyyymmdd>]}, 5.32.0 on. */
    static String releaseLabel(String label) {
        Matcher matcher = RELEASE_LABEL.matcher(label);
        if (!matcher.matches()) {
            throw invalid("Release label " + label + " is not valid.");
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        if (major < 5 || (major == 5 && minor < 32)) {
            throw invalid("Release label " + label + " is not supported by EMR on EKS.");
        }
        return label;
    }

    /** A job driver: exactly one of a {@code spark-submit} driver and a Spark SQL driver. */
    static void driver(JsonNode driver) {
        boolean submit = driver.hasNonNull("sparkSubmitJobDriver");
        boolean sql = driver.hasNonNull("sparkSqlJobDriver");
        if (submit == sql) {
            throw invalid("jobDriver must have exactly one of sparkSubmitJobDriver and sparkSqlJobDriver.");
        }
        if (submit) {
            text(driver.get("sparkSubmitJobDriver"), "entryPoint");
        }
    }

    // ---------------------------------------------------------------- managed endpoints

    public synchronized ObjectNode createManagedEndpoint(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterId");
        ObjectNode vc = virtualCluster(region, vcId);
        if (!"RUNNING".equals(state(region, vc))) {
            throw invalid("Virtual cluster " + vcId + " is not RUNNING.");
        }
        String name = text(input, "name");
        String token = text(input, "clientToken");
        for (ObjectNode existing : store.list(region, ENDPOINT, vcId + "/", record -> true)) {
            if (token.equals(meta(existing).path("clientToken").asText(null))) {
                return endpointIdentity(existing);
            }
            if (name.equals(existing.path("name").asText())
                    && !TERMINAL_ENDPOINT.contains(existing.path("state").asText())) {
                throw invalid("A managed endpoint with name " + name + " already exists.");
            }
        }
        String type = text(input, "type");
        if (!"JUPYTER_ENTERPRISE_GATEWAY".equals(type)) {
            throw invalid("Managed endpoint type " + type + " is not supported.");
        }
        String release = releaseLabel(text(input, "releaseLabel"));
        String role = executionRole(text(input, "executionRoleArn"));
        String certificateArn = optional(input, "certificateArn");
        if (certificateArn != null && acm.isResolvable()) {
            try {
                acm.get().describeCertificate(certificateArn, region);
            } catch (AwsException e) {
                throw invalid("Certificate " + certificateArn + " does not exist.");
            }
        }
        Cluster cluster = eksCluster(vc.path("containerProvider").path("id").asText());
        String id = store.id(13);
        ObjectNode endpoint = store.record();
        endpoint.put("id", id).put("name", name)
                .put("arn", store.arn(region, "virtualclusters/" + vcId + "/endpoints/" + id))
                .put("virtualClusterId", vcId).put("type", type).put("state", "ACTIVE").put("releaseLabel", release)
                .put("executionRoleArn", role);
        if (certificateArn != null) {
            endpoint.put("certificateArn", certificateArn);
            endpoint.putObject("certificateAuthority").put("certificateArn", certificateArn);
        }
        if (input.has("configurationOverrides")) {
            endpoint.set("configurationOverrides", input.get("configurationOverrides").deepCopy());
        }
        String host = id + "." + vcId + ".emrcontainers." + region + "." + dnsSuffix();
        endpoint.put("serverUrl", "https://" + host + ":18888").put("authProxyUrl", "https://" + host)
                .put("createdAt", now());
        if (cluster.getResourcesVpcConfig() != null) {
            if (cluster.getResourcesVpcConfig().getClusterSecurityGroupId() != null) {
                endpoint.put("securityGroup", cluster.getResourcesVpcConfig().getClusterSecurityGroupId());
            }
            ArrayNode subnets = endpoint.putArray("subnetIds");
            if (cluster.getResourcesVpcConfig().getSubnetIds() != null) {
                cluster.getResourcesVpcConfig().getSubnetIds().forEach(subnets::add);
            }
        }
        endpoint.set("tags", tags(input.get("tags")));
        meta(endpoint).put("clientToken", token);
        if (input.hasNonNull("sessionIdleTimeoutInMinutes")) {
            meta(endpoint).put("sessionIdleTimeoutInMinutes", input.get("sessionIdleTimeoutInMinutes").asInt());
        }
        store.put(region, ENDPOINT, vcId + "/" + id, endpoint);
        return endpointIdentity(endpoint);
    }

    public synchronized ObjectNode describeManagedEndpoint(JsonNode input, String region) {
        ObjectNode endpoint = endpoint(region, text(input, "virtualClusterId"), text(input, "id"));
        ObjectNode response = store.mapper().createObjectNode();
        response.set("endpoint", view(endpoint));
        return response;
    }

    public synchronized ObjectNode listManagedEndpoints(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterId");
        Set<String> states = values(input.get("states"));
        Set<String> types = values(input.get("types"));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode endpoint : store.list(region, ENDPOINT, vcId + "/", record -> true)) {
            if ((states.isEmpty() || states.contains(endpoint.path("state").asText()))
                    && (types.isEmpty() || types.contains(endpoint.path("type").asText()))
                    && EmrContainersStore.createdWithin(endpoint, input)) {
                items.add(view(endpoint));
            }
        }
        return store.page(items, input, "endpoints");
    }

    public synchronized ObjectNode deleteManagedEndpoint(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterId");
        String id = text(input, "id");
        ObjectNode endpoint = store.get(region, ENDPOINT, vcId + "/" + id).orElseThrow(() -> invalid(
                "Managed endpoint " + id + " doesn't exist."));
        if (TERMINAL_ENDPOINT.contains(endpoint.path("state").asText())) {
            throw invalid("Managed endpoint " + id + " is already " + endpoint.path("state").asText() + ".");
        }
        endpoint.put("state", "TERMINATED");
        store.put(region, ENDPOINT, vcId + "/" + id, endpoint);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", id).put("virtualClusterId", vcId);
        return response;
    }

    /** A token a client presents to an ACTIVE endpoint, for the endpoint's own execution role. */
    public synchronized ObjectNode getManagedEndpointSessionCredentials(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterIdentifier");
        String id = text(input, "endpointIdentifier");
        ObjectNode endpoint = endpoint(region, vcId, id);
        if (!"ACTIVE".equals(endpoint.path("state").asText())) {
            throw invalid("Managed endpoint " + id + " is not ACTIVE.");
        }
        String role = text(input, "executionRoleArn");
        if (!role.equals(endpoint.path("executionRoleArn").asText())) {
            throw invalid("The execution role is not the managed endpoint's.");
        }
        if (!"TOKEN".equals(text(input, "credentialType"))) {
            throw invalid("credentialType must be TOKEN.");
        }
        int duration = input.hasNonNull("durationInSeconds") ? input.get("durationInSeconds").asInt() : 900;
        if (duration < 900 || duration > 43200) {
            throw invalid("durationInSeconds must be between 900 and 43200.");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", store.id(19));
        response.putObject("credentials").put("token", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(secret));
        response.put("expiresAt", Instant.now().plusSeconds(duration).toEpochMilli() / 1000.0);
        return response;
    }

    private ObjectNode endpoint(String region, String vcId, String id) {
        return store.get(region, ENDPOINT, vcId + "/" + id).orElseThrow(() -> notFound(
                "Managed endpoint " + id + " doesn't exist."));
    }

    private ObjectNode endpointIdentity(ObjectNode endpoint) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", endpoint.path("id").asText()).put("name", endpoint.path("name").asText())
                .put("arn", endpoint.path("arn").asText())
                .put("virtualClusterId", endpoint.path("virtualClusterId").asText());
        return response;
    }

    // ---------------------------------------------------------------- security configurations

    public synchronized ObjectNode createSecurityConfiguration(JsonNode input, String region) {
        String name = text(input, "name");
        String token = text(input, "clientToken");
        for (ObjectNode existing : store.list(region, SECURITY_CONFIGURATION, "", record -> true)) {
            if (token.equals(meta(existing).path("clientToken").asText(null))) {
                return identity(existing);
            }
        }
        JsonNode data = input.path("securityConfigurationData");
        JsonNode tls = data.path("authorizationConfiguration").path("encryptionConfiguration")
                .path("inTransitEncryptionConfiguration").path("tlsCertificateConfiguration");
        if (!tls.isMissingNode()) {
            if (!"PEM".equals(tls.path("certificateProviderType").asText("PEM"))) {
                throw invalid("certificateProviderType must be PEM.");
            }
            for (String field : List.of("publicCertificateSecretArn", "privateCertificateSecretArn")) {
                String secret = optional(tls, field);
                if (secret != null) {
                    secret(secret, region);
                }
            }
        }
        String id = store.id(19);
        ObjectNode configuration = store.record();
        configuration.put("id", id).put("name", name)
                .put("arn", store.arn(region, "securityconfigurations/" + id)).put("createdAt", now())
                .put("createdBy", caller(region));
        configuration.set("securityConfigurationData", data.deepCopy());
        configuration.set("tags", tags(input.get("tags")));
        meta(configuration).put("clientToken", token);
        store.put(region, SECURITY_CONFIGURATION, id, configuration);
        return identity(configuration);
    }

    public synchronized ObjectNode describeSecurityConfiguration(JsonNode input, String region) {
        String id = text(input, "id");
        ObjectNode configuration = store.get(region, SECURITY_CONFIGURATION, id).orElseThrow(() -> notFound(
                "Security configuration " + id + " doesn't exist."));
        ObjectNode response = store.mapper().createObjectNode();
        response.set("securityConfiguration", view(configuration));
        return response;
    }

    public synchronized ObjectNode listSecurityConfigurations(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode configuration : store.list(region, SECURITY_CONFIGURATION, "", record -> true)) {
            if (EmrContainersStore.createdWithin(configuration, input)) {
                items.add(view(configuration));
            }
        }
        return store.page(items, input, "securityConfigurations");
    }

    /** Deletes a security configuration no RUNNING virtual cluster uses. */
    public synchronized ObjectNode deleteSecurityConfiguration(JsonNode input, String region) {
        String id = text(input, "id");
        if (store.get(region, SECURITY_CONFIGURATION, id).isEmpty()) {
            throw invalid("Security configuration " + id + " doesn't exist.");
        }
        for (ObjectNode vc : store.list(region, VIRTUAL_CLUSTER, "", record -> true)) {
            if (id.equals(vc.path("securityConfigurationId").asText(null)) && "RUNNING".equals(state(region, vc))) {
                throw invalid("Security configuration " + id + " is used by virtual cluster "
                        + vc.path("id").asText() + ".");
            }
        }
        store.delete(region, SECURITY_CONFIGURATION, id);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", id);
        return response;
    }

    private void secret(String arn, String region) {
        if (!secrets.isResolvable()) {
            return;
        }
        try {
            secrets.get().describeSecret(arn, region);
        } catch (AwsException e) {
            throw invalid("Secret " + arn + " does not exist.");
        }
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTagsForResource(JsonNode input, String region) {
        ObjectNode record = taggable(region, text(input, "resourceArn"), false);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("tags", record.path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode input, String region) {
        String arn = text(input, "resourceArn");
        ObjectNode record = taggable(region, arn, true);
        ObjectNode tags = record.withObject("/tags");
        input.path("tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        if (tags.size() > 50) {
            throw invalid("A resource can have at most 50 tags.");
        }
        save(region, arn, record);
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode input, String region) {
        String arn = text(input, "resourceArn");
        ObjectNode record = taggable(region, arn, true);
        ObjectNode tags = record.withObject("/tags");
        input.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        save(region, arn, record);
        return store.mapper().createObjectNode();
    }

    private static final Pattern RESOURCE = Pattern.compile("arn:" + AwsArnUtils.PARTITION_REGEX
            + ":emr-containers:([a-z0-9-]+):(\\d{12}):/(virtualclusters/([0-9a-z]+)"
            + "(?:/(jobruns|endpoints)/([0-9a-z]+))?"
            + "|jobtemplates/([0-9a-z]+)|securityconfigurations/([0-9a-z]+))");

    /**
     * The record an ARN names. EMR on EKS tags virtual clusters, job runs, managed endpoints and security
     * configurations; a job template's ARN, whose tags are set when it is created, is an invalid input to
     * TagResource and UntagResource.
     */
    private ObjectNode taggable(String region, String arn, boolean mutate) {
        Matcher matcher = RESOURCE.matcher(arn);
        if (!matcher.matches()) {
            throw new AwsException("BadRequestException", "Invalid input resource arn " + arn, 400);
        }
        if (matcher.group(7) != null && mutate) {
            throw new AwsException("BadRequestException", "Invalid input resource arn " + arn, 400);
        }
        Optional<ObjectNode> record = Optional.empty();
        if (region.equals(matcher.group(1)) && store.account().equals(matcher.group(2))) {
            if (matcher.group(7) != null) {
                record = store.get(region, TEMPLATE, matcher.group(7));
            } else if (matcher.group(8) != null) {
                record = store.get(region, SECURITY_CONFIGURATION, matcher.group(8));
            } else if ("jobruns".equals(matcher.group(5))) {
                record = store.get(region, EmrContainersStore.JOB_RUN, matcher.group(4) + "/" + matcher.group(6));
            } else if ("endpoints".equals(matcher.group(5))) {
                record = store.get(region, ENDPOINT, matcher.group(4) + "/" + matcher.group(6));
            } else {
                record = store.get(region, VIRTUAL_CLUSTER, matcher.group(4));
            }
        }
        return record.orElseThrow(() -> notFound("Resource " + arn + " doesn't exist."));
    }

    private void save(String region, String arn, ObjectNode record) {
        Matcher matcher = RESOURCE.matcher(arn);
        if (!matcher.matches()) {
            return;
        }
        if (matcher.group(8) != null) {
            store.put(region, SECURITY_CONFIGURATION, matcher.group(8), record);
        } else if ("jobruns".equals(matcher.group(5))) {
            store.put(region, EmrContainersStore.JOB_RUN, matcher.group(4) + "/" + matcher.group(6), record);
        } else if ("endpoints".equals(matcher.group(5))) {
            store.put(region, ENDPOINT, matcher.group(4) + "/" + matcher.group(6), record);
        } else {
            store.put(region, VIRTUAL_CLUSTER, matcher.group(4), record);
        }
    }

    // ---------------------------------------------------------------- shared

    ObjectNode tags(JsonNode input) {
        ObjectNode tags = store.mapper().createObjectNode();
        if (input != null && input.isObject()) {
            input.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        if (tags.size() > 50) {
            throw invalid("A resource can have at most 50 tags.");
        }
        return tags;
    }

    /** An execution role that exists in the account. */
    String executionRole(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw invalid("Execution role " + arn + " is not valid.");
        }
        String name = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        if (!"iam".equals(parsed.service()) || iam.findRole(parsed.accountId(), name).isEmpty()) {
            throw invalid("Execution role " + arn + " does not exist.");
        }
        return arn;
    }

    private String kmsKey(String arn, String region) {
        if (!kms.isResolvable()) {
            return arn;
        }
        try {
            return kms.get().describeKey(arn, region).getArn();
        } catch (AwsException e) {
            throw invalid("KMS key " + arn + " does not exist.");
        }
    }

    /** The caller's ARN, as {@code createdBy} reports it. */
    String caller(String region) {
        String accessKey = requestContext.isResolvable() ? requestContext.get().getAccessKeyId() : null;
        String arn = accessKey == null ? null : iam.resolveCallerArn(accessKey).orElse(null);
        return arn != null ? arn
                : AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", store.account(), "root").toString();
    }

    private ObjectNode identity(ObjectNode record) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", record.path("id").asText()).put("name", record.path("name").asText())
                .put("arn", record.path("arn").asText());
        if (record.has("createdAt") && record.has("jobTemplateData")) {
            response.put("createdAt", record.path("createdAt").asDouble());
        }
        return response;
    }

    private static Set<String> values(JsonNode list) {
        Set<String> values = new LinkedHashSet<>();
        if (list != null) {
            if (list.isArray()) {
                list.forEach(value -> values.add(value.asText().toUpperCase(Locale.ROOT)));
            } else if (!list.isNull()) {
                values.add(list.asText().toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    private String dnsSuffix() {
        return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
    }
}

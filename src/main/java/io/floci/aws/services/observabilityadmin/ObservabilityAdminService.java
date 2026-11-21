package io.floci.aws.services.observabilityadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.cloudwatch.logs.LogEventsIngested;
import io.floci.aws.services.cloudwatch.logs.model.LogEvent;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.resourceexplorer2.ManagedViewSource;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.S3TablesService;
import io.floci.aws.services.s3tables.model.TableBucket;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.sqs.model.Message;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CloudWatch Observability Admin. Telemetry evaluation audits the account's resources ({@link TelemetryResources}) and,
 * while it runs, applies telemetry rules to them on every evaluation cycle. Organization evaluation, rules and
 * centralization rules belong to the management account; centralization rules copy log events from member accounts'
 * matching log groups into a destination account and Region as they arrive. Telemetry pipelines run their processors
 * on records ({@link TelemetryPipelineEngine}): TestTelemetryPipeline runs them on the records given, and pipelines with
 * an S3 source read the objects their SQS queue announces into their CloudWatch Logs sink. Enrichment owns a Resource
 * Explorer managed view, S3 Tables integrations own a table bucket.
 */
@ApplicationScoped
@Unremovable
public class ObservabilityAdminService implements ManagedViewSource {

    private static final Logger LOG = Logger.getLogger(ObservabilityAdminService.class);
    private static final String ACCOUNT = "account";
    private static final String TRUSTED_SERVICE = ServicePrincipals.of("observabilityadmin");
    private static final String MANAGED_VIEW = "AWSManagedViewForCloudWatchTelemetry";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_RULES = 100;
    private static final Set<String> ON = Set.of("STARTING", "RUNNING");
    private static final ThreadLocal<Boolean> COPYING = ThreadLocal.withInitial(() -> false);

    private final ObservabilityAdminStore store;
    private final ObjectMapper mapper;
    private final TelemetryResources resources;
    private final Provider<OrganizationsService> organizations;
    private final Provider<IamService> iam;
    private final Provider<CloudWatchLogsService> logs;
    private final Provider<SqsService> sqs;
    private final Provider<S3Service> s3;
    private final Provider<S3TablesService> s3tables;
    private final RegionResolver regionResolver;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "observabilityadmin");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public ObservabilityAdminService(ObservabilityAdminStore store, TelemetryResources resources,
                                     Provider<OrganizationsService> organizations, Provider<IamService> iam,
                                     Provider<CloudWatchLogsService> logs, Provider<SqsService> sqs,
                                     Provider<S3Service> s3, Provider<S3TablesService> s3tables,
                                     RegionResolver regionResolver, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.resources = resources;
        this.organizations = organizations;
        this.iam = iam;
        this.logs = logs;
        this.sqs = sqs;
        this.s3 = s3;
        this.s3tables = s3tables;
        this.regionResolver = regionResolver;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent event) {
        if (!store.config().enabled()) {
            return;
        }
        vertx.setPeriodic(Math.max(1, store.config().evaluationIntervalSeconds()) * 1000L, id ->
                worker.submit(this::evaluate));
        vertx.setPeriodic(Math.max(1, store.config().pipelinePollSeconds()) * 1000L, id ->
                worker.submit(this::pollPipelines));
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    private void later(Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warnv("Observability Admin work failed: {0}", e.getMessage());
            }
        }));
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw ObservabilityAdminStore.validation(member + " is required.");
        }
        return value;
    }

    private String arn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(ObservabilityAdminStore.SERVICE, region, account, resource).toString();
    }

    private String account() {
        return store.account();
    }

    private ObjectNode empty() {
        return mapper.createObjectNode();
    }

    // ================================================================ telemetry evaluation

    private Optional<ObjectNode> evaluation(String account, String kind) {
        return store.getForAccount(account, ObservabilityAdminStore.GLOBAL, kind, ACCOUNT);
    }

    private static boolean covers(JsonNode evaluation, String region) {
        if (evaluation.path("AllRegions").asBoolean(false)) {
            return true;
        }
        for (JsonNode covered : evaluation.path("Regions")) {
            if (region.equals(covered.asText())) {
                return true;
            }
        }
        return region.equals(evaluation.path("HomeRegion").asText());
    }

    /** Whether telemetry evaluation runs for an account (or its organization) in a Region. */
    boolean running(String account, String region, String kind) {
        return evaluation(account, kind).filter(record -> "RUNNING".equals(record.path("Status").asText())
                && covers(record, region)).isPresent();
    }

    private ObjectNode startEvaluation(JsonNode in, String region, String kind) {
        String account = account();
        ObjectNode record = evaluation(account, kind).orElseGet(store::record);
        String status = record.path("Status").asText("NOT_STARTED");
        if (ON.contains(status)) {
            return empty();
        }
        record.put("Status", "STARTING");
        record.put("HomeRegion", region);
        ArrayNode regions = record.putArray("Regions");
        Set<String> wanted = new LinkedHashSet<>();
        wanted.add(region);
        in.path("Regions").forEach(r -> wanted.add(r.asText()));
        wanted.forEach(regions::add);
        record.put("AllRegions", in.path("AllRegions").asBoolean(false));
        record.remove("FailureReason");
        store.putForAccount(account, ObservabilityAdminStore.GLOBAL, kind, ACCOUNT, record);
        later(() -> evaluation(account, kind).ifPresent(current -> {
            if ("STARTING".equals(current.path("Status").asText())) {
                current.put("Status", "RUNNING");
                store.putForAccount(account, ObservabilityAdminStore.GLOBAL, kind, ACCOUNT, current);
                evaluate();
            }
        }));
        return empty();
    }

    private ObjectNode stopEvaluation(String kind) {
        String account = account();
        ObjectNode record = evaluation(account, kind).orElse(null);
        if (record == null || !ON.contains(record.path("Status").asText())) {
            return empty();
        }
        record.put("Status", "STOPPING");
        store.putForAccount(account, ObservabilityAdminStore.GLOBAL, kind, ACCOUNT, record);
        later(() -> evaluation(account, kind).ifPresent(current -> {
            if ("STOPPING".equals(current.path("Status").asText())) {
                current.put("Status", "STOPPED");
                store.putForAccount(account, ObservabilityAdminStore.GLOBAL, kind, ACCOUNT, current);
            }
        }));
        return empty();
    }

    private ObjectNode evaluationStatus(String region, String kind) {
        ObjectNode out = empty();
        ObjectNode record = evaluation(account(), kind).orElse(null);
        if (record == null) {
            out.put("Status", "NOT_STARTED");
            return out;
        }
        String status = covers(record, region) ? record.path("Status").asText() : "NOT_STARTED";
        out.put("Status", status);
        if (record.hasNonNull("FailureReason")) {
            out.put("FailureReason", record.path("FailureReason").asText());
        }
        out.put("HomeRegion", record.path("HomeRegion").asText());
        ArrayNode statuses = out.putArray("RegionStatuses");
        for (JsonNode covered : record.path("Regions")) {
            statuses.addObject().put("Region", covered.asText()).put("Status", record.path("Status").asText());
        }
        return out;
    }

    public ObjectNode startTelemetryEvaluation(JsonNode in, String region) {
        return startEvaluation(in, region, ObservabilityAdminStore.EVALUATION);
    }

    public ObjectNode stopTelemetryEvaluation(JsonNode in, String region) {
        return stopEvaluation(ObservabilityAdminStore.EVALUATION);
    }

    public ObjectNode getTelemetryEvaluationStatus(JsonNode in, String region) {
        return evaluationStatus(region, ObservabilityAdminStore.EVALUATION);
    }

    public ObjectNode startTelemetryEvaluationForOrganization(JsonNode in, String region) {
        management();
        return startEvaluation(in, region, ObservabilityAdminStore.ORG_EVALUATION);
    }

    public ObjectNode stopTelemetryEvaluationForOrganization(JsonNode in, String region) {
        management();
        return stopEvaluation(ObservabilityAdminStore.ORG_EVALUATION);
    }

    public ObjectNode getTelemetryEvaluationStatusForOrganization(JsonNode in, String region) {
        management();
        return evaluationStatus(region, ObservabilityAdminStore.ORG_EVALUATION);
    }

    // ================================================================ organizations

    /** The caller must be its organization's management account, with trusted access for Observability Admin. */
    private void management() {
        String account = account();
        boolean manages;
        try {
            manages = organizations.get().isManagementAccount(account);
        } catch (RuntimeException e) {
            manages = false;
        }
        if (!manages) {
            throw ObservabilityAdminStore.denied("Only an organization's management account can manage "
                    + "organization telemetry.");
        }
        if (!trusted(account)) {
            throw ObservabilityAdminStore.denied("Trusted access for " + TRUSTED_SERVICE + " isn't enabled in the "
                    + "organization.");
        }
    }

    private boolean trusted(String account) {
        try {
            return organizations.get().listAWSServiceAccessForOrganization(account).stream()
                    .anyMatch(principal -> TRUSTED_SERVICE.equals(principal.servicePrincipal()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<String> members(String management) {
        List<String> accounts = new ArrayList<>();
        try {
            organizations.get().listAccounts(management).forEach(member -> accounts.add(member.getId()));
        } catch (RuntimeException e) {
            accounts.add(management);
        }
        return accounts;
    }

    /** The filter attributes of an organization member: its account, organization and organizational unit. */
    private Map<String, List<String>> memberAttributes(String management, String account) {
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put("AccountId", List.of(account));
        try {
            attributes.put("OrganizationId", List.of(organizations.get().describeOrganization(management).getId()));
            List<String> units = new ArrayList<>();
            organizations.get().listParents(management, account).forEach(parent -> units.add(parent.id()));
            attributes.put("OrganizationUnitId", units);
        } catch (RuntimeException e) {
            LOG.debugv("Organization attributes of {0} couldn't be read: {1}", account, e.getMessage());
        }
        return attributes;
    }

    // ================================================================ evaluation cycle

    /** Applies the account and organization rules of every running evaluation. */
    void evaluate() {
        try {
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                    : store.everywhere(ObservabilityAdminStore.EVALUATION)) {
                if (!"RUNNING".equals(entry.value().path("Status").asText())) {
                    continue;
                }
                for (ObjectNode rule : store.listForAccount(entry.accountId(), ObservabilityAdminStore.GLOBAL,
                        ObservabilityAdminStore.RULE)) {
                    for (String region : regions(rule, entry.value())) {
                        resources.apply(rule.path("TelemetryRule"), entry.accountId(), region);
                    }
                }
            }
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                    : store.everywhere(ObservabilityAdminStore.ORG_EVALUATION)) {
                if (!"RUNNING".equals(entry.value().path("Status").asText())) {
                    continue;
                }
                String management = entry.accountId();
                for (ObjectNode rule : store.listForAccount(management, ObservabilityAdminStore.GLOBAL,
                        ObservabilityAdminStore.ORG_RULE)) {
                    String scope = rule.path("TelemetryRule").path("Scope").asText("");
                    for (String member : members(management)) {
                        if (!ObservabilityAdminFilter.matches(scope, memberAttributes(management, member))) {
                            continue;
                        }
                        for (String region : regions(rule, entry.value())) {
                            resources.apply(rule.path("TelemetryRule"), member, region);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Telemetry evaluation failed: {0}", e.getMessage());
        }
    }

    /** The Regions a rule applies in that its evaluation covers. */
    private static List<String> regions(JsonNode rule, JsonNode evaluation) {
        List<String> regions = new ArrayList<>();
        for (JsonNode status : rule.path("RegionStatuses")) {
            String region = status.path("Region").asText();
            if (covers(evaluation, region)) {
                regions.add(region);
            }
        }
        return regions;
    }

    // ================================================================ resource telemetry

    private List<ObjectNode> telemetry(JsonNode in, List<String> accounts, String region) {
        List<String> types = new ArrayList<>();
        in.path("ResourceTypes").forEach(type -> types.add(type.asText()));
        String prefix = in.path("ResourceIdentifierPrefix").asText("");
        JsonNode states = in.path("TelemetryConfigurationState");
        JsonNode tags = in.path("ResourceTags");
        List<ObjectNode> items = new ArrayList<>();
        long now = ObservabilityAdminStore.now();
        for (String account : accounts) {
            for (TelemetryResources.Resource resource : resources.resources(account, region)) {
                if ((!types.isEmpty() && !types.contains(resource.type())) || !resource.id().startsWith(prefix)) {
                    continue;
                }
                boolean matches = true;
                for (Map.Entry<String, JsonNode> state : states.properties()) {
                    matches &= state.getValue().asText().equals(resource.states().get(state.getKey()));
                }
                for (Map.Entry<String, JsonNode> tag : tags.properties()) {
                    matches &= tag.getValue().asText().equals(resource.tags().get(tag.getKey()));
                }
                if (matches) {
                    items.add(resources.view(resource, account, now));
                }
            }
        }
        return items;
    }

    public ObjectNode listResourceTelemetry(JsonNode in, String region) {
        if (!running(account(), region, ObservabilityAdminStore.EVALUATION)) {
            throw ObservabilityAdminStore.validation("Telemetry evaluation isn't running in " + region
                    + "; start it with StartTelemetryEvaluation.");
        }
        ObjectNode out = empty();
        ObservabilityAdminStore.page(telemetry(in, List.of(account()), region), in, out, "TelemetryConfigurations",
                50);
        return out;
    }

    public ObjectNode listResourceTelemetryForOrganization(JsonNode in, String region) {
        management();
        if (!running(account(), region, ObservabilityAdminStore.ORG_EVALUATION)) {
            throw ObservabilityAdminStore.validation("Organization telemetry evaluation isn't running in " + region
                    + ".");
        }
        List<String> accounts = members(account());
        if (in.path("AccountIdentifiers").isArray() && !in.path("AccountIdentifiers").isEmpty()) {
            List<String> wanted = new ArrayList<>();
            in.path("AccountIdentifiers").forEach(id -> wanted.add(id.asText()));
            accounts.retainAll(wanted);
        }
        ObjectNode out = empty();
        ObservabilityAdminStore.page(telemetry(in, accounts, region), in, out, "TelemetryConfigurations", 50);
        return out;
    }

    // ================================================================ telemetry rules

    private static final Map<String, Set<String>> SOURCE_TYPES = Map.of(
            "VPC_FLOW_LOGS", Set.of("AWS::EC2::VPC"),
            "ROUTE53_RESOLVER_QUERY_LOGS", Set.of("AWS::Route53Resolver::ResolverEndpoint"),
            "EKS_AUDIT_LOGS", Set.of("AWS::EKS::Cluster"), "EKS_AUTHENTICATOR_LOGS", Set.of("AWS::EKS::Cluster"),
            "EKS_CONTROLLER_MANAGER_LOGS", Set.of("AWS::EKS::Cluster"), "EKS_SCHEDULER_LOGS",
            Set.of("AWS::EKS::Cluster"), "EKS_API_LOGS", Set.of("AWS::EKS::Cluster"));

    private static void checkRule(JsonNode rule) {
        String type = rule.path("ResourceType").asText("");
        if (type.isBlank()) {
            throw ObservabilityAdminStore.validation("Rule.ResourceType is required.");
        }
        String telemetry = rule.path("TelemetryType").asText();
        for (JsonNode source : rule.path("TelemetrySourceTypes")) {
            Set<String> allowed = SOURCE_TYPES.get(source.asText());
            if (allowed == null || !allowed.contains(type)) {
                throw ObservabilityAdminStore.validation("The telemetry source type " + source.asText()
                        + " doesn't apply to " + type + ".");
            }
            if (!"Logs".equals(telemetry)) {
                throw ObservabilityAdminStore.validation("Telemetry source types apply to Logs rules.");
            }
        }
        if ("Logs".equals(telemetry) && !rule.path("DestinationConfiguration").path("DestinationType").isTextual()
                && rule.path("TelemetrySourceTypes").size() > 0) {
            throw ObservabilityAdminStore.validation("A Logs rule needs DestinationConfiguration.DestinationType.");
        }
        for (String filter : List.of("Scope", "SelectionCriteria")) {
            String problem = ObservabilityAdminFilter.problem(rule.path(filter).asText(null));
            if (problem != null) {
                throw ObservabilityAdminStore.validation(filter + ": " + problem);
            }
        }
    }

    /** What makes two rules the same configuration, which AWS doesn't let two rules share. */
    private static String identity(JsonNode rule) {
        List<String> sources = new ArrayList<>();
        rule.path("TelemetrySourceTypes").forEach(source -> sources.add(source.asText()));
        sources.sort(null);
        return rule.path("ResourceType").asText() + "|" + rule.path("TelemetryType").asText() + "|" + sources + "|"
                + rule.path("Scope").asText("") + "|" + rule.path("SelectionCriteria").asText("");
    }

    private static String ruleName(String identifier) {
        return identifier.contains(":") && identifier.contains("/")
                ? identifier.substring(identifier.lastIndexOf('/') + 1) : identifier;
    }

    private ObjectNode rule(String kind, String identifier) {
        return store.getForAccount(account(), ObservabilityAdminStore.GLOBAL, kind, ruleName(identifier))
                .orElseThrow(() -> ObservabilityAdminStore.notFound("The telemetry rule " + identifier
                        + " doesn't exist."));
    }

    private ObjectNode createRule(JsonNode in, String region, String kind, String evaluationKind, String prefix) {
        String name = required(in, "RuleName");
        JsonNode rule = in.path("Rule");
        String account = account();
        if (!running(account, region, evaluationKind)) {
            throw ObservabilityAdminStore.validation("Telemetry evaluation isn't running; start it before creating "
                    + "telemetry rules.");
        }
        checkRule(rule);
        if (store.getForAccount(account, ObservabilityAdminStore.GLOBAL, kind, name).isPresent()) {
            throw ObservabilityAdminStore.conflict("A telemetry rule named " + name + " already exists.");
        }
        List<ObjectNode> existing = store.listForAccount(account, ObservabilityAdminStore.GLOBAL, kind);
        for (ObjectNode other : existing) {
            if (identity(other.path("TelemetryRule")).equals(identity(rule))) {
                throw ObservabilityAdminStore.conflict("The telemetry rule " + other.path("RuleName").asText()
                        + " already has this configuration.");
            }
        }
        if (existing.size() >= MAX_RULES) {
            throw ObservabilityAdminStore.quota("The account has the maximum of " + MAX_RULES + " telemetry rules.");
        }
        ObjectNode record = store.record();
        String ruleArn = arn(region, account, prefix + "/" + name);
        long now = ObservabilityAdminStore.now();
        record.put("RuleName", name);
        record.put("RuleArn", ruleArn);
        record.put("CreatedTimeStamp", now);
        record.put("LastUpdateTimeStamp", now);
        record.set("TelemetryRule", rule.deepCopy());
        record.put("HomeRegion", region);
        record.put("IsReplicated", false);
        regionStatuses(record, rule, region, account, prefix + "/" + name);
        ObservabilityAdminStore.addTags(record, in.path("Tags"));
        store.putForAccount(account, ObservabilityAdminStore.GLOBAL, kind, name, record);
        later(this::evaluate);
        ObjectNode out = empty();
        out.put("RuleArn", ruleArn);
        return out;
    }

    private void regionStatuses(ObjectNode record, JsonNode rule, String home, String account, String resource) {
        Set<String> regions = new LinkedHashSet<>();
        regions.add(home);
        rule.path("Regions").forEach(r -> regions.add(r.asText()));
        ArrayNode statuses = record.putArray("RegionStatuses");
        regions.forEach(r -> statuses.addObject().put("Region", r).put("Status", "ACTIVE")
                .put("RuleArn", arn(r, account, resource)));
    }

    private ObjectNode updateRule(JsonNode in, String region, String kind, String prefix) {
        ObjectNode record = rule(kind, required(in, "RuleIdentifier"));
        JsonNode rule = in.path("Rule");
        checkRule(rule);
        String account = account();
        for (ObjectNode other : store.listForAccount(account, ObservabilityAdminStore.GLOBAL, kind)) {
            if (!other.path("RuleName").asText().equals(record.path("RuleName").asText())
                    && identity(other.path("TelemetryRule")).equals(identity(rule))) {
                throw ObservabilityAdminStore.conflict("The telemetry rule " + other.path("RuleName").asText()
                        + " already has this configuration.");
            }
        }
        record.set("TelemetryRule", rule.deepCopy());
        record.put("LastUpdateTimeStamp", ObservabilityAdminStore.now());
        regionStatuses(record, rule, record.path("HomeRegion").asText(), account, prefix + "/"
                + record.path("RuleName").asText());
        store.putForAccount(account, ObservabilityAdminStore.GLOBAL, kind, record.path("RuleName").asText(), record);
        later(this::evaluate);
        ObjectNode out = empty();
        out.put("RuleArn", record.path("RuleArn").asText());
        return out;
    }

    private ObjectNode deleteRule(JsonNode in, String kind) {
        ObjectNode record = rule(kind, required(in, "RuleIdentifier"));
        store.deleteForAccount(account(), ObservabilityAdminStore.GLOBAL, kind, record.path("RuleName").asText());
        return empty();
    }

    private ObjectNode getRule(JsonNode in, String kind) {
        return ObservabilityAdminStore.view(rule(kind, required(in, "RuleIdentifier")));
    }

    private ObjectNode listRules(JsonNode in, String region, String kind, List<String> sourceFilters) {
        String prefix = in.path("RuleNamePrefix").asText("");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.listForAccount(account(), ObservabilityAdminStore.GLOBAL, kind)) {
            boolean inRegion = false;
            for (JsonNode status : record.path("RegionStatuses")) {
                inRegion |= region.equals(status.path("Region").asText());
            }
            String scope = record.path("TelemetryRule").path("Scope").asText("");
            if (!inRegion || !record.path("RuleName").asText().startsWith(prefix)
                    || (!sourceFilters.isEmpty() && !scope.isBlank()
                    && sourceFilters.stream().noneMatch(scope::contains))) {
                continue;
            }
            ObjectNode summary = empty();
            summary.put("RuleName", record.path("RuleName").asText());
            summary.put("RuleArn", record.path("RuleArn").asText());
            summary.put("CreatedTimeStamp", record.path("CreatedTimeStamp").asLong());
            summary.put("LastUpdateTimeStamp", record.path("LastUpdateTimeStamp").asLong());
            JsonNode rule = record.path("TelemetryRule");
            for (String field : List.of("ResourceType", "TelemetryType", "TelemetrySourceTypes")) {
                if (rule.has(field)) {
                    summary.set(field, rule.get(field).deepCopy());
                }
            }
            items.add(summary);
        }
        ObjectNode out = empty();
        ObservabilityAdminStore.page(items, in, out, "TelemetryRuleSummaries", 50);
        return out;
    }

    public ObjectNode createTelemetryRule(JsonNode in, String region) {
        return createRule(in, region, ObservabilityAdminStore.RULE, ObservabilityAdminStore.EVALUATION,
                "telemetry-rule");
    }

    public ObjectNode updateTelemetryRule(JsonNode in, String region) {
        return updateRule(in, region, ObservabilityAdminStore.RULE, "telemetry-rule");
    }

    public ObjectNode deleteTelemetryRule(JsonNode in, String region) {
        return deleteRule(in, ObservabilityAdminStore.RULE);
    }

    public ObjectNode getTelemetryRule(JsonNode in, String region) {
        return getRule(in, ObservabilityAdminStore.RULE);
    }

    public ObjectNode listTelemetryRules(JsonNode in, String region) {
        return listRules(in, region, ObservabilityAdminStore.RULE, List.of());
    }

    public ObjectNode createTelemetryRuleForOrganization(JsonNode in, String region) {
        management();
        return createRule(in, region, ObservabilityAdminStore.ORG_RULE, ObservabilityAdminStore.ORG_EVALUATION,
                "organization-telemetry-rule");
    }

    public ObjectNode updateTelemetryRuleForOrganization(JsonNode in, String region) {
        management();
        return updateRule(in, region, ObservabilityAdminStore.ORG_RULE, "organization-telemetry-rule");
    }

    public ObjectNode deleteTelemetryRuleForOrganization(JsonNode in, String region) {
        management();
        return deleteRule(in, ObservabilityAdminStore.ORG_RULE);
    }

    public ObjectNode getTelemetryRuleForOrganization(JsonNode in, String region) {
        management();
        return getRule(in, ObservabilityAdminStore.ORG_RULE);
    }

    public ObjectNode listTelemetryRulesForOrganization(JsonNode in, String region) {
        management();
        List<String> filters = new ArrayList<>();
        in.path("SourceAccountIds").forEach(id -> filters.add(id.asText()));
        in.path("SourceOrganizationUnitIds").forEach(id -> filters.add(id.asText()));
        return listRules(in, region, ObservabilityAdminStore.ORG_RULE, filters);
    }

    // ================================================================ enrichment

    public ObjectNode startTelemetryEnrichment(JsonNode in, String region) {
        String account = account();
        if (!running(account, region, ObservabilityAdminStore.EVALUATION)) {
            throw ObservabilityAdminStore.conflict("Telemetry evaluation must be running to start enrichment.");
        }
        ObjectNode record = store.get(region, ObservabilityAdminStore.ENRICHMENT, ACCOUNT).orElseGet(store::record);
        if (!"Running".equals(record.path("Status").asText())) {
            record.put("Status", "Running");
            record.put("AwsResourceExplorerManagedViewArn", AwsArnUtils.Arn.of("resource-explorer-2", region, account,
                    "managed-view/" + MANAGED_VIEW + "/" + UUID.randomUUID()).toString());
            record.put("LastUpdatedAt", ObservabilityAdminStore.now());
            store.put(region, ObservabilityAdminStore.ENRICHMENT, ACCOUNT, record);
        }
        return enrichment(record, region);
    }

    public ObjectNode stopTelemetryEnrichment(JsonNode in, String region) {
        ObjectNode record = store.get(region, ObservabilityAdminStore.ENRICHMENT, ACCOUNT).orElseThrow(() ->
                ObservabilityAdminStore.conflict("Telemetry enrichment isn't running."));
        if (!"Running".equals(record.path("Status").asText())) {
            throw ObservabilityAdminStore.conflict("Telemetry enrichment isn't running.");
        }
        record.put("Status", "Stopped");
        record.remove("AwsResourceExplorerManagedViewArn");
        store.put(region, ObservabilityAdminStore.ENRICHMENT, ACCOUNT, record);
        ObjectNode out = empty();
        out.put("Status", "Stopped");
        return out;
    }

    public ObjectNode getTelemetryEnrichmentStatus(JsonNode in, String region) {
        ObjectNode record = store.get(region, ObservabilityAdminStore.ENRICHMENT, ACCOUNT).orElseThrow(() ->
                ObservabilityAdminStore.notFound("Telemetry enrichment was never started in " + region + "."));
        return enrichment(record, region);
    }

    private ObjectNode enrichment(ObjectNode record, String region) {
        ObjectNode out = empty();
        String status = record.path("Status").asText();
        if ("Running".equals(status) && !running(account(), region, ObservabilityAdminStore.EVALUATION)) {
            status = "Impaired";
        }
        out.put("Status", status);
        if (record.hasNonNull("AwsResourceExplorerManagedViewArn")) {
            out.put("AwsResourceExplorerManagedViewArn", record.path("AwsResourceExplorerManagedViewArn").asText());
        }
        return out;
    }

    @Override
    public List<ObjectNode> managedViews(String account, String region) {
        List<ObjectNode> views = new ArrayList<>();
        store.getForAccount(account, region, ObservabilityAdminStore.ENRICHMENT, ACCOUNT)
                .filter(record -> record.hasNonNull("AwsResourceExplorerManagedViewArn")).ifPresent(record -> {
                    ObjectNode view = empty();
                    view.put("ManagedViewArn", record.path("AwsResourceExplorerManagedViewArn").asText());
                    view.put("ManagedViewName", MANAGED_VIEW);
                    view.put("TrustedService", TRUSTED_SERVICE);
                    view.put("LastUpdatedAt", record.path("LastUpdatedAt").asLong() / 1000.0);
                    view.put("Owner", account);
                    view.put("Scope", regionResolver.buildGlobalArn("iam", account, "root"));
                    view.putArray("IncludedProperties").addObject().put("Name", "tags");
                    view.putObject("Filters").put("FilterString", "");
                    view.put("Version", "1");
                    views.add(view);
                });
        return views;
    }

    // ================================================================ roles

    /** Why a role can't be used by a service principal, or null when it can. */
    private String roleProblem(String arn, String principal, String region) {
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            return "The role " + arn + " isn't a role ARN.";
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        try {
            IamRole role = RequestScopes.callAs(AwsArnUtils.accountOrDefault(arn, account()), region, () ->
                    iam.get().getRole(name));
            String trust = role.getAssumeRolePolicyDocument() == null ? "" : URLDecoder.decode(
                    role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
            return trust.contains(principal) ? null : "The role " + arn + " doesn't trust " + principal + ".";
        } catch (AwsException e) {
            return "The role " + arn + " can't be used: " + e.getMessage();
        }
    }

    // ================================================================ telemetry pipelines

    private static final String PIPELINE_PRINCIPAL = ServicePrincipals.of("telemetry-pipelines.observabilityadmin");

    private boolean logGroupExists(String name, String region) {
        try {
            return logs.get().logGroupExists(name, region);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TelemetryPipelineEngine pipelineConfig(String body, String region, List<TelemetryPipelineEngine.Problem>
            problems) {
        TelemetryPipelineEngine engine = TelemetryPipelineEngine.parse(body, mapper, group -> logGroupExists(group,
                region), problems);
        if (engine != null) {
            for (String role : engine.roles()) {
                String problem = roleProblem(role, PIPELINE_PRINCIPAL, region);
                if (problem != null) {
                    problems.add(new TelemetryPipelineEngine.Problem(problem, "INVALID_ROLE",
                            Map.of("field", "pipeline.source.aws.sts_role_arn")));
                }
            }
        }
        return problems.isEmpty() ? engine : null;
    }

    private TelemetryPipelineEngine requirePipelineConfig(JsonNode configuration, String region) {
        List<TelemetryPipelineEngine.Problem> problems = new ArrayList<>();
        TelemetryPipelineEngine engine = pipelineConfig(required(configuration, "Body"), region, problems);
        if (engine == null) {
            Map<String, Object> details = new HashMap<>();
            details.put("Errors", errors(problems));
            throw new AwsException("ValidationException", problems.get(0).message(), 400, details);
        }
        return engine;
    }

    private ArrayNode errors(List<TelemetryPipelineEngine.Problem> problems) {
        ArrayNode errors = mapper.createArrayNode();
        for (TelemetryPipelineEngine.Problem problem : problems) {
            ObjectNode error = errors.addObject();
            error.put("Message", problem.message());
            error.put("Reason", problem.reason());
            ObjectNode fields = error.putObject("FieldMap");
            problem.fields().forEach(fields::put);
        }
        return errors;
    }

    private ObjectNode pipeline(String region, String identifier) {
        String name = identifier.contains(":") ? identifier.substring(identifier.lastIndexOf('/') + 1) : identifier;
        return store.get(region, ObservabilityAdminStore.PIPELINE, name).orElseThrow(() ->
                ObservabilityAdminStore.notFound("The telemetry pipeline " + identifier + " doesn't exist."));
    }

    public ObjectNode createTelemetryPipeline(JsonNode in, String region) {
        String name = required(in, "Name");
        if (store.get(region, ObservabilityAdminStore.PIPELINE, name).isPresent()) {
            throw ObservabilityAdminStore.conflict("A telemetry pipeline named " + name + " already exists.");
        }
        TelemetryPipelineEngine engine = requirePipelineConfig(in.path("Configuration"), region);
        String account = account();
        ObjectNode record = store.record();
        long now = ObservabilityAdminStore.now();
        record.put("CreatedTimeStamp", now);
        record.put("LastUpdateTimeStamp", now);
        record.put("Arn", arn(region, account, "telemetry-pipeline/" + name));
        record.put("Name", name);
        record.set("Configuration", in.path("Configuration").deepCopy());
        record.put("Status", "CREATING");
        record.putObject("StatusReason").put("Description", "The pipeline is being created.");
        ObservabilityAdminStore.meta(record).set("summary", engine.summary());
        ObservabilityAdminStore.addTags(record, in.path("Tags"));
        store.put(region, ObservabilityAdminStore.PIPELINE, name, record);
        settle(account, region, name, "CREATING");
        ObjectNode out = empty();
        out.put("Arn", record.path("Arn").asText());
        return out;
    }

    private void settle(String account, String region, String name, String from) {
        later(() -> store.getForAccount(account, region, ObservabilityAdminStore.PIPELINE, name).ifPresent(current -> {
            if (from.equals(current.path("Status").asText())) {
                current.put("Status", "ACTIVE");
                current.putObject("StatusReason").put("Description", "The pipeline is active.");
                store.putForAccount(account, region, ObservabilityAdminStore.PIPELINE, name, current);
            }
        }));
    }

    public ObjectNode updateTelemetryPipeline(JsonNode in, String region) {
        ObjectNode record = pipeline(region, required(in, "PipelineIdentifier"));
        String status = record.path("Status").asText();
        if (Set.of("CREATING", "UPDATING", "DELETING").contains(status)) {
            throw ObservabilityAdminStore.conflict("The telemetry pipeline is " + status + ".");
        }
        TelemetryPipelineEngine engine = requirePipelineConfig(in.path("Configuration"), region);
        record.set("Configuration", in.path("Configuration").deepCopy());
        record.put("Status", "UPDATING");
        record.put("LastUpdateTimeStamp", ObservabilityAdminStore.now());
        ObservabilityAdminStore.meta(record).set("summary", engine.summary());
        String name = record.path("Name").asText();
        store.put(region, ObservabilityAdminStore.PIPELINE, name, record);
        settle(account(), region, name, "UPDATING");
        return empty();
    }

    public ObjectNode deleteTelemetryPipeline(JsonNode in, String region) {
        ObjectNode record = pipeline(region, required(in, "PipelineIdentifier"));
        String status = record.path("Status").asText();
        if (Set.of("CREATING", "UPDATING").contains(status)) {
            throw ObservabilityAdminStore.conflict("The telemetry pipeline is " + status + ".");
        }
        record.put("Status", "DELETING");
        String name = record.path("Name").asText();
        store.put(region, ObservabilityAdminStore.PIPELINE, name, record);
        String account = account();
        later(() -> store.deleteForAccount(account, region, ObservabilityAdminStore.PIPELINE, name));
        return empty();
    }

    public ObjectNode getTelemetryPipeline(JsonNode in, String region) {
        ObjectNode record = pipeline(region, required(in, "PipelineIdentifier"));
        ObjectNode view = ObservabilityAdminStore.view(record);
        view.set("Tags", ObservabilityAdminStore.tags(record).deepCopy());
        ObjectNode out = empty();
        out.set("Pipeline", view);
        return out;
    }

    public ObjectNode listTelemetryPipelines(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, ObservabilityAdminStore.PIPELINE)) {
            ObjectNode summary = empty();
            for (String field : List.of("CreatedTimeStamp", "LastUpdateTimeStamp", "Arn", "Name", "Status")) {
                summary.set(field, record.get(field));
            }
            summary.set("Tags", ObservabilityAdminStore.tags(record).deepCopy());
            summary.set("ConfigurationSummary", ObservabilityAdminStore.meta(record).path("summary").deepCopy());
            items.add(summary);
        }
        ObjectNode out = empty();
        ObservabilityAdminStore.page(items, in, out, "PipelineSummaries", 50);
        return out;
    }

    public ObjectNode validateTelemetryPipelineConfiguration(JsonNode in, String region) {
        List<TelemetryPipelineEngine.Problem> problems = new ArrayList<>();
        pipelineConfig(required(in.path("Configuration"), "Body"), region, problems);
        ObjectNode out = empty();
        out.set("Errors", errors(problems));
        return out;
    }

    public ObjectNode testTelemetryPipeline(JsonNode in, String region) {
        TelemetryPipelineEngine engine = requirePipelineConfig(in.path("Configuration"), region);
        boolean metrics = "METRIC".equals(in.path("SignalType").asText("LOG"));
        if (metrics != engine.metrics()) {
            throw ObservabilityAdminStore.validation("SignalType " + (metrics ? "METRIC" : "LOG")
                    + " doesn't match the pipeline's source.");
        }
        List<TelemetryPipelineEngine.Record> records = new ArrayList<>();
        in.path("Records").forEach(record -> records.add(new TelemetryPipelineEngine.Record(
                record.path("Data").asText(""), record.path("Type").asText("STRING"))));
        ObjectNode out = empty();
        ArrayNode results = out.putArray("Results");
        for (TelemetryPipelineEngine.Output output : engine.run(records)) {
            ObjectNode result = results.addObject();
            if (output.record() != null) {
                result.putObject("Record").put("Data", output.record().data()).put("Type", output.record().type());
            }
            if (output.error() != null) {
                result.putObject("Error").put("Message", output.error());
            }
        }
        return out;
    }

    /** Reads the objects announced on each active S3-source pipeline's queue into its CloudWatch Logs sink. */
    void pollPipelines() {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : store.everywhere(ObservabilityAdminStore.PIPELINE)) {
            ObjectNode record = entry.value();
            if (!"ACTIVE".equals(record.path("Status").asText())) {
                continue;
            }
            String region = ObservabilityAdminStore.meta(record).path("region").asText();
            try {
                List<TelemetryPipelineEngine.Problem> problems = new ArrayList<>();
                TelemetryPipelineEngine engine = TelemetryPipelineEngine.parse(record.path("Configuration")
                        .path("Body").asText(), mapper, group -> true, problems);
                if (engine == null || !"s3".equals(engine.source())) {
                    continue;
                }
                String queue = engine.sourceOptions().path("sqs").path("queue_url").asText("");
                if (queue.isBlank()) {
                    continue;
                }
                RequestScopes.runAs(entry.accountId(), region, () -> drain(engine, queue, entry.accountId(), region,
                        record.path("Name").asText()));
            } catch (RuntimeException e) {
                LOG.debugv("Telemetry pipeline {0} couldn't poll: {1}", record.path("Name").asText(), e.getMessage());
            }
        }
    }

    private void drain(TelemetryPipelineEngine engine, String queue, String account, String region, String name) {
        List<Message> messages = sqs.get().receiveMessage(queue, 10, 60, 0, region);
        for (Message message : messages) {
            try {
                JsonNode notification = mapper.readTree(message.getBody());
                for (JsonNode s3Record : notification.path("Records")) {
                    String bucket = s3Record.path("s3").path("bucket").path("name").asText();
                    String key = URLDecoder.decode(s3Record.path("s3").path("object").path("key").asText(),
                            StandardCharsets.UTF_8);
                    if (bucket.isEmpty() || key.isEmpty()) {
                        continue;
                    }
                    byte[] data = s3.get().getObject(bucket, key).getData();
                    deliver(engine, records(engine, new String(data == null ? new byte[0] : data,
                            StandardCharsets.UTF_8)), account, region, name);
                }
                sqs.get().deleteMessage(queue, message.getReceiptHandle(), region);
            } catch (Exception e) {
                LOG.debugv("Telemetry pipeline {0} couldn't process a message: {1}", name, e.getMessage());
            }
        }
    }

    private List<TelemetryPipelineEngine.Record> records(TelemetryPipelineEngine engine, String text) {
        JsonNode codec = engine.sourceOptions().path("codec");
        List<TelemetryPipelineEngine.Record> records = new ArrayList<>();
        if (codec.has("json")) {
            try {
                JsonNode parsed = mapper.readTree(text);
                (parsed.isArray() ? parsed : mapper.createArrayNode().add(parsed)).forEach(item ->
                        records.add(new TelemetryPipelineEngine.Record(item.toString(), "JSON")));
            } catch (Exception e) {
                LOG.debugv("An S3 object isn't JSON: {0}", e.getMessage());
            }
            return records;
        }
        String type = codec.has("ndjson") ? "JSON" : "STRING";
        for (String line : text.split("\\r?\\n")) {
            if (!line.isBlank()) {
                records.add(new TelemetryPipelineEngine.Record(line, type));
            }
        }
        return records;
    }

    private void deliver(TelemetryPipelineEngine engine, List<TelemetryPipelineEngine.Record> records, String account,
                         String region, String name) {
        Map<String, List<Map<String, Object>>> byGroup = new HashMap<>();
        long now = System.currentTimeMillis();
        for (TelemetryPipelineEngine.Record record : records) {
            ObjectNode event;
            try {
                event = engine.runOne(record);
            } catch (RuntimeException e) {
                LOG.debugv("Telemetry pipeline {0} couldn't process a record: {1}", name, e.getMessage());
                continue;
            }
            if (event == null) {
                continue;
            }
            JsonNode sink = engine.route(event);
            if (sink == null) {
                continue;
            }
            Map<String, Object> logEvent = new HashMap<>();
            logEvent.put("timestamp", now);
            logEvent.put("message", engine.output(event).data());
            byGroup.computeIfAbsent(sink.path("log_group").asText(), group -> new ArrayList<>()).add(logEvent);
        }
        byGroup.forEach((group, events) -> {
            ensureStream(account, group, name, region);
            logs.get().putLogEventsForAccount(account, group, name, events, region);
        });
    }

    private void ensureStream(String account, String group, String stream, String region) {
        try {
            logs.get().createLogGroupForAccount(account, group, null, Map.of(), region);
        } catch (AwsException e) {
            LOG.tracev("Log group {0} exists: {1}", group, e.getMessage());
        }
        try {
            logs.get().createLogStreamForAccount(account, group, stream, region);
        } catch (AwsException e) {
            LOG.tracev("Log stream {0} exists: {1}", stream, e.getMessage());
        }
    }

    // ================================================================ centralization

    private ObjectNode centralization(String identifier) {
        String name = ruleName(identifier);
        return store.getForAccount(account(), ObservabilityAdminStore.GLOBAL, ObservabilityAdminStore.CENTRALIZATION,
                name).orElseThrow(() -> ObservabilityAdminStore.notFound("The centralization rule " + identifier
                + " doesn't exist."));
    }

    private void checkCentralization(JsonNode rule) {
        JsonNode source = rule.path("Source");
        if (!source.path("Regions").isArray() || source.path("Regions").isEmpty()) {
            throw ObservabilityAdminStore.validation("Rule.Source.Regions is required.");
        }
        for (String filter : new String[] {source.path("Scope").asText(null), source.path("SourceLogsConfiguration")
                .path("LogGroupSelectionCriteria").asText(null)}) {
            String problem = ObservabilityAdminFilter.problem(filter);
            if (problem != null) {
                throw ObservabilityAdminStore.validation(problem);
            }
        }
        required(rule.path("Destination"), "Region");
    }

    /** A centralization rule's health: Healthy, or Unhealthy with why. */
    private void health(ObjectNode record, String management) {
        String destination = record.path("CentralizationRule").path("Destination").path("Account").asText(management);
        if (!trusted(management)) {
            record.put("RuleHealth", "Unhealthy");
            record.put("FailureReason", "TRUSTED_ACCESS_NOT_ENABLED");
        } else if (!members(management).contains(destination)) {
            record.put("RuleHealth", "Unhealthy");
            record.put("FailureReason", "DESTINATION_ACCOUNT_NOT_IN_ORGANIZATION");
        } else {
            record.put("RuleHealth", "Healthy");
            record.remove("FailureReason");
        }
    }

    public ObjectNode createCentralizationRuleForOrganization(JsonNode in, String region) {
        management();
        String name = required(in, "RuleName");
        JsonNode rule = in.path("Rule");
        checkCentralization(rule);
        String account = account();
        if (store.getForAccount(account, ObservabilityAdminStore.GLOBAL, ObservabilityAdminStore.CENTRALIZATION,
                name).isPresent()) {
            throw ObservabilityAdminStore.conflict("A centralization rule named " + name + " already exists.");
        }
        ObjectNode record = store.record();
        long now = ObservabilityAdminStore.now();
        record.put("RuleName", name);
        record.put("RuleArn", arn(region, account, "organization-centralization-rule/" + name));
        record.put("CreatorAccountId", account);
        record.put("CreatedTimeStamp", now);
        record.put("CreatedRegion", region);
        record.put("LastUpdateTimeStamp", now);
        record.put("RuleHealth", "Provisioning");
        record.set("CentralizationRule", rule.deepCopy());
        ObservabilityAdminStore.addTags(record, in.path("Tags"));
        store.putForAccount(account, ObservabilityAdminStore.GLOBAL, ObservabilityAdminStore.CENTRALIZATION, name,
                record);
        later(() -> store.getForAccount(account, ObservabilityAdminStore.GLOBAL,
                ObservabilityAdminStore.CENTRALIZATION, name).ifPresent(current -> {
                    health(current, account);
                    store.putForAccount(account, ObservabilityAdminStore.GLOBAL,
                            ObservabilityAdminStore.CENTRALIZATION, name, current);
                }));
        ObjectNode out = empty();
        out.put("RuleArn", record.path("RuleArn").asText());
        return out;
    }

    public ObjectNode updateCentralizationRuleForOrganization(JsonNode in, String region) {
        management();
        ObjectNode record = centralization(required(in, "RuleIdentifier"));
        JsonNode rule = in.path("Rule");
        checkCentralization(rule);
        record.set("CentralizationRule", rule.deepCopy());
        record.put("LastUpdateTimeStamp", ObservabilityAdminStore.now());
        health(record, account());
        store.putForAccount(account(), ObservabilityAdminStore.GLOBAL, ObservabilityAdminStore.CENTRALIZATION,
                record.path("RuleName").asText(), record);
        ObjectNode out = empty();
        out.put("RuleArn", record.path("RuleArn").asText());
        return out;
    }

    public ObjectNode deleteCentralizationRuleForOrganization(JsonNode in, String region) {
        management();
        ObjectNode record = centralization(required(in, "RuleIdentifier"));
        store.deleteForAccount(account(), ObservabilityAdminStore.GLOBAL, ObservabilityAdminStore.CENTRALIZATION,
                record.path("RuleName").asText());
        return empty();
    }

    public ObjectNode getCentralizationRuleForOrganization(JsonNode in, String region) {
        management();
        return ObservabilityAdminStore.view(centralization(required(in, "RuleIdentifier")));
    }

    public ObjectNode listCentralizationRulesForOrganization(JsonNode in, String region) {
        management();
        String prefix = in.path("RuleNamePrefix").asText("");
        boolean all = in.path("AllRegions").asBoolean(false);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.listForAccount(account(), ObservabilityAdminStore.GLOBAL,
                ObservabilityAdminStore.CENTRALIZATION)) {
            if (!record.path("RuleName").asText().startsWith(prefix)
                    || (!all && !region.equals(record.path("CreatedRegion").asText()))) {
                continue;
            }
            ObjectNode summary = ObservabilityAdminStore.view(record);
            summary.remove("CentralizationRule");
            JsonNode destination = record.path("CentralizationRule").path("Destination");
            summary.put("DestinationAccountId", destination.path("Account").asText(account()));
            summary.put("DestinationRegion", destination.path("Region").asText());
            items.add(summary);
        }
        ObjectNode out = empty();
        ObservabilityAdminStore.page(items, in, out, "CentralizationRuleSummaries", 50);
        return out;
    }

    /** Copies ingested log events that a healthy centralization rule selects into its destination. */
    void onLogEvents(@Observes LogEventsIngested event) {
        if (COPYING.get() || event.events() == null || event.events().isEmpty()) {
            return;
        }
        String source = event.accountId() != null ? event.accountId() : regionResolver.getAccountId();
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> rules;
        try {
            rules = store.everywhere(ObservabilityAdminStore.CENTRALIZATION);
        } catch (RuntimeException e) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : rules) {
            ObjectNode record = entry.value();
            if (!"Healthy".equals(record.path("RuleHealth").asText())) {
                continue;
            }
            try {
                copy(entry.accountId(), record.path("CentralizationRule"), source, event);
            } catch (RuntimeException e) {
                LOG.debugv("Centralization rule {0} couldn't copy: {1}", record.path("RuleName").asText(),
                        e.getMessage());
            }
        }
    }

    private void copy(String management, JsonNode rule, String source, LogEventsIngested event) {
        JsonNode from = rule.path("Source");
        boolean inRegion = false;
        for (JsonNode region : from.path("Regions")) {
            inRegion |= event.region().equals(region.asText());
        }
        if (!inRegion || !members(management).contains(source)
                || !ObservabilityAdminFilter.matches(from.path("Scope").asText(""),
                memberAttributes(management, source))
                || !ObservabilityAdminFilter.matches(from.path("SourceLogsConfiguration")
                .path("LogGroupSelectionCriteria").asText(""), Map.of("LogGroupName",
                List.of(event.logGroupName())))) {
            return;
        }
        JsonNode to = rule.path("Destination");
        String account = to.path("Account").asText(management);
        String group = to.path("DestinationLogsConfiguration").path("LogGroupNameConfiguration")
                .path("LogGroupNamePattern").asText("${source.logGroup}")
                .replace("${source.accountId}", source).replace("${source.region}", event.region())
                .replace("${source.logGroup}", event.logGroupName());
        List<String> regions = new ArrayList<>();
        regions.add(to.path("Region").asText());
        String backup = to.path("DestinationLogsConfiguration").path("BackupConfiguration").path("Region").asText("");
        if (!backup.isBlank()) {
            regions.add(backup);
        }
        String stream = source + "_" + event.region() + "_" + event.logStreamName();
        List<Map<String, Object>> events = new ArrayList<>();
        for (LogEvent logEvent : event.events()) {
            Map<String, Object> copied = new HashMap<>();
            copied.put("timestamp", logEvent.getTimestamp());
            copied.put("message", logEvent.getMessage());
            events.add(copied);
        }
        for (String region : regions) {
            if (account.equals(source) && region.equals(event.region()) && group.equals(event.logGroupName())) {
                continue;
            }
            COPYING.set(true);
            try {
                RequestScopes.runAs(account, region, () -> {
                    ensureStream(account, group, stream, region);
                    logs.get().putLogEventsForAccount(account, group, stream, events, region);
                });
            } finally {
                COPYING.set(false);
            }
        }
    }

    // ================================================================ integrations

    public ObjectNode createS3TableIntegration(JsonNode in, String region) {
        String role = required(in, "RoleArn");
        String problem = roleProblem(role, TRUSTED_SERVICE, region);
        if (problem != null) {
            throw ObservabilityAdminStore.validation(problem);
        }
        JsonNode encryption = in.path("Encryption");
        String algorithm = required(encryption, "SseAlgorithm");
        if ("aws:kms".equals(algorithm) && encryption.path("KmsKeyArn").asText("").isBlank()) {
            throw ObservabilityAdminStore.validation("Encryption.KmsKeyArn is required with aws:kms.");
        }
        String account = account();
        for (ObjectNode existing : store.list(region, ObservabilityAdminStore.S3_TABLES)) {
            if (!"DELETING".equals(existing.path("Status").asText())) {
                throw ObservabilityAdminStore.quota("The account already has an S3 Tables integration in " + region
                        + ".");
            }
        }
        String id = ObservabilityAdminStore.random(16, LOWER);
        String bucketName = "cloudwatch-" + id;
        Map<String, Object> bucketEncryption = new HashMap<>();
        bucketEncryption.put("sseAlgorithm", algorithm);
        if (encryption.hasNonNull("KmsKeyArn")) {
            bucketEncryption.put("kmsKeyArn", encryption.path("KmsKeyArn").asText());
        }
        TableBucket bucket = RequestScopes.callAs(account, region, () -> s3tables.get().createTableBucket(bucketName,
                bucketEncryption, null, Map.of(), region));
        ObjectNode record = store.record();
        record.put("Arn", arn(region, account, "s3tableintegration/" + id));
        record.put("RoleArn", role);
        record.put("Status", "ACTIVE");
        record.set("Encryption", encryption.deepCopy());
        record.put("DestinationTableBucketArn", bucket.getArn());
        record.put("CreatedTimeStamp", ObservabilityAdminStore.now());
        ObservabilityAdminStore.addTags(record, in.path("Tags"));
        store.put(region, ObservabilityAdminStore.S3_TABLES, id, record);
        ObjectNode out = empty();
        out.put("Arn", record.path("Arn").asText());
        return out;
    }

    private ObjectNode integration(String kind, String arn, String region) {
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        return store.get(region, kind, id).orElseThrow(() ->
                ObservabilityAdminStore.notFound("The integration " + arn + " doesn't exist."));
    }

    public ObjectNode getS3TableIntegration(JsonNode in, String region) {
        return ObservabilityAdminStore.view(integration(ObservabilityAdminStore.S3_TABLES, required(in, "Arn"),
                region));
    }

    public ObjectNode listS3TableIntegrations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, ObservabilityAdminStore.S3_TABLES).forEach(record -> {
            ObjectNode summary = empty();
            summary.put("Arn", record.path("Arn").asText());
            summary.put("Status", record.path("Status").asText());
            items.add(summary);
        });
        ObjectNode out = empty();
        ObservabilityAdminStore.page(items, in, out, "IntegrationSummaries", 50);
        return out;
    }

    public ObjectNode deleteS3TableIntegration(JsonNode in, String region) {
        String arn = required(in, "Arn");
        ObjectNode record = integration(ObservabilityAdminStore.S3_TABLES, arn, region);
        if ("DELETING".equals(record.path("Status").asText())) {
            throw ObservabilityAdminStore.invalidState("The integration " + arn + " is already being deleted.");
        }
        record.put("Status", "DELETING");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        store.put(region, ObservabilityAdminStore.S3_TABLES, id, record);
        String account = account();
        later(() -> {
            try {
                RequestScopes.runAs(account, region, () -> s3tables.get().deleteTableBucket(record
                        .path("DestinationTableBucketArn").asText(), region));
            } catch (RuntimeException e) {
                LOG.debugv("The integration's table bucket stays: {0}", e.getMessage());
            }
            store.deleteForAccount(account, region, ObservabilityAdminStore.S3_TABLES, id);
        });
        return empty();
    }

    private double seconds() {
        return ObservabilityAdminStore.now() / 1000.0;
    }

    public ObjectNode createDatasetIntegration(JsonNode in, String region) {
        String role = required(in, "RoleArn");
        String problem = roleProblem(role, TRUSTED_SERVICE, region);
        if (problem != null) {
            throw ObservabilityAdminStore.validation(problem);
        }
        String id = ObservabilityAdminStore.random(16, LOWER);
        ObjectNode record = store.record();
        record.put("Arn", arn(region, account(), "dataset-integration/" + id));
        record.put("RoleArn", role);
        record.put("CreatedAt", seconds());
        record.put("UpdatedAt", seconds());
        ObservabilityAdminStore.addTags(record, in.path("Tags"));
        store.put(region, ObservabilityAdminStore.DATASET, id, record);
        return ObservabilityAdminStore.view(record);
    }

    public ObjectNode getDatasetIntegration(JsonNode in, String region) {
        return ObservabilityAdminStore.view(integration(ObservabilityAdminStore.DATASET, required(in, "Arn"),
                region));
    }

    public ObjectNode updateDatasetIntegration(JsonNode in, String region) {
        String arn = required(in, "Arn");
        ObjectNode record = integration(ObservabilityAdminStore.DATASET, arn, region);
        String role = required(in, "RoleArn");
        String problem = roleProblem(role, TRUSTED_SERVICE, region);
        if (problem != null) {
            throw ObservabilityAdminStore.validation(problem);
        }
        record.put("RoleArn", role);
        record.put("UpdatedAt", seconds());
        store.put(region, ObservabilityAdminStore.DATASET, arn.substring(arn.lastIndexOf('/') + 1), record);
        return ObservabilityAdminStore.view(record);
    }

    public ObjectNode deleteDatasetIntegration(JsonNode in, String region) {
        String arn = required(in, "Arn");
        integration(ObservabilityAdminStore.DATASET, arn, region);
        store.delete(region, ObservabilityAdminStore.DATASET, arn.substring(arn.lastIndexOf('/') + 1));
        return empty();
    }

    public ObjectNode listDatasetIntegrations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, ObservabilityAdminStore.DATASET).forEach(record -> items.add(
                ObservabilityAdminStore.view(record)));
        ObjectNode out = empty();
        ObservabilityAdminStore.page(items, in, out, "DatasetIntegrationSummaries", 50);
        return out;
    }

    // ================================================================ tags

    /** The record an ARN names, with where it's kept. */
    private record Located(String region, String kind, String id, ObjectNode record) {
    }

    private Located locate(String arn) {
        if (!AwsArnUtils.isArnFor(arn, ObservabilityAdminStore.SERVICE)) {
            throw ObservabilityAdminStore.validation("The ARN " + arn + " isn't an Observability Admin ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        String id = resource.substring(resource.indexOf('/') + 1);
        String type = resource.substring(0, Math.max(0, resource.indexOf('/')));
        String account = account();
        String region = parsed.region();
        Located located = switch (type) {
            case "telemetry-rule" -> new Located(ObservabilityAdminStore.GLOBAL, ObservabilityAdminStore.RULE, id,
                    null);
            case "organization-telemetry-rule" -> new Located(ObservabilityAdminStore.GLOBAL,
                    ObservabilityAdminStore.ORG_RULE, id, null);
            case "organization-centralization-rule" -> new Located(ObservabilityAdminStore.GLOBAL,
                    ObservabilityAdminStore.CENTRALIZATION, id, null);
            case "telemetry-pipeline" -> new Located(region, ObservabilityAdminStore.PIPELINE, id, null);
            case "s3tableintegration" -> new Located(region, ObservabilityAdminStore.S3_TABLES, id, null);
            case "dataset-integration" -> new Located(region, ObservabilityAdminStore.DATASET, id, null);
            default -> null;
        };
        if (located == null) {
            throw ObservabilityAdminStore.notFound("The resource " + arn + " doesn't exist.");
        }
        ObjectNode record = store.getForAccount(account, located.region(), located.kind(), located.id())
                .filter(found -> arn.equals(found.path("RuleArn").asText(found.path("Arn").asText()))
                        || located.region().equals(ObservabilityAdminStore.GLOBAL))
                .orElseThrow(() -> ObservabilityAdminStore.notFound("The resource " + arn + " doesn't exist."));
        return new Located(located.region(), located.kind(), located.id(), record);
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Located located = locate(required(in, "ResourceARN"));
        ObservabilityAdminStore.addTags(located.record(), in.path("Tags"));
        store.put(located.region(), located.kind(), located.id(), located.record());
        return empty();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Located located = locate(required(in, "ResourceARN"));
        in.path("TagKeys").forEach(key -> ObservabilityAdminStore.tags(located.record()).remove(key.asText()));
        store.put(located.region(), located.kind(), located.id(), located.record());
        return empty();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        Located located = locate(required(in, "ResourceARN"));
        ObjectNode out = empty();
        out.set("Tags", ObservabilityAdminStore.tags(located.record()).deepCopy());
        return out;
    }
}

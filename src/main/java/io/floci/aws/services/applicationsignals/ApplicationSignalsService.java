package io.floci.aws.services.applicationsignals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ServiceLinkedRoles;
import io.floci.aws.services.xray.XRayServiceMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Amazon CloudWatch Application Signals. Services, their operations, dependencies and dependents are discovered from
 * the account's X-Ray traces ({@link XRayServiceMap}), with the {@code ApplicationSignals} metrics they report.
 * Service level objectives evaluate their SLI against real CloudWatch metric data: a period-based SLO counts the
 * periods that meet the threshold, a request-based SLO counts good and bad requests, and budget reports give the
 * attainment and remaining error budget over the goal's interval, leaving out exclusion windows. Instrumentation
 * configurations are kept per service and environment with the status events their agents report. Grouping
 * configurations add service groups; {@code StartDiscovery} creates the service-linked role.
 */
@ApplicationScoped
public class ApplicationSignalsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(ApplicationSignalsService.class);
    private static final String SLO = "slo/";
    private static final String CONFIG = "instrumentation/";
    private static final int MAX_EXCLUSION_WINDOWS = 10;
    private static final int PAGE = 50;

    private final AccountAwareStorageBackend<ObjectNode> state;
    private final ObjectMapper mapper;
    private final Provider<XRayServiceMap> services;
    private final Provider<CloudWatchMetricsService> metrics;
    private final Provider<IamService> iam;

    @Inject
    public ApplicationSignalsService(StorageFactory storageFactory, ObjectMapper mapper,
                                     Provider<XRayServiceMap> services, Provider<CloudWatchMetricsService> metrics,
                                     Provider<IamService> iam) {
        this.state = storageFactory.create("application-signals", "applicationsignals-state.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.services = services;
        this.metrics = metrics;
        this.iam = iam;
    }

    @Override
    public void clear() {
        state.clear();
    }

    public ObjectNode handle(String op, JsonNode in, String region) {
        synchronized (this) {
            return switch (op) {
                case "StartDiscovery" -> startDiscovery(region);
                case "ListServices" -> listServices(in, region);
                case "GetService" -> getService(in, region);
                case "ListServiceOperations" -> listOperations(in, region);
                case "ListServiceDependencies" -> listDependencies(in, region);
                case "ListServiceDependents" -> listDependents(in, region);
                case "ListServiceStates" -> listStates(in, region);
                case "ListEntityEvents" -> listEntityEvents(in);
                case "ListAuditFindings" -> listAuditFindings(in, region);
                case "CreateServiceLevelObjective" -> createSlo(in, region);
                case "GetServiceLevelObjective" -> wrap("Slo", sloView(slo(region, in.path("Id").asText())));
                case "UpdateServiceLevelObjective" -> updateSlo(in, region);
                case "DeleteServiceLevelObjective" -> deleteSlo(in, region);
                case "ListServiceLevelObjectives" -> listSlos(in, region);
                case "BatchGetServiceLevelObjectiveBudgetReport" -> budgetReports(in, region);
                case "BatchUpdateExclusionWindows" -> updateExclusionWindows(in, region);
                case "ListServiceLevelObjectiveExclusionWindows" -> listExclusionWindows(in, region);
                case "CreateInstrumentationConfiguration" -> createConfiguration(in, region);
                case "GetInstrumentationConfiguration" -> wrap("Configuration",
                        configurationView(configuration(region, in), true));
                case "DeleteInstrumentationConfiguration" -> deleteConfiguration(in, region);
                case "BatchDeleteInstrumentationConfigurations" -> batchDeleteConfigurations(in, region);
                case "ListInstrumentationConfigurations" -> listConfigurations(in, region);
                case "GetInstrumentationConfigurationStatus" -> configurationStatus(in, region);
                case "ReportInstrumentationConfigurationStatus" -> reportStatus(in, region);
                case "PutGroupingConfiguration" -> putGrouping(in, region);
                case "ListGroupingAttributeDefinitions" -> listGrouping(region);
                case "DeleteGroupingConfiguration" -> {
                    state.delete(key(region, "grouping"));
                    yield mapper.createObjectNode();
                }
                case "TagResource" -> tagResource(in, region);
                case "UntagResource" -> untagResource(in, region);
                case "ListTagsForResource" -> listTags(in, region);
                default -> throw validation("Unsupported operation " + op);
            };
        }
    }

    // ---------- discovery ----------

    private ObjectNode startDiscovery(String region) {
        // The service-linked role is created once; discovery stays enabled on later calls.
        String service = ServicePrincipals.of("application-signals.cloudwatch");
        if (iam.get().findRole(state.accountId(), ServiceLinkedRoles.roleName(service).orElseThrow()).isEmpty()) {
            iam.get().createServiceLinkedRole(service, null,
                    "Allows CloudWatch Application Signals to discover services");
        }
        ObjectNode discovery = mapper.createObjectNode().put("enabled", true).put("since", now());
        state.put(key(region, "discovery"), discovery);
        return mapper.createObjectNode();
    }

    /** The window a discovery request covers: StartTime and EndTime, at most 24 hours apart. */
    private double[] window(JsonNode in) {
        double start = time(in.path("StartTime"));
        double end = time(in.path("EndTime"));
        if (end <= start) {
            throw validation("EndTime must be after StartTime.");
        }
        if (end - start > 86400) {
            throw validation("The time range can't be longer than 24 hours.");
        }
        return new double[]{start, end};
    }

    private List<XRayServiceMap.ObservedService> observed(String region, JsonNode in) {
        double[] window = window(in);
        return services.get().services(region, window[0], window[1]);
    }

    private ObjectNode keyAttributes(XRayServiceMap.ServiceKey key) {
        ObjectNode out = mapper.createObjectNode().put("Type", key.type()).put("Name", key.name());
        if (key.environment() != null) {
            out.put("Environment", key.environment());
        }
        return out;
    }

    private static boolean matches(XRayServiceMap.ServiceKey key, JsonNode attributes) {
        return Objects.equals(key.type(), attributes.path("Type").asText(null))
                && Objects.equals(key.name(), attributes.path("Name").asText(null))
                && (key.environment() == null || !attributes.hasNonNull("Environment")
                || key.environment().equals(attributes.path("Environment").asText()));
    }

    private ArrayNode metricReferences(XRayServiceMap.ServiceKey key, String operation, String account) {
        ArrayNode out = mapper.createArrayNode();
        for (String[] metric : new String[][]{{"LATENCY", "Latency"}, {"FAULT", "Fault"}, {"ERROR", "Error"}}) {
            ObjectNode reference = out.addObject().put("Namespace", "ApplicationSignals").put("MetricType", metric[0])
                    .put("MetricName", metric[1]).put("AccountId", account);
            ArrayNode dimensions = reference.putArray("Dimensions");
            dimensions.addObject().put("Name", "Service").put("Value", key.name());
            if (key.environment() != null) {
                dimensions.addObject().put("Name", "Environment").put("Value", key.environment());
            }
            if (operation != null) {
                dimensions.addObject().put("Name", "Operation").put("Value", operation);
            }
        }
        return out;
    }

    /** The service groups the account's grouping configuration gives a service. */
    private ArrayNode serviceGroups(String region, XRayServiceMap.ServiceKey key) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode definition : state.get(key(region, "grouping")).map(g -> g.path("GroupingAttributeDefinitions"))
                .orElse(mapper.createArrayNode())) {
            String value = definition.path("DefaultGroupingValue").asText(null);
            if (value == null) {
                continue;
            }
            String name = definition.path("GroupingName").asText();
            out.addObject().put("GroupName", name).put("GroupValue", value).put("GroupSource", "DEFAULT")
                    .put("GroupIdentifier", name + "#" + value);
        }
        return out;
    }

    private ObjectNode summary(String region, XRayServiceMap.ObservedService service) {
        ObjectNode out = mapper.createObjectNode();
        out.set("KeyAttributes", keyAttributes(service.key));
        ArrayNode maps = out.putArray("AttributeMaps");
        ObjectNode platform = maps.addObject();
        String environment = String.valueOf(service.key.environment());
        platform.put("PlatformType", environment.startsWith("lambda") ? "AWS::Lambda"
                : environment.startsWith("ecs") ? "AWS::ECS" : environment.startsWith("eks") ? "AWS::EKS"
                : environment.startsWith("ec2") ? "AWS::EC2" : "Generic");
        if (service.resourceArn != null) {
            platform.put("ResourceArn", service.resourceArn);
        }
        out.set("MetricReferences", metricReferences(service.key, null, state.accountId()));
        out.set("ServiceGroups", serviceGroups(region, service.key));
        return out;
    }

    private ObjectNode listServices(JsonNode in, String region) {
        double[] window = window(in);
        List<XRayServiceMap.ObservedService> found = observed(region, in).stream()
                .filter(s -> "Service".equals(s.key.type())).toList();
        ArrayNode out = mapper.createArrayNode();
        String next = page(found, in, s -> out.add(summary(region, s)));
        ObjectNode result = times(window);
        result.set("ServiceSummaries", out);
        return withToken(result, next);
    }

    private ObjectNode getService(JsonNode in, String region) {
        double[] window = window(in);
        ObjectNode result = times(window);
        Optional<XRayServiceMap.ObservedService> found = observed(region, in).stream()
                .filter(s -> matches(s.key, in.path("KeyAttributes"))).findFirst();
        if (found.isEmpty()) {
            result.putObject("Service");
            return result;
        }
        ObjectNode service = summary(region, found.get());
        service.putArray("LogGroupReferences");
        result.set("Service", service);
        return result;
    }

    private XRayServiceMap.ObservedService service(JsonNode in, String region) {
        return observed(region, in).stream().filter(s -> matches(s.key, in.path("KeyAttributes"))).findFirst()
                .orElse(null);
    }

    private ObjectNode listOperations(JsonNode in, String region) {
        double[] window = window(in);
        XRayServiceMap.ObservedService service = service(in, region);
        List<String> operations = service == null ? List.of() : new ArrayList<>(service.operations.keySet());
        ArrayNode out = mapper.createArrayNode();
        String next = page(operations, in, operation -> {
            ObjectNode entry = out.addObject().put("Name", operation);
            entry.set("MetricReferences", metricReferences(service.key, operation, state.accountId()));
        });
        ObjectNode result = times(window);
        result.set("ServiceOperations", out);
        return withToken(result, next);
    }

    private ObjectNode listDependencies(JsonNode in, String region) {
        double[] window = window(in);
        XRayServiceMap.ObservedService service = service(in, region);
        List<XRayServiceMap.Call> calls = service == null ? List.of() : service.calls;
        ArrayNode out = mapper.createArrayNode();
        String next = page(calls, in, call -> {
            ObjectNode entry = out.addObject().put("OperationName", call.operation());
            entry.set("DependencyKeyAttributes", keyAttributes(call.target()));
            entry.put("DependencyOperationName", call.targetOperation());
            entry.set("MetricReferences", metricReferences(service.key, call.operation(), state.accountId()));
        });
        ObjectNode result = times(window);
        result.set("ServiceDependencies", out);
        return withToken(result, next);
    }

    private ObjectNode listDependents(JsonNode in, String region) {
        double[] window = window(in);
        List<Object[]> dependents = new ArrayList<>();
        for (XRayServiceMap.ObservedService caller : observed(region, in)) {
            for (XRayServiceMap.Call call : caller.calls) {
                if (matches(call.target(), in.path("KeyAttributes"))) {
                    dependents.add(new Object[]{caller, call});
                }
            }
        }
        ArrayNode out = mapper.createArrayNode();
        String next = page(dependents, in, pair -> {
            XRayServiceMap.ObservedService caller = (XRayServiceMap.ObservedService) pair[0];
            XRayServiceMap.Call call = (XRayServiceMap.Call) pair[1];
            ObjectNode entry = out.addObject().put("OperationName", call.targetOperation());
            entry.set("DependentKeyAttributes", keyAttributes(caller.key));
            entry.put("DependentOperationName", call.operation());
            entry.set("MetricReferences", metricReferences(caller.key, call.operation(), state.accountId()));
        });
        ObjectNode result = times(window);
        result.set("ServiceDependents", out);
        return withToken(result, next);
    }

    private ObjectNode listStates(JsonNode in, String region) {
        double[] window = window(in);
        List<XRayServiceMap.ObservedService> found = services.get().services(region, window[0], window[1]).stream()
                .filter(s -> "Service".equals(s.key.type()))
                .filter(s -> attributeFiltersMatch(in.path("AttributeFilters"), s)).toList();
        ArrayNode out = mapper.createArrayNode();
        String next = page(found, in, s -> {
            ObjectNode entry = out.addObject();
            entry.set("Service", keyAttributes(s.key));
            entry.putArray("LatestChangeEvents");
        });
        ObjectNode result = times(window);
        result.set("ServiceStates", out);
        return withToken(result, next);
    }

    private static boolean attributeFiltersMatch(JsonNode filters, XRayServiceMap.ObservedService service) {
        for (JsonNode filter : filters) {
            String name = filter.path("AttributeFilterName").asText();
            List<String> values = new ArrayList<>();
            filter.path("AttributeFilterValues").forEach(v -> values.add(v.asText()));
            String actual = "Name".equals(name) ? service.key.name()
                    : "Environment".equals(name) ? service.key.environment() : null;
            if (actual != null && !values.contains(actual)) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode listEntityEvents(JsonNode in) {
        double[] window = window(in);
        if (!in.path("Entity").isObject() || in.path("Entity").isEmpty()) {
            throw validation("Entity is required.");
        }
        ObjectNode result = times(window);
        result.putArray("ChangeEvents");
        return result;
    }

    /** Findings for the audited services and SLOs: breached SLOs, and services whose faults rose above 5%. */
    private ObjectNode listAuditFindings(JsonNode in, String region) {
        double[] window = window(in);
        ArrayNode findings = mapper.createArrayNode();
        List<XRayServiceMap.ObservedService> observed = services.get().services(region, window[0], window[1]);
        for (JsonNode target : in.path("AuditTargets")) {
            String type = target.path("Type").asText();
            JsonNode data = target.path("Data");
            if ("service".equals(type)) {
                JsonNode wanted = data.path("Service");
                for (XRayServiceMap.ObservedService service : observed) {
                    if (service.key.name().equals(wanted.path("Name").asText())
                            && (!wanted.hasNonNull("Environment")
                            || wanted.get("Environment").asText().equals(service.key.environment()))
                            && service.requests > 0 && service.faults / (double) service.requests > 0.05) {
                        ObjectNode finding = findings.addObject();
                        finding.set("KeyAttributes", keyAttributes(service.key));
                        finding.put("Type", "service");
                        finding.putArray("AuditorResults").addObject().put("Auditor", "operation_metric")
                                .put("Description", service.key.name() + " returned faults for " + service.faults
                                        + " of " + service.requests + " requests.").put("Severity", "HIGH");
                    }
                }
            } else if ("slo".equals(type)) {
                String id = data.path("Slo").hasNonNull("SloArn") ? data.path("Slo").get("SloArn").asText()
                        : data.path("Slo").path("SloName").asText();
                ObjectNode slo;
                try {
                    slo = slo(region, id);
                } catch (AwsException e) {
                    continue;
                }
                ObjectNode report = report(region, slo, window[1]);
                if ("BREACHED".equals(report.path("BudgetStatus").asText())) {
                    ObjectNode finding = findings.addObject();
                    finding.set("KeyAttributes", mapper.createObjectNode().put("Type", "Slo")
                            .put("Name", slo.path("Name").asText()));
                    finding.put("Type", "slo");
                    finding.putArray("AuditorResults").addObject().put("Auditor", "slo")
                            .put("Description", "The SLO " + slo.path("Name").asText() + " has breached its goal.")
                            .put("Severity", "CRITICAL");
                }
            }
        }
        ObjectNode result = times(window);
        result.set("AuditFindings", findings);
        return result;
    }

    // ---------- service level objectives ----------

    private ObjectNode createSlo(JsonNode in, String region) {
        String name = in.path("Name").asText();
        if (state.get(key(region, SLO + name)).isPresent()) {
            throw new AwsException("ConflictException", "A service level objective named " + name
                    + " already exists.", 409);
        }
        ObjectNode slo = mapper.createObjectNode();
        double now = now();
        slo.put("Arn", AwsArnUtils.Arn.of("application-signals", region, state.accountId(), SLO + name).toString())
                .put("Name", name).put("CreatedTime", now).put("LastUpdatedTime", now);
        if (in.hasNonNull("Description")) {
            slo.put("Description", in.get("Description").asText());
        }
        applySli(slo, in);
        slo.set("Goal", goal(in.path("Goal"), null));
        if (in.path("BurnRateConfigurations").isArray()) {
            slo.set("BurnRateConfigurations", in.get("BurnRateConfigurations").deepCopy());
        }
        if (in.has("AutoInvestigationEnabled")) {
            slo.put("AutoInvestigationEnabled", in.get("AutoInvestigationEnabled").asBoolean());
        }
        slo.set("_tags", tags(in.path("Tags")));
        slo.putArray("_exclusionWindows");
        state.put(key(region, SLO + name), slo);
        return wrap("Slo", sloView(slo));
    }

    /** Sets the SLO's period-based or request-based indicator from a create or update request. */
    private void applySli(ObjectNode slo, JsonNode in) {
        boolean period = in.path("SliConfig").isObject();
        boolean request = in.path("RequestBasedSliConfig").isObject();
        if (period == request) {
            if (!period && slo.has("EvaluationType")) {
                return;
            }
            throw validation("Specify exactly one of SliConfig and RequestBasedSliConfig.");
        }
        if (slo.has("EvaluationType") && !slo.path("EvaluationType").asText().equals(period ? "PeriodBased"
                : "RequestBased")) {
            throw validation("A service level objective can't change between period-based and request-based.");
        }
        slo.remove(List.of("Sli", "RequestBasedSli"));
        if (period) {
            JsonNode config = in.get("SliConfig");
            if (!config.has("MetricThreshold") || !config.hasNonNull("ComparisonOperator")) {
                throw validation("A period-based SLI needs a MetricThreshold and a ComparisonOperator.");
            }
            ObjectNode sli = slo.putObject("Sli");
            sli.set("SliMetric", sliMetric(config.path("SliMetricConfig"), false));
            sli.put("MetricThreshold", config.get("MetricThreshold").asDouble())
                    .put("ComparisonOperator", config.get("ComparisonOperator").asText());
            slo.put("EvaluationType", "PeriodBased");
        } else {
            JsonNode config = in.get("RequestBasedSliConfig");
            ObjectNode sli = slo.putObject("RequestBasedSli");
            sli.set("RequestBasedSliMetric", sliMetric(config.path("RequestBasedSliMetricConfig"), true));
            if (config.has("MetricThreshold")) {
                sli.put("MetricThreshold", config.get("MetricThreshold").asDouble());
            }
            if (config.hasNonNull("ComparisonOperator")) {
                sli.put("ComparisonOperator", config.get("ComparisonOperator").asText());
            }
            slo.put("EvaluationType", "RequestBased");
        }
        JsonNode metric = slo.has("Sli") ? slo.path("Sli").path("SliMetric")
                : slo.path("RequestBasedSli").path("RequestBasedSliMetric");
        slo.put("MetricSourceType", metric.has("DependencyConfig") ? "ServiceDependency"
                : metric.has("KeyAttributes") ? "ServiceOperation" : "CloudWatchMetric");
    }

    /**
     * An SLI metric: the request's metric data queries, or, for a service operation ({@code KeyAttributes} and a
     * {@code MetricType}), queries over the {@code ApplicationSignals} metrics that operation reports.
     */
    private ObjectNode sliMetric(JsonNode config, boolean requestBased) {
        ObjectNode metric = mapper.createObjectNode();
        for (String field : List.of("KeyAttributes", "OperationName", "MetricType", "DependencyConfig",
                "MetricSource", "CompositeSliConfig")) {
            if (config.has(field) && !config.get(field).isNull()) {
                metric.set(field, config.get(field).deepCopy());
            }
        }
        if (requestBased) {
            if (config.path("TotalRequestCountMetric").isArray() && config.path("MonitoredRequestCountMetric")
                    .isObject()) {
                checkQueries(config.get("TotalRequestCountMetric"));
                JsonNode monitored = config.get("MonitoredRequestCountMetric");
                if (monitored.has("GoodCountMetric") == monitored.has("BadCountMetric")) {
                    throw validation("MonitoredRequestCountMetric is either GoodCountMetric or BadCountMetric.");
                }
                checkQueries(monitored.has("GoodCountMetric") ? monitored.get("GoodCountMetric")
                        : monitored.get("BadCountMetric"));
                metric.set("TotalRequestCountMetric", config.get("TotalRequestCountMetric").deepCopy());
                metric.set("MonitoredRequestCountMetric", monitored.deepCopy());
                return metric;
            }
        } else if (config.path("MetricDataQueries").isArray() && !config.get("MetricDataQueries").isEmpty()) {
            checkQueries(config.get("MetricDataQueries"));
            metric.set("MetricDataQueries", config.get("MetricDataQueries").deepCopy());
            return metric;
        }
        if (!config.path("KeyAttributes").isObject() || !config.hasNonNull("MetricType")) {
            throw validation("An SLI needs metric data queries, or KeyAttributes and a MetricType.");
        }
        String type = config.get("MetricType").asText();
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("Service", config.path("KeyAttributes").path("Name").asText());
        if (config.path("KeyAttributes").hasNonNull("Environment")) {
            dimensions.put("Environment", config.path("KeyAttributes").path("Environment").asText());
        }
        if (config.hasNonNull("OperationName")) {
            dimensions.put("Operation", config.get("OperationName").asText());
        }
        int period = config.path("PeriodSeconds").asInt(60);
        if (requestBased) {
            metric.set("TotalRequestCountMetric", mapper.createArrayNode().add(query("total", "LATENCY".equals(type)
                    ? "Latency" : "Fault", dimensions, period, "SampleCount")));
            ObjectNode monitored = metric.putObject("MonitoredRequestCountMetric");
            monitored.putArray("BadCountMetric").add(query("bad", "LATENCY".equals(type) ? "Latency" : "Fault",
                    dimensions, period, "LATENCY".equals(type) ? "SampleCount" : "Sum"));
        } else {
            String statistic = config.path("Statistic").asText("LATENCY".equals(type) ? "p99" : "Average");
            metric.set("MetricDataQueries", mapper.createArrayNode().add(query("m1", "LATENCY".equals(type)
                    ? "Latency" : "Availability", dimensions, period, statistic)));
        }
        return metric;
    }

    private ObjectNode query(String id, String name, Map<String, String> dimensions, int period, String stat) {
        ObjectNode query = mapper.createObjectNode().put("Id", id);
        ObjectNode metricStat = query.putObject("MetricStat");
        ObjectNode metric = metricStat.putObject("Metric").put("Namespace", "ApplicationSignals")
                .put("MetricName", name);
        ArrayNode dims = metric.putArray("Dimensions");
        dimensions.forEach((k, v) -> dims.addObject().put("Name", k).put("Value", v));
        metricStat.put("Period", period).put("Stat", stat);
        query.put("ReturnData", true);
        return query;
    }

    private static void checkQueries(JsonNode queries) {
        int returned = 0;
        for (JsonNode query : queries) {
            if (!query.hasNonNull("Id")) {
                throw validation("Each metric data query needs an Id.");
            }
            if (query.has("MetricStat") == query.has("Expression")) {
                throw validation("A metric data query has a MetricStat or an Expression.");
            }
            if (query.path("ReturnData").asBoolean(true)) {
                returned++;
            }
        }
        if (returned != 1) {
            throw validation("Exactly one metric data query must return data.");
        }
    }

    /** A goal with AWS's defaults: a rolling 7-day interval, 99% attainment, a 50% warning threshold. */
    private ObjectNode goal(JsonNode in, JsonNode current) {
        ObjectNode goal = current != null && current.isObject() ? ((ObjectNode) current).deepCopy()
                : mapper.createObjectNode();
        if (in.path("Interval").isObject()) {
            JsonNode interval = in.get("Interval");
            if (interval.has("RollingInterval") == interval.has("CalendarInterval")) {
                throw validation("An interval is either a RollingInterval or a CalendarInterval.");
            }
            goal.set("Interval", interval.deepCopy());
        } else if (!goal.has("Interval")) {
            goal.putObject("Interval").putObject("RollingInterval").put("DurationUnit", "DAY").put("Duration", 7);
        }
        if (in.has("AttainmentGoal")) {
            double attainment = in.get("AttainmentGoal").asDouble();
            if (attainment <= 0 || attainment >= 100) {
                throw validation("AttainmentGoal must be greater than 0 and less than 100.");
            }
            goal.put("AttainmentGoal", attainment);
        } else if (!goal.has("AttainmentGoal")) {
            goal.put("AttainmentGoal", 99.0);
        }
        if (in.has("WarningThreshold")) {
            goal.put("WarningThreshold", in.get("WarningThreshold").asDouble());
        } else if (!goal.has("WarningThreshold")) {
            goal.put("WarningThreshold", 50.0);
        }
        return goal;
    }

    private ObjectNode slo(String region, String id) {
        String name = id.contains(":slo/") ? id.substring(id.indexOf(":slo/") + 5) : id;
        return state.get(key(region, SLO + name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The service level objective " + id + " doesn't exist.", 404,
                Map.of("ResourceType", "ServiceLevelObjective", "ResourceId", id)));
    }

    private ObjectNode sloView(ObjectNode slo) {
        ObjectNode view = slo.deepCopy();
        view.remove(List.of("_tags", "_exclusionWindows"));
        return view;
    }

    private ObjectNode updateSlo(JsonNode in, String region) {
        ObjectNode slo = slo(region, in.path("Id").asText());
        if (in.has("Description")) {
            slo.put("Description", in.get("Description").asText());
        }
        if (in.path("SliConfig").isObject() || in.path("RequestBasedSliConfig").isObject()) {
            applySli(slo, in);
        }
        if (in.path("Goal").isObject()) {
            slo.set("Goal", goal(in.get("Goal"), slo.path("Goal")));
        }
        if (in.path("BurnRateConfigurations").isArray()) {
            slo.set("BurnRateConfigurations", in.get("BurnRateConfigurations").deepCopy());
        }
        if (in.has("AutoInvestigationEnabled")) {
            slo.put("AutoInvestigationEnabled", in.get("AutoInvestigationEnabled").asBoolean());
        }
        slo.put("LastUpdatedTime", now());
        state.put(key(region, SLO + slo.path("Name").asText()), slo);
        return wrap("Slo", sloView(slo));
    }

    private ObjectNode deleteSlo(JsonNode in, String region) {
        ObjectNode slo = slo(region, in.path("Id").asText());
        state.delete(key(region, SLO + slo.path("Name").asText()));
        return mapper.createObjectNode();
    }

    private ObjectNode listSlos(JsonNode in, String region) {
        List<ObjectNode> all = state.scan(k -> k.startsWith(region + "/" + SLO)).stream()
                .filter(s -> sloMatches(s, in))
                .sorted(Comparator.comparing(s -> s.path("Name").asText())).toList();
        ArrayNode out = mapper.createArrayNode();
        String next = page(all, in, slo -> {
            JsonNode metric = slo.has("Sli") ? slo.path("Sli").path("SliMetric")
                    : slo.path("RequestBasedSli").path("RequestBasedSliMetric");
            ObjectNode summary = out.addObject().put("Arn", slo.path("Arn").asText())
                    .put("Name", slo.path("Name").asText());
            for (String field : List.of("KeyAttributes", "OperationName", "DependencyConfig", "MetricSource",
                    "CompositeSliConfig")) {
                if (metric.has(field)) {
                    summary.set(field, metric.get(field));
                }
            }
            summary.set("CreatedTime", slo.path("CreatedTime"));
            summary.put("EvaluationType", slo.path("EvaluationType").asText())
                    .put("MetricSourceType", slo.path("MetricSourceType").asText());
        });
        ObjectNode result = mapper.createObjectNode();
        result.set("SloSummaries", out);
        return withToken(result, next);
    }

    private static boolean sloMatches(ObjectNode slo, JsonNode in) {
        JsonNode metric = slo.has("Sli") ? slo.path("Sli").path("SliMetric")
                : slo.path("RequestBasedSli").path("RequestBasedSliMetric");
        if (in.path("KeyAttributes").isObject() && !in.get("KeyAttributes").equals(metric.path("KeyAttributes"))) {
            return false;
        }
        if (in.hasNonNull("OperationName") && !in.get("OperationName").asText()
                .equals(metric.path("OperationName").asText(null))) {
            return false;
        }
        if (in.path("DependencyConfig").isObject() && !in.get("DependencyConfig")
                .equals(metric.path("DependencyConfig"))) {
            return false;
        }
        if (in.path("MetricSourceTypes").isArray() && !in.get("MetricSourceTypes").isEmpty()) {
            for (JsonNode type : in.get("MetricSourceTypes")) {
                if (type.asText().equals(slo.path("MetricSourceType").asText())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    // ---------- budget reports ----------

    private ObjectNode budgetReports(JsonNode in, String region) {
        double at = time(in.path("Timestamp"));
        ArrayNode reports = mapper.createArrayNode();
        ArrayNode errors = mapper.createArrayNode();
        for (JsonNode id : in.path("SloIds")) {
            try {
                reports.add(report(region, slo(region, id.asText()), at));
            } catch (AwsException e) {
                errors.addObject().put("Name", id.asText()).put("Arn", id.asText().contains(":slo/") ? id.asText()
                        : AwsArnUtils.Arn.of("application-signals", region, state.accountId(), SLO + id.asText())
                        .toString()).put("ErrorCode", e.getErrorCode()).put("ErrorMessage", e.getMessage());
            }
        }
        ObjectNode result = mapper.createObjectNode().put("Timestamp", at);
        result.set("Reports", reports);
        result.set("Errors", errors);
        return result;
    }

    /** The SLO's attainment and remaining error budget over its goal's interval, as of {@code at}. */
    ObjectNode report(String region, ObjectNode slo, double at) {
        JsonNode goal = slo.path("Goal");
        double attainmentGoal = goal.path("AttainmentGoal").asDouble(99);
        double warning = goal.path("WarningThreshold").asDouble(50);
        Instant end = Instant.ofEpochMilli((long) (at * 1000));
        Instant start = intervalStart(goal.path("Interval"), end);
        List<long[]> excluded = exclusions(slo, start, end);
        ObjectNode report = mapper.createObjectNode().put("Arn", slo.path("Arn").asText())
                .put("Name", slo.path("Name").asText()).put("EvaluationType", slo.path("EvaluationType").asText());
        if ("PeriodBased".equals(slo.path("EvaluationType").asText())) {
            JsonNode sli = slo.path("Sli");
            JsonNode query = returned(sli.path("SliMetric").path("MetricDataQueries"));
            int period = query.path("MetricStat").path("Period").asInt(60);
            long total = Math.max(1, ChronoUnit.SECONDS.between(start, end) / period);
            Map<Long, Double> values = values(region, query, start, end);
            long bad = 0;
            long measured = 0;
            for (Map.Entry<Long, Double> value : values.entrySet()) {
                if (isExcluded(excluded, value.getKey())) {
                    continue;
                }
                measured++;
                if (!compare(value.getValue(), sli.path("ComparisonOperator").asText(),
                        sli.path("MetricThreshold").asDouble())) {
                    bad++;
                }
            }
            long budgetPeriods = (long) Math.floor(total * (100 - attainmentGoal) / 100.0);
            long totalBudgetSeconds = budgetPeriods * period;
            long remaining = totalBudgetSeconds - bad * period;
            report.put("Attainment", measured == 0 ? 100.0 : round(100.0 * (measured - bad) / measured))
                    .put("TotalBudgetSeconds", totalBudgetSeconds).put("BudgetSecondsRemaining", remaining);
            report.put("BudgetStatus", status(measured, remaining, totalBudgetSeconds, warning));
            report.set("Sli", sli);
        } else {
            JsonNode sli = slo.path("RequestBasedSli");
            JsonNode metric = sli.path("RequestBasedSliMetric");
            long total = sum(region, returned(metric.path("TotalRequestCountMetric")), start, end, excluded);
            JsonNode monitored = metric.path("MonitoredRequestCountMetric");
            long bad = monitored.has("BadCountMetric")
                    ? sum(region, returned(monitored.get("BadCountMetric")), start, end, excluded)
                    : total - sum(region, returned(monitored.path("GoodCountMetric")), start, end, excluded);
            bad = Math.max(0, Math.min(total, bad));
            long totalBudget = (long) Math.floor(total * (100 - attainmentGoal) / 100.0);
            long remaining = totalBudget - bad;
            report.put("Attainment", total == 0 ? 100.0 : round(100.0 * (total - bad) / total))
                    .put("TotalBudgetRequests", totalBudget).put("BudgetRequestsRemaining", remaining);
            report.put("BudgetStatus", status(total, remaining, totalBudget, warning));
            report.set("RequestBasedSli", sli);
        }
        report.set("Goal", goal);
        return report;
    }

    private static String status(long measured, long remaining, long total, double warning) {
        if (measured == 0) {
            return "INSUFFICIENT_DATA";
        }
        if (remaining < 0) {
            return "BREACHED";
        }
        return total > 0 && remaining < total * warning / 100.0 ? "WARNING" : "OK";
    }

    private static JsonNode returned(JsonNode queries) {
        for (JsonNode query : queries) {
            if (query.path("ReturnData").asBoolean(true)) {
                return query;
            }
        }
        return queries.path(0);
    }

    /** The query's values by period start (epoch seconds), from CloudWatch. */
    private Map<Long, Double> values(String region, JsonNode query, Instant start, Instant end) {
        Map<Long, Double> out = new LinkedHashMap<>();
        JsonNode stat = query.path("MetricStat");
        if (stat.isMissingNode()) {
            return out;
        }
        List<Dimension> dimensions = new ArrayList<>();
        stat.path("Metric").path("Dimensions").forEach(d -> dimensions.add(new Dimension(d.path("Name").asText(),
                d.path("Value").asText())));
        CloudWatchMetricsService.MetricStat metricStat = new CloudWatchMetricsService.MetricStat(
                stat.path("Metric").path("Namespace").asText(), stat.path("Metric").path("MetricName").asText(),
                dimensions, stat.path("Period").asInt(60), stat.path("Stat").asText("Average"),
                stat.path("Unit").asText(null));
        try {
            for (CloudWatchMetricsService.MetricDataResult result : metrics.get().getMetricData(List.of(
                    new CloudWatchMetricsService.MetricDataQuery(query.path("Id").asText("m1"), metricStat, null, null,
                            true)), start, end, region)) {
                for (int i = 0; i < result.timestamps().size(); i++) {
                    out.put(result.timestamps().get(i).getEpochSecond(), result.values().get(i));
                }
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "Application Signals could not read metric data for {0}", query);
        }
        return out;
    }

    private long sum(String region, JsonNode query, Instant start, Instant end, List<long[]> excluded) {
        double total = 0;
        for (Map.Entry<Long, Double> value : values(region, query, start, end).entrySet()) {
            if (!isExcluded(excluded, value.getKey())) {
                total += value.getValue();
            }
        }
        return Math.round(total);
    }

    private static boolean compare(double value, String operator, double threshold) {
        if ("GreaterThanOrEqualTo".equals(operator)) {
            return value >= threshold;
        }
        if ("GreaterThan".equals(operator)) {
            return value > threshold;
        }
        return "LessThan".equals(operator) ? value < threshold : value <= threshold;
    }

    private static Instant intervalStart(JsonNode interval, Instant end) {
        if (interval.has("CalendarInterval")) {
            JsonNode calendar = interval.get("CalendarInterval");
            Instant start = Instant.ofEpochMilli((long) (time(calendar.path("StartTime")) * 1000));
            ChronoUnit unit = unit(calendar.path("DurationUnit").asText());
            long length = calendar.path("Duration").asLong(1);
            ZonedDateTime cursor = start.atZone(ZoneOffset.UTC);
            while (cursor.plus(length, unit).toInstant().isBefore(end)) {
                cursor = cursor.plus(length, unit);
            }
            return cursor.toInstant();
        }
        JsonNode rolling = interval.path("RollingInterval");
        return end.atZone(ZoneOffset.UTC).minus(rolling.path("Duration").asLong(7),
                unit(rolling.path("DurationUnit").asText("DAY"))).toInstant();
    }

    private static ChronoUnit unit(String unit) {
        return switch (unit) {
            case "MINUTE" -> ChronoUnit.MINUTES;
            case "HOUR" -> ChronoUnit.HOURS;
            case "MONTH" -> ChronoUnit.MONTHS;
            default -> ChronoUnit.DAYS;
        };
    }

    // ---------- exclusion windows ----------

    /** The SLO's exclusion windows that overlap the interval, as [start, end) epoch seconds. */
    private static List<long[]> exclusions(ObjectNode slo, Instant start, Instant end) {
        List<long[]> out = new ArrayList<>();
        for (JsonNode window : slo.path("_exclusionWindows")) {
            if (!window.has("StartTime")) {
                continue;
            }
            long from = (long) time(window.path("StartTime"));
            long length = windowSeconds(window.path("Window"));
            out.add(new long[]{from, from + length});
        }
        return out;
    }

    private static boolean isExcluded(List<long[]> windows, long second) {
        for (long[] window : windows) {
            if (second >= window[0] && second < window[1]) {
                return true;
            }
        }
        return false;
    }

    /** The length of a window, in seconds. */
    private static long windowSeconds(JsonNode window) {
        long duration = window.path("Duration").asLong();
        return switch (window.path("DurationUnit").asText()) {
            case "MINUTE" -> duration * 60;
            case "HOUR" -> duration * 3600;
            case "MONTH" -> duration * 30 * 86400;
            default -> duration * 86400;
        };
    }

    private ObjectNode updateExclusionWindows(JsonNode in, String region) {
        ArrayNode errors = mapper.createArrayNode();
        ArrayNode updated = mapper.createArrayNode();
        for (JsonNode window : in.path("AddExclusionWindows")) {
            checkWindow(window);
        }
        for (JsonNode id : in.path("SloIds")) {
            ObjectNode slo;
            try {
                slo = slo(region, id.asText());
            } catch (AwsException e) {
                errors.addObject().put("SloId", id.asText()).put("ErrorCode", e.getErrorCode())
                        .put("ErrorMessage", e.getMessage());
                continue;
            }
            ArrayNode windows = (ArrayNode) slo.withArray("_exclusionWindows");
            for (JsonNode remove : in.path("RemoveExclusionWindows")) {
                for (Iterator<JsonNode> it = windows.iterator(); it.hasNext(); ) {
                    if (sameWindow(it.next(), remove)) {
                        it.remove();
                    }
                }
            }
            for (JsonNode add : in.path("AddExclusionWindows")) {
                boolean present = false;
                for (JsonNode existing : windows) {
                    present |= sameWindow(existing, add);
                }
                if (!present) {
                    windows.add(add.deepCopy());
                }
            }
            if (windows.size() > MAX_EXCLUSION_WINDOWS) {
                errors.addObject().put("SloId", id.asText()).put("ErrorCode", "ValidationException")
                        .put("ErrorMessage", "A service level objective has at most " + MAX_EXCLUSION_WINDOWS
                                + " exclusion windows.");
                continue;
            }
            state.put(key(region, SLO + slo.path("Name").asText()), slo);
            updated.add(id.asText());
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("SloIds", updated);
        result.set("Errors", errors);
        return result;
    }

    private static void checkWindow(JsonNode window) {
        if (!window.path("Window").isObject()) {
            throw validation("An exclusion window needs a Window.");
        }
        if (!window.has("StartTime") && !window.path("RecurrenceRule").isObject()) {
            throw validation("An exclusion window needs a StartTime or a RecurrenceRule.");
        }
    }

    private static boolean sameWindow(JsonNode a, JsonNode b) {
        return a.path("Window").equals(b.path("Window"))
                && Math.abs(time(a.path("StartTime")) - time(b.path("StartTime"))) < 1
                && a.path("RecurrenceRule").path("Expression").asText("")
                .equals(b.path("RecurrenceRule").path("Expression").asText(""));
    }

    private ObjectNode listExclusionWindows(JsonNode in, String region) {
        ObjectNode slo = slo(region, in.path("Id").asText());
        List<JsonNode> windows = new ArrayList<>();
        slo.path("_exclusionWindows").forEach(windows::add);
        ArrayNode out = mapper.createArrayNode();
        String next = page(windows, in, out::add);
        ObjectNode result = mapper.createObjectNode();
        result.set("ExclusionWindows", out);
        return withToken(result, next);
    }

    // ---------- instrumentation configurations ----------

    private static String configKey(String type, String service, String environment, String signal, String hash) {
        return CONFIG + type + "/" + service + "/" + environment + "/" + signal + "/" + hash;
    }

    /** A code location's hash: the same location always hashes the same. */
    static String locationHash(JsonNode location) {
        JsonNode code = location.path("CodeLocation");
        String text = String.join("|", code.path("Language").asText(), code.path("CodeUnit").asText(""),
                code.path("ClassName").asText(""), code.path("MethodName").asText(""), code.path("FilePath").asText(),
                code.path("LineNumber").asText(""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode createConfiguration(JsonNode in, String region) {
        JsonNode code = in.path("Location").path("CodeLocation");
        if (!code.isObject()) {
            throw validation("Location.CodeLocation is required.");
        }
        if ("BREAKPOINT".equals(in.path("InstrumentationType").asText()) && !code.has("LineNumber")) {
            throw validation("A breakpoint needs a LineNumber.");
        }
        String hash = locationHash(in.path("Location"));
        String key = configKey(in.path("InstrumentationType").asText(), in.path("Service").asText(),
                in.path("Environment").asText(), in.path("SignalType").asText(), hash);
        if (state.get(key(region, key)).isPresent()) {
            throw new AwsException("ConflictException", "An instrumentation configuration already exists at this "
                    + "location.", 409);
        }
        ObjectNode configuration = mapper.createObjectNode();
        for (String field : List.of("InstrumentationType", "Service", "Environment", "SignalType", "Location",
                "Description", "ExpiresAt", "AttributeFilters", "CaptureConfiguration")) {
            if (in.has(field) && !in.get(field).isNull()) {
                configuration.set(field, in.get(field).deepCopy());
            }
        }
        configuration.put("LocationHash", hash).put("CreatedAt", now())
                .put("ARN", AwsArnUtils.Arn.of("application-signals", region, state.accountId(),
                        "instrumentationConfig/" + hash).toString());
        configuration.set("_tags", tags(in.path("Tags")));
        configuration.putArray("_events");
        state.put(key(region, key), configuration);
        return configurationView(configuration, true);
    }

    private ObjectNode configuration(String region, JsonNode in) {
        JsonNode identifier = in.path("LocationIdentifier");
        String hash = identifier.hasNonNull("LocationHash") ? identifier.get("LocationHash").asText()
                : identifier.path("CodeLocation").isObject()
                ? locationHash(mapper.createObjectNode().set("CodeLocation", identifier.get("CodeLocation"))) : null;
        if (hash == null) {
            throw validation("LocationIdentifier needs a LocationHash or a CodeLocation.");
        }
        String key = configKey(in.path("InstrumentationType").asText(), in.path("Service").asText(),
                in.path("Environment").asText(), in.path("SignalType").asText(), hash);
        ObjectNode configuration = state.get(key(region, key)).orElseThrow(() -> notFound("instrumentation "
                + "configuration " + hash));
        if (configuration.hasNonNull("ExpiresAt") && time(configuration.get("ExpiresAt")) < now()) {
            state.delete(key(region, key));
            throw notFound("instrumentation configuration " + hash);
        }
        return configuration;
    }

    private ObjectNode configurationView(ObjectNode configuration, boolean withServiceEnvironment) {
        ObjectNode view = configuration.deepCopy();
        view.remove(List.of("_tags", "_events"));
        if (!withServiceEnvironment) {
            view.remove(List.of("Service", "Environment"));
        }
        return view;
    }

    private String keyOf(ObjectNode configuration) {
        return configKey(configuration.path("InstrumentationType").asText(), configuration.path("Service").asText(),
                configuration.path("Environment").asText(), configuration.path("SignalType").asText(),
                configuration.path("LocationHash").asText());
    }

    private ObjectNode deleteConfiguration(JsonNode in, String region) {
        ObjectNode configuration = configuration(region, in);
        state.delete(key(region, keyOf(configuration)));
        return mapper.createObjectNode().put("DeletionStatus", "DELETED");
    }

    private ObjectNode batchDeleteConfigurations(JsonNode in, String region) {
        JsonNode target = in.path("DeletionTarget");
        ArrayNode deleted = mapper.createArrayNode();
        ArrayNode errors = mapper.createArrayNode();
        List<ObjectNode> all = configurations(region);
        if (target.path("Scope").isObject()) {
            JsonNode scope = target.get("Scope");
            for (ObjectNode configuration : all) {
                if (scope.path("Service").asText().equals(configuration.path("Service").asText())
                        && scope.path("Environment").asText().equals(configuration.path("Environment").asText())
                        && scope.path("InstrumentationType").asText()
                        .equals(configuration.path("InstrumentationType").asText())) {
                    state.delete(key(region, keyOf(configuration)));
                    deleted.addObject().put("ResourceArn", configuration.path("ARN").asText())
                            .put("SignalType", configuration.path("SignalType").asText())
                            .put("LocationHash", configuration.path("LocationHash").asText());
                }
            }
        } else {
            for (JsonNode arn : target.path("ResourceArns").path("ResourceArns")) {
                Optional<ObjectNode> found = all.stream().filter(c -> arn.asText().equals(c.path("ARN").asText()))
                        .findFirst();
                if (found.isEmpty()) {
                    errors.addObject().put("ResourceArn", arn.asText()).put("Code", "ResourceNotFoundException")
                            .put("Message", "The instrumentation configuration " + arn.asText() + " doesn't exist.");
                    continue;
                }
                state.delete(key(region, keyOf(found.get())));
                deleted.addObject().put("ResourceArn", arn.asText())
                        .put("SignalType", found.get().path("SignalType").asText())
                        .put("LocationHash", found.get().path("LocationHash").asText());
            }
        }
        ObjectNode result = mapper.createObjectNode().put("DeletedCount", deleted.size());
        result.set("SuccessfulDeletions", deleted);
        result.set("Errors", errors);
        return result;
    }

    private List<ObjectNode> configurations(String region) {
        return state.scan(k -> k.startsWith(region + "/" + CONFIG));
    }

    private ObjectNode listConfigurations(JsonNode in, String region) {
        double syncedAt = in.has("SyncedAt") ? time(in.get("SyncedAt")) : 0;
        List<ObjectNode> matching = configurations(region).stream()
                .filter(c -> in.path("Service").asText().equals(c.path("Service").asText())
                        && in.path("Environment").asText().equals(c.path("Environment").asText())
                        && in.path("InstrumentationType").asText().equals(c.path("InstrumentationType").asText()))
                .filter(c -> !c.hasNonNull("ExpiresAt") || time(c.get("ExpiresAt")) >= now())
                .sorted(Comparator.comparing(c -> c.path("LocationHash").asText())).toList();
        boolean changed = syncedAt == 0 || matching.stream().anyMatch(c -> c.path("CreatedAt").asDouble() > syncedAt);
        ObjectNode result = mapper.createObjectNode().put("Service", in.path("Service").asText())
                .put("Environment", in.path("Environment").asText()).put("Changed", changed)
                .put("SyncedAt", now()).put("SyncInterval", 30);
        ArrayNode out = result.putArray("LatestConfigurations");
        String next = changed ? page(matching, in, c -> out.add(configurationView(c, false))) : null;
        return withToken(result, next);
    }

    private ObjectNode configurationStatus(JsonNode in, String region) {
        ObjectNode configuration = configuration(region, in);
        double start = in.has("StartTime") ? time(in.get("StartTime")) : 0;
        double end = in.has("EndTime") ? time(in.get("EndTime")) : Double.MAX_VALUE;
        String wanted = in.path("Status").asText(null);
        List<JsonNode> events = new ArrayList<>();
        for (JsonNode event : configuration.path("_events")) {
            double at = event.path("Time").asDouble();
            if (at >= start && at <= end && (wanted == null || wanted.equals(event.path("_status").asText()))) {
                events.add(event);
            }
        }
        String status = configuration.path("_events").isEmpty() ? "READY"
                : configuration.path("_events").get(0).path("_status").asText("READY");
        ObjectNode result = mapper.createObjectNode().put("Service", configuration.path("Service").asText())
                .put("Environment", configuration.path("Environment").asText())
                .put("SignalType", configuration.path("SignalType").asText());
        result.set("Location", configuration.path("Location"));
        result.put("Status", status);
        ArrayNode out = result.putArray("Events");
        String next = page(events, in, event -> {
            ObjectNode copy = ((ObjectNode) event).deepCopy();
            copy.remove("_status");
            out.add(copy);
        });
        return withToken(result, next);
    }

    private ObjectNode reportStatus(JsonNode in, String region) {
        ArrayNode unprocessed = mapper.createArrayNode();
        for (JsonNode report : in.path("Configurations")) {
            String key = configKey(report.path("InstrumentationType").asText(), in.path("Service").asText(),
                    in.path("Environment").asText(), report.path("SignalType").asText(),
                    report.path("LocationHash").asText());
            Optional<ObjectNode> configuration = state.get(key(region, key));
            if (configuration.isEmpty()) {
                ObjectNode entry = ((ObjectNode) report).deepCopy();
                entry.put("FailedReason", "VALIDATION_ERROR");
                unprocessed.add(entry);
                continue;
            }
            ObjectNode event = mapper.createObjectNode().put("Time", time(report.path("Time")))
                    .put("_status", report.path("Status").asText());
            if (report.hasNonNull("ErrorCause")) {
                event.put("ErrorCause", report.get("ErrorCause").asText());
            }
            ((ArrayNode) configuration.get().withArray("_events")).insert(0, event);
            state.put(key(region, key), configuration.get());
        }
        ObjectNode result = mapper.createObjectNode().put("Service", in.path("Service").asText())
                .put("Environment", in.path("Environment").asText());
        result.set("UnprocessedStatusEvents", unprocessed);
        return result;
    }

    // ---------- grouping ----------

    private ObjectNode putGrouping(JsonNode in, String region) {
        Set<String> names = new HashSet<>();
        for (JsonNode definition : in.path("GroupingAttributeDefinitions")) {
            if (!names.add(definition.path("GroupingName").asText())) {
                throw validation("Grouping names must be unique.");
            }
            if (definition.path("GroupingSourceKeys").isEmpty()) {
                throw validation("Each grouping attribute definition needs source keys.");
            }
        }
        ObjectNode grouping = mapper.createObjectNode();
        grouping.set("GroupingAttributeDefinitions", in.path("GroupingAttributeDefinitions").deepCopy());
        grouping.put("UpdatedAt", now());
        state.put(key(region, "grouping"), grouping);
        return wrap("GroupingConfiguration", grouping);
    }

    private ObjectNode listGrouping(String region) {
        ObjectNode grouping = state.get(key(region, "grouping")).orElse(null);
        ObjectNode result = mapper.createObjectNode();
        result.set("GroupingAttributeDefinitions", grouping == null ? mapper.createArrayNode()
                : grouping.path("GroupingAttributeDefinitions"));
        if (grouping != null) {
            result.set("UpdatedAt", grouping.path("UpdatedAt"));
        }
        return result;
    }

    // ---------- tags ----------

    /** The SLO or instrumentation configuration of the caller's account in the Region that has the ARN. */
    private Optional<Map.Entry<String, ObjectNode>> taggable(String region, String arn) {
        for (String key : state.keys()) {
            if (!key.startsWith(region + "/" + SLO) && !key.startsWith(region + "/" + CONFIG)) {
                continue;
            }
            Optional<ObjectNode> value = state.get(key);
            if (value.isPresent() && (arn.equals(value.get().path("Arn").asText(null))
                    || arn.equals(value.get().path("ARN").asText(null)))) {
                return Optional.of(Map.entry(key, value.get()));
            }
        }
        return Optional.empty();
    }

    private Map.Entry<String, ObjectNode> requireTaggable(String region, String arn) {
        return taggable(region, arn).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The resource " + arn + " doesn't exist.", 404));
    }

    private ObjectNode tagResource(JsonNode in, String region) {
        Map.Entry<String, ObjectNode> resource = requireTaggable(region, in.path("ResourceArn").asText());
        ObjectNode tags = (ObjectNode) resource.getValue().withObject("/_tags");
        for (JsonNode tag : in.path("Tags")) {
            tags.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        if (tags.size() > 200) {
            throw new AwsException("ServiceQuotaExceededException", "A resource can have at most 200 tags.", 402);
        }
        state.put(resource.getKey(), resource.getValue());
        return mapper.createObjectNode();
    }

    private ObjectNode untagResource(JsonNode in, String region) {
        Map.Entry<String, ObjectNode> resource = requireTaggable(region, in.path("ResourceArn").asText());
        ObjectNode tags = (ObjectNode) resource.getValue().withObject("/_tags");
        in.path("TagKeys").forEach(k -> tags.remove(k.asText()));
        state.put(resource.getKey(), resource.getValue());
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        Map.Entry<String, ObjectNode> resource = requireTaggable(region, in.path("ResourceArn").asText());
        ArrayNode out = mapper.createArrayNode();
        resource.getValue().path("_tags").fields().forEachRemaining(e -> out.addObject().put("Key", e.getKey())
                .put("Value", e.getValue().asText()));
        return wrap("Tags", out);
    }

    private ObjectNode tags(JsonNode list) {
        ObjectNode out = mapper.createObjectNode();
        for (JsonNode tag : list) {
            out.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        return out;
    }

    // ---------- helpers ----------

    /** A timestamp: epoch seconds, or an ISO-8601 string as query parameters carry it. */
    static double time(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        String text = value.asText();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            try {
                return Instant.parse(text).toEpochMilli() / 1000.0;
            } catch (RuntimeException parse) {
                throw validation("Invalid timestamp " + text + ".");
            }
        }
    }

    private ObjectNode times(double[] window) {
        return mapper.createObjectNode().put("StartTime", window[0]).put("EndTime", window[1]);
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private ObjectNode wrap(String field, JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private static ObjectNode withToken(ObjectNode out, String next) {
        if (next != null) {
            out.put("NextToken", next);
        }
        return out;
    }

    private static <T> String page(List<T> items, JsonNode in, Consumer<T> emit) {
        int size = in.has("MaxResults") ? in.get("MaxResults").asInt() : PAGE;
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The NextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + size);
        for (int i = Math.min(start, items.size()); i < end; i++) {
            emit.accept(items.get(i));
        }
        return end < items.size()
                ? Base64.getEncoder().encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)) : null;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String what) {
        return new AwsException("ResourceNotFoundException", "The " + what + " doesn't exist.", 404);
    }
}

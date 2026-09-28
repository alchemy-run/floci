package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonCompatibilityDetail;
import io.github.hectorvent.floci.services.eks.model.AddonInfo;
import io.github.hectorvent.floci.services.eks.model.AddonVersionInfo;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.DeprecationDetail;
import io.github.hectorvent.floci.services.eks.model.Insight;
import io.github.hectorvent.floci.services.eks.model.InsightCategorySpecificSummary;
import io.github.hectorvent.floci.services.eks.model.InsightResourceDetail;
import io.github.hectorvent.floci.services.eks.model.InsightStatus;
import io.github.hectorvent.floci.services.eks.model.InsightSummary;
import io.github.hectorvent.floci.services.eks.model.InsightsRefresh;
import io.github.hectorvent.floci.services.eks.model.ListInsightsRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EKS cluster insights (ListInsights, DescribeInsight, StartInsightsRefresh,
 * DescribeInsightsRefresh). Every check is evaluated against the cluster's actual state: the
 * deprecated API check reads the {@code apiserver_requested_deprecated_apis} metric from the
 * cluster's k3s API server, the add-on check compares installed add-on versions with the add-on
 * catalog for the next Kubernetes minor version, and the kubelet check compares nodegroup
 * versions with the skew policy. A check whose input is unavailable reports {@code UNKNOWN}.
 */
@ApplicationScoped
public class EksInsightsService {

    private static final Logger LOG = Logger.getLogger(EksInsightsService.class);

    static final String UPGRADE_READINESS = "UPGRADE_READINESS";
    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String COMPLETED = "COMPLETED";
    static final String FAILED = "FAILED";
    private static final Set<String> CATEGORIES = Set.of("UPGRADE_READINESS", "MISCONFIGURATION", "ROLLBACK_READINESS");
    private static final Set<String> STATUSES = Set.of("PASSING", "WARNING", "ERROR", "UNKNOWN");
    private static final int MAX_PAGE_SIZE = 100;
    /** Kubernetes 1.28 and later let a kubelet trail the API server by up to three minor versions. */
    static final int KUBELET_MAX_SKEW = 3;
    /** EKS re-evaluates insights on a daily schedule in addition to on-demand refreshes. */
    static final Duration SCHEDULED_REFRESH_INTERVAL = Duration.ofHours(24);
    /** A refresh still IN_PROGRESS after this long was lost (for example to a restart). */
    static final Duration REFRESH_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern DEPRECATED_API_SAMPLE =
            Pattern.compile("^apiserver_requested_deprecated_apis\\{([^}]*)}\\s+(\\S+)");
    private static final Pattern METRIC_LABEL = Pattern.compile("(\\w+)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern MINOR_VERSION = Pattern.compile("^v?1\\.(\\d+)");

    private final StorageBackend<String, StoredInsights> storage;
    private final EksService clusters;
    private final EksAddonService addons;
    private final EksAddonCatalog catalog;
    private final EksClusterManager clusterManager;
    private final RegionResolver regionResolver;
    private final Executor refresher;

    @RegisterForReflection
    public record StoredInsights(List<Insight> insights, InsightsRefresh refresh) {}

    /** The cluster state a refresh evaluates, captured on the request thread. */
    record ClusterState(Cluster cluster, List<Addon> addons, List<Nodegroup> nodegroups) {}

    record DeprecatedApiUsage(String group, String version, String resource, String subresource,
                              String removedRelease) {
        String usage() {
            String base = group.isEmpty() ? "/api/" + version : "/apis/" + group + "/" + version;
            return base + "/" + resource + (subresource.isEmpty() ? "" : "/" + subresource);
        }
    }

    @Inject
    public EksInsightsService(StorageFactory storageFactory, EksService clusters, EksAddonService addons,
                              EksAddonCatalog catalog, EksClusterManager clusterManager,
                              RegionResolver regionResolver) {
        this(storageFactory.create("eks", "eks-insights.json",
                        new TypeReference<Map<String, StoredInsights>>() {}),
                clusters, addons, catalog, clusterManager, regionResolver,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "eks-insights-refresh");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    EksInsightsService(StorageBackend<String, StoredInsights> storage, EksService clusters,
                       EksAddonService addons, EksAddonCatalog catalog, EksClusterManager clusterManager,
                       RegionResolver regionResolver, Executor refresher) {
        this.storage = storage;
        this.clusters = clusters;
        this.addons = addons;
        this.catalog = catalog;
        this.clusterManager = clusterManager;
        this.regionResolver = regionResolver;
        this.refresher = refresher;
    }

    @PreDestroy
    void shutdown() {
        if (refresher instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    public PaginatedResult<InsightSummary> list(Cluster cluster, ListInsightsRequest request) {
        ListInsightsRequest.Filter filter = request == null ? null : request.filter();
        validateFilter(filter);
        List<InsightSummary> matching = currentInsights(cluster).stream()
                .filter(insight -> matches(insight, filter))
                .map(Insight::summary)
                .toList();
        return Pagination.paginate(matching, InsightSummary::id,
                request == null ? null : request.maxResults(),
                request == null ? null : request.nextToken(),
                MAX_PAGE_SIZE, "InvalidParameterException");
    }

    public Insight describe(Cluster cluster, String id) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParameterException", "id is required", 400);
        }
        return currentInsights(cluster).stream()
                .filter(insight -> insight.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No insight found for id: " + id, 404));
    }

    public InsightsRefresh startRefresh(Cluster cluster) {
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException",
                    "Cluster must be ACTIVE to refresh insights, but was " + cluster.getStatus(), 400);
        }
        String key = key(cluster);
        String accountId = accountOf(cluster);
        ClusterState state = snapshot(cluster);
        Instant now = Instant.now();
        InsightsRefresh started;
        synchronized (this) {
            StoredInsights stored = storage.get(key).orElse(null);
            InsightsRefresh current = stored == null ? null : effectiveRefresh(stored.refresh(), now);
            if (current != null && IN_PROGRESS.equals(current.status())) {
                throw new AwsException("InvalidRequestException",
                        "An insights refresh is already in progress for cluster " + cluster.getName(), 400);
            }
            started = new InsightsRefresh("Insights refresh is in progress.", IN_PROGRESS, epochSeconds(now), null);
            storage.put(key, new StoredInsights(stored == null ? null : stored.insights(), started));
        }
        refresher.execute(() -> RequestScopes.runAs(accountId, () -> completeRefresh(key, state, started)));
        return new InsightsRefresh("Insights refresh started.", IN_PROGRESS, null, null);
    }

    public InsightsRefresh describeRefresh(Cluster cluster) {
        InsightsRefresh refresh = storage.get(key(cluster)).map(StoredInsights::refresh).orElse(null);
        if (refresh == null) {
            throw new AwsException("ResourceNotFoundException",
                    "No insights refresh has been started for cluster: " + cluster.getName(), 404);
        }
        return effectiveRefresh(refresh, Instant.now());
    }

    private void completeRefresh(String key, ClusterState state, InsightsRefresh started) {
        List<Insight> evaluated = null;
        InsightsRefresh finished;
        try {
            List<Insight> previous = storage.get(key).map(StoredInsights::insights).orElse(null);
            evaluated = evaluate(state, clusterManager.readDeprecatedApiMetrics(state.cluster()).orElse(null),
                    previous, Instant.now());
            finished = new InsightsRefresh("Insights refresh completed.", COMPLETED, started.startedAt(),
                    epochSeconds(Instant.now()));
        } catch (RuntimeException e) {
            LOG.warnv("Insights refresh failed for EKS cluster {0}: {1}", state.cluster().getName(), e.getMessage());
            finished = new InsightsRefresh("Insights refresh failed: " + e.getMessage(), FAILED,
                    started.startedAt(), epochSeconds(Instant.now()));
        }
        synchronized (this) {
            StoredInsights stored = storage.get(key).orElse(null);
            if (stored == null || stored.refresh() == null
                    || !Objects.equals(stored.refresh().startedAt(), started.startedAt())) {
                return;
            }
            storage.put(key, new StoredInsights(evaluated != null ? evaluated : stored.insights(), finished));
        }
    }

    private List<Insight> currentInsights(Cluster cluster) {
        String key = key(cluster);
        Instant now = Instant.now();
        StoredInsights stored = storage.get(key).orElse(null);
        List<Insight> previous = stored == null ? null : stored.insights();
        if (previous != null && !scheduledRefreshDue(previous, now)) {
            return previous;
        }
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            return previous != null ? previous : List.of();
        }
        List<Insight> evaluated = evaluate(snapshot(cluster),
                clusterManager.readDeprecatedApiMetrics(cluster).orElse(null), previous, now);
        synchronized (this) {
            StoredInsights latest = storage.get(key).orElse(null);
            if (latest != null && latest.insights() != null && !scheduledRefreshDue(latest.insights(), now)) {
                return latest.insights();
            }
            storage.put(key, new StoredInsights(evaluated, latest == null ? null : latest.refresh()));
        }
        return evaluated;
    }

    private static boolean scheduledRefreshDue(List<Insight> insights, Instant now) {
        Double lastRefresh = insights.stream().map(Insight::lastRefreshTime).filter(Objects::nonNull)
                .findFirst().orElse(null);
        return lastRefresh == null
                || now.toEpochMilli() / 1000.0 - lastRefresh >= SCHEDULED_REFRESH_INTERVAL.toSeconds();
    }

    private static InsightsRefresh effectiveRefresh(InsightsRefresh refresh, Instant now) {
        if (refresh != null && IN_PROGRESS.equals(refresh.status()) && refresh.startedAt() != null
                && now.toEpochMilli() / 1000.0 - refresh.startedAt() > REFRESH_TIMEOUT.toSeconds()) {
            return new InsightsRefresh("Insights refresh did not complete.", FAILED, refresh.startedAt(), null);
        }
        return refresh;
    }

    private ClusterState snapshot(Cluster cluster) {
        List<Addon> installed = addons == null ? List.of() : addons.clusterAddons(cluster);
        List<Nodegroup> nodegroups = new ArrayList<>();
        if (clusters != null) {
            for (String nodegroupName : clusters.listNodeGroups(cluster.getName())) {
                nodegroups.add(clusters.describeNodeGroup(cluster.getName(), nodegroupName));
            }
        }
        return new ClusterState(cluster, installed, nodegroups);
    }

    /** {@code deprecatedApiMetrics} is null when the cluster's API server metrics are unavailable. */
    List<Insight> evaluate(ClusterState state, String deprecatedApiMetrics, List<Insight> previous,
                           Instant now) {
        String nextVersion = nextMinorVersion(state.cluster().getVersion());
        if (nextVersion == null) {
            return List.of();
        }
        Map<String, Insight> priorById = new HashMap<>();
        if (previous != null) {
            for (Insight insight : previous) {
                priorById.put(insight.id(), insight);
            }
        }
        double refreshedAt = epochSeconds(now);
        List<Insight> insights = new ArrayList<>();
        insights.add(deprecatedApisInsight(state, deprecatedApiMetrics, nextVersion, priorById, refreshedAt));
        insights.add(addonCompatibilityInsight(state, nextVersion, priorById, refreshedAt));
        insights.add(kubeletSkewInsight(state, nextVersion, priorById, refreshedAt));
        return List.copyOf(insights);
    }

    private Insight deprecatedApisInsight(ClusterState state, String metrics, String nextVersion,
                                          Map<String, Insight> prior, double now) {
        InsightStatus status;
        List<DeprecationDetail> details = List.of();
        if (metrics == null) {
            status = new InsightStatus("UNKNOWN", "Kubernetes API server metrics are not available for this cluster.");
        } else {
            Map<String, DeprecationDetail> byUsage = new LinkedHashMap<>();
            for (DeprecatedApiUsage usage : parseDeprecatedApiUsage(metrics)) {
                if (nextVersion.equals(usage.removedRelease())) {
                    byUsage.putIfAbsent(usage.usage(),
                            new DeprecationDetail(usage.usage(), null, usage.removedRelease(), null));
                }
            }
            details = List.copyOf(byUsage.values());
            status = details.isEmpty()
                    ? new InsightStatus("PASSING", "No deprecated API usage detected.")
                    : new InsightStatus("ERROR", "Deprecated API usage detected.");
        }
        return insight(state, "deprecated-apis", nextVersion,
                "Deprecated APIs removed in Kubernetes v" + nextVersion,
                "Checks for usage of deprecated APIs that are scheduled for removal in Kubernetes v" + nextVersion
                        + ". Upgrading your cluster before migrating to the updated APIs could cause application impact.",
                status,
                "Update manifests and API clients to use newer Kubernetes APIs if applicable before upgrading to Kubernetes v"
                        + nextVersion + ".",
                List.of(), new InsightCategorySpecificSummary(details, null), prior, now);
    }

    private Insight addonCompatibilityInsight(ClusterState state, String nextVersion, Map<String, Insight> prior,
                                              double now) {
        InsightStatus status;
        List<AddonCompatibilityDetail> details = new ArrayList<>();
        List<InsightResourceDetail> resources = new ArrayList<>();
        if (!catalog.isClusterVersionInCatalog(nextVersion)) {
            status = new InsightStatus("UNKNOWN",
                    "Add-on compatibility data for Kubernetes v" + nextVersion + " is not available.");
        } else {
            for (Addon addon : state.addons()) {
                List<String> compatibleVersions = catalog.describeVersions(addon.addonName(), nextVersion,
                                null, null, null).stream()
                        .map(AddonInfo::addonVersions)
                        .flatMap(List::stream)
                        .map(AddonVersionInfo::addonVersion)
                        .toList();
                details.add(new AddonCompatibilityDetail(addon.addonName(), compatibleVersions));
                if (!catalog.isVersionSupported(addon.addonName(), addon.addonVersion(), nextVersion)) {
                    resources.add(new InsightResourceDetail(new InsightStatus("ERROR",
                            "Add-on version " + addon.addonVersion() + " is not compatible with Kubernetes v"
                                    + nextVersion + "."), null, addon.addonArn()));
                }
            }
            status = !resources.isEmpty()
                    ? new InsightStatus("ERROR", "One or more add-ons are not compatible with Kubernetes v"
                            + nextVersion + ".")
                    : new InsightStatus("PASSING", state.addons().isEmpty()
                            ? "No add-ons are installed."
                            : "All installed add-ons are compatible with Kubernetes v" + nextVersion + ".");
        }
        return insight(state, "addon-compatibility", nextVersion,
                "Amazon EKS add-on version compatibility",
                "Checks version of installed EKS add-ons to ensure they are compatible with the next version of Kubernetes.",
                status,
                "Upgrade Amazon EKS add-ons to versions compatible with Kubernetes v" + nextVersion
                        + " before upgrading the cluster.",
                List.copyOf(resources), new InsightCategorySpecificSummary(null, List.copyOf(details)), prior, now);
    }

    private Insight kubeletSkewInsight(ClusterState state, String nextVersion, Map<String, Insight> prior,
                                       double now) {
        int targetMinor = minorOf(nextVersion);
        List<InsightResourceDetail> resources = new ArrayList<>();
        for (Nodegroup nodegroup : state.nodegroups()) {
            Integer minor = minorOrNull(nodegroup.getVersion());
            if (minor != null && targetMinor - minor > KUBELET_MAX_SKEW) {
                resources.add(new InsightResourceDetail(new InsightStatus("ERROR",
                        "Nodegroup version " + nodegroup.getVersion() + " would exceed the kubelet version skew"
                                + " allowed by Kubernetes v" + nextVersion + "."),
                        null, nodegroup.getNodegroupArn()));
            }
        }
        InsightStatus status = !resources.isEmpty()
                ? new InsightStatus("ERROR", "Worker nodes would exceed the supported kubelet version skew.")
                : new InsightStatus("PASSING", state.nodegroups().isEmpty()
                        ? "No managed nodegroups are attached to the cluster."
                        : "All nodegroups are within the supported kubelet version skew.");
        return insight(state, "kubelet-version-skew", nextVersion,
                "Kubelet version skew",
                "Checks for kubelet versions of worker nodes in the cluster to see if upgrade would cause"
                        + " noncompliance with supported Kubernetes kubelet version skew policy.",
                status,
                "Upgrade your worker nodes to match the Kubernetes version of your cluster control plane.",
                List.copyOf(resources), null, prior, now);
    }

    private Insight insight(ClusterState state, String check, String nextVersion, String name, String description,
                            InsightStatus status, String recommendation, List<InsightResourceDetail> resources,
                            InsightCategorySpecificSummary summary, Map<String, Insight> prior, double now) {
        String id = UUID.nameUUIDFromBytes((key(state.cluster()) + "/" + check + "/" + nextVersion)
                .getBytes(StandardCharsets.UTF_8)).toString();
        Insight previous = prior.get(id);
        Double transitionedAt = previous != null && previous.insightStatus() != null
                && Objects.equals(previous.insightStatus().status(), status.status())
                ? previous.lastTransitionTime() : Double.valueOf(now);
        return new Insight(id, name, UPGRADE_READINESS, nextVersion, now, transitionedAt, description, status,
                recommendation, null, resources, summary);
    }

    static List<DeprecatedApiUsage> parseDeprecatedApiUsage(String metrics) {
        List<DeprecatedApiUsage> usages = new ArrayList<>();
        for (String line : metrics.split("\n")) {
            Matcher sample = DEPRECATED_API_SAMPLE.matcher(line.trim());
            if (!sample.find()) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(sample.group(2));
            } catch (NumberFormatException e) {
                LOG.debugv("Ignoring malformed deprecated API metric sample: {0}", line);
                continue;
            }
            if (!(value > 0)) {
                continue;
            }
            Map<String, String> labels = new HashMap<>();
            Matcher label = METRIC_LABEL.matcher(sample.group(1));
            while (label.find()) {
                labels.put(label.group(1), label.group(2));
            }
            String resource = labels.getOrDefault("resource", "");
            String version = labels.getOrDefault("version", "");
            if (resource.isEmpty() || version.isEmpty()) {
                continue;
            }
            usages.add(new DeprecatedApiUsage(labels.getOrDefault("group", ""), version, resource,
                    labels.getOrDefault("subresource", ""), labels.getOrDefault("removed_release", "")));
        }
        return usages;
    }

    static String nextMinorVersion(String version) {
        Integer minor = minorOrNull(version);
        return minor == null ? null : "1." + (minor + 1);
    }

    private static int minorOf(String version) {
        Integer minor = minorOrNull(version);
        return minor == null ? 0 : minor;
    }

    private static Integer minorOrNull(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = MINOR_VERSION.matcher(version.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static void validateFilter(ListInsightsRequest.Filter filter) {
        if (filter == null) {
            return;
        }
        if (filter.categories() != null && !CATEGORIES.containsAll(filter.categories())) {
            throw new AwsException("InvalidParameterException",
                    "filter.categories must contain only " + String.join(", ", CATEGORIES.stream().sorted().toList()), 400);
        }
        if (filter.statuses() != null && !STATUSES.containsAll(filter.statuses())) {
            throw new AwsException("InvalidParameterException",
                    "filter.statuses must contain only " + String.join(", ", STATUSES.stream().sorted().toList()), 400);
        }
    }

    private static boolean matches(Insight insight, ListInsightsRequest.Filter filter) {
        if (filter == null) {
            return true;
        }
        return (filter.categories() == null || filter.categories().isEmpty()
                        || filter.categories().contains(insight.category()))
                && (filter.kubernetesVersions() == null || filter.kubernetesVersions().isEmpty()
                        || filter.kubernetesVersions().contains(insight.kubernetesVersion()))
                && (filter.statuses() == null || filter.statuses().isEmpty()
                        || (insight.insightStatus() != null
                                && filter.statuses().contains(insight.insightStatus().status())));
    }

    private String accountOf(Cluster cluster) {
        return cluster.getAccountId() != null ? cluster.getAccountId() : regionResolver.getAccountId();
    }

    /** A recreated cluster must not inherit its predecessor's insights. */
    private static String key(Cluster cluster) {
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt());
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }
}

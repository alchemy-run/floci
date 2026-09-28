package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonHealth;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.Insight;
import io.github.hectorvent.floci.services.eks.model.InsightSummary;
import io.github.hectorvent.floci.services.eks.model.InsightsRefresh;
import io.github.hectorvent.floci.services.eks.model.ListInsightsRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksInsightsServiceTest {

    private static final String CLUSTER_ARN = "arn:aws:eks:us-east-1:123456789012:cluster/insights";
    private static final String DEPRECATED_METRICS = String.join("\n",
            "apiserver_requested_deprecated_apis{group=\"flowcontrol.apiserver.k8s.io\",removed_release=\"1.30\","
                    + "resource=\"flowschemas\",subresource=\"\",version=\"v1beta2\"} 1",
            "apiserver_requested_deprecated_apis{group=\"\",removed_release=\"1.30\",resource=\"pods\","
                    + "subresource=\"status\",version=\"v1\"} 1",
            "apiserver_requested_deprecated_apis{group=\"batch\",removed_release=\"1.35\",resource=\"jobs\","
                    + "subresource=\"\",version=\"v1beta9\"} 1",
            "apiserver_requested_deprecated_apis{group=\"policy\",removed_release=\"1.30\",resource=\"evictions\","
                    + "subresource=\"\",version=\"v1beta1\"} 0");

    @Test
    void parsesOnlyRequestedDeprecatedApiSamples() {
        List<EksInsightsService.DeprecatedApiUsage> usages =
                EksInsightsService.parseDeprecatedApiUsage("# HELP ignored\n" + DEPRECATED_METRICS);
        assertEquals(3, usages.size());
        assertEquals("/apis/flowcontrol.apiserver.k8s.io/v1beta2/flowschemas", usages.get(0).usage());
        assertEquals("/api/v1/pods/status", usages.get(1).usage());
        assertEquals("1.35", usages.get(2).removedRelease());
    }

    @Test
    void deprecatedApiInsightReportsUsageRemovedInTheNextVersion() {
        Fixture fixture = fixture(DEPRECATED_METRICS);
        Insight insight = byName(fixture.service.evaluate(state(fixture.cluster, List.of(), List.of()),
                DEPRECATED_METRICS, null, Instant.now()), "Deprecated APIs removed in Kubernetes v1.30");
        assertEquals("ERROR", insight.insightStatus().status());
        assertEquals("1.30", insight.kubernetesVersion());
        assertEquals("UPGRADE_READINESS", insight.category());
        assertEquals(List.of("/apis/flowcontrol.apiserver.k8s.io/v1beta2/flowschemas", "/api/v1/pods/status"),
                insight.categorySpecificSummary().deprecationDetails().stream().map(d -> d.usage()).toList());
    }

    @Test
    void deprecatedApiInsightPassesWithoutUsageAndIsUnknownWithoutMetrics() {
        Fixture fixture = fixture(null);
        EksInsightsService.ClusterState state = state(fixture.cluster, List.of(), List.of());
        assertEquals("PASSING", byName(fixture.service.evaluate(state, "", null, Instant.now()),
                "Deprecated APIs removed in Kubernetes v1.30").insightStatus().status());
        assertEquals("UNKNOWN", byName(fixture.service.evaluate(state, null, null, Instant.now()),
                "Deprecated APIs removed in Kubernetes v1.30").insightStatus().status());
    }

    @Test
    void addonCompatibilityReflectsTheCatalogForTheNextVersion() {
        Fixture fixture = fixture(null);
        Addon compatible = addon("vpc-cni", "v1.18.1-eksbuild.1");
        Addon incompatible = addon("vpc-cni", "v1.16.0-eksbuild.1");
        Insight passing = byName(fixture.service.evaluate(state(fixture.cluster, List.of(compatible), List.of()),
                null, null, Instant.now()), "Amazon EKS add-on version compatibility");
        assertEquals("PASSING", passing.insightStatus().status());
        assertTrue(passing.resources().isEmpty());
        assertEquals(List.of("v1.18.5-eksbuild.1", "v1.18.1-eksbuild.1"),
                passing.categorySpecificSummary().addonCompatibilityDetails().getFirst().compatibleVersions());

        Insight failing = byName(fixture.service.evaluate(state(fixture.cluster, List.of(incompatible), List.of()),
                null, null, Instant.now()), "Amazon EKS add-on version compatibility");
        assertEquals("ERROR", failing.insightStatus().status());
        assertEquals(incompatible.addonArn(), failing.resources().getFirst().arn());
    }

    @Test
    void kubeletSkewFlagsNodegroupsBeyondThreeMinorVersions() {
        Fixture fixture = fixture(null);
        fixture.cluster.setVersion("1.32");
        Nodegroup old = nodegroup("old", "1.29");
        Nodegroup recent = nodegroup("recent", "1.30");
        Insight insight = byName(fixture.service.evaluate(state(fixture.cluster, List.of(), List.of(old, recent)),
                null, null, Instant.now()), "Kubelet version skew");
        assertEquals("ERROR", insight.insightStatus().status());
        assertEquals(List.of(old.getNodegroupArn()), insight.resources().stream().map(r -> r.arn()).toList());
        assertEquals("PASSING", byName(fixture.service.evaluate(state(fixture.cluster, List.of(), List.of(recent)),
                null, null, Instant.now()), "Kubelet version skew").insightStatus().status());
    }

    @Test
    void listEvaluatesOnceWithStableIdsAndDescribeFindsThem() {
        Fixture fixture = fixture("");
        PaginatedResult<InsightSummary> page = fixture.service.list(fixture.cluster, null);
        assertEquals(3, page.items().size());
        PaginatedResult<InsightSummary> again = fixture.service.list(fixture.cluster,
                new ListInsightsRequest(null, null, null));
        assertEquals(page.items(), again.items());
        verify(fixture.clusterManager, times(1)).readDeprecatedApiMetrics(fixture.cluster);

        String id = page.items().getFirst().id();
        assertEquals(id, fixture.service.describe(fixture.cluster, id).id());
        AwsException missing = assertThrows(AwsException.class,
                () -> fixture.service.describe(fixture.cluster, "00000000-0000-0000-0000-000000000000"));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void listFiltersByStatusAndRejectsUnknownFilterValues() {
        Fixture fixture = fixture(null);
        PaginatedResult<InsightSummary> unknown = fixture.service.list(fixture.cluster,
                new ListInsightsRequest(new ListInsightsRequest.Filter(null, List.of("1.30"), List.of("UNKNOWN")),
                        null, null));
        assertEquals(List.of("Deprecated APIs removed in Kubernetes v1.30"),
                unknown.items().stream().map(InsightSummary::name).toList());
        AwsException invalid = assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster,
                new ListInsightsRequest(new ListInsightsRequest.Filter(List.of("BOGUS"), null, null), null, null)));
        assertEquals("InvalidParameterException", invalid.getErrorCode());
    }

    @Test
    void nonActiveClusterHasNoInsightsAndCannotRefresh() {
        Fixture fixture = fixture(null);
        fixture.cluster.setStatus(ClusterStatus.CREATING);
        assertTrue(fixture.service.list(fixture.cluster, null).items().isEmpty());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.startRefresh(fixture.cluster)).getErrorCode());
    }

    @Test
    void refreshRunsAsynchronouslyAndRejectsAConcurrentRefresh() {
        Fixture fixture = fixture(DEPRECATED_METRICS);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.service.describeRefresh(fixture.cluster)).getErrorCode());

        InsightsRefresh started = fixture.service.startRefresh(fixture.cluster);
        assertEquals("IN_PROGRESS", started.status());
        assertEquals("IN_PROGRESS", fixture.service.describeRefresh(fixture.cluster).status());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.startRefresh(fixture.cluster)).getErrorCode());

        fixture.pending.removeFirst().run();
        InsightsRefresh completed = fixture.service.describeRefresh(fixture.cluster);
        assertEquals("COMPLETED", completed.status());
        assertNotNull(completed.startedAt());
        assertNotNull(completed.endedAt());
        assertEquals("ERROR", fixture.service.list(fixture.cluster, null).items().stream()
                .filter(summary -> summary.name().startsWith("Deprecated APIs")).findFirst().orElseThrow()
                .insightStatus().status());
        assertEquals("IN_PROGRESS", fixture.service.startRefresh(fixture.cluster).status());
    }

    private static Insight byName(List<Insight> insights, String name) {
        return insights.stream().filter(insight -> insight.name().equals(name)).findFirst().orElseThrow();
    }

    private static EksInsightsService.ClusterState state(Cluster cluster, List<Addon> addons,
                                                         List<Nodegroup> nodegroups) {
        return new EksInsightsService.ClusterState(cluster, addons, nodegroups);
    }

    private static Addon addon(String name, String version) {
        return new Addon("arn:aws:eks:us-east-1:123456789012:addon/insights/" + name + "/" + version, name, version,
                "insights", "ACTIVE", new AddonHealth(List.of()), 1.0, 1.0, null, null, Map.of(), List.of(),
                "aws", "eks");
    }

    private static Nodegroup nodegroup(String name, String version) {
        Nodegroup nodegroup = new Nodegroup();
        nodegroup.setNodegroupName(name);
        nodegroup.setNodegroupArn("arn:aws:eks:us-east-1:123456789012:nodegroup/insights/" + name + "/id");
        nodegroup.setVersion(version);
        return nodegroup;
    }

    /** {@code metrics} null means the cluster has no readable API server metrics. */
    private static Fixture fixture(String metrics) {
        Cluster cluster = new Cluster();
        cluster.setName("insights");
        cluster.setArn(CLUSTER_ARN);
        cluster.setCreatedAt(Instant.parse("2026-09-20T12:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setVersion("1.29");
        cluster.setAccountId("123456789012");

        EksService clusters = mock(EksService.class);
        when(clusters.listNodeGroups("insights")).thenReturn(List.of());
        EksAddonService addons = mock(EksAddonService.class);
        when(addons.clusterAddons(cluster)).thenReturn(List.of());
        EksClusterManager clusterManager = mock(EksClusterManager.class);
        when(clusterManager.readDeprecatedApiMetrics(cluster)).thenReturn(Optional.ofNullable(metrics));
        List<Runnable> pending = new ArrayList<>();
        EksInsightsService service = new EksInsightsService(new InMemoryStorage<>(), clusters, addons,
                new EksAddonCatalog(), clusterManager, new RegionResolver("us-east-1", "123456789012"), pending::add);
        return new Fixture(cluster, clusterManager, pending, service);
    }

    private record Fixture(Cluster cluster, EksClusterManager clusterManager, List<Runnable> pending,
                           EksInsightsService service) {}
}

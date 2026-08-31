package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dashboard;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CloudWatch dashboards are account-global (ARN region is empty).
 * {@code putDashboard} is a pure upsert; {@code deleteDashboards} is idempotent.
 * Looked up from handlers via CDI, so the bean must remain {@code @Unremovable}.
 */
@ApplicationScoped
@Unremovable
public class CloudWatchDashboardService {

    private static final Logger LOG = Logger.getLogger(CloudWatchDashboardService.class);

    private final StorageBackend<String, Dashboard> dashboardStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchDashboardService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.dashboardStore = storageFactory.create("cloudwatchmetrics", "cwdashboards.json",
                new TypeReference<Map<String, Dashboard>>() {});
        this.regionResolver = regionResolver;
    }

    CloudWatchDashboardService(StorageBackend<String, Dashboard> dashboardStore, RegionResolver regionResolver) {
        this.dashboardStore = dashboardStore;
        this.regionResolver = regionResolver;
    }

    public Dashboard putDashboard(String dashboardName, String dashboardBody) {
        if (dashboardName == null || dashboardName.isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "DashboardName is a required parameter.", 400);
        }
        if (dashboardBody == null) {
            throw new AwsException("MissingRequiredParameterException",
                    "DashboardBody is a required parameter.", 400);
        }
        Dashboard dashboard = new Dashboard();
        dashboard.setDashboardName(dashboardName);
        dashboard.setDashboardArn(regionResolver.buildArn("cloudwatch", "", "dashboard/" + dashboardName));
        dashboard.setDashboardBody(dashboardBody);
        dashboard.setLastModified(Instant.now().getEpochSecond());
        dashboard.setSize(dashboardBody.getBytes(StandardCharsets.UTF_8).length);
        dashboardStore.put(dashboardName, dashboard);
        LOG.infov("PutDashboard: {0}", dashboardName);
        return dashboard;
    }

    public Dashboard getDashboard(String dashboardName) {
        if (dashboardName == null || dashboardName.isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "DashboardName is a required parameter.", 400);
        }
        return dashboardStore.get(dashboardName).orElseThrow(() ->
                new AwsException("DashboardNotFoundError",
                        "Dashboard " + dashboardName + " does not exist", 404));
    }

    public List<Dashboard> listDashboards(String namePrefix) {
        List<Dashboard> all = new ArrayList<>(dashboardStore.values());
        if (namePrefix != null && !namePrefix.isBlank()) {
            all = all.stream()
                    .filter(d -> d.getDashboardName() != null && d.getDashboardName().startsWith(namePrefix))
                    .collect(Collectors.toList());
        }
        all.sort(Comparator.comparing(Dashboard::getDashboardName, Comparator.nullsLast(String::compareTo)));
        return all;
    }

    public void deleteDashboards(List<String> dashboardNames) {
        if (dashboardNames == null) {
            return;
        }
        for (String name : dashboardNames) {
            if (name != null && !name.isBlank()) {
                dashboardStore.delete(name);
            }
        }
        LOG.infov("Deleted dashboards: {0}", dashboardNames);
    }
}

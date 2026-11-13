package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * X-Ray insights: every interval, each group with insights enabled compares each service's faults over the interval
 * with the service's own baseline (its fault rate over the previous intervals). A service whose faults rise well above
 * its baseline opens an insight; the insight records an event for each interval it stays anomalous and closes once the
 * service's faults return to normal. Groups with notifications enabled announce each insight change to EventBridge
 * ({@code aws.xray}, {@code AWS X-Ray Insight Update}).
 */
@ApplicationScoped
public class XRayInsights {

    private static final Logger LOG = Logger.getLogger(XRayInsights.class);
    private static final int BASELINE_WINDOWS = 10;
    private static final int MIN_FAULTS = 3;
    private static final double MIN_RATE = 0.3;
    private static final int CLOSE_AFTER_NORMAL_WINDOWS = 2;

    private final XRayService xray;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<EventBridgeService> events;
    private final Map<String, Deque<double[]>> baselines = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xray-insights");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public XRayInsights(XRayService xray, ObjectMapper mapper, EmulatorConfig config,
                        Provider<EventBridgeService> events) {
        this.xray = xray;
        this.mapper = mapper;
        this.config = config;
        this.events = events;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!config.services().xray().enabled()) {
            return;
        }
        long interval = config.services().xray().insightsIntervalMillis();
        executor.scheduleWithFixedDelay(this::evaluate, interval, interval, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    private void evaluate() {
        try {
            Set<String> scopes = new HashSet<>();
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : xray.allStateEntries("group/")) {
                scopes.add(entry.accountId() + "|" + entry.key().substring(0, entry.key().indexOf('/')));
            }
            for (String scope : scopes) {
                String account = scope.substring(0, scope.indexOf('|'));
                String region = scope.substring(scope.indexOf('|') + 1);
                RequestScopes.runAs(account, region, () -> evaluate(region));
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "X-Ray insight evaluation failed");
        }
    }

    private void evaluate(String region) {
        double now = XRayService.now();
        double window = config.services().xray().insightsIntervalMillis() / 1000.0;
        List<XRayTraces.Trace> recent = xray.tracesBetween(region, now - window, now);
        for (ObjectNode group : xray.insightGroups(region)) {
            Predicate<XRayTraces.Scope> filter = xray.groupFilter(region, group);
            Map<String, long[]> byService = new LinkedHashMap<>();
            Map<String, XRayTraces.Node> sample = new LinkedHashMap<>();
            long clientFaults = 0;
            long clientOk = 0;
            for (XRayTraces.Trace trace : recent) {
                if (!filter.test(XRayTraces.Scope.of(trace))) {
                    continue;
                }
                XRayTraces.Node root = trace.root();
                if (root != null) {
                    if (root.fault) {
                        clientFaults++;
                    } else {
                        clientOk++;
                    }
                }
                for (XRayTraces.Node segment : trace.segments) {
                    if (segment.end < now - window) {
                        continue;
                    }
                    String key = segment.name + "|" + XRayTraces.type(segment);
                    long[] counts = byService.computeIfAbsent(key, k -> new long[2]);
                    counts[1]++;
                    if (segment.fault) {
                        counts[0]++;
                    }
                    sample.putIfAbsent(key, segment);
                }
            }
            String groupArn = group.path("GroupARN").asText();
            Set<String> anomalous = new HashSet<>();
            for (Map.Entry<String, long[]> service : byService.entrySet()) {
                long faults = service.getValue()[0];
                long total = service.getValue()[1];
                double rate = total == 0 ? 0 : faults / (double) total;
                Deque<double[]> history = baselines.computeIfAbsent(xray.account() + "/" + groupArn + "/"
                        + service.getKey(), k -> new ArrayDeque<>());
                double baseline = history.stream().mapToDouble(h -> h[0]).sum()
                        / Math.max(1, history.stream().mapToDouble(h -> h[1]).sum());
                if (faults >= MIN_FAULTS && rate >= MIN_RATE && rate > 3 * baseline) {
                    anomalous.add(service.getKey());
                    open(region, group, sample.get(service.getKey()), faults, total - faults, clientFaults, clientOk);
                }
                history.addLast(new double[]{faults, total});
                while (history.size() > BASELINE_WINDOWS) {
                    history.removeFirst();
                }
            }
            for (ObjectNode insight : xray.insightsOf(region)) {
                if (!groupArn.equals(insight.path("GroupARN").asText())
                        || !"ACTIVE".equals(insight.path("State").asText())) {
                    continue;
                }
                String key = insight.path("_service").asText();
                if (anomalous.contains(key)) {
                    continue;
                }
                int normal = insight.path("_normal").asInt() + 1;
                insight.put("_normal", normal);
                if (normal >= CLOSE_AFTER_NORMAL_WINDOWS) {
                    insight.put("State", "CLOSED").put("EndTime", now).put("LastUpdateTime", now);
                    event(insight, "The faults of " + insight.path("RootCauseServiceId").path("Name").asText()
                            + " returned to normal.", 0, 0, clientFaults, clientOk);
                    notify(region, group, insight);
                }
                xray.saveInsight(region, insight);
            }
        }
    }

    private void open(String region, ObjectNode group, XRayTraces.Node service, long faults, long ok,
                      long clientFaults, long clientOk) {
        String key = service.name + "|" + XRayTraces.type(service);
        ObjectNode insight = null;
        for (ObjectNode existing : xray.insightsOf(region)) {
            if (group.path("GroupARN").asText().equals(existing.path("GroupARN").asText())
                    && "ACTIVE".equals(existing.path("State").asText()) && key.equals(existing.path("_service")
                    .asText())) {
                insight = existing;
            }
        }
        double now = XRayService.now();
        boolean created = insight == null;
        if (created) {
            insight = mapper.createObjectNode().put("InsightId", UUID.randomUUID().toString())
                    .put("GroupARN", group.path("GroupARN").asText()).put("GroupName", group.path("GroupName").asText());
            insight.set("RootCauseServiceId", XRayTraces.serviceId(mapper, service, xray.account()));
            insight.putArray("Categories").add("FAULT");
            insight.put("State", "ACTIVE").put("StartTime", now)
                    .put("Summary", service.name + " is returning more faults than usual.");
            ArrayNode anomalous = insight.putArray("TopAnomalousServices");
            anomalous.addObject().set("ServiceId", XRayTraces.serviceId(mapper, service, xray.account()));
            insight.put("_service", key);
            insight.putArray("_events");
        }
        insight.put("_normal", 0).put("LastUpdateTime", now);
        insight.set("ClientRequestImpactStatistics", impact(clientFaults, clientOk));
        insight.set("RootCauseServiceRequestImpactStatistics", impact(faults, ok));
        event(insight, created ? service.name + " started returning more faults than usual."
                : service.name + " is still returning more faults than usual.", faults, ok, clientFaults, clientOk);
        xray.saveInsight(region, insight);
        notify(region, group, insight);
    }

    private void event(ObjectNode insight, String summary, long faults, long ok, long clientFaults, long clientOk) {
        ArrayNode events = (ArrayNode) insight.withArray("_events");
        ObjectNode event = events.insertObject(0).put("Summary", summary).put("EventTime", XRayService.now());
        event.set("ClientRequestImpactStatistics", impact(clientFaults, clientOk));
        event.set("RootCauseServiceRequestImpactStatistics", impact(faults, ok));
        event.set("TopAnomalousServices", insight.path("TopAnomalousServices").deepCopy());
        while (events.size() > 500) {
            events.remove(events.size() - 1);
        }
    }

    private ObjectNode impact(long faults, long ok) {
        return mapper.createObjectNode().put("FaultCount", faults).put("OkCount", ok).put("TotalCount", faults + ok);
    }

    private void notify(String region, ObjectNode group, ObjectNode insight) {
        if (!group.path("InsightsConfiguration").path("NotificationsEnabled").asBoolean(false)) {
            return;
        }
        ObjectNode detail = xray.insightView(insight, true);
        if (!detail.has("EndTime")) {
            detail.putNull("EndTime");
        }
        events.get().publishServiceEvent("aws.xray", "AWS X-Ray Insight Update", detail.toString(),
                insight.path("GroupARN").asText(), region);
    }
}

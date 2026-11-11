package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iot.IotService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;

/**
 * What SiteWise computes from data: transforms, evaluated whenever one of their inputs gets a value, at that value's
 * time; metrics, evaluated over each tumbling window once it ends (and again when late data lands in a window
 * already computed), with hierarchy variables reading every child asset; and property notifications, the MQTT
 * messages a property with notifications ENABLED publishes on its topic for each value it gets.
 */
@ApplicationScoped
public class SiteWiseCompute {

    private static final Logger LOG = Logger.getLogger(SiteWiseCompute.class);
    private static final int MAX_WINDOWS_PER_TICK = 50;

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<SiteWiseAssets> assets;
    private final Instance<SiteWiseModels> models;
    private final Instance<SiteWiseData> data;
    private final Instance<IotService> iot;
    /** For each metric of each asset, {@code account|region|asset|property}: the end of its last computed window. */
    private final Map<String, Long> computed = new ConcurrentHashMap<>();
    /** Window ends of metrics computed already that late data changed. */
    private final Map<String, Set<Long>> dirty = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Inject
    public SiteWiseCompute(SiteWiseStore store, Instance<SiteWiseAssets> assets, Instance<SiteWiseModels> models,
                           Instance<SiteWiseData> data, Instance<IotService> iot) {
        this.store = store;
        this.mapper = store.mapper();
        this.assets = assets;
        this.models = models;
        this.data = data;
        this.iot = iot;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iotsitewise-metrics");
            thread.setDaemon(true);
            return thread;
        });
        long tick = Math.max(100, store.config().metricTickMillis());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                LOG.warnv(e, "IoT SiteWise could not compute metrics");
            }
        }, tick, tick, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // ---------------------------------------------------------------- triggers

    /** New points of an asset property: publish them, run the transforms they feed, and mark late metric windows. */
    void ingested(String account, String region, ObjectNode asset, ObjectNode property, List<ObjectNode> points) {
        start();
        notify(account, region, asset, property, points);
        ObjectNode model = models.get().find(account, region, asset.get("assetModelId").asText());
        if (model == null) {
            return;
        }
        String propertyId = property.get("id").asText();
        for (ObjectNode definition : SiteWiseModels.allProperties(model)) {
            JsonNode transform = definition.path("type").path("transform");
            if (!transform.isMissingNode() && uses(transform, propertyId, null)) {
                transform(account, region, asset, definition, (ObjectNode) transform, propertyId, points);
            }
            JsonNode metric = definition.path("type").path("metric");
            if (!metric.isMissingNode() && uses(metric, propertyId, null)) {
                late(account, region, asset, definition, points);
            }
        }
        JsonNode parent = meta(asset).path("parent");
        if (parent.hasNonNull("assetId")) {
            ObjectNode parentAsset = assets.get().find(account, region, parent.get("assetId").asText());
            ObjectNode parentModel = parentAsset == null ? null
                    : models.get().find(account, region, parentAsset.get("assetModelId").asText());
            if (parentModel != null) {
                for (ObjectNode definition : SiteWiseModels.allProperties(parentModel)) {
                    JsonNode metric = definition.path("type").path("metric");
                    if (!metric.isMissingNode() && uses(metric, propertyId, parent.get("hierarchyId").asText())) {
                        late(account, region, parentAsset, definition, points);
                    }
                }
            }
        }
    }

    private static boolean uses(JsonNode formula, String propertyId, String hierarchyId) {
        for (JsonNode variable : formula.path("variables")) {
            JsonNode value = variable.path("value");
            if (propertyId.equals(value.path("propertyId").asText())
                    && Objects.equals(hierarchyId, value.path("hierarchyId").asText(null))) {
                return true;
            }
        }
        return false;
    }

    private void late(String account, String region, ObjectNode asset, ObjectNode definition, List<ObjectNode> points) {
        String key = key(account, region, asset.get("assetId").asText(), definition.get("id").asText());
        Long last = computed.get(key);
        if (last == null) {
            return;
        }
        SiteWiseWindows window = SiteWiseWindows.parse(definition.path("type").path("metric").path("window")
                .path("tumbling"));
        for (ObjectNode point : points) {
            long end = window.endOf((long) Math.ceil(SiteWiseData.time(point) * 1000));
            if (end <= last) {
                dirty.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(end);
            }
        }
    }

    private static String key(String account, String region, String assetId, String propertyId) {
        return account + "|" + region + "|" + assetId + "|" + propertyId;
    }

    // ---------------------------------------------------------------- notifications

    void notify(String account, String region, ObjectNode asset, ObjectNode property, List<ObjectNode> points) {
        if (!"ENABLED".equals(property.path("notification").path("state").asText())) {
            return;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("type", "PropertyValueUpdate");
        ObjectNode payload = message.putObject("payload");
        payload.put("assetId", asset.get("assetId").asText());
        payload.put("propertyId", property.get("id").asText());
        ArrayNode values = payload.putArray("values");
        points.forEach(p -> values.add(data.get().value(p)));
        String topic = property.path("notification").path("topic").asText();
        try {
            RequestScopes.runAs(account, region, () -> iot.get().publish(topic,
                    message.toString().getBytes(StandardCharsets.UTF_8), false, 1, region, null));
        } catch (RuntimeException e) {
            LOG.debugv(e, "IoT SiteWise could not publish to {0}", topic);
        }
    }

    // ---------------------------------------------------------------- transforms

    private void transform(String account, String region, ObjectNode asset, ObjectNode definition,
                           ObjectNode transform, String trigger, List<ObjectNode> points) {
        SiteWiseFormula.Node node = SiteWiseFormula.parse(transform.get("expression").asText());
        Map<String, String> variables = new HashMap<>();
        transform.path("variables").forEach(v -> variables.put(v.get("name").asText(),
                v.path("value").path("propertyId").asText()));
        Map<String, List<JsonNode>> series = new HashMap<>();
        for (String propertyId : new HashSet<>(variables.values())) {
            series.put(propertyId, good(account, region, asset.get("assetId").asText(), propertyId));
        }
        List<ObjectNode> outputs = new ArrayList<>();
        for (ObjectNode point : points) {
            if (!"GOOD".equals(point.path("q").asText("GOOD"))) {
                continue;
            }
            double at = SiteWiseData.time(point);
            SiteWiseFormula.Context context = new SiteWiseFormula.Context() {
                @Override
                public Object value(String variable) {
                    JsonNode latest = latest(series.get(variables.get(variable)), at);
                    return latest == null ? null : SiteWiseData.number(latest.get("v"));
                }

                @Override
                public double timestamp(String variable) {
                    JsonNode latest = latest(series.get(variables.get(variable)), at);
                    return latest == null ? at : SiteWiseData.time(latest);
                }

                @Override
                public SiteWiseFormula.Point pretrigger(String variable) {
                    List<JsonNode> values = series.get(variables.get(variable));
                    double before = trigger.equals(variables.get(variable)) ? Math.nextDown(at) : at;
                    JsonNode latest = latest(values, before);
                    return latest == null ? null
                            : new SiteWiseFormula.Point(SiteWiseData.time(latest), SiteWiseData.number(latest.get("v")));
                }

                @Override
                public double now() {
                    return at;
                }
            };
            ObjectNode variant = evaluate(node, context, definition.get("dataType").asText());
            if (variant != null) {
                outputs.add(data.get().point(point.get("t").asLong(), point.get("n").asInt(), "GOOD", variant));
            }
        }
        if (!outputs.isEmpty()) {
            ObjectNode property = assets.get().property(asset, definition.get("id").asText());
            data.get().record(account, region, asset, property, outputs);
        }
    }

    /** The expression's value as a Variant of the output type, or null for no output. */
    private ObjectNode evaluate(SiteWiseFormula.Node node, SiteWiseFormula.Context context, String dataType) {
        Object result;
        try {
            result = node.eval(context);
        } catch (RuntimeException e) {
            return null;
        }
        if (result == null || result == SiteWiseFormula.NONE || !(result instanceof Double || result instanceof String)) {
            return null;
        }
        ObjectNode variant = mapper.createObjectNode();
        if ("STRING".equals(dataType)) {
            variant.put("stringValue", SiteWiseFormula.string(result));
            return variant;
        }
        if (!(result instanceof Double number) || number.isNaN() || number.isInfinite()) {
            return null;
        }
        variant.put("doubleValue", number);
        return variant;
    }

    private List<JsonNode> good(String account, String region, String assetId, String propertyId) {
        List<JsonNode> good = new ArrayList<>();
        for (JsonNode point : data.get().points(account, region, SiteWiseData.associatedId(assetId, propertyId))) {
            if ("GOOD".equals(point.path("q").asText("GOOD")) && SiteWiseData.number(point.get("v")) != null) {
                good.add(point);
            }
        }
        return good;
    }

    private static JsonNode latest(List<JsonNode> points, double at) {
        JsonNode latest = null;
        if (points != null) {
            for (JsonNode point : points) {
                if (SiteWiseData.time(point) <= at) {
                    latest = point;
                } else {
                    break;
                }
            }
        }
        return latest;
    }

    // ---------------------------------------------------------------- metrics

    /** Computes every metric window that has ended since the last tick, children before parents. */
    void tick() {
        long now = System.currentTimeMillis();
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> entries =
                store.everywhere(key -> key.contains("/" + SiteWiseStore.ASSET + "/"));
        Map<String, Integer> depth = new HashMap<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : entries) {
            depth.put(entry.accountId() + "|" + entry.value().get("assetId").asText(), depth(entries, entry));
        }
        entries.sort((a, b) -> Integer.compare(depth.get(b.accountId() + "|" + b.value().get("assetId").asText()),
                depth.get(a.accountId() + "|" + a.value().get("assetId").asText())));
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : entries) {
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            String account = entry.accountId();
            RequestScopes.runAs(account, region, () -> {
                ObjectNode asset = assets.get().find(account, region, entry.value().get("assetId").asText());
                if (asset == null || !"ACTIVE".equals(asset.path("assetStatus").path("state").asText())
                        && !"UPDATING".equals(asset.path("assetStatus").path("state").asText())) {
                    return;
                }
                ObjectNode model = models.get().find(account, region, asset.get("assetModelId").asText());
                if (model != null) {
                    metrics(account, region, asset, model, now);
                }
            });
        }
    }

    private static int depth(List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> entries,
                             AccountAwareStorageBackend.AccountEntry<ObjectNode> entry) {
        int depth = 0;
        String parent = meta(entry.value()).path("parent").path("assetId").asText(null);
        Set<String> seen = new HashSet<>();
        while (parent != null && seen.add(parent) && depth < 100) {
            depth++;
            String next = null;
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> other : entries) {
                if (other.accountId().equals(entry.accountId()) && parent.equals(other.value().get("assetId").asText())) {
                    next = meta(other.value()).path("parent").path("assetId").asText(null);
                }
            }
            parent = next;
        }
        return depth;
    }

    private void metrics(String account, String region, ObjectNode asset, ObjectNode model, long now) {
        List<ObjectNode> metrics = new ArrayList<>();
        for (ObjectNode definition : SiteWiseModels.allProperties(model)) {
            if (definition.path("type").has("metric") && definition.path("type").path("metric").hasNonNull("expression")) {
                metrics.add(definition);
            }
        }
        for (ObjectNode definition : order(metrics)) {
            JsonNode metric = definition.path("type").path("metric");
            SiteWiseWindows window = SiteWiseWindows.parse(metric.path("window").path("tumbling"));
            String key = key(account, region, asset.get("assetId").asText(), definition.get("id").asText());
            long created = (long) (asset.get("assetCreationDate").asDouble() * 1000);
            long last = computed.computeIfAbsent(key, k -> window.endOf(created) - window.intervalMillis());
            TreeSet<Long> ends = new TreeSet<>();
            Set<Long> late = dirty.remove(key);
            if (late != null) {
                ends.addAll(late);
            }
            long end = last + window.intervalMillis();
            while (end <= now) {
                ends.add(end);
                last = end;
                end += window.intervalMillis();
            }
            while (ends.size() > MAX_WINDOWS_PER_TICK) {
                ends.pollFirst();
            }
            computed.put(key, last);
            ObjectNode property;
            try {
                property = assets.get().property(asset, definition.get("id").asText());
            } catch (RuntimeException e) {
                continue;
            }
            List<ObjectNode> outputs = new ArrayList<>();
            for (long windowEnd : ends) {
                ObjectNode variant = metric(account, region, asset, (ObjectNode) metric, definition, window, windowEnd);
                if (variant != null) {
                    outputs.add(data.get().point(Math.floorDiv(windowEnd, 1000L),
                            (int) Math.floorMod(windowEnd, 1000L) * 1_000_000, "GOOD", variant));
                }
            }
            if (!outputs.isEmpty()) {
                data.get().record(account, region, asset, property, outputs);
            }
        }
    }

    /** Metrics ordered so that each comes after the metrics it reads. */
    private static List<ObjectNode> order(List<ObjectNode> metrics) {
        Map<String, ObjectNode> byId = new HashMap<>();
        metrics.forEach(m -> byId.put(m.get("id").asText(), m));
        List<ObjectNode> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (int pass = 0; pass <= metrics.size() && ordered.size() < metrics.size(); pass++) {
            for (ObjectNode metric : metrics) {
                String id = metric.get("id").asText();
                if (placed.contains(id)) {
                    continue;
                }
                boolean ready = true;
                for (JsonNode variable : metric.path("type").path("metric").path("variables")) {
                    String target = variable.path("value").path("propertyId").asText();
                    if (!variable.path("value").hasNonNull("hierarchyId") && byId.containsKey(target)
                            && !placed.contains(target)) {
                        ready = false;
                    }
                }
                if (ready || pass == metrics.size()) {
                    ordered.add(metric);
                    placed.add(id);
                }
            }
        }
        return ordered;
    }

    private ObjectNode metric(String account, String region, ObjectNode asset, ObjectNode metric, ObjectNode definition,
                              SiteWiseWindows window, long windowEnd) {
        double end = windowEnd / 1000.0;
        double start = (windowEnd - window.intervalMillis()) / 1000.0;
        Map<String, JsonNode> variables = new HashMap<>();
        metric.path("variables").forEach(v -> variables.put(v.get("name").asText(), v.get("value")));
        ObjectNode model = models.get().find(account, region, asset.get("assetModelId").asText());
        Map<String, List<JsonNode>> cache = new HashMap<>();
        SiteWiseFormula.Context context = new SiteWiseFormula.Context() {
            private List<JsonNode> series(String variable) {
                return cache.computeIfAbsent(variable, name -> {
                    JsonNode value = variables.get(name);
                    if (value == null) {
                        return List.of();
                    }
                    String propertyId = value.path("propertyId").asText();
                    if (!value.hasNonNull("hierarchyId")) {
                        return good(account, region, asset.get("assetId").asText(), propertyId);
                    }
                    List<JsonNode> pooled = new ArrayList<>();
                    for (ObjectNode child : assets.get().children(account, region, asset.get("assetId").asText(),
                            value.get("hierarchyId").asText())) {
                        pooled.addAll(good(account, region, child.get("assetId").asText(), propertyId));
                    }
                    pooled.sort((a, b) -> Long.compare(SiteWiseData.nanos(a), SiteWiseData.nanos(b)));
                    return pooled;
                });
            }

            private boolean isMetric(String variable) {
                JsonNode value = variables.get(variable);
                if (value == null || value.hasNonNull("hierarchyId") || model == null) {
                    return false;
                }
                for (ObjectNode property : SiteWiseModels.allProperties(model)) {
                    if (property.get("id").asText().equals(value.path("propertyId").asText())) {
                        return property.path("type").has("metric");
                    }
                }
                return false;
            }

            private boolean isAttribute(String variable) {
                JsonNode value = variables.get(variable);
                if (value == null || value.hasNonNull("hierarchyId") || model == null) {
                    return false;
                }
                for (ObjectNode property : SiteWiseModels.allProperties(model)) {
                    if (property.get("id").asText().equals(value.path("propertyId").asText())) {
                        return property.path("type").has("attribute");
                    }
                }
                return false;
            }

            @Override
            public Object value(String variable) {
                JsonNode point = isAttribute(variable) ? latest(series(variable), end)
                        : last(window(variable, start, end));
                return point == null ? null : SiteWiseData.number(point.get("v"));
            }

            private JsonNode last(List<SiteWiseFormula.Point> points) {
                if (points.isEmpty()) {
                    return null;
                }
                SiteWiseFormula.Point point = points.get(points.size() - 1);
                ObjectNode node = mapper.createObjectNode();
                node.put("t", (long) Math.floor(point.time()));
                node.put("n", (int) Math.round((point.time() - Math.floor(point.time())) * 1e9));
                ObjectNode variant = node.putObject("v");
                if (point.value() instanceof String s) {
                    variant.put("stringValue", s);
                } else {
                    variant.put("doubleValue", SiteWiseFormula.number(point.value()));
                }
                return node;
            }

            @Override
            public double timestamp(String variable) {
                JsonNode point = latest(series(variable), end);
                return point == null ? end : SiteWiseData.time(point);
            }

            @Override
            public double now() {
                return end;
            }

            @Override
            public List<SiteWiseFormula.Point> window(String variable, double from, double to) {
                List<SiteWiseFormula.Point> points = new ArrayList<>();
                boolean exact = isMetric(variable);
                for (JsonNode point : series(variable)) {
                    double at = SiteWiseData.time(point);
                    if (exact ? Math.abs(at - to) < 1e-6 : at > from && at <= to) {
                        points.add(new SiteWiseFormula.Point(at, SiteWiseData.number(point.get("v"))));
                    }
                }
                return points;
            }

            @Override
            public SiteWiseFormula.Point before(String variable, double time) {
                JsonNode point = latest(series(variable), time);
                return point == null ? null
                        : new SiteWiseFormula.Point(SiteWiseData.time(point), SiteWiseData.number(point.get("v")));
            }

            @Override
            public double[] bounds() {
                return new double[] {start, end};
            }
        };
        SiteWiseFormula.Node node;
        try {
            node = SiteWiseFormula.parse(metric.get("expression").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return evaluate(node, context, definition.get("dataType").asText());
    }

    /** Forgets every metric's progress, as a reset does. */
    void clear() {
        computed.clear();
        dirty.clear();
    }
}

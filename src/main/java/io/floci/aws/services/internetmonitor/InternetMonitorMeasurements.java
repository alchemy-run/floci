package io.floci.aws.services.internetmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ViewerTraffic;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures each active monitor's traffic: the requests and connections {@link ViewerTraffic} observes for the
 * monitor's resources are gathered over a window, and when the window closes their city-network measurements are
 * stored for queries, published to the monitor's CloudWatch Logs groups and its S3 bucket, and checked against the
 * monitor's health event thresholds. Traffic to every resource, monitored or not, raises internet events.
 */
@ApplicationScoped
public class InternetMonitorMeasurements implements Resettable {

    private static final Logger LOG = Logger.getLogger(InternetMonitorMeasurements.class);
    private static final String SOURCE = "aws.internetmonitor";
    private static final int LOGGED_CITY_NETWORKS = 500;
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final double INTERNET_EVENT_THRESHOLD = 95;
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter OBJECT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'")
            .withZone(ZoneOffset.UTC);
    static final List<String> IMPACT_TYPES = List.of("AVAILABILITY", "PERFORMANCE", "LOCAL_AVAILABILITY",
            "LOCAL_PERFORMANCE");

    /** One observed request or connection. */
    record Sample(String serviceLocation, String clientIp, long latencyMillis, boolean failed, long bytesIn,
                  long bytesOut) {
    }

    record MonitorRef(String account, String region, String name) {
    }

    private final InternetMonitorStore store;
    private final Instance<InternetMonitorService> service;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<S3Service> s3;
    private final Instance<EventBridgeService> events;

    private final Map<MonitorRef, ConcurrentLinkedQueue<Sample>> buffers = new ConcurrentHashMap<>();
    private final Map<MonitorRef, AtomicLong> sampled = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Sample> internet = new ConcurrentLinkedQueue<>();
    /** Internet events, newest first, and the active one of each type. */
    private final List<ObjectNode> internetEvents = new ArrayList<>();
    private final Map<String, ObjectNode> activeInternetEvents = new LinkedHashMap<>();
    private ScheduledExecutorService scheduler;

    @Inject
    public InternetMonitorMeasurements(InternetMonitorStore store, Instance<InternetMonitorService> service,
                                       Instance<CloudWatchLogsService> logs, Instance<S3Service> s3,
                                       Instance<EventBridgeService> events) {
        this.store = store;
        this.service = service;
        this.logs = logs;
        this.s3 = s3;
        this.events = events;
    }

    /** Starts closing windows on their boundaries: multiples of the window length since the epoch. */
    synchronized void start() {
        if (scheduler != null) {
            return;
        }
        long length = Math.max(1, store.config().measurementSeconds()) * 1000;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "internetmonitor-windows");
            thread.setDaemon(true);
            return thread;
        });
        long now = System.currentTimeMillis();
        long first = (now / length + 1) * length - now;
        scheduler.scheduleAtFixedRate(() -> {
            long end = System.currentTimeMillis() / length * length;
            try {
                close(Instant.ofEpochMilli(end - length), Instant.ofEpochMilli(end));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Internet Monitor could not close the window ending {0}", Instant.ofEpochMilli(end));
            }
        }, first, length, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void clear() {
        buffers.clear();
        sampled.clear();
        internet.clear();
        synchronized (internetEvents) {
            internetEvents.clear();
            activeInternetEvents.clear();
        }
    }

    void forget(String account, String region, String name) {
        MonitorRef ref = new MonitorRef(account, region, name);
        buffers.remove(ref);
        sampled.remove(ref);
    }

    // ---------------------------------------------------------------- observing

    void observe(@Observes ViewerTraffic.Observation observation) {
        if (!store.config().enabled()) {
            return;
        }
        start();
        Sample sample = new Sample(observation.serviceLocation(), observation.clientIp(), observation.latencyMillis(),
                observation.failed(), observation.bytesIn(), observation.bytesOut());
        internet.add(sample);
        Set<String> reached = new HashSet<>(observation.resources());
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : monitors()) {
            if (!entry.accountId().equals(observation.accountId())) {
                continue;
            }
            ObjectNode monitor = service.get().view(entry.value());
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            if (!"ACTIVE".equals(monitor.path("Status").asText())
                    || (observation.region() != null && !observation.region().equals(region))) {
                continue;
            }
            boolean watched = false;
            for (JsonNode resource : monitor.path("Resources")) {
                watched |= reached.contains(resource.asText());
            }
            if (!watched) {
                continue;
            }
            MonitorRef ref = new MonitorRef(entry.accountId(), region, monitor.path("MonitorName").asText());
            long seen = sampled.computeIfAbsent(ref, ignored -> new AtomicLong()).getAndIncrement();
            int percentage = monitor.path("TrafficPercentageToMonitor").asInt(100);
            // A traffic percentage keeps that share of requests, spread evenly: the n-th request is kept when it
            // carries the running count past another multiple of 100.
            if ((seen + 1) * percentage / 100 == seen * percentage / 100) {
                continue;
            }
            buffers.computeIfAbsent(ref, ignored -> new ConcurrentLinkedQueue<>()).add(sample);
        }
    }

    private List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> monitors() {
        return store.everywhere(key -> key.indexOf("/monitor/") > 0);
    }

    // ---------------------------------------------------------------- closing a window

    /** Closes the window {@code [start, end)} of every active monitor, then raises or resolves internet events. */
    void close(Instant start, Instant end) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : monitors()) {
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            ObjectNode monitor = service.get().view(entry.value());
            MonitorRef ref = new MonitorRef(entry.accountId(), region, monitor.path("MonitorName").asText());
            List<Sample> samples = drain(buffers.get(ref));
            if (!"ACTIVE".equals(monitor.path("Status").asText())) {
                continue;
            }
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> closeMonitor(ref, monitor, samples, start));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Internet Monitor could not close a window of {0}", ref.name());
            }
        }
        closeInternet(drain(internet), start);
        prune(end);
    }

    private static List<Sample> drain(ConcurrentLinkedQueue<Sample> queue) {
        List<Sample> samples = new ArrayList<>();
        if (queue != null) {
            for (Sample sample = queue.poll(); sample != null; sample = queue.poll()) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private void closeMonitor(MonitorRef ref, ObjectNode monitor, List<Sample> samples, Instant start) {
        String key = InternetMonitorStore.monitorKey(ref.region(), ref.name());
        if (samples.isEmpty()) {
            store.get(ref.account(), key).ifPresent(record -> {
                record.put("ProcessingStatus", "INSUFFICIENT_DATA")
                        .put("ProcessingStatusInfo", "Internet Monitor measured no traffic to the monitor's "
                                + "resources in the last window.");
                store.put(ref.account(), key, record);
            });
            return;
        }
        List<ObjectNode> rows = rows(samples, monitor.path("MaxCityNetworksToMonitor").asInt(Integer.MAX_VALUE));
        ObjectNode window = store.mapper().createObjectNode();
        window.put("Timestamp", start.toString());
        ArrayNode array = window.putArray("Rows");
        rows.forEach(array::add);
        store.put(ref.account(), InternetMonitorStore.windowKey(ref.region(), ref.name(), start.getEpochSecond()),
                window);
        publishLogs(ref, rows, start);
        deliverToS3(ref, monitor, rows, start);
        evaluate(ref, monitor, rows, start);
        store.get(ref.account(), key).ifPresent(record -> {
            record.put("ProcessingStatus", "OK").put("ProcessingStatusInfo", "The monitor is processing data.");
            store.put(ref.account(), key, record);
        });
    }

    /**
     * The window's measurements, one row per city-network and service location, largest first and capped at the
     * monitor's city-network limit. Every viewer is in the one configured client location, so a city-network is that
     * location served from one Region or from CloudFront.
     */
    List<ObjectNode> rows(List<Sample> samples, int maxCityNetworks) {
        long performanceThreshold = store.config().performanceThresholdMillis();
        Map<String, List<Sample>> byLocation = new LinkedHashMap<>();
        for (Sample sample : samples) {
            byLocation.computeIfAbsent(sample.serviceLocation(), ignored -> new ArrayList<>()).add(sample);
        }
        long total = samples.size();
        long totalFailed = samples.stream().filter(Sample::failed).count();
        long totalSlow = samples.stream().filter(sample -> !sample.failed()
                && sample.latencyMillis() > performanceThreshold).count();
        List<ObjectNode> rows = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> location : byLocation.entrySet()) {
            List<Sample> group = location.getValue();
            long count = group.size();
            long failed = group.stream().filter(Sample::failed).count();
            long slow = group.stream().filter(sample -> !sample.failed()
                    && sample.latencyMillis() > performanceThreshold).count();
            List<Long> latencies = group.stream().filter(sample -> !sample.failed()).map(Sample::latencyMillis)
                    .sorted().toList();
            ObjectNode row = store.mapper().createObjectNode();
            row.put("serviceLocation", location.getKey()).put("requests", count).put("failed", failed)
                    .put("slow", slow)
                    .put("bytesIn", group.stream().mapToLong(Sample::bytesIn).sum())
                    .put("bytesOut", group.stream().mapToLong(Sample::bytesOut).sum())
                    .put("percentageOfTotalTraffic", round(100.0 * count / total))
                    .put("availability", round(100.0 * (count - failed) / count))
                    .put("availabilityImpact", round(totalFailed == 0 ? 0 : 100.0 * failed / total))
                    .put("availabilityLocalImpact", round(100.0 * failed / count))
                    .put("performance", round(100.0 * (count - slow) / count))
                    .put("performanceImpact", round(totalSlow == 0 ? 0 : 100.0 * slow / total))
                    .put("performanceLocalImpact", round(100.0 * slow / count))
                    .put("rttP50", percentile(latencies, 50)).put("rttP90", percentile(latencies, 90))
                    .put("rttP95", percentile(latencies, 95))
                    .put("ttfb", latencies.isEmpty() ? 0
                            : round(latencies.stream().mapToLong(Long::longValue).average().orElse(0)));
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((ObjectNode row) -> row.path("requests").asLong()).reversed());
        return rows.size() > maxCityNetworks ? new ArrayList<>(rows.subList(0, maxCityNetworks)) : rows;
    }

    private static double percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    // ---------------------------------------------------------------- publishing

    /** A row as Internet Monitor's log record, its client location cut to {@code granularity}. */
    ObjectNode logRecord(ObjectNode row, Instant start, String granularity) {
        EmulatorConfig.InternetMonitorServiceConfig.ClientLocationConfig location = store.config().clientLocation();
        ObjectNode record = store.mapper().createObjectNode();
        record.put("version", 1).put("timestamp", start.getEpochSecond());
        ObjectNode client = record.putObject("clientLocation");
        client.put("latitude", location.latitude()).put("longitude", location.longitude())
                .put("country", location.country()).put("countryCode", location.countryCode());
        if (!"byCountry".equals(granularity)) {
            client.put("subdivision", location.subdivision()).put("subdivisionCode", location.subdivisionCode());
        }
        if ("byCity".equals(granularity) || "byMetro".equals(granularity)) {
            client.put("metro", location.metro());
        }
        if ("byCity".equals(granularity)) {
            client.put("city", location.city());
        }
        client.put("asn", location.asNumber()).put("networkName", location.asName());
        String serviceLocation = row.path("serviceLocation").asText();
        record.put("serviceLocation", serviceLocation)
                .put("percentageOfTotalTraffic", row.path("percentageOfTotalTraffic").asDouble())
                .put("bytesIn", row.path("bytesIn").asLong()).put("bytesOut", row.path("bytesOut").asLong())
                .put("clientConnectionCount", row.path("requests").asLong());
        ObjectNode health = record.putObject("internetHealth");
        health.putObject("availability").put("experienceScore", row.path("availability").asDouble())
                .put("percentageOfTotalTrafficImpacted", row.path("availabilityImpact").asDouble())
                .put("percentageOfClientLocationImpacted", row.path("availabilityLocalImpact").asDouble());
        ObjectNode performance = health.putObject("performance");
        performance.put("experienceScore", row.path("performance").asDouble())
                .put("percentageOfTotalTrafficImpacted", row.path("performanceImpact").asDouble())
                .put("percentageOfClientLocationImpacted", row.path("performanceLocalImpact").asDouble());
        performance.putObject("roundTripTime").put("p50", row.path("rttP50").asDouble())
                .put("p90", row.path("rttP90").asDouble()).put("p95", row.path("rttP95").asDouble());
        ObjectNode ttfb = record.putObject("trafficInsights").putObject("timeToFirstByte");
        boolean cloudFront = "CloudFront".equals(serviceLocation);
        ttfb.putObject("currentExperience").put("serviceName", cloudFront ? "CloudFront" : "EC2")
                .put("serviceLocation", serviceLocation).put("value", row.path("ttfb").asDouble());
        if (cloudFront) {
            ttfb.putObject("cloudfront").put("serviceName", "CloudFront").put("value", row.path("ttfb").asDouble());
        } else {
            ttfb.putObject("ec2").put("serviceName", "EC2").put("serviceLocation", serviceLocation)
                    .put("value", row.path("ttfb").asDouble());
        }
        return record;
    }

    private void publishLogs(MonitorRef ref, List<ObjectNode> rows, Instant start) {
        List<ObjectNode> top = rows.size() > LOGGED_CITY_NETWORKS ? rows.subList(0, LOGGED_CITY_NETWORKS) : rows;
        for (String granularity : List.of("byCity", "byMetro", "bySubdivision", "byCountry")) {
            String group = "/aws/internet-monitor/" + ref.name() + "/" + granularity;
            try {
                if (!logs.get().logGroupExists(group, ref.region())) {
                    logs.get().createLogGroupForAccount(ref.account(), group, null, Map.of(), ref.region());
                }
                for (ObjectNode row : top) {
                    String stream = row.path("serviceLocation").asText();
                    try {
                        logs.get().createLogStreamForAccount(ref.account(), group, stream, ref.region());
                    } catch (AwsException e) {
                        if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                    logs.get().putLogEventsForAccount(ref.account(), group, stream, List.of(Map.of(
                            "timestamp", start.toEpochMilli(),
                            "message", logRecord(row, start, granularity).toString())), ref.region());
                }
            } catch (RuntimeException e) {
                LOG.debugv(e, "Internet Monitor could not publish to {0}", group);
            }
        }
    }

    private void deliverToS3(MonitorRef ref, ObjectNode monitor, List<ObjectNode> rows, Instant start) {
        JsonNode s3Config = monitor.path("InternetMeasurementsLogDelivery").path("S3Config");
        if (s3Config.isMissingNode() || "DISABLED".equals(s3Config.path("LogDeliveryStatus").asText())
                || !s3Config.hasNonNull("BucketName")) {
            return;
        }
        String prefix = s3Config.path("BucketPrefix").asText("");
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        ZonedDateTime at = start.atZone(ZoneOffset.UTC);
        String key = prefix + "AWSLogs/" + ref.account() + "/internetmonitor/" + ref.region() + "/"
                + String.format("%04d/%02d/%02d/", at.getYear(), at.getMonthValue(), at.getDayOfMonth())
                + ref.account() + "_internetmonitor_" + ref.region() + "_" + ref.name() + "_"
                + OBJECT_TIME.format(start) + ".json";
        StringBuilder body = new StringBuilder("version timestamp clientLocation serviceLocation "
                + "percentageOfTotalTraffic bytesIn bytesOut clientConnectionCount internetHealth trafficInsights\n");
        rows.forEach(row -> body.append(logRecord(row, start, "byCity")).append('\n'));
        try {
            s3.get().putObject(s3Config.path("BucketName").asText(), key,
                    body.toString().getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
        } catch (RuntimeException e) {
            LOG.debugv(e, "Internet Monitor could not deliver measurements to {0}", s3Config.path("BucketName"));
        }
    }

    // ---------------------------------------------------------------- health events

    private void evaluate(MonitorRef ref, ObjectNode monitor, List<ObjectNode> rows, Instant start) {
        JsonNode config = monitor.path("HealthEventsConfig");
        double availabilityThreshold = config.path("AvailabilityScoreThreshold").asDouble(95);
        double performanceThreshold = config.path("PerformanceScoreThreshold").asDouble(95);
        long total = rows.stream().mapToLong(row -> row.path("requests").asLong()).sum();
        long failed = rows.stream().mapToLong(row -> row.path("failed").asLong()).sum();
        long slow = rows.stream().mapToLong(row -> row.path("slow").asLong()).sum();
        double availability = 100.0 * (total - failed) / total;
        double performance = 100.0 * (total - slow) / total;
        Map<String, Impact> impacts = new LinkedHashMap<>();
        if (availability < availabilityThreshold) {
            impacts.put("AVAILABILITY", new Impact(availabilityThreshold, round(100.0 * failed / total),
                    rows.stream().filter(row -> row.path("availability").asDouble() < availabilityThreshold)
                            .toList()));
        } else {
            local(config.path("AvailabilityLocalHealthEventsConfig"), rows, "availability", "availabilityImpact")
                    .ifPresent(impact -> impacts.put("LOCAL_AVAILABILITY", impact));
        }
        if (performance < performanceThreshold) {
            impacts.put("PERFORMANCE", new Impact(performanceThreshold, round(100.0 * slow / total),
                    rows.stream().filter(row -> row.path("performance").asDouble() < performanceThreshold)
                            .toList()));
        } else {
            local(config.path("PerformanceLocalHealthEventsConfig"), rows, "performance", "performanceImpact")
                    .ifPresent(impact -> impacts.put("LOCAL_PERFORMANCE", impact));
        }
        String key = InternetMonitorStore.monitorKey(ref.region(), ref.name());
        ObjectNode record = store.get(ref.account(), key).orElse(null);
        if (record == null) {
            return;
        }
        ObjectNode active = ((ObjectNode) record.path("_")).has("activeEvents")
                ? (ObjectNode) record.path("_").path("activeEvents")
                : ((ObjectNode) record.path("_")).putObject("activeEvents");
        Instant now = Instant.now();
        for (String type : IMPACT_TYPES) {
            String eventId = active.path(type).asText(null);
            Impact impact = impacts.get(type);
            ObjectNode event = eventId == null ? null : store.get(ref.account(),
                    InternetMonitorStore.eventKey(ref.region(), ref.name(), eventId)).orElse(null);
            if (impact != null && event == null) {
                eventId = EVENT_TIME.format(start) + "/" + suffix(type);
                event = store.mapper().createObjectNode();
                event.put("EventArn", AwsArnUtils.Arn.of(InternetMonitorStore.SERVICE, ref.region(), ref.account(),
                                "monitor/" + ref.name() + "/health-event/" + eventId).toString())
                        .put("EventId", eventId).put("StartedAt", start.toString()).put("CreatedAt", now.toString());
                fill(event, type, impact, rows, now, "ACTIVE");
                active.put(type, eventId);
                save(ref, event);
                publish(ref, monitor, event, "Health Event Created");
            } else if (impact != null) {
                fill(event, type, impact, rows, now, "ACTIVE");
                save(ref, event);
                publish(ref, monitor, event, "Health Event Updated");
            } else if (event != null) {
                event.put("Status", "RESOLVED").put("EndedAt", now.toString()).put("LastUpdatedAt", now.toString());
                for (JsonNode location : event.path("ImpactedLocations")) {
                    ((ObjectNode) location).put("Status", "RESOLVED");
                }
                active.remove(type);
                save(ref, event);
                publish(ref, monitor, event, "Health Event Closed");
            } else {
                active.remove(type);
            }
        }
        store.put(ref.account(), key, record);
    }

    /** Which city-networks crossed a local threshold, if local events are on and enough traffic is impacted. */
    private static Optional<Impact> local(JsonNode config, List<ObjectNode> rows, String score, String impact) {
        if ("DISABLED".equals(config.path("Status").asText("ENABLED"))) {
            return Optional.empty();
        }
        double threshold = config.path("HealthScoreThreshold").asDouble(60);
        double minimum = config.path("MinTrafficImpact").asDouble(0.1);
        List<ObjectNode> impacted = rows.stream().filter(row -> row.path(score).asDouble() < threshold
                && row.path(impact).asDouble() >= minimum).toList();
        if (impacted.isEmpty()) {
            return Optional.empty();
        }
        double percent = impacted.stream().mapToDouble(row -> row.path(impact).asDouble()).sum();
        return Optional.of(new Impact(threshold, round(percent), impacted));
    }

    private record Impact(double threshold, double percentImpacted, List<ObjectNode> rows) {
    }

    private static String suffix(String type) {
        return switch (type) {
            case "AVAILABILITY" -> "availability";
            case "PERFORMANCE" -> "latency";
            case "LOCAL_AVAILABILITY" -> "local-availability";
            default -> "local-latency";
        };
    }

    private void fill(ObjectNode event, String type, Impact impact, List<ObjectNode> rows, Instant now,
                      String status) {
        EmulatorConfig.InternetMonitorServiceConfig.ClientLocationConfig location = store.config().clientLocation();
        event.put("LastUpdatedAt", now.toString()).put("Status", status).put("ImpactType", type)
                .put("PercentOfTotalTrafficImpacted", impact.percentImpacted())
                .put("HealthScoreThreshold", impact.threshold());
        ArrayNode locations = event.putArray("ImpactedLocations");
        for (ObjectNode row : impact.rows()) {
            ObjectNode impacted = locations.addObject();
            impacted.put("ASName", location.asName()).put("ASNumber", location.asNumber())
                    .put("Country", location.country()).put("Subdivision", location.subdivision())
                    .put("Metro", location.metro()).put("City", location.city())
                    .put("Latitude", location.latitude()).put("Longitude", location.longitude())
                    .put("CountryCode", location.countryCode()).put("SubdivisionCode", location.subdivisionCode())
                    .put("ServiceLocation", row.path("serviceLocation").asText()).put("Status", status);
            ObjectNode causedBy = impacted.putObject("CausedBy");
            // The failures and slow answers are the AWS resource's own: the request reached it.
            causedBy.putArray("Networks").addObject().put("ASName", location.asName())
                    .put("ASNumber", location.asNumber());
            causedBy.putArray("AsPath").addObject().put("ASName", location.asName())
                    .put("ASNumber", location.asNumber());
            causedBy.put("NetworkEventType", "AWS");
            ObjectNode health = impacted.putObject("InternetHealth");
            health.putObject("Availability").put("ExperienceScore", row.path("availability").asDouble())
                    .put("PercentOfTotalTrafficImpacted", row.path("availabilityImpact").asDouble())
                    .put("PercentOfClientLocationImpacted", row.path("availabilityLocalImpact").asDouble());
            ObjectNode performance = health.putObject("Performance");
            performance.put("ExperienceScore", row.path("performance").asDouble())
                    .put("PercentOfTotalTrafficImpacted", row.path("performanceImpact").asDouble())
                    .put("PercentOfClientLocationImpacted", row.path("performanceLocalImpact").asDouble());
            performance.putObject("RoundTripTime").put("P50", row.path("rttP50").asDouble())
                    .put("P90", row.path("rttP90").asDouble()).put("P95", row.path("rttP95").asDouble());
        }
    }

    private void save(MonitorRef ref, ObjectNode event) {
        store.put(ref.account(), InternetMonitorStore.eventKey(ref.region(), ref.name(),
                event.path("EventId").asText()), event);
    }

    private void publish(MonitorRef ref, ObjectNode monitor, ObjectNode event, String detailType) {
        ObjectNode detail = store.mapper().createObjectNode();
        String type = event.path("ImpactType").asText();
        String what = type.endsWith("AVAILABILITY") ? "Availability" : "Performance";
        detail.put("eventArn", event.path("EventArn").asText()).put("eventId", event.path("EventId").asText())
                .put("summary", ("RESOLVED".equals(event.path("Status").asText()) ? "Resolved: " : "")
                        + what + " dropped below " + event.path("HealthScoreThreshold").asDouble() + "% for "
                        + event.path("PercentOfTotalTrafficImpacted").asDouble() + "% of traffic to monitor "
                        + ref.name())
                .put("startedAt", event.path("StartedAt").asText()).put("impactType", type)
                .put("status", event.path("Status").asText())
                .put("percentOfTotalTrafficImpacted", event.path("PercentOfTotalTrafficImpacted").asDouble())
                .put("healthScoreThreshold", event.path("HealthScoreThreshold").asDouble());
        if (event.hasNonNull("EndedAt")) {
            detail.put("endedAt", event.path("EndedAt").asText());
        }
        ArrayNode locations = detail.putArray("impactedLocations");
        for (JsonNode location : event.path("ImpactedLocations")) {
            ObjectNode camel = locations.addObject();
            location.fields().forEachRemaining(field -> camel.set(camelCase(field.getKey()), field.getValue()));
        }
        try {
            events.get().putEvents(List.of(EventBridgeService.serviceEventEntry(SOURCE, detailType,
                    detail.toString(), monitor.path("MonitorArn").asText())), ref.region(), ref.account());
        } catch (RuntimeException e) {
            LOG.debugv(e, "Internet Monitor could not publish {0}", detailType);
        }
    }

    private static String camelCase(String name) {
        if (name.startsWith("AS")) {
            return "as" + name.substring(2);
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    // ---------------------------------------------------------------- internet events

    /** Raises an internet event when all the traffic Floci served from the client location degraded. */
    private void closeInternet(List<Sample> samples, Instant start) {
        if (samples.isEmpty()) {
            return;
        }
        long threshold = store.config().performanceThresholdMillis();
        long total = samples.size();
        long failed = samples.stream().filter(Sample::failed).count();
        long slow = samples.stream().filter(sample -> !sample.failed() && sample.latencyMillis() > threshold)
                .count();
        Map<String, Boolean> degraded = Map.of(
                "AVAILABILITY", 100.0 * (total - failed) / total < INTERNET_EVENT_THRESHOLD,
                "PERFORMANCE", 100.0 * (total - slow) / total < INTERNET_EVENT_THRESHOLD);
        Instant now = Instant.now();
        synchronized (internetEvents) {
            for (String type : List.of("AVAILABILITY", "PERFORMANCE")) {
                ObjectNode active = activeInternetEvents.get(type);
                if (degraded.get(type) && active == null) {
                    EmulatorConfig.InternetMonitorServiceConfig.ClientLocationConfig location =
                            store.config().clientLocation();
                    ObjectNode event = store.mapper().createObjectNode();
                    event.put("EventId", UUID.randomUUID().toString()).put("StartedAt", start.toString())
                            .put("EventType", type).put("EventStatus", "ACTIVE");
                    event.putObject("ClientLocation").put("ASName", location.asName())
                            .put("ASNumber", location.asNumber()).put("Country", location.country())
                            .put("Subdivision", location.subdivision()).put("Metro", location.metro())
                            .put("City", location.city()).put("Latitude", location.latitude())
                            .put("Longitude", location.longitude());
                    activeInternetEvents.put(type, event);
                    internetEvents.add(0, event);
                } else if (!degraded.get(type) && active != null) {
                    active.put("EventStatus", "RESOLVED").put("EndedAt", now.toString());
                    activeInternetEvents.remove(type);
                }
            }
        }
    }

    List<ObjectNode> internetEvents(String region) {
        synchronized (internetEvents) {
            return internetEvents.stream().map(event -> withArn(event, region)).toList();
        }
    }

    Optional<ObjectNode> internetEvent(String eventId, String region) {
        synchronized (internetEvents) {
            return internetEvents.stream().filter(event -> eventId.equals(event.path("EventId").asText()))
                    .findFirst().map(event -> withArn(event, region));
        }
    }

    /** Internet events are global; their ARN is in the caller's partition. */
    private ObjectNode withArn(ObjectNode event, String region) {
        ObjectNode view = event.deepCopy();
        view.put("EventArn", AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), InternetMonitorStore.SERVICE, "",
                "internet-event/" + event.path("EventId").asText()).toString());
        return view;
    }

    private void prune(Instant end) {
        long oldest = end.minus(RETENTION).getEpochSecond();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : store.everywhere(key -> key.indexOf("/window/") > 0)) {
            long second = Long.parseLong(entry.key().substring(entry.key().lastIndexOf('/') + 1));
            if (second < oldest) {
                store.delete(entry.accountId(), entry.key());
            }
        }
        synchronized (internetEvents) {
            internetEvents.removeIf(event -> !activeInternetEvents.containsValue(event)
                    && Instant.parse(event.path("StartedAt").asText()).getEpochSecond() < oldest);
        }
    }
}

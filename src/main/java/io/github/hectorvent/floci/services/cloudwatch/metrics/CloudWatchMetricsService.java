package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmMuteRule;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.InsightRule;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricStream;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CloudWatchMetricsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsService.class);

    private final StorageBackend<String, MetricDatum> metricStore;
    private final StorageBackend<String, MetricAlarm> alarmStore;
    private final StorageBackend<String, CompositeAlarm> compositeAlarmStore;
    private final StorageBackend<String, AlarmMuteRule> muteRuleStore;
    private final StorageBackend<String, MetricStream> metricStreamStore;
    private final StorageBackend<String, InsightRule> insightRuleStore;
    private final StorageBackend<String, AlarmHistory> alarmHistoryStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchMetricsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.metricStore = storageFactory.create("cloudwatchmetrics", "cwmetrics.json",
                new TypeReference<Map<String, MetricDatum>>() {});
        this.alarmStore = storageFactory.create("cloudwatchmetrics", "cwalarms.json",
                new TypeReference<Map<String, MetricAlarm>>() {});
        this.compositeAlarmStore = storageFactory.create("cloudwatchmetrics", "cwcompositealarms.json",
                new TypeReference<Map<String, CompositeAlarm>>() {});
        this.muteRuleStore = storageFactory.create("cloudwatchmetrics", "cwmuterules.json",
                new TypeReference<Map<String, AlarmMuteRule>>() {});
        this.metricStreamStore = storageFactory.create("cloudwatchmetrics", "cwmetricstreams.json",
                new TypeReference<Map<String, MetricStream>>() {});
        this.insightRuleStore = storageFactory.create("cloudwatchmetrics", "cwinsightrules.json",
                new TypeReference<Map<String, InsightRule>>() {});
        this.alarmHistoryStore = storageFactory.create("cloudwatchmetrics", "cwalarmhistory.json",
                new TypeReference<Map<String, AlarmHistory>>() {});
        this.regionResolver = regionResolver;
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, MetricAlarm> alarmStore,
                             RegionResolver regionResolver) {
        this(metricStore, alarmStore, new InMemoryStorage<>(), regionResolver);
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, MetricAlarm> alarmStore,
                             StorageBackend<String, CompositeAlarm> compositeAlarmStore,
                             RegionResolver regionResolver) {
        this.metricStore = metricStore;
        this.alarmStore = alarmStore;
        this.compositeAlarmStore = compositeAlarmStore;
        this.muteRuleStore = new InMemoryStorage<>();
        this.metricStreamStore = new InMemoryStorage<>();
        this.insightRuleStore = new InMemoryStorage<>();
        this.alarmHistoryStore = new InMemoryStorage<>();
        this.regionResolver = regionResolver;
    }

    public void putMetricData(String namespace, List<MetricDatum> datums, String region) {
        long nowSeconds = Instant.now().getEpochSecond();
        for (MetricDatum datum : datums) {
            datum.setNamespace(namespace);
            if (datum.getTimestamp() == 0) {
                datum.setTimestamp(nowSeconds);
            }
            // Synthesize StatisticValues if only a scalar value was provided
            if (datum.getSampleCount() == 0 && datum.getSum() == 0) {
                datum.setSampleCount(1);
                datum.setSum(datum.getValue());
                datum.setMinimum(datum.getValue());
                datum.setMaximum(datum.getValue());
            }

            String dimKey = buildDimKey(datum.getDimensions());
            String key = region + "::" + namespace + "::" + datum.getMetricName()
                    + "::" + dimKey + "::"
                    + String.format("%013d", datum.getTimestamp()) + "::" + UUID.randomUUID();
            metricStore.put(key, datum);
        }
        LOG.debugv("PutMetricData: {0} datums for namespace {1}", datums.size(), namespace);
    }

    public record MetricIdentity(String namespace, String metricName, List<Dimension> dimensions) {}

    public List<MetricIdentity> listMetrics(String namespace, String metricName,
                                             List<Dimension> dimensions, String region) {
        String prefix = region + "::";
        if (namespace != null && !namespace.isBlank()) {
            prefix += namespace + "::";
        }

        final String finalPrefix = prefix;
        List<MetricDatum> all = metricStore.scan(k -> k.startsWith(finalPrefix));

        // De-duplicate by (namespace, metricName, dimKey)
        Map<String, MetricIdentity> deduped = new LinkedHashMap<>();
        for (MetricDatum d : all) {
            if (metricName != null && !metricName.isBlank() && !metricName.equals(d.getMetricName())) {
                continue;
            }
            if (dimensions != null && !dimensions.isEmpty() && !matchesDimensions(d.getDimensions(), dimensions)) {
                continue;
            }
            String identity = d.getNamespace() + "::" + d.getMetricName() + "::" + buildDimKey(d.getDimensions());
            deduped.putIfAbsent(identity, new MetricIdentity(d.getNamespace(), d.getMetricName(), d.getDimensions()));
        }
        return new ArrayList<>(deduped.values());
    }

    public record Datapoint(Instant timestamp, double sampleCount, double sum,
                             double average, double minimum, double maximum, String unit) {}

    public record MetricStat(
            String namespace,
            String metricName,
            List<Dimension> dimensions,
            int period,
            String stat,
            String unit
    ) {}

    public record MetricDataQuery(
            String id,
            MetricStat metricStat,
            String expression,
            String label,
            boolean returnData
    ) {}

    public record MetricDataResult(
            String id,
            String label,
            List<Instant> timestamps,
            List<Double> values,
            String statusCode
    ) {}

    public List<Datapoint> getMetricStatistics(String namespace, String metricName,
                                                List<Dimension> dimensions,
                                                Instant startTime, Instant endTime,
                                                int periodSeconds,
                                                List<String> statistics,
                                                String unit, String region) {
        if (periodSeconds <= 0) {
            periodSeconds = 60;
        }
        String dimKey = dimensions != null ? buildDimKey(dimensions) : "";
        String prefix = region + "::" + namespace + "::" + metricName + "::" + dimKey + "::";

        long startEpoch = startTime != null ? startTime.getEpochSecond() : 0;
        long endEpoch = endTime != null ? endTime.getEpochSecond() : Long.MAX_VALUE;

        List<MetricDatum> matching = metricStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            // Extract timestamp from key segment
            String[] parts = k.split("::");
            if (parts.length < 6) return false;
            try {
                long ts = Long.parseLong(parts[parts.length - 2]);
                return ts >= startEpoch && ts <= endEpoch;
            } catch (NumberFormatException e) {
                return false;
            }
        });

        if (unit != null && !unit.isBlank() && !"None".equals(unit)) {
            matching = matching.stream()
                    .filter(d -> unit.equals(d.getUnit()))
                    .collect(Collectors.toList());
        }

        // Group by period bucket
        Map<Long, List<MetricDatum>> buckets = new LinkedHashMap<>();
        for (MetricDatum d : matching) {
            long bucket = (d.getTimestamp() / periodSeconds) * periodSeconds;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(d);
        }

        List<Datapoint> result = new ArrayList<>();
        for (Map.Entry<Long, List<MetricDatum>> entry : buckets.entrySet()) {
            List<MetricDatum> group = entry.getValue();
            double sc = group.stream().mapToDouble(MetricDatum::getSampleCount).sum();
            double sum = group.stream().mapToDouble(MetricDatum::getSum).sum();
            double min = group.stream().mapToDouble(MetricDatum::getMinimum).min().orElse(0);
            double max = group.stream().mapToDouble(MetricDatum::getMaximum).max().orElse(0);
            double avg = sc > 0 ? sum / sc : 0;
            String resolvedUnit = group.stream()
                    .map(MetricDatum::getUnit)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst().orElse("None");
            result.add(new Datapoint(
                    Instant.ofEpochSecond(entry.getKey()),
                    sc, sum, avg, min, max, resolvedUnit
            ));
        }
        result.sort(Comparator.comparing(Datapoint::timestamp));
        return result;
    }

    public List<MetricDataResult> getMetricData(
            List<MetricDataQuery> queries,
            Instant startTime,
            Instant endTime,
            String region) {

        List<MetricDataResult> results = new ArrayList<>();

        for (MetricDataQuery query : queries) {
            if (!query.returnData()) {
                continue;
            }
            if (query.metricStat() != null) {
                MetricStat stat = query.metricStat();
                int period = stat.period() > 0 ? stat.period() : 60;

                List<Datapoint> datapoints = getMetricStatistics(
                        stat.namespace(), stat.metricName(), stat.dimensions(),
                        startTime, endTime, period,
                        List.of(stat.stat()), stat.unit(), region);

                List<Instant> timestamps = new ArrayList<>();
                List<Double> values = new ArrayList<>();
                for (Datapoint dp : datapoints) {
                    timestamps.add(dp.timestamp());
                    values.add(resolveStatValue(dp, stat.stat()));
                }

                String label = query.label() != null ? query.label() : stat.metricName();
                results.add(new MetricDataResult(query.id(), label, timestamps, values, "Complete"));
            }
            // Expression-based queries are out of scope for this implementation
        }
        return results;
    }

    private double resolveStatValue(Datapoint dp, String stat) {
        return switch (stat) {
            case "Average" -> dp.average();
            case "Sum" -> dp.sum();
            case "Minimum" -> dp.minimum();
            case "Maximum" -> dp.maximum();
            case "SampleCount" -> dp.sampleCount();
            default -> {
                if (stat.startsWith("p")) yield dp.maximum();
                else yield dp.average();
            }
        };
    }

    public void putMetricAlarm(MetricAlarm alarm, String region) {
        if (alarm.getAlarmArn() == null) {
            alarm.setAlarmArn(regionResolver.buildArn("cloudwatch", region, "alarm:" + alarm.getAlarmName()));
        }
        alarm.setAlarmConfigurationUpdatedTimestamp(Instant.now().getEpochSecond());
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
        LOG.infov("PutMetricAlarm: {0} in {1}", alarm.getAlarmName(), region);
    }

    public void putCompositeAlarm(CompositeAlarm alarm, String region) {
        String key = region + "::" + alarm.getAlarmName();
        CompositeAlarm existing = compositeAlarmStore.get(key).orElse(null);
        if (alarm.getAlarmArn() == null) {
            if (existing != null && existing.getAlarmArn() != null) {
                alarm.setAlarmArn(existing.getAlarmArn());
            } else {
                alarm.setAlarmArn(regionResolver.buildArn("cloudwatch", region, "alarm:" + alarm.getAlarmName()));
            }
        }
        if (existing != null) {
            alarm.setTags(new LinkedHashMap<>(existing.getTags()));
        } else if (alarm.getTags() == null) {
            alarm.setTags(new LinkedHashMap<>());
        } else {
            alarm.setTags(new LinkedHashMap<>(alarm.getTags()));
        }
        alarm.setAlarmConfigurationUpdatedTimestamp(Instant.now().getEpochSecond());
        compositeAlarmStore.put(key, alarm);
        LOG.infov("PutCompositeAlarm: {0} in {1}", alarm.getAlarmName(), region);
    }

    public List<MetricAlarm> describeAlarms(List<String> alarmNames, String alarmNamePrefix, String region) {
        String prefix = region + "::";
        List<MetricAlarm> all = alarmStore.scan(k -> k.startsWith(prefix));

        if (alarmNames != null && !alarmNames.isEmpty()) {
            return all.stream().filter(a -> alarmNames.contains(a.getAlarmName())).toList();
        }
        if (alarmNamePrefix != null && !alarmNamePrefix.isBlank()) {
            return all.stream().filter(a -> a.getAlarmName().startsWith(alarmNamePrefix)).toList();
        }
        return all;
    }

    public List<CompositeAlarm> describeCompositeAlarms(List<String> alarmNames, String alarmNamePrefix, String region) {
        String prefix = region + "::";
        List<CompositeAlarm> all = compositeAlarmStore.scan(k -> k.startsWith(prefix));

        if (alarmNames != null && !alarmNames.isEmpty()) {
            return all.stream().filter(a -> alarmNames.contains(a.getAlarmName())).toList();
        }
        if (alarmNamePrefix != null && !alarmNamePrefix.isBlank()) {
            return all.stream().filter(a -> a.getAlarmName().startsWith(alarmNamePrefix)).toList();
        }
        return all;
    }

    public void deleteAlarms(List<String> alarmNames, String region) {
        for (String name : alarmNames) {
            alarmStore.delete(region + "::" + name);
            compositeAlarmStore.delete(region + "::" + name);
        }
        LOG.infov("Deleted alarms: {0} in {1}", alarmNames, region);
    }

    public void setAlarmState(String alarmName, String stateValue, String stateReason, String stateReasonData, String region) {
        String key = region + "::" + alarmName;
        MetricAlarm alarm = alarmStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFound", "Alarm not found: " + alarmName, 404));

        alarm.setStateValue(stateValue);
        alarm.setStateReason(stateReason);
        alarm.setStateReasonData(stateReasonData);
        alarm.setStateUpdatedTimestamp(Instant.now().getEpochSecond());

        alarmStore.put(key, alarm);
        LOG.infov("SetAlarmState: {0} -> {1}", alarmName, stateValue);
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        var metricTags = alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst()
                .map(MetricAlarm::getTags);
        if (metricTags.isPresent()) {
            return metricTags.get();
        }
        var compositeTags = compositeAlarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst()
                .map(CompositeAlarm::getTags);
        if (compositeTags.isPresent()) {
            return compositeTags.get();
        }
        var streamTags = metricStreamStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(s -> resourceArn.equals(s.getArn()))
                .findFirst()
                .map(MetricStream::getTags);
        if (streamTags.isPresent()) {
            return streamTags.get();
        }
        return insightRuleStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(r -> resourceArn.equals(r.getArn()))
                .findFirst()
                .map(InsightRule::getTags)
                .orElse(Map.of());
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        var metric = alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst();
        if (metric.isPresent()) {
            metric.get().getTags().putAll(tags);
            alarmStore.put(region + "::" + metric.get().getAlarmName(), metric.get());
            return;
        }
        var composite = compositeAlarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst();
        if (composite.isPresent()) {
            composite.get().getTags().putAll(tags);
            compositeAlarmStore.put(region + "::" + composite.get().getAlarmName(), composite.get());
            return;
        }
        var stream = metricStreamStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(s -> resourceArn.equals(s.getArn()))
                .findFirst();
        if (stream.isPresent()) {
            stream.get().getTags().putAll(tags);
            metricStreamStore.put(region + "::" + stream.get().getName(), stream.get());
            return;
        }
        insightRuleStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(r -> resourceArn.equals(r.getArn()))
                .findFirst()
                .ifPresent(rule -> {
                    rule.getTags().putAll(tags);
                    insightRuleStore.put(region + "::" + rule.getName(), rule);
                });
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        var metric = alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst();
        if (metric.isPresent()) {
            tagKeys.forEach(metric.get().getTags()::remove);
            alarmStore.put(region + "::" + metric.get().getAlarmName(), metric.get());
            return;
        }
        var composite = compositeAlarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst();
        if (composite.isPresent()) {
            tagKeys.forEach(composite.get().getTags()::remove);
            compositeAlarmStore.put(region + "::" + composite.get().getAlarmName(), composite.get());
            return;
        }
        var stream = metricStreamStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(s -> resourceArn.equals(s.getArn()))
                .findFirst();
        if (stream.isPresent()) {
            tagKeys.forEach(stream.get().getTags()::remove);
            metricStreamStore.put(region + "::" + stream.get().getName(), stream.get());
            return;
        }
        insightRuleStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(r -> resourceArn.equals(r.getArn()))
                .findFirst()
                .ifPresent(rule -> {
                    tagKeys.forEach(rule.getTags()::remove);
                    insightRuleStore.put(region + "::" + rule.getName(), rule);
                });
    }

    public AlarmMuteRule putAlarmMuteRule(AlarmMuteRule rule, String region) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new AwsException("MissingRequiredParameterException", "Name is a required parameter.", 400);
        }
        rule.setAlarmMuteRuleArn(
                regionResolver.buildArn("cloudwatch", region, "alarm-mute-rule:" + rule.getName()));
        rule.setLastUpdatedTimestamp(Instant.now().getEpochSecond());
        if (rule.getMuteType() == null || rule.getMuteType().isBlank()) {
            rule.setMuteType(inferMuteType(rule.getScheduleExpression()));
        }
        muteRuleStore.put(region + "::" + rule.getName(), rule);
        LOG.infov("PutAlarmMuteRule: {0} in {1}", rule.getName(), region);
        return rule;
    }

    public AlarmMuteRule getAlarmMuteRule(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "AlarmMuteRuleName is a required parameter.", 400);
        }
        return muteRuleStore.get(region + "::" + name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Alarm mute rule not found: " + name, 404));
    }

    public List<AlarmMuteRule> listAlarmMuteRules(String alarmName, List<String> statuses, String region) {
        String prefix = region + "::";
        List<AlarmMuteRule> all = new ArrayList<>(muteRuleStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(AlarmMuteRule::getName, Comparator.nullsLast(String::compareTo)));
        long now = Instant.now().getEpochSecond();
        return all.stream()
                .filter(rule -> alarmName == null || alarmName.isBlank()
                        || rule.getAlarmNames().contains(alarmName))
                .filter(rule -> statuses == null || statuses.isEmpty()
                        || statuses.contains(rule.status(now)))
                .collect(Collectors.toList());
    }

    public void deleteAlarmMuteRule(String name, String region) {
        if (name == null || name.isBlank()) {
            return;
        }
        muteRuleStore.delete(region + "::" + name);
        LOG.infov("DeleteAlarmMuteRule: {0} in {1}", name, region);
    }

    static String inferMuteType(String expression) {
        if (expression != null && expression.startsWith("at(")) {
            return "ONE_TIME";
        }
        return "RECURRING";
    }

    public MetricStream putMetricStream(MetricStream incoming, String region) {
        if (incoming.getName() == null || incoming.getName().isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "Name is a required parameter.", 400);
        }
        if (incoming.getFirehoseArn() == null || incoming.getFirehoseArn().isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "FirehoseArn is a required parameter.", 400);
        }
        if (incoming.getRoleArn() == null || incoming.getRoleArn().isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "RoleArn is a required parameter.", 400);
        }
        if (incoming.getOutputFormat() == null || incoming.getOutputFormat().isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "OutputFormat is a required parameter.", 400);
        }
        String key = region + "::" + incoming.getName();
        MetricStream existing = metricStreamStore.get(key).orElse(null);
        long now = Instant.now().getEpochSecond();
        if (existing == null) {
            incoming.setArn(regionResolver.buildArn("cloudwatch", region, "metric-stream/" + incoming.getName()));
            incoming.setCreationDate(now);
            incoming.setState("running");
            if (incoming.getTags() == null) {
                incoming.setTags(new LinkedHashMap<>());
            }
        } else {
            incoming.setArn(existing.getArn());
            incoming.setCreationDate(existing.getCreationDate());
            incoming.setState(existing.getState() != null ? existing.getState() : "running");
            if (incoming.getTags() == null || incoming.getTags().isEmpty()) {
                incoming.setTags(existing.getTags());
            } else {
                Map<String, String> merged = new LinkedHashMap<>(existing.getTags());
                merged.putAll(incoming.getTags());
                incoming.setTags(merged);
            }
        }
        incoming.setLastUpdateDate(now);
        metricStreamStore.put(key, incoming);
        LOG.infov("PutMetricStream: {0} in {1}", incoming.getName(), region);
        return incoming;
    }

    public MetricStream getMetricStream(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "Name is a required parameter.", 400);
        }
        return metricStreamStore.get(region + "::" + name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Metric stream not found: " + name, 404));
    }

    public List<MetricStream> listMetricStreams(String region) {
        List<MetricStream> all = new ArrayList<>(metricStreamStore.scan(k -> k.startsWith(region + "::")));
        all.sort(Comparator.comparing(MetricStream::getName, Comparator.nullsLast(String::compareTo)));
        return all;
    }

    public void deleteMetricStream(String name, String region) {
        getMetricStream(name, region);
        metricStreamStore.delete(region + "::" + name);
        LOG.infov("DeleteMetricStream: {0} in {1}", name, region);
    }

    public void startMetricStreams(List<String> names, String region) {
        setMetricStreamState(names, "running", region);
    }

    public void stopMetricStreams(List<String> names, String region) {
        setMetricStreamState(names, "stopped", region);
    }

    private void setMetricStreamState(List<String> names, String state, String region) {
        if (names == null || names.isEmpty()) {
            throw new AwsException("MissingRequiredParameterException",
                    "Names is a required parameter.", 400);
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            metricStreamStore.get(region + "::" + name).ifPresent(stream -> {
                stream.setState(state);
                stream.setLastUpdateDate(Instant.now().getEpochSecond());
                metricStreamStore.put(region + "::" + name, stream);
            });
        }
        LOG.infov("SetMetricStreamState: {0} -> {1} in {2}", names, state, region);
    }

    public record InsightRulesPage(List<InsightRule> rules, String nextToken) {}

    public InsightRule putInsightRule(InsightRule rule, String region) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "RuleName is a required parameter.", 400);
        }
        String key = region + "::" + rule.getName();
        InsightRule existing = insightRuleStore.get(key).orElse(null);
        if (rule.getArn() == null || rule.getArn().isBlank()) {
            if (existing != null && existing.getArn() != null) {
                rule.setArn(existing.getArn());
            } else {
                rule.setArn(regionResolver.buildArn("cloudwatch", region, "insight-rule/" + rule.getName()));
            }
        }
        if (existing != null) {
            if (rule.getTags() == null || rule.getTags().isEmpty()) {
                rule.setTags(existing.getTags());
            }
            rule.setManagedRule(existing.isManagedRule());
        } else if (rule.getTags() == null) {
            rule.setTags(new LinkedHashMap<>());
        }
        if (rule.getState() == null || rule.getState().isBlank()) {
            rule.setState("ENABLED");
        }
        if (rule.getSchema() == null || rule.getSchema().isBlank()) {
            rule.setSchema(InsightRule.DEFAULT_SCHEMA);
        }
        insightRuleStore.put(key, rule);
        return rule;
    }

    public InsightRulesPage describeInsightRules(Integer maxResults, String nextToken, String region) {
        List<InsightRule> all = new ArrayList<>(insightRuleStore.scan(k -> k.startsWith(region + "::")));
        all.sort(Comparator.comparing(InsightRule::getName, Comparator.nullsLast(String::compareTo)));
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                start = Math.max(0, Integer.parseInt(nextToken));
            } catch (NumberFormatException e) {
                start = 0;
            }
        }
        if (start > all.size()) {
            start = all.size();
        }
        int limit = maxResults != null && maxResults > 0 ? maxResults : 100;
        int end = Math.min(start + limit, all.size());
        String next = end < all.size() ? String.valueOf(end) : null;
        return new InsightRulesPage(new ArrayList<>(all.subList(start, end)), next);
    }

    public List<Map<String, String>> deleteInsightRules(List<String> names, String region) {
        List<Map<String, String>> failures = new ArrayList<>();
        if (names == null) {
            return failures;
        }
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                insightRuleStore.delete(region + "::" + name);
            }
        }
        return failures;
    }

    public List<Map<String, String>> setInsightRulesState(List<String> names, String state, String region) {
        List<Map<String, String>> failures = new ArrayList<>();
        if (names == null) {
            return failures;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            InsightRule rule = insightRuleStore.get(region + "::" + name).orElse(null);
            if (rule == null) {
                Map<String, String> failure = new LinkedHashMap<>();
                failure.put("FailureResource", name);
                failure.put("ExceptionType", "ResourceNotFoundException");
                failure.put("FailureCode", "ResourceNotFound");
                failure.put("FailureDescription", "Insight rule not found: " + name);
                failures.add(failure);
                continue;
            }
            rule.setState(state);
            insightRuleStore.put(region + "::" + name, rule);
        }
        return failures;
    }

    public InsightRule requireInsightRule(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingRequiredParameterException",
                    "RuleName is a required parameter.", 400);
        }
        return insightRuleStore.get(region + "::" + name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Insight rule not found: " + name, 404));
    }

    public List<MetricAlarm> describeAlarmsForMetric(String namespace, String metricName,
                                                     String statistic, Integer period,
                                                     List<Dimension> dimensions, String region) {
        return alarmStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(a -> namespace == null || namespace.equals(a.getNamespace()))
                .filter(a -> metricName == null || metricName.equals(a.getMetricName()))
                .filter(a -> statistic == null || statistic.isBlank() || statistic.equals(a.getStatistic()))
                .filter(a -> period == null || period <= 0 || period == a.getPeriod())
                .filter(a -> dimensions == null || dimensions.isEmpty()
                        || buildDimKey(a.getDimensions()).equals(buildDimKey(dimensions)))
                .collect(Collectors.toList());
    }

    public void setAlarmActionsEnabled(List<String> names, boolean enabled, String region) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            alarmStore.get(region + "::" + name).ifPresent(alarm -> {
                alarm.setActionsEnabled(enabled);
                alarmStore.put(region + "::" + name, alarm);
            });
            compositeAlarmStore.get(region + "::" + name).ifPresent(alarm -> {
                alarm.setActionsEnabled(enabled);
                compositeAlarmStore.put(region + "::" + name, alarm);
            });
        }
    }

    public List<AlarmHistoryItem> describeAlarmHistory(String alarmName, Integer maxRecords, String region) {
        AlarmHistory history = alarmHistoryStore.get(region + "::" + (alarmName == null ? "" : alarmName))
                .orElseGet(AlarmHistory::new);
        List<AlarmHistoryItem> items = new ArrayList<>(history.getItems());
        items.sort(Comparator.comparingLong(AlarmHistoryItem::getTimestamp).reversed());
        if (maxRecords != null && maxRecords > 0 && items.size() > maxRecords) {
            return new ArrayList<>(items.subList(0, maxRecords));
        }
        return items;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    static String buildDimKey(List<Dimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "";
        }
        return dimensions.stream()
                .sorted(Comparator.comparing(Dimension::name))
                .map(d -> d.name() + "=" + d.value())
                .collect(Collectors.joining(","));
    }

    private boolean matchesDimensions(List<Dimension> actual, List<Dimension> required) {
        String requiredKey = buildDimKey(required);
        String actualKey = buildDimKey(actual);
        return actualKey.contains(requiredKey);
    }
}

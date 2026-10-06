package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

final class LiveTailSession implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LiveTailSession.class);
    private static final int MAX_EVENTS = 500;
    private static final long DURATION_NANOS = Duration.ofHours(3).toNanos();

    final String account;
    final String region;
    private final Map<String, String> groups;
    private final List<String> names;
    private final List<String> prefixes;
    private final String filter;
    private final String id = UUID.randomUUID().toString();
    private final String requestId = UUID.randomUUID().toString();
    private final List<Map<String, Object>> pending = new ArrayList<>();
    private final ArrayBlockingQueue<Map<String, Object>> updates = new ArrayBlockingQueue<>(10);
    private long matched;
    private volatile boolean closed;
    private Thread producer;

    LiveTailSession(String account, String region, Map<String, String> groups,
                    List<String> names, List<String> prefixes, String filter) {
        this.account = account;
        this.region = region;
        this.groups = Map.copyOf(groups);
        this.names = List.copyOf(names);
        this.prefixes = List.copyOf(prefixes);
        this.filter = filter;
    }

    synchronized void accept(String group, String stream, List<LogEvent> events) {
        String arn = groups.get(group);
        if (closed || arn == null || (!names.isEmpty() && !names.contains(stream))
                || (!prefixes.isEmpty() && prefixes.stream().noneMatch(stream::startsWith))) {
            return;
        }
        for (LogEvent event : events) {
            if (!CloudWatchLogsService.matchesFilterPattern(event.getMessage(), filter)) {
                continue;
            }
            Map<String, Object> result = Map.of(
                    "logGroupIdentifier", arn, "logStreamName", stream,
                    "timestamp", event.getTimestamp(), "ingestionTime", event.getIngestionTime(),
                    "message", event.getMessage());
            matched++;
            if (pending.size() < MAX_EVENTS) {
                pending.add(result);
            } else {
                long index = ThreadLocalRandom.current().nextLong(matched);
                if (index < MAX_EVENTS) {
                    pending.set((int) index, result);
                }
            }
        }
    }

    private synchronized Map<String, Object> nextUpdate() {
        Map<String, Object> update = Map.of("sessionMetadata", Map.of("sampled", matched > MAX_EVENTS),
                "sessionResults", List.copyOf(pending));
        pending.clear();
        matched = 0;
        return update;
    }

    void write(OutputStream output, ObjectMapper mapper, BooleanSupplier disconnected) throws IOException {
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("sessionId", id);
        start.put("requestId", requestId);
        start.put("logGroupIdentifiers", List.copyOf(groups.values()));
        if (!names.isEmpty()) {
            start.put("logStreamNames", names);
        }
        if (!prefixes.isEmpty()) {
            start.put("logStreamNamePrefixes", prefixes);
        }
        if (filter != null) {
            start.put("logEventFilterPattern", filter);
        }
        writeFrame(output, mapper, "event", "initial-response", Map.of());
        writeFrame(output, mapper, "event", "sessionStart", start);
        long deadline = System.nanoTime() + DURATION_NANOS;
        producer = Thread.ofVirtual().name("logs-live-tail-" + id).start(() -> {
            try {
                while (!closed && System.nanoTime() < deadline) {
                    Thread.sleep(1000);
                    Map<String, Object> update = nextUpdate();
                    if (!updates.offer(update)) {
                        updates.poll();
                        updates.offer(update);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            while (!closed && System.nanoTime() < deadline) {
                // Quarkus's StreamingOutput can accept writes after the socket closes.
                if (disconnected.getAsBoolean()) {
                    return;
                }
                Map<String, Object> update = updates.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (update != null) {
                    writeFrame(output, mapper, "event", "sessionUpdate", update);
                }
            }
            if (!closed) {
                writeFrame(output, mapper, "exception", "SessionTimeoutException",
                        Map.of("message", "The maximum live tail session duration of three hours has elapsed."));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Live tail session interrupted", e);
        }
    }

    private static void writeFrame(OutputStream output, ObjectMapper mapper, String type,
                                   String event, Map<String, Object> payload) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(":message-type", type);
        headers.put(type.equals("event") ? ":event-type" : ":exception-type", event);
        headers.put(":content-type", "application/json");
        try {
            output.write(AwsEventStreamEncoder.encodeMessage(headers, mapper.writeValueAsBytes(payload)));
            output.flush();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to encode live tail event", e);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (producer != null) {
            producer.interrupt();
        }
        pending.clear();
        updates.clear();
        LOG.debugf("Closed live tail session %s", id);
    }
}

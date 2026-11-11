package io.floci.aws.services.ivschat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.firehose.FirehoseService;
import io.floci.aws.services.firehose.model.Record;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Chat logging: every message and event a room delivers, written to each active logging configuration the room
 * has, in IVS Chat's record format ({@code event_timestamp}, {@code type}, {@code version}, {@code payload}). A
 * CloudWatch Logs destination gets each record on the stream {@code aws/IVSChatLogs/1.0/room_<id>}, a Firehose
 * delivery stream each record as it happens, and an S3 bucket gzipped objects under
 * {@code AWSLogs/<account>/IVSChatLogs/1.0/<region>/room_<id>/<y>/<m>/<d>/<h>/}, one for each room and minute's
 * records gathered over {@code s3-flush-millis}.
 */
@ApplicationScoped
public class IvsChatLogging implements Resettable {

    private static final Logger LOG = Logger.getLogger(IvsChatLogging.class);
    static final String VERSION = "1.0";
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'");

    private final IvsChatStore store;
    private final Instance<S3Service> s3;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<FirehoseService> firehose;
    private final Map<Batch, List<String>> batches = new HashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ivschat-log-flush");
        thread.setDaemon(true);
        return thread;
    });

    /** The S3 object one room's records of one minute go to. */
    private record Batch(String account, String region, String bucket, String roomId, String minute) {
    }

    @Inject
    public IvsChatLogging(IvsChatStore store, Instance<S3Service> s3, Instance<CloudWatchLogsService> logs,
                          Instance<FirehoseService> firehose) {
        this.store = store;
        this.s3 = s3;
        this.logs = logs;
        this.firehose = firehose;
    }

    @PreDestroy
    void stop() {
        flushAll();
        flusher.shutdownNow();
    }

    @Override
    public void clear() {
        synchronized (batches) {
            batches.clear();
        }
    }

    /** ResourceNotFoundException unless the destination a configuration names exists. */
    void requireDestination(JsonNode destination, String account, String region) {
        boolean exists;
        String name;
        if (destination.has("s3")) {
            name = destination.path("s3").path("bucketName").asText();
            exists = s3.get().bucketExists(name);
        } else if (destination.has("cloudWatchLogs")) {
            name = destination.path("cloudWatchLogs").path("logGroupName").asText();
            exists = logs.get().logGroupExists(name, region);
        } else if (destination.has("firehose")) {
            name = destination.path("firehose").path("deliveryStreamName").asText();
            exists = firehoseExists(account, region, name);
        } else {
            throw IvsChatStore.validation("destinationConfiguration names one of s3, cloudWatchLogs or firehose.");
        }
        if (!exists) {
            throw IvsChatStore.notFound(name, IvsChatStore.LOGGING);
        }
    }

    private boolean firehoseExists(String account, String region, String name) {
        try {
            firehose.get().describeDeliveryStream(account, region, name);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    /** Logs a delivered message or event to the room's active logging configurations. */
    void log(String account, String region, ObjectNode room, ObjectNode payload) {
        List<ObjectNode> destinations = new ArrayList<>();
        for (JsonNode identifier : room.path("loggingConfigurationIdentifiers")) {
            try {
                ObjectNode configuration = RequestScopes.callAs(account, region,
                        () -> store.require(identifier.asText(), IvsChatStore.LOGGING, region));
                if ("ACTIVE".equals(configuration.path("state").asText())) {
                    destinations.add((ObjectNode) configuration.path("destinationConfiguration"));
                }
            } catch (AwsException e) {
                LOG.debugv("IVS Chat room {0} logs to a missing configuration {1}", room.path("arn"), identifier);
            }
        }
        if (destinations.isEmpty()) {
            return;
        }
        Instant at = Instant.parse(payload.path("SendTime").asText());
        ObjectNode record = store.mapper().createObjectNode();
        record.put("event_timestamp", payload.path("SendTime").asText()).put("type", payload.path("Type").asText())
                .put("version", VERSION).set("payload", payload);
        String line = record.toString();
        String roomId = room.path("id").asText();
        for (ObjectNode destination : destinations) {
            try {
                RequestScopes.runAs(account, region, () -> deliver(account, region, roomId, destination, line, at));
            } catch (RuntimeException e) {
                LOG.debugv(e, "IVS Chat could not log room {0} to {1}", roomId, destination);
            }
        }
    }

    private void deliver(String account, String region, String roomId, ObjectNode destination, String line,
                         Instant at) {
        if (destination.has("cloudWatchLogs")) {
            String group = destination.path("cloudWatchLogs").path("logGroupName").asText();
            String stream = "aws/IVSChatLogs/" + VERSION + "/room_" + roomId;
            try {
                logs.get().createLogStreamForAccount(account, group, stream, region);
            } catch (AwsException e) {
                if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                    throw e;
                }
            }
            logs.get().putLogEventsForAccount(account, group, stream,
                    List.of(Map.of("timestamp", at.toEpochMilli(), "message", line)), region);
        } else if (destination.has("firehose")) {
            firehose.get().putRecord(account, region, destination.path("firehose").path("deliveryStreamName").asText(),
                    new Record((line + "\n").getBytes(StandardCharsets.UTF_8)));
        } else if (destination.has("s3")) {
            Batch batch = new Batch(account, region, destination.path("s3").path("bucketName").asText(), roomId,
                    MINUTE.format(at.atZone(ZoneOffset.UTC)));
            long flushMillis = store.config().s3FlushMillis();
            boolean first;
            synchronized (batches) {
                List<String> lines = batches.computeIfAbsent(batch, ignored -> new ArrayList<>());
                first = lines.isEmpty();
                lines.add(line);
            }
            if (flushMillis <= 0) {
                flush(batch);
            } else if (first) {
                flusher.schedule(() -> flush(batch), flushMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flushAll() {
        List<Batch> pending;
        synchronized (batches) {
            pending = new ArrayList<>(batches.keySet());
        }
        pending.forEach(this::flush);
    }

    private void flush(Batch batch) {
        List<String> lines;
        synchronized (batches) {
            lines = batches.remove(batch);
        }
        if (lines == null || lines.isEmpty()) {
            return;
        }
        ZonedDateTime at = ZonedDateTime.parse(batch.minute(), MINUTE.withZone(ZoneOffset.UTC));
        String prefix = "AWSLogs/" + batch.account() + "/IVSChatLogs/" + VERSION + "/" + batch.region() + "/room_"
                + batch.roomId() + "/" + String.format("%04d/%02d/%02d/%02d/", at.getYear(), at.getMonthValue(),
                at.getDayOfMonth(), at.getHour());
        String name = batch.account() + "_IVSChatLogs_" + VERSION + "_" + batch.region() + "_room_" + batch.roomId()
                + "_" + batch.minute() + "_" + IvsChatStore.id(8).toLowerCase() + ".log.gz";
        byte[] body = gzip(String.join("\n", lines) + "\n");
        try {
            RequestScopes.runAs(batch.account(), batch.region(), () ->
                    s3.get().putObject(batch.bucket(), prefix + name, body, "application/octet-stream", Map.of()));
        } catch (RuntimeException e) {
            LOG.debugv(e, "IVS Chat could not write its log object to bucket {0}", batch.bucket());
        }
    }

    private static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}

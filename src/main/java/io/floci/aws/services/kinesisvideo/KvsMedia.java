package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.floci.aws.services.kinesisvideo.KvsStore.invalid;
import static io.floci.aws.services.kinesisvideo.KvsStore.meta;

/**
 * Media in and out of a stream. {@code PutMedia} takes a producer's Matroska stream and stores each cluster as a
 * fragment (numbered, with its producer and server timestamps and duration), acknowledging each one with
 * {@code BUFFERING}, {@code RECEIVED} and, when the stream retains data, {@code PERSISTED} events. {@code GetMedia}
 * returns the fragments from where its selector starts, each a whole Matroska document with its
 * {@code AWS_KINESISVIDEO_*} tags, then follows the stream live until it's idle, and ends with a continuation
 * token. A stream without retention keeps its fragments only for live readers, for a minute.
 */
@ApplicationScoped
public class KvsMedia {

    private static final Logger LOG = Logger.getLogger(KvsMedia.class);
    static final long LIVE_BUFFER_MILLIS = 60_000;
    static final int MAX_FRAGMENT_BYTES = 50 * 1024 * 1024;

    private final KvsStore store;
    private final KvsService service;
    private final AtomicLong counter = new AtomicLong();
    private final List<Consumer<String>> listeners = new ArrayList<>();

    @Inject
    public KvsMedia(KvsStore store, KvsService service) {
        this.store = store;
        this.service = service;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    /** Runs a listener with each new fragment's stream key ({@code <account>/<region>/<stream>}). */
    synchronized void onFragment(Consumer<String> listener) {
        listeners.add(listener);
    }

    String number(long serverMillis) {
        return BigInteger.valueOf(serverMillis).multiply(BigInteger.TEN.pow(23))
                .add(BigInteger.valueOf(counter.incrementAndGet() % 100_000_000_000_000_000L)).add(BigInteger.TEN
                        .pow(37).multiply(BigInteger.valueOf(9))).toString();
    }

    /** The fragments a stream keeps now, dropping those past its retention. */
    List<ObjectNode> fragments(String account, String region, ObjectNode stream) {
        String name = stream.path("StreamName").asText();
        int hours = stream.path("DataRetentionInHours").asInt();
        long keep = hours > 0 ? hours * 3_600_000L : LIVE_BUFFER_MILLIS;
        long cutoff = System.currentTimeMillis() - keep;
        List<ObjectNode> kept = new ArrayList<>();
        for (ObjectNode fragment : store.fragments(account, region, name)) {
            if (fragment.path("ServerTimestamp").asDouble() * 1000 < cutoff) {
                store.deleteFragment(account, region, name, fragment.path("FragmentNumber").asText());
            } else {
                kept.add(fragment);
            }
        }
        return kept;
    }

    // ---------------------------------------------------------------- PutMedia

    /** Ingests a producer's stream, writing an acknowledgement line per event to {@code acks}. */
    void putMedia(String account, String region, Map<String, String> headers, InputStream body, OutputStream acks)
            throws IOException {
        ObjectNode request = mapper().createObjectNode();
        if (headers.get("x-amzn-stream-name") != null) {
            request.put("StreamName", headers.get("x-amzn-stream-name"));
        }
        if (headers.get("x-amzn-stream-arn") != null) {
            request.put("StreamARN", headers.get("x-amzn-stream-arn"));
        }
        ObjectNode stream = service.activeStream(account, region, request);
        String name = stream.path("StreamName").asText();
        String timecodeType = headers.getOrDefault("x-amzn-fragment-timecode-type", "ABSOLUTE");
        if (!"ABSOLUTE".equals(timecodeType) && !"RELATIVE".equals(timecodeType)) {
            throw invalid("x-amzn-fragment-timecode-type must be ABSOLUTE or RELATIVE.");
        }
        double start = 0;
        if ("RELATIVE".equals(timecodeType)) {
            String startHeader = headers.get("x-amzn-producer-start-timestamp");
            if (startHeader == null) {
                throw invalid("RELATIVE timecodes need x-amzn-producer-start-timestamp.");
            }
            start = Double.parseDouble(startHeader);
        }
        boolean persisted = stream.path("DataRetentionInHours").asInt() > 0;
        Mkv.Reader reader = new Mkv.Reader(body);
        Mkv.Cluster previous = null;
        Mkv.StreamHeader previousHeader = null;
        while (true) {
            Mkv.Cluster cluster;
            try {
                cluster = reader.next();
            } catch (IOException e) {
                ack(acks, mapper().createObjectNode().put("EventType", "ERROR").put("ErrorId", 4007)
                        .put("ErrorMessage", "Invalid MKV: " + e.getMessage()));
                break;
            }
            if (previous != null) {
                long nextTimecode = cluster == null ? previous.lastBlockTimecode() : cluster.timecode();
                store(account, region, name, previousHeader, previous, timecodeType, start, nextTimecode, persisted,
                        acks);
            }
            if (cluster == null) {
                break;
            }
            if (cluster.bytes().length > MAX_FRAGMENT_BYTES) {
                ack(acks, mapper().createObjectNode().put("EventType", "ERROR").put("ErrorId", 4002)
                        .put("FragmentTimecode", cluster.timecode()).put("ErrorMessage", "The fragment exceeds 50 MB."));
                previous = null;
                continue;
            }
            previous = cluster;
            previousHeader = reader.header();
        }
    }

    private void store(String account, String region, String name, Mkv.StreamHeader header, Mkv.Cluster cluster,
                       String timecodeType, double start, long nextTimecode, boolean persisted, OutputStream acks)
            throws IOException {
        long now = System.currentTimeMillis();
        double scaleMs = header.timecodeScale() / 1_000_000.0;
        double producerMs = "RELATIVE".equals(timecodeType) ? start * 1000 + cluster.timecode() * scaleMs
                : cluster.timecode() * scaleMs;
        long durationMs = Math.max(0, Math.round((nextTimecode - cluster.timecode()) * scaleMs));
        String number = number(now);
        ack(acks, mapper().createObjectNode().put("EventType", "BUFFERING").put("FragmentTimecode",
                cluster.timecode()).put("FragmentNumber", number));
        ObjectNode fragment = mapper().createObjectNode();
        fragment.put("FragmentNumber", number).put("FragmentSizeInBytes", cluster.bytes().length)
                .put("ProducerTimestamp", producerMs / 1000.0).put("ServerTimestamp", now / 1000.0)
                .put("FragmentLengthInMilliseconds", durationMs);
        ObjectNode m = meta(fragment);
        Base64.Encoder b64 = Base64.getEncoder();
        m.put("cluster", b64.encodeToString(cluster.bytes())).put("ebml", b64.encodeToString(header.ebml()))
                .put("info", b64.encodeToString(header.info())).put("tracks", b64.encodeToString(header.tracks()))
                .put("keyFrame", cluster.keyFrame()).put("codecPrivate", header.codecPrivate())
                .put("timecode", cluster.timecode()).put("scale", header.timecodeScale());
        header.codecs().forEach(c -> m.withArray("codecs").add(c));
        store.putFragment(account, region, name, fragment);
        ack(acks, mapper().createObjectNode().put("EventType", "RECEIVED").put("FragmentTimecode",
                cluster.timecode()).put("FragmentNumber", number));
        if (persisted) {
            ack(acks, mapper().createObjectNode().put("EventType", "PERSISTED").put("FragmentTimecode",
                    cluster.timecode()).put("FragmentNumber", number));
        }
        List<Consumer<String>> current;
        synchronized (this) {
            current = new ArrayList<>(listeners);
            notifyAll();
        }
        for (Consumer<String> listener : current) {
            try {
                listener.accept(account + "/" + region + "/" + name);
            } catch (RuntimeException e) {
                LOG.debugf("Kinesis Video fragment listener failed: %s", e.getMessage());
            }
        }
    }

    private void ack(OutputStream acks, ObjectNode event) throws IOException {
        acks.write((event.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        acks.flush();
    }

    // ---------------------------------------------------------------- reading fragments

    /** A stored fragment as a whole Matroska document with its tags. */
    static byte[] document(ObjectNode fragment, String continuation) {
        ObjectNode m = meta(fragment);
        Base64.Decoder b64 = Base64.getDecoder();
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("AWS_KINESISVIDEO_FRAGMENT_NUMBER", fragment.path("FragmentNumber").asText());
        tags.put("AWS_KINESISVIDEO_SERVER_TIMESTAMP", String.format("%.3f", fragment.path("ServerTimestamp")
                .asDouble()));
        tags.put("AWS_KINESISVIDEO_PRODUCER_TIMESTAMP", String.format("%.3f", fragment.path("ProducerTimestamp")
                .asDouble()));
        if (continuation != null) {
            tags.put("AWS_KINESISVIDEO_CONTINUATION_TOKEN", continuation);
        }
        return Mkv.fragment(b64.decode(m.path("ebml").asText()), b64.decode(m.path("info").asText()),
                b64.decode(m.path("tracks").asText()), tags, b64.decode(m.path("cluster").asText()));
    }

    /** The bytes of a fragment as a standalone Matroska file, without tags (for transcoding). */
    static byte[] plain(ObjectNode fragment) {
        ObjectNode m = meta(fragment);
        Base64.Decoder b64 = Base64.getDecoder();
        return Mkv.concat(b64.decode(m.path("ebml").asText()), Mkv.encodeId(Mkv.SEGMENT), Mkv.encodeSize(
                Mkv.UNKNOWN), b64.decode(m.path("info").asText()), b64.decode(m.path("tracks").asText()),
                b64.decode(m.path("cluster").asText()));
    }

    // ---------------------------------------------------------------- GetMedia

    /** Resolves a GetMedia request before its response starts: the stream and where to begin. */
    record Start(String account, String region, ObjectNode stream, String afterNumber, double afterTime,
                 boolean producerTime, boolean onlyNew) {
    }

    Start start(String account, String region, JsonNode in) {
        ObjectNode stream = service.activeStream(account, region, in);
        JsonNode selector = in.path("StartSelector");
        String type = selector.path("StartSelectorType").asText();
        List<ObjectNode> fragments = fragments(account, region, stream);
        return switch (type) {
            case "EARLIEST" -> new Start(account, region, stream, null, -1, false, false);
            case "NOW" -> new Start(account, region, stream, fragments.isEmpty() ? null
                    : fragments.get(fragments.size() - 1).path("FragmentNumber").asText(), -1, false, true);
            case "FRAGMENT_NUMBER" -> {
                String after = selector.path("AfterFragmentNumber").asText(null);
                if (after == null || fragments.stream().noneMatch(f -> after.equals(f.path("FragmentNumber")
                        .asText()))) {
                    throw invalid("The fragment number " + after + " is not in the stream.");
                }
                yield new Start(account, region, stream, after, -1, false, false);
            }
            case "CONTINUATION_TOKEN" -> {
                String token = selector.path("ContinuationToken").asText(null);
                if (token == null) {
                    throw invalid("CONTINUATION_TOKEN needs a ContinuationToken.");
                }
                yield new Start(account, region, stream, token, -1, false, false);
            }
            case "SERVER_TIMESTAMP", "PRODUCER_TIMESTAMP" -> {
                if (!selector.hasNonNull("StartTimestamp")) {
                    throw invalid(type + " needs a StartTimestamp.");
                }
                yield new Start(account, region, stream, null, selector.get("StartTimestamp").asDouble(),
                        "PRODUCER_TIMESTAMP".equals(type), false);
            }
            default -> throw invalid("Unknown StartSelectorType " + type + ".");
        };
    }

    private static int compareNumbers(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }

    /** Streams fragments from where the request starts, follows the stream until it's idle, then closes. */
    void getMedia(Start start, OutputStream out) throws IOException {
        String name = start.stream().path("StreamName").asText();
        String last = start.afterNumber();
        long idleUntil = System.currentTimeMillis() + store.config().getMediaIdleMillis();
        ObjectNode lastFragment = null;
        out.flush();
        while (true) {
            List<ObjectNode> pending = new ArrayList<>();
            for (ObjectNode fragment : fragments(start.account(), start.region(), start.stream())) {
                String number = fragment.path("FragmentNumber").asText();
                if (last != null && compareNumbers(number, last) <= 0) {
                    continue;
                }
                if (last == null && start.afterTime() >= 0 && fragment.path(start.producerTime()
                        ? "ProducerTimestamp" : "ServerTimestamp").asDouble() < start.afterTime()) {
                    continue;
                }
                pending.add(fragment);
            }
            for (ObjectNode fragment : pending) {
                out.write(document(fragment, null));
                out.flush();
                last = fragment.path("FragmentNumber").asText();
                lastFragment = fragment;
                idleUntil = System.currentTimeMillis() + store.config().getMediaIdleMillis();
            }
            long wait = idleUntil - System.currentTimeMillis();
            if (wait <= 0) {
                break;
            }
            synchronized (this) {
                try {
                    wait(Math.min(wait, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lastFragment != null) {
            ObjectNode m = meta(lastFragment);
            Base64.Decoder b64 = Base64.getDecoder();
            out.write(Mkv.continuation(b64.decode(m.path("ebml").asText()), b64.decode(m.path("info").asText()),
                    b64.decode(m.path("tracks").asText()), last));
        }
        out.flush();
    }

    static AwsException noFragments(String what) {
        return KvsStore.notFound("No fragments found in the stream for the " + what + " request.");
    }

    static String name(ObjectNode stream) {
        return stream.path("StreamName").asText();
    }
}

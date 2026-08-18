package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FirehoseService {

    private static final Logger LOG = Logger.getLogger(FirehoseService.class);
    private static final String DEFAULT_BUCKET = "floci-firehose-results";
    private static final int DEFAULT_FLUSH_COUNT = 5;

    private final StorageBackend<String, DeliveryStreamDescription> streamStore;
    private final Map<String, List<byte[]>> buffers = new ConcurrentHashMap<>();
    private final S3Service s3Service;
    private final RegionResolver regionResolver;

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, RegionResolver regionResolver) {
        this.streamStore = storageFactory.create("firehose", "streams.json",
                new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.s3Service = s3Service;
        this.regionResolver = regionResolver;
    }

    public String createDeliveryStream(String name, S3Destination s3Config) {
        return createDeliveryStream(name, s3Config, List.of());
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags) {
        return createDeliveryStream(name, s3Config, tags, null);
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType) {
        return createDeliveryStream(name, s3Config, tags, deliveryStreamType, null, null);
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType,
                                       DeliveryStreamDescription.KinesisStreamSourceDescription kinesisSource,
                                       DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration encryption) {
        if (name == null || name.isEmpty() || name.length() > 64 || !name.matches("[a-zA-Z0-9_.-]+")) {
            throw new AwsException("InvalidArgumentException",
                    "Delivery stream name must be between 1 and 64 characters and contain only letters, numbers, underscores, hyphens, or periods.", 400);
        }

        if (streamStore.get(name).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Delivery stream " + name + " already exists.", 409);
        }

        validateBufferingHints(s3Config);
        boolean hasKinesisSource = kinesisSource != null
                && kinesisSource.getKinesisStreamArn() != null
                && !kinesisSource.getKinesisStreamArn().isBlank();
        if ("KinesisStreamAsSource".equals(deliveryStreamType) && !hasKinesisSource) {
            throw new AwsException("InvalidArgumentException",
                    "KinesisStreamSourceConfiguration is required when DeliveryStreamType is KinesisStreamAsSource.", 400);
        }
        if (hasKinesisSource && "DirectPut".equals(deliveryStreamType)) {
            throw new AwsException("InvalidArgumentException",
                    "KinesisStreamSourceConfiguration cannot be set when DeliveryStreamType is DirectPut.", 400);
        }
        if (hasKinesisSource && (kinesisSource.getRoleArn() == null || kinesisSource.getRoleArn().isBlank())) {
            throw new AwsException("InvalidArgumentException",
                    "KinesisStreamSourceConfiguration.RoleARN must not be null or empty.", 400);
        }

        String resolvedType = hasKinesisSource
                ? "KinesisStreamAsSource"
                : (deliveryStreamType != null && !deliveryStreamType.isBlank() ? deliveryStreamType : "DirectPut");
        if (encryption != null && "KinesisStreamAsSource".equals(resolvedType)) {
            throw new AwsException("InvalidArgumentException",
                    "Server-side encryption is not supported for delivery streams with a Kinesis stream as source.", 400);
        }

        String arn = AwsArnUtils.Arn.of("firehose", regionResolver.getDefaultRegion(), regionResolver.getAccountId(), "deliverystream/" + name).toString();
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn, s3Config);
        description.setAccountId(regionResolver.getAccountId());
        description.setTags(tags);
        description.setDeliveryStreamType(resolvedType);
        if (hasKinesisSource) {
            if (kinesisSource.getDeliveryStartTimestamp() == null) {
                kinesisSource.setDeliveryStartTimestamp(java.time.Instant.now());
            }
            description.setSource(new DeliveryStreamDescription.Source(kinesisSource));
        }
        if (encryption != null) {
            applyEncryptionInput(description, encryption);
        } else {
            description.setDeliveryStreamEncryptionConfiguration(
                    DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.disabled());
        }
        streamStore.put(name, description);
        buffers.put(name, Collections.synchronizedList(new ArrayList<>()));
        LOG.infov("Created Firehose delivery stream: {0}", name);
        return arn;
    }

    public void updateDestination(String name, String currentVersionId, String destinationId, S3Destination update) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        if (!stream.getVersionId().equals(currentVersionId)) {
            throw new AwsException("ConcurrentModificationException",
                    "Cannot update firehose: " + name + " since the current version id: " + stream.getVersionId()
                            + " and specified version id: " + currentVersionId + " do not match", 400);
        }
        DeliveryStreamDescription.Destination destination = stream.getDestinations() != null && !stream.getDestinations().isEmpty()
                ? stream.getDestinations().get(0)
                : null;
        if (destination == null || !destination.getDestinationId().equals(destinationId)) {
            throw new AwsException("InvalidArgumentException",
                    "Destination Id " + destinationId + " not found", 400);
        }
        if (update == null) {
            throw new AwsException("InvalidArgumentException",
                    "A destination update is required for UpdateDestination.", 400);
        }
        validateBufferingHints(update);
        S3Destination current = destination.getExtendedS3DestinationDescription();
        if (current == null) {
            update.applyDefaults();
            destination.setExtendedS3DestinationDescription(update);
        } else {
            mergeDestination(current, update);
        }
        stream.setVersionId(String.valueOf(parseVersionId(stream.getVersionId()) + 1));
        stream.setLastUpdateTimestamp(java.time.Instant.now());
        streamStore.put(name, stream);
        LOG.infov("Updated destination {0} of Firehose delivery stream {1}", destinationId, name);
    }

    // A corrupt persisted version can only reach here when the caller echoed it
    // (the equality check above passed), so self-heal instead of failing with a 500
    // or blaming the client.
    private static long parseVersionId(String versionId) {
        try {
            return Long.parseLong(versionId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * AWS requires SizeInMBs and IntervalInSeconds to be specified together:
     * "This parameter is optional but if you specify a value for it, you must
     * also specify a value for IntervalInSeconds, and vice versa" (firehose
     * service-2.json). Rejecting partial hints here is what keeps the
     * whole-object replacement in mergeDestination faithful to AWS.
     */
    private static void validateBufferingHints(S3Destination config) {
        DeliveryStreamDescription.BufferingHints hints = config == null ? null : config.getBufferingHints();
        if (hints == null) {
            return;
        }
        if ((hints.getSizeInMBs() == null) != (hints.getIntervalInSeconds() == null)) {
            throw new AwsException("InvalidArgumentException",
                    "If you specify a value for SizeInMBs, you must also specify a value for IntervalInSeconds, and vice versa.",
                    400);
        }
    }

    private static void mergeDestination(S3Destination current, S3Destination update) {
        if (update.getRoleArn() != null) current.setRoleArn(update.getRoleArn());
        if (update.getBucketArn() != null) current.setBucketArn(update.getBucketArn());
        if (update.getPrefix() != null) current.setPrefix(update.getPrefix());
        if (update.getErrorOutputPrefix() != null) current.setErrorOutputPrefix(update.getErrorOutputPrefix());
        if (update.getCompressionFormat() != null) current.setCompressionFormat(update.getCompressionFormat());
        if (update.getBufferingHints() != null) current.setBufferingHints(update.getBufferingHints());
        if (update.getEncryptionConfiguration() != null) current.setEncryptionConfiguration(update.getEncryptionConfiguration());
    }

    public void tagDeliveryStream(String name, List<DeliveryStreamDescription.Tag> tagsToTag) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        Map<String, String> tagMap = new LinkedHashMap<>();
        for (DeliveryStreamDescription.Tag t : stream.getTags()) {
            tagMap.put(t.getKey(), t.getValue());
        }
        for (DeliveryStreamDescription.Tag t : tagsToTag) {
            tagMap.put(t.getKey(), t.getValue());
        }
        List<DeliveryStreamDescription.Tag> newTags = new ArrayList<>();
        tagMap.forEach((k, v) -> newTags.add(new DeliveryStreamDescription.Tag(k, v)));
        stream.setTags(newTags);
        streamStore.put(name, stream);
        LOG.infov("Tagged Firehose delivery stream {0}: {1}", name, tagsToTag);
    }

    public void untagDeliveryStream(String name, List<String> tagKeys) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        List<DeliveryStreamDescription.Tag> newTags = new ArrayList<>();
        for (DeliveryStreamDescription.Tag t : stream.getTags()) {
            if (!tagKeys.contains(t.getKey())) {
                newTags.add(t);
            }
        }
        stream.setTags(newTags);
        streamStore.put(name, stream);
        LOG.infov("Untagged Firehose delivery stream {0}: {1}", name, tagKeys);
    }

    public List<DeliveryStreamDescription.Tag> listTagsForDeliveryStream(String name, String exclusiveStartTagKey, Integer limit) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        List<DeliveryStreamDescription.Tag> tags = stream.getTags();
        int startIndex = 0;
        if (exclusiveStartTagKey != null && !exclusiveStartTagKey.isEmpty()) {
            for (int i = 0; i < tags.size(); i++) {
                if (tags.get(i).getKey().equals(exclusiveStartTagKey)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }
        int size = tags.size() - startIndex;
        int end = tags.size();
        if (limit != null && limit > 0 && limit < size) {
            end = startIndex + limit;
        }
        return new ArrayList<>(tags.subList(startIndex, end));
    }

    public DeliveryStreamDescription describeDeliveryStream(String name) {
        DeliveryStreamDescription stream = streamStore.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Delivery stream not found: " + name, 400));
        // Normalizes streams persisted before required output members existed.
        if (stream.s3Destination() != null) {
            stream.s3Destination().applyDefaults();
        }
        if (stream.getDeliveryStreamEncryptionConfiguration() == null) {
            stream.setDeliveryStreamEncryptionConfiguration(
                    DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.disabled());
        }
        return stream;
    }

    public void startDeliveryStreamEncryption(String name,
                                              DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration encryption) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        if ("KinesisStreamAsSource".equals(stream.getDeliveryStreamType())) {
            throw new AwsException("InvalidArgumentException",
                    "Server-side encryption is not supported for delivery streams with a Kinesis stream as source.", 400);
        }
        applyEncryptionInput(stream, encryption);
        stream.setLastUpdateTimestamp(java.time.Instant.now());
        streamStore.put(name, stream);
        LOG.infov("Enabled SSE on Firehose delivery stream {0}", name);
    }

    public void stopDeliveryStreamEncryption(String name) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        stream.setDeliveryStreamEncryptionConfiguration(
                DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.disabled());
        stream.setLastUpdateTimestamp(java.time.Instant.now());
        streamStore.put(name, stream);
        LOG.infov("Disabled SSE on Firehose delivery stream {0}", name);
    }

    private static void applyEncryptionInput(DeliveryStreamDescription stream,
                                             DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration encryption) {
        if (encryption == null || encryption.getKeyType() == null || encryption.getKeyType().isBlank()) {
            throw new AwsException("InvalidArgumentException",
                    "DeliveryStreamEncryptionConfigurationInput.KeyType is required.", 400);
        }
        String keyType = encryption.getKeyType();
        if (!"AWS_OWNED_CMK".equals(keyType) && !"CUSTOMER_MANAGED_CMK".equals(keyType)) {
            throw new AwsException("InvalidArgumentException",
                    "KeyType must be AWS_OWNED_CMK or CUSTOMER_MANAGED_CMK.", 400);
        }
        if ("CUSTOMER_MANAGED_CMK".equals(keyType)
                && (encryption.getKeyArn() == null || encryption.getKeyArn().isBlank())) {
            throw new AwsException("InvalidArgumentException",
                    "KeyARN is required when KeyType is CUSTOMER_MANAGED_CMK.", 400);
        }
        stream.setDeliveryStreamEncryptionConfiguration(
                DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.enabled(
                        keyType, encryption.getKeyArn()));
    }

    public void deleteDeliveryStream(String name) {
        describeDeliveryStream(name);
        streamStore.delete(name);
        buffers.remove(name);
        LOG.infov("Deleted Firehose delivery stream: {0}", name);
    }

    public List<String> listDeliveryStreams() {
        return listDeliveryStreams(null, Integer.MAX_VALUE, null).names();
    }

    /**
     * AWS-shaped list: names are alphabetical and {@code ExclusiveStartDeliveryStreamName}
     * is exclusive. When {@code Limit} is set, the page is capped (AWS max 10000).
     * When omitted, every matching name is returned — AWS defaults to 10, but
     * callers that do not paginate (SDK compat + Alchemy bindings) expect a
     * complete list on a shared emulator.
     */
    public DeliveryStreamList listDeliveryStreams(String exclusiveStart, Integer limit, String type) {
        List<String> names = streamStore.scan(k -> true).stream()
                .filter(s -> type == null || type.isBlank() || type.equals(s.getDeliveryStreamType()))
                .map(DeliveryStreamDescription::getDeliveryStreamName)
                .sorted()
                .toList();
        int start = 0;
        if (exclusiveStart != null && !exclusiveStart.isEmpty()) {
            while (start < names.size() && names.get(start).compareTo(exclusiveStart) <= 0) {
                start++;
            }
        }
        int effectiveLimit = (limit == null || limit < 1) ? Integer.MAX_VALUE : Math.min(limit, 10_000);
        int end = Math.min(start + effectiveLimit, names.size());
        return new DeliveryStreamList(new ArrayList<>(names.subList(start, end)), end < names.size());
    }

    public record DeliveryStreamList(List<String> names, boolean hasMore) {}

    public void putRecord(String streamName, Record record) {
        DeliveryStreamDescription stream = describeDeliveryStream(streamName);
        buffers.computeIfAbsent(streamName, k -> Collections.synchronizedList(new ArrayList<>()))
               .add(record.getData());

        if (buffers.get(streamName).size() >= DEFAULT_FLUSH_COUNT) {
            flush(streamName, stream);
        }
    }

    public void putRecordBatch(String streamName, List<Record> records) {
        DeliveryStreamDescription stream = describeDeliveryStream(streamName);
        List<byte[]> buffer = buffers.computeIfAbsent(
                streamName, k -> Collections.synchronizedList(new ArrayList<>()));
        for (Record r : records) {
            buffer.add(r.getData());
        }
        if (buffer.size() >= DEFAULT_FLUSH_COUNT) {
            flush(streamName, stream);
        }
    }

    public void flush(String streamName) {
        streamStore.get(streamName).ifPresent(stream -> flush(streamName, stream));
    }

    private void flush(String streamName, DeliveryStreamDescription stream) {
        List<byte[]> buffer = buffers.get(streamName);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        List<byte[]> toFlush;
        synchronized (buffer) {
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        }

        try {
            String bucket = resolveBucket(stream);
            String prefix = resolvePrefix(stream);
            String key = prefix + UUID.randomUUID() + ".json";

            ensureBucket(bucket);

            StringBuilder sb = new StringBuilder();
            for (byte[] data : toFlush) {
                sb.append(new String(data, StandardCharsets.UTF_8));
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
            }

            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            s3Service.putObject(bucket, key, body, "application/x-ndjson", Map.of());
            LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3}",
                    toFlush.size(), streamName, bucket, key);
        } catch (Exception e) {
            LOG.errorv("Failed to flush Firehose stream {0}: {1}", streamName, e.getMessage());
        }
    }

    private String resolveBucket(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        if (s3 != null && s3.bucketName() != null) {
            return s3.bucketName();
        }
        return DEFAULT_BUCKET;
    }

    private String resolvePrefix(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        String prefix = (s3 != null && s3.getPrefix() != null) ? s3.getPrefix() : stream.getDeliveryStreamName() + "/";

        // Substitute time-based placeholders matching real Firehose
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        prefix = prefix
                .replace("{year}", String.format("%04d", now.getYear()))
                .replace("{month}", String.format("%02d", now.getMonthValue()))
                .replace("{day}", String.format("%02d", now.getDayOfMonth()))
                .replace("{hour}", String.format("%02d", now.getHour()));

        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private void ensureBucket(String bucket) {
        try {
            s3Service.createBucket(bucket, regionResolver.getDefaultRegion());
        } catch (Exception ignored) {}
    }
}

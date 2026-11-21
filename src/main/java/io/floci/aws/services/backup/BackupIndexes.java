package io.floci.aws.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.backup.model.RecoveryPoint;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Backup indexes: what a recovery point holds, recorded so AWS Backup Search can search it. An S3 recovery
 * point's index lists the objects of the bucket when it was indexed (key, size, ETag, version and creation time,
 * every version of a versioned bucket); an EBS recovery point's index has no files, as Floci's volumes hold none.
 * Other resource types can't be indexed, as on AWS.
 */
@ApplicationScoped
public class BackupIndexes implements Resettable {
    private static final int PAGE = 1000;

    private final AccountAwareStorageBackend<ObjectNode> indexes;
    private final Instance<S3Service> s3;
    private final ObjectMapper mapper;

    @Inject
    public BackupIndexes(StorageFactory storageFactory, Instance<S3Service> s3, ObjectMapper mapper) {
        this.indexes = storageFactory.create("backup", "backup-recovery-point-indexes.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.s3 = s3;
        this.mapper = mapper;
    }

    static boolean indexable(String resourceType) {
        return "S3".equals(resourceType) || "EBS".equals(resourceType);
    }

    /** Indexes a recovery point now and records the index's status on it. */
    public synchronized void build(RecoveryPoint point, Map<String, String> tags) {
        ObjectNode index = mapper.createObjectNode();
        long now = Instant.now().getEpochSecond();
        index.put("RecoveryPointArn", point.getRecoveryPointArn());
        index.put("BackupVaultArn", point.getBackupVaultArn());
        index.put("BackupVaultName", point.getBackupVaultName());
        index.put("SourceResourceArn", point.getResourceArn());
        index.put("ResourceType", point.getResourceType());
        if (point.getIamRoleArn() != null) {
            index.put("IamRoleArn", point.getIamRoleArn());
        }
        index.put("BackupCreationDate", point.getCreationDate());
        index.put("IndexCreationDate", now);
        ObjectNode tagNode = index.putObject("Tags");
        if (tags != null) {
            tags.forEach(tagNode::put);
        }
        ArrayNode items = index.putArray("Items");
        try {
            if ("S3".equals(point.getResourceType())) {
                indexBucket(AwsArnUtils.parse(point.getResourceArn()).resource(), items);
            }
            index.put("IndexStatus", "ACTIVE");
            point.setIndexStatus("ACTIVE");
            point.setIndexStatusMessage(null);
        } catch (AwsException | IllegalArgumentException e) {
            index.put("IndexStatus", "FAILED");
            index.put("IndexStatusMessage", "The source resource couldn't be read: " + e.getMessage());
            point.setIndexStatus("FAILED");
            point.setIndexStatusMessage(index.path("IndexStatusMessage").asText());
        }
        index.put("IndexCompletionDate", Instant.now().getEpochSecond());
        index.put("TotalItemsIndexed", items.size());
        indexes.put(point.getRecoveryPointArn(), index);
    }

    private void indexBucket(String bucket, ArrayNode items) {
        if (!s3.isResolvable()) {
            return;
        }
        String keyMarker = null;
        String versionMarker = null;
        while (true) {
            S3Service.ListVersionsResult page = s3.get().listObjectVersions(bucket, null, null, PAGE, keyMarker,
                    versionMarker);
            for (S3Object object : page.versions()) {
                if (object.isDeleteMarker()) {
                    continue;
                }
                ObjectNode item = items.addObject();
                item.put("ObjectKey", object.getKey());
                item.put("ObjectSize", object.getSize());
                if (object.getETag() != null) {
                    item.put("ETag", object.getETag().replace("\"", ""));
                }
                item.put("VersionId", object.getVersionId() == null ? "null" : object.getVersionId());
                if (object.getLastModified() != null) {
                    item.put("CreationTime", object.getLastModified().toEpochMilli() / 1000.0);
                }
            }
            if (!page.isTruncated()) {
                return;
            }
            keyMarker = page.nextKeyMarker();
            versionMarker = page.nextVersionIdMarker();
        }
    }

    public synchronized Optional<ObjectNode> get(String recoveryPointArn) {
        return indexes.get(recoveryPointArn);
    }

    public synchronized void delete(String recoveryPointArn) {
        indexes.delete(recoveryPointArn);
    }

    /** The calling account's indexes in a Region, with their items. */
    public synchronized List<ObjectNode> all(String region) {
        return indexes.scan(k -> region.equals(AwsArnUtils.isArn(k) ? AwsArnUtils.parse(k).region() : ""));
    }

    @Override
    public void clear() {
        indexes.clear();
    }
}

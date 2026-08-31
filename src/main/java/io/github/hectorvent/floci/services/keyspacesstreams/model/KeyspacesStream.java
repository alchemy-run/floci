package io.github.hectorvent.floci.services.keyspacesstreams.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory CDC stream for an Amazon Keyspaces table.
 */
public class KeyspacesStream {

    public static final String SHARD_ID = "shardId-000000000001-000000000000000001";

    private String streamArn;
    private String streamLabel;
    private String streamStatus;
    private String streamViewType;
    private String keyspaceName;
    private String tableName;
    private Instant creationRequestDateTime;
    private String startingSequenceNumber;
    private final ConcurrentLinkedDeque<KeyspacesChangeRecord> records = new ConcurrentLinkedDeque<>();

    public String getStreamArn() {
        return streamArn;
    }

    public void setStreamArn(String streamArn) {
        this.streamArn = streamArn;
    }

    public String getStreamLabel() {
        return streamLabel;
    }

    public void setStreamLabel(String streamLabel) {
        this.streamLabel = streamLabel;
    }

    public String getStreamStatus() {
        return streamStatus;
    }

    public void setStreamStatus(String streamStatus) {
        this.streamStatus = streamStatus;
    }

    public String getStreamViewType() {
        return streamViewType;
    }

    public void setStreamViewType(String streamViewType) {
        this.streamViewType = streamViewType;
    }

    public String getKeyspaceName() {
        return keyspaceName;
    }

    public void setKeyspaceName(String keyspaceName) {
        this.keyspaceName = keyspaceName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Instant getCreationRequestDateTime() {
        return creationRequestDateTime;
    }

    public void setCreationRequestDateTime(Instant creationRequestDateTime) {
        this.creationRequestDateTime = creationRequestDateTime;
    }

    public String getStartingSequenceNumber() {
        return startingSequenceNumber;
    }

    public void setStartingSequenceNumber(String startingSequenceNumber) {
        this.startingSequenceNumber = startingSequenceNumber;
    }

    public ConcurrentLinkedDeque<KeyspacesChangeRecord> getRecords() {
        return records;
    }

    public List<KeyspacesChangeRecord> snapshotRecords() {
        return new ArrayList<>(records);
    }
}

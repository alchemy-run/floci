package io.github.hectorvent.floci.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * One entry in a durable execution's checkpointed operation log.
 *
 * <p>Operation ids are opaque strings chosen by the client SDK (it hashes its
 * deterministic step ids before checkpointing), except for the EXECUTION
 * operation which Floci mints when the execution starts. Floci never
 * interprets ids beyond map lookup.
 *
 * <p>Timestamps are stored as epoch milliseconds internally and serialized to
 * the AWS wire format (epoch seconds) by the service.
 */
@RegisterForReflection
public class DurableOperation {

    /** Operation types (subset of the AWS model Floci understands). */
    public static final String TYPE_EXECUTION = "EXECUTION";
    public static final String TYPE_CONTEXT = "CONTEXT";
    public static final String TYPE_STEP = "STEP";
    public static final String TYPE_WAIT = "WAIT";
    public static final String TYPE_CALLBACK = "CALLBACK";
    public static final String TYPE_CHAINED_INVOKE = "CHAINED_INVOKE";

    /** Operation statuses. */
    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";

    private String id;
    private String parentId;
    private String name;
    private String type;
    private String subType;
    private String status;
    private long startTimestampMillis;
    private Long endTimestampMillis;

    /** EXECUTION only: the customer input payload (raw string). */
    private String inputPayload;

    /** STEP only. */
    private Integer attempt;
    private Long nextAttemptTimestampMillis;

    /** WAIT only. */
    private Long scheduledEndTimestampMillis;

    /** CALLBACK only. */
    private String callbackId;

    /** Result payload for STEP / CONTEXT / CALLBACK / EXECUTION operations. */
    private String result;

    /** AWS ErrorObject shape ({@code ErrorMessage}, {@code ErrorType}, ...). */
    private Map<String, Object> error;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSubType() { return subType; }
    public void setSubType(String subType) { this.subType = subType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getStartTimestampMillis() { return startTimestampMillis; }
    public void setStartTimestampMillis(long startTimestampMillis) { this.startTimestampMillis = startTimestampMillis; }

    public Long getEndTimestampMillis() { return endTimestampMillis; }
    public void setEndTimestampMillis(Long endTimestampMillis) { this.endTimestampMillis = endTimestampMillis; }

    public String getInputPayload() { return inputPayload; }
    public void setInputPayload(String inputPayload) { this.inputPayload = inputPayload; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public Long getNextAttemptTimestampMillis() { return nextAttemptTimestampMillis; }
    public void setNextAttemptTimestampMillis(Long nextAttemptTimestampMillis) { this.nextAttemptTimestampMillis = nextAttemptTimestampMillis; }

    public Long getScheduledEndTimestampMillis() { return scheduledEndTimestampMillis; }
    public void setScheduledEndTimestampMillis(Long scheduledEndTimestampMillis) { this.scheduledEndTimestampMillis = scheduledEndTimestampMillis; }

    public String getCallbackId() { return callbackId; }
    public void setCallbackId(String callbackId) { this.callbackId = callbackId; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Map<String, Object> getError() { return error; }
    public void setError(Map<String, Object> error) { this.error = error; }
}

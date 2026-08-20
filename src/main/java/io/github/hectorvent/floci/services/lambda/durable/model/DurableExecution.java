package io.github.hectorvent.floci.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Lambda Durable Execution: one named, checkpointed run of a durable
 * function. The operation log (EXECUTION operation first, then every
 * checkpointed STEP / WAIT / CALLBACK / CONTEXT operation) is the source of
 * truth the client SDK replays against on each (re-)invocation.
 */
@RegisterForReflection
public class DurableExecution {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";
    public static final String STATUS_STOPPED = "STOPPED";

    private String executionArn;
    private String executionName;
    private String region;
    private String accountId;
    /** Short function name (no qualifier). */
    private String functionName;
    /** Version or alias captured at start, or null. */
    private String qualifier;
    /** Qualified function ARN reported by Get/List APIs. */
    private String functionArn;

    private String status = STATUS_RUNNING;
    /** Raw customer input payload (the Invoke request body). */
    private String inputPayload;
    /** Final result payload when SUCCEEDED. */
    private String result;
    /** AWS ErrorObject shape when FAILED / STOPPED / TIMED_OUT. */
    private Map<String, Object> error;

    private long startTimestampMillis;
    private Long endTimestampMillis;

    /** Token the currently-outstanding invocation must checkpoint with. */
    private String checkpointToken;

    /** Consecutive invocation-level failures (reset on a clean PENDING/terminal). */
    private int invocationFailures;

    /** Ordered operation log; the EXECUTION operation is always first. */
    private List<DurableOperation> operations = new ArrayList<>();

    public String getExecutionArn() { return executionArn; }
    public void setExecutionArn(String executionArn) { this.executionArn = executionArn; }

    public String getExecutionName() { return executionName; }
    public void setExecutionName(String executionName) { this.executionName = executionName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }

    public String getQualifier() { return qualifier; }
    public void setQualifier(String qualifier) { this.qualifier = qualifier; }

    public String getFunctionArn() { return functionArn; }
    public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInputPayload() { return inputPayload; }
    public void setInputPayload(String inputPayload) { this.inputPayload = inputPayload; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Map<String, Object> getError() { return error; }
    public void setError(Map<String, Object> error) { this.error = error; }

    public long getStartTimestampMillis() { return startTimestampMillis; }
    public void setStartTimestampMillis(long startTimestampMillis) { this.startTimestampMillis = startTimestampMillis; }

    public Long getEndTimestampMillis() { return endTimestampMillis; }
    public void setEndTimestampMillis(Long endTimestampMillis) { this.endTimestampMillis = endTimestampMillis; }

    public String getCheckpointToken() { return checkpointToken; }
    public void setCheckpointToken(String checkpointToken) { this.checkpointToken = checkpointToken; }

    public int getInvocationFailures() { return invocationFailures; }
    public void setInvocationFailures(int invocationFailures) { this.invocationFailures = invocationFailures; }

    public List<DurableOperation> getOperations() { return operations; }
    public void setOperations(List<DurableOperation> operations) { this.operations = operations; }

    public boolean isRunning() {
        return STATUS_RUNNING.equals(status);
    }

    public DurableOperation findOperation(String id) {
        for (DurableOperation op : operations) {
            if (op.getId().equals(id)) {
                return op;
            }
        }
        return null;
    }
}

package io.github.hectorvent.floci.services.lambda.model;

import java.util.concurrent.CompletableFuture;

public class PendingInvocation {

    private final String requestId;
    private final byte[] payload;
    private final int timeoutSeconds;
    private final String functionArn;
    private final CompletableFuture<InvokeResult> resultFuture;
    private volatile long deadlineMs;

    public PendingInvocation(String requestId, byte[] payload, long deadlineMs,
                              String functionArn, CompletableFuture<InvokeResult> resultFuture) {
        this.requestId = requestId;
        this.payload = payload;
        this.timeoutSeconds = 0;
        this.functionArn = functionArn;
        this.resultFuture = resultFuture;
        this.deadlineMs = deadlineMs;
    }

    /**
     * Queue-safe invocation: the AWS timeout clock starts when the runtime
     * dequeues the event ({@link #markDispatched()}), not when it is enqueued.
     * Bursting Function URL callers therefore do not spend the 3s default
     * timeout sitting behind a single warm container.
     */
    public static PendingInvocation withExecutionTimeout(
            String requestId, byte[] payload, int timeoutSeconds,
            String functionArn, CompletableFuture<InvokeResult> resultFuture) {
        return new PendingInvocation(requestId, payload, timeoutSeconds, functionArn, resultFuture);
    }

    private PendingInvocation(String requestId, byte[] payload, int timeoutSeconds,
                              String functionArn, CompletableFuture<InvokeResult> resultFuture) {
        this.requestId = requestId;
        this.payload = payload;
        this.timeoutSeconds = timeoutSeconds;
        this.functionArn = functionArn;
        this.resultFuture = resultFuture;
        this.deadlineMs = 0L;
    }

    /** Starts the execution deadline if it was not already an absolute timestamp. */
    public void markDispatched() {
        if (deadlineMs <= 0) {
            int seconds = timeoutSeconds > 0 ? timeoutSeconds : 3;
            deadlineMs = System.currentTimeMillis() + seconds * 1000L;
        }
    }

    public String getRequestId() { return requestId; }
    public byte[] getPayload() { return payload; }
    public long getDeadlineMs() { return deadlineMs; }
    public String getFunctionArn() { return functionArn; }
    public CompletableFuture<InvokeResult> getResultFuture() { return resultFuture; }
}

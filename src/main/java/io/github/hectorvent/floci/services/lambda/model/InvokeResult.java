package io.github.hectorvent.floci.services.lambda.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class InvokeResult {

    private int statusCode;
    private byte[] payload;
    private String functionError;
    private String logResult;
    private String requestId;
    private String executedVersion;
    private StreamingPayload stream;

    public InvokeResult() {
    }

    public InvokeResult(int statusCode, String functionError, byte[] payload, String logResult, String requestId) {
        this.statusCode = statusCode;
        this.functionError = functionError;
        this.payload = payload;
        this.logResult = logResult;
        this.requestId = requestId;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getFunctionError() {
        return functionError;
    }

    public void setFunctionError(String functionError) {
        this.functionError = functionError;
    }

    /**
     * Full response payload. For a streaming result this drains the remaining
     * chunks first (blocking) and caches the assembled bytes, so buffered
     * consumers behave exactly as they did before streaming support existed.
     */
    public synchronized byte[] getPayload() {
        if (payload == null && stream != null) {
            try {
                payload = stream.drain();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to drain streaming response payload", e);
            }
        }
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getLogResult() {
        return logResult;
    }

    public void setLogResult(String logResult) {
        this.logResult = logResult;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** Live chunk stream for a streaming response, or null for buffered results. */
    public StreamingPayload getStream() {
        return stream;
    }

    public void setStream(StreamingPayload stream) {
        this.stream = stream;
    }

    public boolean isStreaming() {
        return stream != null;
    }

    public String getExecutedVersion() {
        return executedVersion;
    }

    public void setExecutedVersion(String executedVersion) {
        this.executedVersion = executedVersion;
    }
}

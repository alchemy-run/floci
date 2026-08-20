package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * In-memory Distributed Map Run. AWS ARN shape:
 * {@code arn:aws:states:region:account:mapRun:stateMachineName/executionName:uuid}
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapRun {
    private String mapRunArn;
    private String executionArn;
    private String status = "RUNNING";
    private double startDate;
    private Double stopDate;
    private int maxConcurrency;
    private double toleratedFailurePercentage;
    private long toleratedFailureCount;
    private int pending;
    private int running;
    private int succeeded;
    private int failed;
    private int timedOut;
    private int aborted;
    private int total;

    public MapRun() {
        this.startDate = System.currentTimeMillis() / 1000.0;
    }

    public String getMapRunArn() { return mapRunArn; }
    public void setMapRunArn(String mapRunArn) { this.mapRunArn = mapRunArn; }

    public String getExecutionArn() { return executionArn; }
    public void setExecutionArn(String executionArn) { this.executionArn = executionArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getStartDate() { return startDate; }
    public void setStartDate(double startDate) { this.startDate = startDate; }

    public Double getStopDate() { return stopDate; }
    public void setStopDate(Double stopDate) { this.stopDate = stopDate; }

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public double getToleratedFailurePercentage() { return toleratedFailurePercentage; }
    public void setToleratedFailurePercentage(double toleratedFailurePercentage) {
        this.toleratedFailurePercentage = toleratedFailurePercentage;
    }

    public long getToleratedFailureCount() { return toleratedFailureCount; }
    public void setToleratedFailureCount(long toleratedFailureCount) {
        this.toleratedFailureCount = toleratedFailureCount;
    }

    public int getPending() { return pending; }
    public void setPending(int pending) { this.pending = pending; }

    public int getRunning() { return running; }
    public void setRunning(int running) { this.running = running; }

    public int getSucceeded() { return succeeded; }
    public void setSucceeded(int succeeded) { this.succeeded = succeeded; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }

    public int getTimedOut() { return timedOut; }
    public void setTimedOut(int timedOut) { this.timedOut = timedOut; }

    public int getAborted() { return aborted; }
    public void setAborted(int aborted) { this.aborted = aborted; }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
}

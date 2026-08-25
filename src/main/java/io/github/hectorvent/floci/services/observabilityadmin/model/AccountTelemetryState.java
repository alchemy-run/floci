package io.github.hectorvent.floci.services.observabilityadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-account, per-region CloudWatch Observability Admin singleton state
 * (telemetry evaluation / enrichment onboarding).
 */
@RegisterForReflection
public class AccountTelemetryState {

    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String RUNNING = "RUNNING";
    public static final String STOPPED = "STOPPED";
    public static final String ENRICHMENT_STOPPED = "Stopped";
    public static final String ENRICHMENT_RUNNING = "Running";

    private String evaluationStatus = NOT_STARTED;
    private String enrichmentStatus = ENRICHMENT_STOPPED;
    private String homeRegion;

    public AccountTelemetryState() {
    }

    public String getEvaluationStatus() {
        return evaluationStatus;
    }

    public void setEvaluationStatus(String evaluationStatus) {
        this.evaluationStatus = evaluationStatus == null ? NOT_STARTED : evaluationStatus;
    }

    public String getEnrichmentStatus() {
        return enrichmentStatus;
    }

    public void setEnrichmentStatus(String enrichmentStatus) {
        this.enrichmentStatus = enrichmentStatus == null ? ENRICHMENT_STOPPED : enrichmentStatus;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public boolean evaluationOn() {
        return RUNNING.equals(evaluationStatus);
    }
}

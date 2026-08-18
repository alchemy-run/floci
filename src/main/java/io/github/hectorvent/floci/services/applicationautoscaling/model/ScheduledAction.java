package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A scheduled action attached to a scalable target.
 *
 * <p>Identity is the target triple plus {@code scheduledActionName}. {@code putScheduledAction}
 * is an upsert on that key; omitted {@code StartTime}/{@code EndTime} are deleted on update,
 * matching AWS.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_ScheduledAction.html">ScheduledAction</a>
 */
@RegisterForReflection
public class ScheduledAction {

    private String scheduledActionName;
    private String scheduledActionArn;
    private String serviceNamespace;
    private String resourceId;
    private String scalableDimension;
    private String schedule;
    private String timezone;
    private Double startTime;
    private Double endTime;
    private ScalableTargetAction scalableTargetAction;
    private double creationTime;

    public String getScheduledActionName() { return scheduledActionName; }
    public void setScheduledActionName(String v) { this.scheduledActionName = v; }

    public String getScheduledActionArn() { return scheduledActionArn; }
    public void setScheduledActionArn(String v) { this.scheduledActionArn = v; }

    public String getServiceNamespace() { return serviceNamespace; }
    public void setServiceNamespace(String v) { this.serviceNamespace = v; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String v) { this.resourceId = v; }

    public String getScalableDimension() { return scalableDimension; }
    public void setScalableDimension(String v) { this.scalableDimension = v; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String v) { this.schedule = v; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }

    public Double getStartTime() { return startTime; }
    public void setStartTime(Double v) { this.startTime = v; }

    public Double getEndTime() { return endTime; }
    public void setEndTime(Double v) { this.endTime = v; }

    public ScalableTargetAction getScalableTargetAction() { return scalableTargetAction; }
    public void setScalableTargetAction(ScalableTargetAction v) { this.scalableTargetAction = v; }

    public double getCreationTime() { return creationTime; }
    public void setCreationTime(double v) { this.creationTime = v; }
}

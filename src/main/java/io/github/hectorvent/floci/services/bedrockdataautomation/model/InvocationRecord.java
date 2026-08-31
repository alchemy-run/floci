package io.github.hectorvent.floci.services.bedrockdataautomation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Data Automation or blueprint-optimization invocation. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvocationRecord {

    private String invocationArn;
    private String kind;
    private String status;
    private String outputS3Uri;
    private String jobSubmissionTime;
    private String jobCompletionTime;

    public InvocationRecord() {
    }

    public String getInvocationArn() {
        return invocationArn;
    }

    public void setInvocationArn(String invocationArn) {
        this.invocationArn = invocationArn;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutputS3Uri() {
        return outputS3Uri;
    }

    public void setOutputS3Uri(String outputS3Uri) {
        this.outputS3Uri = outputS3Uri;
    }

    public String getJobSubmissionTime() {
        return jobSubmissionTime;
    }

    public void setJobSubmissionTime(String jobSubmissionTime) {
        this.jobSubmissionTime = jobSubmissionTime;
    }

    public String getJobCompletionTime() {
        return jobCompletionTime;
    }

    public void setJobCompletionTime(String jobCompletionTime) {
        this.jobCompletionTime = jobCompletionTime;
    }
}

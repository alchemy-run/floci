package io.github.hectorvent.floci.services.amplify.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An Amplify Hosting job. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AmplifyJob {

    private String jobArn;
    private String jobId;
    private String branchName;
    private String commitId;
    private String commitMessage;
    private Long commitTime;
    private long startTime;
    private String status;
    private Long endTime;
    private String jobType;
    private String sourceUrl;
    private String sourceUrlType;
    private String zipObjectKey;
    private List<AmplifyStep> steps = new ArrayList<>();
    private List<AmplifyArtifact> artifacts = new ArrayList<>();

    public AmplifyJob() {
    }

    public String getJobArn() {
        return jobArn;
    }

    public void setJobArn(String jobArn) {
        this.jobArn = jobArn;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public Long getCommitTime() {
        return commitTime;
    }

    public void setCommitTime(Long commitTime) {
        this.commitTime = commitTime;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSourceUrlType() {
        return sourceUrlType;
    }

    public void setSourceUrlType(String sourceUrlType) {
        this.sourceUrlType = sourceUrlType;
    }

    public String getZipObjectKey() {
        return zipObjectKey;
    }

    public void setZipObjectKey(String zipObjectKey) {
        this.zipObjectKey = zipObjectKey;
    }

    public List<AmplifyStep> getSteps() {
        if (steps == null) {
            steps = new ArrayList<>();
        }
        return steps;
    }

    public void setSteps(List<AmplifyStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
    }

    public List<AmplifyArtifact> getArtifacts() {
        if (artifacts == null) {
            artifacts = new ArrayList<>();
        }
        return artifacts;
    }

    public void setArtifacts(List<AmplifyArtifact> artifacts) {
        this.artifacts = artifacts == null ? new ArrayList<>() : new ArrayList<>(artifacts);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AmplifyStep {
        private String stepName;
        private long startTime;
        private String status;
        private Long endTime;
        private String logUrl;
        private String statusReason;

        public AmplifyStep() {
        }

        public String getStepName() {
            return stepName;
        }

        public void setStepName(String stepName) {
            this.stepName = stepName;
        }

        public long getStartTime() {
            return startTime;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getEndTime() {
            return endTime;
        }

        public void setEndTime(Long endTime) {
            this.endTime = endTime;
        }

        public String getLogUrl() {
            return logUrl;
        }

        public void setLogUrl(String logUrl) {
            this.logUrl = logUrl;
        }

        public String getStatusReason() {
            return statusReason;
        }

        public void setStatusReason(String statusReason) {
            this.statusReason = statusReason;
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AmplifyArtifact {
        private String artifactFileName;
        private String artifactId;
        private String artifactUrl;

        public AmplifyArtifact() {
        }

        public String getArtifactFileName() {
            return artifactFileName;
        }

        public void setArtifactFileName(String artifactFileName) {
            this.artifactFileName = artifactFileName;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        public String getArtifactUrl() {
            return artifactUrl;
        }

        public void setArtifactUrl(String artifactUrl) {
            this.artifactUrl = artifactUrl;
        }
    }
}

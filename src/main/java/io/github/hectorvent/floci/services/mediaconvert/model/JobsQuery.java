package io.github.hectorvent.floci.services.mediaconvert.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An asynchronous MediaConvert jobs query. */
@RegisterForReflection
public class JobsQuery {

    private String id;
    private String accountId;
    private String status;
    private List<MediaConvertJob> jobs = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<MediaConvertJob> getJobs() {
        return jobs;
    }

    public void setJobs(List<MediaConvertJob> jobs) {
        this.jobs = jobs != null ? jobs : new ArrayList<>();
    }
}

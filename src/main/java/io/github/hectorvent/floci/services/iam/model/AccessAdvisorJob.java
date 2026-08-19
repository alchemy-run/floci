package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessAdvisorJob {

    private String jobId;
    private String arn;
    private String granularity;
    private String status;
    private Instant creationDate;
    private Instant completionDate;
    private List<String> serviceNamespaces = new ArrayList<>();
    private String entityName;
    private String entityType;
    private String entityId;
    private String entityPath;

    public AccessAdvisorJob() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getGranularity() { return granularity; }
    public void setGranularity(String granularity) { this.granularity = granularity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public Instant getCompletionDate() { return completionDate; }
    public void setCompletionDate(Instant completionDate) { this.completionDate = completionDate; }

    public List<String> getServiceNamespaces() { return serviceNamespaces; }
    public void setServiceNamespaces(List<String> serviceNamespaces) {
        this.serviceNamespaces = serviceNamespaces;
    }

    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getEntityPath() { return entityPath; }
    public void setEntityPath(String entityPath) { this.entityPath = entityPath; }
}

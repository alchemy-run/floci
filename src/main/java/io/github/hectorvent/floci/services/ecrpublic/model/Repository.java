package io.github.hectorvent.floci.services.ecrpublic.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable ECR Public repository entity.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECRPublic/latest/APIReference/API_Repository.html">AWS ECR Public Repository</a>
 */
@RegisterForReflection
public class Repository {
    private String repositoryArn;
    private String registryId;
    private String repositoryName;
    private String repositoryUri;
    private Instant createdAt;
    private String repositoryPolicyText;
    private CatalogData catalogData;
    private Map<String, String> tags = new HashMap<>();

    public Repository() {}

    public String getRepositoryArn() { return repositoryArn; }
    public void setRepositoryArn(String repositoryArn) { this.repositoryArn = repositoryArn; }

    public String getRegistryId() { return registryId; }
    public void setRegistryId(String registryId) { this.registryId = registryId; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getRepositoryUri() { return repositoryUri; }
    public void setRepositoryUri(String repositoryUri) { this.repositoryUri = repositoryUri; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getRepositoryPolicyText() { return repositoryPolicyText; }
    public void setRepositoryPolicyText(String repositoryPolicyText) {
        this.repositoryPolicyText = repositoryPolicyText;
    }

    public CatalogData getCatalogData() { return catalogData; }
    public void setCatalogData(CatalogData catalogData) { this.catalogData = catalogData; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new HashMap<>() : tags; }
}

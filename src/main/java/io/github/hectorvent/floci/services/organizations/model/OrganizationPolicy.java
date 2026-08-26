package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * AWS Organizations policy (SCP, tag policy, backup policy, …).
 *
 * <p>Customer ARN:
 * {@code arn:aws:organizations::<account>:policy/<org-id>/<type-lowercase>/<policy-id>}
 * AWS-managed FullAWSAccess:
 * {@code arn:aws:organizations::aws:policy/service_control_policy/p-FullAWSAccess}
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationPolicy {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String type;
    private String content;
    private boolean awsManaged;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Set<String> attachedTargets = new LinkedHashSet<>();

    public OrganizationPolicy() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isAwsManaged() {
        return awsManaged;
    }

    public void setAwsManaged(boolean awsManaged) {
        this.awsManaged = awsManaged;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public Set<String> getAttachedTargets() {
        return attachedTargets;
    }

    public void setAttachedTargets(Set<String> attachedTargets) {
        this.attachedTargets = attachedTargets != null ? attachedTargets : new LinkedHashSet<>();
    }
}

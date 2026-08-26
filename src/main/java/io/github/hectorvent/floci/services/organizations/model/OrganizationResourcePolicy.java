package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Singleton AWS Organizations resource policy.
 *
 * <p>ARN shape: {@code arn:aws:organizations::<account>:resourcepolicy/<org-id>/<policy-id>}
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationResourcePolicy {

    private String id;
    private String arn;
    private String content;

    public OrganizationResourcePolicy() {
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}

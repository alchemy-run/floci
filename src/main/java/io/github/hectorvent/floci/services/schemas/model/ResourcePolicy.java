package io.github.hectorvent.floci.services.schemas.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Resource-based policy attached to an EventBridge Schema Registry. */
@RegisterForReflection
public class ResourcePolicy {
    private String policy;
    private String revisionId;

    public ResourcePolicy() {
    }

    public ResourcePolicy(String policy, String revisionId) {
        this.policy = policy;
        this.revisionId = revisionId;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(String revisionId) {
        this.revisionId = revisionId;
    }
}

package io.github.hectorvent.floci.services.signer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One statement in a signing profile's cross-account permission policy. */
@RegisterForReflection
public class ProfilePermission {
    public String action;
    public String principal;
    public String statementId;
    public String profileVersion;

    public ProfilePermission() {
    }
}

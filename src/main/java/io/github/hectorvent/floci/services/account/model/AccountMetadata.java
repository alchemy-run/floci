package io.github.hectorvent.floci.services.account.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AccountMetadata(String accountName, String accountCreatedDate) {
}

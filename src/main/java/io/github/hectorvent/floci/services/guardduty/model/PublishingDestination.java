package io.github.hectorvent.floci.services.guardduty.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PublishingDestination(String destinationId, String destinationType, String destinationArn,
                                    String kmsKeyArn, String status, String clientToken) {}

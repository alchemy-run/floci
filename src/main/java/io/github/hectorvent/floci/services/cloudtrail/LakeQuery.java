package io.github.hectorvent.floci.services.cloudtrail;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record LakeQuery(
        String queryId,
        String storeId,
        String storeArn,
        String queryStatement,
        String status,
        long createdTimestamp,
        String prompt) {

    public LakeQuery withStatus(String newStatus) {
        return new LakeQuery(queryId, storeId, storeArn, queryStatement, newStatus, createdTimestamp, prompt);
    }
}

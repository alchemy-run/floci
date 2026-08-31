package io.github.hectorvent.floci.services.comprehendmedical.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * In-memory async job for Amazon Comprehend Medical batch operations.
 */
@RegisterForReflection
public record ComprehendMedicalJob(
        String jobId,
        Family family,
        String jobName,
        String jobStatus,
        long submitTime,
        Long endTime,
        String inputBucket,
        String inputKey,
        String outputBucket,
        String outputKey,
        String languageCode,
        String dataAccessRoleArn
) {
    public enum Family {
        ENTITIES_V2,
        ICD10CM,
        PHI,
        RXNORM,
        SNOMEDCT
    }

    public ComprehendMedicalJob withStatus(String status, Long endedAt) {
        return new ComprehendMedicalJob(
                jobId, family, jobName, status, submitTime, endedAt,
                inputBucket, inputKey, outputBucket, outputKey, languageCode, dataAccessRoleArn);
    }
}

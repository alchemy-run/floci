package io.floci.aws.services.transcribe.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptionJob(
        @JsonProperty("TranscriptionJobName") String transcriptionJobName,
        @JsonProperty("TranscriptionJobStatus") String transcriptionJobStatus,
        @JsonProperty("LanguageCode") String languageCode,
        @JsonProperty("MediaFormat") String mediaFormat,
        @JsonProperty("MediaSampleRateHertz") Integer mediaSampleRateHertz,
        @JsonProperty("Media") Media media,
        @JsonProperty("Transcript") Transcript transcript,
        @JsonProperty("CreationTime") Long creationTime,
        @JsonProperty("StartTime") Long startTime,
        @JsonProperty("CompletionTime") Long completionTime,
        @JsonProperty("FailureReason") String failureReason
) {
    public TranscriptionJob(String transcriptionJobName, String transcriptionJobStatus, String languageCode,
                            String mediaFormat, Integer mediaSampleRateHertz, Media media, Transcript transcript,
                            Long creationTime, Long startTime, Long completionTime) {
        this(transcriptionJobName, transcriptionJobStatus, languageCode, mediaFormat, mediaSampleRateHertz, media,
                transcript, creationTime, startTime, completionTime, null);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(
            @JsonProperty("MediaFileUri") String mediaFileUri
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Transcript(
            @JsonProperty("TranscriptFileUri") String transcriptFileUri
    ) {}
}

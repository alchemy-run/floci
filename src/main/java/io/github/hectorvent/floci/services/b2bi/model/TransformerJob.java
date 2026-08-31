package io.github.hectorvent.floci.services.b2bi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An AWS B2BI transformer job. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransformerJob {

    private String transformerJobId;
    private String transformerId;
    private String status;
    private String message;
    private List<S3Ref> outputFiles = new ArrayList<>();
    private String startTimestamp;
    private String endTimestamp;

    @JsonIgnore
    private String region;

    public TransformerJob() {
    }

    public String getTransformerJobId() {
        return transformerJobId;
    }

    public void setTransformerJobId(String transformerJobId) {
        this.transformerJobId = transformerJobId;
    }

    public String getTransformerId() {
        return transformerId;
    }

    public void setTransformerId(String transformerId) {
        this.transformerId = transformerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<S3Ref> getOutputFiles() {
        return outputFiles;
    }

    public void setOutputFiles(List<S3Ref> outputFiles) {
        this.outputFiles = outputFiles != null ? outputFiles : new ArrayList<>();
    }

    public String getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(String startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public String getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(String endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class S3Ref {
        private String bucketName;
        private String key;
        private Long objectSizeBytes;

        public S3Ref() {
        }

        public S3Ref(String bucketName, String key, Long objectSizeBytes) {
            this.bucketName = bucketName;
            this.key = key;
            this.objectSizeBytes = objectSizeBytes;
        }

        public String getBucketName() {
            return bucketName;
        }

        public void setBucketName(String bucketName) {
            this.bucketName = bucketName;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Long getObjectSizeBytes() {
            return objectSizeBytes;
        }

        public void setObjectSizeBytes(Long objectSizeBytes) {
            this.objectSizeBytes = objectSizeBytes;
        }
    }
}

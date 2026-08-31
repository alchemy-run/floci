package io.github.hectorvent.floci.services.glacier.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlacierMultipartUpload {

    private String uploadId;
    private String archiveDescription;
    private long partSizeInBytes;
    private String creationDate;
    private List<GlacierPart> parts = new ArrayList<>();

    public GlacierMultipartUpload() {
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getArchiveDescription() {
        return archiveDescription;
    }

    public void setArchiveDescription(String archiveDescription) {
        this.archiveDescription = archiveDescription;
    }

    public long getPartSizeInBytes() {
        return partSizeInBytes;
    }

    public void setPartSizeInBytes(long partSizeInBytes) {
        this.partSizeInBytes = partSizeInBytes;
    }

    public String getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(String creationDate) {
        this.creationDate = creationDate;
    }

    public List<GlacierPart> getParts() {
        return parts;
    }

    public void setParts(List<GlacierPart> parts) {
        this.parts = parts == null ? new ArrayList<>() : new ArrayList<>(parts);
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlacierPart {
        private String rangeInBytes;
        private String sha256TreeHash;

        public GlacierPart() {
        }

        public GlacierPart(String rangeInBytes, String sha256TreeHash) {
            this.rangeInBytes = rangeInBytes;
            this.sha256TreeHash = sha256TreeHash;
        }

        public String getRangeInBytes() {
            return rangeInBytes;
        }

        public void setRangeInBytes(String rangeInBytes) {
            this.rangeInBytes = rangeInBytes;
        }

        public String getSha256TreeHash() {
            return sha256TreeHash;
        }

        public void setSha256TreeHash(String sha256TreeHash) {
            this.sha256TreeHash = sha256TreeHash;
        }
    }
}

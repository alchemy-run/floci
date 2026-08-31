package io.github.hectorvent.floci.services.deadline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A Deadline Cloud storage profile. Wire JSON is camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeadlineStorageProfile {

    private String farmId;
    private String storageProfileId;
    private String displayName;
    private String osFamily;
    private String createdAt;
    private String createdBy;
    private String updatedAt;
    private String updatedBy;
    private String region;
    private String accountId;
    private List<FileSystemLocation> fileSystemLocations = new ArrayList<>();

    public DeadlineStorageProfile() {
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public String getStorageProfileId() {
        return storageProfileId;
    }

    public void setStorageProfileId(String storageProfileId) {
        this.storageProfileId = storageProfileId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getOsFamily() {
        return osFamily;
    }

    public void setOsFamily(String osFamily) {
        this.osFamily = osFamily;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public List<FileSystemLocation> getFileSystemLocations() {
        return fileSystemLocations;
    }

    public void setFileSystemLocations(List<FileSystemLocation> fileSystemLocations) {
        this.fileSystemLocations = fileSystemLocations == null
                ? new ArrayList<>()
                : new ArrayList<>(fileSystemLocations);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileSystemLocation {
        private String name;
        private String path;
        private String type;

        public FileSystemLocation() {
        }

        public FileSystemLocation(String name, String path, String type) {
            this.name = name;
            this.path = path;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String key() {
            return name + "\0" + path + "\0" + type;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FileSystemLocation that)) {
                return false;
            }
            return Objects.equals(name, that.name)
                    && Objects.equals(path, that.path)
                    && Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, path, type);
        }
    }
}

package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code runtimePlatform} of a Batch container: {@code cpuArchitecture} is {@code X86_64} or
 * {@code ARM64}, and AWS runs {@code X86_64} when it is omitted.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchRuntimePlatform {
    public static final String X86_64 = "X86_64";
    public static final String ARM64 = "ARM64";

    private String cpuArchitecture;
    private String operatingSystemFamily;

    public BatchRuntimePlatform() {
    }

    public BatchRuntimePlatform(String cpuArchitecture, String operatingSystemFamily) {
        this.cpuArchitecture = cpuArchitecture;
        this.operatingSystemFamily = operatingSystemFamily;
    }

    public String getCpuArchitecture() {
        return cpuArchitecture;
    }

    public void setCpuArchitecture(String cpuArchitecture) {
        this.cpuArchitecture = cpuArchitecture;
    }

    public String getOperatingSystemFamily() {
        return operatingSystemFamily;
    }

    public void setOperatingSystemFamily(String operatingSystemFamily) {
        this.operatingSystemFamily = operatingSystemFamily;
    }

    /**
     * The Docker platform a container with this runtime platform runs on. A null platform or
     * architecture is {@code X86_64}, as it is on AWS.
     */
    public static String dockerPlatform(BatchRuntimePlatform platform) {
        String architecture = platform != null ? platform.getCpuArchitecture() : null;
        return ARM64.equals(architecture) ? "linux/arm64" : "linux/amd64";
    }
}

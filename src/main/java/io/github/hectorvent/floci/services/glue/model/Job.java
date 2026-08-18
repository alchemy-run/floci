package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Role")
    private String role;
    @JsonProperty("Command")
    private JobCommand command;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("DefaultArguments")
    private Map<String, String> defaultArguments;
    @JsonProperty("NonOverridableArguments")
    private Map<String, String> nonOverridableArguments;
    @JsonProperty("Connections")
    private ConnectionsList connections;
    @JsonProperty("MaxRetries")
    private Integer maxRetries;
    @JsonProperty("Timeout")
    private Integer timeout;
    @JsonProperty("MaxCapacity")
    private Double maxCapacity;
    @JsonProperty("GlueVersion")
    private String glueVersion;
    @JsonProperty("NumberOfWorkers")
    private Integer numberOfWorkers;
    @JsonProperty("WorkerType")
    private String workerType;
    @JsonProperty("ExecutionClass")
    private String executionClass;
    @JsonProperty("ExecutionProperty")
    private ExecutionProperty executionProperty;
    @JsonProperty("CreatedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdOn;
    @JsonProperty("LastModifiedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastModifiedOn;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public JobCommand getCommand() { return command; }
    public void setCommand(JobCommand command) { this.command = command; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, String> getDefaultArguments() { return defaultArguments; }
    public void setDefaultArguments(Map<String, String> defaultArguments) { this.defaultArguments = defaultArguments; }
    public Map<String, String> getNonOverridableArguments() { return nonOverridableArguments; }
    public void setNonOverridableArguments(Map<String, String> nonOverridableArguments) {
        this.nonOverridableArguments = nonOverridableArguments;
    }
    public ConnectionsList getConnections() { return connections; }
    public void setConnections(ConnectionsList connections) { this.connections = connections; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }
    public Double getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Double maxCapacity) { this.maxCapacity = maxCapacity; }
    public String getGlueVersion() { return glueVersion; }
    public void setGlueVersion(String glueVersion) { this.glueVersion = glueVersion; }
    public Integer getNumberOfWorkers() { return numberOfWorkers; }
    public void setNumberOfWorkers(Integer numberOfWorkers) { this.numberOfWorkers = numberOfWorkers; }
    public String getWorkerType() { return workerType; }
    public void setWorkerType(String workerType) { this.workerType = workerType; }
    public String getExecutionClass() { return executionClass; }
    public void setExecutionClass(String executionClass) { this.executionClass = executionClass; }
    public ExecutionProperty getExecutionProperty() { return executionProperty; }
    public void setExecutionProperty(ExecutionProperty executionProperty) { this.executionProperty = executionProperty; }
    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }
    public Instant getLastModifiedOn() { return lastModifiedOn; }
    public void setLastModifiedOn(Instant lastModifiedOn) { this.lastModifiedOn = lastModifiedOn; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobCommand {
        @JsonProperty("Name")
        private String name;
        @JsonProperty("ScriptLocation")
        private String scriptLocation;
        @JsonProperty("PythonVersion")
        private String pythonVersion;
        @JsonProperty("Runtime")
        private String runtime;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getScriptLocation() { return scriptLocation; }
        public void setScriptLocation(String scriptLocation) { this.scriptLocation = scriptLocation; }
        public String getPythonVersion() { return pythonVersion; }
        public void setPythonVersion(String pythonVersion) { this.pythonVersion = pythonVersion; }
        public String getRuntime() { return runtime; }
        public void setRuntime(String runtime) { this.runtime = runtime; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConnectionsList {
        @JsonProperty("Connections")
        private List<String> connections;

        public List<String> getConnections() { return connections; }
        public void setConnections(List<String> connections) { this.connections = connections; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExecutionProperty {
        @JsonProperty("MaxConcurrentRuns")
        private Integer maxConcurrentRuns;

        public Integer getMaxConcurrentRuns() { return maxConcurrentRuns; }
        public void setMaxConcurrentRuns(Integer maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
    }
}

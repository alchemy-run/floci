package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Container {@code logConfiguration} as stored on a task definition. Alchemy's
 * TaskDefinition provider injects {@code awslogs} and then diffs the observed
 * revision against the desired request, so this must round-trip on
 * Register/DescribeTaskDefinition.
 */
@RegisterForReflection
public class LogConfiguration {

    private String logDriver;
    private Map<String, String> options;
    private List<Secret> secretOptions;

    public String getLogDriver() { return logDriver; }
    public void setLogDriver(String logDriver) { this.logDriver = logDriver; }

    public Map<String, String> getOptions() { return options; }
    public void setOptions(Map<String, String> options) { this.options = options; }

    public List<Secret> getSecretOptions() { return secretOptions; }
    public void setSecretOptions(List<Secret> secretOptions) { this.secretOptions = secretOptions; }
}

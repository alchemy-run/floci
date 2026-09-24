package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A bootstrap action a cluster ran at launch (the EMR {@code Command} shape). */
@RegisterForReflection
public class EmrBootstrapAction {

    private String name;
    private String scriptPath;
    private List<String> args = new ArrayList<>();

    public EmrBootstrapAction() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String scriptPath) { this.scriptPath = scriptPath; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }
}

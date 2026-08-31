package io.github.hectorvent.floci.services.medialive.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS Elemental MediaLive input. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Input {

    private String id;
    private String arn;
    private String name;
    private String type;
    private String state;
    private String inputClass;
    private String roleArn;
    private String region;
    private List<Destination> destinations = new ArrayList<>();
    private List<Source> sources = new ArrayList<>();
    private List<String> securityGroups = new ArrayList<>();
    private List<String> attachedChannels = new ArrayList<>();
    private List<String> mediaConnectFlows = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public Input() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getInputClass() {
        return inputClass;
    }

    public void setInputClass(String inputClass) {
        this.inputClass = inputClass;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<Destination> getDestinations() {
        return destinations;
    }

    public void setDestinations(List<Destination> destinations) {
        this.destinations = destinations == null ? new ArrayList<>() : new ArrayList<>(destinations);
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
    }

    public List<String> getSecurityGroups() {
        return securityGroups;
    }

    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups == null ? new ArrayList<>() : new ArrayList<>(securityGroups);
    }

    public List<String> getAttachedChannels() {
        return attachedChannels;
    }

    public void setAttachedChannels(List<String> attachedChannels) {
        this.attachedChannels = attachedChannels == null ? new ArrayList<>() : new ArrayList<>(attachedChannels);
    }

    public List<String> getMediaConnectFlows() {
        return mediaConnectFlows;
    }

    public void setMediaConnectFlows(List<String> mediaConnectFlows) {
        this.mediaConnectFlows = mediaConnectFlows == null ? new ArrayList<>() : new ArrayList<>(mediaConnectFlows);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Destination {
        private String url;
        private String ip;
        private String port;

        public Destination() {
        }

        public Destination(String url, String ip, String port) {
            this.url = url;
            this.ip = ip;
            this.port = port;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Source {
        private String url;
        private String username;
        private String passwordParam;

        public Source() {
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordParam() {
            return passwordParam;
        }

        public void setPasswordParam(String passwordParam) {
            this.passwordParam = passwordParam;
        }
    }
}

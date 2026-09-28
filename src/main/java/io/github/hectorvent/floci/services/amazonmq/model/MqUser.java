package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A broker user. Amazon MQ never returns the password in any response
 * (DescribeUser / ListUsers omit it), so the password is stored for later
 * projection into the real broker but is never serialized back to the client.
 *
 * <p>ActiveMQ users managed through the standalone User API carry a pending change
 * ({@code CREATE}, {@code UPDATE} or {@code DELETE}) until the next RebootBroker
 * applies it, exactly as Amazon MQ stages them.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MqUser {

    public static final String CHANGE_CREATE = "CREATE";
    public static final String CHANGE_UPDATE = "UPDATE";
    public static final String CHANGE_DELETE = "DELETE";

    @JsonProperty("username")
    private String username;

    // The admin password is a secret used only to seed the broker container at
    // create time. It is deliberately kept in memory only and never serialized (to the
    // API or to StorageBackend) so it is not written in cleartext to
    // amazonmq-brokers.json (per the project rule against persisting secrets). A broker
    // reloaded from persistent storage therefore has a null password, and the container
    // managers fail loudly rather than seed a null credential.
    @JsonIgnore
    private String password;

    @JsonProperty("consoleAccess")
    private boolean consoleAccess;

    @JsonProperty("groups")
    private List<String> groups;

    @JsonProperty("pendingChange")
    private String pendingChange;

    @JsonProperty("pendingConsoleAccess")
    private Boolean pendingConsoleAccess;

    @JsonProperty("pendingGroups")
    private List<String> pendingGroups;

    // Staged by CreateUser/UpdateUser and applied by RebootBroker. In memory only, like password.
    @JsonIgnore
    private String pendingPassword;

    public MqUser() {}

    public MqUser(String username, String password, boolean consoleAccess, List<String> groups) {
        this.username = username;
        this.password = password;
        this.consoleAccess = consoleAccess;
        this.groups = groups;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    @JsonIgnore
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isConsoleAccess() { return consoleAccess; }
    public void setConsoleAccess(boolean consoleAccess) { this.consoleAccess = consoleAccess; }

    public List<String> getGroups() { return groups; }
    public void setGroups(List<String> groups) { this.groups = groups; }

    public String getPendingChange() { return pendingChange; }
    public void setPendingChange(String pendingChange) { this.pendingChange = pendingChange; }

    public Boolean getPendingConsoleAccess() { return pendingConsoleAccess; }
    public void setPendingConsoleAccess(Boolean pendingConsoleAccess) { this.pendingConsoleAccess = pendingConsoleAccess; }

    public List<String> getPendingGroups() { return pendingGroups; }
    public void setPendingGroups(List<String> pendingGroups) { this.pendingGroups = pendingGroups; }

    @JsonIgnore
    public String getPendingPassword() { return pendingPassword; }
    public void setPendingPassword(String pendingPassword) { this.pendingPassword = pendingPassword; }
}

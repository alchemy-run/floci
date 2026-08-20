package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedPrefixList {

    private String prefixListId;
    private String prefixListArn;
    private String prefixListName;
    private String addressFamily = "IPv4";
    private int maxEntries;
    private long version = 1;
    private String state = "create-complete";
    private String ownerId;
    private String region;
    private List<PrefixListEntry> entries = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public ManagedPrefixList() {}

    public String getPrefixListId() { return prefixListId; }
    public void setPrefixListId(String prefixListId) { this.prefixListId = prefixListId; }

    public String getPrefixListArn() { return prefixListArn; }
    public void setPrefixListArn(String prefixListArn) { this.prefixListArn = prefixListArn; }

    public String getPrefixListName() { return prefixListName; }
    public void setPrefixListName(String prefixListName) { this.prefixListName = prefixListName; }

    public String getAddressFamily() { return addressFamily; }
    public void setAddressFamily(String addressFamily) { this.addressFamily = addressFamily; }

    public int getMaxEntries() { return maxEntries; }
    public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<PrefixListEntry> getEntries() { return entries; }
    public void setEntries(List<PrefixListEntry> entries) { this.entries = entries; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}

package io.floci.aws.services.ec2.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A deleted snapshot, AMI or volume kept in the Recycle Bin, with its tags and when it entered and leaves. */
public class RecycledResource {
    private String resourceType;
    private String resourceId;
    private Snapshot snapshot;
    private Volume volume;
    private Image image;
    private List<Tag> tags = new ArrayList<>();
    private Instant enterTime;
    private Instant exitTime;

    public RecycledResource() {}

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public Snapshot getSnapshot() { return snapshot; }
    public void setSnapshot(Snapshot snapshot) { this.snapshot = snapshot; }
    public Volume getVolume() { return volume; }
    public void setVolume(Volume volume) { this.volume = volume; }
    public Image getImage() { return image; }
    public void setImage(Image image) { this.image = image; }
    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
    public Instant getEnterTime() { return enterTime; }
    public void setEnterTime(Instant enterTime) { this.enterTime = enterTime; }
    public Instant getExitTime() { return exitTime; }
    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }
}

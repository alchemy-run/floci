package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A DMS replication instance control-plane record. Floci runs no replication compute, so the
 * instance owns no network interface and reports no IP addresses.
 *
 * <p>{@code subnetGroup} is a snapshot of the group the instance was placed in, refreshed when that
 * group is modified. {@code pending*} fields hold modifications that have been requested but not
 * yet applied; {@link #isPendingApplyImmediately()} says whether they apply when the current
 * {@code modifying} transition settles or wait for a maintenance window.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReplicationInstance {

    private String replicationInstanceIdentifier;
    private String replicationInstanceArn;
    private String replicationInstanceClass;
    private String status;
    private int allocatedStorage;
    private long instanceCreateTimeMillis;
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private String availabilityZone;
    private String secondaryAvailabilityZone;
    private ReplicationSubnetGroup subnetGroup;
    private String preferredMaintenanceWindow;
    private boolean multiAZ;
    private String engineVersion;
    private boolean autoMinorVersionUpgrade;
    private String kmsKeyId;
    private boolean publiclyAccessible;
    private String networkType;
    private String dnsNameServers;
    private String pendingReplicationInstanceClass;
    private Integer pendingAllocatedStorage;
    private Boolean pendingMultiAZ;
    private String pendingEngineVersion;
    private String pendingNetworkType;
    private boolean pendingApplyImmediately;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ReplicationInstance() {
    }

    public String getReplicationInstanceIdentifier() {
        return replicationInstanceIdentifier;
    }

    public void setReplicationInstanceIdentifier(String replicationInstanceIdentifier) {
        this.replicationInstanceIdentifier = replicationInstanceIdentifier;
    }

    public String getReplicationInstanceArn() {
        return replicationInstanceArn;
    }

    public void setReplicationInstanceArn(String replicationInstanceArn) {
        this.replicationInstanceArn = replicationInstanceArn;
    }

    public String getReplicationInstanceClass() {
        return replicationInstanceClass;
    }

    public void setReplicationInstanceClass(String replicationInstanceClass) {
        this.replicationInstanceClass = replicationInstanceClass;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAllocatedStorage() {
        return allocatedStorage;
    }

    public void setAllocatedStorage(int allocatedStorage) {
        this.allocatedStorage = allocatedStorage;
    }

    public long getInstanceCreateTimeMillis() {
        return instanceCreateTimeMillis;
    }

    public void setInstanceCreateTimeMillis(long instanceCreateTimeMillis) {
        this.instanceCreateTimeMillis = instanceCreateTimeMillis;
    }

    public List<String> getVpcSecurityGroupIds() {
        return List.copyOf(vpcSecurityGroupIds);
    }

    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds != null
                ? new ArrayList<>(vpcSecurityGroupIds)
                : new ArrayList<>();
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public void setAvailabilityZone(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public String getSecondaryAvailabilityZone() {
        return secondaryAvailabilityZone;
    }

    public void setSecondaryAvailabilityZone(String secondaryAvailabilityZone) {
        this.secondaryAvailabilityZone = secondaryAvailabilityZone;
    }

    public ReplicationSubnetGroup getSubnetGroup() {
        return subnetGroup;
    }

    public void setSubnetGroup(ReplicationSubnetGroup subnetGroup) {
        this.subnetGroup = subnetGroup;
    }

    public String getPreferredMaintenanceWindow() {
        return preferredMaintenanceWindow;
    }

    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) {
        this.preferredMaintenanceWindow = preferredMaintenanceWindow;
    }

    public boolean isMultiAZ() {
        return multiAZ;
    }

    public void setMultiAZ(boolean multiAZ) {
        this.multiAZ = multiAZ;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public void setEngineVersion(String engineVersion) {
        this.engineVersion = engineVersion;
    }

    public boolean isAutoMinorVersionUpgrade() {
        return autoMinorVersionUpgrade;
    }

    public void setAutoMinorVersionUpgrade(boolean autoMinorVersionUpgrade) {
        this.autoMinorVersionUpgrade = autoMinorVersionUpgrade;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public boolean isPubliclyAccessible() {
        return publiclyAccessible;
    }

    public void setPubliclyAccessible(boolean publiclyAccessible) {
        this.publiclyAccessible = publiclyAccessible;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    public String getDnsNameServers() {
        return dnsNameServers;
    }

    public void setDnsNameServers(String dnsNameServers) {
        this.dnsNameServers = dnsNameServers;
    }

    public String getPendingReplicationInstanceClass() {
        return pendingReplicationInstanceClass;
    }

    public void setPendingReplicationInstanceClass(String pendingReplicationInstanceClass) {
        this.pendingReplicationInstanceClass = pendingReplicationInstanceClass;
    }

    public Integer getPendingAllocatedStorage() {
        return pendingAllocatedStorage;
    }

    public void setPendingAllocatedStorage(Integer pendingAllocatedStorage) {
        this.pendingAllocatedStorage = pendingAllocatedStorage;
    }

    public Boolean getPendingMultiAZ() {
        return pendingMultiAZ;
    }

    public void setPendingMultiAZ(Boolean pendingMultiAZ) {
        this.pendingMultiAZ = pendingMultiAZ;
    }

    public String getPendingEngineVersion() {
        return pendingEngineVersion;
    }

    public void setPendingEngineVersion(String pendingEngineVersion) {
        this.pendingEngineVersion = pendingEngineVersion;
    }

    public String getPendingNetworkType() {
        return pendingNetworkType;
    }

    public void setPendingNetworkType(String pendingNetworkType) {
        this.pendingNetworkType = pendingNetworkType;
    }

    public boolean isPendingApplyImmediately() {
        return pendingApplyImmediately;
    }

    public void setPendingApplyImmediately(boolean pendingApplyImmediately) {
        this.pendingApplyImmediately = pendingApplyImmediately;
    }

    public boolean hasPendingModifications() {
        return pendingReplicationInstanceClass != null || pendingAllocatedStorage != null
                || pendingMultiAZ != null || pendingEngineVersion != null || pendingNetworkType != null;
    }

    public void clearPendingModifications() {
        pendingReplicationInstanceClass = null;
        pendingAllocatedStorage = null;
        pendingMultiAZ = null;
        pendingEngineVersion = null;
        pendingNetworkType = null;
        pendingApplyImmediately = false;
    }

    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}

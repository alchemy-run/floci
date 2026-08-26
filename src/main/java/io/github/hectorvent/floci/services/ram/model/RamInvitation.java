package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Invitation for a principal to accept a RAM resource share. */
@RegisterForReflection
public class RamInvitation {
    private String arn;
    private String resourceShareArn;
    private String resourceShareName;
    private String senderAccountId;
    private String receiverAccountId;
    private long invitationTimestamp;
    private String status;

    public RamInvitation() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getResourceShareArn() {
        return resourceShareArn;
    }

    public void setResourceShareArn(String resourceShareArn) {
        this.resourceShareArn = resourceShareArn;
    }

    public String getResourceShareName() {
        return resourceShareName;
    }

    public void setResourceShareName(String resourceShareName) {
        this.resourceShareName = resourceShareName;
    }

    public String getSenderAccountId() {
        return senderAccountId;
    }

    public void setSenderAccountId(String senderAccountId) {
        this.senderAccountId = senderAccountId;
    }

    public String getReceiverAccountId() {
        return receiverAccountId;
    }

    public void setReceiverAccountId(String receiverAccountId) {
        this.receiverAccountId = receiverAccountId;
    }

    public long getInvitationTimestamp() {
        return invitationTimestamp;
    }

    public void setInvitationTimestamp(long invitationTimestamp) {
        this.invitationTimestamp = invitationTimestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

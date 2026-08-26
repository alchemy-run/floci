package io.github.hectorvent.floci.services.socialmessaging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WhatsAppPhoneNumber {

    private String arn;
    private String phoneNumber;
    private String phoneNumberId;
    private String metaPhoneNumberId;
    private String displayPhoneNumberName;
    private String displayPhoneNumber;
    private String qualityRating;
    private String dataLocalizationRegion;

    public WhatsAppPhoneNumber() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public void setPhoneNumberId(String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
    }

    public String getMetaPhoneNumberId() {
        return metaPhoneNumberId;
    }

    public void setMetaPhoneNumberId(String metaPhoneNumberId) {
        this.metaPhoneNumberId = metaPhoneNumberId;
    }

    public String getDisplayPhoneNumberName() {
        return displayPhoneNumberName;
    }

    public void setDisplayPhoneNumberName(String displayPhoneNumberName) {
        this.displayPhoneNumberName = displayPhoneNumberName;
    }

    public String getDisplayPhoneNumber() {
        return displayPhoneNumber;
    }

    public void setDisplayPhoneNumber(String displayPhoneNumber) {
        this.displayPhoneNumber = displayPhoneNumber;
    }

    public String getQualityRating() {
        return qualityRating;
    }

    public void setQualityRating(String qualityRating) {
        this.qualityRating = qualityRating;
    }

    public String getDataLocalizationRegion() {
        return dataLocalizationRegion;
    }

    public void setDataLocalizationRegion(String dataLocalizationRegion) {
        this.dataLocalizationRegion = dataLocalizationRegion;
    }
}

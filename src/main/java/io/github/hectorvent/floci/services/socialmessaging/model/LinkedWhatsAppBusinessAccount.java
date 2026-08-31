package io.github.hectorvent.floci.services.socialmessaging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class LinkedWhatsAppBusinessAccount {

    private String arn;
    private String id;
    private String wabaId;
    private String registrationStatus;
    private long linkDate;
    private String wabaName;
    private String marketingMessagesOnboardingStatus;
    private String region;
    private List<WhatsAppEventDestination> eventDestinations = new ArrayList<>();
    private List<WhatsAppPhoneNumber> phoneNumbers = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public LinkedWhatsAppBusinessAccount() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWabaId() {
        return wabaId;
    }

    public void setWabaId(String wabaId) {
        this.wabaId = wabaId;
    }

    public String getRegistrationStatus() {
        return registrationStatus;
    }

    public void setRegistrationStatus(String registrationStatus) {
        this.registrationStatus = registrationStatus;
    }

    public long getLinkDate() {
        return linkDate;
    }

    public void setLinkDate(long linkDate) {
        this.linkDate = linkDate;
    }

    public String getWabaName() {
        return wabaName;
    }

    public void setWabaName(String wabaName) {
        this.wabaName = wabaName;
    }

    public String getMarketingMessagesOnboardingStatus() {
        return marketingMessagesOnboardingStatus;
    }

    public void setMarketingMessagesOnboardingStatus(String marketingMessagesOnboardingStatus) {
        this.marketingMessagesOnboardingStatus = marketingMessagesOnboardingStatus;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<WhatsAppEventDestination> getEventDestinations() {
        return eventDestinations;
    }

    public void setEventDestinations(List<WhatsAppEventDestination> eventDestinations) {
        this.eventDestinations = eventDestinations == null ? new ArrayList<>() : new ArrayList<>(eventDestinations);
    }

    public List<WhatsAppPhoneNumber> getPhoneNumbers() {
        return phoneNumbers;
    }

    public void setPhoneNumbers(List<WhatsAppPhoneNumber> phoneNumbers) {
        this.phoneNumbers = phoneNumbers == null ? new ArrayList<>() : new ArrayList<>(phoneNumbers);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}

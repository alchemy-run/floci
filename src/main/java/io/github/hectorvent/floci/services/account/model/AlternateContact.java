package io.github.hectorvent.floci.services.account.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Alternate contact for an AWS account. Serialized with AWS's UpperCamelCase member names.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class AlternateContact {
    private String name;
    private String title;
    private String emailAddress;
    private String phoneNumber;
    private String alternateContactType;

    public AlternateContact() {
    }

    public AlternateContact(
            String name,
            String title,
            String emailAddress,
            String phoneNumber,
            String alternateContactType) {
        this.name = name;
        this.title = title;
        this.emailAddress = emailAddress;
        this.phoneNumber = phoneNumber;
        this.alternateContactType = alternateContactType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getAlternateContactType() {
        return alternateContactType;
    }

    public void setAlternateContactType(String alternateContactType) {
        this.alternateContactType = alternateContactType;
    }
}

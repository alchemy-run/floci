package io.github.hectorvent.floci.services.ecrpublic.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Public gallery catalog metadata for an ECR Public repository.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECRPublic/latest/APIReference/API_RepositoryCatalogData.html">RepositoryCatalogData</a>
 */
@RegisterForReflection
public class CatalogData {
    private String description;
    private List<String> architectures = new ArrayList<>();
    private List<String> operatingSystems = new ArrayList<>();
    private String aboutText;
    private String usageText;
    private String logoUrl;
    private boolean marketplaceCertified;

    public CatalogData() {}

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getArchitectures() { return architectures; }
    public void setArchitectures(List<String> architectures) {
        this.architectures = architectures == null ? new ArrayList<>() : architectures;
    }

    public List<String> getOperatingSystems() { return operatingSystems; }
    public void setOperatingSystems(List<String> operatingSystems) {
        this.operatingSystems = operatingSystems == null ? new ArrayList<>() : operatingSystems;
    }

    public String getAboutText() { return aboutText; }
    public void setAboutText(String aboutText) { this.aboutText = aboutText; }

    public String getUsageText() { return usageText; }
    public void setUsageText(String usageText) { this.usageText = usageText; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public boolean isMarketplaceCertified() { return marketplaceCertified; }
    public void setMarketplaceCertified(boolean marketplaceCertified) {
        this.marketplaceCertified = marketplaceCertified;
    }
}

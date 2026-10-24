package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class ResourceRecordSet {

    private String name;
    private String type;
    private Long ttl;
    private List<ResourceRecord> records;
    private AliasTarget aliasTarget;
    private Long weight;
    private String region;
    private String setIdentifier;
    private String failover;
    private String healthCheckId;
    private String geoContinentCode;
    private String geoCountryCode;
    private String geoSubdivisionCode;
    private String cidrCollectionId;
    private String cidrLocationName;
    private String geoProximityAwsRegion;
    private String geoProximityLocalZoneGroup;
    private Integer geoProximityBias;

    public ResourceRecordSet() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }

    public List<ResourceRecord> getRecords() { return records; }
    public void setRecords(List<ResourceRecord> records) { this.records = records; }

    public AliasTarget getAliasTarget() { return aliasTarget; }
    public void setAliasTarget(AliasTarget aliasTarget) { this.aliasTarget = aliasTarget; }

    public Long getWeight() { return weight; }
    public void setWeight(Long weight) { this.weight = weight; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getSetIdentifier() { return setIdentifier; }
    public void setSetIdentifier(String setIdentifier) { this.setIdentifier = setIdentifier; }

    public String getFailover() { return failover; }
    public void setFailover(String failover) { this.failover = failover; }

    public String getHealthCheckId() { return healthCheckId; }
    public void setHealthCheckId(String healthCheckId) { this.healthCheckId = healthCheckId; }

    public String getGeoContinentCode() { return geoContinentCode; }
    public void setGeoContinentCode(String geoContinentCode) { this.geoContinentCode = geoContinentCode; }

    public String getGeoCountryCode() { return geoCountryCode; }
    public void setGeoCountryCode(String geoCountryCode) { this.geoCountryCode = geoCountryCode; }

    public String getGeoSubdivisionCode() { return geoSubdivisionCode; }
    public void setGeoSubdivisionCode(String geoSubdivisionCode) { this.geoSubdivisionCode = geoSubdivisionCode; }

    public String getCidrCollectionId() { return cidrCollectionId; }
    public void setCidrCollectionId(String cidrCollectionId) { this.cidrCollectionId = cidrCollectionId; }

    public String getCidrLocationName() { return cidrLocationName; }
    public void setCidrLocationName(String cidrLocationName) { this.cidrLocationName = cidrLocationName; }

    public String getGeoProximityAwsRegion() { return geoProximityAwsRegion; }
    public void setGeoProximityAwsRegion(String geoProximityAwsRegion) {
        this.geoProximityAwsRegion = geoProximityAwsRegion;
    }

    public String getGeoProximityLocalZoneGroup() { return geoProximityLocalZoneGroup; }
    public void setGeoProximityLocalZoneGroup(String geoProximityLocalZoneGroup) {
        this.geoProximityLocalZoneGroup = geoProximityLocalZoneGroup;
    }

    public Integer getGeoProximityBias() { return geoProximityBias; }
    public void setGeoProximityBias(Integer geoProximityBias) { this.geoProximityBias = geoProximityBias; }
}

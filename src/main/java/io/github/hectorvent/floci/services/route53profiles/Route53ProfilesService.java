package io.github.hectorvent.floci.services.route53profiles;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.route53profiles.model.ProfileResourceAssociation;
import io.github.hectorvent.floci.services.route53profiles.model.Route53Profile;
import io.github.hectorvent.floci.services.route53profiles.model.Route53ProfileAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Route 53 Profiles restJson1. Profiles, VPC associations, and DNS resource
 * attachments used by Alchemy; tag APIs share {@code /tags/{arn}} via {@link TagHandler}.
 *
 * <p>Storage is account-scoped by {@code StorageFactory}. Keys are
 * {@code {region}::{id}}.
 */
@ApplicationScoped
public class Route53ProfilesService implements TagHandler {

    static final String SERVICE = "route53profiles";

    private final StorageBackend<String, Route53Profile> profiles;
    private final StorageBackend<String, Route53ProfileAssociation> associations;
    private final StorageBackend<String, ProfileResourceAssociation> resourceAssociations;
    private final RegionResolver regionResolver;

    @Inject
    public Route53ProfilesService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(SERVICE, "route53profiles-profiles.json",
                        new TypeReference<Map<String, Route53Profile>>() {
                        }),
                storageFactory.create(SERVICE, "route53profiles-associations.json",
                        new TypeReference<Map<String, Route53ProfileAssociation>>() {
                        }),
                storageFactory.create(SERVICE, "route53profiles-resource-associations.json",
                        new TypeReference<Map<String, ProfileResourceAssociation>>() {
                        }),
                regionResolver);
    }

    Route53ProfilesService(
            StorageBackend<String, Route53Profile> profiles,
            StorageBackend<String, Route53ProfileAssociation> associations,
            StorageBackend<String, ProfileResourceAssociation> resourceAssociations,
            RegionResolver regionResolver) {
        this.profiles = profiles;
        this.associations = associations;
        this.resourceAssociations = resourceAssociations;
        this.regionResolver = regionResolver;
    }

    public synchronized Route53Profile createProfile(String region, JsonNode request) {
        String name = requireText(request, "Name");
        String clientToken = requireText(request, "ClientToken");
        if (name.isBlank()) {
            throw invalid("Name is required.");
        }
        for (Route53Profile existing : profilesInRegion(region)) {
            if (clientToken.equals(existing.getClientToken())) {
                return existing;
            }
        }
        for (Route53Profile existing : profilesInRegion(region)) {
            if (name.equals(existing.getName())) {
                throw exists("A profile named '" + name + "' already exists.", "PROFILE");
            }
        }
        long now = now();
        String id = "rp-" + hexId(13);
        Route53Profile profile = new Route53Profile();
        profile.setId(id);
        profile.setArn(profileArn(region, id));
        profile.setName(name);
        profile.setOwnerId(accountId());
        profile.setRegion(region);
        profile.setStatus("COMPLETE");
        profile.setShareStatus("NOT_SHARED");
        profile.setClientToken(clientToken);
        profile.setCreationTime(now);
        profile.setModificationTime(now);
        profile.setTags(readTagList(request.get("Tags")));
        profiles.put(profileKey(region, id), profile);
        return profile;
    }

    public Route53Profile getProfile(String region, String profileId) {
        return requireProfile(region, profileId);
    }

    public synchronized Route53Profile deleteProfile(String region, String profileId) {
        Route53Profile profile = requireProfile(region, profileId);
        for (Route53ProfileAssociation association : associationsInRegion(region)) {
            if (profileId.equals(association.getProfileId())) {
                throw conflict("Profile " + profileId + " has associated VPCs and cannot be deleted.");
            }
        }
        for (ProfileResourceAssociation association : resourceAssociationsInRegion(region)) {
            if (profileId.equals(association.getProfileId())) {
                throw conflict("Profile " + profileId + " has associated DNS resources and cannot be deleted.");
            }
        }
        profiles.delete(profileKey(region, profileId));
        profile.setStatus("DELETED");
        profile.setModificationTime(now());
        return profile;
    }

    public List<Route53Profile> listProfiles(String region) {
        List<Route53Profile> items = profilesInRegion(region);
        items.sort(Comparator.comparing(Route53Profile::getId));
        return items;
    }

    public synchronized Route53ProfileAssociation associateProfile(String region, JsonNode request) {
        String profileId = requireText(request, "ProfileId");
        String resourceId = requireText(request, "ResourceId");
        String name = requireText(request, "Name");
        Route53Profile profile = requireProfile(region, profileId);
        for (Route53ProfileAssociation existing : associationsInRegion(region)) {
            if (resourceId.equals(existing.getResourceId())) {
                throw exists("VPC " + resourceId + " is already associated with a profile.",
                        "PROFILE_ASSOCIATION");
            }
        }
        long now = now();
        String id = "rpassoc-" + hexId(17);
        Route53ProfileAssociation association = new Route53ProfileAssociation();
        association.setId(id);
        association.setName(name);
        association.setOwnerId(profile.getOwnerId());
        association.setRegion(region);
        association.setProfileId(profileId);
        association.setResourceId(resourceId);
        association.setStatus("COMPLETE");
        association.setCreationTime(now);
        association.setModificationTime(now);
        association.setTags(readTagList(request.get("Tags")));
        associations.put(associationKey(region, id), association);
        return association;
    }

    public Route53ProfileAssociation getProfileAssociation(String region, String profileAssociationId) {
        return requireAssociation(region, profileAssociationId);
    }

    public synchronized Route53ProfileAssociation disassociateProfile(
            String region, String profileId, String resourceId) {
        Route53ProfileAssociation found = null;
        for (Route53ProfileAssociation association : associationsInRegion(region)) {
            if (profileId.equals(association.getProfileId())
                    && resourceId.equals(association.getResourceId())) {
                found = association;
                break;
            }
        }
        if (found == null) {
            throw notFound("Profile association not found.", "PROFILE_ASSOCIATION");
        }
        associations.delete(associationKey(region, found.getId()));
        found.setStatus("DELETED");
        found.setModificationTime(now());
        return found;
    }

    public List<Route53ProfileAssociation> listProfileAssociations(
            String region, String profileId, String resourceId) {
        List<Route53ProfileAssociation> items = new ArrayList<>();
        for (Route53ProfileAssociation association : associationsInRegion(region)) {
            if (profileId != null && !profileId.isBlank() && !profileId.equals(association.getProfileId())) {
                continue;
            }
            if (resourceId != null && !resourceId.isBlank() && !resourceId.equals(association.getResourceId())) {
                continue;
            }
            items.add(association);
        }
        items.sort(Comparator.comparing(Route53ProfileAssociation::getId));
        return items;
    }

    /**
     * True when {@code resourceArn} is attached to any Profile via a resource
     * association. Route 53 Resolver consults this before deleting a firewall
     * rule group.
     */
    public boolean hasResourceAssociation(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            return false;
        }
        for (ProfileResourceAssociation association : resourceAssociations.values()) {
            if (resourceArn.equals(association.getResourceArn())
                    && !"DELETED".equals(association.getStatus())
                    && !"DELETING".equals(association.getStatus())) {
                return true;
            }
        }
        return false;
    }

    public synchronized ProfileResourceAssociation associateResourceToProfile(String region, JsonNode request) {
        String profileId = requireText(request, "ProfileId");
        String resourceArn = requireText(request, "ResourceArn");
        String name = requireText(request, "Name");
        Route53Profile profile = requireProfile(region, profileId);
        for (ProfileResourceAssociation existing : resourceAssociationsInRegion(region)) {
            if (profileId.equals(existing.getProfileId()) && resourceArn.equals(existing.getResourceArn())) {
                return existing;
            }
        }
        long now = now();
        String id = "rpr-" + hexId(16);
        ProfileResourceAssociation association = new ProfileResourceAssociation();
        association.setId(id);
        association.setName(name);
        association.setOwnerId(profile.getOwnerId());
        association.setProfileId(profileId);
        association.setRegion(region);
        association.setResourceArn(resourceArn);
        association.setResourceType(resourceTypeFromArn(resourceArn));
        association.setResourceProperties(optionalText(request, "ResourceProperties"));
        association.setStatus("COMPLETE");
        association.setCreationTime(now);
        association.setModificationTime(now);
        resourceAssociations.put(resourceAssociationKey(region, id), association);
        return association;
    }

    public ProfileResourceAssociation getProfileResourceAssociation(
            String region, String profileResourceAssociationId) {
        return requireResourceAssociation(region, profileResourceAssociationId);
    }

    public synchronized ProfileResourceAssociation updateProfileResourceAssociation(
            String region, String profileResourceAssociationId, JsonNode request) {
        ProfileResourceAssociation association = requireResourceAssociation(region, profileResourceAssociationId);
        if (request != null && request.hasNonNull("Name")) {
            association.setName(requireText(request, "Name"));
        }
        if (request != null && request.hasNonNull("ResourceProperties")) {
            association.setResourceProperties(requireText(request, "ResourceProperties"));
        }
        association.setModificationTime(now());
        association.setStatus("COMPLETE");
        resourceAssociations.put(resourceAssociationKey(region, association.getId()), association);
        return association;
    }

    public synchronized ProfileResourceAssociation disassociateResourceFromProfile(
            String region, String profileId, String resourceArn) {
        requireProfile(region, profileId);
        ProfileResourceAssociation found = null;
        for (ProfileResourceAssociation association : resourceAssociationsInRegion(region)) {
            if (profileId.equals(association.getProfileId())
                    && resourceArn.equals(association.getResourceArn())) {
                found = association;
                break;
            }
        }
        if (found == null) {
            throw notFound("Profile resource association not found.", "PROFILE_RESOURCE_ASSOCIATION");
        }
        resourceAssociations.delete(resourceAssociationKey(region, found.getId()));
        found.setStatus("DELETED");
        found.setModificationTime(now());
        return found;
    }

    public List<ProfileResourceAssociation> listProfileResourceAssociations(
            String region, String profileId, String resourceType) {
        requireProfile(region, profileId);
        List<ProfileResourceAssociation> items = new ArrayList<>();
        for (ProfileResourceAssociation association : resourceAssociationsInRegion(region)) {
            if (!profileId.equals(association.getProfileId())) {
                continue;
            }
            if (resourceType != null && !resourceType.isBlank()
                    && !resourceType.equals(association.getResourceType())) {
                continue;
            }
            items.add(association);
        }
        items.sort(Comparator.comparing(ProfileResourceAssociation::getId));
        return items;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireProfileByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Route53Profile profile = requireProfileByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(profile.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        profile.setTags(current);
        profile.setModificationTime(now());
        profiles.put(profileKey(region, profile.getId()), profile);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Route53Profile profile = requireProfileByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(profile.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        profile.setTags(current);
        profile.setModificationTime(now());
        profiles.put(profileKey(region, profile.getId()), profile);
    }

    private Route53Profile requireProfile(String region, String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw invalid("ProfileId is required.");
        }
        return profiles.get(profileKey(region, profileId))
                .orElseThrow(() -> notFound("Profile " + profileId + " was not found.", "PROFILE"));
    }

    private Route53Profile requireProfileByArn(String region, String arn) {
        String profileId = profileIdFromArn(arn);
        return requireProfile(region, profileId);
    }

    private Route53ProfileAssociation requireAssociation(String region, String associationId) {
        if (associationId == null || associationId.isBlank()) {
            throw invalid("ProfileAssociationId is required.");
        }
        return associations.get(associationKey(region, associationId))
                .orElseThrow(() -> notFound(
                        "Profile association " + associationId + " was not found.", "PROFILE_ASSOCIATION"));
    }

    private ProfileResourceAssociation requireResourceAssociation(String region, String associationId) {
        if (associationId == null || associationId.isBlank()) {
            throw invalid("ProfileResourceAssociationId is required.");
        }
        return resourceAssociations.get(resourceAssociationKey(region, associationId))
                .orElseThrow(() -> notFound(
                        "Profile resource association " + associationId + " was not found.",
                        "PROFILE_RESOURCE_ASSOCIATION"));
    }

    private List<Route53Profile> profilesInRegion(String region) {
        String prefix = region + "::";
        List<Route53Profile> items = profiles.scan(key -> key.startsWith(prefix));
        items.removeIf(profile -> profile.getRegion() != null && !region.equals(profile.getRegion()));
        return items;
    }

    private List<Route53ProfileAssociation> associationsInRegion(String region) {
        String prefix = region + "::";
        List<Route53ProfileAssociation> items = associations.scan(key -> key.startsWith(prefix));
        items.removeIf(association -> association.getRegion() != null && !region.equals(association.getRegion()));
        return items;
    }

    private List<ProfileResourceAssociation> resourceAssociationsInRegion(String region) {
        String prefix = region + "::";
        List<ProfileResourceAssociation> items = resourceAssociations.scan(key -> key.startsWith(prefix));
        items.removeIf(association -> association.getRegion() != null && !region.equals(association.getRegion()));
        return items;
    }

    private String profileArn(String region, String profileId) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId(), "profile/" + profileId).toString();
    }

    private static String profileIdFromArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service())) {
                throw notFound("Resource " + arn + " was not found.", "PROFILE");
            }
            String resource = parsed.resource();
            if (resource != null && resource.startsWith("profile/")) {
                return resource.substring("profile/".length());
            }
            throw notFound("Resource " + arn + " was not found.", "PROFILE");
        } catch (AwsException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid resource ARN.");
        }
    }

    private static String profileKey(String region, String profileId) {
        return region + "::" + profileId;
    }

    private static String associationKey(String region, String associationId) {
        return region + "::" + associationId;
    }

    private static String resourceAssociationKey(String region, String associationId) {
        return region + "::" + associationId;
    }

    private static String resourceTypeFromArn(String resourceArn) {
        String lower = resourceArn.toLowerCase();
        if (lower.contains("hostedzone")) {
            return "PRIVATE_HOSTED_ZONE";
        }
        if (lower.contains("firewall-rule-group") || lower.contains("firewallrulegroup")) {
            return "FIREWALL_RULE_GROUP";
        }
        if (lower.contains("resolver-rule") || lower.contains("resolverrule")) {
            return "RESOLVER_RULE";
        }
        return "";
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String accountId() {
        return regionResolver.getAccountId();
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String hexId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private static String requireText(JsonNode request, String field) {
        if (request == null || !request.isObject() || !request.hasNonNull(field)) {
            throw invalid(field + " is required.");
        }
        String value = request.get(field).asText();
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static Map<String, String> readTagList(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if (entry == null || !entry.isObject()) {
                    continue;
                }
                JsonNode key = entry.get("Key");
                JsonNode value = entry.get("Value");
                if (key != null && !key.isNull() && value != null && !value.isNull()) {
                    tags.put(key.asText(), value.asText());
                }
            }
            return tags;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    tags.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return tags;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException notFound(String message, String resourceType) {
        return new AwsException("ResourceNotFoundException", message, 404,
                Map.of("ResourceType", resourceType));
    }

    private static AwsException exists(String message, String resourceType) {
        return new AwsException("ResourceExistsException", message, 400,
                Map.of("ResourceType", resourceType));
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}

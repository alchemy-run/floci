package io.floci.aws.services.route53profiles;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.VpcEndpoint;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.HostedZone;
import io.floci.aws.services.route53resolver.Route53ResolverFirewallRuleGroups;
import io.floci.aws.services.route53resolver.Route53ResolverService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Route 53 Profiles: a profile collects DNS configuration (private hosted zones, Resolver rules, DNS Firewall
 * rule groups and interface VPC endpoints) and applies it to every VPC it is associated with. Each resource is
 * checked in the service that owns it, a VPC takes at most one profile, and a profile in use by a VPC cannot be
 * deleted. Resolver rules attached to a profile steer the DNS queries of its VPCs through
 * {@link Route53ProfilesDnsForwardingRules}; a rule group attached to a profile cannot be deleted.
 */
@ApplicationScoped
public class Route53ProfilesService implements Resettable, Route53ResolverFirewallRuleGroups.FirewallRuleGroupUsers {

    static final String FIREWALL_RULE_GROUP = "FIREWALL_RULE_GROUP";
    static final String PRIVATE_HOSTED_ZONE = "PRIVATE_HOSTED_ZONE";
    static final String RESOLVER_RULE = "RESOLVER_RULE";
    static final String INTERFACE_ENDPOINT = "INTERFACE_ENDPOINT";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> profiles;
    private final AccountAwareStorageBackend<ObjectNode> associations;
    private final AccountAwareStorageBackend<ObjectNode> resources;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<Ec2Service> ec2;
    private final Provider<Route53Service> route53;
    private final Provider<Route53ResolverService> resolver;
    private final Provider<Route53ResolverFirewallRuleGroups> ruleGroups;

    @Inject
    public Route53ProfilesService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                                  Provider<Ec2Service> ec2, Provider<Route53Service> route53,
                                  Provider<Route53ResolverService> resolver,
                                  Provider<Route53ResolverFirewallRuleGroups> ruleGroups) {
        this.profiles = storageFactory.create("route53profiles", "profiles.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.associations = storageFactory.create("route53profiles", "associations.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.resources = storageFactory.create("route53profiles", "resource-associations.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.ec2 = ec2;
        this.route53 = route53;
        this.resolver = resolver;
        this.ruleGroups = ruleGroups;
    }

    @Override
    public void clear() {
        profiles.clear();
        associations.clear();
        resources.clear();
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateProfile" -> createProfile(in, region);
            case "GetProfile" -> wrap("Profile", view(profile(region, required(in, "ProfileId"))));
            case "ListProfiles" -> listProfiles(in, region);
            case "DeleteProfile" -> deleteProfile(in, region);
            case "AssociateProfile" -> associateProfile(in, region);
            case "DisassociateProfile" -> disassociateProfile(in, region);
            case "GetProfileAssociation" -> wrap("ProfileAssociation",
                    view(settled(associations, region, required(in, "ProfileAssociationId"), "profile association")));
            case "ListProfileAssociations" -> listProfileAssociations(in, region);
            case "AssociateResourceToProfile" -> associateResource(in, region);
            case "UpdateProfileResourceAssociation" -> updateResource(in, region);
            case "DisassociateResourceFromProfile" -> disassociateResource(in, region);
            case "GetProfileResourceAssociation" -> wrap("ProfileResourceAssociation", view(settled(resources, region,
                    required(in, "ProfileResourceAssociationId"), "profile resource association")));
            case "ListProfileResourceAssociations" -> listResources(in, region);
            case "TagResource" -> tag(in, true);
            case "UntagResource" -> tag(in, false);
            case "ListTagsForResource" -> listTags(in);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    /** Throws the not-found the API returns for an unknown profile, before the request's parameters are checked. */
    public void checkProfile(JsonNode in, String region) {
        if (in.hasNonNull("ProfileId")) {
            profile(region, in.get("ProfileId").asText());
        }
    }

    // ---------- profiles ----------

    private ObjectNode createProfile(JsonNode in, String region) {
        String token = required(in, "ClientToken");
        for (ObjectNode existing : profiles.scan(key -> key.startsWith(region + "/"))) {
            if (token.equals(existing.path("ClientToken").asText())) {
                return wrap("Profile", view(settle(profiles, region, existing)));
            }
        }
        String id = "rp-" + hex(8);
        String account = profiles.accountId();
        ObjectNode profile = record(id, "profile/" + id, region, account);
        profile.put("Name", required(in, "Name")).put("ClientToken", token).put("ShareStatus", "NOT_SHARED")
                .put("StatusMessage", "Creating Profile");
        profile.set("_tags", tagMap(in.path("Tags")));
        profiles.put(key(region, id), profile);
        return wrap("Profile", view(profile));
    }

    private ObjectNode listProfiles(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : live(profiles, region)) {
            ObjectNode summary = mapper.createObjectNode();
            for (String field : List.of("Arn", "Id", "Name", "ShareStatus")) {
                summary.set(field, profile.get(field));
            }
            items.add(summary);
        }
        return page(items, in, "ProfileSummaries");
    }

    private ObjectNode deleteProfile(JsonNode in, String region) {
        ObjectNode profile = profile(region, required(in, "ProfileId"));
        String id = profile.path("Id").asText();
        if (!"COMPLETE".equals(profile.path("Status").asText()) && !"FAILED".equals(profile.path("Status").asText())) {
            throw conflict("The profile " + id + " is " + profile.path("Status").asText() + ".");
        }
        for (ObjectNode association : live(associations, region)) {
            if (id.equals(association.path("ProfileId").asText())) {
                throw conflict("The profile " + id + " is associated with VPC "
                        + association.path("ResourceId").asText() + ". Disassociate it before deleting it.");
            }
        }
        for (ObjectNode resource : live(resources, region)) {
            if (id.equals(resource.path("ProfileId").asText())) {
                resources.delete(key(region, resource.path("Id").asText()));
            }
        }
        return wrap("Profile", view(retire(profiles, region, profile, "Deleting Profile")));
    }

    private ObjectNode profile(String region, String id) {
        return settled(profiles, region, id, "profile");
    }

    /** A profile that can be given new associations: present and done creating. */
    private ObjectNode usableProfile(String region, String id) {
        ObjectNode profile = profile(region, id);
        if (!"COMPLETE".equals(profile.path("Status").asText())) {
            throw conflict("The profile " + id + " is " + profile.path("Status").asText() + ".");
        }
        return profile;
    }

    // ---------- VPC associations ----------

    private ObjectNode associateProfile(JsonNode in, String region) {
        ObjectNode profile = usableProfile(region, required(in, "ProfileId"));
        String vpcId = required(in, "ResourceId");
        try {
            ec2.get().requireVpc(region, vpcId);
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "The VPC " + vpcId + " doesn't exist.", 404);
        }
        for (ObjectNode existing : live(associations, region)) {
            if (vpcId.equals(existing.path("ResourceId").asText())) {
                if (profile.path("Id").asText().equals(existing.path("ProfileId").asText())) {
                    throw new AwsException("ResourceExistsException", "The profile "
                            + profile.path("Id").asText() + " is already associated with " + vpcId + ".", 400);
                }
                throw conflict("The VPC " + vpcId + " is already associated with the profile "
                        + existing.path("ProfileId").asText() + ". A VPC can have one profile.");
            }
        }
        String id = "rpassoc-" + hex(8);
        ObjectNode association = record(id, null, region, associations.accountId());
        association.put("Name", required(in, "Name")).put("ProfileId", profile.path("Id").asText())
                .put("ResourceId", vpcId).put("StatusMessage", "Creating Profile Association");
        association.set("_tags", tagMap(in.path("Tags")));
        associations.put(key(region, id), association);
        return wrap("ProfileAssociation", view(association));
    }

    private ObjectNode disassociateProfile(JsonNode in, String region) {
        String profileId = required(in, "ProfileId");
        String vpcId = required(in, "ResourceId");
        for (ObjectNode association : live(associations, region)) {
            if (profileId.equals(association.path("ProfileId").asText())
                    && vpcId.equals(association.path("ResourceId").asText())) {
                return wrap("ProfileAssociation",
                        view(retire(associations, region, association, "Deleting Profile Association")));
            }
        }
        throw notFound("The profile " + profileId + " isn't associated with " + vpcId + ".");
    }

    private ObjectNode listProfileAssociations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode association : live(associations, region)) {
            if (in.hasNonNull("ProfileId") && !in.get("ProfileId").asText()
                    .equals(association.path("ProfileId").asText())) {
                continue;
            }
            if (in.hasNonNull("ResourceId") && !in.get("ResourceId").asText()
                    .equals(association.path("ResourceId").asText())) {
                continue;
            }
            items.add(view(association));
        }
        return page(items, in, "ProfileAssociations");
    }

    // ---------- resource associations ----------

    private ObjectNode associateResource(JsonNode in, String region) {
        ObjectNode profile = usableProfile(region, required(in, "ProfileId"));
        String profileId = profile.path("Id").asText();
        String arn = required(in, "ResourceArn");
        String type = resourceType(arn, region);
        for (ObjectNode existing : live(resources, region)) {
            if (profileId.equals(existing.path("ProfileId").asText()) && arn.equals(existing.path("ResourceArn")
                    .asText())) {
                throw conflict("The resource " + arn + " is already associated with the profile " + profileId + ".");
            }
        }
        String properties = properties(type, in.path("ResourceProperties").asText(null), profileId, region, null);
        String id = "rpr-" + hex(8);
        ObjectNode resource = record(id, null, region, resources.accountId());
        resource.put("Name", required(in, "Name")).put("ProfileId", profileId).put("ResourceArn", arn)
                .put("ResourceType", type).put("StatusMessage", "Creating Profile Resource Association");
        if (properties != null) {
            resource.put("ResourceProperties", properties);
        }
        resources.put(key(region, id), resource);
        return wrap("ProfileResourceAssociation", view(resource));
    }

    private ObjectNode updateResource(JsonNode in, String region) {
        String id = required(in, "ProfileResourceAssociationId");
        ObjectNode resource = settled(resources, region, id, "profile resource association");
        if (!"COMPLETE".equals(resource.path("Status").asText())) {
            throw conflict("The profile resource association " + id + " is " + resource.path("Status").asText()
                    + ".");
        }
        if (in.hasNonNull("ResourceProperties")) {
            String properties = properties(resource.path("ResourceType").asText(),
                    in.get("ResourceProperties").asText(), resource.path("ProfileId").asText(), region, id);
            resource.put("ResourceProperties", properties);
        }
        if (in.hasNonNull("Name")) {
            resource.put("Name", in.get("Name").asText());
        }
        ObjectNode meta = (ObjectNode) resource.get("_");
        meta.put("ready", now() + step());
        resource.put("Status", "UPDATING").put("StatusMessage", "Updating Profile Resource Association")
                .put("ModificationTime", now());
        resources.put(key(region, id), resource);
        return wrap("ProfileResourceAssociation", view(resource));
    }

    private ObjectNode disassociateResource(JsonNode in, String region) {
        String profileId = required(in, "ProfileId");
        String arn = decode(required(in, "ResourceArn"));
        profile(region, profileId);
        for (ObjectNode resource : live(resources, region)) {
            if (profileId.equals(resource.path("ProfileId").asText()) && arn.equals(resource.path("ResourceArn")
                    .asText())) {
                return wrap("ProfileResourceAssociation",
                        view(retire(resources, region, resource, "Deleting Profile Resource Association")));
            }
        }
        throw notFound("The resource " + arn + " isn't associated with the profile " + profileId + ".");
    }

    private ObjectNode listResources(JsonNode in, String region) {
        String profileId = profile(region, required(in, "ProfileId")).path("Id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode resource : live(resources, region)) {
            if (profileId.equals(resource.path("ProfileId").asText()) && (!in.hasNonNull("ResourceType")
                    || in.get("ResourceType").asText().equals(resource.path("ResourceType").asText()))) {
                items.add(view(resource));
            }
        }
        return page(items, in, "ProfileResourceAssociations");
    }

    /** Which kind of DNS resource the ARN names, after checking that it exists in this account and region. */
    private String resourceType(String arn, String region) {
        if (!AwsArnUtils.isArn(arn)) {
            throw invalid("ResourceArn " + arn + " isn't a valid ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        if (parsed.service().equals("route53") && resource.startsWith("hostedzone/")) {
            HostedZone zone;
            try {
                zone = route53.get().getHostedZone(resource.substring("hostedzone/".length()));
            } catch (AwsException e) {
                throw notFound("The hosted zone " + arn + " doesn't exist.");
            }
            if (!zone.isPrivateZone()) {
                throw invalid("The hosted zone " + arn + " is public. A profile takes private hosted zones.");
            }
            return PRIVATE_HOSTED_ZONE;
        }
        if (!region.equals(parsed.region()) || !profiles.accountId().equals(parsed.accountId())) {
            throw notFound("The resource " + arn + " doesn't exist in " + region + ".");
        }
        if (parsed.service().equals("route53resolver") && resource.startsWith("resolver-rule/")) {
            String ruleId = resource.substring("resolver-rule/".length());
            ObjectNode rule = resolver.get().resolverRuleIn(parsed.accountId(), region, ruleId)
                    .orElseThrow(() -> notFound("The Resolver rule " + arn + " doesn't exist."));
            if (!arn.equals(rule.path("Arn").asText())) {
                throw notFound("The Resolver rule " + arn + " doesn't exist.");
            }
            return RESOLVER_RULE;
        }
        if (parsed.service().equals("route53resolver") && resource.startsWith("firewall-rule-group/")) {
            ruleGroups.get().byArn(arn).orElseThrow(() -> notFound("The rule group " + arn + " doesn't exist."));
            return FIREWALL_RULE_GROUP;
        }
        if (parsed.service().equals("ec2") && resource.startsWith("vpc-endpoint/")) {
            String endpointId = resource.substring("vpc-endpoint/".length());
            List<VpcEndpoint> endpoints;
            try {
                endpoints = ec2.get().describeVpcEndpoints(region, List.of(endpointId), Map.of());
            } catch (AwsException e) {
                endpoints = List.of();
            }
            VpcEndpoint endpoint = endpoints.stream().findFirst()
                    .orElseThrow(() -> notFound("The VPC endpoint " + arn + " doesn't exist."));
            if (!"Interface".equalsIgnoreCase(endpoint.getVpcEndpointType())) {
                throw invalid("The VPC endpoint " + endpointId + " is a " + endpoint.getVpcEndpointType()
                        + " endpoint. A profile takes interface endpoints.");
            }
            return INTERFACE_ENDPOINT;
        }
        throw invalid("A profile can't hold " + arn + ". It takes private hosted zones, Resolver rules, DNS "
                + "Firewall rule groups and interface VPC endpoints.");
    }

    /**
     * The resource's properties, normalized. A rule group takes a {@code priority} between 100 and 9900 that no
     * other rule group on the profile has; the other kinds take none.
     */
    private String properties(String type, String raw, String profileId, String region, String self) {
        if (!FIREWALL_RULE_GROUP.equals(type)) {
            if (raw != null && !raw.isBlank() && !raw.trim().equals("{}")) {
                throw invalid("ResourceProperties apply only to DNS Firewall rule groups.");
            }
            return null;
        }
        if (raw == null || raw.isBlank()) {
            throw invalid("A DNS Firewall rule group needs ResourceProperties with a priority.");
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(raw);
        } catch (Exception e) {
            throw invalid("ResourceProperties must be a JSON object.");
        }
        JsonNode priority = parsed.path("priority");
        if (!priority.canConvertToInt() || priority.asInt() < 100 || priority.asInt() > 9900) {
            throw invalid("The rule group priority must be a number between 100 and 9900.");
        }
        for (ObjectNode other : live(resources, region)) {
            if (profileId.equals(other.path("ProfileId").asText()) && !other.path("Id").asText().equals(self)
                    && FIREWALL_RULE_GROUP.equals(other.path("ResourceType").asText())
                    && priorityOf(other) == priority.asInt()) {
                throw conflict("Another rule group on the profile " + profileId + " has priority "
                        + priority.asInt() + ".");
            }
        }
        return mapper.createObjectNode().put("priority", priority.asInt()).toString();
    }

    private int priorityOf(ObjectNode resource) {
        try {
            return mapper.readTree(resource.path("ResourceProperties").asText("{}")).path("priority").asInt(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- what other services read ----------

    /** The rule groups attached to a profile are in use; deleting one is a conflict. */
    @Override
    public Optional<String> userOf(String ruleGroupArn) {
        if (!AwsArnUtils.isArnFor(ruleGroupArn, "route53resolver")) {
            return Optional.empty();
        }
        String region = AwsArnUtils.parse(ruleGroupArn).region();
        for (ObjectNode resource : live(resources, region)) {
            if (ruleGroupArn.equals(resource.path("ResourceArn").asText())) {
                return Optional.of("the Route 53 Profile " + resource.path("ProfileId").asText());
            }
        }
        return Optional.empty();
    }

    /**
     * The Resolver rule ids an account's profiles apply to a VPC: the rules attached to the profile the VPC is
     * associated with, once both associations are complete. Read off the DNS packet path.
     */
    public List<String> resolverRuleIdsFor(String accountId, String region, String vpcId) {
        return RequestScopes.callAs(accountId, region, () -> {
            List<String> ids = new ArrayList<>();
            for (ObjectNode association : live(associations, region)) {
                if (!vpcId.equals(association.path("ResourceId").asText())
                        || !"COMPLETE".equals(association.path("Status").asText())) {
                    continue;
                }
                String profileId = association.path("ProfileId").asText();
                for (ObjectNode resource : live(resources, region)) {
                    if (profileId.equals(resource.path("ProfileId").asText())
                            && RESOLVER_RULE.equals(resource.path("ResourceType").asText())
                            && !"CREATING".equals(resource.path("Status").asText())) {
                        String arn = resource.path("ResourceArn").asText();
                        ids.add(arn.substring(arn.lastIndexOf('/') + 1));
                    }
                }
            }
            return ids;
        });
    }

    // ---------- tags ----------

    private ObjectNode tag(JsonNode in, boolean add) {
        String arn = decode(required(in, "ResourceArn"));
        ObjectNode profile = profileByArn(arn);
        ObjectNode tags = (ObjectNode) profile.get("_tags");
        if (add) {
            in.path("Tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
            if (tags.size() > 200) {
                throw new AwsException("ValidationException", "A profile can have at most 200 tags.", 400);
            }
        } else {
            for (JsonNode key : in.path("TagKeys")) {
                tags.remove(key.asText());
            }
        }
        profiles.put(key(AwsArnUtils.parse(arn).region(), profile.path("Id").asText()), profile);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", profileByArn(decode(required(in, "ResourceArn"))).get("_tags").deepCopy());
        return out;
    }

    private ObjectNode profileByArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "route53profiles") || !AwsArnUtils.parse(arn).resource().startsWith("profile/")) {
            throw notFound("The resource " + arn + " doesn't exist.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        ObjectNode profile = profile(parsed.region(), parsed.resource().substring("profile/".length()));
        if (!arn.equals(profile.path("Arn").asText())) {
            throw notFound("The resource " + arn + " doesn't exist.");
        }
        return profile;
    }

    // ---------- records and their lifecycle ----------

    private ObjectNode record(String id, String arnResource, String region, String account) {
        ObjectNode record = mapper.createObjectNode();
        record.put("Id", id);
        if (arnResource != null) {
            record.put("Arn", AwsArnUtils.Arn.of("route53profiles", region, account, arnResource).toString());
        }
        double now = now();
        record.put("OwnerId", account).put("Status", "CREATING").put("CreationTime", now)
                .put("ModificationTime", now);
        record.putObject("_").put("ready", now + step());
        return record;
    }

    /** The record, with any creation, update or deletion that is due applied. */
    private ObjectNode settle(AccountAwareStorageBackend<ObjectNode> store, String region, ObjectNode stored) {
        ObjectNode record = stored.deepCopy();
        ObjectNode meta = (ObjectNode) record.get("_");
        String status = record.path("Status").asText();
        double now = now();
        if (("CREATING".equals(status) || "UPDATING".equals(status)) && now >= meta.path("ready").asDouble()) {
            record.put("Status", "COMPLETE").put("StatusMessage", "CREATING".equals(status) ? "Created" : "Updated")
                    .put("ModificationTime", now);
            store.put(key(region, record.path("Id").asText()), record);
        } else if ("DELETING".equals(status) && now >= meta.path("gone").asDouble()) {
            store.delete(key(region, record.path("Id").asText()));
            return null;
        }
        return record;
    }

    private ObjectNode settled(AccountAwareStorageBackend<ObjectNode> store, String region, String id, String type) {
        ObjectNode record = store.get(key(region, decode(id))).map(stored -> settle(store, region, stored))
                .orElse(null);
        if (record == null) {
            throw notFound("The " + type + " " + id + " doesn't exist.");
        }
        return record;
    }

    /** Every record of the region that hasn't finished deleting, settled, oldest first. */
    private List<ObjectNode> live(AccountAwareStorageBackend<ObjectNode> store, String region) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode stored : store.scan(key -> key.startsWith(region + "/"))) {
            ObjectNode record = settle(store, region, stored);
            if (record != null && !"DELETING".equals(record.path("Status").asText())) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparingDouble((ObjectNode record) -> record.path("CreationTime").asDouble())
                .thenComparing(record -> record.path("Id").asText()));
        return out;
    }

    private ObjectNode retire(AccountAwareStorageBackend<ObjectNode> store, String region, ObjectNode record,
                              String message) {
        ObjectNode meta = (ObjectNode) record.get("_");
        meta.put("gone", now() + step());
        record.put("Status", "DELETING").put("StatusMessage", message).put("ModificationTime", now());
        store.put(key(region, record.path("Id").asText()), record);
        return record;
    }

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        view.remove("_tags");
        return view;
    }

    private ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        for (JsonNode tag : tags) {
            map.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        return map;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String field) {
        int max = in.path("MaxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException("InvalidNextTokenException", "The NextToken is not valid.", 400);
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + Math.max(1, max));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    /** The transition time, in seconds. */
    private double step() {
        return config.services().route53profiles().transitionMillis() / 1000.0;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static String hex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random).substring(0, bytes * 2 - 1);
    }

    private static String decode(String value) {
        return value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
    }

    private static String required(JsonNode in, String field) {
        String value = in.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return value;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}

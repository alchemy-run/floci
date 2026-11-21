package io.floci.aws.services.repostspace;

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
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.identitystore.model.Membership;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.services.ssoadmin.model.SsoInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * AWS re:Post Private: private spaces on the account's IAM Identity Center, their administrators, roles and channels,
 * and invitations. Accessors (administrators, role holders, channel members and invitees) are users and groups of
 * the Identity Center instance's identity store, checked there; unknown ones come back as per-accessor errors.
 */
@ApplicationScoped
public class RePostSpaceService implements Resettable {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long GIB = 1024L * 1024 * 1024;

    private final AccountAwareStorageBackend<ObjectNode> spaces;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<SsoAdminService> sso;
    private final Provider<IdentityStoreService> identities;
    private final Provider<IamService> iam;

    @Inject
    public RePostSpaceService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                              Provider<SsoAdminService> sso, Provider<IdentityStoreService> identities,
                              Provider<IamService> iam) {
        this.spaces = storageFactory.create("repostspace", "spaces.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.sso = sso;
        this.identities = identities;
        this.iam = iam;
    }

    @Override
    public void clear() {
        spaces.clear();
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "The " + type + " " + id + " doesn't exist.", 404,
                Map.of("resourceId", id, "resourceType", type));
    }

    private static AwsException conflict(String type, String id, String message) {
        return new AwsException("ConflictException", message, 409, Map.of("resourceId", id, "resourceType", type));
    }

    private long step() {
        return config.services().repostspace().transitionMillis();
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    /** A space, with its creation, deletion and domain approval settled by time. */
    private ObjectNode space(String region, String id) {
        ObjectNode space = spaces.get(key(region, id)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("space", id));
        ObjectNode meta = (ObjectNode) space.get("_");
        double now = now();
        boolean changed = false;
        if ("CREATING".equals(space.path("status").asText()) && now >= meta.path("ready").asDouble()) {
            space.put("status", "CREATE_COMPLETED").put("vanityDomainStatus", "APPROVED");
            changed = true;
        }
        if ("DELETING".equals(space.path("status").asText()) && now >= meta.path("gone").asDouble()) {
            spaces.delete(key(region, id));
            throw notFound("space", id);
        }
        if (changed) {
            spaces.put(key(region, id), space);
        }
        return space;
    }

    private void save(String region, ObjectNode space) {
        spaces.put(key(region, space.get("spaceId").asText()), space);
    }

    private ObjectNode view(ObjectNode record, String... fields) {
        ObjectNode view = mapper.createObjectNode();
        for (String field : fields) {
            if (record.has(field) && !record.get(field).isNull()) {
                view.set(field, record.get(field).deepCopy());
            }
        }
        return view;
    }

    private SsoInstance instance() {
        List<SsoInstance> instances = sso.get().listInstances(spaces.accountId());
        if (instances.isEmpty()) {
            throw validation("re:Post Private needs an IAM Identity Center instance.");
        }
        return instances.get(0);
    }

    // ================================================================ accessors

    /** Whether an id is a user or group of the space's identity store, and which. */
    private String accessorKind(ObjectNode space, String id) {
        String store = space.path("identityStoreId").asText();
        ObjectNode request = mapper.createObjectNode().put("IdentityStoreId", store);
        try {
            identities.get().describeUser(request.deepCopy().put("UserId", id));
            return "user";
        } catch (AwsException e) {
            try {
                identities.get().describeGroup(request.deepCopy().put("GroupId", id));
                return "group";
            } catch (AwsException notGroup) {
                return null;
            }
        }
    }

    private ObjectNode batch(ObjectNode space, JsonNode accessors, String resultName,
                             Consumer<String> apply) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode done = out.putArray(resultName);
        ArrayNode errors = out.putArray("errors");
        for (JsonNode accessor : accessors) {
            String id = accessor.asText();
            if (accessorKind(space, id) == null) {
                errors.addObject().put("accessorId", id).put("error", 404).put("message",
                        "The accessor " + id + " isn't a user or group of the space's identity store.");
                continue;
            }
            apply.accept(id);
            done.add(id);
        }
        return out;
    }

    // ================================================================ operations

    /** Fails when the request names a space that doesn't exist, as the service checks before validating the rest. */
    void checkSpace(JsonNode in, String region) {
        if (in.hasNonNull("spaceId")) {
            space(region, in.get("spaceId").asText());
        }
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateSpace" -> createSpace(in, region);
            case "GetSpace" -> getSpace(in, region);
            case "UpdateSpace" -> updateSpace(in, region);
            case "DeleteSpace" -> deleteSpace(in, region);
            case "ListSpaces" -> listSpaces(in, region);
            case "RegisterAdmin" -> admin(in, region, true);
            case "DeregisterAdmin" -> admin(in, region, false);
            case "BatchAddRole" -> roles(in, region, true);
            case "BatchRemoveRole" -> roles(in, region, false);
            case "SendInvites" -> sendInvites(in, region);
            case "CreateChannel" -> createChannel(in, region);
            case "GetChannel" -> getChannel(in, region);
            case "UpdateChannel" -> updateChannel(in, region);
            case "ListChannels" -> listChannels(in, region);
            case "BatchAddChannelRoleToAccessors" -> channelRoles(in, region, true);
            case "BatchRemoveChannelRoleFromAccessors" -> channelRoles(in, region, false);
            case "TagResource" -> tag(in, true);
            case "UntagResource" -> tag(in, false);
            case "ListTagsForResource" -> listTags(in);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    private void checkRole(String roleArn, String region) {
        if (roleArn == null) {
            return;
        }
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.parse(roleArn).resource().startsWith("role/")) {
            throw validation("roleArn " + roleArn + " isn't a role ARN.");
        }
        String resource = AwsArnUtils.parse(roleArn).resource();
        try {
            RequestScopes.callAs(AwsArnUtils.accountOrDefault(roleArn, spaces.accountId()), region, () ->
                    iam.get().getRole(resource.substring(resource.lastIndexOf('/') + 1)));
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "The role " + roleArn + " doesn't exist.", 404);
        }
    }

    private static ObjectNode emailDomains(ObjectMapper mapper, JsonNode in) {
        ObjectNode status = mapper.createObjectNode().put("enabled", in.path("enabled").asText("DISABLED"));
        ArrayNode domains = status.putArray("allowedDomains");
        for (JsonNode domain : in.path("allowedDomains")) {
            if (!domain.asText().matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) {
                throw validation("The email domain " + domain.asText() + " isn't a domain name.");
            }
            domains.add(domain.asText().toLowerCase(Locale.ROOT));
        }
        return status;
    }

    private ObjectNode createSpace(JsonNode in, String region) {
        String subdomain = in.path("subdomain").asText().toLowerCase(Locale.ROOT);
        if (!subdomain.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?")) {
            throw validation("subdomain must be lowercase letters, digits and hyphens.");
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : spaces.scanAllAccountEntries(k -> true)) {
            if (subdomain.equals(entry.value().path("_").path("subdomain").asText())
                    && !"DELETING".equals(entry.value().path("status").asText())) {
                throw conflict("space", subdomain, "The subdomain " + subdomain + " is taken.");
            }
        }
        if (spaces.scan(k -> k.startsWith(region + "/")).size() >= 5) {
            throw new AwsException("ServiceQuotaExceededException", "A Region has at most 5 spaces.", 402);
        }
        String roleArn = in.hasNonNull("roleArn") ? in.get("roleArn").asText() : null;
        checkRole(roleArn, region);
        SsoInstance instance = instance();
        String id = "SP" + random(22);
        String tier = in.path("tier").asText();
        ObjectNode space = mapper.createObjectNode();
        ObjectNode meta = space.putObject("_").put("subdomain", subdomain).put("ready", now() + step() / 1000.0);
        meta.putObject("tags");
        meta.putArray("invites");
        in.path("tags").properties().forEach(tag -> ((ObjectNode) meta.get("tags")).put(tag.getKey(),
                tag.getValue().asText()));
        String partition = AwsArnUtils.parse(instance.instanceArn()).partition();
        space.put("spaceId", id).put("arn", AwsArnUtils.Arn.of("repostspace", region, spaces.accountId(), "space/" + id)
                .toString()).put("name", in.path("name").asText()).put("status", "CREATING")
                .put("configurationStatus", "UNCONFIGURED").put("clientId", random(26))
                .put("identityStoreId", instance.identityStoreId())
                .put("applicationArn", AwsArnUtils.Arn.global(partition, "sso", spaces.accountId(), "application/"
                        + instance.instanceArn().substring(instance.instanceArn().lastIndexOf('/') + 1) + "/apl-"
                        + random(16).toLowerCase(Locale.ROOT)).toString())
                .put("vanityDomainStatus", "PENDING").put("vanityDomain", subdomain + "." + domain())
                .put("randomDomain", random(12).toLowerCase(Locale.ROOT) + "." + domain())
                .put("createDateTime", now()).put("tier", tier)
                .put("storageLimit", "STANDARD".equals(tier) ? 1024 * GIB : 10 * GIB)
                .put("userCount", 0).put("contentSize", 0);
        for (String member : List.of("description", "userKMSKey")) {
            if (in.hasNonNull(member)) {
                space.put(member, in.get(member).asText());
            }
        }
        if (roleArn != null) {
            space.put("customerRoleArn", roleArn);
        }
        space.set("supportedEmailDomains", emailDomains(mapper, in.path("supportedEmailDomains")));
        space.putArray("userAdmins");
        space.putArray("groupAdmins");
        space.putObject("roles");
        space.putObject("channels");
        save(region, space);
        return mapper.createObjectNode().put("spaceId", id);
    }

    /** The domain spaces are served under: re:Post Private's in the commercial partition. */
    private static String domain() {
        return "private.repost.aws";
    }

    private static final String[] SPACE_FIELDS = {"spaceId", "arn", "name", "status", "configurationStatus",
        "clientId", "identityStoreId", "applicationArn", "description", "vanityDomainStatus", "vanityDomain",
        "randomDomain", "customerRoleArn", "createDateTime", "deleteDateTime", "tier", "storageLimit", "userAdmins",
        "groupAdmins", "roles", "userKMSKey", "userCount", "contentSize", "supportedEmailDomains"};

    private ObjectNode getSpace(JsonNode in, String region) {
        return view(space(region, in.path("spaceId").asText()), SPACE_FIELDS);
    }

    private ObjectNode active(String region, String id) {
        ObjectNode space = space(region, id);
        if (!"CREATE_COMPLETED".equals(space.path("status").asText())) {
            throw conflict("space", id, "The space is " + space.path("status").asText() + ".");
        }
        return space;
    }

    private ObjectNode updateSpace(JsonNode in, String region) {
        ObjectNode space = active(region, in.path("spaceId").asText());
        if (in.hasNonNull("description")) {
            space.put("description", in.get("description").asText());
        }
        if (in.hasNonNull("tier")) {
            String tier = in.get("tier").asText();
            if ("BASIC".equals(tier) && space.path("contentSize").asLong() > 10 * GIB) {
                throw conflict("space", space.get("spaceId").asText(), "The space holds more than BASIC allows.");
            }
            space.put("tier", tier).put("storageLimit", "STANDARD".equals(tier) ? 1024 * GIB : 10 * GIB);
        }
        if (in.hasNonNull("roleArn")) {
            checkRole(in.get("roleArn").asText(), region);
            space.put("customerRoleArn", in.get("roleArn").asText());
        }
        if (in.has("supportedEmailDomains")) {
            space.set("supportedEmailDomains", emailDomains(mapper, in.get("supportedEmailDomains")));
        }
        save(region, space);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteSpace(JsonNode in, String region) {
        ObjectNode space = space(region, in.path("spaceId").asText());
        if (!"DELETING".equals(space.path("status").asText())) {
            space.put("status", "DELETING").put("deleteDateTime", now());
            ((ObjectNode) space.get("_")).put("gone", now() + step() / 1000.0);
            save(region, space);
        }
        return mapper.createObjectNode();
    }

    private ObjectNode listSpaces(JsonNode in, String region) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode stored : spaces.scan(k -> k.startsWith(region + "/"))) {
            try {
                found.add(space(region, stored.get("spaceId").asText()));
            } catch (AwsException e) {
                // deleted while listing
            }
        }
        found.sort(Comparator.comparingDouble(space -> space.path("createDateTime").asDouble()));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("spaces");
        page(found, in, out).forEach(space -> list.add(view(space, "spaceId", "arn", "name", "description", "status",
                "configurationStatus", "vanityDomainStatus", "vanityDomain", "randomDomain", "tier", "storageLimit",
                "createDateTime", "deleteDateTime", "userKMSKey", "userCount", "contentSize",
                "supportedEmailDomains")));
        return out;
    }

    private static <T> List<T> page(List<T> items, JsonNode in, ObjectNode out) {
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("nextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + in.path("maxResults").asInt(100));
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return items.subList(Math.min(start, items.size()), end);
    }

    private ObjectNode admin(JsonNode in, String region, boolean register) {
        ObjectNode space = active(region, in.path("spaceId").asText());
        String id = in.path("adminId").asText();
        String kind = accessorKind(space, id);
        if (kind == null) {
            throw notFound("admin", id);
        }
        ArrayNode admins = (ArrayNode) space.get("user".equals(kind) ? "userAdmins" : "groupAdmins");
        Set<String> ids = new LinkedHashSet<>();
        admins.forEach(a -> ids.add(a.asText()));
        if (register) {
            ids.add(id);
        } else if (!ids.remove(id)) {
            throw notFound("admin", id);
        }
        admins.removeAll();
        ids.forEach(admins::add);
        boolean configured = !space.get("userAdmins").isEmpty() || !space.get("groupAdmins").isEmpty();
        space.put("configurationStatus", configured ? "CONFIGURED" : "UNCONFIGURED");
        save(region, space);
        return mapper.createObjectNode();
    }

    private static void updateRoles(ObjectNode roles, String id, String role, boolean add) {
        ArrayNode held = roles.has(id) ? (ArrayNode) roles.get(id) : roles.putArray(id);
        Set<String> set = new LinkedHashSet<>();
        held.forEach(r -> set.add(r.asText()));
        if (add) {
            set.add(role);
        } else {
            set.remove(role);
        }
        held.removeAll();
        set.forEach(held::add);
        if (held.isEmpty()) {
            roles.remove(id);
        }
    }

    private ObjectNode roles(JsonNode in, String region, boolean add) {
        ObjectNode space = active(region, in.path("spaceId").asText());
        ObjectNode roles = (ObjectNode) space.get("roles");
        String role = in.path("role").asText();
        ObjectNode out = batch(space, in.path("accessorIds"), add ? "addedAccessorIds" : "removedAccessorIds",
                id -> updateRoles(roles, id, role, add));
        space.put("userCount", countUsers(space));
        save(region, space);
        return out;
    }

    /** The users who can enter a space: its administrators and role holders, with groups expanded. */
    private int countUsers(ObjectNode space) {
        Set<String> users = new LinkedHashSet<>();
        List<String> accessors = new ArrayList<>();
        space.path("userAdmins").forEach(a -> accessors.add(a.asText()));
        space.path("groupAdmins").forEach(a -> accessors.add(a.asText()));
        space.path("roles").fieldNames().forEachRemaining(accessors::add);
        for (String id : accessors) {
            users.addAll(members(space, id));
        }
        return users.size();
    }

    private List<String> members(ObjectNode space, String accessor) {
        String kind = accessorKind(space, accessor);
        if ("user".equals(kind)) {
            return List.of(accessor);
        }
        if (!"group".equals(kind)) {
            return List.of();
        }
        List<String> users = new ArrayList<>();
        ObjectNode request = mapper.createObjectNode().put("IdentityStoreId", space.path("identityStoreId").asText())
                .put("GroupId", accessor);
        for (Membership membership : identities.get().listGroupMemberships(request).items()) {
            if (membership.userId() != null) {
                users.add(membership.userId());
            }
        }
        return users;
    }

    private ObjectNode sendInvites(JsonNode in, String region) {
        ObjectNode space = active(region, in.path("spaceId").asText());
        ArrayNode invites = (ArrayNode) space.get("_").get("invites");
        for (JsonNode accessor : in.path("accessorIds")) {
            if (accessorKind(space, accessor.asText()) == null) {
                throw notFound("accessor", accessor.asText());
            }
        }
        for (JsonNode accessor : in.path("accessorIds")) {
            for (String user : members(space, accessor.asText())) {
                invites.addObject().put("userId", user).put("title", in.path("title").asText())
                        .put("body", in.path("body").asText()).put("sentAt", now());
            }
        }
        save(region, space);
        return mapper.createObjectNode();
    }

    // ================================================================ channels

    private ObjectNode channel(ObjectNode space, String id) {
        JsonNode channel = space.path("channels").get(id);
        if (channel == null) {
            throw notFound("channel", id);
        }
        ObjectNode settled = (ObjectNode) channel;
        if ("CREATING".equals(settled.path("channelStatus").asText())
                && now() >= settled.path("_ready").asDouble()) {
            settled.put("channelStatus", "CREATED");
        }
        return settled;
    }

    private ObjectNode createChannel(JsonNode in, String region) {
        ObjectNode space = active(region, in.path("spaceId").asText());
        ObjectNode channels = (ObjectNode) space.get("channels");
        String name = in.path("channelName").asText();
        for (JsonNode existing : channels) {
            if (name.equals(existing.path("channelName").asText())) {
                throw conflict("channel", existing.path("channelId").asText(), "The channel " + name
                        + " already exists.");
            }
        }
        if (channels.size() >= 50) {
            throw new AwsException("ServiceQuotaExceededException", "A space has at most 50 channels.", 402);
        }
        String id = random(24);
        ObjectNode channel = channels.putObject(id).put("spaceId", space.get("spaceId").asText()).put("channelId", id)
                .put("channelName", name).put("createDateTime", now()).put("channelStatus", "CREATING")
                .put("_ready", now() + step() / 1000.0);
        if (in.hasNonNull("channelDescription")) {
            channel.put("channelDescription", in.get("channelDescription").asText());
        }
        channel.putObject("channelRoles");
        save(region, space);
        return mapper.createObjectNode().put("channelId", id);
    }

    private ObjectNode getChannel(JsonNode in, String region) {
        ObjectNode space = space(region, in.path("spaceId").asText());
        return view(channel(space, in.path("channelId").asText()), "spaceId", "channelId", "channelName",
                "channelDescription", "createDateTime", "deleteDateTime", "channelRoles", "channelStatus");
    }

    private ObjectNode updateChannel(JsonNode in, String region) {
        ObjectNode space = active(region, in.path("spaceId").asText());
        ObjectNode channel = channel(space, in.path("channelId").asText());
        String name = in.path("channelName").asText();
        for (JsonNode existing : space.get("channels")) {
            if (name.equals(existing.path("channelName").asText()) && existing != channel) {
                throw conflict("channel", existing.path("channelId").asText(), "The channel " + name
                        + " already exists.");
            }
        }
        channel.put("channelName", name);
        if (in.has("channelDescription")) {
            channel.put("channelDescription", in.get("channelDescription").asText());
        }
        save(region, space);
        return mapper.createObjectNode();
    }

    private ObjectNode listChannels(JsonNode in, String region) {
        ObjectNode space = space(region, in.path("spaceId").asText());
        List<ObjectNode> channels = new ArrayList<>();
        space.path("channels").fieldNames().forEachRemaining(id -> channels.add(channel(space, id)));
        channels.sort(Comparator.comparingDouble(c -> c.path("createDateTime").asDouble()));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("channels");
        for (ObjectNode channel : page(channels, in, out)) {
            ObjectNode view = view(channel, "spaceId", "channelId", "channelName", "channelDescription",
                    "createDateTime", "deleteDateTime", "channelStatus");
            int users = 0;
            int groups = 0;
            for (String id : (Iterable<String>) () -> channel.path("channelRoles").fieldNames()) {
                if ("group".equals(accessorKind(space, id))) {
                    groups++;
                } else {
                    users++;
                }
            }
            list.add(view.put("userCount", users).put("groupCount", groups));
        }
        return out;
    }

    private ObjectNode channelRoles(JsonNode in, String region, boolean add) {
        ObjectNode space = active(region, in.path("spaceId").asText());
        ObjectNode channel = channel(space, in.path("channelId").asText());
        ObjectNode roles = (ObjectNode) channel.get("channelRoles");
        String role = in.path("channelRole").asText();
        ObjectNode out = batch(space, in.path("accessorIds"), add ? "addedAccessorIds" : "removedAccessorIds",
                id -> updateRoles(roles, id, role, add));
        save(region, space);
        return out;
    }

    // ================================================================ tags

    private ObjectNode byArn(String encoded) {
        String arn = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        if (!AwsArnUtils.isArnFor(arn, "repostspace")) {
            throw validation("The ARN " + arn + " isn't a re:Post Private ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.accountId().equals(spaces.accountId())) {
            throw notFound("space", arn);
        }
        return space(parsed.region(), parsed.resource().substring(parsed.resource().indexOf('/') + 1));
    }

    private ObjectNode tag(JsonNode in, boolean add) {
        ObjectNode space = byArn(in.path("resourceArn").asText());
        ObjectNode tags = (ObjectNode) space.get("_").get("tags");
        if (add) {
            in.path("tags").properties().forEach(tag -> {
                if (tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                    throw validation("Tag keys can't start with aws:.");
                }
                tags.put(tag.getKey(), tag.getValue().asText());
            });
            if (tags.size() > 50) {
                throw validation("A space has at most 50 tags.");
            }
        } else {
            in.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        }
        save(AwsArnUtils.parse(space.get("arn").asText()).region(), space);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in) {
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", byArn(in.path("resourceArn").asText()).get("_").get("tags").deepCopy());
        return out;
    }
}

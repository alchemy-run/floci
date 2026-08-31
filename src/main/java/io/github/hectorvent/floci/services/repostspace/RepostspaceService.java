package io.github.hectorvent.floci.services.repostspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.repostspace.model.Channel;
import io.github.hectorvent.floci.services.repostspace.model.Space;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS re:Post Private restJson1 — private re:Post space and channel lifecycle.
 *
 * <p>Provisioning is synchronous here: {@code CreateSpace} leaves the space in
 * {@code CREATE_COMPLETED} so local stacks do not wait on the live ~30 minute
 * Identity Center setup. GetChannel / ListChannels / SendInvites / BatchAddRole
 * on a missing space return {@code ResourceNotFoundException}. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController} using
 * ARN service {@code repostspace}.
 */
@ApplicationScoped
public class RepostspaceService implements TagHandler {

    static final String SERVICE = "repostspace";
    private static final String RESOURCE_SPACE = "AWS::repostspace::space";
    private static final String STATUS_CREATE_COMPLETED = "CREATE_COMPLETED";
    private static final String TIER_BASIC = "BASIC";
    private static final String TIER_STANDARD = "STANDARD";
    private static final Set<String> TIERS = Set.of(TIER_BASIC, TIER_STANDARD);
    private static final Set<String> ROLES = Set.of("EXPERT", "MODERATOR", "ADMINISTRATOR", "SUPPORTREQUESTOR");
    private static final Set<String> CHANNEL_ROLES = Set.of("ASKER", "EXPERT", "MODERATOR", "SUPPORTREQUESTOR");
    private static final Set<String> EMAIL_DOMAIN_ENABLED = Set.of("ENABLED", "DISABLED");
    private static final long BASIC_STORAGE_LIMIT = 10L * 1024 * 1024 * 1024;
    private static final long STANDARD_STORAGE_LIMIT = 100L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "repostspace:v1:";
    private static final Pattern SUBDOMAIN_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^.{1,30}$");

    private final StorageBackend<String, Space> spaces;
    private final StorageBackend<String, Channel> channels;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public RepostspaceService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(
                        SERVICE,
                        "repostspace-spaces.json",
                        new TypeReference<Map<String, Space>>() {
                        }),
                storageFactory.create(
                        SERVICE,
                        "repostspace-channels.json",
                        new TypeReference<Map<String, Channel>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    RepostspaceService(
            StorageBackend<String, Space> spaces,
            StorageBackend<String, Channel> channels,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.spaces = spaces;
        this.channels = channels;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Space createSpace(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must be between 1 and 30 characters.");
        }
        String subdomain = requireText(request, "subdomain");
        if (!SUBDOMAIN_PATTERN.matcher(subdomain).matches()) {
            throw validation("subdomain must be 1-63 lowercase alphanumeric characters and hyphens.");
        }
        String tier = requireText(request, "tier").toUpperCase();
        if (!TIERS.contains(tier)) {
            throw validation("tier must be BASIC or STANDARD.");
        }
        Space existing = findByName(region, name);
        if (existing != null) {
            throw conflict(existing.getSpaceId(), "A private re:Post named " + name + " already exists.");
        }
        existing = findBySubdomain(region, subdomain);
        if (existing != null) {
            throw conflict(existing.getSpaceId(), "Subdomain " + subdomain + " is already in use.");
        }

        String account = regionResolver.getAccountId();
        String spaceId = newSpaceId();
        String suffix = spaceId.substring(2, 10).toLowerCase();
        Space space = new Space();
        space.setSpaceId(spaceId);
        space.setArn(arn(region, account, spaceId));
        space.setName(name);
        space.setSubdomain(subdomain);
        space.setStatus(STATUS_CREATE_COMPLETED);
        space.setConfigurationStatus("CONFIGURED");
        space.setClientId(UUID.randomUUID().toString());
        space.setIdentityStoreId("d-" + spaceId.substring(2, 12).toLowerCase());
        space.setApplicationArn("arn:aws:sso::" + account + ":application/" + space.getIdentityStoreId()
                + "/apl-" + spaceId.substring(12, 24).toLowerCase());
        space.setDescription(optionalText(request, "description"));
        space.setVanityDomain(subdomain);
        space.setVanityDomainStatus("APPROVED");
        space.setRandomDomain(subdomain + "-" + suffix + ".private.repost.aws");
        space.setCustomerRoleArn(optionalText(request, "roleArn"));
        space.setCreateDateTime(Instant.now().toString());
        space.setTier(tier);
        space.setStorageLimit(TIER_STANDARD.equals(tier) ? STANDARD_STORAGE_LIMIT : BASIC_STORAGE_LIMIT);
        space.setUserKMSKey(optionalText(request, "userKMSKey"));
        applySupportedEmailDomains(space, request.get("supportedEmailDomains"), true);
        space.setTags(readTags(request.get("tags")));
        spaces.put(storageKey(region, spaceId), space);
        return space;
    }

    public Space getSpace(String region, String spaceId) {
        return requireSpace(region, spaceId);
    }

    public synchronized Space updateSpace(String region, String spaceId, JsonNode request) {
        requireObject(request, "Request body");
        Space space = requireSpace(region, spaceId);
        if (request.has("description")) {
            space.setDescription(optionalText(request, "description"));
        }
        if (request.has("tier") && !request.get("tier").isNull()) {
            String tier = requireText(request, "tier").toUpperCase();
            if (!TIERS.contains(tier)) {
                throw validation("tier must be BASIC or STANDARD.");
            }
            space.setTier(tier);
            space.setStorageLimit(TIER_STANDARD.equals(tier) ? STANDARD_STORAGE_LIMIT : BASIC_STORAGE_LIMIT);
        }
        if (request.has("roleArn")) {
            space.setCustomerRoleArn(optionalText(request, "roleArn"));
        }
        if (request.has("supportedEmailDomains")) {
            applySupportedEmailDomains(space, request.get("supportedEmailDomains"), false);
        }
        spaces.put(storageKey(region, space.getSpaceId()), space);
        return space;
    }

    public synchronized void deleteSpace(String region, String spaceId) {
        Space space = requireSpace(region, spaceId);
        String prefix = region + "::" + space.getSpaceId() + "::";
        for (Channel channel : channels.scan(key -> key.startsWith(prefix))) {
            channels.delete(channelKey(region, space.getSpaceId(), channel.getChannelId()));
        }
        spaces.delete(storageKey(region, space.getSpaceId()));
    }

    public synchronized Channel createChannel(String region, String spaceId, JsonNode request) {
        requireSpace(region, spaceId);
        requireObject(request, "Request body");
        String channelName = requireText(request, "channelName");
        String channelId = "CH" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
        Channel channel = new Channel();
        channel.setSpaceId(spaceId);
        channel.setChannelId(channelId);
        channel.setChannelName(channelName);
        channel.setChannelDescription(optionalText(request, "channelDescription"));
        channel.setCreateDateTime(Instant.now().toString());
        channel.setChannelStatus("CREATED");
        channels.put(channelKey(region, spaceId, channelId), channel);
        return channel;
    }

    public Channel getChannel(String region, String spaceId, String channelId) {
        requireSpace(region, spaceId);
        return requireChannel(region, spaceId, channelId);
    }

    public List<Channel> listChannels(String region, String spaceId) {
        requireSpace(region, spaceId);
        List<Channel> items = channels.scan(key -> key.startsWith(region + "::" + spaceId + "::"));
        items.sort(Comparator.comparing(Channel::getChannelId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized Channel updateChannel(String region, String spaceId, String channelId, JsonNode request) {
        requireSpace(region, spaceId);
        requireObject(request, "Request body");
        Channel channel = requireChannel(region, spaceId, channelId);
        channel.setChannelName(requireText(request, "channelName"));
        if (request.has("channelDescription")) {
            channel.setChannelDescription(optionalText(request, "channelDescription"));
        }
        channels.put(channelKey(region, spaceId, channelId), channel);
        return channel;
    }

    public synchronized BatchRoleResult batchAddRole(String region, String spaceId, JsonNode request) {
        Space space = requireSpace(region, spaceId);
        requireObject(request, "Request body");
        List<String> accessorIds = requireAccessorIds(request);
        String role = requireAllowed(request, "role", ROLES);
        Map<String, List<String>> roles = space.getRoles();
        for (String accessorId : accessorIds) {
            List<String> current = roles.computeIfAbsent(accessorId, key -> new ArrayList<>());
            if (!current.contains(role)) {
                current.add(role);
            }
        }
        space.setRoles(roles);
        spaces.put(storageKey(region, spaceId), space);
        return new BatchRoleResult(accessorIds, List.of());
    }

    public synchronized BatchRoleResult batchRemoveRole(String region, String spaceId, JsonNode request) {
        Space space = requireSpace(region, spaceId);
        requireObject(request, "Request body");
        List<String> accessorIds = requireAccessorIds(request);
        String role = requireAllowed(request, "role", ROLES);
        Map<String, List<String>> roles = space.getRoles();
        for (String accessorId : accessorIds) {
            List<String> current = roles.get(accessorId);
            if (current != null) {
                current.remove(role);
                if (current.isEmpty()) {
                    roles.remove(accessorId);
                }
            }
        }
        space.setRoles(roles);
        spaces.put(storageKey(region, spaceId), space);
        return new BatchRoleResult(accessorIds, List.of());
    }

    public synchronized BatchRoleResult batchAddChannelRole(
            String region, String spaceId, String channelId, JsonNode request) {
        requireSpace(region, spaceId);
        Channel channel = requireChannel(region, spaceId, channelId);
        requireObject(request, "Request body");
        List<String> accessorIds = requireAccessorIds(request);
        String role = requireAllowed(request, "channelRole", CHANNEL_ROLES);
        Map<String, List<String>> roles = channel.getChannelRoles();
        for (String accessorId : accessorIds) {
            List<String> current = roles.computeIfAbsent(accessorId, key -> new ArrayList<>());
            if (!current.contains(role)) {
                current.add(role);
            }
        }
        channel.setChannelRoles(roles);
        channels.put(channelKey(region, spaceId, channelId), channel);
        return new BatchRoleResult(accessorIds, List.of());
    }

    public synchronized BatchRoleResult batchRemoveChannelRole(
            String region, String spaceId, String channelId, JsonNode request) {
        requireSpace(region, spaceId);
        Channel channel = requireChannel(region, spaceId, channelId);
        requireObject(request, "Request body");
        List<String> accessorIds = requireAccessorIds(request);
        String role = requireAllowed(request, "channelRole", CHANNEL_ROLES);
        Map<String, List<String>> roles = channel.getChannelRoles();
        for (String accessorId : accessorIds) {
            List<String> current = roles.get(accessorId);
            if (current != null) {
                current.remove(role);
                if (current.isEmpty()) {
                    roles.remove(accessorId);
                }
            }
        }
        channel.setChannelRoles(roles);
        channels.put(channelKey(region, spaceId, channelId), channel);
        return new BatchRoleResult(accessorIds, List.of());
    }

    public synchronized void registerAdmin(String region, String spaceId, String adminId) {
        Space space = requireSpace(region, spaceId);
        if (adminId == null || adminId.isBlank()) {
            throw validation("adminId is required.");
        }
        List<String> admins = space.getUserAdmins();
        if (!admins.contains(adminId)) {
            admins.add(adminId);
        }
        space.setUserAdmins(admins);
        spaces.put(storageKey(region, spaceId), space);
    }

    public synchronized void deregisterAdmin(String region, String spaceId, String adminId) {
        Space space = requireSpace(region, spaceId);
        List<String> admins = space.getUserAdmins();
        admins.remove(adminId);
        space.setUserAdmins(admins);
        spaces.put(storageKey(region, spaceId), space);
    }

    public void sendInvites(String region, String spaceId, JsonNode request) {
        requireSpace(region, spaceId);
        requireObject(request, "Request body");
        requireAccessorIds(request);
        requireText(request, "title");
        requireText(request, "body");
    }

    public ObjectNode toChannel(Channel channel) {
        ObjectNode node = toChannelData(channel);
        ObjectNode roles = node.putObject("channelRoles");
        channel.getChannelRoles().forEach((accessor, list) -> {
            ArrayNode values = roles.putArray(accessor);
            if (list != null) {
                list.forEach(values::add);
            }
        });
        return node;
    }

    public ObjectNode toChannelData(Channel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("spaceId", channel.getSpaceId());
        node.put("channelId", channel.getChannelId());
        node.put("channelName", channel.getChannelName());
        putText(node, "channelDescription", channel.getChannelDescription());
        node.put("createDateTime", channel.getCreateDateTime());
        putText(node, "deleteDateTime", channel.getDeleteDateTime());
        node.put("channelStatus", channel.getChannelStatus());
        node.put("userCount", channel.getUserCount());
        node.put("groupCount", channel.getGroupCount());
        return node;
    }

    public ObjectNode toBatchResult(BatchRoleResult result, String idsKey) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode ids = node.putArray(idsKey);
        result.ids().forEach(ids::add);
        ArrayNode errors = node.putArray("errors");
        for (BatchError error : result.errors()) {
            ObjectNode item = errors.addObject();
            item.put("accessorId", error.accessorId());
            item.put("error", error.error());
            item.put("message", error.message());
        }
        return node;
    }

    public Page listSpaces(String region, String maxResultsValue, String nextToken) {
        List<Space> items = spaces.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(Space::getSpaceId, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResultsValue, nextToken);
    }

    public ObjectNode toGetSpace(Space space) {
        ObjectNode node = toSpaceData(space);
        node.put("clientId", space.getClientId());
        putText(node, "identityStoreId", space.getIdentityStoreId());
        putText(node, "applicationArn", space.getApplicationArn());
        putText(node, "customerRoleArn", space.getCustomerRoleArn());
        if (space.getUserAdmins() != null && !space.getUserAdmins().isEmpty()) {
            ArrayNode admins = node.putArray("userAdmins");
            space.getUserAdmins().forEach(admins::add);
        }
        if (space.getGroupAdmins() != null && !space.getGroupAdmins().isEmpty()) {
            ArrayNode admins = node.putArray("groupAdmins");
            space.getGroupAdmins().forEach(admins::add);
        }
        if (space.getRoles() != null && !space.getRoles().isEmpty()) {
            ObjectNode roles = node.putObject("roles");
            space.getRoles().forEach((accessor, list) -> {
                ArrayNode values = roles.putArray(accessor);
                if (list != null) {
                    list.forEach(values::add);
                }
            });
        }
        return node;
    }

    public ObjectNode toSpaceData(Space space) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("spaceId", space.getSpaceId());
        node.put("arn", space.getArn());
        node.put("name", space.getName());
        node.put("status", space.getStatus());
        node.put("configurationStatus", space.getConfigurationStatus());
        putText(node, "description", space.getDescription());
        node.put("vanityDomainStatus", space.getVanityDomainStatus());
        node.put("vanityDomain", space.getVanityDomain());
        node.put("randomDomain", space.getRandomDomain());
        node.put("createDateTime", space.getCreateDateTime());
        putText(node, "deleteDateTime", space.getDeleteDateTime());
        node.put("tier", space.getTier());
        node.put("storageLimit", space.getStorageLimit());
        putText(node, "userKMSKey", space.getUserKMSKey());
        if (space.getUserCount() > 0) {
            node.put("userCount", space.getUserCount());
        }
        if (space.getContentSize() > 0) {
            node.put("contentSize", space.getContentSize());
        }
        if (space.getSupportedEmailDomainsEnabled() != null) {
            ObjectNode domains = node.putObject("supportedEmailDomains");
            domains.put("enabled", space.getSupportedEmailDomainsEnabled());
            if (space.getAllowedDomains() != null && !space.getAllowedDomains().isEmpty()) {
                ArrayNode allowed = domains.putArray("allowedDomains");
                space.getAllowedDomains().forEach(allowed::add);
            }
        }
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Space space = requireTagged(region, arn);
        return Map.copyOf(space.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Space space = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(space.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        space.setTags(current);
        spaces.put(storageKey(region, space.getSpaceId()), space);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Space space = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(space.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        space.setTags(current);
        spaces.put(storageKey(region, space.getSpaceId()), space);
    }

    private Space requireSpace(String region, String spaceId) {
        String id = decode(spaceId);
        return spaces.get(storageKey(region, id)).orElseThrow(() -> notFound(id));
    }

    private Channel requireChannel(String region, String spaceId, String channelId) {
        String id = decode(channelId);
        return channels.get(channelKey(region, spaceId, id)).orElseThrow(() -> notFoundChannel(id));
    }

    private Space requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decode(arn));
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("space/")) {
            throw validation("resourceArn is invalid.");
        }
        return requireSpace(region, parsed.resource().substring("space/".length()));
    }

    private Space findByName(String region, String name) {
        for (Space space : spaces.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(space.getName())) {
                return space;
            }
        }
        return null;
    }

    private Space findBySubdomain(String region, String subdomain) {
        for (Space space : spaces.scan(key -> key.startsWith(region + "::"))) {
            if (subdomain.equals(space.getSubdomain())) {
                return space;
            }
        }
        return null;
    }

    private void applySupportedEmailDomains(Space space, JsonNode node, boolean creating) {
        if (node == null || node.isNull()) {
            if (creating) {
                space.setSupportedEmailDomainsEnabled(null);
                space.setAllowedDomains(List.of());
            }
            return;
        }
        requireObject(node, "supportedEmailDomains");
        String enabled = optionalText(node, "enabled");
        if (enabled != null && !EMAIL_DOMAIN_ENABLED.contains(enabled)) {
            throw validation("supportedEmailDomains.enabled must be ENABLED or DISABLED.");
        }
        space.setSupportedEmailDomainsEnabled(enabled);
        JsonNode allowed = node.get("allowedDomains");
        if (allowed == null || allowed.isNull()) {
            space.setAllowedDomains(List.of());
            return;
        }
        if (!allowed.isArray()) {
            throw validation("supportedEmailDomains.allowedDomains must be an array.");
        }
        List<String> domains = new ArrayList<>();
        for (JsonNode domain : allowed) {
            if (domain == null || !domain.isTextual()) {
                throw validation("supportedEmailDomains.allowedDomains members must be strings.");
            }
            domains.add(domain.textValue());
        }
        space.setAllowedDomains(domains);
    }

    private String arn(String region, String account, String spaceId) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "space/" + spaceId).toString();
    }

    private static String storageKey(String region, String spaceId) {
        return region + "::" + spaceId;
    }

    private static String channelKey(String region, String spaceId, String channelId) {
        return region + "::" + spaceId + "::" + channelId;
    }

    private static String newSpaceId() {
        return "SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    private Page page(List<Space> items, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page(items.subList(offset, end), responseToken);
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset > resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static List<String> requireAccessorIds(JsonNode request) {
        JsonNode node = request.get("accessorIds");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw validation("accessorIds is required.");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode value : node) {
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw validation("accessorIds contains an invalid value.");
            }
            ids.add(value.textValue());
        }
        return ids;
    }

    private static String requireAllowed(JsonNode request, String field, Set<String> allowed) {
        String value = requireText(request, field);
        if (!allowed.contains(value)) {
            throw validation(field + " is not a valid role.");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    static AwsException notFound(String spaceId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Space " + spaceId + " does not exist.",
                404,
                Map.of("resourceId", spaceId, "resourceType", RESOURCE_SPACE));
    }

    static AwsException notFoundChannel(String channelId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Channel " + channelId + " does not exist.",
                404,
                Map.of("resourceId", channelId, "resourceType", "channel"));
    }

    static AwsException conflict(String spaceId, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("resourceId", spaceId, "resourceType", RESOURCE_SPACE));
    }

    static AwsException validation(String message) {
        return new AwsException(
                "ValidationException", message, 400, Map.of("reason", "fieldValidationFailed"));
    }

    public record Page(List<Space> spaces, String nextToken) {
        public Page {
            spaces = List.copyOf(spaces);
        }
    }

    public record BatchRoleResult(List<String> ids, List<BatchError> errors) {
        public BatchRoleResult {
            ids = List.copyOf(ids);
            errors = List.copyOf(errors);
        }
    }

    public record BatchError(String accessorId, int error, String message) {
    }
}

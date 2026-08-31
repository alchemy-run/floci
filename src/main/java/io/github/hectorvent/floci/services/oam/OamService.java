package io.github.hectorvent.floci.services.oam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.oam.model.OamLink;
import io.github.hectorvent.floci.services.oam.model.OamSink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CloudWatch Observability Access Manager (OAM) restJson1 — sink/link lifecycle
 * and ListAttachedLinks. Tag APIs share {@code /tags/{arn}} via {@link TagHandler}
 * using ARN service {@code oam}.
 *
 * <p>AWS allows one sink per account per region. Storage is account-scoped by
 * {@code StorageFactory}, so the region key is the singleton.
 */
@ApplicationScoped
public class OamService implements TagHandler {

    static final String SERVICE = "oam";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 1000;
    private static final String TOKEN_PREFIX = "oam:v1:";
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{1,255}");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final StorageBackend<String, OamSink> sinks;
    private final StorageBackend<String, OamLink> links;
    private final RegionResolver regionResolver;

    @Inject
    public OamService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(SERVICE, "oam-sinks.json", new TypeReference<Map<String, OamSink>>() {
                }),
                storageFactory.create(SERVICE, "oam-links.json", new TypeReference<Map<String, OamLink>>() {
                }),
                regionResolver);
    }

    OamService(
            StorageBackend<String, OamSink> sinks,
            StorageBackend<String, OamLink> links,
            RegionResolver regionResolver) {
        this.sinks = sinks;
        this.links = links;
        this.regionResolver = regionResolver;
    }

    public synchronized OamSink createSink(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name);
        if (sinks.get(region).isPresent()) {
            throw conflict("A sink already exists in this account and Region.");
        }
        Map<String, String> tags = readTags(request);
        String id = UUID.randomUUID().toString();
        OamSink sink = new OamSink();
        sink.setId(id);
        sink.setName(name);
        sink.setArn(arn(region, "sink/" + id));
        sink.setTags(tags);
        sinks.put(region, sink);
        return sink;
    }

    public OamSink getSink(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "Identifier");
        return requireSink(region, identifier);
    }

    public synchronized void deleteSink(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "Identifier");
        OamSink sink = requireSink(region, identifier);
        long attached = links.scan(key -> key.startsWith(region + "::")).stream()
                .filter(link -> sink.getArn().equals(link.getSinkArn()))
                .count();
        if (attached > 0) {
            throw conflict("Cannot delete a sink that still has attached links.");
        }
        sinks.delete(region);
    }

    public Page<OamSink> listSinks(String region, JsonNode request) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? null
                : request;
        int maxResults = parseMaxResults(body);
        String nextToken = textOrNull(body, "NextToken");
        List<OamSink> items = new ArrayList<>();
        sinks.get(region).ifPresent(items::add);
        items.sort(Comparator.comparing(OamSink::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public synchronized OamSink putSinkPolicy(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "SinkIdentifier");
        String policy = requireText(request, "Policy");
        OamSink sink = requireSink(region, identifier);
        sink.setPolicy(policy);
        sinks.put(region, sink);
        return sink;
    }

    public OamSink getSinkPolicy(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "SinkIdentifier");
        OamSink sink = requireSink(region, identifier);
        if (sink.getPolicy() == null || sink.getPolicy().isBlank()) {
            throw resourceNotFound("sink policy", identifier);
        }
        return sink;
    }

    public Page<OamLink> listAttachedLinks(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "SinkIdentifier");
        OamSink sink = requireSink(region, identifier);
        int maxResults = parseMaxResults(request);
        String nextToken = textOrNull(request, "NextToken");
        List<OamLink> items = links.scan(key -> key.startsWith(region + "::")).stream()
                .filter(link -> sink.getArn().equals(link.getSinkArn()))
                .sorted(Comparator.comparing(OamLink::getArn, Comparator.nullsLast(String::compareTo)))
                .toList();
        return page(items, maxResults, nextToken);
    }

    public synchronized OamLink createLink(String region, JsonNode request) {
        requireObject(request, "Request body");
        String labelTemplate = requireText(request, "LabelTemplate");
        List<String> resourceTypes = readStringList(request, "ResourceTypes", true);
        String sinkIdentifier = requireText(request, "SinkIdentifier");
        String sinkArn = canonicalizeSinkArn(sinkIdentifier);
        String account = regionResolver.getAccountId();
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(sinkArn);
            if (account.equals(parsed.accountId())) {
                throw invalidParameter("A link cannot be created to a sink in the same account.");
            }
        } catch (IllegalArgumentException e) {
            throw invalidParameter("SinkIdentifier must be a valid sink ARN.");
        }
        String id = UUID.randomUUID().toString();
        OamLink link = new OamLink();
        link.setId(id);
        link.setArn(arn(region, "link/" + id));
        link.setLabelTemplate(labelTemplate);
        link.setLabel(labelTemplate);
        link.setResourceTypes(resourceTypes);
        link.setSinkArn(sinkArn);
        link.setLinkConfiguration(optionalObject(request, "LinkConfiguration"));
        link.setTags(readTags(request));
        links.put(linkKey(region, id), link);
        return link;
    }

    public OamLink getLink(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "Identifier");
        return requireLink(region, identifier);
    }

    public synchronized void deleteLink(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "Identifier");
        OamLink link = requireLink(region, identifier);
        links.delete(linkKey(region, link.getId()));
    }

    public Page<OamLink> listLinks(String region, JsonNode request) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? null
                : request;
        int maxResults = parseMaxResults(body);
        String nextToken = textOrNull(body, "NextToken");
        List<OamLink> items = new ArrayList<>(links.scan(key -> key.startsWith(region + "::")));
        items.sort(Comparator.comparing(OamLink::getArn, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public synchronized OamLink updateLink(String region, JsonNode request) {
        requireObject(request, "Request body");
        String identifier = requireText(request, "Identifier");
        OamLink link = requireLink(region, identifier);
        if (request.has("ResourceTypes") && !request.get("ResourceTypes").isNull()) {
            link.setResourceTypes(readStringList(request, "ResourceTypes", true));
        }
        if (request.has("LinkConfiguration")) {
            link.setLinkConfiguration(optionalObject(request, "LinkConfiguration"));
        }
        links.put(linkKey(region, link.getId()), link);
        return link;
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
    public boolean tagResourceUsesPut() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        if (current.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags.", 400);
        }
        tagged.applyTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.applyTags(current);
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw resourceNotFound("resource", arn);
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
            throw resourceNotFound("resource", arn);
        }
        if (parsed.resource().startsWith("sink/")) {
            OamSink sink = requireSink(region, arn);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return sink.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    sink.setTags(tags);
                    sinks.put(region, sink);
                }
            };
        }
        if (parsed.resource().startsWith("link/")) {
            OamLink link = requireLink(region, arn);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return link.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    link.setTags(tags);
                    links.put(linkKey(region, link.getId()), link);
                }
            };
        }
        throw resourceNotFound("resource", arn);
    }

    private OamSink requireSink(String region, String identifier) {
        Optional<OamSink> found = sinks.get(region);
        if (found.isEmpty()) {
            throw resourceNotFound("sink", identifier);
        }
        OamSink sink = found.get();
        if (matchesSink(sink, identifier)) {
            return sink;
        }
        throw resourceNotFound("sink", identifier);
    }

    private OamLink requireLink(String region, String identifier) {
        String id = linkIdFromIdentifier(identifier);
        return links.get(linkKey(region, id)).orElseThrow(() -> resourceNotFound("link", identifier));
    }

    private static boolean matchesSink(OamSink sink, String identifier) {
        if (identifier.equals(sink.getArn()) || identifier.equals(sink.getId()) || identifier.equals(sink.getName())) {
            return true;
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(identifier);
            return SERVICE.equals(parsed.service())
                    && parsed.resource() != null
                    && parsed.resource().equals("sink/" + sink.getId());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String canonicalizeSinkArn(String identifier) {
        if (identifier.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn parsed = AwsArnUtils.parse(identifier);
                if (!SERVICE.equals(parsed.service())
                        || parsed.resource() == null
                        || !parsed.resource().startsWith("sink/")) {
                    throw invalidParameter("SinkIdentifier must be a sink ARN.");
                }
                return identifier;
            } catch (IllegalArgumentException e) {
                throw invalidParameter("SinkIdentifier must be a valid sink ARN.");
            }
        }
        throw invalidParameter("SinkIdentifier must be a sink ARN.");
    }

    private String linkIdFromIdentifier(String identifier) {
        if (identifier.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn parsed = AwsArnUtils.parse(identifier);
                if (!SERVICE.equals(parsed.service())
                        || parsed.resource() == null
                        || !parsed.resource().startsWith("link/")) {
                    throw resourceNotFound("link", identifier);
                }
                String id = parsed.resource().substring("link/".length());
                if (id.isBlank()) {
                    throw resourceNotFound("link", identifier);
                }
                return id;
            } catch (IllegalArgumentException e) {
                throw resourceNotFound("link", identifier);
            }
        }
        if (UUID_PATTERN.matcher(identifier).matches()) {
            return identifier;
        }
        throw resourceNotFound("link", identifier);
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static String linkKey(String region, String id) {
        return region + "::" + id;
    }

    private static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw invalidParameter("Name must match [a-zA-Z0-9_.-]{1,255}.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("Tags") || request.get("Tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("Tags");
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw invalidParameter("Tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (entry.getKey().isBlank() || value == null || !value.isTextual()) {
                throw invalidParameter("Tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode parent, String field, boolean required) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            if (required) {
                throw missing(field);
            }
            return List.of();
        }
        JsonNode array = parent.get(field);
        if (!array.isArray() || array.isEmpty()) {
            throw invalidParameter(field + " must be a non-empty array of strings.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw invalidParameter(field + " members must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static JsonNode optionalObject(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value.deepCopy();
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalidParameter(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw missing(field);
        }
        String text = value.textValue();
        if (text == null || text.isBlank()) {
            throw missing(field);
        }
        return text;
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }

    private static int parseMaxResults(JsonNode request) {
        if (request == null || !request.has("MaxResults") || request.get("MaxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = request.get("MaxResults");
        if (!value.isNumber() && !value.isTextual()) {
            throw invalidParameter("MaxResults must be an integer between 1 and 1000.");
        }
        int parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw invalidParameter("MaxResults must be between 1 and 1000.");
        }
        return parsed;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw invalidParameter("NextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw invalidParameter("NextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw invalidParameter("NextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException resourceNotFound(String type, String identifier) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource " + type + " " + identifier + " not found.",
                404);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException missing(String field) {
        return new AwsException(
                "MissingRequiredParameterException",
                "Missing required parameter " + field + ".",
                400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private interface Tagged {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }
}

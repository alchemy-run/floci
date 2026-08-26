package io.github.hectorvent.floci.services.resourceexplorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.resourceexplorer.model.ExplorerIndex;
import io.github.hectorvent.floci.services.resourceexplorer.model.ExplorerView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * AWS Resource Explorer 2 restJson1 — region-singleton index, views, search,
 * and ListSupportedResourceTypes. Tag APIs share {@code /tags/{arn}} via
 * {@link TagHandler} using ARN service {@code resource-explorer-2}.
 *
 * <p>Storage is account-scoped by {@code StorageFactory}, so the index key is
 * the region singleton.
 */
@ApplicationScoped
public class ResourceExplorerService implements TagHandler {

    static final String SERVICE = "resource-explorer-2";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 1000;
    private static final String TOKEN_PREFIX = "re2:v1:";
    private static final Pattern VIEW_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-]{1,64}");
    private static final List<SupportedType> SUPPORTED_TYPES = List.of(
            new SupportedType("ec2", "ec2:instance", List.of("AWS::EC2::Instance")),
            new SupportedType("ec2", "ec2:volume", List.of("AWS::EC2::Volume")),
            new SupportedType("ec2", "ec2:security-group", List.of("AWS::EC2::SecurityGroup")),
            new SupportedType("ec2", "ec2:vpc", List.of("AWS::EC2::VPC")),
            new SupportedType("s3", "s3:bucket", List.of("AWS::S3::Bucket")),
            new SupportedType("lambda", "lambda:function", List.of("AWS::Lambda::Function")),
            new SupportedType("dynamodb", "dynamodb:table", List.of("AWS::DynamoDB::Table")),
            new SupportedType("sqs", "sqs:queue", List.of("AWS::SQS::Queue")),
            new SupportedType("sns", "sns:topic", List.of("AWS::SNS::Topic")),
            new SupportedType("iam", "iam:role", List.of("AWS::IAM::Role")),
            new SupportedType("iam", "iam:user", List.of("AWS::IAM::User")),
            new SupportedType("rds", "rds:db", List.of("AWS::RDS::DBInstance")),
            new SupportedType("kms", "kms:key", List.of("AWS::KMS::Key")),
            new SupportedType("logs", "logs:log-group", List.of("AWS::Logs::LogGroup")),
            new SupportedType("cloudformation", "cloudformation:stack", List.of("AWS::CloudFormation::Stack")),
            new SupportedType("ecs", "ecs:cluster", List.of("AWS::ECS::Cluster")),
            new SupportedType("ecs", "ecs:service", List.of("AWS::ECS::Service")),
            new SupportedType("eks", "eks:cluster", List.of("AWS::EKS::Cluster")),
            new SupportedType("apigateway", "apigateway:restapi", List.of("AWS::ApiGateway::RestApi")),
            new SupportedType("secretsmanager", "secretsmanager:secret", List.of("AWS::SecretsManager::Secret")));

    private final StorageBackend<String, ExplorerIndex> indexes;
    private final StorageBackend<String, ExplorerView> views;
    private final RegionResolver regionResolver;

    @Inject
    public ResourceExplorerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(SERVICE, "resource-explorer-indexes.json",
                        new TypeReference<Map<String, ExplorerIndex>>() {
                        }),
                storageFactory.create(SERVICE, "resource-explorer-views.json",
                        new TypeReference<Map<String, ExplorerView>>() {
                        }),
                regionResolver);
    }

    ResourceExplorerService(
            StorageBackend<String, ExplorerIndex> indexes,
            StorageBackend<String, ExplorerView> views,
            RegionResolver regionResolver) {
        this.indexes = indexes;
        this.views = views;
        this.regionResolver = regionResolver;
    }

    public synchronized ExplorerIndex createIndex(String region, JsonNode request) {
        JsonNode body = request != null && request.isObject() ? request : null;
        if (indexes.get(region).isPresent()) {
            throw conflict("An index already exists in this Region.");
        }
        String now = now();
        String id = UUID.randomUUID().toString();
        ExplorerIndex index = new ExplorerIndex();
        index.setArn(arn(region, "index/" + id));
        index.setType("LOCAL");
        index.setState("ACTIVE");
        index.setCreatedAt(now);
        index.setLastUpdatedAt(now);
        index.setTags(readTags(body));
        indexes.put(region, index);
        return index;
    }

    public ExplorerIndex getIndex(String region) {
        return indexes.get(region).orElseThrow(ResourceExplorerService::indexNotFound);
    }

    public synchronized ExplorerIndex deleteIndex(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "Arn");
        ExplorerIndex index = requireIndexByArn(region, arn);
        String now = now();
        index.setState("DELETED");
        index.setLastUpdatedAt(now);
        indexes.delete(region);
        for (ExplorerView view : views.scan(key -> key.startsWith(region + "::"))) {
            views.delete(viewKey(region, view.getViewName()));
        }
        return index;
    }

    public synchronized ExplorerIndex updateIndexType(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "Arn");
        String type = requireText(request, "Type");
        if (!"LOCAL".equals(type) && !"AGGREGATOR".equals(type)) {
            throw validation("Type must be LOCAL or AGGREGATOR.");
        }
        ExplorerIndex index = requireIndexByArn(region, arn);
        index.setType(type);
        index.setState("ACTIVE");
        index.setLastUpdatedAt(now());
        indexes.put(region, index);
        return index;
    }

    public synchronized ExplorerView createView(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireActiveIndex(region);
        String name = requireText(request, "ViewName");
        validateViewName(name);
        if (views.get(viewKey(region, name)).isPresent()) {
            throw conflict("A view with that name already exists in this Region.");
        }
        String now = now();
        String id = UUID.randomUUID().toString();
        String account = regionResolver.getAccountId();
        ExplorerView view = new ExplorerView();
        view.setViewName(name);
        view.setViewArn(arn(region, "view/" + name + "/" + id));
        view.setOwner(account);
        view.setLastUpdatedAt(now);
        view.setScope(textOrNull(request, "Scope"));
        if (view.getScope() == null) {
            view.setScope("arn:aws:iam::" + account + ":root");
        }
        view.setFilterString(readFilterString(request));
        view.setIncludedProperties(readIncludedPropertyNames(request, false));
        view.setTags(readTags(request));
        views.put(viewKey(region, name), view);
        return view;
    }

    public ExplorerView getView(String region, JsonNode request) {
        requireObject(request, "Request body");
        String viewArn = requireText(request, "ViewArn");
        return requireViewByArn(region, viewArn);
    }

    public synchronized ExplorerView updateView(String region, JsonNode request) {
        requireObject(request, "Request body");
        String viewArn = requireText(request, "ViewArn");
        ExplorerView view = requireViewByArn(region, viewArn);
        // UpdateView replaces both Filters and IncludedProperties; omitted fields clear.
        view.setFilterString(readFilterString(request));
        view.setIncludedProperties(readIncludedPropertyNames(request, false));
        view.setLastUpdatedAt(now());
        views.put(viewKey(region, view.getViewName()), view);
        return view;
    }

    public synchronized ExplorerView deleteView(String region, JsonNode request) {
        requireObject(request, "Request body");
        String viewArn = requireText(request, "ViewArn");
        ExplorerView view = requireViewByArn(region, viewArn);
        views.delete(viewKey(region, view.getViewName()));
        indexes.get(region).ifPresent(index -> {
            if (viewArn.equals(index.getDefaultViewArn())) {
                index.setDefaultViewArn(null);
                indexes.put(region, index);
            }
        });
        return view;
    }

    public Page<String> listViews(String region, JsonNode request) {
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = textOrNull(body, "NextToken");
        List<String> items = views.scan(key -> key.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(ExplorerView::getViewArn, Comparator.nullsLast(String::compareTo)))
                .map(ExplorerView::getViewArn)
                .toList();
        return page(items, maxResults, nextToken);
    }

    public SearchResult search(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "QueryString");
        requireActiveIndex(region);
        ExplorerView view = resolveView(region, textOrNull(request, "ViewArn"));
        return new SearchResult(view.getViewArn(), List.of(), 0, true);
    }

    public SearchResult listResources(String region, JsonNode request) {
        JsonNode body = emptyIfNull(request);
        requireObject(body, "Request body");
        requireActiveIndex(region);
        ExplorerView view = resolveView(region, textOrNull(body, "ViewArn"));
        return new SearchResult(view.getViewArn(), List.of(), 0, true);
    }

    public Page<SupportedType> listSupportedResourceTypes(JsonNode request) {
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = textOrNull(body, "NextToken");
        return page(SUPPORTED_TYPES, maxResults, nextToken);
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
            throw validation("A resource can have at most 50 tags.");
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
        if (parsed.resource().startsWith("index/")) {
            ExplorerIndex index = requireIndexByArn(region, arn);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return index.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    index.setTags(tags);
                    index.setLastUpdatedAt(now());
                    indexes.put(region, index);
                }
            };
        }
        if (parsed.resource().startsWith("view/")) {
            ExplorerView view = requireViewByArn(region, arn);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return view.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    view.setTags(tags);
                    view.setLastUpdatedAt(now());
                    views.put(viewKey(region, view.getViewName()), view);
                }
            };
        }
        throw resourceNotFound("resource", arn);
    }

    private ExplorerIndex requireIndexByArn(String region, String arn) {
        ExplorerIndex index = indexes.get(region).orElseThrow(ResourceExplorerService::indexNotFound);
        if (!arn.equals(index.getArn())) {
            throw indexNotFound();
        }
        return index;
    }

    private ExplorerIndex requireActiveIndex(String region) {
        ExplorerIndex index = indexes.get(region).orElseThrow(ResourceExplorerService::unauthorizedView);
        if (!"ACTIVE".equals(index.getState()) && !"UPDATING".equals(index.getState())) {
            throw unauthorizedView();
        }
        return index;
    }

    private ExplorerView requireViewByArn(String region, String viewArn) {
        String name = viewNameFromArn(viewArn);
        return views.get(viewKey(region, name)).filter(view -> viewArn.equals(view.getViewArn()))
                .orElseThrow(ResourceExplorerService::unauthorizedView);
    }

    private ExplorerView resolveView(String region, String viewArn) {
        if (viewArn != null) {
            return requireViewByArn(region, viewArn);
        }
        ExplorerIndex index = requireActiveIndex(region);
        if (index.getDefaultViewArn() == null) {
            throw unauthorizedView();
        }
        return requireViewByArn(region, index.getDefaultViewArn());
    }

    private String viewNameFromArn(String viewArn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(viewArn);
        } catch (IllegalArgumentException e) {
            throw unauthorizedView();
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith("view/")) {
            throw unauthorizedView();
        }
        String rest = parsed.resource().substring("view/".length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw unauthorizedView();
        }
        return rest.substring(0, slash);
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static String viewKey(String region, String name) {
        return region + "::" + name;
    }

    private static void validateViewName(String name) {
        if (name == null || !VIEW_NAME_PATTERN.matcher(name).matches()) {
            throw validation("ViewName must match [a-zA-Z0-9-]{1,64}.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("Tags") || request.get("Tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("Tags");
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("Tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (entry.getKey().isBlank() || value == null || !value.isTextual()) {
                throw validation("Tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static String readFilterString(JsonNode request) {
        if (request == null || !request.has("Filters") || request.get("Filters").isNull()) {
            return null;
        }
        JsonNode filters = request.get("Filters");
        if (!filters.isObject()) {
            throw validation("Filters must be an object.");
        }
        return textOrNull(filters, "FilterString");
    }

    private static List<String> readIncludedPropertyNames(JsonNode request, boolean required) {
        if (request == null || !request.has("IncludedProperties") || request.get("IncludedProperties").isNull()) {
            if (required) {
                throw missing("IncludedProperties");
            }
            return List.of();
        }
        JsonNode array = request.get("IncludedProperties");
        if (!array.isArray()) {
            throw validation("IncludedProperties must be an array.");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : array) {
            if (item == null || !item.isObject()) {
                throw validation("IncludedProperties members must be objects with Name.");
            }
            names.add(requireText(item, "Name"));
        }
        return names;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static JsonNode emptyIfNull(JsonNode request) {
        return request == null || request.isNull() || request.isMissingNode() ? null : request;
    }

    private static String requireText(JsonNode parent, String field) {
        if (parent == null) {
            throw missing(field);
        }
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
            throw validation("MaxResults must be an integer between 1 and 1000.");
        }
        int parsed;
        try {
            parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            throw validation("MaxResults must be an integer between 1 and 1000.");
        }
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw validation("MaxResults must be between 1 and 1000.");
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
                throw validation("NextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("NextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("NextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException indexNotFound() {
        return new AwsException("ResourceNotFoundException", "The specified index was not found.", 404);
    }

    private static AwsException resourceNotFound(String type, String identifier) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource " + type + " " + identifier + " not found.",
                404);
    }

    private static AwsException unauthorizedView() {
        return new AwsException("UnauthorizedException", "", 401);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException missing(String field) {
        return new AwsException("ValidationException", "Missing required parameter " + field + ".", 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record SearchResult(String viewArn, List<Map<String, String>> resources, int totalResources,
            boolean complete) {
        public SearchResult {
            resources = List.copyOf(resources);
        }
    }

    public record SupportedType(String service, String resourceType, List<String> cfnResourceTypes) {
        public SupportedType {
            cfnResourceTypes = List.copyOf(cfnResourceTypes);
        }
    }

    private interface Tagged {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }
}

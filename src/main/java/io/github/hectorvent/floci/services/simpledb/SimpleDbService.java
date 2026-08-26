package io.github.hectorvent.floci.services.simpledb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.simpledb.model.SimpleDbDomain;
import io.github.hectorvent.floci.services.simpledb.model.SimpleDbItem;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SimpleDbService implements Resettable {

    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-zA-Z0-9._-]{3,255}");
    private static final Pattern SELECT = Pattern.compile(
            "(?is)^\\s*select\\s+(.+?)\\s+from\\s+(`([^`]+)`|'([^']+)'|\"([^\"]+)\"|([^\\s]+))"
                    + "(?:\\s+where\\s+(.+?))?"
                    + "(?:\\s+order\\s+by\\s+(`([^`]+)`|\\S+)(?:\\s+(asc|desc))?)?"
                    + "(?:\\s+limit\\s+(\\d+))?\\s*$");
    private static final Pattern PREDICATE = Pattern.compile(
            "(?is)^\\s*(itemName\\(\\)|`([^`]+)`|[\\w.:-]+)\\s*"
                    + "(=|!=|like|not\\s+like)\\s*"
                    + "(?:'((?:\\\\'|[^'])*)'|\"((?:\\\\\"|[^\"]*)*)\")\\s*$");

    private final StorageFactory storageFactory;
    private Map<String, SimpleDbDomain> domains = new ConcurrentHashMap<>();

    @Inject
    SimpleDbService(StorageFactory storageFactory) {
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return;
        }
        this.domains = new StorageBackedMap<>(storageFactory.create("simpledb",
                "simpledb-domains.json",
                new TypeReference<Map<String, SimpleDbDomain>>() {}));
    }

    @Override
    public void clear() {
        domains.clear();
    }

    public synchronized SimpleDbDomain createDomain(String region, String domainName) {
        requireName(domainName, "DomainName");
        validateDomainName(domainName);
        String key = key(region, domainName);
        SimpleDbDomain existing = domains.get(key);
        if (existing != null) {
            return existing;
        }
        SimpleDbDomain domain = new SimpleDbDomain();
        domain.setDomainName(domainName);
        domain.setCreatedAtEpochSeconds(System.currentTimeMillis() / 1000);
        domains.put(key, domain);
        return domain;
    }

    public synchronized void deleteDomain(String region, String domainName) {
        requireName(domainName, "DomainName");
        domains.remove(key(region, domainName));
    }

    public List<String> listDomains(String region, Integer maxNumberOfDomains, String nextToken) {
        List<String> names = domains.entrySet().stream()
                .filter(e -> e.getKey().startsWith(regionPrefix(region)))
                .map(e -> e.getValue().getDomainName())
                .sorted()
                .toList();
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidNextToken", "The specified next token is not valid.", 400);
            }
        }
        if (start < 0) {
            start = 0;
        }
        int max = maxNumberOfDomains == null ? 100 : Math.max(1, Math.min(maxNumberOfDomains, 100));
        int end = Math.min(names.size(), start + max);
        if (start >= names.size()) {
            return List.of();
        }
        return names.subList(start, end);
    }

    public DomainMetadata domainMetadata(String region, String domainName) {
        SimpleDbDomain domain = requireDomain(region, domainName);
        long itemNamesSize = 0;
        long attributeNamesSize = 0;
        long attributeValuesSize = 0;
        long attributeValueCount = 0;
        var attributeNames = new java.util.HashSet<String>();
        for (SimpleDbItem item : domain.getItems().values()) {
            String name = item.getName();
            if (name != null) {
                itemNamesSize += name.length();
            }
            for (var entry : item.getAttributes().entrySet()) {
                attributeNames.add(entry.getKey());
                attributeNamesSize += entry.getKey().length();
                for (String value : entry.getValue()) {
                    attributeValueCount++;
                    if (value != null) {
                        attributeValuesSize += value.length();
                    }
                }
            }
        }
        return new DomainMetadata(
                domain.getItems().size(),
                itemNamesSize,
                attributeNames.size(),
                attributeNamesSize,
                attributeValueCount,
                attributeValuesSize,
                domain.getCreatedAtEpochSeconds());
    }

    public synchronized void putAttributes(String region, String domainName, String itemName,
                                           List<AttributeUpdate> attributes) {
        requireName(domainName, "DomainName");
        requireName(itemName, "ItemName");
        if (attributes == null || attributes.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter Attribute.", 400);
        }
        SimpleDbDomain domain = requireDomain(region, domainName);
        SimpleDbItem item = domain.getItems().computeIfAbsent(itemName, name -> {
            SimpleDbItem created = new SimpleDbItem();
            created.setName(name);
            return created;
        });
        applyUpdates(item, attributes);
        domains.put(key(region, domainName), domain);
    }

    public List<AttributePair> getAttributes(String region, String domainName, String itemName,
                                             List<String> attributeNames) {
        requireName(domainName, "DomainName");
        requireName(itemName, "ItemName");
        SimpleDbDomain domain = requireDomain(region, domainName);
        SimpleDbItem item = domain.getItems().get(itemName);
        if (item == null) {
            return List.of();
        }
        List<AttributePair> result = new ArrayList<>();
        for (var entry : item.getAttributes().entrySet()) {
            if (attributeNames != null && !attributeNames.isEmpty() && !attributeNames.contains(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                result.add(new AttributePair(entry.getKey(), value));
            }
        }
        return result;
    }

    public synchronized void deleteAttributes(String region, String domainName, String itemName,
                                              List<AttributeUpdate> attributes) {
        requireName(domainName, "DomainName");
        requireName(itemName, "ItemName");
        SimpleDbDomain domain = requireDomain(region, domainName);
        SimpleDbItem item = domain.getItems().get(itemName);
        if (item == null) {
            return;
        }
        if (attributes == null || attributes.isEmpty()) {
            domain.getItems().remove(itemName);
        } else {
            for (AttributeUpdate update : attributes) {
                if (update.name() == null || update.name().isBlank()) {
                    continue;
                }
                if (update.value() == null) {
                    item.getAttributes().remove(update.name());
                } else {
                    List<String> values = item.getAttributes().get(update.name());
                    if (values != null) {
                        values.removeIf(v -> update.value().equals(v));
                        if (values.isEmpty()) {
                            item.getAttributes().remove(update.name());
                        }
                    }
                }
            }
            if (item.isEmpty()) {
                domain.getItems().remove(itemName);
            }
        }
        domains.put(key(region, domainName), domain);
    }

    public synchronized void batchPutAttributes(String region, String domainName, List<ItemUpdate> items) {
        requireName(domainName, "DomainName");
        if (items == null || items.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter Item.", 400);
        }
        if (items.size() > 25) {
            throw new AwsException("NumberSubmittedItemsExceeded",
                    "Too many items in a single call. A BatchPutAttributes call can include 25 items at most.", 409);
        }
        var seen = new java.util.HashSet<String>();
        for (ItemUpdate item : items) {
            if (!seen.add(item.itemName())) {
                throw new AwsException("DuplicateItemName",
                        "The item name " + item.itemName() + " was specified more than once.", 400);
            }
        }
        SimpleDbDomain domain = requireDomain(region, domainName);
        for (ItemUpdate update : items) {
            requireName(update.itemName(), "Item.ItemName");
            SimpleDbItem item = domain.getItems().computeIfAbsent(update.itemName(), name -> {
                SimpleDbItem created = new SimpleDbItem();
                created.setName(name);
                return created;
            });
            applyUpdates(item, update.attributes());
        }
        domains.put(key(region, domainName), domain);
    }

    public synchronized void batchDeleteAttributes(String region, String domainName, List<ItemUpdate> items) {
        requireName(domainName, "DomainName");
        if (items == null || items.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter Item.", 400);
        }
        SimpleDbDomain domain = requireDomain(region, domainName);
        for (ItemUpdate update : items) {
            if (update.itemName() == null || update.itemName().isBlank()) {
                continue;
            }
            SimpleDbItem item = domain.getItems().get(update.itemName());
            if (item == null) {
                continue;
            }
            if (update.attributes() == null || update.attributes().isEmpty()) {
                domain.getItems().remove(update.itemName());
            } else {
                for (AttributeUpdate attr : update.attributes()) {
                    if (attr.name() == null) {
                        continue;
                    }
                    if (attr.value() == null) {
                        item.getAttributes().remove(attr.name());
                    } else {
                        List<String> values = item.getAttributes().get(attr.name());
                        if (values != null) {
                            values.removeIf(v -> attr.value().equals(v));
                            if (values.isEmpty()) {
                                item.getAttributes().remove(attr.name());
                            }
                        }
                    }
                }
                if (item.isEmpty()) {
                    domain.getItems().remove(update.itemName());
                }
            }
        }
        domains.put(key(region, domainName), domain);
    }

    public SelectResult select(String region, String expression) {
        if (expression == null || expression.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter SelectExpression.", 400);
        }
        Matcher matcher = SELECT.matcher(expression);
        if (!matcher.matches()) {
            throw new AwsException("InvalidQueryExpression",
                    "The specified query expression syntax is not valid.", 400);
        }
        String output = matcher.group(1).trim();
        String domainName = firstNonNull(matcher.group(3), matcher.group(4), matcher.group(5), matcher.group(6));
        String where = matcher.group(7);
        String orderAttr = firstNonNull(matcher.group(9), matcher.group(8));
        if (orderAttr != null && orderAttr.startsWith("`") && orderAttr.endsWith("`") && orderAttr.length() >= 2) {
            orderAttr = orderAttr.substring(1, orderAttr.length() - 1);
        }
        String orderDir = matcher.group(10);
        Integer limit = matcher.group(11) == null ? null : Integer.parseInt(matcher.group(11));

        SimpleDbDomain domain = requireDomain(region, domainName);
        List<SimpleDbItem> matched = new ArrayList<>();
        for (SimpleDbItem item : domain.getItems().values()) {
            if (matchesWhere(item, where)) {
                matched.add(item);
            }
        }
        if (orderAttr != null && !orderAttr.isBlank()) {
            String attr = orderAttr;
            boolean desc = orderDir != null && orderDir.equalsIgnoreCase("desc");
            matched.sort((a, b) -> {
                String av = firstValue(a, attr);
                String bv = firstValue(b, attr);
                int cmp = av.compareTo(bv);
                return desc ? -cmp : cmp;
            });
        }
        if (limit != null && limit >= 0 && limit < matched.size()) {
            matched = matched.subList(0, limit);
        }

        List<String> projected = projectNames(output);
        List<SelectedItem> items = new ArrayList<>();
        for (SimpleDbItem item : matched) {
            List<AttributePair> attrs = new ArrayList<>();
            for (var entry : item.getAttributes().entrySet()) {
                if (projected != null && !projected.contains(entry.getKey())) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    attrs.add(new AttributePair(entry.getKey(), value));
                }
            }
            items.add(new SelectedItem(item.getName(), attrs));
        }
        return new SelectResult(items);
    }

    private void applyUpdates(SimpleDbItem item, List<AttributeUpdate> attributes) {
        for (AttributeUpdate update : attributes) {
            requireName(update.name(), "Attribute.Name");
            if (update.value() == null) {
                throw new AwsException("MissingParameter", "The request must contain the parameter Attribute.Value.", 400);
            }
            if (update.replace()) {
                List<String> values = new ArrayList<>();
                values.add(update.value());
                item.getAttributes().put(update.name(), values);
            } else {
                List<String> values = item.getAttributes().computeIfAbsent(update.name(), k -> new ArrayList<>());
                if (!values.contains(update.value())) {
                    values.add(update.value());
                }
            }
        }
    }

    private boolean matchesWhere(SimpleDbItem item, String where) {
        if (where == null || where.isBlank()) {
            return true;
        }
        String trimmed = stripParens(where.trim());
        List<String> orParts = splitTopLevel(trimmed, " or ");
        if (orParts.size() > 1) {
            for (String part : orParts) {
                if (matchesWhere(item, part)) {
                    return true;
                }
            }
            return false;
        }
        List<String> andParts = splitTopLevel(trimmed, " and ");
        if (andParts.size() > 1) {
            for (String part : andParts) {
                if (!matchesWhere(item, part)) {
                    return false;
                }
            }
            return true;
        }
        return matchesPredicate(item, trimmed);
    }

    private boolean matchesPredicate(SimpleDbItem item, String predicate) {
        Matcher matcher = PREDICATE.matcher(predicate);
        if (!matcher.matches()) {
            throw new AwsException("InvalidQueryExpression",
                    "The specified query expression syntax is not valid.", 400);
        }
        String rawName = matcher.group(1);
        String quoted = matcher.group(2);
        String op = matcher.group(3).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String rawValue = matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
        String value = rawValue == null ? null : rawValue.replace("\\'", "'").replace("\\\"", "\"");
        boolean itemName = rawName != null && rawName.equalsIgnoreCase("itemName()");
        String attributeName = quoted != null ? quoted : rawName;
        List<String> candidates;
        if (itemName) {
            candidates = item.getName() == null ? List.of() : List.of(item.getName());
        } else {
            candidates = item.values(attributeName);
        }
        return switch (op) {
            case "=" -> candidates.contains(value);
            case "!=" -> !candidates.contains(value);
            case "like" -> candidates.stream().anyMatch(v -> like(v, value));
            case "not like" -> candidates.stream().noneMatch(v -> like(v, value));
            default -> throw new AwsException("InvalidQueryExpression",
                    "The specified query expression syntax is not valid.", 400);
        };
    }

    private static boolean like(String value, String pattern) {
        if (value == null || pattern == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return value.matches(regex.toString());
    }

    private static List<String> projectNames(String output) {
        if (output == null || output.isBlank() || "*".equals(output.trim())) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String part : output.split(",")) {
            String name = part.trim();
            if (name.startsWith("`") && name.endsWith("`") && name.length() >= 2) {
                name = name.substring(1, name.length() - 1);
            }
            if (!name.isEmpty() && !"itemName()".equalsIgnoreCase(name) && !"*".equals(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : names;
    }

    private static String firstValue(SimpleDbItem item, String attribute) {
        List<String> values = item.values(attribute);
        return values.isEmpty() || values.getFirst() == null ? "" : values.getFirst();
    }

    private static String stripParens(String value) {
        String current = value;
        while (current.startsWith("(") && current.endsWith(")") && balanced(current.substring(1, current.length() - 1))) {
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static boolean balanced(String value) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth < 0) {
                        return false;
                    }
                }
            }
        }
        return depth == 0 && !inSingle && !inDouble;
    }

    private static List<String> splitTopLevel(String value, String delimiter) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        String lower = value.toLowerCase(Locale.ROOT);
        String delim = delimiter.toLowerCase(Locale.ROOT);
        int start = 0;
        for (int i = 0; i <= value.length() - delim.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && lower.startsWith(delim, i)) {
                    parts.add(value.substring(start, i).trim());
                    i += delim.length() - 1;
                    start = i + 1;
                }
            }
        }
        parts.add(value.substring(start).trim());
        return parts.stream().filter(s -> !s.isEmpty()).toList();
    }

    private SimpleDbDomain requireDomain(String region, String domainName) {
        requireName(domainName, "DomainName");
        SimpleDbDomain domain = domains.get(key(region, domainName));
        if (domain == null) {
            throw new AwsException("NoSuchDomain", "The specified domain does not exist.", 400);
        }
        return domain;
    }

    private static void requireName(String value, String parameter) {
        if (value == null || value.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter " + parameter + ".", 400);
        }
    }

    private static void validateDomainName(String domainName) {
        if (!DOMAIN_NAME.matcher(domainName).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + domainName + ") for parameter DomainName is invalid.", 400);
        }
    }

    private static String key(String region, String domainName) {
        return regionPrefix(region) + domainName;
    }

    private static String regionPrefix(String region) {
        return (region == null || region.isBlank() ? "us-east-1" : region) + "::";
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record AttributeUpdate(String name, String value, boolean replace) {}

    public record AttributePair(String name, String value) {}

    public record ItemUpdate(String itemName, List<AttributeUpdate> attributes) {}

    public record DomainMetadata(
            long itemCount,
            long itemNamesSizeBytes,
            long attributeNameCount,
            long attributeNamesSizeBytes,
            long attributeValueCount,
            long attributeValuesSizeBytes,
            long timestamp
    ) {}

    public record SelectedItem(String name, List<AttributePair> attributes) {}

    public record SelectResult(List<SelectedItem> items) {}
}

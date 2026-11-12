package io.floci.aws.services.sdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Amazon SimpleDB: domains of items whose attributes hold sets of string values, conditional puts and deletes,
 * metadata counted from the items, and select queries (see {@link SimpleDbSelect}). Every read is consistent.
 */
@ApplicationScoped
public class SimpleDbService implements Resettable {

    static final int MAX_DOMAINS = 250;
    static final int MAX_ITEM_PAIRS = 256;
    static final int MAX_BATCH = 25;

    /** One attribute change: a name, a value, and whether it replaces the attribute's other values. */
    record Change(String name, String value, boolean replace) {
    }

    /** A condition on an attribute: that it doesn't exist, or that it has exactly this one value. */
    record Expected(String name, String value, Boolean exists) {
    }

    private final AccountAwareStorageBackend<ObjectNode> domains;
    private final ObjectMapper mapper;

    @Inject
    public SimpleDbService(StorageFactory storageFactory, ObjectMapper mapper) {
        this.domains = storageFactory.create("sdb", "domains.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
    }

    @Override
    public void clear() {
        domains.clear();
    }

    private static String key(String region, String domain) {
        return region + "/" + domain;
    }

    static void requireDomainName(String name) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter DomainName.", 400);
        }
        if (!name.matches("[a-zA-Z0-9_.\\-]{3,255}")) {
            throw new AwsException("InvalidParameterValue", "Value (" + name + ") for parameter DomainName is "
                    + "invalid.", 400);
        }
    }

    public synchronized void createDomain(String region, String name) {
        requireDomainName(name);
        if (domains.get(key(region, name)).isPresent()) {
            return;
        }
        if (domains.scan(k -> k.startsWith(region + "/")).size() >= MAX_DOMAINS) {
            throw new AwsException("NumberDomainsExceeded", "The domain limit was exceeded.", 409);
        }
        ObjectNode domain = mapper.createObjectNode().put("name", name).put("created", Instant.now().getEpochSecond());
        domain.putObject("items");
        domains.put(key(region, name), domain);
    }

    public synchronized void deleteDomain(String region, String name) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter DomainName.", 400);
        }
        domains.delete(key(region, name));
    }

    public List<String> listDomains(String region) {
        List<String> names = new ArrayList<>();
        domains.scan(k -> k.startsWith(region + "/")).forEach(d -> names.add(d.path("name").asText()));
        names.sort(String::compareTo);
        return names;
    }

    private ObjectNode domain(String region, String name) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter DomainName.", 400);
        }
        return domains.get(key(region, name)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("NoSuchDomain", "The specified domain does not exist.", 400));
    }

    /** An item's attributes, in name order, each value set in insertion order. */
    private static Map<String, Set<String>> attributes(JsonNode item) {
        Map<String, Set<String>> out = new TreeMap<>();
        if (item != null) {
            item.fields().forEachRemaining(entry -> {
                Set<String> values = new LinkedHashSet<>();
                entry.getValue().forEach(v -> values.add(v.asText()));
                out.put(entry.getKey(), values);
            });
        }
        return out;
    }

    private ObjectNode toNode(Map<String, Set<String>> attributes) {
        ObjectNode node = mapper.createObjectNode();
        attributes.forEach((name, values) -> {
            ArrayNode array = node.putArray(name);
            values.forEach(array::add);
        });
        return node;
    }

    private static int pairs(Map<String, Set<String>> attributes) {
        return attributes.values().stream().mapToInt(Set::size).sum();
    }

    private static void check(Map<String, Set<String>> attributes, List<Expected> expected) {
        for (Expected condition : expected) {
            Set<String> values = attributes.get(condition.name());
            boolean exists = values != null && !values.isEmpty();
            if (Boolean.FALSE.equals(condition.exists())) {
                if (exists) {
                    throw new AwsException("ConditionalCheckFailed", "Conditional check failed. Attribute ("
                            + condition.name() + ") value exists", 409);
                }
                continue;
            }
            if (!exists) {
                throw new AwsException("AttributeDoesNotExist", "Attribute (" + condition.name()
                        + ") does not exist", 404);
            }
            if (values.size() > 1) {
                throw new AwsException("MultiValuedAttribute", "Attribute (" + condition.name()
                        + ") is multi-valued. Conditional check can only be performed on a single-valued attribute",
                        409);
            }
            if (condition.value() != null && !values.contains(condition.value())) {
                throw new AwsException("ConditionalCheckFailed", "Conditional check failed. Attribute ("
                        + condition.name() + ") value is (" + values.iterator().next() + ") but was expected ("
                        + condition.value() + ")", 409);
            }
        }
    }

    private static void requireItemName(String item) {
        if (item == null || item.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter ItemName.", 400);
        }
        if (item.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new AwsException("InvalidParameterValue", "Value for parameter ItemName is too long.", 400);
        }
    }

    private static void requireChanges(List<Change> changes) {
        if (changes.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter Attribute.Name.", 400);
        }
        for (Change change : changes) {
            if (change.name() == null || change.name().isEmpty() || change.value() == null) {
                throw new AwsException("MissingParameter", "Each attribute needs a Name and a Value.", 400);
            }
            if (change.name().getBytes(StandardCharsets.UTF_8).length > 1024
                    || change.value().getBytes(StandardCharsets.UTF_8).length > 1024) {
                throw new AwsException("InvalidParameterValue", "Attribute names and values are at most 1024 "
                        + "bytes.", 400);
            }
        }
    }

    private static void apply(Map<String, Set<String>> attributes, List<Change> changes) {
        Set<String> replaced = new LinkedHashSet<>();
        for (Change change : changes) {
            if (change.replace() && replaced.add(change.name())) {
                attributes.remove(change.name());
            }
        }
        for (Change change : changes) {
            attributes.computeIfAbsent(change.name(), k -> new LinkedHashSet<>()).add(change.value());
        }
    }

    public synchronized void putAttributes(String region, String domainName, String itemName, List<Change> changes,
                                           List<Expected> expected) {
        requireItemName(itemName);
        requireChanges(changes);
        ObjectNode domain = domain(region, domainName);
        ObjectNode items = (ObjectNode) domain.get("items");
        Map<String, Set<String>> attributes = attributes(items.get(itemName));
        check(attributes, expected);
        apply(attributes, changes);
        if (pairs(attributes) > MAX_ITEM_PAIRS) {
            throw new AwsException("NumberItemAttributesExceeded", "Too many attributes in this item.", 409);
        }
        items.set(itemName, toNode(attributes));
        domains.put(key(region, domainName), domain);
    }

    public synchronized void batchPut(String region, String domainName, Map<String, List<Change>> batch) {
        if (batch.size() > MAX_BATCH) {
            throw new AwsException("NumberSubmittedItemsExceeded", "Too many items in a single call. Up to 25 items "
                    + "per call allowed.", 409);
        }
        ObjectNode domain = domain(region, domainName);
        ObjectNode items = (ObjectNode) domain.get("items");
        for (Map.Entry<String, List<Change>> entry : batch.entrySet()) {
            requireItemName(entry.getKey());
            requireChanges(entry.getValue());
            Map<String, Set<String>> attributes = attributes(items.get(entry.getKey()));
            apply(attributes, entry.getValue());
            if (pairs(attributes) > MAX_ITEM_PAIRS) {
                throw new AwsException("NumberItemAttributesExceeded", "Too many attributes in item "
                        + entry.getKey() + ".", 409);
            }
            items.set(entry.getKey(), toNode(attributes));
        }
        domains.put(key(region, domainName), domain);
    }

    /** Removes the named values, the named attributes, or with nothing named, the whole item. */
    public synchronized void deleteAttributes(String region, String domainName, String itemName,
                                              List<Change> targets, List<Expected> expected) {
        requireItemName(itemName);
        ObjectNode domain = domain(region, domainName);
        ObjectNode items = (ObjectNode) domain.get("items");
        Map<String, Set<String>> attributes = attributes(items.get(itemName));
        check(attributes, expected);
        remove(attributes, targets);
        if (attributes.isEmpty()) {
            items.remove(itemName);
        } else {
            items.set(itemName, toNode(attributes));
        }
        domains.put(key(region, domainName), domain);
    }

    private static void remove(Map<String, Set<String>> attributes, List<Change> targets) {
        if (targets.isEmpty()) {
            attributes.clear();
            return;
        }
        for (Change target : targets) {
            if (target.value() == null) {
                attributes.remove(target.name());
            } else {
                Set<String> values = attributes.get(target.name());
                if (values != null) {
                    values.remove(target.value());
                    if (values.isEmpty()) {
                        attributes.remove(target.name());
                    }
                }
            }
        }
    }

    public synchronized void batchDelete(String region, String domainName, Map<String, List<Change>> batch) {
        if (batch.size() > MAX_BATCH) {
            throw new AwsException("NumberSubmittedItemsExceeded", "Too many items in a single call. Up to 25 items "
                    + "per call allowed.", 409);
        }
        ObjectNode domain = domain(region, domainName);
        ObjectNode items = (ObjectNode) domain.get("items");
        for (Map.Entry<String, List<Change>> entry : batch.entrySet()) {
            Map<String, Set<String>> attributes = attributes(items.get(entry.getKey()));
            remove(attributes, entry.getValue());
            if (attributes.isEmpty()) {
                items.remove(entry.getKey());
            } else {
                items.set(entry.getKey(), toNode(attributes));
            }
        }
        domains.put(key(region, domainName), domain);
    }

    public Map<String, Set<String>> getAttributes(String region, String domainName, String itemName,
                                                  List<String> names) {
        requireItemName(itemName);
        Map<String, Set<String>> attributes = attributes(domain(region, domainName).path("items").get(itemName));
        if (!names.isEmpty()) {
            attributes.keySet().retainAll(names);
        }
        return attributes;
    }

    /** Counts and sizes of what the domain holds, computed from its items. */
    public Map<String, Long> metadata(String region, String domainName) {
        ObjectNode domain = domain(region, domainName);
        long itemCount = 0;
        long itemNamesSize = 0;
        long valueCount = 0;
        long valuesSize = 0;
        Set<String> names = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = domain.path("items").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> item = fields.next();
            itemCount++;
            itemNamesSize += item.getKey().getBytes(StandardCharsets.UTF_8).length;
            for (Map.Entry<String, Set<String>> attribute : attributes(item.getValue()).entrySet()) {
                names.add(attribute.getKey());
                for (String value : attribute.getValue()) {
                    valueCount++;
                    valuesSize += value.getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        long namesSize = names.stream().mapToLong(n -> n.getBytes(StandardCharsets.UTF_8).length).sum();
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("ItemCount", itemCount);
        out.put("ItemNamesSizeBytes", itemNamesSize);
        out.put("AttributeNameCount", (long) names.size());
        out.put("AttributeNamesSizeBytes", namesSize);
        out.put("AttributeValueCount", valueCount);
        out.put("AttributeValuesSizeBytes", valuesSize);
        out.put("Timestamp", Instant.now().getEpochSecond());
        return out;
    }

    /** The result of a select: the matching items, projected, and where the next page starts. */
    record SelectResult(List<SimpleDbSelect.Item> items, int next) {
    }

    public SelectResult select(String region, String expression, int start) {
        if (expression == null || expression.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter SelectExpression.",
                    400);
        }
        SimpleDbSelect.Query query = SimpleDbSelect.parse(expression);
        ObjectNode domain = domain(region, query.domain());
        List<SimpleDbSelect.Item> matched = new ArrayList<>();
        domain.path("items").fields().forEachRemaining(entry -> {
            SimpleDbSelect.Item item = new SimpleDbSelect.Item(entry.getKey(), attributes(entry.getValue()));
            if (query.where().test(item)) {
                matched.add(item);
            }
        });
        if (query.count()) {
            Map<String, Set<String>> count = new TreeMap<>();
            count.put("Count", Set.of(String.valueOf(matched.size())));
            return new SelectResult(List.of(new SimpleDbSelect.Item("Domain", count)), -1);
        }
        Comparator<SimpleDbSelect.Item> order = Comparator.comparing(SimpleDbSelect.Item::name);
        if (query.sortAttribute() != null && !"itemName()".equals(query.sortAttribute())) {
            String sort = query.sortAttribute();
            order = Comparator.comparing((SimpleDbSelect.Item item) -> item.attributes().getOrDefault(sort, Set.of())
                    .stream().sorted().findFirst().orElse(""));
        }
        if (query.descending()) {
            order = order.reversed();
        }
        matched.sort(order);
        int from = Math.min(Math.max(0, start), matched.size());
        int to = Math.min(matched.size(), from + query.limit());
        List<SimpleDbSelect.Item> page = new ArrayList<>();
        for (SimpleDbSelect.Item item : matched.subList(from, to)) {
            Map<String, Set<String>> projected = new TreeMap<>(item.attributes());
            if (query.itemNameOnly()) {
                projected.clear();
            } else if (!query.all()) {
                projected.keySet().retainAll(query.output());
            }
            page.add(new SimpleDbSelect.Item(item.name(), projected));
        }
        return new SelectResult(page, to < matched.size() ? to : -1);
    }
}

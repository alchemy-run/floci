package io.github.hectorvent.floci.services.directoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.directoryservice.model.Directory;
import io.github.hectorvent.floci.services.directoryservice.model.DirectoryEventTopic;
import io.github.hectorvent.floci.services.directoryservice.model.DirectoryForwarder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local AWS Directory Service stub. Directories become {@code Active} immediately
 * with synthetic DNS addresses so Alchemy's wait-for-active loop does not stall.
 */
@ApplicationScoped
public class DirectoryService implements Resettable {

    private static final Set<String> MICROSOFT_TYPES = Set.of("MicrosoftAD", "ADConnector", "SharedMicrosoftAD");

    private final StorageBackend<String, Directory> directories;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DirectoryService(StorageFactory factory, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.directories = factory.create("ds", "directoryservice-directories.json",
                new TypeReference<Map<String, Directory>>() {
                });
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    DirectoryService(StorageBackend<String, Directory> directories, ObjectMapper objectMapper,
                     RegionResolver regionResolver) {
        this.directories = directories;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        directories.clear();
    }

    public ObjectNode createDirectory(JsonNode request, String region) {
        return create(request, region, "SimpleAD",
                textOr(request, "Size", "Small"), null);
    }

    public ObjectNode createMicrosoftAD(JsonNode request, String region) {
        return create(request, region, "MicrosoftAD", null,
                textOr(request, "Edition", "Standard"));
    }

    public ObjectNode getDirectoryLimits() {
        int simple = 0;
        int microsoft = 0;
        int connected = 0;
        for (Directory directory : directories.values()) {
            String type = directory.getType();
            if ("MicrosoftAD".equals(type) || "SharedMicrosoftAD".equals(type)) {
                microsoft++;
            } else if ("ADConnector".equals(type)) {
                connected++;
            } else {
                simple++;
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode limits = response.putObject("DirectoryLimits");
        limits.put("CloudOnlyDirectoriesLimit", 10);
        limits.put("CloudOnlyDirectoriesCurrentCount", simple);
        limits.put("CloudOnlyDirectoriesLimitReached", simple >= 10);
        limits.put("CloudOnlyMicrosoftADLimit", 20);
        limits.put("CloudOnlyMicrosoftADCurrentCount", microsoft);
        limits.put("CloudOnlyMicrosoftADLimitReached", microsoft >= 20);
        limits.put("ConnectedDirectoriesLimit", 10);
        limits.put("ConnectedDirectoriesCurrentCount", connected);
        limits.put("ConnectedDirectoriesLimitReached", connected >= 10);
        return response;
    }

    public ObjectNode describeDirectories(JsonNode request, String region) {
        List<String> ids = stringList(request.path("DirectoryIds"));
        List<Directory> matches = new ArrayList<>();
        if (ids.isEmpty()) {
            for (Directory directory : directories.values()) {
                if (region == null || region.equals(directory.getRegion())) {
                    matches.add(directory);
                }
            }
        } else {
            for (String id : ids) {
                matches.add(requireDirectory(id));
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DirectoryDescriptions");
        for (Directory directory : matches) {
            list.add(toDirectoryNode(directory));
        }
        return response;
    }

    public ObjectNode deleteDirectory(JsonNode request) {
        Directory directory = requireDirectory(requireText(request, "DirectoryId"));
        directories.delete(directory.getDirectoryId());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DirectoryId", directory.getDirectoryId());
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Directory directory = requireDirectory(requireText(request, "ResourceId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsArray(directory.getTags()));
        return response;
    }

    public ObjectNode addTagsToResource(JsonNode request) {
        Directory directory = requireDirectory(requireText(request, "ResourceId"));
        applyTags(request.path("Tags"), directory.getTags());
        directories.put(directory.getDirectoryId(), directory);
        return objectMapper.createObjectNode();
    }

    public ObjectNode removeTagsFromResource(JsonNode request) {
        Directory directory = requireDirectory(requireText(request, "ResourceId"));
        for (String key : stringList(request.path("TagKeys"))) {
            directory.getTags().remove(key);
        }
        directories.put(directory.getDirectoryId(), directory);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeEventTopics(JsonNode request) {
        String directoryId = textOrNull(request, "DirectoryId");
        List<String> topicNames = stringList(request.path("TopicNames"));
        List<DirectoryEventTopic> topics = new ArrayList<>();
        if (directoryId != null) {
            Directory directory = requireDirectory(directoryId);
            collectTopics(directory, topicNames, topics);
        } else {
            for (Directory directory : directories.values()) {
                collectTopics(directory, topicNames, topics);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("EventTopics");
        for (DirectoryEventTopic topic : topics) {
            list.add(toEventTopicNode(topic));
        }
        return response;
    }

    public ObjectNode registerEventTopic(JsonNode request, String region) {
        Directory directory = requireDirectory(requireText(request, "DirectoryId"));
        String topicName = requireText(request, "TopicName");
        DirectoryEventTopic topic = directory.getEventTopics().get(topicName);
        if (topic == null) {
            topic = new DirectoryEventTopic();
            topic.setDirectoryId(directory.getDirectoryId());
            topic.setTopicName(topicName);
            topic.setTopicArn(regionResolver.buildArn("sns", region, topicName));
            topic.setCreatedDateTime(now());
            topic.setStatus("Registered");
            directory.getEventTopics().put(topicName, topic);
            directories.put(directory.getDirectoryId(), directory);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode deregisterEventTopic(JsonNode request) {
        Directory directory = requireDirectory(requireText(request, "DirectoryId"));
        String topicName = requireText(request, "TopicName");
        if (directory.getEventTopics().remove(topicName) == null) {
            throw notFound("Event topic " + topicName + " does not exist.");
        }
        directories.put(directory.getDirectoryId(), directory);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeConditionalForwarders(JsonNode request) {
        Directory directory = requireMicrosoft(requireText(request, "DirectoryId"));
        List<String> names = stringList(request.path("RemoteDomainNames"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ConditionalForwarders");
        if (names.isEmpty()) {
            for (DirectoryForwarder forwarder : directory.getForwarders().values()) {
                list.add(toForwarderNode(forwarder));
            }
        } else {
            for (String name : names) {
                DirectoryForwarder forwarder = directory.getForwarders().get(name);
                if (forwarder != null) {
                    list.add(toForwarderNode(forwarder));
                }
            }
        }
        return response;
    }

    public ObjectNode createConditionalForwarder(JsonNode request) {
        Directory directory = requireMicrosoft(requireText(request, "DirectoryId"));
        String remoteDomainName = requireText(request, "RemoteDomainName");
        if (directory.getForwarders().containsKey(remoteDomainName)) {
            throw new AwsException("EntityAlreadyExistsException",
                    "Conditional forwarder for " + remoteDomainName + " already exists.", 400);
        }
        List<String> dns = stringList(request.path("DnsIpAddrs"));
        if (dns.isEmpty()) {
            throw invalid("DnsIpAddrs is required.");
        }
        DirectoryForwarder forwarder = new DirectoryForwarder();
        forwarder.setRemoteDomainName(remoteDomainName);
        forwarder.setDnsIpAddrs(dns);
        forwarder.setReplicationScope("Domain");
        directory.getForwarders().put(remoteDomainName, forwarder);
        directories.put(directory.getDirectoryId(), directory);
        return objectMapper.createObjectNode();
    }

    public ObjectNode updateConditionalForwarder(JsonNode request) {
        Directory directory = requireMicrosoft(requireText(request, "DirectoryId"));
        String remoteDomainName = requireText(request, "RemoteDomainName");
        DirectoryForwarder forwarder = directory.getForwarders().get(remoteDomainName);
        if (forwarder == null) {
            throw notFound("Conditional forwarder for " + remoteDomainName + " does not exist.");
        }
        List<String> dns = stringList(request.path("DnsIpAddrs"));
        if (dns.isEmpty()) {
            throw invalid("DnsIpAddrs is required.");
        }
        forwarder.setDnsIpAddrs(dns);
        directories.put(directory.getDirectoryId(), directory);
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteConditionalForwarder(JsonNode request) {
        Directory directory = requireMicrosoft(requireText(request, "DirectoryId"));
        String remoteDomainName = requireText(request, "RemoteDomainName");
        if (directory.getForwarders().remove(remoteDomainName) == null) {
            throw notFound("Conditional forwarder for " + remoteDomainName + " does not exist.");
        }
        directories.put(directory.getDirectoryId(), directory);
        return objectMapper.createObjectNode();
    }

    private ObjectNode create(JsonNode request, String region, String type, String size, String edition) {
        String name = requireText(request, "Name");
        String password = requireText(request, "Password");
        JsonNode vpc = request.path("VpcSettings");
        String vpcId = textOrNull(vpc, "VpcId");
        List<String> subnetIds = stringList(vpc.path("SubnetIds"));
        if (vpcId == null || vpcId.isBlank()) {
            throw invalid("VpcSettings.VpcId is required.");
        }
        if (subnetIds.size() != 2) {
            throw invalid("VpcSettings.SubnetIds must contain exactly two subnets in different Availability Zones.");
        }
        String directoryId = "d-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String shortName = textOrNull(request, "ShortName");
        if (shortName == null) {
            int dot = name.indexOf('.');
            shortName = (dot > 0 ? name.substring(0, dot) : name).toUpperCase();
            if (shortName.length() > 15) {
                shortName = shortName.substring(0, 15);
            }
        }
        Directory directory = new Directory();
        directory.setDirectoryId(directoryId);
        directory.setRegion(region);
        directory.setName(name);
        directory.setShortName(shortName);
        directory.setPassword(password);
        directory.setDescription(textOrNull(request, "Description"));
        directory.setType(type);
        directory.setSize(size);
        directory.setEdition(edition);
        directory.setStage("Active");
        directory.setAlias(directoryId);
        directory.setAccessUrl(directoryId + ".awsapps.com");
        directory.setVpcId(vpcId);
        directory.setSubnetIds(subnetIds);
        directory.setAvailabilityZones(List.of(region + "a", region + "b"));
        directory.setSecurityGroupId("sg-" + directoryId.substring(2, 10));
        directory.setDnsIpAddrs(dnsAddrs(directoryId));
        directory.setLaunchTime(now());
        applyTags(request.path("Tags"), directory.getTags());
        directories.put(directoryId, directory);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("DirectoryId", directoryId);
        return response;
    }

    private Directory requireDirectory(String directoryId) {
        if (directoryId == null || directoryId.isBlank()) {
            throw invalid("DirectoryId is required.");
        }
        return directories.get(directoryId).orElseThrow(() ->
                notFound("Directory " + directoryId + " does not exist."));
    }

    private Directory requireMicrosoft(String directoryId) {
        Directory directory = requireDirectory(directoryId);
        if (!MICROSOFT_TYPES.contains(directory.getType())) {
            throw new AwsException("UnsupportedOperationException",
                    "Conditional forwarders are not supported for " + directory.getType() + " directories.",
                    400);
        }
        return directory;
    }

    private ObjectNode toDirectoryNode(Directory directory) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("DirectoryId", directory.getDirectoryId());
        node.put("Name", directory.getName());
        node.put("ShortName", directory.getShortName());
        node.put("Type", directory.getType());
        node.put("Stage", directory.getStage());
        if (directory.getSize() != null) {
            node.put("Size", directory.getSize());
        }
        if (directory.getEdition() != null) {
            node.put("Edition", directory.getEdition());
        }
        if (directory.getDescription() != null) {
            node.put("Description", directory.getDescription());
        }
        if (directory.getAlias() != null) {
            node.put("Alias", directory.getAlias());
        }
        if (directory.getAccessUrl() != null) {
            node.put("AccessUrl", directory.getAccessUrl());
        }
        node.put("LaunchTime", directory.getLaunchTime());
        node.put("StageLastUpdatedDateTime", directory.getLaunchTime());
        ArrayNode dns = node.putArray("DnsIpAddrs");
        directory.getDnsIpAddrs().forEach(dns::add);
        ObjectNode vpc = node.putObject("VpcSettings");
        vpc.put("VpcId", directory.getVpcId());
        vpc.put("SecurityGroupId", directory.getSecurityGroupId());
        ArrayNode subnets = vpc.putArray("SubnetIds");
        directory.getSubnetIds().forEach(subnets::add);
        ArrayNode azs = vpc.putArray("AvailabilityZones");
        directory.getAvailabilityZones().forEach(azs::add);
        return node;
    }

    private ObjectNode toEventTopicNode(DirectoryEventTopic topic) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("DirectoryId", topic.getDirectoryId());
        node.put("TopicName", topic.getTopicName());
        node.put("TopicArn", topic.getTopicArn());
        node.put("CreatedDateTime", topic.getCreatedDateTime());
        node.put("Status", topic.getStatus());
        return node;
    }

    private ObjectNode toForwarderNode(DirectoryForwarder forwarder) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RemoteDomainName", forwarder.getRemoteDomainName());
        node.put("ReplicationScope", forwarder.getReplicationScope());
        ArrayNode dns = node.putArray("DnsIpAddrs");
        forwarder.getDnsIpAddrs().forEach(dns::add);
        return node;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = array.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return array;
    }

    private static void collectTopics(Directory directory, List<String> topicNames,
                                      List<DirectoryEventTopic> out) {
        if (topicNames.isEmpty()) {
            out.addAll(directory.getEventTopics().values());
            return;
        }
        for (String name : topicNames) {
            DirectoryEventTopic topic = directory.getEventTopics().get(name);
            if (topic != null) {
                out.add(topic);
            }
        }
    }

    private static void applyTags(JsonNode node, Map<String, String> tags) {
        if (node == null || !node.isArray()) {
            return;
        }
        node.forEach(tag -> {
            String key = tag.path("Key").asText(null);
            if (key != null) {
                tags.put(key, tag.path("Value").asText(""));
            }
        });
    }

    private static List<String> dnsAddrs(String directoryId) {
        int octet = Math.floorMod(directoryId.hashCode(), 250) + 1;
        List<String> addrs = new ArrayList<>(2);
        addrs.add("10." + octet + ".0.2");
        addrs.add("10." + octet + ".1.2");
        return addrs;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                if (!n.isNull()) {
                    String text = n.asText();
                    if (text != null && !text.isBlank()) {
                        list.add(text);
                    }
                }
            });
        }
        return list;
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            throw invalid(field + " is required.");
        }
        return node.asText();
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String text = textOrNull(request, field);
        return text == null ? fallback : text;
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("EntityDoesNotExistException", message, 400);
    }
}

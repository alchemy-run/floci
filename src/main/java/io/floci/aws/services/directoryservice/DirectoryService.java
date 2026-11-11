package io.floci.aws.services.directoryservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.floci.aws.services.directoryservice.DirectoryStore.client;
import static io.floci.aws.services.directoryservice.DirectoryStore.error;
import static io.floci.aws.services.directoryservice.DirectoryStore.exists;
import static io.floci.aws.services.directoryservice.DirectoryStore.invalid;
import static io.floci.aws.services.directoryservice.DirectoryStore.missing;
import static io.floci.aws.services.directoryservice.DirectoryStore.now;
import static io.floci.aws.services.directoryservice.DirectoryStore.optional;
import static io.floci.aws.services.directoryservice.DirectoryStore.strings;
import static io.floci.aws.services.directoryservice.DirectoryStore.text;
import static io.floci.aws.services.directoryservice.DirectoryStore.unavailable;
import static io.floci.aws.services.directoryservice.DirectoryStore.unsupported;

/**
 * AWS Directory Service directories: Simple AD, AWS Managed Microsoft AD, AD Connector and directories shared from
 * another account, with their domain controllers, built-in administrator, computers, snapshots, Region replicas,
 * sharing, setup updates, tags and limits.
 * <p>
 * A directory is placed in two subnets of different Availability Zones of its VPC: Directory Service creates a
 * {@code <directoryId>_controllers} security group and one network interface per domain controller, whose
 * addresses are the directory's DNS addresses. A directory moves {@code Requested} to {@code Creating} to
 * {@code Active}, announcing each stage to its registered SNS topics; deleting it removes its network interfaces
 * and security group. An AD Connector reaches the directory serving its customer DNS addresses with the
 * service account's credentials, and fails when none answers. As on AWS, Simple AD is closed to new customers
 * unless {@code floci.services.ds.simple-ad-open} is set.
 */
@ApplicationScoped
public class DirectoryService {

    private static final Logger LOG = Logger.getLogger(DirectoryService.class);
    static final String DIRECTORIES = "directories";
    static final String SNAPSHOTS = "snapshots";
    static final String SHARES = "shares";
    static final String INVITATIONS = "shared-directories";
    static final String SIMPLE_AD_CLOSED = "Simple AD is no longer open to new customers. For capabilities similar "
            + "to Simple AD, explore AWS Managed Microsoft AD.";
    private static final int SIMPLE_AD_LIMIT = 10;
    private static final int MICROSOFT_AD_LIMIT = 20;
    private static final int CONNECTED_LIMIT = 10;
    private static final int MANUAL_SNAPSHOT_LIMIT = 5;

    private final DirectoryStore store;
    private final Instance<Ec2Service> ec2;
    private final Instance<SnsService> sns;
    private final long transitionMillis;
    private final boolean simpleAdOpen;

    @Inject
    public DirectoryService(DirectoryStore store, Instance<Ec2Service> ec2, Instance<SnsService> sns,
                            EmulatorConfig config) {
        this.store = store;
        this.ec2 = ec2;
        this.sns = sns;
        this.transitionMillis = config.services().ds().transitionMillis();
        this.simpleAdOpen = config.services().ds().simpleAdOpen();
    }

    long transitionMillis() {
        return transitionMillis;
    }

    // ---------------------------------------------------------------- lookups

    /** A directory, its elapsed stage transitions applied. */
    ObjectNode directory(String region, String id) {
        ObjectNode directory = store.get(region, DIRECTORIES, id).orElseThrow(() -> missing("Directory " + id
                + " does not exist."));
        return settle(region, directory);
    }

    /** A directory that must be Active for the operation. */
    ObjectNode active(String region, String id) {
        ObjectNode directory = directory(region, id);
        if (!"Active".equals(directory.path("Stage").asText())) {
            throw unavailable("Directory " + id + " is " + directory.path("Stage").asText() + "; it must be Active.");
        }
        return directory;
    }

    /** A Managed Microsoft AD (or shared Microsoft AD) directory, for operations only it supports. */
    ObjectNode microsoftAd(String region, String id, String what) {
        ObjectNode directory = directory(region, id);
        if (!directory.path("Type").asText().endsWith("MicrosoftAD")) {
            throw unsupported(what + " is supported only for AWS Managed Microsoft AD directories.");
        }
        return directory;
    }

    void save(String region, ObjectNode directory) {
        store.put(region, DIRECTORIES, directory.path("DirectoryId").asText(), directory);
    }

    private ObjectNode settle(String region, ObjectNode directory) {
        ObjectNode meta = (ObjectNode) directory.get("_");
        if (System.currentTimeMillis() < meta.path("readyAt").asLong()) {
            String stage = directory.path("Stage").asText();
            if ("Requested".equals(stage) && System.currentTimeMillis() >= meta.path("creatingAt").asLong()) {
                stage(region, directory, "Creating", null);
                save(region, directory);
            }
            return directory;
        }
        String stage = directory.path("Stage").asText();
        switch (stage) {
            case "Requested", "Creating", "Restoring", "Updating" -> {
                String failure = meta.path("failure").asText(null);
                if (failure != null) {
                    stage(region, directory, "Failed", failure);
                } else {
                    stage(region, directory, "Active", null);
                    if ("Creating".equals(stage) || "Requested".equals(stage)) {
                        activated(region, directory);
                    }
                    for (JsonNode controller : directory.path("_").path("controllers")) {
                        ((ObjectNode) controller).put("Status", "Active");
                    }
                }
                save(region, directory);
            }
            case "Deleting" -> {
                release(region, directory);
                stage(region, directory, "Deleted", null);
                store.delete(region, DIRECTORIES, directory.path("DirectoryId").asText());
            }
            default -> {
            }
        }
        return directory;
    }

    private void stage(String region, ObjectNode directory, String stage, String reason) {
        directory.put("Stage", stage);
        directory.put("StageLastUpdatedDateTime", now());
        if (reason != null) {
            directory.put("StageReason", reason);
        } else {
            directory.remove("StageReason");
        }
        announce(region, directory, stage);
    }

    /** The first automatic snapshot a Simple AD or Managed Microsoft AD directory takes once Active. */
    private void activated(String region, ObjectNode directory) {
        String type = directory.path("Type").asText();
        if ("SimpleAD".equals(type) || "MicrosoftAD".equals(type)) {
            snapshot(region, directory, "Auto", null);
        }
    }

    /** Publishes a directory's stage change to its registered SNS topics. */
    void announce(String region, ObjectNode directory, String stage) {
        if (!sns.isResolvable()) {
            return;
        }
        String directoryId = directory.path("DirectoryId").asText();
        for (JsonNode topic : directory.path("_").path("topics")) {
            String arn = topic.path("TopicArn").asText();
            try {
                if (sns.get().topicExists(arn, region)) {
                    ObjectNode message = store.object();
                    message.put("DirectoryId", directoryId);
                    message.put("DirectoryName", directory.path("Name").asText());
                    message.put("Stage", stage);
                    message.put("Message", "Directory " + directoryId + " (" + directory.path("Name").asText()
                            + ") has moved to the " + stage + " stage.");
                    sns.get().publish(arn, null, message.toString(), "AWS Directory Service status change",
                            region);
                }
            } catch (AwsException e) {
                LOG.debugv("Could not notify topic {0}: {1}", arn, e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- placement

    private record Placement(String vpcId, List<Subnet> subnets) {}

    /** The VPC and its two subnets, in different Availability Zones, a directory is placed in. */
    Placement placement(String region, JsonNode settings) {
        String vpcId = text(settings, "VpcId");
        List<String> subnetIds = strings(settings.path("SubnetIds"));
        if (subnetIds.size() != 2) {
            throw invalid("Two subnets in different Availability Zones are required.");
        }
        if (!ec2.isResolvable()) {
            return new Placement(vpcId, List.of());
        }
        if (ec2.get().describeVpcs(region, List.of(vpcId), Map.of()).isEmpty()) {
            throw invalid("Invalid VPC ID: " + vpcId + ".");
        }
        List<Subnet> subnets;
        try {
            subnets = ec2.get().describeSubnets(region, subnetIds, Map.of());
        } catch (AwsException e) {
            throw invalid("Invalid subnet ID(s): " + String.join(", ", subnetIds) + ".");
        }
        if (subnets.size() != 2) {
            throw invalid("Invalid subnet ID(s): " + String.join(", ", subnetIds) + ".");
        }
        for (Subnet subnet : subnets) {
            if (!vpcId.equals(subnet.getVpcId())) {
                throw invalid("Subnet " + subnet.getSubnetId() + " is not in VPC " + vpcId + ".");
            }
        }
        if (subnets.get(0).getAvailabilityZone().equals(subnets.get(1).getAvailabilityZone())) {
            throw invalid("The subnets must be in different Availability Zones.");
        }
        List<Subnet> ordered = new ArrayList<>();
        for (String id : subnetIds) {
            for (Subnet subnet : subnets) {
                if (id.equals(subnet.getSubnetId())) {
                    ordered.add(subnet);
                }
            }
        }
        return new Placement(vpcId, ordered);
    }

    /** Creates the directory's security group and one network interface per domain controller. */
    private void place(String region, ObjectNode directory, Placement placement, int controllers,
                       ObjectNode settings) {
        String directoryId = directory.path("DirectoryId").asText();
        ObjectNode meta = (ObjectNode) directory.get("_");
        ArrayNode list = meta.putArray("controllers");
        ArrayNode zones = settings.putArray("AvailabilityZones");
        placement.subnets().forEach(subnet -> zones.add(subnet.getAvailabilityZone()));
        if (!ec2.isResolvable() || placement.subnets().isEmpty()) {
            for (int index = 0; index < controllers; index++) {
                list.addObject().put("DomainControllerId", "dc-" + store.id("x").substring(2))
                        .put("DnsIpAddr", "10.0." + index + "." + (10 + store.nextInt(200)))
                        .put("VpcId", placement.vpcId()).put("SubnetId", "").put("AvailabilityZone", "")
                        .put("Status", "Creating").put("LaunchTime", now());
            }
            return;
        }
        SecurityGroup group = ec2.get().createSecurityGroup(region, directoryId + "_controllers",
                "AWS created security group for " + directoryId + " directory controllers", placement.vpcId());
        meta.put("securityGroupId", group.getGroupId());
        settings.put("SecurityGroupId", group.getGroupId());
        for (int index = 0; index < controllers; index++) {
            addController(region, directory, placement.subnets().get(index % placement.subnets().size()));
        }
    }

    void addController(String region, ObjectNode directory, Subnet subnet) {
        ObjectNode meta = (ObjectNode) directory.get("_");
        String directoryId = directory.path("DirectoryId").asText();
        NetworkInterface eni = ec2.get().createNetworkInterface(region, subnet.getSubnetId(),
                "AWS created network interface for directory " + directoryId, null, null,
                List.of(meta.path("securityGroupId").asText()), null);
        meta.withArray("controllers").addObject()
                .put("DomainControllerId", "dc-" + eni.getNetworkInterfaceId().substring(4, 14))
                .put("DnsIpAddr", eni.getPrivateIpAddress()).put("VpcId", subnet.getVpcId())
                .put("SubnetId", subnet.getSubnetId()).put("AvailabilityZone", subnet.getAvailabilityZone())
                .put("Status", "Creating").put("LaunchTime", now())
                .put("StatusLastUpdatedDateTime", now()).put("networkInterfaceId", eni.getNetworkInterfaceId());
    }

    /** Deletes a directory's network interfaces and security group. */
    private void release(String region, ObjectNode directory) {
        if (!ec2.isResolvable()) {
            return;
        }
        for (JsonNode controller : directory.path("_").path("controllers")) {
            String eni = controller.path("networkInterfaceId").asText(null);
            if (eni != null) {
                try {
                    ec2.get().deleteNetworkInterface(region, eni);
                } catch (AwsException e) {
                    LOG.debugv("Could not delete {0}: {1}", eni, e.getMessage());
                }
            }
        }
        String group = directory.path("_").path("securityGroupId").asText(null);
        if (group != null) {
            try {
                ec2.get().deleteSecurityGroup(region, group);
            } catch (AwsException e) {
                LOG.debugv("Could not delete {0}: {1}", group, e.getMessage());
            }
        }
    }

    List<String> dnsAddresses(ObjectNode directory) {
        List<String> addresses = new ArrayList<>();
        for (JsonNode controller : directory.path("_").path("controllers")) {
            addresses.add(controller.path("DnsIpAddr").asText());
        }
        return addresses;
    }

    // ---------------------------------------------------------------- creation

    private static void validateName(String name) {
        if (!name.matches("^([a-zA-Z0-9]+[\\.-])+([a-zA-Z0-9])+$")) {
            throw invalid("Invalid directory name: " + name + ". It must be a fully qualified domain name.");
        }
    }

    private static String shortName(JsonNode input, String name) {
        String shortName = optional(input, "ShortName");
        if (shortName == null) {
            shortName = name.split("\\.")[0].toUpperCase(Locale.ROOT);
            return shortName.length() > 15 ? shortName.substring(0, 15) : shortName;
        }
        if (!shortName.matches("^[^\\\\/:*?\"<>|.]+[^\\\\/:*?\"<>|]*$") || shortName.length() > 15) {
            throw invalid("Invalid short name: " + shortName + ".");
        }
        return shortName;
    }

    /** Directory passwords: 8 to 64 characters with three of lowercase, uppercase, digits and symbols. */
    static boolean strong(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            return false;
        }
        int classes = 0;
        classes += password.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isDigit) ? 1 : 0;
        classes += password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0;
        return classes >= 3;
    }

    private ObjectNode newDirectory(JsonNode input, String type, String name) {
        validateName(name);
        String id = store.id("d");
        double now = now();
        ObjectNode directory = store.record();
        directory.put("DirectoryId", id);
        directory.put("Name", name);
        directory.put("ShortName", shortName(input, name));
        if (input.hasNonNull("Description")) {
            directory.put("Description", input.get("Description").asText());
        }
        directory.put("AccessUrl", id + ".awsapps.com");
        directory.put("Alias", id);
        directory.put("Stage", transitionMillis <= 0 ? "Active" : "Requested");
        directory.put("LaunchTime", now);
        directory.put("StageLastUpdatedDateTime", now);
        directory.put("Type", type);
        directory.put("SsoEnabled", false);
        directory.put("NetworkType", input.path("NetworkType").asText("IPv4"));
        ObjectNode meta = (ObjectNode) directory.get("_");
        meta.put("creatingAt", System.currentTimeMillis() + transitionMillis / 4);
        meta.put("readyAt", System.currentTimeMillis() + transitionMillis);
        ObjectNode tags = meta.putObject("tags");
        for (JsonNode tag : input.path("Tags")) {
            tags.put(text(tag, "Key"), tag.path("Value").asText(""));
        }
        if (tags.size() > 50) {
            throw error("TagLimitExceededException", "A directory can have at most 50 tags.");
        }
        return directory;
    }

    private int count(String region, Set<String> types) {
        return store.list(region, DIRECTORIES, directory -> types.contains(directory.path("Type").asText()))
                .size();
    }

    public synchronized ObjectNode createDirectory(JsonNode input, String region) {
        if (!simpleAdOpen) {
            throw client(SIMPLE_AD_CLOSED);
        }
        String name = text(input, "Name");
        String password = text(input, "Password");
        if (!strong(password)) {
            throw invalid("Invalid directory password: it must be 8 to 64 characters with three of lowercase, "
                    + "uppercase, digits and symbols.");
        }
        String size = text(input, "Size");
        if (!Set.of("Small", "Large").contains(size)) {
            throw invalid("Size must be Small or Large.");
        }
        if (!input.path("VpcSettings").isObject()) {
            throw invalid("VpcSettings is required.");
        }
        if (count(region, Set.of("SimpleAD")) >= SIMPLE_AD_LIMIT) {
            throw error("DirectoryLimitExceededException", "The account has reached its Simple AD directory limit.");
        }
        Placement placement = placement(region, input.get("VpcSettings"));
        ObjectNode directory = newDirectory(input, "SimpleAD", name);
        directory.put("Size", size);
        ObjectNode settings = directory.putObject("VpcSettings");
        settings.put("VpcId", placement.vpcId());
        settings.set("SubnetIds", input.get("VpcSettings").get("SubnetIds").deepCopy());
        ObjectNode meta = (ObjectNode) directory.get("_");
        meta.putObject("users").put("Administrator", password);
        place(region, directory, placement, 2, settings);
        finish(region, directory);
        return store.object().put("DirectoryId", directory.path("DirectoryId").asText());
    }

    public synchronized ObjectNode createMicrosoftAD(JsonNode input, String region) {
        String name = text(input, "Name");
        String password = text(input, "Password");
        if (!strong(password)) {
            throw invalid("Invalid directory password: it must be 8 to 64 characters with three of lowercase, "
                    + "uppercase, digits and symbols.");
        }
        String edition = input.path("Edition").asText("Enterprise");
        if (!Set.of("Enterprise", "Standard", "Hybrid").contains(edition)) {
            throw invalid("Edition must be Enterprise, Standard or Hybrid.");
        }
        if ("Hybrid".equals(edition)) {
            throw unsupported("Hybrid edition directories are created with CreateHybridAD.");
        }
        if (!input.path("VpcSettings").isObject()) {
            throw invalid("VpcSettings is required.");
        }
        if (count(region, Set.of("MicrosoftAD")) >= MICROSOFT_AD_LIMIT) {
            throw error("DirectoryLimitExceededException", "The account has reached its Microsoft AD directory "
                    + "limit.");
        }
        Placement placement = placement(region, input.get("VpcSettings"));
        ObjectNode directory = newDirectory(input, "MicrosoftAD", name);
        directory.put("Edition", edition);
        directory.put("DesiredNumberOfDomainControllers", 2);
        directory.put("OsVersion", "SERVER_2019");
        ObjectNode settings = directory.putObject("VpcSettings");
        settings.put("VpcId", placement.vpcId());
        settings.set("SubnetIds", input.get("VpcSettings").get("SubnetIds").deepCopy());
        directory.putObject("RegionsInfo").put("PrimaryRegion", region).putArray("AdditionalRegions");
        ObjectNode meta = (ObjectNode) directory.get("_");
        meta.putObject("users").put("Admin", password);
        place(region, directory, placement, 2, settings);
        finish(region, directory);
        return store.object().put("DirectoryId", directory.path("DirectoryId").asText());
    }

    private void finish(String region, ObjectNode directory) {
        ArrayNode addresses = directory.putArray("DnsIpAddrs");
        dnsAddresses(directory).forEach(addresses::add);
        if ("Active".equals(directory.path("Stage").asText())) {
            for (JsonNode controller : directory.path("_").path("controllers")) {
                ((ObjectNode) controller).put("Status", "Active");
            }
            activated(region, directory);
        }
        save(region, directory);
    }

    public synchronized ObjectNode connectDirectory(JsonNode input, String region) {
        String name = text(input, "Name");
        String password = text(input, "Password");
        String size = text(input, "Size");
        if (!Set.of("Small", "Large").contains(size)) {
            throw invalid("Size must be Small or Large.");
        }
        JsonNode connect = input.path("ConnectSettings");
        if (!connect.isObject()) {
            throw invalid("ConnectSettings is required.");
        }
        String user = text(connect, "CustomerUserName");
        List<String> dnsIps = strings(connect.path("CustomerDnsIps"));
        if (dnsIps.isEmpty()) {
            throw invalid("ConnectSettings.CustomerDnsIps is required.");
        }
        if (count(region, Set.of("ADConnector")) >= CONNECTED_LIMIT) {
            throw error("DirectoryLimitExceededException", "The account has reached its AD Connector limit.");
        }
        Placement placement = placement(region, connect);
        ObjectNode directory = newDirectory(input, "ADConnector", name);
        directory.put("Size", size);
        ObjectNode settings = directory.putObject("ConnectSettings");
        settings.put("VpcId", placement.vpcId());
        settings.set("SubnetIds", connect.get("SubnetIds").deepCopy());
        settings.put("CustomerUserName", user);
        place(region, directory, placement, 2, settings);
        ArrayNode connectIps = settings.putArray("ConnectIps");
        dnsAddresses(directory).forEach(connectIps::add);
        ArrayNode addresses = directory.putArray("DnsIpAddrs");
        dnsIps.forEach(addresses::add);
        String failure = reach(region, name, dnsIps, user, password);
        ObjectNode meta = (ObjectNode) directory.get("_");
        if (failure != null) {
            meta.put("failure", failure);
            if (transitionMillis <= 0) {
                directory.put("Stage", "Failed");
                directory.put("StageReason", failure);
            }
        } else if ("Active".equals(directory.path("Stage").asText())) {
            for (JsonNode controller : meta.path("controllers")) {
                ((ObjectNode) controller).put("Status", "Active");
            }
        }
        save(region, directory);
        return store.object().put("DirectoryId", directory.path("DirectoryId").asText());
    }

    /**
     * Whether an AD Connector reaches its domain: a directory of that name must serve one of the customer DNS
     * addresses and accept the service account's credentials. Returns why it does not, or null.
     */
    private String reach(String region, String name, List<String> dnsIps, String user, String password) {
        for (ObjectNode candidate : store.list(region, DIRECTORIES, directory -> !"ADConnector".equals(directory
                .path("Type").asText()))) {
            ObjectNode directory = settle(region, candidate);
            if (dnsAddresses(directory).stream().noneMatch(dnsIps::contains)) {
                continue;
            }
            if (!name.equalsIgnoreCase(directory.path("Name").asText())) {
                return "The DNS server at the customer DNS addresses serves " + directory.path("Name").asText()
                        + ", not " + name + ".";
            }
            if (!"Active".equals(directory.path("Stage").asText())) {
                return "The directory " + name + " is not reachable: it is " + directory.path("Stage").asText() + ".";
            }
            String known = directory.path("_").path("users").path(user).asText(null);
            if (known == null || !known.equals(password)) {
                return "Authentication failed for the service account " + user + ".";
            }
            return null;
        }
        return "Connectivity issues detected: no DNS server answers at " + String.join(", ", dnsIps) + ".";
    }

    // ---------------------------------------------------------------- reads

    ObjectNode description(String region, ObjectNode directory) {
        ObjectNode view = store.view(directory);
        if (!directory.path("_").path("radius").isMissingNode()) {
            ObjectNode radius = ((ObjectNode) directory.get("_").get("radius")).deepCopy();
            radius.remove("SharedSecret");
            view.set("RadiusSettings", radius);
            view.put("RadiusStatus", System.currentTimeMillis() >= directory.path("_").path("radiusReadyAt")
                    .asLong() ? "Completed" : "Creating");
        }
        return view;
    }

    public synchronized ObjectNode describeDirectories(JsonNode input, String region) {
        List<String> ids = strings(input.path("DirectoryIds"));
        List<ObjectNode> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                items.add(description(region, directory(region, id)));
            }
        } else {
            for (ObjectNode stored : store.list(region, DIRECTORIES, directory -> true)) {
                ObjectNode directory = settle(region, stored);
                if (store.get(region, DIRECTORIES, directory.path("DirectoryId").asText()).isPresent()) {
                    items.add(description(region, directory));
                }
            }
        }
        return store.page(items, input, "DirectoryDescriptions", 100);
    }

    public synchronized ObjectNode describeDomainControllers(JsonNode input, String region) {
        ObjectNode directory = directory(region, text(input, "DirectoryId"));
        if ("ADConnector".equals(directory.path("Type").asText())) {
            throw unsupported("AD Connector directories have no domain controllers.");
        }
        List<String> ids = strings(input.path("DomainControllerIds"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode controller : directory.path("_").path("controllers")) {
            if (ids.isEmpty() || ids.contains(controller.path("DomainControllerId").asText())) {
                ObjectNode view = ((ObjectNode) controller).deepCopy();
                view.remove("networkInterfaceId");
                view.put("DirectoryId", directory.path("DirectoryId").asText());
                items.add(view);
            }
        }
        return store.page(items, input, "DomainControllers", 100);
    }

    public synchronized ObjectNode getDirectoryLimits(JsonNode input, String region) {
        int simple = count(region, Set.of("SimpleAD"));
        int microsoft = count(region, Set.of("MicrosoftAD"));
        int connected = count(region, Set.of("ADConnector"));
        ObjectNode limits = store.object();
        limits.put("CloudOnlyDirectoriesLimit", SIMPLE_AD_LIMIT);
        limits.put("CloudOnlyDirectoriesCurrentCount", simple);
        limits.put("CloudOnlyDirectoriesLimitReached", simple >= SIMPLE_AD_LIMIT);
        limits.put("CloudOnlyMicrosoftADLimit", MICROSOFT_AD_LIMIT);
        limits.put("CloudOnlyMicrosoftADCurrentCount", microsoft);
        limits.put("CloudOnlyMicrosoftADLimitReached", microsoft >= MICROSOFT_AD_LIMIT);
        limits.put("ConnectedDirectoriesLimit", CONNECTED_LIMIT);
        limits.put("ConnectedDirectoriesCurrentCount", connected);
        limits.put("ConnectedDirectoriesLimitReached", connected >= CONNECTED_LIMIT);
        ObjectNode response = store.object();
        response.set("DirectoryLimits", limits);
        return response;
    }

    // ---------------------------------------------------------------- deletion

    public synchronized ObjectNode deleteDirectory(JsonNode input, String region) {
        ObjectNode directory = directory(region, text(input, "DirectoryId"));
        String id = directory.path("DirectoryId").asText();
        for (ObjectNode share : store.list(region, SHARES, share -> id.equals(share.path("OwnerDirectoryId")
                .asText()) && Set.of("Shared", "PendingAcceptance", "Sharing").contains(share.path("ShareStatus")
                .asText()))) {
            throw client("Directory " + id + " is shared with account " + share.path("SharedAccountId").asText()
                    + "; unshare it before deleting it.");
        }
        if ("SharedMicrosoftAD".equals(directory.path("Type").asText())) {
            String owner = directory.path("_").path("ownerAccountId").asText();
            String shareId = directory.path("_").path("sharedDirectoryId").asText();
            store.getForAccount(owner, region, SHARES, shareId).ifPresent(share -> {
                share.put("ShareStatus", "Deleted");
                share.put("LastUpdatedDateTime", now());
                store.putForAccount(owner, region, SHARES, shareId, share);
            });
        }
        for (ObjectNode snapshot : store.list(region, SNAPSHOTS, snapshot -> id.equals(snapshot.path("DirectoryId")
                .asText()))) {
            store.delete(region, SNAPSHOTS, snapshot.path("SnapshotId").asText());
        }
        stage(region, directory, "Deleting", null);
        ((ObjectNode) directory.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
        save(region, directory);
        if (transitionMillis <= 0) {
            settle(region, directory);
        }
        return store.object().put("DirectoryId", id);
    }

    // ---------------------------------------------------------------- users and computers

    public synchronized ObjectNode resetUserPassword(JsonNode input, String region) {
        ObjectNode directory = active(region, text(input, "DirectoryId"));
        if (Set.of("ADConnector", "SharedMicrosoftAD").contains(directory.path("Type").asText())) {
            throw unsupported("Passwords are reset in the directory that owns the users.");
        }
        String user = text(input, "UserName");
        ObjectNode users = (ObjectNode) directory.get("_").get("users");
        String existing = null;
        for (String name : names(users)) {
            if (name.equalsIgnoreCase(user)) {
                existing = name;
            }
        }
        if (existing == null) {
            throw error("UserDoesNotExistException", "User " + user + " does not exist in directory "
                    + directory.path("DirectoryId").asText() + ".");
        }
        String password = text(input, "NewPassword");
        if (!strong(password)) {
            throw error("InvalidPasswordException", "The new password does not meet the directory's password "
                    + "policy.");
        }
        users.put(existing, password);
        save(region, directory);
        return store.object();
    }

    static List<String> names(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    public synchronized ObjectNode createComputer(JsonNode input, String region) {
        ObjectNode directory = active(region, text(input, "DirectoryId"));
        if ("ADConnector".equals(directory.path("Type").asText())) {
            throw unsupported("Computers are created in the directory an AD Connector connects to.");
        }
        String name = text(input, "ComputerName");
        if (name.length() > 15 || !name.matches("^[A-Za-z0-9-]+$")) {
            throw invalid("ComputerName must be 1 to 15 letters, digits and hyphens.");
        }
        String password = text(input, "Password");
        if (password.length() < 8 || password.length() > 64) {
            throw invalid("The computer password must be 8 to 64 characters.");
        }
        ObjectNode computers = ((ObjectNode) directory.get("_")).withObject("computers");
        for (String existing : names(computers)) {
            if (existing.equalsIgnoreCase(name)) {
                throw exists("Computer " + name + " already exists in directory " + directory.path("DirectoryId")
                        .asText() + ".");
            }
        }
        String base = baseDn(directory.path("Name").asText());
        String ou = optional(input, "OrganizationalUnitDistinguishedName");
        if (ou != null && !ou.toLowerCase(Locale.ROOT).endsWith(base.toLowerCase(Locale.ROOT))) {
            throw invalid("The organizational unit " + ou + " is not in the domain " + base + ".");
        }
        String dn = "CN=" + name + "," + (ou != null ? ou : "CN=Computers," + base);
        ObjectNode computer = store.object();
        int rid = 1100 + computers.size();
        computer.put("ComputerId", sid(directory) + "-" + rid);
        computer.put("ComputerName", name);
        ArrayNode attributes = computer.putArray("ComputerAttributes");
        attributes.addObject().put("Name", "DistinguishedName").put("Value", dn);
        attributes.addObject().put("Name", "WindowsSamName").put("Value", name.toUpperCase(Locale.ROOT) + "$");
        for (JsonNode attribute : input.path("ComputerAttributes")) {
            attributes.addObject().put("Name", attribute.path("Name").asText()).put("Value",
                    attribute.path("Value").asText());
        }
        computers.set(name, computer);
        save(region, directory);
        ObjectNode response = store.object();
        response.set("Computer", computer.deepCopy());
        return response;
    }

    static String baseDn(String domain) {
        StringBuilder dn = new StringBuilder();
        for (String label : domain.split("\\.")) {
            dn.append(dn.isEmpty() ? "" : ",").append("DC=").append(label);
        }
        return dn.toString();
    }

    /** The domain's security identifier, stable per directory. */
    static String sid(ObjectNode directory) {
        int hash = directory.path("DirectoryId").asText().hashCode();
        return "S-1-5-21-" + Integer.toUnsignedString(hash) + "-" + Integer.toUnsignedString(hash * 31 + 7) + "-"
                + Integer.toUnsignedString(hash * 17 + 3);
    }

    // ---------------------------------------------------------------- snapshots

    private ObjectNode snapshot(String region, ObjectNode directory, String type, String name) {
        ObjectNode snapshot = store.record();
        String id = store.id("s");
        snapshot.put("DirectoryId", directory.path("DirectoryId").asText());
        snapshot.put("SnapshotId", id);
        snapshot.put("Type", type);
        if (name != null) {
            snapshot.put("Name", name);
        }
        snapshot.put("Status", transitionMillis <= 0 ? "Completed" : "Creating");
        snapshot.put("StartTime", now());
        ObjectNode meta = (ObjectNode) snapshot.get("_");
        meta.put("readyAt", System.currentTimeMillis() + transitionMillis);
        ObjectNode state = meta.putObject("state");
        for (String field : List.of("users", "computers", "forwarders", "settings", "routes")) {
            if (directory.path("_").has(field)) {
                state.set(field, directory.get("_").get(field).deepCopy());
            }
        }
        store.put(region, SNAPSHOTS, id, snapshot);
        return snapshot;
    }

    private ObjectNode settleSnapshot(String region, ObjectNode snapshot) {
        if ("Creating".equals(snapshot.path("Status").asText())
                && System.currentTimeMillis() >= snapshot.path("_").path("readyAt").asLong()) {
            snapshot.put("Status", "Completed");
            store.put(region, SNAPSHOTS, snapshot.path("SnapshotId").asText(), snapshot);
        }
        return snapshot;
    }

    private long manualSnapshots(String region, String directoryId) {
        return store.list(region, SNAPSHOTS, snapshot -> directoryId.equals(snapshot.path("DirectoryId").asText())
                && "Manual".equals(snapshot.path("Type").asText())).size();
    }

    public synchronized ObjectNode createSnapshot(JsonNode input, String region) {
        ObjectNode directory = active(region, text(input, "DirectoryId"));
        if (!Set.of("SimpleAD", "MicrosoftAD").contains(directory.path("Type").asText())) {
            throw invalid("Snapshots are supported for Simple AD and Managed Microsoft AD directories.");
        }
        if (manualSnapshots(region, directory.path("DirectoryId").asText()) >= MANUAL_SNAPSHOT_LIMIT) {
            throw error("SnapshotLimitExceededException", "Directory " + directory.path("DirectoryId").asText()
                    + " already has " + MANUAL_SNAPSHOT_LIMIT + " manual snapshots.");
        }
        String name = optional(input, "Name");
        if (name != null && !name.matches("^([a-zA-Z0-9_])[\\\\a-zA-Z0-9_@#%*+=:?./!\\s-]*$")) {
            throw invalid("Invalid snapshot name: " + name + ".");
        }
        ObjectNode snapshot = snapshot(region, directory, "Manual", name);
        return store.object().put("SnapshotId", snapshot.path("SnapshotId").asText());
    }

    public synchronized ObjectNode describeSnapshots(JsonNode input, String region) {
        String directoryId = optional(input, "DirectoryId");
        if (directoryId != null) {
            directory(region, directoryId);
        }
        List<String> ids = strings(input.path("SnapshotIds"));
        List<ObjectNode> items = new ArrayList<>();
        for (String id : ids) {
            if (store.get(region, SNAPSHOTS, id).isEmpty()) {
                throw missing("Snapshot " + id + " does not exist.");
            }
        }
        for (ObjectNode snapshot : store.list(region, SNAPSHOTS, snapshot -> (directoryId == null
                || directoryId.equals(snapshot.path("DirectoryId").asText())) && (ids.isEmpty()
                || ids.contains(snapshot.path("SnapshotId").asText())))) {
            items.add(store.view(settleSnapshot(region, snapshot)));
        }
        return store.page(items, input, "Snapshots", 100);
    }

    public synchronized ObjectNode deleteSnapshot(JsonNode input, String region) {
        String id = text(input, "SnapshotId");
        ObjectNode snapshot = store.get(region, SNAPSHOTS, id).orElseThrow(() -> missing("Snapshot " + id
                + " does not exist."));
        if ("Auto".equals(snapshot.path("Type").asText())) {
            throw invalid("Automatic snapshots cannot be deleted.");
        }
        store.delete(region, SNAPSHOTS, id);
        return store.object().put("SnapshotId", id);
    }

    public synchronized ObjectNode restoreFromSnapshot(JsonNode input, String region) {
        String id = text(input, "SnapshotId");
        ObjectNode snapshot = store.get(region, SNAPSHOTS, id).map(value -> settleSnapshot(region, value))
                .orElseThrow(() -> missing("Snapshot " + id + " does not exist."));
        if (!"Completed".equals(snapshot.path("Status").asText())) {
            throw invalid("Snapshot " + id + " is " + snapshot.path("Status").asText() + ".");
        }
        ObjectNode directory = active(region, snapshot.path("DirectoryId").asText());
        ObjectNode meta = (ObjectNode) directory.get("_");
        snapshot.path("_").path("state").properties().forEach(entry -> meta.set(entry.getKey(),
                entry.getValue().deepCopy()));
        stage(region, directory, transitionMillis <= 0 ? "Active" : "Restoring", null);
        meta.put("readyAt", System.currentTimeMillis() + transitionMillis);
        save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode getSnapshotLimits(JsonNode input, String region) {
        ObjectNode directory = directory(region, text(input, "DirectoryId"));
        long count = manualSnapshots(region, directory.path("DirectoryId").asText());
        ObjectNode response = store.object();
        response.putObject("SnapshotLimits").put("ManualSnapshotsLimit", MANUAL_SNAPSHOT_LIMIT)
                .put("ManualSnapshotsCurrentCount", count)
                .put("ManualSnapshotsLimitReached", count >= MANUAL_SNAPSHOT_LIMIT);
        return response;
    }

    // ---------------------------------------------------------------- domain controllers and setup

    public synchronized ObjectNode updateNumberOfDomainControllers(JsonNode input, String region) {
        ObjectNode directory = active(region, text(input, "DirectoryId"));
        if (!"MicrosoftAD".equals(directory.path("Type").asText())) {
            throw unsupported("Only Managed Microsoft AD directories can add domain controllers.");
        }
        int desired = input.path("DesiredNumber").asInt(-1);
        if (desired < 2) {
            throw invalid("DesiredNumber must be at least 2.");
        }
        if (desired > 20) {
            throw error("DomainControllerLimitExceededException", "A directory can have at most 20 domain "
                    + "controllers.");
        }
        ArrayNode controllers = (ArrayNode) directory.get("_").get("controllers");
        List<String> subnets = strings(directory.path("VpcSettings").path("SubnetIds"));
        while (controllers.size() < desired && ec2.isResolvable() && !subnets.isEmpty()) {
            List<Subnet> found = ec2.get().describeSubnets(region, List.of(subnets.get(controllers.size()
                    % subnets.size())), Map.of());
            addController(region, directory, found.get(0));
        }
        while (controllers.size() > desired) {
            JsonNode removed = controllers.remove(controllers.size() - 1);
            String eni = removed.path("networkInterfaceId").asText(null);
            if (eni != null && ec2.isResolvable()) {
                try {
                    ec2.get().deleteNetworkInterface(region, eni);
                } catch (AwsException e) {
                    LOG.debugv("Could not delete {0}: {1}", eni, e.getMessage());
                }
            }
        }
        directory.put("DesiredNumberOfDomainControllers", desired);
        if (transitionMillis <= 0) {
            controllers.forEach(controller -> ((ObjectNode) controller).put("Status", "Active"));
        }
        ArrayNode addresses = directory.putArray("DnsIpAddrs");
        dnsAddresses(directory).forEach(addresses::add);
        save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode updateDirectorySetup(JsonNode input, String region) {
        ObjectNode directory = active(region, text(input, "DirectoryId"));
        String type = text(input, "UpdateType");
        ObjectNode entry = store.object();
        entry.put("Region", region);
        entry.put("InitiatedBy", store.regions().buildGlobalArn("iam", store.account(), "root"));
        entry.put("StartTime", now());
        entry.put("LastUpdatedDateTime", now());
        switch (type) {
            case "OS" -> {
                if (!"MicrosoftAD".equals(directory.path("Type").asText())) {
                    throw unsupported("Only Managed Microsoft AD directories update their operating system.");
                }
                String version = text(input.path("OSUpdateSettings"), "OSVersion");
                if (version.equals(directory.path("OsVersion").asText())) {
                    throw error("DirectoryInDesiredStateException", "Directory is already on " + version + ".");
                }
                if ("SERVER_2012".equals(version)) {
                    throw invalid("Directories can only be updated to a newer operating system.");
                }
                entry.putObject("PreviousValue").putObject("OSUpdateSettings").put("OSVersion",
                        directory.path("OsVersion").asText());
                entry.putObject("NewValue").putObject("OSUpdateSettings").put("OSVersion", version);
                directory.put("OsVersion", version);
            }
            case "SIZE" -> {
                String size = text(input.path("DirectorySizeUpdateSettings"), "DirectorySize");
                if (!directory.has("Size")) {
                    throw unsupported("Only Simple AD and AD Connector directories have a size.");
                }
                if (size.equals(directory.path("Size").asText())) {
                    throw error("DirectoryInDesiredStateException", "Directory is already " + size + ".");
                }
                if ("Small".equals(size)) {
                    throw invalid("Directories can only be enlarged.");
                }
                directory.put("Size", size);
            }
            case "NETWORK" -> {
                String network = text(input.path("NetworkUpdateSettings"), "NetworkType");
                if (network.equals(directory.path("NetworkType").asText())) {
                    throw error("DirectoryInDesiredStateException", "Directory already uses " + network + ".");
                }
                directory.put("NetworkType", network);
                if (input.path("NetworkUpdateSettings").has("CustomerDnsIpsV6")) {
                    directory.set("DnsIpv6Addrs", input.get("NetworkUpdateSettings").get("CustomerDnsIpsV6")
                            .deepCopy());
                }
            }
            default -> throw invalid("UpdateType must be OS, NETWORK or SIZE.");
        }
        if (input.path("CreateSnapshotBeforeUpdate").asBoolean(false)) {
            snapshot(region, directory, "Manual", "pre-update");
        }
        entry.put("Status", "Updated");
        ObjectNode updates = ((ObjectNode) directory.get("_")).withObject("updates");
        updates.withArray(type).add(entry);
        stage(region, directory, transitionMillis <= 0 ? "Active" : "Updating", null);
        ((ObjectNode) directory.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
        save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode describeUpdateDirectory(JsonNode input, String region) {
        ObjectNode directory = directory(region, text(input, "DirectoryId"));
        String type = text(input, "UpdateType");
        String regionName = optional(input, "RegionName");
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode entry : directory.path("_").path("updates").path(type)) {
            if (regionName == null || regionName.equals(entry.path("Region").asText())) {
                items.add(((ObjectNode) entry).deepCopy());
            }
        }
        return store.page(items, input, "UpdateActivities", 100);
    }

    // ---------------------------------------------------------------- Regions

    public synchronized ObjectNode addRegion(JsonNode input, String region) {
        ObjectNode directory = active(region, text(input, "DirectoryId"));
        if (!"MicrosoftAD".equals(directory.path("Type").asText()) || !"Enterprise".equals(directory
                .path("Edition").asText())) {
            throw unsupported("Multi-Region replication is supported only for Enterprise Edition Managed Microsoft "
                    + "AD directories.");
        }
        String target = text(input, "RegionName");
        if (target.equals(region)) {
            throw error("DirectoryAlreadyInRegionException", "The directory's primary Region is " + region + ".");
        }
        ArrayNode additional = (ArrayNode) directory.path("RegionsInfo").path("AdditionalRegions");
        if (strings(additional).contains(target)) {
            throw error("DirectoryAlreadyInRegionException", "The directory is already replicated to " + target
                    + ".");
        }
        if (additional.size() >= 4) {
            throw error("RegionLimitExceededException", "A directory can be replicated to at most four additional "
                    + "Regions.");
        }
        Placement placement = placement(target, input.path("VPCSettings"));
        ObjectNode replica = store.record();
        replica.setAll(store.view(directory));
        replica.putObject("_").put("seq", System.nanoTime()).put("replicaOf", region)
                .put("readyAt", System.currentTimeMillis() + transitionMillis)
                .set("users", directory.path("_").path("users").deepCopy());
        ObjectNode settings = replica.putObject("VpcSettings");
        settings.put("VpcId", placement.vpcId());
        settings.set("SubnetIds", input.path("VPCSettings").path("SubnetIds").deepCopy());
        replica.put("Stage", transitionMillis <= 0 ? "Active" : "Creating");
        place(target, replica, placement, 2, settings);
        ArrayNode addresses = replica.putArray("DnsIpAddrs");
        dnsAddresses(replica).forEach(addresses::add);
        additional.add(target);
        ((ObjectNode) replica.get("RegionsInfo")).set("AdditionalRegions", additional.deepCopy());
        store.put(target, DIRECTORIES, directory.path("DirectoryId").asText(), replica);
        ((ObjectNode) directory.get("_")).withObject("regions").put(target, now());
        save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode removeRegion(JsonNode input, String region) {
        ObjectNode replica = directory(region, text(input, "DirectoryId"));
        String primary = replica.path("_").path("replicaOf").asText(null);
        if (primary == null) {
            throw unsupported("RemoveRegion is called in an additional Region; " + region + " is the primary.");
        }
        release(region, replica);
        store.delete(region, DIRECTORIES, replica.path("DirectoryId").asText());
        store.get(primary, DIRECTORIES, replica.path("DirectoryId").asText()).ifPresent(directory -> {
            ArrayNode kept = store.array();
            directory.path("RegionsInfo").path("AdditionalRegions").forEach(name -> {
                if (!region.equals(name.asText())) {
                    kept.add(name.asText());
                }
            });
            ((ObjectNode) directory.get("RegionsInfo")).set("AdditionalRegions", kept);
            ((ObjectNode) directory.get("_")).withObject("regions").remove(region);
            store.put(primary, DIRECTORIES, directory.path("DirectoryId").asText(), directory);
        });
        return store.object();
    }

    public synchronized ObjectNode describeRegions(JsonNode input, String region) {
        ObjectNode directory = directory(region, text(input, "DirectoryId"));
        if (!"MicrosoftAD".equals(directory.path("Type").asText())) {
            throw unsupported("Regions are described for Managed Microsoft AD directories.");
        }
        String primary = directory.path("RegionsInfo").path("PrimaryRegion").asText(region);
        String wanted = optional(input, "RegionName");
        List<ObjectNode> items = new ArrayList<>();
        List<String> regions = new ArrayList<>();
        regions.add(primary);
        regions.addAll(strings(directory.path("RegionsInfo").path("AdditionalRegions")));
        for (String name : regions) {
            if (wanted != null && !wanted.equals(name)) {
                continue;
            }
            Optional<ObjectNode> copy = store.get(name, DIRECTORIES, directory.path("DirectoryId").asText());
            if (copy.isEmpty()) {
                continue;
            }
            ObjectNode replica = settle(name, copy.get());
            ObjectNode item = store.object();
            item.put("DirectoryId", replica.path("DirectoryId").asText());
            item.put("RegionName", name);
            item.put("RegionType", name.equals(primary) ? "Primary" : "Additional");
            item.put("Status", replica.path("Stage").asText());
            item.set("VpcSettings", replica.path("VpcSettings").deepCopy());
            item.put("DesiredNumberOfDomainControllers", replica.path("DesiredNumberOfDomainControllers").asInt(2));
            item.put("LaunchTime", replica.path("LaunchTime").asDouble());
            item.put("StatusLastUpdatedDateTime", replica.path("StageLastUpdatedDateTime").asDouble());
            item.put("LastUpdatedDateTime", replica.path("StageLastUpdatedDateTime").asDouble());
            items.add(item);
        }
        return store.page(items, input, "RegionsDescription", 100);
    }

    // ---------------------------------------------------------------- sharing

    public synchronized ObjectNode shareDirectory(JsonNode input, String region) {
        ObjectNode directory = active(region, text(input, "DirectoryId"));
        if (!"MicrosoftAD".equals(directory.path("Type").asText())) {
            throw unsupported("Only Managed Microsoft AD directories can be shared.");
        }
        JsonNode target = input.path("ShareTarget");
        String accountId = text(target, "Id");
        if (!"ACCOUNT".equals(target.path("Type").asText())) {
            throw invalid("ShareTarget.Type must be ACCOUNT.");
        }
        if (!accountId.matches("^\\d{12}$") || accountId.equals(store.account())) {
            throw error("InvalidTargetException", "Account " + accountId + " is not a valid share target.");
        }
        String method = text(input, "ShareMethod");
        if (!Set.of("ORGANIZATIONS", "HANDSHAKE").contains(method)) {
            throw invalid("ShareMethod must be ORGANIZATIONS or HANDSHAKE.");
        }
        String directoryId = directory.path("DirectoryId").asText();
        for (ObjectNode share : store.list(region, SHARES, share -> directoryId.equals(share.path("OwnerDirectoryId")
                .asText()) && accountId.equals(share.path("SharedAccountId").asText()) && !Set.of("Rejected",
                "Deleted").contains(share.path("ShareStatus").asText()))) {
            throw error("DirectoryAlreadySharedException", "Directory " + directoryId + " is already shared with "
                    + accountId + ".");
        }
        if (store.list(region, SHARES, share -> directoryId.equals(share.path("OwnerDirectoryId").asText()))
                .size() >= 125) {
            throw error("ShareLimitExceededException", "A directory can be shared with at most 125 accounts.");
        }
        String sharedId = store.id("d");
        double now = now();
        ObjectNode share = store.record();
        share.put("OwnerAccountId", store.account());
        share.put("OwnerDirectoryId", directoryId);
        share.put("ShareMethod", method);
        share.put("SharedAccountId", accountId);
        share.put("SharedDirectoryId", sharedId);
        share.put("ShareStatus", "ORGANIZATIONS".equals(method) ? "Shared" : "PendingAcceptance");
        if (input.hasNonNull("ShareNotes")) {
            share.put("ShareNotes", input.get("ShareNotes").asText());
        }
        share.put("CreatedDateTime", now);
        share.put("LastUpdatedDateTime", now);
        store.put(region, SHARES, sharedId, share);
        store.putForAccount(accountId, region, INVITATIONS, sharedId, share.deepCopy());
        if ("ORGANIZATIONS".equals(method)) {
            accept(region, accountId, share);
        }
        return store.object().put("SharedDirectoryId", sharedId);
    }

    /** Creates the shared directory in the consumer account. */
    private void accept(String region, String accountId, ObjectNode share) {
        ObjectNode owner = store.get(region, DIRECTORIES, share.path("OwnerDirectoryId").asText())
                .or(() -> store.getForAccount(share.path("OwnerAccountId").asText(), region, DIRECTORIES,
                        share.path("OwnerDirectoryId").asText())).orElseThrow(() -> missing("Directory "
                        + share.path("OwnerDirectoryId").asText() + " does not exist."));
        ObjectNode shared = store.record();
        String id = share.path("SharedDirectoryId").asText();
        shared.put("DirectoryId", id);
        for (String field : List.of("Name", "ShortName", "Edition", "Description")) {
            if (owner.has(field)) {
                shared.set(field, owner.get(field).deepCopy());
            }
        }
        shared.put("Type", "SharedMicrosoftAD");
        shared.put("Stage", "Active");
        shared.put("ShareStatus", "Shared");
        shared.put("ShareMethod", share.path("ShareMethod").asText());
        if (share.has("ShareNotes")) {
            shared.put("ShareNotes", share.get("ShareNotes").asText());
        }
        shared.put("LaunchTime", now());
        shared.put("StageLastUpdatedDateTime", now());
        shared.put("SsoEnabled", false);
        shared.set("DnsIpAddrs", owner.path("DnsIpAddrs").deepCopy());
        ObjectNode ownerDescription = shared.putObject("OwnerDirectoryDescription");
        ownerDescription.put("DirectoryId", owner.path("DirectoryId").asText());
        ownerDescription.put("AccountId", share.path("OwnerAccountId").asText());
        ownerDescription.set("DnsIpAddrs", owner.path("DnsIpAddrs").deepCopy());
        ownerDescription.set("VpcSettings", owner.path("VpcSettings").deepCopy());
        ownerDescription.put("NetworkType", owner.path("NetworkType").asText("IPv4"));
        ObjectNode meta = (ObjectNode) shared.get("_");
        meta.put("ownerAccountId", share.path("OwnerAccountId").asText()).put("sharedDirectoryId", id);
        meta.putObject("tags");
        store.putForAccount(accountId, region, DIRECTORIES, id, shared);
        share.put("ShareStatus", "Shared");
        share.put("LastUpdatedDateTime", now());
        store.putForAccount(share.path("OwnerAccountId").asText(), region, SHARES, id, share);
        store.putForAccount(accountId, region, INVITATIONS, id, share.deepCopy());
    }

    public synchronized ObjectNode acceptSharedDirectory(JsonNode input, String region) {
        String id = text(input, "SharedDirectoryId");
        ObjectNode invitation = store.get(region, INVITATIONS, id).orElseThrow(() -> missing("Shared directory "
                + id + " does not exist."));
        if (!"PendingAcceptance".equals(invitation.path("ShareStatus").asText())) {
            throw error("DirectoryAlreadySharedException", "Shared directory " + id + " is "
                    + invitation.path("ShareStatus").asText() + ".");
        }
        accept(region, store.account(), invitation);
        ObjectNode response = store.object();
        response.set("SharedDirectory", store.view(store.get(region, INVITATIONS, id).orElse(invitation)));
        return response;
    }

    public synchronized ObjectNode rejectSharedDirectory(JsonNode input, String region) {
        String id = text(input, "SharedDirectoryId");
        ObjectNode invitation = store.get(region, INVITATIONS, id).orElseThrow(() -> missing("Shared directory "
                + id + " does not exist."));
        if (!"PendingAcceptance".equals(invitation.path("ShareStatus").asText())) {
            throw error("DirectoryAlreadySharedException", "Shared directory " + id + " is "
                    + invitation.path("ShareStatus").asText() + ".");
        }
        invitation.put("ShareStatus", "Rejected");
        invitation.put("LastUpdatedDateTime", now());
        store.put(region, INVITATIONS, id, invitation);
        store.putForAccount(invitation.path("OwnerAccountId").asText(), region, SHARES, id, invitation.deepCopy());
        return store.object().put("SharedDirectoryId", id);
    }

    public synchronized ObjectNode unshareDirectory(JsonNode input, String region) {
        ObjectNode directory = directory(region, text(input, "DirectoryId"));
        JsonNode target = input.path("UnshareTarget");
        String accountId = text(target, "Id");
        String directoryId = directory.path("DirectoryId").asText();
        List<ObjectNode> shares = store.list(region, SHARES, share -> directoryId.equals(share
                .path("OwnerDirectoryId").asText()) && accountId.equals(share.path("SharedAccountId").asText())
                && !Set.of("Rejected", "Deleted").contains(share.path("ShareStatus").asText()));
        if (shares.isEmpty()) {
            throw error("DirectoryNotSharedException", "Directory " + directoryId + " is not shared with "
                    + accountId + ".");
        }
        ObjectNode share = shares.get(0);
        String sharedId = share.path("SharedDirectoryId").asText();
        share.put("ShareStatus", "Deleted");
        share.put("LastUpdatedDateTime", now());
        store.put(region, SHARES, sharedId, share);
        store.deleteForAccount(accountId, region, DIRECTORIES, sharedId);
        store.putForAccount(accountId, region, INVITATIONS, sharedId, share.deepCopy());
        return store.object().put("SharedDirectoryId", sharedId);
    }

    public synchronized ObjectNode describeSharedDirectories(JsonNode input, String region) {
        String owner = text(input, "OwnerDirectoryId");
        directory(region, owner);
        List<String> ids = strings(input.path("SharedDirectoryIds"));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode share : store.list(region, SHARES, share -> owner.equals(share.path("OwnerDirectoryId")
                .asText()) && (ids.isEmpty() || ids.contains(share.path("SharedDirectoryId").asText())))) {
            items.add(store.view(share));
        }
        return store.page(items, input, "SharedDirectories", 100);
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode tagged(String region, String id) {
        return directory(region, id);
    }

    public synchronized ObjectNode addTagsToResource(JsonNode input, String region) {
        ObjectNode directory = tagged(region, text(input, "ResourceId"));
        ObjectNode tags = ((ObjectNode) directory.get("_")).withObject("tags");
        Map<String, String> merged = new LinkedHashMap<>();
        tags.properties().forEach(entry -> merged.put(entry.getKey(), entry.getValue().asText()));
        for (JsonNode tag : input.path("Tags")) {
            String key = text(tag, "Key");
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw invalid("Tag keys cannot begin with aws:.");
            }
            merged.put(key, tag.path("Value").asText(""));
        }
        if (merged.size() > 50) {
            throw error("TagLimitExceededException", "A directory can have at most 50 tags.");
        }
        merged.forEach(tags::put);
        save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode removeTagsFromResource(JsonNode input, String region) {
        ObjectNode directory = tagged(region, text(input, "ResourceId"));
        ObjectNode tags = ((ObjectNode) directory.get("_")).withObject("tags");
        input.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode listTagsForResource(JsonNode input, String region) {
        ObjectNode directory = tagged(region, text(input, "ResourceId"));
        List<ObjectNode> items = new ArrayList<>();
        directory.path("_").path("tags").properties().forEach(entry -> items.add(store.object()
                .put("Key", entry.getKey()).put("Value", entry.getValue().asText())));
        return store.page(items, input, "Tags", 50);
    }

    /** Every account's directories in a region, for features that look across accounts. */
    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> allDirectories(String region) {
        return store.listAll(region, DIRECTORIES);
    }

    static Set<String> set(JsonNode list) {
        return new LinkedHashSet<>(strings(list));
    }
}

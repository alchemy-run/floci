package io.floci.aws.services.licensemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * License configurations and what's counted against them: resources associated through their license
 * specifications (an AMI's configurations also count the instances launched from it), usage computed by the counting
 * type from each instance's type, a hard limit that refuses an association past the count (recorded as a failure),
 * the resource inventory of the Region's instances, service settings, report generators that write CSV reports to the
 * settings' bucket, license type conversions, and tags on every License Manager resource.
 */
@ApplicationScoped
public class LicenseConfigurations {

    private static final Pattern RULE = Pattern.compile("^#([A-Za-z]+)=(.+)$");
    private static final Map<String, Set<String>> RULES = Map.of(
            "vCPU", Set.of("minimumVcpus", "maximumVcpus", "honorVcpuOptimization", "allowedTenancy"),
            "Core", Set.of("minimumCores", "maximumCores", "allowedTenancy", "licenseAffinityToHost"),
            "Socket", Set.of("minimumSockets", "maximumSockets", "allowedTenancy", "licenseAffinityToHost"),
            "Instance", Set.of("allowedTenancy", "licenseAffinityToHost"));
    private static final Set<String> TENANCIES = Set.of("EC2-Default", "EC2-DedicatedHost", "EC2-DedicatedInstance");
    private static final Map<String, Integer> SIZE_VCPUS = Map.ofEntries(Map.entry("nano", 2),
            Map.entry("micro", 2), Map.entry("small", 2), Map.entry("medium", 2), Map.entry("large", 2),
            Map.entry("xlarge", 4), Map.entry("metal", 96));

    private final LicenseManagerStore store;
    private final ObjectMapper mapper;
    private final Provider<Ec2Service> ec2;
    private final Provider<S3Service> s3;

    @Inject
    public LicenseConfigurations(LicenseManagerStore store, Provider<Ec2Service> ec2,
                                 Provider<S3Service> s3) {
        this.store = store;
        this.mapper = store.mapper();
        this.ec2 = ec2;
        this.s3 = s3;
    }

    // ================================================================ configurations

    public ObjectNode createLicenseConfiguration(JsonNode in, String region) {
        String type = in.path("LicenseCountingType").asText();
        checkRules(type, in.path("LicenseRules"));
        checkProducts(in.path("ProductInformationList"));
        if (in.hasNonNull("LicenseCount") && in.get("LicenseCount").asLong() < 0) {
            throw LicenseManagerStore.invalid("LicenseCount can't be negative.");
        }
        if (in.path("LicenseCountHardLimit").asBoolean(false) && !in.hasNonNull("LicenseCount")) {
            throw LicenseManagerStore.invalid("A hard limit needs a LicenseCount.");
        }
        if (store.list(region, LicenseManagerStore.CONFIGURATION).size() >= store.config().maxLicenseConfigurations()) {
            throw LicenseManagerStore.limit("The account has the maximum number of license configurations.");
        }
        String id = "lic-" + LicenseManagerStore.hex(16);
        ObjectNode record = store.record();
        record.put("LicenseConfigurationId", id);
        record.put("LicenseConfigurationArn", store.regionalArn(region, "license-configuration:" + id));
        copy(in, record, "Name", "Description", "LicenseCountingType", "LicenseRules", "LicenseCount",
                "DisassociateWhenNotFound", "ProductInformationList", "LicenseExpiry");
        record.put("LicenseCountHardLimit", in.path("LicenseCountHardLimit").asBoolean(false));
        record.put("Status", "AVAILABLE");
        record.put("OwnerAccountId", store.account());
        if (in.path("Tags").isArray()) {
            LicenseManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, LicenseManagerStore.CONFIGURATION, id, record);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseConfigurationArn", record.path("LicenseConfigurationArn").asText());
        return out;
    }

    private static void checkRules(String type, JsonNode rules) {
        Set<String> allowed = RULES.get(type);
        for (JsonNode rule : rules) {
            Matcher matcher = RULE.matcher(rule.asText());
            if (!matcher.matches()) {
                throw LicenseManagerStore.invalid("The license rule " + rule.asText() + " must be #name=value.");
            }
            String name = matcher.group(1);
            String value = matcher.group(2);
            if (allowed == null || !allowed.contains(name)) {
                throw LicenseManagerStore.invalid("The license rule " + name + " doesn't apply to " + type
                        + " counting.");
            }
            if ("allowedTenancy".equals(name)) {
                for (String tenancy : value.split(",")) {
                    if (!TENANCIES.contains(tenancy.trim())) {
                        throw LicenseManagerStore.invalid("The tenancy " + tenancy + " is not one of " + TENANCIES
                                + ".");
                    }
                }
            } else if ("honorVcpuOptimization".equals(name)) {
                if (!"True".equalsIgnoreCase(value) && !"False".equalsIgnoreCase(value)) {
                    throw LicenseManagerStore.invalid("honorVcpuOptimization is True or False.");
                }
            } else if (!value.matches("\\d+")) {
                throw LicenseManagerStore.invalid("The license rule " + name + " needs a whole number.");
            }
        }
    }

    private static void checkProducts(JsonNode products) {
        for (JsonNode product : products) {
            String type = product.path("ResourceType").asText();
            if (!Set.of("SSM_MANAGED", "RDS").contains(type)) {
                throw LicenseManagerStore.invalid("Product information applies to SSM_MANAGED or RDS resources, not "
                        + type + ".");
            }
            for (JsonNode filter : product.path("ProductInformationFilterList")) {
                String comparator = filter.path("ProductInformationFilterComparator").asText();
                if (!Set.of("EQUALS", "NOT_EQUALS").contains(comparator)) {
                    throw LicenseManagerStore.invalid("The comparator " + comparator + " is not EQUALS or "
                            + "NOT_EQUALS.");
                }
            }
        }
    }

    /** A license configuration by ARN; {@code InvalidParameterValueException} as AWS reports a missing one. */
    ObjectNode configuration(String arn) {
        Optional<AwsArnUtils.Arn> parsed = LicenseManagerStore.parse(arn);
        String resource = parsed.map(AwsArnUtils.Arn::resource).orElse("");
        if (parsed.isEmpty() || !resource.startsWith("license-configuration:")) {
            throw LicenseManagerStore.invalid("Invalid license configuration ARN: " + arn);
        }
        String id = resource.substring("license-configuration:".length());
        return store.getForAccount(parsed.get().accountId(), parsed.get().region(),
                        LicenseManagerStore.CONFIGURATION, id)
                .orElseThrow(() -> LicenseManagerStore.invalid("Invalid license configuration ARN: " + arn));
    }

    private ObjectNode ownedConfiguration(String arn) {
        ObjectNode record = configuration(arn);
        if (!store.account().equals(record.path("OwnerAccountId").asText())) {
            throw LicenseManagerStore.authorization("The license configuration " + arn + " belongs to another "
                    + "account.");
        }
        return record;
    }

    public ObjectNode getLicenseConfiguration(JsonNode in, String region) {
        ObjectNode record = configuration(in.path("LicenseConfigurationArn").asText());
        ObjectNode out = summary(record);
        out.set("Tags", store.tagList(record));
        return out;
    }

    /** A configuration with its consumption and managed-resource summaries. */
    private ObjectNode summary(ObjectNode record) {
        ObjectNode out = LicenseManagerStore.view(record);
        List<ObjectNode> usage = usage(record);
        long consumed = 0;
        Map<String, Long> byType = new TreeMap<>();
        for (ObjectNode use : usage) {
            consumed += use.path("ConsumedLicenses").asLong();
            byType.merge(use.path("ResourceType").asText(), use.path("ConsumedLicenses").asLong(), Long::sum);
        }
        out.put("ConsumedLicenses", consumed);
        ArrayNode consumedList = out.putArray("ConsumedLicenseSummaryList");
        byType.forEach((type, count) -> consumedList.addObject().put("ResourceType", type)
                .put("ConsumedLicenses", count));
        Map<String, Long> managed = new TreeMap<>();
        for (ObjectNode association : associations(record)) {
            managed.merge(association.path("ResourceType").asText(), 1L, Long::sum);
        }
        ArrayNode managedList = out.putArray("ManagedResourceSummaryList");
        managed.forEach((type, count) -> managedList.addObject().put("ResourceType", type)
                .put("AssociationCount", count));
        if (record.path("ProductInformationList").size() > 0) {
            out.putObject("AutomatedDiscoveryInformation").put("LastRunTime", LicenseManagerStore.epoch());
        }
        return out;
    }

    public ObjectNode updateLicenseConfiguration(JsonNode in, String region) {
        ObjectNode record = ownedConfiguration(in.path("LicenseConfigurationArn").asText());
        if (in.has("LicenseRules")) {
            checkRules(record.path("LicenseCountingType").asText(), in.path("LicenseRules"));
            record.set("LicenseRules", in.get("LicenseRules").deepCopy());
        }
        if (in.has("ProductInformationList")) {
            checkProducts(in.path("ProductInformationList"));
            record.set("ProductInformationList", in.get("ProductInformationList").deepCopy());
        }
        copy(in, record, "LicenseCount", "LicenseCountHardLimit", "Name", "Description", "DisassociateWhenNotFound",
                "LicenseExpiry");
        if (in.hasNonNull("LicenseConfigurationStatus")) {
            record.put("Status", in.get("LicenseConfigurationStatus").asText());
        }
        if (record.path("LicenseCountHardLimit").asBoolean(false) && !record.hasNonNull("LicenseCount")) {
            throw LicenseManagerStore.invalid("A hard limit needs a LicenseCount.");
        }
        save(record);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteLicenseConfiguration(JsonNode in, String region) {
        ObjectNode record = ownedConfiguration(in.path("LicenseConfigurationArn").asText());
        String arn = record.path("LicenseConfigurationArn").asText();
        for (ObjectNode association : associations(record)) {
            store.delete(regionOf(record), LicenseManagerStore.ASSOCIATION,
                    association.path("_").path("id").asText());
        }
        AwsArnUtils.Arn parsed = LicenseManagerStore.parse(arn).orElseThrow();
        store.delete(parsed.region(), LicenseManagerStore.CONFIGURATION, record.path("LicenseConfigurationId")
                .asText());
        for (ObjectNode failure : store.list(parsed.region(), LicenseManagerStore.FAILURE)) {
            if (arn.equals(failure.path("_").path("configuration").asText())) {
                store.delete(parsed.region(), LicenseManagerStore.FAILURE, failure.path("_").path("id").asText());
            }
        }
        return mapper.createObjectNode();
    }

    private void save(ObjectNode record) {
        store.put(regionOf(record), LicenseManagerStore.CONFIGURATION, record.path("LicenseConfigurationId")
                .asText(), record);
    }

    private static String regionOf(ObjectNode configuration) {
        return LicenseManagerStore.parse(configuration.path("LicenseConfigurationArn").asText()).orElseThrow()
                .region();
    }

    public ObjectNode listLicenseConfigurations(JsonNode in, String region) {
        return listConfigurations(in, store.list(region, LicenseManagerStore.CONFIGURATION));
    }

    public ObjectNode listLicenseConfigurationsForOrganization(JsonNode in, String region) {
        requireOrganization(region);
        List<ObjectNode> all = new ArrayList<>();
        for (ObjectNode record : store.everywhere(region, LicenseManagerStore.CONFIGURATION)) {
            all.add(record);
        }
        return listConfigurations(in, all);
    }

    private void requireOrganization(String region) {
        if (!settings(region).path("OrganizationConfiguration").path("EnableIntegration").asBoolean(false)) {
            throw new AwsException("AccessDeniedException", "Turn on the AWS Organizations integration in the "
                    + "License Manager settings first.", 400);
        }
    }

    private ObjectNode listConfigurations(JsonNode in, List<ObjectNode> records) {
        List<ObjectNode> items = new ArrayList<>();
        Map<String, Function<JsonNode, String>> fields = Map.of(
                "licenseCountingType", r -> r.path("LicenseCountingType").asText(),
                "enforceLicenseCount", r -> String.valueOf(r.path("LicenseCountHardLimit").asBoolean(false)),
                "usagelimitExceeded", r -> String.valueOf(r.hasNonNull("LicenseCount")
                        && r.path("ConsumedLicenses").asLong() > r.path("LicenseCount").asLong()));
        for (ObjectNode record : records) {
            if (in.path("LicenseConfigurationArns").size() > 0 && !LicenseManagerStore.any(
                    in.path("LicenseConfigurationArns"), arn -> arn.equals(record.path("LicenseConfigurationArn")
                            .asText()))) {
                continue;
            }
            ObjectNode summary = summary(record);
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, summary)) {
                items.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "LicenseConfigurations");
        return out;
    }

    // ================================================================ associations

    private static String resourceType(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (RuntimeException e) {
            throw LicenseManagerStore.invalid("The resource ARN " + arn + " is not valid.");
        }
        String resource = parsed.resource();
        if ("ec2".equals(parsed.service())) {
            if (resource.startsWith("instance/i-")) {
                return "EC2_INSTANCE";
            }
            if (resource.startsWith("image/ami-")) {
                return "EC2_AMI";
            }
            if (resource.startsWith("dedicated-host/h-")) {
                return "EC2_HOST";
            }
        }
        if ("rds".equals(parsed.service()) && resource.startsWith("db:")) {
            return "RDS";
        }
        if ("ssm".equals(parsed.service()) && resource.startsWith("managed-instance/")) {
            return "SYSTEMS_MANAGER_MANAGED_INSTANCE";
        }
        throw LicenseManagerStore.invalid("The resource " + arn + " can't have license specifications.");
    }

    public ObjectNode updateLicenseSpecificationsForResource(JsonNode in, String region) {
        String resourceArn = in.path("ResourceArn").asText();
        String type = resourceType(resourceArn);
        for (JsonNode spec : in.path("RemoveLicenseSpecifications")) {
            ObjectNode record = configuration(spec.path("LicenseConfigurationArn").asText());
            for (ObjectNode association : associations(record)) {
                if (resourceArn.equals(association.path("ResourceArn").asText())) {
                    store.delete(regionOf(record), LicenseManagerStore.ASSOCIATION,
                            association.path("_").path("id").asText());
                }
            }
        }
        for (JsonNode spec : in.path("AddLicenseSpecifications")) {
            String configurationArn = spec.path("LicenseConfigurationArn").asText();
            ObjectNode record = configuration(configurationArn);
            if (!"AVAILABLE".equals(record.path("Status").asText())) {
                throw LicenseManagerStore.invalid("The license configuration " + configurationArn + " is "
                        + record.path("Status").asText() + ".");
            }
            boolean present = false;
            for (ObjectNode association : associations(record)) {
                present |= resourceArn.equals(association.path("ResourceArn").asText());
            }
            if (present) {
                continue;
            }
            long adding = "EC2_AMI".equals(type) ? 0 : consumption(record, resourceArn, type);
            long consumed = 0;
            for (ObjectNode use : usage(record)) {
                consumed += use.path("ConsumedLicenses").asLong();
            }
            if (record.path("LicenseCountHardLimit").asBoolean(false) && record.hasNonNull("LicenseCount")
                    && consumed + adding > record.path("LicenseCount").asLong()) {
                fail(record, resourceArn, type, "The license count hard limit of "
                        + record.path("LicenseCount").asLong() + " would be exceeded.");
                throw new AwsException("LicenseUsageException", "The license configuration "
                        + configurationArn + " has reached its hard limit of " + record.path("LicenseCount").asLong()
                        + " licenses.", 400);
            }
            ObjectNode association = store.record();
            association.put("ResourceArn", resourceArn);
            association.put("ResourceType", type);
            association.put("ResourceOwnerId", ownerOf(resourceArn));
            association.put("AssociationTime", LicenseManagerStore.epoch());
            if (spec.hasNonNull("AmiAssociationScope")) {
                association.put("AmiAssociationScope", spec.get("AmiAssociationScope").asText());
            }
            LicenseManagerStore.meta(association).put("configuration", configurationArn);
            store.put(regionOf(record), LicenseManagerStore.ASSOCIATION, LicenseManagerStore.hex(12), association);
        }
        return mapper.createObjectNode();
    }

    private String ownerOf(String arn) {
        try {
            String account = AwsArnUtils.parse(arn).accountId();
            return account == null || account.isEmpty() ? store.account() : account;
        } catch (RuntimeException e) {
            return store.account();
        }
    }

    private void fail(ObjectNode configuration, String resourceArn, String type, String message) {
        ObjectNode failure = store.record();
        failure.put("ResourceArn", resourceArn);
        failure.put("ResourceType", type);
        failure.put("ErrorMessage", message);
        failure.put("FailureTime", LicenseManagerStore.epoch());
        failure.put("OperationName", "UpdateLicenseSpecificationsForResource");
        failure.put("ResourceOwnerId", ownerOf(resourceArn));
        failure.put("OperationRequestedBy", store.account());
        failure.putArray("MetadataList");
        LicenseManagerStore.meta(failure).put("configuration", configuration.path("LicenseConfigurationArn")
                .asText());
        store.put(regionOf(configuration), LicenseManagerStore.FAILURE, LicenseManagerStore.hex(12), failure);
    }

    private List<ObjectNode> associations(ObjectNode configuration) {
        String arn = configuration.path("LicenseConfigurationArn").asText();
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode association : store.everywhere(regionOf(configuration), LicenseManagerStore.ASSOCIATION)) {
            if (arn.equals(association.path("_").path("configuration").asText())) {
                found.add(association);
            }
        }
        return found;
    }

    public ObjectNode listLicenseSpecificationsForResource(JsonNode in, String region) {
        String resourceArn = in.path("ResourceArn").asText();
        resourceType(resourceArn);
        List<ObjectNode> specs = new ArrayList<>();
        for (ObjectNode association : store.list(region, LicenseManagerStore.ASSOCIATION)) {
            if (resourceArn.equals(association.path("ResourceArn").asText())) {
                ObjectNode spec = mapper.createObjectNode();
                spec.put("LicenseConfigurationArn", association.path("_").path("configuration").asText());
                if (association.has("AmiAssociationScope")) {
                    spec.put("AmiAssociationScope", association.path("AmiAssociationScope").asText());
                }
                specs.add(spec);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(specs, in, out, "LicenseSpecifications");
        return out;
    }

    public ObjectNode listAssociationsForLicenseConfiguration(JsonNode in, String region) {
        ObjectNode record = configuration(in.path("LicenseConfigurationArn").asText());
        List<ObjectNode> items = new ArrayList<>();
        associations(record).forEach(a -> items.add(LicenseManagerStore.view(a)));
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "LicenseConfigurationAssociations");
        return out;
    }

    public ObjectNode listUsageForLicenseConfiguration(JsonNode in, String region) {
        ObjectNode record = configuration(in.path("LicenseConfigurationArn").asText());
        List<ObjectNode> items = new ArrayList<>();
        Map<String, Function<JsonNode, String>> fields = Map.of(
                "resourceArn", u -> u.path("ResourceArn").asText(),
                "resourceType", u -> u.path("ResourceType").asText(),
                "resourceAccount", u -> u.path("ResourceOwnerId").asText());
        for (ObjectNode use : usage(record)) {
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, use)) {
                items.add(use);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "LicenseConfigurationUsageList");
        return out;
    }

    public ObjectNode listFailuresForLicenseConfigurationOperations(JsonNode in, String region) {
        ObjectNode record = configuration(in.path("LicenseConfigurationArn").asText());
        String arn = record.path("LicenseConfigurationArn").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode failure : store.list(regionOf(record), LicenseManagerStore.FAILURE)) {
            if (arn.equals(failure.path("_").path("configuration").asText())) {
                items.add(LicenseManagerStore.view(failure));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "LicenseOperationFailureList");
        return out;
    }

    /**
     * What a configuration's associated resources consume: each instance, host or database by the counting type, and
     * for each associated AMI the running instances launched from it.
     */
    private List<ObjectNode> usage(ObjectNode configuration) {
        List<ObjectNode> usage = new ArrayList<>();
        List<String> counted = new ArrayList<>();
        for (ObjectNode association : associations(configuration)) {
            String arn = association.path("ResourceArn").asText();
            String type = association.path("ResourceType").asText();
            if ("EC2_AMI".equals(type)) {
                String amiId = arn.substring(arn.lastIndexOf('/') + 1);
                for (Instance instance : instances(regionOf(configuration))) {
                    String instanceArn = instanceArn(regionOf(configuration), instance);
                    if (amiId.equals(instance.getImageId()) && running(instance) && !counted.contains(instanceArn)) {
                        counted.add(instanceArn);
                        usage.add(use(instanceArn, "EC2_INSTANCE", "running", association.path("AssociationTime")
                                .asDouble(), count(configuration, vcpus(instance.getInstanceType())), store.account()));
                    }
                }
                continue;
            }
            if (counted.contains(arn)) {
                continue;
            }
            counted.add(arn);
            String status = "EC2_INSTANCE".equals(type) ? instance(regionOf(configuration), arn)
                    .map(i -> i.getState() == null ? "running" : i.getState().getName()).orElse("terminated")
                    : "available";
            long consumed = "terminated".equals(status) || "stopped".equals(status) ? 0
                    : consumption(configuration, arn, type);
            usage.add(use(arn, type, status, association.path("AssociationTime").asDouble(), consumed,
                    association.path("ResourceOwnerId").asText()));
        }
        return usage;
    }

    private ObjectNode use(String arn, String type, String status, double time, long consumed, String owner) {
        ObjectNode use = mapper.createObjectNode();
        use.put("ResourceArn", arn);
        use.put("ResourceType", type);
        use.put("ResourceStatus", status);
        use.put("ResourceOwnerId", owner);
        use.put("AssociationTime", time);
        use.put("ConsumedLicenses", consumed);
        return use;
    }

    /** The licenses one resource consumes under a configuration's counting type. */
    private long consumption(ObjectNode configuration, String arn, String type) {
        String counting = configuration.path("LicenseCountingType").asText();
        if ("Instance".equals(counting) || "Socket".equals(counting)) {
            return 1;
        }
        int vcpus = "EC2_INSTANCE".equals(type) ? instance(regionOf(configuration), arn)
                .map(i -> vcpus(i.getInstanceType())).orElse(2) : 2;
        return "Core".equals(counting) ? Math.max(1, vcpus / 2) : vcpus;
    }

    private long count(ObjectNode configuration, int vcpus) {
        String counting = configuration.path("LicenseCountingType").asText();
        if ("Instance".equals(counting) || "Socket".equals(counting)) {
            return 1;
        }
        return "Core".equals(counting) ? Math.max(1, vcpus / 2) : vcpus;
    }

    /** An instance type's vCPUs: 2 up to large, then 4 per xlarge multiple. */
    static int vcpus(String instanceType) {
        if (instanceType == null || !instanceType.contains(".")) {
            return 2;
        }
        String size = instanceType.substring(instanceType.indexOf('.') + 1);
        Matcher multiple = Pattern.compile("^(\\d+)xlarge$").matcher(size);
        if (multiple.matches()) {
            return 4 * Integer.parseInt(multiple.group(1));
        }
        return SIZE_VCPUS.getOrDefault(size, 2);
    }

    private static boolean running(Instance instance) {
        String state = instance.getState() == null ? "running" : instance.getState().getName();
        return "running".equals(state) || "pending".equals(state);
    }

    private List<Instance> instances(String region) {
        List<Instance> found = new ArrayList<>();
        try {
            for (Reservation reservation : ec2.get().describeInstances(region, null, Map.of())) {
                found.addAll(reservation.getInstances());
            }
        } catch (RuntimeException e) {
            // EC2 has no instances to count here
        }
        return found;
    }

    private Optional<Instance> instance(String region, String arn) {
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        for (Instance instance : instances(region)) {
            if (id.equals(instance.getInstanceId())) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    private String instanceArn(String region, Instance instance) {
        return AwsArnUtils.Arn.of("ec2", region, store.account(), "instance/" + instance.getInstanceId()).toString();
    }

    // ================================================================ inventory

    public ObjectNode listResourceInventory(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (Instance instance : instances(region)) {
            if (!running(instance)) {
                continue;
            }
            ObjectNode item = mapper.createObjectNode();
            item.put("ResourceId", instance.getInstanceId());
            item.put("ResourceType", "EC2_INSTANCE");
            item.put("ResourceArn", instanceArn(region, instance));
            item.put("Platform", "Linux/UNIX");
            item.put("PlatformVersion", "");
            item.put("ResourceOwningAccountId", store.account());
            item.putArray("MarketplaceProductCodes");
            item.put("UsageOperation", "RunInstances");
            item.put("AmiId", instance.getImageId());
            item.put("Region", region);
            item.put("InstanceType", instance.getInstanceType());
            if (inventoryMatches(in.path("Filters"), item)) {
                items.add(item);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "ResourceInventoryList");
        return out;
    }

    private static boolean inventoryMatches(JsonNode filters, ObjectNode item) {
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText();
            String value = switch (name) {
                case "account_id" -> item.path("ResourceOwningAccountId").asText();
                case "platform" -> item.path("Platform").asText();
                case "resource_id" -> item.path("ResourceId").asText();
                case "application_name", "license_included" -> "";
                default -> {
                    if (name.startsWith("tag:")) {
                        yield "";
                    }
                    throw LicenseManagerStore.invalid("The inventory filter " + name + " is not supported.");
                }
            };
            String wanted = filter.path("Value").asText("");
            boolean match = switch (filter.path("Condition").asText()) {
                case "NOT_EQUALS" -> !value.equalsIgnoreCase(wanted);
                case "BEGINS_WITH" -> value.toLowerCase(Locale.ROOT).startsWith(wanted.toLowerCase(Locale.ROOT));
                case "CONTAINS" -> value.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
                default -> value.equalsIgnoreCase(wanted);
            };
            if (!match) {
                return false;
            }
        }
        return true;
    }

    // ================================================================ settings

    ObjectNode settings(String region) {
        return store.get(region, LicenseManagerStore.SETTINGS, "settings").orElseGet(() -> {
            ObjectNode settings = store.record();
            settings.putObject("OrganizationConfiguration").put("EnableIntegration", false);
            settings.put("EnableCrossAccountsDiscovery", false);
            return settings;
        });
    }

    public ObjectNode getServiceSettings(JsonNode in, String region) {
        ObjectNode out = LicenseManagerStore.view(settings(region));
        ObjectNode status = out.putObject("ServiceStatus");
        status.putObject("CrossAccountDiscovery").put("Message", out.path("EnableCrossAccountsDiscovery")
                .asBoolean(false) ? "Cross-account discovery is enabled." : "Cross-account discovery is disabled.");
        ObjectNode regions = status.putObject("CrossRegionDiscovery").putObject("Message");
        out.path("CrossRegionDiscoverySourceRegions").forEach(r -> regions.putObject(r.asText()).put("Status",
                "ENABLED"));
        return out;
    }

    public ObjectNode updateServiceSettings(JsonNode in, String region) {
        ObjectNode settings = settings(region);
        if (in.hasNonNull("S3BucketArn")) {
            String arn = in.get("S3BucketArn").asText();
            if (!AwsArnUtils.isArnFor(arn, "s3")) {
                throw LicenseManagerStore.invalid("S3BucketArn must be an S3 bucket ARN.");
            }
            settings.put("S3BucketArn", arn);
        }
        if (in.hasNonNull("SnsTopicArn")) {
            String arn = in.get("SnsTopicArn").asText();
            if (!AwsArnUtils.isArnFor(arn, "sns")) {
                throw LicenseManagerStore.invalid("SnsTopicArn must be an SNS topic ARN.");
            }
            settings.put("SnsTopicArn", arn);
        }
        if (in.has("OrganizationConfiguration")) {
            settings.set("OrganizationConfiguration", in.get("OrganizationConfiguration").deepCopy());
            if (in.path("OrganizationConfiguration").path("EnableIntegration").asBoolean(false)) {
                settings.put("LicenseManagerResourceShareArn", AwsArnUtils.Arn.of("ram", region, store.account(),
                        "resource-share/" + UUID.randomUUID()).toString());
            } else {
                settings.remove("LicenseManagerResourceShareArn");
            }
        }
        if (in.hasNonNull("EnableCrossAccountsDiscovery")) {
            if (in.get("EnableCrossAccountsDiscovery").asBoolean() && !settings.path("OrganizationConfiguration")
                    .path("EnableIntegration").asBoolean(false)) {
                throw LicenseManagerStore.invalid("Cross-account discovery needs the Organizations integration.");
            }
            settings.put("EnableCrossAccountsDiscovery", in.get("EnableCrossAccountsDiscovery").asBoolean());
        }
        if (in.has("EnabledDiscoverySourceRegions")) {
            settings.put("CrossRegionDiscoveryHomeRegion", region);
            settings.set("CrossRegionDiscoverySourceRegions", in.get("EnabledDiscoverySourceRegions").deepCopy());
        }
        store.put(region, LicenseManagerStore.SETTINGS, "settings", settings);
        return mapper.createObjectNode();
    }

    // ================================================================ report generators

    public ObjectNode createLicenseManagerReportGenerator(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateLicenseManagerReportGenerator", in);
        if (repeated != null) {
            return repeated;
        }
        checkReport(in, region);
        String id = "r-" + LicenseManagerStore.hex(16);
        ObjectNode record = store.record();
        record.put("ReportGeneratorName", in.path("ReportGeneratorName").asText());
        record.set("ReportType", in.path("Type").deepCopy());
        record.set("ReportContext", in.path("ReportContext").deepCopy());
        record.set("ReportFrequency", in.path("ReportFrequency").deepCopy());
        record.put("LicenseManagerReportGeneratorArn", store.regionalArn(region, "report-generator:" + id));
        record.put("ReportCreatorAccount", store.account());
        copy(in, record, "Description");
        record.put("CreateTime", String.valueOf((long) LicenseManagerStore.epoch()));
        if (in.path("Tags").isArray()) {
            LicenseManagerStore.addTags(record, in.get("Tags"));
        }
        generate(record, region);
        store.put(region, LicenseManagerStore.REPORT_GENERATOR, id, record);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseManagerReportGeneratorArn", record.path("LicenseManagerReportGeneratorArn").asText());
        store.remember("CreateLicenseManagerReportGenerator", in, out);
        return out;
    }

    private void checkReport(JsonNode in, String region) {
        JsonNode context = in.path("ReportContext");
        for (JsonNode arn : context.path("licenseConfigurationArns")) {
            configuration(arn.asText());
        }
        for (JsonNode type : in.path("Type")) {
            if ("LicenseAssetGroupUsageReport".equals(type.asText()) && context.path("licenseAssetGroupArns")
                    .isEmpty()) {
                throw LicenseManagerStore.invalid("A LicenseAssetGroupUsageReport needs licenseAssetGroupArns.");
            }
            if (!"LicenseAssetGroupUsageReport".equals(type.asText()) && context.path("licenseConfigurationArns")
                    .isEmpty()) {
                throw LicenseManagerStore.invalid("A " + type.asText() + " needs licenseConfigurationArns.");
            }
        }
        if (in.path("ReportFrequency").path("value").asInt(1) < 1) {
            throw LicenseManagerStore.invalid("The report frequency value must be at least 1.");
        }
    }

    /** Writes the generator's reports as CSV to the service settings' bucket, and records how the run went. */
    private void generate(ObjectNode generator, String region) {
        String bucketArn = settings(region).path("S3BucketArn").asText(null);
        String time = String.valueOf((long) LicenseManagerStore.epoch());
        generator.put("LastReportGenerationTime", time);
        if (bucketArn == null) {
            generator.put("LastRunStatus", "FAILED");
            generator.put("LastRunFailureReason", "Set an S3 bucket in the License Manager service settings.");
            return;
        }
        String bucket = bucketArn.substring(bucketArn.lastIndexOf(':') + 1);
        String prefix = "license-manager-reports/" + generator.path("ReportGeneratorName").asText() + "/";
        generator.putObject("S3Location").put("bucket", bucket).put("keyPrefix", prefix);
        try {
            for (JsonNode type : generator.path("ReportType")) {
                StringBuilder csv = new StringBuilder();
                switch (type.asText()) {
                    case "LicenseConfigurationSummaryReport" -> {
                        csv.append("LicenseConfigurationArn,Name,LicenseCountingType,LicenseCount,"
                                + "LicenseCountHardLimit,ConsumedLicenses\n");
                        for (JsonNode arn : generator.path("ReportContext").path("licenseConfigurationArns")) {
                            ObjectNode summary = summary(configuration(arn.asText()));
                            csv.append(String.join(",", summary.path("LicenseConfigurationArn").asText(),
                                    quote(summary.path("Name").asText()), summary.path("LicenseCountingType").asText(),
                                    summary.path("LicenseCount").asText(""),
                                    summary.path("LicenseCountHardLimit").asText(),
                                    summary.path("ConsumedLicenses").asText())).append('\n');
                        }
                    }
                    case "LicenseConfigurationUsageReport" -> {
                        csv.append("LicenseConfigurationArn,ResourceArn,ResourceType,ResourceStatus,ConsumedLicenses\n");
                        for (JsonNode arn : generator.path("ReportContext").path("licenseConfigurationArns")) {
                            for (ObjectNode use : usage(configuration(arn.asText()))) {
                                csv.append(String.join(",", arn.asText(), use.path("ResourceArn").asText(),
                                        use.path("ResourceType").asText(), use.path("ResourceStatus").asText(),
                                        use.path("ConsumedLicenses").asText())).append('\n');
                            }
                        }
                    }
                    default -> {
                        csv.append("LicenseAssetGroupArn,AssetArn\n");
                        for (JsonNode arn : generator.path("ReportContext").path("licenseAssetGroupArns")) {
                            csv.append(arn.asText()).append(",\n");
                        }
                    }
                }
                s3.get().putObject(bucket, prefix + type.asText() + "-" + Instant.now().toString() + ".csv",
                        csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
            }
            generator.put("LastRunStatus", "SUCCESS");
            generator.remove("LastRunFailureReason");
        } catch (AwsException e) {
            generator.put("LastRunStatus", "FAILED");
            generator.put("LastRunFailureReason", "The reports could not be written to s3://" + bucket + ": "
                    + e.getMessage());
        }
    }

    private static String quote(String value) {
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private ObjectNode generator(String arn) {
        Optional<AwsArnUtils.Arn> parsed = LicenseManagerStore.parse(arn);
        String resource = parsed.map(AwsArnUtils.Arn::resource).orElse("");
        if (!resource.startsWith("report-generator:")) {
            throw LicenseManagerStore.notFound("The report generator " + arn + " does not exist.");
        }
        return store.get(parsed.get().region(), LicenseManagerStore.REPORT_GENERATOR,
                        resource.substring("report-generator:".length()))
                .orElseThrow(() -> LicenseManagerStore.notFound("The report generator " + arn + " does not exist."));
    }

    public ObjectNode getLicenseManagerReportGenerator(JsonNode in, String region) {
        ObjectNode record = generator(in.path("LicenseManagerReportGeneratorArn").asText());
        ObjectNode generator = LicenseManagerStore.view(record);
        generator.set("Tags", store.tagList(record));
        ObjectNode out = mapper.createObjectNode();
        out.set("ReportGenerator", generator);
        return out;
    }

    public ObjectNode updateLicenseManagerReportGenerator(JsonNode in, String region) {
        ObjectNode record = generator(in.path("LicenseManagerReportGeneratorArn").asText());
        checkReport(in, region);
        record.put("ReportGeneratorName", in.path("ReportGeneratorName").asText());
        record.set("ReportType", in.path("Type").deepCopy());
        record.set("ReportContext", in.path("ReportContext").deepCopy());
        record.set("ReportFrequency", in.path("ReportFrequency").deepCopy());
        record.remove("Description");
        copy(in, record, "Description");
        generate(record, region);
        store.put(region, LicenseManagerStore.REPORT_GENERATOR, record.path("_").path("id").asText(), record);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteLicenseManagerReportGenerator(JsonNode in, String region) {
        ObjectNode record = generator(in.path("LicenseManagerReportGeneratorArn").asText());
        store.delete(region, LicenseManagerStore.REPORT_GENERATOR, record.path("_").path("id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listLicenseManagerReportGenerators(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        Map<String, Function<JsonNode, String>> fields = Map.of(
                "LicenseConfigurationArn", g -> g.path("ReportContext").path("licenseConfigurationArns").path(0)
                        .asText(""));
        for (ObjectNode record : store.list(region, LicenseManagerStore.REPORT_GENERATOR)) {
            ObjectNode generator = LicenseManagerStore.view(record);
            generator.set("Tags", store.tagList(record));
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, generator)) {
                items.add(generator);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "ReportGenerators");
        return out;
    }

    // ================================================================ conversions

    public ObjectNode createLicenseConversionTaskForResource(JsonNode in, String region) {
        String resourceArn = in.path("ResourceArn").asText();
        if (!"EC2_INSTANCE".equals(resourceType(resourceArn))) {
            throw LicenseManagerStore.invalid("License type conversion applies to EC2 instances.");
        }
        String source = in.path("SourceLicenseContext").path("UsageOperation").asText(null);
        String destination = in.path("DestinationLicenseContext").path("UsageOperation").asText(null);
        if (destination == null && in.path("DestinationLicenseContext").path("ProductCodes").isEmpty()) {
            throw LicenseManagerStore.invalid("The destination license context needs a usage operation or product "
                    + "codes.");
        }
        boolean exists = instance(region, resourceArn).isPresent();
        String id = "lct-" + LicenseManagerStore.hex(16);
        ObjectNode task = store.record();
        task.put("LicenseConversionTaskId", id);
        task.put("ResourceArn", resourceArn);
        task.set("SourceLicenseContext", in.path("SourceLicenseContext").deepCopy());
        task.set("DestinationLicenseContext", in.path("DestinationLicenseContext").deepCopy());
        task.put("Status", "IN_PROGRESS");
        task.put("StartTime", LicenseManagerStore.epoch());
        LicenseManagerStore.meta(task).put("doneAt", System.currentTimeMillis() + store.config().transitionMillis());
        LicenseManagerStore.meta(task).put("outcome", exists && (source == null || !source.equals(destination))
                ? "SUCCEEDED" : "FAILED");
        LicenseManagerStore.meta(task).put("message", !exists ? "The instance " + resourceArn + " was not found."
                : source != null && source.equals(destination) ? "The source and destination usage operations are "
                + "the same." : "The license type was converted.");
        store.put(region, LicenseManagerStore.CONVERSION, id, task);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseConversionTaskId", id);
        return out;
    }

    private ObjectNode settled(ObjectNode task, String region) {
        ObjectNode meta = LicenseManagerStore.meta(task);
        if ("IN_PROGRESS".equals(task.path("Status").asText()) && meta.path("doneAt").asLong()
                <= System.currentTimeMillis()) {
            task.put("Status", meta.path("outcome").asText());
            task.put("StatusMessage", meta.path("message").asText());
            double now = LicenseManagerStore.epoch();
            task.put("EndTime", now);
            if ("SUCCEEDED".equals(task.path("Status").asText())) {
                task.put("LicenseConversionTime", now);
            }
            store.put(region, LicenseManagerStore.CONVERSION, task.path("LicenseConversionTaskId").asText(), task);
        }
        return LicenseManagerStore.view(task);
    }

    public ObjectNode getLicenseConversionTask(JsonNode in, String region) {
        String id = in.path("LicenseConversionTaskId").asText();
        ObjectNode task = store.get(region, LicenseManagerStore.CONVERSION, id)
                .orElseThrow(() -> LicenseManagerStore.notFound("The conversion task " + id + " does not exist."));
        return settled(task, region);
    }

    public ObjectNode listLicenseConversionTasks(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        Map<String, Function<JsonNode, String>> fields = Map.of(
                "ResourceArns", t -> t.path("ResourceArn").asText(),
                "Status", t -> t.path("Status").asText());
        for (ObjectNode task : store.list(region, LicenseManagerStore.CONVERSION)) {
            ObjectNode view = settled(task, region);
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, view)) {
                items.add(view);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "LicenseConversionTasks");
        return out;
    }

    // ================================================================ tags

    /** The record a taggable ARN names: a configuration, license, grant, report generator, asset group or ruleset. */
    private Tagged tagged(String arn) {
        Optional<AwsArnUtils.Arn> parsed = LicenseManagerStore.parse(arn);
        if (parsed.isEmpty()) {
            throw LicenseManagerStore.invalid("The resource ARN " + arn + " is not a License Manager ARN.");
        }
        String resource = parsed.get().resource();
        Map<String, String[]> kinds = new LinkedHashMap<>();
        kinds.put("license-configuration:", new String[] {parsed.get().region(), LicenseManagerStore.CONFIGURATION});
        kinds.put("license:", new String[] {LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE});
        kinds.put("grant:", new String[] {LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT});
        kinds.put("report-generator:", new String[] {parsed.get().region(), LicenseManagerStore.REPORT_GENERATOR});
        kinds.put("license-asset-group:", new String[] {parsed.get().region(), LicenseManagerStore.ASSET_GROUP});
        kinds.put("license-asset-ruleset:", new String[] {parsed.get().region(), LicenseManagerStore.RULESET});
        for (Map.Entry<String, String[]> kind : kinds.entrySet()) {
            if (resource.startsWith(kind.getKey())) {
                String id = resource.substring(kind.getKey().length());
                ObjectNode record = store.getForAccount(parsed.get().accountId(), kind.getValue()[0],
                                kind.getValue()[1], id)
                        .orElseThrow(() -> LicenseManagerStore.invalid("The resource " + arn + " does not exist."));
                return new Tagged(parsed.get().accountId(), kind.getValue()[0], kind.getValue()[1], id, record);
            }
        }
        throw LicenseManagerStore.invalid("The resource " + arn + " can't be tagged.");
    }

    private record Tagged(String account, String region, String kind, String id, ObjectNode record) {
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Tagged tagged = tagged(in.path("ResourceArn").asText());
        LicenseManagerStore.addTags(tagged.record(), in.path("Tags"));
        store.putForAccount(tagged.account(), tagged.region(), tagged.kind(), tagged.id(), tagged.record());
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Tagged tagged = tagged(in.path("ResourceArn").asText());
        ObjectNode tags = LicenseManagerStore.tags(tagged.record());
        in.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        store.putForAccount(tagged.account(), tagged.region(), tagged.kind(), tagged.id(), tagged.record());
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        Tagged tagged = tagged(in.path("ResourceArn").asText());
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", store.tagList(tagged.record()));
        return out;
    }

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode value = from.get(field);
            if (value != null && !value.isNull()) {
                to.set(field, value.deepCopy());
            }
        }
    }
}

package io.floci.aws.services.mailmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.CsvParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Mail Manager's resources: rule sets, traffic policies, relays, ingress points (each served by its own SMTP
 * listener, {@link MailManagerSmtp}), address lists with their members and import jobs, archives, add-on
 * subscriptions and instances, and tags on all of them.
 */
@ApplicationScoped
public class MailManagerResources {

    private static final Pattern ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    static final Map<String, String> ADDONS = Map.of(
            "TRENDMICRO_VSAPI", "Trend Micro Virus Scanning",
            "SPAMHAUS_DBL", "Spamhaus Domain Block List",
            "ABUSIX_MAIL_INTELLIGENCE", "Abusix Mail Intelligence",
            "VADE_ADVANCED_EMAIL_SECURITY", "Vade Advanced Email Security");

    private final MailManagerStore store;
    private final MailManagerSmtp smtp;
    private final ObjectMapper mapper;

    @Inject
    public MailManagerResources(MailManagerStore store, MailManagerSmtp smtp) {
        this.store = store;
        this.smtp = smtp;
        this.mapper = store.mapper();
    }

    private ObjectNode created(String region, String operation, JsonNode in, String field, String id) {
        ObjectNode out = mapper.createObjectNode();
        out.put(field, id);
        store.remember(region, operation, in, out);
        return out;
    }

    private ObjectNode find(String region, String kind, String idOrArn, String what) {
        String id = MailManagerStore.idOf(idOrArn);
        return store.get(region, kind, id == null ? "" : id)
                .orElseThrow(() -> MailManagerStore.notFound("The " + what + " " + idOrArn + " does not exist."));
    }

    private void uniqueName(String region, String kind, String field, String name, String self, String what) {
        for (ObjectNode other : store.list(region, kind, "")) {
            if (name.equals(other.path(field).asText()) && !other.path("_").path("id").asText().equals(self)) {
                throw MailManagerStore.conflict("A " + what + " named " + name + " already exists.");
            }
        }
    }

    // ================================================================ rule sets

    public ObjectNode createRuleSet(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateRuleSet", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("RuleSetName").asText();
        uniqueName(region, MailManagerStore.RULE_SET, "RuleSetName", name, null, "rule set");
        checkRules(region, in.path("Rules"));
        String id = MailManagerStore.id("rs-");
        ObjectNode record = store.record();
        record.put("RuleSetId", id);
        record.put("RuleSetArn", store.arn(region, "rule-set/" + id));
        record.put("RuleSetName", name);
        double now = MailManagerStore.epoch();
        record.put("CreatedDate", now);
        record.put("LastModificationDate", now);
        record.set("Rules", in.path("Rules").deepCopy());
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, MailManagerStore.RULE_SET, id, record);
        return created(region, "CreateRuleSet", in, "RuleSetId", id);
    }

    private void checkRules(String region, JsonNode rules) {
        if (rules.size() > 40) {
            throw MailManagerStore.validation("A rule set can have at most 40 rules.");
        }
        for (JsonNode rule : rules) {
            if (rule.path("Actions").isEmpty()) {
                throw MailManagerStore.validation("Each rule needs at least one action.");
            }
            for (JsonNode action : rule.path("Actions")) {
                if (action.size() != 1) {
                    throw MailManagerStore.validation("Each rule action names exactly one action.");
                }
                if (action.has("Relay")) {
                    find(region, MailManagerStore.RELAY, action.path("Relay").path("Relay").asText(), "relay");
                }
                if (action.has("Archive")) {
                    archiveByNameOrArn(region, action.path("Archive").path("TargetArchive").asText());
                }
            }
            for (JsonNode condition : rule.path("Conditions")) {
                checkAddressLists(region, condition.path("BooleanExpression").path("Evaluate")
                        .path("IsInAddressList").path("AddressLists"));
            }
        }
    }

    private void checkAddressLists(String region, JsonNode lists) {
        for (JsonNode list : lists) {
            find(region, MailManagerStore.ADDRESS_LIST, list.asText(), "address list");
        }
    }

    /** An archive by id, ARN or name. */
    ObjectNode archiveByNameOrArn(String region, String nameOrArn) {
        String id = MailManagerStore.idOf(nameOrArn);
        Optional<ObjectNode> byId = store.get(region, MailManagerStore.ARCHIVE, id);
        if (byId.isPresent()) {
            return byId.get();
        }
        for (ObjectNode archive : store.list(region, MailManagerStore.ARCHIVE, "")) {
            if (nameOrArn.equals(archive.path("ArchiveName").asText())) {
                return archive;
            }
        }
        throw MailManagerStore.notFound("The archive " + nameOrArn + " does not exist.");
    }

    public ObjectNode getRuleSet(JsonNode in, String region) {
        return MailManagerStore.view(find(region, MailManagerStore.RULE_SET, in.path("RuleSetId").asText(),
                "rule set"));
    }

    public ObjectNode updateRuleSet(JsonNode in, String region) {
        ObjectNode record = find(region, MailManagerStore.RULE_SET, in.path("RuleSetId").asText(), "rule set");
        String id = record.path("RuleSetId").asText();
        if (in.hasNonNull("RuleSetName")) {
            uniqueName(region, MailManagerStore.RULE_SET, "RuleSetName", in.get("RuleSetName").asText(), id,
                    "rule set");
            record.put("RuleSetName", in.get("RuleSetName").asText());
        }
        if (in.has("Rules")) {
            checkRules(region, in.get("Rules"));
            record.set("Rules", in.get("Rules").deepCopy());
        }
        record.put("LastModificationDate", MailManagerStore.epoch());
        store.put(region, MailManagerStore.RULE_SET, id, record);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteRuleSet(JsonNode in, String region) {
        ObjectNode record = existing(() -> find(region, MailManagerStore.RULE_SET, in.path("RuleSetId").asText(), "rule set"));
        if (record == null) {
            return mapper.createObjectNode();
        }
        String id = record.path("RuleSetId").asText();
        for (ObjectNode ingress : store.list(region, MailManagerStore.INGRESS_POINT, "")) {
            if (id.equals(ingress.path("RuleSetId").asText())) {
                throw MailManagerStore.conflict("The rule set is used by the ingress point "
                        + ingress.path("IngressPointName").asText() + ".");
            }
        }
        store.delete(region, MailManagerStore.RULE_SET, id);
        return mapper.createObjectNode();
    }

    public ObjectNode listRuleSets(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, MailManagerStore.RULE_SET, "")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(record, summary, "RuleSetId", "RuleSetName", "LastModificationDate");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "RuleSets", 20);
        return out;
    }

    // ================================================================ traffic policies

    public ObjectNode createTrafficPolicy(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateTrafficPolicy", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("TrafficPolicyName").asText();
        uniqueName(region, MailManagerStore.TRAFFIC_POLICY, "TrafficPolicyName", name, null, "traffic policy");
        checkStatements(region, in.path("PolicyStatements"));
        String id = MailManagerStore.id("tp-");
        ObjectNode record = store.record();
        record.put("TrafficPolicyName", name);
        record.put("TrafficPolicyId", id);
        record.put("TrafficPolicyArn", store.arn(region, "traffic-policy/" + id));
        record.set("PolicyStatements", in.path("PolicyStatements").deepCopy());
        copy(in, record, "MaxMessageSizeBytes");
        record.put("DefaultAction", in.path("DefaultAction").asText());
        double now = MailManagerStore.epoch();
        record.put("CreatedTimestamp", now);
        record.put("LastUpdatedTimestamp", now);
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, MailManagerStore.TRAFFIC_POLICY, id, record);
        return created(region, "CreateTrafficPolicy", in, "TrafficPolicyId", id);
    }

    private void checkStatements(String region, JsonNode statements) {
        for (JsonNode statement : statements) {
            if (statement.path("Conditions").isEmpty()) {
                throw MailManagerStore.validation("Each policy statement needs at least one condition.");
            }
            for (JsonNode condition : statement.path("Conditions")) {
                if (condition.size() != 1) {
                    throw MailManagerStore.validation("Each policy condition names exactly one expression.");
                }
                checkAddressLists(region, condition.path("BooleanExpression").path("Evaluate")
                        .path("IsInAddressList").path("AddressLists"));
                for (JsonNode cidr : condition.path("IpExpression").path("Values")) {
                    if (!MailManagerPolicies.validCidr(cidr.asText(), false)) {
                        throw MailManagerStore.validation("The CIDR " + cidr.asText() + " is not valid.");
                    }
                }
            }
        }
    }

    public ObjectNode getTrafficPolicy(JsonNode in, String region) {
        return MailManagerStore.view(find(region, MailManagerStore.TRAFFIC_POLICY,
                in.path("TrafficPolicyId").asText(), "traffic policy"));
    }

    public ObjectNode updateTrafficPolicy(JsonNode in, String region) {
        ObjectNode record = find(region, MailManagerStore.TRAFFIC_POLICY, in.path("TrafficPolicyId").asText(),
                "traffic policy");
        String id = record.path("TrafficPolicyId").asText();
        if (in.hasNonNull("TrafficPolicyName")) {
            uniqueName(region, MailManagerStore.TRAFFIC_POLICY, "TrafficPolicyName",
                    in.get("TrafficPolicyName").asText(), id, "traffic policy");
            record.put("TrafficPolicyName", in.get("TrafficPolicyName").asText());
        }
        if (in.has("PolicyStatements")) {
            checkStatements(region, in.get("PolicyStatements"));
            record.set("PolicyStatements", in.get("PolicyStatements").deepCopy());
        }
        copy(in, record, "DefaultAction", "MaxMessageSizeBytes");
        record.put("LastUpdatedTimestamp", MailManagerStore.epoch());
        store.put(region, MailManagerStore.TRAFFIC_POLICY, id, record);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteTrafficPolicy(JsonNode in, String region) {
        ObjectNode record = existing(() -> find(region, MailManagerStore.TRAFFIC_POLICY, in.path("TrafficPolicyId").asText(),
                "traffic policy"));
        if (record == null) {
            return mapper.createObjectNode();
        }
        String id = record.path("TrafficPolicyId").asText();
        for (ObjectNode ingress : store.list(region, MailManagerStore.INGRESS_POINT, "")) {
            if (id.equals(ingress.path("TrafficPolicyId").asText())) {
                throw MailManagerStore.conflict("The traffic policy is used by the ingress point "
                        + ingress.path("IngressPointName").asText() + ".");
            }
        }
        store.delete(region, MailManagerStore.TRAFFIC_POLICY, id);
        return mapper.createObjectNode();
    }

    public ObjectNode listTrafficPolicies(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, MailManagerStore.TRAFFIC_POLICY, "")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(record, summary, "TrafficPolicyName", "TrafficPolicyId", "DefaultAction");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "TrafficPolicies", 20);
        return out;
    }

    // ================================================================ relays

    public ObjectNode createRelay(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateRelay", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("RelayName").asText();
        uniqueName(region, MailManagerStore.RELAY, "RelayName", name, null, "relay");
        checkAuthentication(in.path("Authentication"));
        String id = MailManagerStore.id("rl-");
        ObjectNode record = store.record();
        record.put("RelayId", id);
        record.put("RelayArn", store.arn(region, "relay/" + id));
        record.put("RelayName", name);
        copy(in, record, "ServerName", "ServerPort", "Authentication");
        double now = MailManagerStore.epoch();
        record.put("CreatedTimestamp", now);
        record.put("LastModifiedTimestamp", now);
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, MailManagerStore.RELAY, id, record);
        return created(region, "CreateRelay", in, "RelayId", id);
    }

    private static void checkAuthentication(JsonNode authentication) {
        if (authentication.has("SecretArn") == authentication.has("NoAuthentication")) {
            throw MailManagerStore.validation("Relay authentication names a SecretArn or NoAuthentication.");
        }
    }

    public ObjectNode getRelay(JsonNode in, String region) {
        return MailManagerStore.view(find(region, MailManagerStore.RELAY, in.path("RelayId").asText(), "relay"));
    }

    public ObjectNode updateRelay(JsonNode in, String region) {
        ObjectNode record = find(region, MailManagerStore.RELAY, in.path("RelayId").asText(), "relay");
        String id = record.path("RelayId").asText();
        if (in.hasNonNull("RelayName")) {
            uniqueName(region, MailManagerStore.RELAY, "RelayName", in.get("RelayName").asText(), id, "relay");
        }
        if (in.has("Authentication")) {
            checkAuthentication(in.get("Authentication"));
        }
        copy(in, record, "RelayName", "ServerName", "ServerPort", "Authentication");
        record.put("LastModifiedTimestamp", MailManagerStore.epoch());
        store.put(region, MailManagerStore.RELAY, id, record);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteRelay(JsonNode in, String region) {
        ObjectNode record = find(region, MailManagerStore.RELAY, in.path("RelayId").asText(), "relay");
        String id = record.path("RelayId").asText();
        for (ObjectNode ruleSet : store.list(region, MailManagerStore.RULE_SET, "")) {
            for (JsonNode relay : ruleSet.findValues("Relay")) {
                if (relay.isObject() && id.equals(MailManagerStore.idOf(relay.path("Relay").asText()))) {
                    throw MailManagerStore.conflict("The relay is used by the rule set "
                            + ruleSet.path("RuleSetName").asText() + ".");
                }
            }
        }
        store.delete(region, MailManagerStore.RELAY, id);
        return mapper.createObjectNode();
    }

    public ObjectNode listRelays(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, MailManagerStore.RELAY, "")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(record, summary, "RelayId", "RelayName", "LastModifiedTimestamp");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "Relays", 20);
        return out;
    }

    // ================================================================ ingress points

    public ObjectNode createIngressPoint(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateIngressPoint", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("IngressPointName").asText();
        uniqueName(region, MailManagerStore.INGRESS_POINT, "IngressPointName", name, null, "ingress point");
        find(region, MailManagerStore.RULE_SET, in.path("RuleSetId").asText(), "rule set");
        find(region, MailManagerStore.TRAFFIC_POLICY, in.path("TrafficPolicyId").asText(), "traffic policy");
        String type = in.path("Type").asText();
        JsonNode configuration = in.path("IngressPointConfiguration");
        if ("AUTH".equals(type) && !configuration.has("SmtpPassword") && !configuration.has("SecretArn")) {
            throw MailManagerStore.validation("An AUTH ingress point needs an SmtpPassword or SecretArn.");
        }
        if ("MTLS".equals(type) && !configuration.has("TlsAuthConfiguration")) {
            throw MailManagerStore.validation("An MTLS ingress point needs a TlsAuthConfiguration.");
        }
        if ("OPEN".equals(type) && configuration.size() > 0) {
            throw MailManagerStore.validation("An OPEN ingress point takes no authentication configuration.");
        }
        String id = MailManagerStore.id("inp-");
        ObjectNode record = store.record();
        record.put("IngressPointId", id);
        record.put("IngressPointName", name);
        record.put("IngressPointArn", store.arn(region, "ingress-point/" + id));
        record.put("Status", "PROVISIONING");
        record.put("Type", type);
        record.put("ARecord", store.config().smtpHostname());
        copy(in, record, "RuleSetId", "TrafficPolicyId", "NetworkConfiguration");
        record.put("TlsPolicy", in.path("TlsPolicy").asText("OPTIONAL"));
        ObjectNode auth = record.putObject("IngressPointAuthConfiguration");
        if (configuration.has("SmtpPassword")) {
            auth.putObject("IngressPointPasswordConfiguration").put("SmtpPasswordVersion", "1");
            MailManagerStore.meta(record).put("password", configuration.get("SmtpPassword").asText());
        }
        if (configuration.has("SecretArn")) {
            auth.put("SecretArn", configuration.get("SecretArn").asText());
        }
        if (configuration.has("TlsAuthConfiguration")) {
            auth.set("TlsAuthConfiguration", configuration.get("TlsAuthConfiguration").deepCopy());
        }
        if (auth.isEmpty()) {
            record.remove("IngressPointAuthConfiguration");
        }
        double now = MailManagerStore.epoch();
        record.put("CreatedTimestamp", now);
        record.put("LastUpdatedTimestamp", now);
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        MailManagerStore.meta(record).put("activeAt", System.currentTimeMillis() + store.config().transitionMillis());
        store.put(region, MailManagerStore.INGRESS_POINT, id, record);
        return created(region, "CreateIngressPoint", in, "IngressPointId", id);
    }

    /** An ingress point, ACTIVE (and listening) once provisioned, unless closed. */
    ObjectNode ingressPoint(String region, String id) {
        ObjectNode record = find(region, MailManagerStore.INGRESS_POINT, id, "ingress point");
        ObjectNode meta = MailManagerStore.meta(record);
        if (meta.has("activeAt") && meta.get("activeAt").asLong() <= System.currentTimeMillis()) {
            meta.remove("activeAt");
            if (Set.of("PROVISIONING", "UPDATING").contains(record.path("Status").asText())) {
                record.put("Status", "ACTIVE");
            }
            store.put(region, MailManagerStore.INGRESS_POINT, record.path("IngressPointId").asText(), record);
        }
        if ("ACTIVE".equals(record.path("Status").asText())) {
            int port = smtp.listen(store.account(), region, record.path("IngressPointId").asText());
            if (port > 0 && record.path("_").path("port").asInt() != port) {
                meta.put("port", port);
                store.put(region, MailManagerStore.INGRESS_POINT, record.path("IngressPointId").asText(), record);
            }
        } else {
            smtp.close(record.path("IngressPointId").asText());
        }
        return record;
    }

    public ObjectNode getIngressPoint(JsonNode in, String region) {
        ObjectNode record = ingressPoint(region, in.path("IngressPointId").asText());
        ObjectNode out = MailManagerStore.view(record);
        if (record.path("_").has("port")) {
            out.put("SmtpPort", record.path("_").path("port").asInt());
        }
        return out;
    }

    public ObjectNode updateIngressPoint(JsonNode in, String region) {
        ObjectNode record = ingressPoint(region, in.path("IngressPointId").asText());
        String id = record.path("IngressPointId").asText();
        if (Set.of("PROVISIONING", "DEPROVISIONING").contains(record.path("Status").asText())) {
            throw MailManagerStore.conflict("The ingress point is " + record.path("Status").asText() + ".");
        }
        if (in.hasNonNull("IngressPointName")) {
            uniqueName(region, MailManagerStore.INGRESS_POINT, "IngressPointName", in.get("IngressPointName")
                    .asText(), id, "ingress point");
            record.put("IngressPointName", in.get("IngressPointName").asText());
        }
        if (in.hasNonNull("RuleSetId")) {
            find(region, MailManagerStore.RULE_SET, in.get("RuleSetId").asText(), "rule set");
            record.put("RuleSetId", in.get("RuleSetId").asText());
        }
        if (in.hasNonNull("TrafficPolicyId")) {
            find(region, MailManagerStore.TRAFFIC_POLICY, in.get("TrafficPolicyId").asText(), "traffic policy");
            record.put("TrafficPolicyId", in.get("TrafficPolicyId").asText());
        }
        if (in.has("TlsPolicy")) {
            record.put("TlsPolicy", in.get("TlsPolicy").asText());
        }
        if (in.path("IngressPointConfiguration").has("SmtpPassword")) {
            ObjectNode password = record.withObject("/IngressPointAuthConfiguration")
                    .withObject("/IngressPointPasswordConfiguration");
            String previous = password.path("SmtpPasswordVersion").asText("1");
            password.put("PreviousSmtpPasswordVersion", previous);
            password.put("SmtpPasswordVersion", String.valueOf(Integer.parseInt(previous) + 1));
            password.put("PreviousSmtpPasswordExpiryTimestamp", MailManagerStore.epoch() + 86400);
            MailManagerStore.meta(record).put("previousPassword", record.path("_").path("password").asText());
            MailManagerStore.meta(record).put("password", in.path("IngressPointConfiguration").get("SmtpPassword")
                    .asText());
        }
        if (in.path("IngressPointConfiguration").has("SecretArn")) {
            record.withObject("/IngressPointAuthConfiguration").put("SecretArn", in.path("IngressPointConfiguration")
                    .get("SecretArn").asText());
        }
        if (in.hasNonNull("StatusToUpdate")) {
            String status = in.get("StatusToUpdate").asText();
            record.put("Status", "CLOSED".equals(status) ? "CLOSED" : "UPDATING");
            if (!"CLOSED".equals(status)) {
                MailManagerStore.meta(record).put("activeAt", System.currentTimeMillis()
                        + store.config().transitionMillis());
            }
        }
        record.put("LastUpdatedTimestamp", MailManagerStore.epoch());
        store.put(region, MailManagerStore.INGRESS_POINT, id, record);
        if ("CLOSED".equals(record.path("Status").asText())) {
            smtp.close(id);
        }
        return mapper.createObjectNode();
    }

    public ObjectNode deleteIngressPoint(JsonNode in, String region) {
        ObjectNode record = ingressPoint(region, in.path("IngressPointId").asText());
        String id = record.path("IngressPointId").asText();
        smtp.close(id);
        store.delete(region, MailManagerStore.INGRESS_POINT, id);
        return mapper.createObjectNode();
    }

    public ObjectNode listIngressPoints(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode listed : store.list(region, MailManagerStore.INGRESS_POINT, "")) {
            ObjectNode record = ingressPoint(region, listed.path("IngressPointId").asText());
            ObjectNode summary = mapper.createObjectNode();
            copy(record, summary, "IngressPointName", "IngressPointId", "Status", "Type", "ARecord");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "IngressPoints", 20);
        return out;
    }

    // ================================================================ address lists

    public ObjectNode createAddressList(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateAddressList", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("AddressListName").asText();
        uniqueName(region, MailManagerStore.ADDRESS_LIST, "AddressListName", name, null, "address list");
        if (store.list(region, MailManagerStore.ADDRESS_LIST, "").size() >= 100) {
            throw MailManagerStore.quota("The account has the maximum number of address lists.");
        }
        String id = MailManagerStore.id("al-");
        ObjectNode record = store.record();
        record.put("AddressListId", id);
        record.put("AddressListArn", store.arn(region, "address-list/" + id));
        record.put("AddressListName", name);
        double now = MailManagerStore.epoch();
        record.put("CreatedTimestamp", now);
        record.put("LastUpdatedTimestamp", now);
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, MailManagerStore.ADDRESS_LIST, id, record);
        return created(region, "CreateAddressList", in, "AddressListId", id);
    }

    ObjectNode addressList(String region, String id) {
        return find(region, MailManagerStore.ADDRESS_LIST, id, "address list");
    }

    public ObjectNode getAddressList(JsonNode in, String region) {
        return MailManagerStore.view(addressList(region, in.path("AddressListId").asText()));
    }

    public ObjectNode deleteAddressList(JsonNode in, String region) {
        ObjectNode record = existing(() -> addressList(region, in.path("AddressListId").asText()));
        if (record == null) {
            return mapper.createObjectNode();
        }
        String id = record.path("AddressListId").asText();
        String arn = record.path("AddressListArn").asText();
        for (String kind : List.of(MailManagerStore.RULE_SET, MailManagerStore.TRAFFIC_POLICY)) {
            for (ObjectNode user : store.list(region, kind, "")) {
                for (JsonNode lists : user.findValues("AddressLists")) {
                    for (JsonNode list : lists) {
                        if (arn.equals(list.asText()) || id.equals(list.asText())) {
                            throw MailManagerStore.conflict("The address list is used by a "
                                    + ("ruleset".equals(kind) ? "rule set" : "traffic policy") + ".");
                        }
                    }
                }
            }
        }
        for (ObjectNode member : store.list(region, MailManagerStore.MEMBER, id + "/")) {
            store.delete(region, MailManagerStore.MEMBER, member.path("_").path("id").asText());
        }
        store.delete(region, MailManagerStore.ADDRESS_LIST, id);
        return mapper.createObjectNode();
    }

    public ObjectNode listAddressLists(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, MailManagerStore.ADDRESS_LIST, "").forEach(r -> items.add(MailManagerStore.view(r)));
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "AddressLists", 20);
        return out;
    }

    static String normalize(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }

    private void touch(String region, ObjectNode list) {
        list.put("LastUpdatedTimestamp", MailManagerStore.epoch());
        store.put(region, MailManagerStore.ADDRESS_LIST, list.path("AddressListId").asText(), list);
    }

    public ObjectNode registerMemberToAddressList(JsonNode in, String region) {
        ObjectNode list = addressList(region, in.path("AddressListId").asText());
        String address = normalize(in.path("Address").asText());
        if (!ADDRESS.matcher(address).matches()) {
            throw MailManagerStore.validation("The address " + in.path("Address").asText() + " is not valid.");
        }
        String listId = list.path("AddressListId").asText();
        if (store.get(region, MailManagerStore.MEMBER, listId + "/" + address).isEmpty()) {
            if (store.list(region, MailManagerStore.MEMBER, listId + "/").size() >= store.config()
                    .maxAddressListMembers()) {
                throw MailManagerStore.quota("The address list has the maximum number of members.");
            }
            ObjectNode member = store.record();
            member.put("Address", address);
            member.put("CreatedTimestamp", MailManagerStore.epoch());
            store.put(region, MailManagerStore.MEMBER, listId + "/" + address, member);
            touch(region, list);
        }
        return mapper.createObjectNode();
    }

    public ObjectNode getMemberOfAddressList(JsonNode in, String region) {
        ObjectNode list = addressList(region, in.path("AddressListId").asText());
        String address = normalize(in.path("Address").asText());
        return MailManagerStore.view(store.get(region, MailManagerStore.MEMBER, list.path("AddressListId").asText()
                        + "/" + address)
                .orElseThrow(() -> MailManagerStore.notFound("The address " + address + " is not in the list.")));
    }

    public ObjectNode deregisterMemberFromAddressList(JsonNode in, String region) {
        ObjectNode list = addressList(region, in.path("AddressListId").asText());
        String address = normalize(in.path("Address").asText());
        String key = list.path("AddressListId").asText() + "/" + address;
        if (store.get(region, MailManagerStore.MEMBER, key).isPresent()) {
            store.delete(region, MailManagerStore.MEMBER, key);
            touch(region, list);
        }
        return mapper.createObjectNode();
    }

    public ObjectNode listMembersOfAddressList(JsonNode in, String region) {
        ObjectNode list = addressList(region, in.path("AddressListId").asText());
        String prefix = normalize(in.path("Filter").path("AddressPrefix").asText(""));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode member : store.list(region, MailManagerStore.MEMBER, list.path("AddressListId").asText()
                + "/" + prefix)) {
            items.add(MailManagerStore.view(member));
        }
        items.sort((a, b) -> a.path("Address").asText().compareTo(b.path("Address").asText()));
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "Addresses", 100);
        return out;
    }

    /** Whether an address is a member of an address list, named by id or ARN. */
    boolean isMember(String account, String region, String listIdOrArn, String address) {
        String id = MailManagerStore.idOf(listIdOrArn);
        return store.getForAccount(account, region, MailManagerStore.MEMBER, id + "/" + normalize(address))
                .isPresent();
    }

    // ---------------------------------------------------------------- import jobs

    public ObjectNode createAddressListImportJob(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateAddressListImportJob", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode list = addressList(region, in.path("AddressListId").asText());
        String jobId = MailManagerStore.id("j-");
        ObjectNode job = store.record();
        job.put("JobId", jobId);
        job.put("Name", in.path("Name").asText());
        job.put("Status", "CREATED");
        job.put("PreSignedUrl", store.reserveUpload(jobId));
        job.set("ImportDataFormat", in.path("ImportDataFormat").deepCopy());
        job.put("AddressListId", list.path("AddressListId").asText());
        job.put("CreatedTimestamp", MailManagerStore.epoch());
        store.put(region, MailManagerStore.IMPORT_JOB, jobId, job);
        ObjectNode out = mapper.createObjectNode();
        out.put("JobId", jobId);
        out.put("PreSignedUrl", job.path("PreSignedUrl").asText());
        store.remember(region, "CreateAddressListImportJob", in, out);
        return out;
    }

    private ObjectNode job(String region, String jobId) {
        return find(region, MailManagerStore.IMPORT_JOB, jobId, "import job");
    }

    public ObjectNode startAddressListImportJob(JsonNode in, String region) {
        ObjectNode job = job(region, in.path("JobId").asText());
        if (!"CREATED".equals(job.path("Status").asText())) {
            throw MailManagerStore.conflict("The import job is " + job.path("Status").asText() + ".");
        }
        String jobId = job.path("JobId").asText();
        double now = MailManagerStore.epoch();
        job.put("StartTimestamp", now);
        Optional<byte[]> uploaded = store.uploaded(jobId);
        if (uploaded.isEmpty()) {
            job.put("Status", "FAILED");
            job.put("Error", "Nothing was uploaded to the job's URL.");
            job.put("CompletedTimestamp", now);
            store.put(region, MailManagerStore.IMPORT_JOB, jobId, job);
            return mapper.createObjectNode();
        }
        List<String> addresses = new ArrayList<>();
        try {
            addresses = parseImport(new String(uploaded.get(), StandardCharsets.UTF_8),
                    job.path("ImportDataFormat").path("ImportDataType").asText());
        } catch (AwsException e) {
            job.put("Status", "FAILED");
            job.put("Error", e.getMessage());
            job.put("CompletedTimestamp", now);
            store.put(region, MailManagerStore.IMPORT_JOB, jobId, job);
            return mapper.createObjectNode();
        }
        int imported = 0;
        int failed = 0;
        Set<String> seen = new HashSet<>();
        for (String address : addresses) {
            String normalized = normalize(address);
            if (!ADDRESS.matcher(normalized).matches() || !seen.add(normalized)) {
                failed++;
                continue;
            }
            try {
                ObjectNode request = mapper.createObjectNode();
                request.put("AddressListId", job.path("AddressListId").asText());
                request.put("Address", normalized);
                registerMemberToAddressList(request, region);
                imported++;
            } catch (AwsException e) {
                failed++;
            }
        }
        job.put("Status", "PROCESSING");
        job.put("ImportedItemsCount", imported);
        job.put("FailedItemsCount", failed);
        MailManagerStore.meta(job).put("doneAt", System.currentTimeMillis() + store.config().transitionMillis());
        store.put(region, MailManagerStore.IMPORT_JOB, jobId, job);
        return mapper.createObjectNode();
    }

    private List<String> parseImport(String text, String type) {
        List<String> addresses = new ArrayList<>();
        if ("JSON".equals(type)) {
            for (String line : text.split("\\r?\\n")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode item = mapper.readTree(line);
                    addresses.add(item.path("address").asText(item.path("Address").asText("")));
                } catch (Exception e) {
                    throw MailManagerStore.validation("Line " + (addresses.size() + 1) + " is not a JSON object.");
                }
            }
            return addresses;
        }
        List<List<String>> rows = CsvParser.parseAll(text.replace("\r\n", "\n"));
        if (rows.isEmpty() || !"address".equalsIgnoreCase(rows.get(0).get(0).trim())) {
            throw MailManagerStore.validation("A CSV import starts with an address header.");
        }
        for (List<String> row : rows.subList(1, rows.size())) {
            if (!row.isEmpty() && !row.get(0).isBlank()) {
                addresses.add(row.get(0).trim());
            }
        }
        return addresses;
    }

    private ObjectNode settled(String region, ObjectNode job) {
        ObjectNode meta = MailManagerStore.meta(job);
        if ("PROCESSING".equals(job.path("Status").asText()) && meta.path("doneAt").asLong()
                <= System.currentTimeMillis()) {
            job.put("Status", "COMPLETED");
            job.put("CompletedTimestamp", MailManagerStore.epoch());
            meta.remove("doneAt");
            store.put(region, MailManagerStore.IMPORT_JOB, job.path("JobId").asText(), job);
        }
        return MailManagerStore.view(job);
    }

    public ObjectNode getAddressListImportJob(JsonNode in, String region) {
        return settled(region, job(region, in.path("JobId").asText()));
    }

    public ObjectNode stopAddressListImportJob(JsonNode in, String region) {
        ObjectNode job = job(region, in.path("JobId").asText());
        String status = job.path("Status").asText();
        if (!"CREATED".equals(status) && !"PROCESSING".equals(status)) {
            throw MailManagerStore.conflict("The import job is " + status + ".");
        }
        job.put("Status", "STOPPED");
        job.put("CompletedTimestamp", MailManagerStore.epoch());
        MailManagerStore.meta(job).remove("doneAt");
        store.put(region, MailManagerStore.IMPORT_JOB, job.path("JobId").asText(), job);
        return mapper.createObjectNode();
    }

    public ObjectNode listAddressListImportJobs(JsonNode in, String region) {
        ObjectNode list = addressList(region, in.path("AddressListId").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.list(region, MailManagerStore.IMPORT_JOB, "")) {
            if (list.path("AddressListId").asText().equals(job.path("AddressListId").asText())) {
                items.add(settled(region, job));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "ImportJobs", 20);
        return out;
    }

    // ================================================================ archives

    public ObjectNode createArchive(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateArchive", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("ArchiveName").asText();
        for (ObjectNode other : store.list(region, MailManagerStore.ARCHIVE, "")) {
            if (name.equals(other.path("ArchiveName").asText()) && "ACTIVE".equals(other.path("ArchiveState")
                    .asText())) {
                throw MailManagerStore.conflict("An archive named " + name + " already exists.");
            }
        }
        String id = MailManagerStore.id("a-");
        ObjectNode record = store.record();
        record.put("ArchiveId", id);
        record.put("ArchiveName", name);
        record.put("ArchiveArn", store.arn(region, "archive/" + id));
        record.put("ArchiveState", "ACTIVE");
        record.set("Retention", in.has("Retention") ? in.get("Retention").deepCopy()
                : mapper.createObjectNode().put("RetentionPeriod", "PERMANENT"));
        copy(in, record, "KmsKeyArn");
        double now = MailManagerStore.epoch();
        record.put("CreatedTimestamp", now);
        record.put("LastUpdatedTimestamp", now);
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, MailManagerStore.ARCHIVE, id, record);
        return created(region, "CreateArchive", in, "ArchiveId", id);
    }

    ObjectNode archive(String region, String id) {
        return find(region, MailManagerStore.ARCHIVE, id, "archive");
    }

    public ObjectNode getArchive(JsonNode in, String region) {
        return MailManagerStore.view(archive(region, in.path("ArchiveId").asText()));
    }

    public ObjectNode updateArchive(JsonNode in, String region) {
        ObjectNode record = archive(region, in.path("ArchiveId").asText());
        if (!"ACTIVE".equals(record.path("ArchiveState").asText())) {
            throw MailManagerStore.conflict("The archive is pending deletion.");
        }
        if (in.hasNonNull("ArchiveName")) {
            String name = in.get("ArchiveName").asText();
            for (ObjectNode other : store.list(region, MailManagerStore.ARCHIVE, "")) {
                if (name.equals(other.path("ArchiveName").asText()) && !other.path("ArchiveId").asText()
                        .equals(record.path("ArchiveId").asText())) {
                    throw MailManagerStore.conflict("An archive named " + name + " already exists.");
                }
            }
            record.put("ArchiveName", name);
        }
        if (in.has("Retention")) {
            record.set("Retention", in.get("Retention").deepCopy());
        }
        record.put("LastUpdatedTimestamp", MailManagerStore.epoch());
        store.put(region, MailManagerStore.ARCHIVE, record.path("ArchiveId").asText(), record);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteArchive(JsonNode in, String region) {
        ObjectNode record = existing(() -> archive(region, in.path("ArchiveId").asText()));
        if (record == null) {
            return mapper.createObjectNode();
        }
        record.put("ArchiveState", "PENDING_DELETION");
        record.put("LastUpdatedTimestamp", MailManagerStore.epoch());
        store.put(region, MailManagerStore.ARCHIVE, record.path("ArchiveId").asText(), record);
        return mapper.createObjectNode();
    }

    public ObjectNode listArchives(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, MailManagerStore.ARCHIVE, "")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(record, summary, "ArchiveId", "ArchiveName", "ArchiveState", "LastUpdatedTimestamp");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "Archives", 20);
        return out;
    }

    // ================================================================ add-ons

    public ObjectNode createAddonSubscription(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateAddonSubscription", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("AddonName").asText();
        if (!ADDONS.containsKey(name)) {
            throw MailManagerStore.validation("The add-on " + name + " is not one of " + ADDONS.keySet() + ".");
        }
        for (ObjectNode other : store.list(region, MailManagerStore.SUBSCRIPTION, "")) {
            if (name.equals(other.path("AddonName").asText())) {
                throw MailManagerStore.conflict("The account already subscribes to " + name + ".");
            }
        }
        String id = MailManagerStore.id("as-");
        ObjectNode record = store.record();
        record.put("AddonSubscriptionId", id);
        record.put("AddonName", name);
        record.put("AddonSubscriptionArn", store.arn(region, "addon-subscription/" + id));
        record.put("CreatedTimestamp", MailManagerStore.epoch());
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, MailManagerStore.SUBSCRIPTION, id, record);
        return created(region, "CreateAddonSubscription", in, "AddonSubscriptionId", id);
    }

    public ObjectNode getAddonSubscription(JsonNode in, String region) {
        ObjectNode out = MailManagerStore.view(find(region, MailManagerStore.SUBSCRIPTION,
                in.path("AddonSubscriptionId").asText(), "add-on subscription"));
        out.remove("AddonSubscriptionId");
        return out;
    }

    public ObjectNode deleteAddonSubscription(JsonNode in, String region) {
        ObjectNode record = existing(() -> find(region, MailManagerStore.SUBSCRIPTION, in.path("AddonSubscriptionId").asText(),
                "add-on subscription"));
        if (record == null) {
            return mapper.createObjectNode();
        }
        String id = record.path("AddonSubscriptionId").asText();
        for (ObjectNode instance : store.list(region, MailManagerStore.INSTANCE, "")) {
            if (id.equals(instance.path("AddonSubscriptionId").asText())) {
                throw MailManagerStore.conflict("The subscription has add-on instances.");
            }
        }
        store.delete(region, MailManagerStore.SUBSCRIPTION, id);
        return mapper.createObjectNode();
    }

    public ObjectNode listAddonSubscriptions(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, MailManagerStore.SUBSCRIPTION, "").forEach(r -> items.add(MailManagerStore.view(r)));
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "AddonSubscriptions", 20);
        return out;
    }

    public ObjectNode createAddonInstance(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateAddonInstance", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode subscription = find(region, MailManagerStore.SUBSCRIPTION, in.path("AddonSubscriptionId")
                .asText(), "add-on subscription");
        String id = MailManagerStore.id("ai-");
        ObjectNode record = store.record();
        record.put("AddonInstanceId", id);
        record.put("AddonSubscriptionId", subscription.path("AddonSubscriptionId").asText());
        record.put("AddonName", subscription.path("AddonName").asText());
        record.put("AddonInstanceArn", store.arn(region, "addon-instance/" + id));
        record.put("CreatedTimestamp", MailManagerStore.epoch());
        if (in.path("Tags").isArray()) {
            MailManagerStore.addTags(record, in.get("Tags"));
        }
        store.put(region, MailManagerStore.INSTANCE, id, record);
        return created(region, "CreateAddonInstance", in, "AddonInstanceId", id);
    }

    public ObjectNode getAddonInstance(JsonNode in, String region) {
        ObjectNode out = MailManagerStore.view(find(region, MailManagerStore.INSTANCE,
                in.path("AddonInstanceId").asText(), "add-on instance"));
        out.remove("AddonInstanceId");
        return out;
    }

    public ObjectNode deleteAddonInstance(JsonNode in, String region) {
        ObjectNode record = existing(() -> find(region, MailManagerStore.INSTANCE, in.path("AddonInstanceId").asText(),
                "add-on instance"));
        if (record == null) {
            return mapper.createObjectNode();
        }
        store.delete(region, MailManagerStore.INSTANCE, record.path("AddonInstanceId").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listAddonInstances(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, MailManagerStore.INSTANCE, "").forEach(r -> items.add(MailManagerStore.view(r)));
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "AddonInstances", 20);
        return out;
    }

    // ================================================================ tags

    private static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("mailmanager-rule-set/", MailManagerStore.RULE_SET);
        KINDS.put("mailmanager-traffic-policy/", MailManagerStore.TRAFFIC_POLICY);
        KINDS.put("mailmanager-relay/", MailManagerStore.RELAY);
        KINDS.put("mailmanager-ingress-point/", MailManagerStore.INGRESS_POINT);
        KINDS.put("mailmanager-address-list/", MailManagerStore.ADDRESS_LIST);
        KINDS.put("mailmanager-archive/", MailManagerStore.ARCHIVE);
        KINDS.put("mailmanager-addon-subscription/", MailManagerStore.SUBSCRIPTION);
        KINDS.put("mailmanager-addon-instance/", MailManagerStore.INSTANCE);
    }

    private record Tagged(String kind, String id, ObjectNode record) {
    }

    private Tagged tagged(String arn, String region) {
        String resource = arn.contains(":") ? arn.substring(arn.lastIndexOf(':') + 1) : arn;
        for (Map.Entry<String, String> kind : KINDS.entrySet()) {
            if (resource.startsWith(kind.getKey())) {
                String id = resource.substring(kind.getKey().length());
                ObjectNode record = store.get(region, kind.getValue(), id)
                        .orElseThrow(() -> MailManagerStore.notFound("The resource " + arn + " does not exist."));
                return new Tagged(kind.getValue(), id, record);
            }
        }
        throw MailManagerStore.validation("The resource ARN " + arn + " is not a Mail Manager ARN.");
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Tagged tagged = tagged(in.path("ResourceArn").asText(), region);
        MailManagerStore.addTags(tagged.record(), in.path("Tags"));
        store.put(region, tagged.kind(), tagged.id(), tagged.record());
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Tagged tagged = tagged(in.path("ResourceArn").asText(), region);
        ObjectNode tags = MailManagerStore.tags(tagged.record());
        in.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        store.put(region, tagged.kind(), tagged.id(), tagged.record());
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        Tagged tagged = tagged(in.path("ResourceArn").asText(), region);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", store.tagList(tagged.record()));
        return out;
    }

    /** The record a lookup finds, or null when it finds none: deletes AWS answers without a not-found error succeed. */
    private static ObjectNode existing(Supplier<ObjectNode> lookup) {
        try {
            return lookup.get();
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
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

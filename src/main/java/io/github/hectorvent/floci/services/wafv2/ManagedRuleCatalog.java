package io.github.hectorvent.floci.services.wafv2;

import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static AWS-managed rule-group catalog. Floci does not fetch live AWS
 * managed-rule metadata; it returns enough of the published AWS catalog for
 * describe/list round-trips used by IaC and Alchemy bindings.
 */
final class ManagedRuleCatalog {

    record Version(String name, Instant lastUpdate) {}

    record Rule(String name, String action) {}

    record Group(
            String vendorName,
            String name,
            String description,
            boolean versioningSupported,
            long capacity,
            String labelNamespace,
            String productId,
            String productTitle,
            String productLink,
            boolean advanced,
            List<Version> versions,
            List<Rule> rules
    ) {
        String currentDefaultVersion() {
            return versions.isEmpty() ? null : versions.get(0).name();
        }
    }

    private static final Instant PUBLISHED = Instant.parse("2024-06-01T00:00:00Z");

    private static final Map<String, Group> GROUPS = build();

    private ManagedRuleCatalog() {}

    static List<Group> list(String vendorName) {
        List<Group> result = new ArrayList<>();
        for (Group group : GROUPS.values()) {
            if (vendorName == null || vendorName.equals(group.vendorName())) {
                result.add(group);
            }
        }
        return result;
    }

    static Group require(String vendorName, String name) {
        Group group = GROUPS.get(key(vendorName, name));
        if (group == null) {
            throw new AwsException("WAFNonexistentItemException",
                    "AWS WAF couldn't perform the operation because your resource doesn't exist.",
                    404);
        }
        return group;
    }

    private static String key(String vendor, String name) {
        return vendor + ":" + name;
    }

    private static Map<String, Group> build() {
        Map<String, Group> groups = new LinkedHashMap<>();
        add(groups, aws(
                "AWSManagedRulesCommonRuleSet",
                "Contains rules that are generally applicable to web applications.",
                700,
                "awswaf:managed:aws:core-rule-set:",
                "AWSManagedRulesCommonRuleSet",
                false,
                List.of("Version_1.16", "Version_1.15", "Version_1.14"),
                List.of(
                        new Rule("NoUserAgent_HEADER", "BLOCK"),
                        new Rule("UserAgent_BadBots_HEADER", "BLOCK"),
                        new Rule("SizeRestrictions_QUERYSTRING", "BLOCK"),
                        new Rule("SizeRestrictions_Cookie_HEADER", "BLOCK"),
                        new Rule("SizeRestrictions_BODY", "BLOCK"),
                        new Rule("SizeRestrictions_URIPATH", "BLOCK"),
                        new Rule("EC2MetaDataSSRF_BODY", "BLOCK"),
                        new Rule("GenericLFI_URIPATH", "BLOCK"),
                        new Rule("GenericRFI_QUERYARGUMENTS", "BLOCK"),
                        new Rule("RestrictedExtensions_URIPATH", "BLOCK"),
                        new Rule("GenericRFI_BODY", "BLOCK"),
                        new Rule("CrossSiteScripting_COOKIE", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesKnownBadInputsRuleSet",
                "Contains rules that inspect for known bad inputs.",
                200,
                "awswaf:managed:aws:known-bad-inputs:",
                "AWSManagedRulesKnownBadInputsRuleSet",
                false,
                List.of("Version_1.24", "Version_1.23"),
                List.of(
                        new Rule("JavaDeserializationRCE_BODY", "BLOCK"),
                        new Rule("Host_localhost_HEADER", "BLOCK"),
                        new Rule("PROPFIND_METHOD", "BLOCK"),
                        new Rule("ExploitablePaths_URIPATH", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesAmazonIpReputationList",
                "Inspects for IP addresses on the Amazon IP reputation list.",
                25,
                "awswaf:managed:aws:amazon-ip-list:",
                "AWSManagedRulesAmazonIpReputationList",
                false,
                List.of("Version_1.11", "Version_1.10"),
                List.of(
                        new Rule("AWSManagedIPReputationList", "BLOCK"),
                        new Rule("AWSManagedReconnaissanceList", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesAnonymousIpList",
                "Inspects for requests from anonymous IP sources such as VPNs and Tor.",
                50,
                "awswaf:managed:aws:anonymous-ip-list:",
                "AWSManagedRulesAnonymousIpList",
                false,
                List.of("Version_1.8", "Version_1.7"),
                List.of(
                        new Rule("AnonymousIPList", "BLOCK"),
                        new Rule("HostingProviderIPList", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesSQLiRuleSet",
                "Inspects for request patterns associated with SQL injection.",
                200,
                "awswaf:managed:aws:sql-database:",
                "AWSManagedRulesSQLiRuleSet",
                false,
                List.of("Version_1.3", "Version_1.2"),
                List.of(
                        new Rule("SQLi_QUERYARGUMENTS", "BLOCK"),
                        new Rule("SQLiExtendedPatterns_QUERYARGUMENTS", "BLOCK"),
                        new Rule("SQLi_BODY", "BLOCK"),
                        new Rule("SQLi_COOKIE", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesLinuxRuleSet",
                "Inspects for request patterns associated with Linux-specific vulnerabilities.",
                200,
                "awswaf:managed:aws:linux-os:",
                "AWSManagedRulesLinuxRuleSet",
                false,
                List.of("Version_2.6", "Version_2.5"),
                List.of(
                        new Rule("LFI_URIPATH", "BLOCK"),
                        new Rule("LFI_QUERYSTRING", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesUnixRuleSet",
                "Inspects for request patterns associated with POSIX/UNIX vulnerabilities.",
                100,
                "awswaf:managed:aws:posix-os:",
                "AWSManagedRulesUnixRuleSet",
                false,
                List.of("Version_2.2", "Version_2.1"),
                List.of(new Rule("UNIXShellCommandsVariables_QUERYARGUMENTS", "BLOCK"))
        ));
        add(groups, aws(
                "AWSManagedRulesWindowsRuleSet",
                "Inspects for request patterns associated with Windows-specific vulnerabilities.",
                200,
                "awswaf:managed:aws:windows-os:",
                "AWSManagedRulesWindowsRuleSet",
                false,
                List.of("Version_2.3", "Version_2.2"),
                List.of(
                        new Rule("WindowsShellCommands_COOKIE", "BLOCK"),
                        new Rule("PowerShellCommands_BODY", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesPHPRuleSet",
                "Inspects for request patterns associated with PHP-specific vulnerabilities.",
                100,
                "awswaf:managed:aws:php-app:",
                "AWSManagedRulesPHPRuleSet",
                false,
                List.of("Version_2.1", "Version_2.0"),
                List.of(
                        new Rule("PHPHighRiskMethodsVariables_HEADER", "BLOCK"),
                        new Rule("PHPHighRiskMethodsVariables_QUERYSTRING", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesWordPressRuleSet",
                "Inspects for request patterns associated with WordPress vulnerabilities.",
                100,
                "awswaf:managed:aws:wordpress-app:",
                "AWSManagedRulesWordPressRuleSet",
                false,
                List.of("Version_1.3", "Version_1.2"),
                List.of(
                        new Rule("WordPressExploitableCommands_QUERYSTRING", "BLOCK"),
                        new Rule("WordPressExploitablePaths_URIPATH", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesBotControlRuleSet",
                "Inspects for bot traffic using AWS WAF Bot Control.",
                50,
                "awswaf:managed:aws:bot-control:",
                "AWSManagedRulesBotControlRuleSet",
                true,
                List.of("Version_3.2", "Version_3.1"),
                List.of(
                        new Rule("CategoryAdvertising", "BLOCK"),
                        new Rule("CategoryArchiver", "BLOCK"),
                        new Rule("CategoryHttpLibrary", "BLOCK"),
                        new Rule("SignalNonBrowserUserAgent", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesATPRuleSet",
                "AWS WAF Fraud Control account takeover prevention.",
                50,
                "awswaf:managed:aws:atp:",
                "AWSManagedRulesATPRuleSet",
                true,
                List.of("Version_1.1", "Version_1.0"),
                List.of(
                        new Rule("VolumetricIpHigh", "BLOCK"),
                        new Rule("AttributeCompromisedCredentials", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesACFPRuleSet",
                "AWS WAF Fraud Control account creation fraud prevention.",
                50,
                "awswaf:managed:aws:acfp:",
                "AWSManagedRulesACFPRuleSet",
                true,
                List.of("Version_1.1", "Version_1.0"),
                List.of(
                        new Rule("VolumetricIpHigh", "BLOCK"),
                        new Rule("AttributeSuspiciousTlsFingerprint", "BLOCK")
                )));
        add(groups, aws(
                "AWSManagedRulesAntiDDoSRuleSet",
                "AWS WAF anti-DDoS managed rule group.",
                50,
                "awswaf:managed:aws:anti-ddos:",
                "AWSManagedRulesAntiDDoSRuleSet",
                true,
                List.of("Version_1.0"),
                List.of(new Rule("ChallengeAllDuringEvent", "BLOCK"))
        ));
        return Map.copyOf(groups);
    }

    private static Group aws(String name, String description, long capacity,
                             String labelNamespace, String productId, boolean advanced,
                             List<String> versions, List<Rule> rules) {
        List<Version> versionList = versions.stream()
                .map(v -> new Version(v, PUBLISHED))
                .toList();
        return new Group(
                "AWS",
                name,
                description,
                true,
                capacity,
                labelNamespace,
                productId,
                name,
                "https://docs.aws.amazon.com/waf/latest/developerguide/aws-managed-rule-groups-list.html",
                advanced,
                versionList,
                rules);
    }

    private static void add(Map<String, Group> groups, Group group) {
        groups.put(key(group.vendorName(), group.name()), group);
    }
}

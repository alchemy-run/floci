package io.floci.aws.services.route53profiles;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.DnsForwardingRule;
import io.floci.aws.core.common.dns.DnsForwardingRuleSource;
import io.floci.aws.services.route53resolver.Route53ResolverDnsForwardingRules;
import io.floci.aws.services.route53resolver.Route53ResolverService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Resolver rules a Route 53 Profile applies to the VPCs it is associated with, handed to the embedded DNS
 * server alongside the rules associated with a VPC directly. A rule reaches a VPC this way once both the VPC's
 * profile association and the rule's resource association are in place, and stops when either is removed.
 */
@ApplicationScoped
public class Route53ProfilesDnsForwardingRules implements DnsForwardingRuleSource {

    private final Route53ProfilesService profiles;
    private final Route53ResolverService resolver;
    private final EmulatorConfig config;

    @Inject
    public Route53ProfilesDnsForwardingRules(Route53ProfilesService profiles, Route53ResolverService resolver,
                                             EmulatorConfig config) {
        this.profiles = profiles;
        this.resolver = resolver;
        this.config = config;
    }

    @Override
    public List<DnsForwardingRule> rulesFor(String accountId, String region, String vpcId) {
        if (!config.services().route53profiles().enabled() || !config.services().route53resolver().enabled()) {
            return List.of();
        }
        List<DnsForwardingRule> rules = new ArrayList<>();
        for (String ruleId : profiles.resolverRuleIdsFor(accountId, region, vpcId)) {
            resolver.resolverRuleIn(accountId, region, ruleId)
                    .flatMap(Route53ResolverDnsForwardingRules::forwardingRuleFor)
                    .ifPresent(rules::add);
        }
        rules.sort(Comparator.comparingInt(DnsForwardingRule::specificity).reversed()
                .thenComparing(DnsForwardingRule::domainName));
        return List.copyOf(rules);
    }
}

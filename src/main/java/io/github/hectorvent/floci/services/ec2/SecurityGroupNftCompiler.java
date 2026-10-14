package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds only rules for an endpoint's private nftables table. Docker's tables are untouched. */
public final class SecurityGroupNftCompiler {

    private SecurityGroupNftCompiler() {}

    public record Endpoint(String accountId, String region, String vpcId, String eniId,
                           String logicalAddress, String transportAddress, Set<String> groupIds,
                           List<SecurityGroup> groups) {
        SecurityGroupPolicy.Peer peer() {
            return new SecurityGroupPolicy.Peer(logicalAddress, accountId, vpcId, groupIds);
        }
    }

    public static String initialRuleset() {
        return "add table inet floci_sg\n"
                + "add chain inet floci_sg ingress { type filter hook input priority -10; policy drop; }\n"
                + "add chain inet floci_sg egress { type filter hook output priority -10; policy drop; }\n";
    }

    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                 Map<String, List<String>> prefixLists) {
        if (target == null || target.groups() == null || target.groups().isEmpty()) {
            throw new IllegalArgumentException("Protected endpoint needs at least one security group");
        }
        StringBuilder rules = new StringBuilder(privateAddressTranslation(target, peers))
                .append("flush chain inet floci_sg ingress\n")
                .append("flush chain inet floci_sg egress\n")
                .append("add rule inet floci_sg ingress iifname lo accept\n")
                .append("add rule inet floci_sg egress oifname lo accept\n")
                .append("add rule inet floci_sg ingress ct state established,related accept\n")
                .append("add rule inet floci_sg egress ct state established,related accept\n");

        // Docker's embedded DNS is loopback and covered above. DHCP and the
        // link-local metadata endpoint are the other local infrastructure exceptions.
        rules.append("add rule inet floci_sg egress udp sport 68 udp dport 67 accept\n")
                .append("add rule inet floci_sg ingress udp sport 67 udp dport 68 accept\n")
                .append("add rule inet floci_sg egress ip daddr 169.254.169.254 accept\n")
                .append("add rule inet floci_sg ingress ip saddr 169.254.169.254 accept\n");

        List<Endpoint> managed = peers == null ? List.of() : peers.stream()
                .filter(peer -> !target.eniId().equals(peer.eniId()))
                .filter(peer -> peer.transportAddress() != null && literal(peer.transportAddress()))
                .toList();

        for (boolean egress : new boolean[]{false, true}) {
            String chain = egress ? "egress" : "ingress";
            String addressField = egress ? "daddr" : "saddr";
            for (SecurityGroup group : target.groups()) {
                List<IpPermission> permissions = egress
                        ? group.getIpPermissionsEgress() : group.getIpPermissions();
                if (permissions == null) {
                    continue;
                }
                for (IpPermission permission : permissions) {
                    String protocol = protocolExpression(permission);
                    if (protocol == null) {
                        continue;
                    }
                    for (Endpoint peer : managed) {
                        if (target.accountId().equals(peer.accountId())
                                && target.region().equals(peer.region())
                                && target.vpcId().equals(peer.vpcId())
                                && SecurityGroupPolicy.matchesPeer(group, permission, peer.peer(), prefixLists)) {
                            appendRule(rules, chain, addressField, peer.transportAddress(), protocol);
                        }
                    }
                }
            }
            // A managed peer must never fall through to a broad external CIDR rule
            // evaluated against its Docker bridge IP instead of its logical ENI address.
            for (Endpoint peer : managed) {
                appendRule(rules, chain, addressField, peer.transportAddress(), "drop");
            }
            for (SecurityGroup group : target.groups()) {
                List<IpPermission> permissions = egress
                        ? group.getIpPermissionsEgress() : group.getIpPermissions();
                if (permissions == null) {
                    continue;
                }
                for (IpPermission permission : permissions) {
                    String protocol = protocolExpression(permission);
                    if (protocol == null) {
                        continue;
                    }
                    Set<String> cidrs = new LinkedHashSet<>();
                    if (permission.getIpRanges() != null) {
                        permission.getIpRanges().forEach(range -> cidrs.add(range.getCidrIp()));
                    }
                    if (permission.getIpv6Ranges() != null) {
                        permission.getIpv6Ranges().forEach(range -> cidrs.add(range.getCidrIpv6()));
                    }
                    if (permission.getPrefixListIds() != null && prefixLists != null) {
                        permission.getPrefixListIds().forEach(ref ->
                                cidrs.addAll(prefixLists.getOrDefault(ref.getPrefixListId(), List.of())));
                    }
                    for (String cidr : cidrs) {
                        if (SecurityGroupPolicy.validCidr(cidr)) {
                            appendRule(rules, chain, addressField, cidr, protocol);
                        }
                    }
                }
            }
        }
        return rules.toString();
    }

    static String compileNetworkOnly(Endpoint target, Collection<Endpoint> peers) {
        StringBuilder rules = new StringBuilder(privateAddressTranslation(target, peers))
                .append("flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n");
        for (Endpoint peer : peers) {
            if (!target.accountId().equals(peer.accountId()) || !target.region().equals(peer.region())
                    || !target.vpcId().equals(peer.vpcId())) {
                if (!ipv4(peer.transportAddress())) {
                    throw new IllegalArgumentException("Private network isolation requires IPv4 endpoints");
                }
                appendRule(rules, "ingress", "saddr", peer.transportAddress(), "drop");
                appendRule(rules, "egress", "daddr", peer.transportAddress(), "drop");
            }
        }
        return rules.append("add rule inet floci_sg ingress accept\n")
                .append("add rule inet floci_sg egress accept\n").toString();
    }

    /** Output DNAT runs before SG filtering; postrouting fixes the source after the route changes. */
    static String privateAddressTranslation(Endpoint target, Collection<Endpoint> peers) {
        StringBuilder rules = new StringBuilder(
                "add chain inet floci_sg private_output { type nat hook output priority -100; policy accept; }\n")
                .append("add chain inet floci_sg private_source { type nat hook postrouting priority 100; policy accept; }\n")
                .append("flush chain inet floci_sg private_output\n")
                .append("flush chain inet floci_sg private_source\n");
        if (peers == null) {
            return rules.toString();
        }
        Map<String, String> destinations = new LinkedHashMap<>();
        for (Endpoint peer : peers) {
            if (!target.accountId().equals(peer.accountId()) || !target.region().equals(peer.region())
                    || !target.vpcId().equals(peer.vpcId())) {
                continue;
            }
            if (!ipv4(peer.logicalAddress()) || !ipv4(peer.transportAddress()) || !ipv4(target.transportAddress())) {
                throw new IllegalArgumentException("Private address translation requires IPv4 endpoints");
            }
            String previous = destinations.putIfAbsent(peer.logicalAddress(), peer.transportAddress());
            if (previous != null && !previous.equals(peer.transportAddress())) {
                throw new IllegalStateException("Two ENIs claim the same logical address in one VPC");
            }
        }
        for (Map.Entry<String, String> entry : destinations.entrySet()) {
            if (!entry.getKey().equals(entry.getValue())) {
                rules.append("add rule inet floci_sg private_output ip daddr ").append(entry.getKey())
                        .append(" dnat ip to ").append(entry.getValue()).append('\n');
            }
            rules.append("add rule inet floci_sg private_source ip daddr ").append(entry.getValue())
                    .append(" snat ip to ").append(target.transportAddress()).append('\n');
        }
        return rules.toString();
    }

    private static boolean ipv4(String address) {
        return address != null && address.indexOf(':') < 0 && literal(address);
    }

    private static void appendRule(StringBuilder rules, String chain, String addressField,
                                   String address, String expression) {
        rules.append("add rule inet floci_sg ").append(chain).append(' ')
                .append(address.indexOf(':') >= 0 ? "ip6 " : "ip ")
                .append(addressField).append(' ').append(address).append(' ')
                .append(expression).append('\n');
    }

    private static String protocolExpression(IpPermission permission) {
        String protocol = permission.getIpProtocol();
        if (protocol == null) {
            return null;
        }
        if ("-1".equals(protocol)) {
            return "accept";
        }
        String normalized = switch (protocol.toLowerCase(Locale.ROOT)) {
            case "6" -> "tcp";
            case "17" -> "udp";
            case "1" -> "icmp";
            case "58" -> "icmpv6";
            default -> protocol.toLowerCase(Locale.ROOT);
        };
        if ("tcp".equals(normalized) || "udp".equals(normalized)) {
            Integer from = permission.getFromPort();
            Integer to = permission.getToPort();
            if (from == null || to == null || from < 0 || to > 65535 || to < from) {
                return null;
            }
            return normalized + " dport " + (from.equals(to) ? from : from + "-" + to) + " accept";
        }
        if ("icmp".equals(normalized) || "icmpv6".equals(normalized)) {
            // nftables header fields are separate expressions, so type and code each repeat
            // the protocol keyword: "icmp type 8 icmp code 0", never "icmp type 8 code 0".
            StringBuilder expression = new StringBuilder();
            Integer type = permission.getFromPort();
            Integer code = permission.getToPort();
            if (type != null && type >= 0) {
                expression.append(normalized).append(" type ").append(type).append(' ');
            }
            if (code != null && code >= 0) {
                expression.append(normalized).append(" code ").append(code).append(' ');
            }
            if (expression.isEmpty()) {
                expression.append("meta l4proto ").append(normalized).append(' ');
            }
            return expression.append("accept").toString();
        }
        try {
            int number = Integer.parseInt(normalized);
            return number >= 0 && number <= 255 ? "meta l4proto " + number + " accept" : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean literal(String address) {
        try {
            if (address == null || address.contains("%")
                    || !(address.contains(":") ? address.matches("[0-9a-fA-F:.]+")
                    : address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
                return false;
            }
            InetAddress.getByName(address);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}

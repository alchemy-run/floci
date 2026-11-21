package io.floci.aws.services.mailmanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * Evaluates traffic policy statements and rule conditions. Strings compare case-insensitively; an attribute with
 * several values (the recipients, the To and Cc addresses) matches when any value does. SPF and DKIM verdicts are
 * {@code GRAY} and the DMARC policy is {@code NONE}: Floci checks neither. Add-on analyses report no findings.
 */
final class MailManagerPolicies {

    private MailManagerPolicies() {
    }

    /** What the policies see of a message: its envelope, connection and (once received) headers and size. */
    record Mail(String helo, String mailFrom, List<String> recipients, String sourceIp, boolean tls,
                String tlsProtocol, MailManagerMime.Parsed message, long size) {
    }

    /** Membership of an address in an address list, by id or ARN. */
    interface Lists extends BiPredicate<String, String> {
    }

    // ================================================================ traffic policies

    /** Whether a traffic policy accepts mail for one recipient: the first statement whose conditions all hold. */
    static boolean accepts(JsonNode policy, Mail mail, String recipient, Lists lists) {
        for (JsonNode statement : policy.path("PolicyStatements")) {
            boolean all = true;
            for (JsonNode condition : statement.path("Conditions")) {
                all &= ingressCondition(condition, mail, recipient, lists);
            }
            if (all) {
                return "ALLOW".equals(statement.path("Action").asText());
            }
        }
        return "ALLOW".equals(policy.path("DefaultAction").asText("ALLOW"));
    }

    private static boolean ingressCondition(JsonNode condition, Mail mail, String recipient, Lists lists) {
        if (condition.has("StringExpression")) {
            JsonNode expression = condition.get("StringExpression");
            String value = expression.path("Evaluate").has("Analysis") ? "" : recipient;
            return strings(expression.path("Operator").asText(), List.of(value), expression.path("Values"));
        }
        if (condition.has("IpExpression")) {
            JsonNode expression = condition.get("IpExpression");
            return cidrs(expression.path("Operator").asText(), mail.sourceIp(), expression.path("Values"));
        }
        if (condition.has("Ipv6Expression")) {
            JsonNode expression = condition.get("Ipv6Expression");
            return cidrs(expression.path("Operator").asText(), mail.sourceIp(), expression.path("Values"));
        }
        if (condition.has("TlsExpression")) {
            JsonNode expression = condition.get("TlsExpression");
            String wanted = expression.path("Value").asText();
            String actual = mail.tls() ? mail.tlsProtocol() : "";
            if ("IS".equals(expression.path("Operator").asText())) {
                return wanted.equals(actual);
            }
            return !actual.isEmpty() && actual.compareTo(wanted) >= 0;
        }
        if (condition.has("BooleanExpression")) {
            JsonNode expression = condition.get("BooleanExpression");
            boolean value = false;
            JsonNode lookup = expression.path("Evaluate").path("IsInAddressList");
            if (lookup.isObject()) {
                for (JsonNode list : lookup.path("AddressLists")) {
                    value |= lists.test(list.asText(), recipient);
                }
            }
            return "IS_TRUE".equals(expression.path("Operator").asText()) == value;
        }
        return false;
    }

    // ================================================================ rules

    /** Whether a rule applies to a message: all its conditions hold and none of its unless conditions do. */
    static boolean applies(JsonNode rule, Mail mail, Lists lists) {
        for (JsonNode condition : rule.path("Conditions")) {
            if (!ruleCondition(condition, mail, lists)) {
                return false;
            }
        }
        for (JsonNode condition : rule.path("Unless")) {
            if (ruleCondition(condition, mail, lists)) {
                return false;
            }
        }
        return true;
    }

    private static boolean ruleCondition(JsonNode condition, Mail mail, Lists lists) {
        if (condition.has("BooleanExpression")) {
            JsonNode expression = condition.get("BooleanExpression");
            JsonNode evaluate = expression.path("Evaluate");
            boolean value;
            if (evaluate.has("IsInAddressList")) {
                value = false;
                JsonNode lookup = evaluate.get("IsInAddressList");
                for (String address : addresses(lookup.path("Attribute").asText(), mail)) {
                    for (JsonNode list : lookup.path("AddressLists")) {
                        value |= lists.test(list.asText(), address);
                    }
                }
            } else if (evaluate.has("Analysis")) {
                value = false;
            } else {
                value = switch (evaluate.path("Attribute").asText()) {
                    case "READ_RECEIPT_REQUESTED" -> mail.message() != null
                            && mail.message().header("Disposition-Notification-To") != null;
                    case "TLS" -> mail.tls();
                    default -> false;
                };
            }
            return "IS_TRUE".equals(expression.path("Operator").asText()) == value;
        }
        if (condition.has("StringExpression")) {
            JsonNode expression = condition.get("StringExpression");
            JsonNode evaluate = expression.path("Evaluate");
            List<String> values;
            if (evaluate.has("MimeHeaderAttribute")) {
                String header = mail.message() == null ? null : mail.message().header(evaluate.get(
                        "MimeHeaderAttribute").asText());
                values = header == null ? List.of() : List.of(header);
            } else if (evaluate.has("Analysis") || evaluate.has("ClientCertificateAttribute")) {
                values = List.of();
            } else {
                values = strings(evaluate.path("Attribute").asText(), mail);
            }
            return strings(expression.path("Operator").asText(), values, expression.path("Values"));
        }
        if (condition.has("NumberExpression")) {
            JsonNode expression = condition.get("NumberExpression");
            double value = mail.size();
            double wanted = expression.path("Value").asDouble();
            return switch (expression.path("Operator").asText()) {
                case "EQUALS" -> value == wanted;
                case "NOT_EQUALS" -> value != wanted;
                case "LESS_THAN" -> value < wanted;
                case "GREATER_THAN" -> value > wanted;
                case "LESS_THAN_OR_EQUAL" -> value <= wanted;
                default -> value >= wanted;
            };
        }
        if (condition.has("IpExpression")) {
            JsonNode expression = condition.get("IpExpression");
            return cidrs(expression.path("Operator").asText(), mail.sourceIp(), expression.path("Values"));
        }
        if (condition.has("VerdictExpression")) {
            JsonNode expression = condition.get("VerdictExpression");
            return strings("EQUALS".equals(expression.path("Operator").asText()) ? "EQUALS" : "NOT_EQUALS",
                    List.of("GRAY"), expression.path("Values"));
        }
        if (condition.has("DmarcExpression")) {
            JsonNode expression = condition.get("DmarcExpression");
            return strings("EQUALS".equals(expression.path("Operator").asText()) ? "EQUALS" : "NOT_EQUALS",
                    List.of("NONE"), expression.path("Values"));
        }
        return false;
    }

    private static List<String> strings(String attribute, Mail mail) {
        MailManagerMime.Parsed message = mail.message();
        return switch (attribute) {
            case "MAIL_FROM" -> List.of(mail.mailFrom());
            case "HELO" -> List.of(mail.helo() == null ? "" : mail.helo());
            case "RECIPIENT" -> mail.recipients();
            case "SENDER" -> message == null || message.header("Sender") == null ? List.of()
                    : List.of(message.header("Sender"));
            case "FROM" -> message == null ? List.of() : message.addresses("From");
            case "TO" -> message == null ? List.of() : message.addresses("To");
            case "CC" -> message == null ? List.of() : message.addresses("Cc");
            case "SUBJECT" -> message == null || message.header("Subject") == null ? List.of()
                    : List.of(message.header("Subject"));
            default -> List.of();
        };
    }

    private static List<String> addresses(String attribute, Mail mail) {
        return switch (attribute) {
            case "RECIPIENT", "MAIL_FROM", "SENDER", "FROM", "TO", "CC" -> {
                List<String> values = new ArrayList<>();
                for (String value : strings(attribute, mail)) {
                    values.add(MailManagerMime.bare(value));
                }
                yield values;
            }
            default -> List.of();
        };
    }

    static boolean strings(String operator, List<String> actual, JsonNode wanted) {
        boolean negate = "NOT_EQUALS".equals(operator);
        for (String value : actual) {
            String v = value.toLowerCase(Locale.ROOT);
            for (JsonNode candidate : wanted) {
                String w = candidate.asText().toLowerCase(Locale.ROOT);
                boolean match = switch (operator) {
                    case "STARTS_WITH" -> v.startsWith(w);
                    case "ENDS_WITH" -> v.endsWith(w);
                    case "CONTAINS" -> v.contains(w);
                    default -> v.equals(w);
                };
                if (match) {
                    return !negate;
                }
            }
        }
        return negate;
    }

    private static boolean cidrs(String operator, String ip, JsonNode values) {
        boolean match = false;
        for (JsonNode cidr : values) {
            match |= inCidr(ip, cidr.asText());
        }
        return "NOT_CIDR_MATCHES".equals(operator) != match;
    }

    static boolean validCidr(String cidr, boolean v6) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(parts[0]);
            int bits = Integer.parseInt(parts[1]);
            return bits >= 0 && bits <= address.getAddress().length * 8 && (v6 || address.getAddress().length == 4);
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    static boolean inCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] address = InetAddress.getByName(ip).getAddress();
            if (network.length != address.length) {
                return false;
            }
            int bits = Integer.parseInt(parts[1]);
            int length = network.length * 8;
            BigInteger mask = bits == 0 ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(length).subtract(BigInteger.ONE).shiftRight(length - bits)
                    .shiftLeft(length - bits);
            return new BigInteger(1, network).and(mask).equals(new BigInteger(1, address).and(mask));
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }
}

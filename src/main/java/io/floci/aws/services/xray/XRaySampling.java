package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * X-Ray sampling as the service applies it: the first matching rule by priority samples up to its reservoir each
 * second and a fixed rate of the rest. Statistics reported by SDK clients ({@code GetSamplingTargets}) and by
 * decisions floci makes itself (traced Lambda invocations) are aggregated per rule into ten-second windows, and each
 * reporting client gets a share of the rule's reservoir.
 */
final class XRaySampling {

    static final long WINDOW_MILLIS = 10_000;
    static final long CLIENT_TTL_MILLIS = 30_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One rule's statistics for one window. */
    static final class Window {
        long start;
        long requests;
        long sampled;
        long borrowed;
    }

    private final Map<String, Window> current = new ConcurrentHashMap<>();
    private final Map<String, Window> last = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> clients = new ConcurrentHashMap<>();
    private final Map<String, long[]> reservoirs = new ConcurrentHashMap<>();

    /** The first rule, by priority then name, that matches the request. */
    static JsonNode match(List<JsonNode> rules, String service, String serviceType, String host, String method,
                          String path, Map<String, String> attributes) {
        return rules.stream()
                .sorted(Comparator.comparingInt((JsonNode r) -> r.path("Priority").asInt())
                        .thenComparing(r -> r.path("RuleName").asText()))
                .filter(rule -> glob(rule.path("ServiceName").asText("*"), service)
                        && glob(rule.path("ServiceType").asText("*"), serviceType)
                        && glob(rule.path("Host").asText("*"), host)
                        && glob(rule.path("HTTPMethod").asText("*"), method)
                        && glob(rule.path("URLPath").asText("*"), path)
                        && attributesMatch(rule.path("Attributes"), attributes))
                .findFirst().orElse(null);
    }

    private static boolean attributesMatch(JsonNode wanted, Map<String, String> attributes) {
        Iterator<Map.Entry<String, JsonNode>> fields = wanted.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!glob(field.getValue().asText(), attributes.getOrDefault(field.getKey(), ""))) {
                return false;
            }
        }
        return true;
    }

    /** X-Ray's wildcards: {@code *} for any characters and {@code ?} for one, case-insensitively. */
    static boolean glob(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        String text = value == null ? "" : value.toLowerCase();
        String p = pattern.toLowerCase();
        return glob(p, 0, text, 0);
    }

    private static boolean glob(String p, int pi, String t, int ti) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                for (int k = ti; k <= t.length(); k++) {
                    if (glob(p, pi + 1, t, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (ti >= t.length() || (c != '?' && c != t.charAt(ti))) {
                return false;
            }
            pi++;
            ti++;
        }
        return ti == t.length();
    }

    /** Decides one request against the rule, as the service samples it, and counts the decision. */
    boolean decide(String scope, JsonNode rule) {
        String key = scope + "/" + rule.path("RuleName").asText();
        long now = System.currentTimeMillis();
        boolean sampled;
        long[] reservoir = reservoirs.computeIfAbsent(key, k -> new long[]{0, 0});
        synchronized (reservoir) {
            long second = now / 1000;
            if (reservoir[0] != second) {
                reservoir[0] = second;
                reservoir[1] = 0;
            }
            if (reservoir[1] < rule.path("ReservoirSize").asInt()) {
                reservoir[1]++;
                sampled = true;
            } else {
                sampled = RANDOM.nextDouble() < rule.path("FixedRate").asDouble();
            }
        }
        record(key, now, 1, sampled ? 1 : 0, 0);
        return sampled;
    }

    /** Adds a client's reported counts to the rule's current window. */
    void report(String scope, String rule, String clientId, long timestamp, long requests, long sampled,
                long borrowed) {
        String key = scope + "/" + rule;
        clients.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(clientId, System.currentTimeMillis());
        record(key, timestamp, requests, sampled, borrowed);
    }

    private void record(String key, long timestamp, long requests, long sampled, long borrowed) {
        long start = timestamp - timestamp % WINDOW_MILLIS;
        Window window = current.compute(key, (k, existing) -> {
            if (existing != null && existing.start == start) {
                return existing;
            }
            if (existing != null) {
                last.put(k, existing);
            }
            Window fresh = new Window();
            fresh.start = start;
            return fresh;
        });
        synchronized (window) {
            window.requests += requests;
            window.sampled += sampled;
            window.borrowed += borrowed;
        }
    }

    /** The rule's most recent statistics: the window under way, or the last one completed. */
    Window summary(String scope, String rule) {
        String key = scope + "/" + rule;
        Window window = current.get(key);
        return window != null ? window : last.get(key);
    }

    /** The client's share of the rule's reservoir: an even split among clients that reported recently. */
    int quota(String scope, String rule, String clientId, int reservoirSize) {
        Map<String, Long> active = clients.getOrDefault(scope + "/" + rule, Map.of());
        long now = System.currentTimeMillis();
        List<String> live = active.entrySet().stream().filter(e -> now - e.getValue() < CLIENT_TTL_MILLIS)
                .map(Map.Entry::getKey).sorted().toList();
        if (live.isEmpty()) {
            return reservoirSize;
        }
        int share = reservoirSize / live.size();
        int remainder = reservoirSize % live.size();
        int index = live.indexOf(clientId);
        return share + (index >= 0 && index < remainder ? 1 : 0);
    }

    void forget(String scope, String rule) {
        String key = scope + "/" + rule;
        current.remove(key);
        last.remove(key);
        clients.remove(key);
        reservoirs.remove(key);
    }

    void clear() {
        current.clear();
        last.clear();
        clients.clear();
        reservoirs.clear();
    }
}

package io.floci.aws.services.grafana;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Grafana versions Amazon Managed Grafana offers, oldest first, and the open source Grafana release floci runs
 * for each: the last patch of the line the version names. Version 8.4 is no longer offered (2026-10-01).
 */
final class GrafanaVersions {

    private static final Map<String, String> RELEASES = new LinkedHashMap<>();

    static {
        RELEASES.put("9.4", "9.4.17");
        RELEASES.put("10.4", "10.4.19");
        RELEASES.put("12.4", "12.4.15");
        RELEASES.put("13.2", "13.2.6");
    }

    private GrafanaVersions() {
    }

    static List<String> all() {
        return List.copyOf(RELEASES.keySet());
    }

    /** The version a workspace is created with when it names none: the latest. */
    static String latest() {
        List<String> all = all();
        return all.get(all.size() - 1);
    }

    static boolean supported(String version) {
        return RELEASES.containsKey(version);
    }

    static String release(String version) {
        return RELEASES.get(version);
    }

    /** The versions a workspace on {@code version} can be upgraded to, in order. */
    static List<String> after(String version) {
        List<String> all = all();
        int index = all.indexOf(version);
        return index < 0 ? all : all.subList(index + 1, all.size());
    }

    /** Whether unified alerting is on by default: Grafana 10 and later. */
    static boolean unifiedAlertingByDefault(String version) {
        return !version.startsWith("9.");
    }
}

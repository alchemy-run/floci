package io.github.hectorvent.floci.services.emr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Static EMR release catalog used by ListReleaseLabels, DescribeReleaseLabel, and
 * ListSupportedInstanceTypes. Newest labels first, matching live EMR.
 */
public final class EmrReleaseCatalog {

    public record Application(String name, String version) {}

    public record SupportedInstanceType(
            String type,
            double memoryGb,
            int storageGb,
            int vcpu,
            boolean is64BitsOnly,
            String instanceFamilyId,
            boolean ebsOptimizedAvailable,
            boolean ebsOptimizedByDefault,
            int numberOfDisks,
            boolean ebsStorageOnly,
            String architecture) {}

    public record Release(String label, List<Application> applications, List<String> osReleases) {}

    private static final List<Application> EMR7_APPS = List.of(
            new Application("AmazonCloudWatchAgent", "1.300032.2"),
            new Application("Flink", "1.19.1"),
            new Application("Hadoop", "3.4.0"),
            new Application("Hive", "3.1.3"),
            new Application("Hudi", "0.15.0"),
            new Application("Hue", "4.11.0"),
            new Application("Iceberg", "1.6.1"),
            new Application("JupyterEnterpriseGateway", "2.6.0"),
            new Application("Livy", "0.8.0"),
            new Application("Spark", "3.5.2"),
            new Application("TensorFlow", "2.16.1"),
            new Application("Tez", "0.10.2"),
            new Application("ZooKeeper", "3.9.1"));

    private static final List<Application> EMR6_APPS = List.of(
            new Application("Flink", "1.17.1"),
            new Application("Hadoop", "3.3.6"),
            new Application("Hive", "3.1.3"),
            new Application("Hue", "4.11.0"),
            new Application("Spark", "3.4.1"),
            new Application("Tez", "0.10.2"),
            new Application("ZooKeeper", "3.5.10"));

    private static final List<String> AL2023 = List.of("2023");
    private static final List<String> AL2 = List.of("2");

    /** Newest first, as live {@code ListReleaseLabels} returns. */
    private static final List<Release> RELEASES = List.of(
            release("emr-7.9.0", EMR7_APPS, AL2023),
            release("emr-7.8.0", EMR7_APPS, AL2023),
            release("emr-7.7.0", EMR7_APPS, AL2023),
            release("emr-7.6.0", EMR7_APPS, AL2023),
            release("emr-7.5.0", EMR7_APPS, AL2023),
            release("emr-7.4.0", EMR7_APPS, AL2023),
            release("emr-7.3.0", EMR7_APPS, AL2023),
            release("emr-7.2.0", EMR7_APPS, AL2023),
            release("emr-7.1.0", EMR7_APPS, AL2023),
            release("emr-7.0.0", EMR7_APPS, AL2023),
            release("emr-6.15.0", EMR6_APPS, AL2),
            release("emr-6.14.0", EMR6_APPS, AL2),
            release("emr-6.13.0", EMR6_APPS, AL2),
            release("emr-6.12.0", EMR6_APPS, AL2),
            release("emr-6.11.0", EMR6_APPS, AL2),
            release("emr-6.10.0", EMR6_APPS, AL2));

    private static final List<SupportedInstanceType> INSTANCE_TYPES = List.of(
            instance("m5.xlarge", 16.0, 4, "m5", "x86_64"),
            instance("m5.2xlarge", 32.0, 8, "m5", "x86_64"),
            instance("m5.4xlarge", 64.0, 16, "m5", "x86_64"),
            instance("m6g.xlarge", 16.0, 4, "m6g", "arm64"),
            instance("c5.xlarge", 8.0, 4, "c5", "x86_64"),
            instance("c5.2xlarge", 16.0, 8, "c5", "x86_64"),
            instance("r5.xlarge", 32.0, 4, "r5", "x86_64"),
            instance("r5.2xlarge", 64.0, 8, "r5", "x86_64"));

    private EmrReleaseCatalog() {}

    static String latestLabel() {
        return RELEASES.get(0).label();
    }

    static Optional<Release> find(String label) {
        return RELEASES.stream().filter(r -> r.label().equals(label)).findFirst();
    }

    static List<String> listLabels(String prefix, String application) {
        List<String> out = new ArrayList<>();
        for (Release release : RELEASES) {
            if (prefix != null && !release.label().startsWith(prefix)) {
                continue;
            }
            if (application != null
                    && release.applications().stream().noneMatch(app -> application.equals(app.name()))) {
                continue;
            }
            out.add(release.label());
        }
        return out;
    }

    static List<SupportedInstanceType> instanceTypes() {
        return INSTANCE_TYPES;
    }

    private static Release release(String label, List<Application> applications, List<String> osReleases) {
        return new Release(label, applications, osReleases);
    }

    private static SupportedInstanceType instance(
            String type, double memoryGb, int vcpu, String family, String architecture) {
        return new SupportedInstanceType(
                type, memoryGb, 0, vcpu, true, family, true, false, 0, true, architecture);
    }
}

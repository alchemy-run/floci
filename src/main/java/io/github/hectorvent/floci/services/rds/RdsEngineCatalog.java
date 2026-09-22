package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A pinned local catalog, not a claim about current regional AWS engine availability. */
final class RdsEngineCatalog {

    private RdsEngineCatalog() {}

    record Version(String engine, String version, String family, String description,
                   String status, boolean engineDefault, boolean majorDefault) {
        String majorVersion() {
            return family.substring(engine.length());
        }

        List<String> modes() {
            return List.of("provisioned");
        }
    }

    // Release identifiers: https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-versions.html
    // https://docs.aws.amazon.com/AmazonRDS/latest/AuroraMySQLReleaseNotes/AuroraMySQL.Updates.3082.html
    // Defaults for non-Aurora engines preserve Floci's existing create-time defaults.
    private static final List<Version> VERSIONS = List.of(
            new Version("postgres", "9.6.24", "postgres9.6", "PostgreSQL", "deprecated", false, true),
            version("postgres", "13.16", "postgres13", "PostgreSQL", false, true),
            version("postgres", "14.13", "postgres14", "PostgreSQL", false, true),
            version("postgres", "15.8", "postgres15", "PostgreSQL", false, true),
            version("postgres", "16.3", "postgres16", "PostgreSQL", true, true),
            version("postgres", "16.4", "postgres16", "PostgreSQL", false, false),
            version("postgres", "16.13", "postgres16", "PostgreSQL", false, false),
            version("postgres", "16.14", "postgres16", "PostgreSQL", false, false),
            version("postgres", "17.1", "postgres17", "PostgreSQL", false, true),
            version("postgres", "18.1", "postgres18", "PostgreSQL", false, true),
            version("postgres", "18.4", "postgres18", "PostgreSQL", false, false),
            version("mysql", "8.0.36", "mysql8.0", "MySQL Community Edition", true, true),
            version("mysql", "8.4.3", "mysql8.4", "MySQL Community Edition", false, true),
            version("mariadb", "11.2", "mariadb11.2", "MariaDB", true, true),
            version("aurora-postgresql", "13.16", "aurora-postgresql13", "Aurora PostgreSQL", false, true),
            version("aurora-postgresql", "14.13", "aurora-postgresql14", "Aurora PostgreSQL", false, true),
            version("aurora-postgresql", "15.8", "aurora-postgresql15", "Aurora PostgreSQL", false, true),
            version("aurora-postgresql", "16.3", "aurora-postgresql16", "Aurora PostgreSQL", true, true),
            version("aurora-postgresql", "16.4", "aurora-postgresql16", "Aurora PostgreSQL", false, false),
            version("aurora-postgresql", "17.4", "aurora-postgresql17", "Aurora PostgreSQL", false, true),
            version("aurora-mysql", "8.0.mysql_aurora.3.08.2", "aurora-mysql8.0", "Aurora MySQL", true, true),
            version("sqlserver-ee", "15.00", "sqlserver-ee15.0", "SQL Server Enterprise Edition", true, true),
            version("sqlserver-se", "15.00", "sqlserver-se15.0", "SQL Server Standard Edition", true, true),
            version("sqlserver-ex", "15.00", "sqlserver-ex15.0", "SQL Server Express Edition", true, true),
            version("sqlserver-web", "15.00", "sqlserver-web15.0", "SQL Server Web Edition", true, true));

    static List<Version> describe(String engine, String version, String family, boolean defaultOnly,
                                  boolean includeAll, Map<String, List<String>> filters) {
        if (defaultOnly && includeAll) {
            throw new AwsException("InvalidParameterCombination", "IncludeAll and DefaultOnly cannot both be true.", 400);
        }
        Set<String> supportedFilters = Set.of("engine", "engine-version", "db-parameter-group-family",
                "engine-mode", "status");
        filters.forEach((name, values) -> {
            if (!supportedFilters.contains(name) || values.isEmpty()) {
                throw new AwsException("InvalidParameterValue", "Invalid engine version filter: " + name, 400);
            }
        });
        List<Version> matching = VERSIONS.stream()
                .filter(entry -> absent(engine) || engine.equals(entry.engine()))
                .filter(entry -> absent(version) || version.equals(entry.version()) || version.equals(entry.majorVersion()))
                .filter(entry -> absent(family) || family.equals(entry.family()))
                .filter(entry -> includeAll || "available".equals(entry.status()))
                .filter(entry -> filters.entrySet().stream().allMatch(filter -> matches(entry, filter)))
                .sorted(Comparator.comparing(Version::engine).thenComparing(Version::version))
                .toList();
        if (!defaultOnly) {
            return matching;
        }
        Map<String, Version> defaults = new LinkedHashMap<>();
        for (Version entry : matching) {
            boolean majorRequested = !absent(version) || !absent(family)
                    || filters.containsKey("engine-version") || filters.containsKey("db-parameter-group-family");
            if (majorRequested ? entry.majorDefault() : entry.engineDefault()) {
                defaults.put(majorRequested ? entry.family() : entry.engine(), entry);
            }
        }
        return List.copyOf(defaults.values());
    }

    static String defaultVersion(String engine) {
        return VERSIONS.stream().filter(entry -> entry.engine().equals(engine) && entry.engineDefault())
                .map(Version::version).findFirst().orElse("1.0");
    }

    private static boolean matches(Version entry, Map.Entry<String, List<String>> filter) {
        return switch (filter.getKey()) {
            case "engine" -> filter.getValue().contains(entry.engine());
            case "engine-version" -> filter.getValue().contains(entry.version());
            case "db-parameter-group-family" -> filter.getValue().contains(entry.family());
            case "status" -> filter.getValue().contains(entry.status());
            case "engine-mode" -> entry.modes().stream().anyMatch(filter.getValue()::contains);
            default -> false;
        };
    }

    private static boolean absent(String value) {
        return value == null || value.isBlank();
    }

    private static Version version(String engine, String version, String family, String description,
                                    boolean engineDefault, boolean majorDefault) {
        return new Version(engine, version, family, description, "available", engineDefault, majorDefault);
    }
}

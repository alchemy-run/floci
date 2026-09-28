package io.github.hectorvent.floci.services.dms;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Static DMS catalogue data: the engines an endpoint may name, the replication instance classes
 * and engine versions Floci offers, and the extra-connection-attribute settings it documents per
 * engine.
 */
final class DmsCatalog {

    /** Engine names accepted by CreateEndpoint and ModifyEndpoint. */
    static final Set<String> ENGINES = Set.of(
            "mysql", "oracle", "postgres", "mariadb", "aurora", "aurora-postgresql", "opensearch",
            "redshift", "redshift-serverless", "s3", "db2", "db2-zos", "azuredb", "sybase", "dynamodb",
            "mongodb", "kinesis", "kafka", "elasticsearch", "docdb", "sqlserver", "neptune", "babelfish",
            "timestream", "azure-sql-managed-instance", "gcp-mysql", "redis");

    /** Engines DMS supports only as a migration target. */
    static final Set<String> TARGET_ONLY_ENGINES = Set.of(
            "dynamodb", "kinesis", "kafka", "elasticsearch", "opensearch", "neptune", "redshift",
            "redshift-serverless", "redis", "timestream", "babelfish");

    /** Engines DMS supports only as a migration source. */
    static final Set<String> SOURCE_ONLY_ENGINES = Set.of("db2", "db2-zos", "mongodb", "gcp-mysql");

    static final Set<String> SSL_MODES = Set.of("none", "require", "verify-ca", "verify-full");

    static final Set<String> NETWORK_TYPES = Set.of("IPV4", "IPV6", "DUAL");

    /** Engine versions offered for new instances, oldest first; the last is the default. */
    static final List<String> ENGINE_VERSIONS = List.of("3.5.3", "3.5.4");

    static final int MIN_ALLOCATED_STORAGE = 5;
    static final int MAX_ALLOCATED_STORAGE = 6144;
    static final String STORAGE_TYPE = "gp2";

    /** Instance class to the storage included in its price, which is also the default size. */
    static final Map<String, Integer> INSTANCE_CLASSES = Map.ofEntries(
            Map.entry("dms.t3.micro", 50),
            Map.entry("dms.t3.small", 50),
            Map.entry("dms.t3.medium", 50),
            Map.entry("dms.t3.large", 50),
            Map.entry("dms.c5.large", 100),
            Map.entry("dms.c5.xlarge", 100),
            Map.entry("dms.c5.2xlarge", 100),
            Map.entry("dms.c5.4xlarge", 100),
            Map.entry("dms.c5.9xlarge", 100),
            Map.entry("dms.c5.12xlarge", 100),
            Map.entry("dms.c5.18xlarge", 100),
            Map.entry("dms.c5.24xlarge", 100),
            Map.entry("dms.r5.large", 100),
            Map.entry("dms.r5.xlarge", 100),
            Map.entry("dms.r5.2xlarge", 100),
            Map.entry("dms.r5.4xlarge", 100),
            Map.entry("dms.r5.8xlarge", 100),
            Map.entry("dms.r5.12xlarge", 100),
            Map.entry("dms.r5.16xlarge", 100),
            Map.entry("dms.r5.24xlarge", 100));

    /**
     * One endpoint setting as DescribeEndpointSettings reports it. {@code applicability} is null
     * for a setting that applies to both sources and targets.
     */
    record EndpointSetting(String name, String type, List<String> enumValues, boolean sensitive,
                           String units, String applicability, Integer intValueMin, Integer intValueMax,
                           String defaultValue) {
    }

    private static final List<EndpointSetting> MYSQL_SETTINGS = List.of(
            setting("afterConnectScript", "string", null, null),
            setting("CleanSrcMetadataOnMismatch", "boolean", "source", "false"),
            integer("EventsPollInterval", "seconds", "source", null, null, "5"),
            setting("initstmt", "string", "target", null),
            integer("maxFileSize", "KB", "target", 1, 1_048_576, "32768"),
            integer("parallelLoadThreads", null, "target", 1, 5, "1"),
            setting("serverTimezone", "string", null, null),
            new EndpointSetting("targetDbType", "enum", List.of("specific-database", "multiple-databases"),
                    false, null, "target", null, null, "multiple-databases"));

    private static final List<EndpointSetting> POSTGRES_SETTINGS = List.of(
            setting("afterConnectScript", "string", null, null),
            setting("captureDDLs", "boolean", "source", "true"),
            setting("ddlArtifactsSchema", "string", "source", "public"),
            integer("executeTimeout", "seconds", null, null, null, "60"),
            setting("failTasksOnLobTruncation", "boolean", "source", "false"),
            setting("heartbeatEnable", "boolean", "source", "false"),
            integer("heartbeatFrequency", "minutes", "source", null, null, "5"),
            setting("heartbeatSchema", "string", "source", "public"),
            setting("mapBooleanAsBoolean", "boolean", null, "false"),
            integer("maxFileSize", "KB", "target", 1, 1_048_576, "32768"),
            new EndpointSetting("PluginName", "enum", List.of("pglogical", "test_decoding"),
                    false, null, "source", null, null, null),
            setting("slotName", "string", "source", null));

    private DmsCatalog() {
    }

    static boolean isEngine(String engineName) {
        return engineName != null && ENGINES.contains(engineName.toLowerCase(Locale.ROOT));
    }

    /**
     * The settings Floci documents for an engine, or null when it has no catalogue for that engine.
     * MySQL-compatible and PostgreSQL-compatible engines share their family's settings.
     */
    static List<EndpointSetting> endpointSettings(String engineName) {
        return switch (engineName.toLowerCase(Locale.ROOT)) {
            case "mysql", "mariadb", "aurora" -> MYSQL_SETTINGS;
            case "postgres", "aurora-postgresql" -> POSTGRES_SETTINGS;
            default -> null;
        };
    }

    private static EndpointSetting setting(String name, String type, String applicability, String defaultValue) {
        return new EndpointSetting(name, type, null, false, null, applicability, null, null, defaultValue);
    }

    private static EndpointSetting integer(String name, String units, String applicability,
                                           Integer min, Integer max, String defaultValue) {
        return new EndpointSetting(name, "integer", null, false, units, applicability, min, max, defaultValue);
    }
}

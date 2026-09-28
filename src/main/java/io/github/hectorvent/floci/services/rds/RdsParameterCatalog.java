package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Engine defaults are immutable; only user overrides belong in parameter-group storage. */
final class RdsParameterCatalog {

    private RdsParameterCatalog() {}

    record Parameter(String name, String value, String description, String source,
                     String applyType, String applyMethod, String dataType, String allowedValues) {
        Parameter withOverride(String override, String method) {
            return new Parameter(name, override, description, "user", applyType, method, dataType, allowedValues);
        }
    }

    // https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Limits.html#RDS_Limits.MaxConnections
    // https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Tuning.concepts.memory.html
    private static final List<Parameter> POSTGRES = List.of(
            parameter("max_connections", "LEAST({DBInstanceClassMemory/9531392},5000)",
                    "Maximum number of concurrent connections.", "static", "integer", "6-262143"),
            parameter("work_mem", "4096", "Memory per sort or hash operation, in kB.",
                    "dynamic", "integer", "64-2147483647"),
            parameter("shared_buffers", "{DBInstanceClassMemory/32768}",
                    "Number of shared memory buffers used by the server.", "static", "integer", "16-1073741823"),
            parameter("log_min_duration_statement", "-1", "Minimum statement duration to log, in milliseconds.",
                    "dynamic", "integer", "-1-2147483647"));

    // https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/MySQL.Concepts.LocalTimeZone.html
    private static final List<Parameter> MYSQL = List.of(
            parameter("max_connections", "{DBInstanceClassMemory/12582880}",
                    "Maximum number of simultaneous client connections.", "dynamic", "integer", "1-100000"),
            parameter("time_zone", "UTC", "Default time zone for connections.", "dynamic", "string", null));

    static Map<String, Parameter> defaults(String family) {
        Map<String, Parameter> defaults = new TreeMap<>();
        if (family != null && family.matches("postgres(?:13|14|15|16|17|18)")) {
            POSTGRES.forEach(parameter -> defaults.put(parameter.name(), parameter));
            // PG 15+ defaults to 10 minutes; RDS documents 10 seconds for PG 11-14.
            // https://www.postgresql.org/docs/16/runtime-config-logging.html#GUC-LOG-AUTOVACUUM-MIN-DURATION
            // https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.PostgreSQL.CommonDBATasks.Autovacuum.Logging.html
            String duration = List.of("postgres13", "postgres14").contains(family) ? "10000" : "600000";
            Parameter autovacuum = parameter("log_autovacuum_min_duration", duration,
                    "Minimum autovacuum duration to log, in milliseconds.", "dynamic", "integer", "-1-2147483647");
            defaults.put(autovacuum.name(), autovacuum);
        } else if ("mysql8.0".equals(family) || "mysql8.4".equals(family)) {
            MYSQL.forEach(parameter -> defaults.put(parameter.name(), parameter));
        }
        return defaults;
    }

    static String defaultApplyMethod(String family, String name) {
        Parameter parameter = defaults(family).get(name);
        return parameter == null ? "immediate" : parameter.applyMethod();
    }

    static List<Parameter> describe(DbParameterGroup group, String source) {
        if (source != null && !source.isBlank()
                && !List.of("user", "engine-default", "system").contains(source)) {
            throw new AwsException("InvalidParameterValue", "Source must be user, engine-default, or system.", 400);
        }
        Map<String, Parameter> parameters = defaults(group.getDbParameterGroupFamily());
        group.getParameters().forEach((name, value) -> {
            Parameter definition = parameters.getOrDefault(name,
                    parameter(name, null, null, "dynamic", "string", null));
            String method = group.getParameterApplyMethods().getOrDefault(name, definition.applyMethod());
            parameters.put(name, definition.withOverride(value, method));
        });
        return parameters.values().stream()
                .filter(parameter -> source == null || source.isBlank() || source.equals(parameter.source()))
                .toList();
    }

    private static Parameter parameter(String name, String value, String description, String applyType,
                                       String dataType, String allowedValues) {
        return new Parameter(name, value, description, "engine-default", applyType,
                "static".equals(applyType) ? "pending-reboot" : "immediate", dataType, allowedValues);
    }
}

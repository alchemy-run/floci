package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.services.dms.model.DmsEndpoint;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Performs the database work behind TestConnection and RefreshSchemas by logging in to the
 * endpoint's database over JDBC from the Floci process. Only MySQL-compatible and
 * PostgreSQL-compatible engines have a driver on Floci's classpath; every other engine reports a
 * failed attempt with a message saying so, never a success.
 *
 * <p>DMS connects from the replication instance's VPC; Floci connects from wherever it runs, so a
 * host that is only resolvable inside the caller's network fails here with the driver's error.
 */
@ApplicationScoped
public class DmsConnectivityProbe {

    static {
        // Connector/J otherwise starts its abandoned-connection cleanup thread on first use.
        System.setProperty("com.mysql.cj.disableAbandonedConnectionCleanup", "true");
    }

    /** The outcome of one probe; {@code schemas} is empty for a connection test. */
    public record Result(boolean success, String failureMessage, List<String> schemas) {

        public Result {
            schemas = schemas != null ? List.copyOf(schemas) : List.of();
        }

        static Result failed(String message) {
            return new Result(false, message, List.of());
        }
    }

    private enum Family { MYSQL, POSTGRES }

    public Result testConnection(DmsEndpoint endpoint) {
        return withConnection(endpoint, (connection, family) -> new Result(true, null, List.of()));
    }

    public Result listSchemas(DmsEndpoint endpoint) {
        return withConnection(endpoint, (connection, family) -> {
            DatabaseMetaData metadata = connection.getMetaData();
            List<String> schemas = new ArrayList<>();
            try (ResultSet rows = family == Family.MYSQL ? metadata.getCatalogs() : metadata.getSchemas()) {
                while (rows.next()) {
                    schemas.add(rows.getString(family == Family.MYSQL ? "TABLE_CAT" : "TABLE_SCHEM"));
                }
            }
            return new Result(true, null, schemas);
        });
    }

    @FunctionalInterface
    private interface ConnectionWork {
        Result apply(Connection connection, Family family) throws SQLException;
    }

    private Result withConnection(DmsEndpoint endpoint, ConnectionWork work) {
        String engine = endpoint.getEngineName() == null ? "" : endpoint.getEngineName().toLowerCase(Locale.ROOT);
        Family family = switch (engine) {
            case "mysql", "mariadb", "aurora" -> Family.MYSQL;
            case "postgres", "aurora-postgresql" -> Family.POSTGRES;
            default -> null;
        };
        if (family == null) {
            return Result.failed("Floci cannot open a connection to a " + engine + " endpoint; only"
                    + " MySQL-compatible and PostgreSQL-compatible endpoints can be tested.");
        }
        if (endpoint.getServerName() == null || endpoint.getServerName().isBlank()) {
            return Result.failed("The endpoint has no ServerName to connect to.");
        }
        if (endpoint.getUsername() == null || endpoint.getUsername().isBlank()) {
            return Result.failed("The endpoint has no Username to log in with.");
        }
        if (endpoint.getPassword() == null) {
            return Result.failed("The endpoint has no Password to log in with.");
        }
        if (family == Family.POSTGRES
                && (endpoint.getDatabaseName() == null || endpoint.getDatabaseName().isBlank())) {
            return Result.failed("A PostgreSQL endpoint needs a DatabaseName to connect to.");
        }
        int port = endpoint.getPort() != null ? endpoint.getPort() : (family == Family.MYSQL ? 3306 : 5432);
        Properties props = new Properties();
        props.setProperty("user", endpoint.getUsername());
        props.setProperty("password", endpoint.getPassword());
        // Connector/J takes milliseconds; the PostgreSQL driver takes seconds.
        if (family == Family.MYSQL) {
            props.setProperty("connectTimeout", "5000");
            props.setProperty("socketTimeout", "10000");
            props.setProperty("sslMode", mysqlSslMode(endpoint.getSslMode()));
            props.setProperty("allowPublicKeyRetrieval", "true");
        } else {
            props.setProperty("connectTimeout", "5");
            props.setProperty("socketTimeout", "10");
            props.setProperty("loginTimeout", "10");
            props.setProperty("sslmode", postgresSslMode(endpoint.getSslMode()));
        }
        String url = url(family, endpoint.getServerName(), port, endpoint.getDatabaseName());
        try (Connection connection = DriverManager.getConnection(url, props)) {
            return work.apply(connection, family);
        } catch (SQLException e) {
            return Result.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return Result.failed("Connection attempt failed: " + e.getMessage());
        }
    }

    private static String url(Family family, String host, int port, String database) {
        String authority = (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
        String path = database == null ? "" : URLEncoder.encode(database, StandardCharsets.UTF_8);
        return family == Family.MYSQL
                ? "jdbc:mysql://" + authority + "/" + path
                : "jdbc:postgresql://" + authority + "/" + path;
    }

    private static String mysqlSslMode(String sslMode) {
        return switch (sslMode == null ? "none" : sslMode) {
            case "require" -> "REQUIRED";
            case "verify-ca" -> "VERIFY_CA";
            case "verify-full" -> "VERIFY_IDENTITY";
            default -> "DISABLED";
        };
    }

    private static String postgresSslMode(String sslMode) {
        return switch (sslMode == null ? "none" : sslMode) {
            case "require" -> "require";
            case "verify-ca" -> "verify-ca";
            case "verify-full" -> "verify-full";
            default -> "disable";
        };
    }
}

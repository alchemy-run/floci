package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.DatabaseSecretCredentials;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Loads a delivered S3 object into a Floci Redshift cluster. The {@code COPY} runs over a JDBC
 * connection to the cluster's wire proxy in simple query mode, so the proxy's S3 COPY emulation
 * does the work exactly as it does for any other client.
 */
@ApplicationScoped
public class FirehoseRedshiftDelivery {

    private static final Logger LOG = Logger.getLogger(FirehoseRedshiftDelivery.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SOCKET_TIMEOUT_SECONDS = 150;

    /** Seam for tests; production opens a real JDBC connection. */
    interface SqlRunner {
        void execute(String jdbcUrl, String user, String password, String sql) throws SQLException;
    }

    private final RedshiftService redshiftService;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper mapper;
    private final SqlRunner runner;

    @Inject
    public FirehoseRedshiftDelivery(RedshiftService redshiftService, SecretsManagerService secretsManagerService,
                                    ObjectMapper mapper) {
        this(redshiftService, secretsManagerService, mapper, FirehoseRedshiftDelivery::runJdbc);
    }

    FirehoseRedshiftDelivery(RedshiftService redshiftService, SecretsManagerService secretsManagerService,
                             ObjectMapper mapper, SqlRunner runner) {
        this.redshiftService = redshiftService;
        this.secretsManagerService = secretsManagerService;
        this.mapper = mapper;
        this.runner = runner;
    }

    /**
     * Runs the destination's COPY for the object at {@code s3://bucket/key}.
     *
     * Throws {@code AwsException} when the cluster or the secret cannot be resolved.
     *
     * @throws IllegalStateException when the statement itself fails; the message carries the table and SQL state
     */
    public void copy(String accountId, String region, RedshiftDestination destination, String bucket, String key) {
        RedshiftJdbcUrl target = RedshiftJdbcUrl.parse(destination.getClusterJdbcUrl());
        Cluster cluster = redshiftService.describeClustersForAccount(accountId, target.clusterIdentifier()).get(0);
        String[] credentials = credentials(destination, cluster, region);
        RedshiftDestination.CopyCommand command = destination.getCopyCommand();
        String sql = RedshiftCopySql.build(command.getDataTableName(), command.getDataTableColumns(), bucket, key,
                command.getCopyOptions());
        // The database name in the URL is ignored: the emulated container database is always "dev".
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                + "/dev?preferQueryMode=simple";
        try {
            runner.execute(jdbcUrl, credentials[0], credentials[1], sql);
        } catch (SQLException e) {
            throw new IllegalStateException("COPY into " + command.getDataTableName() + " on cluster "
                    + target.clusterIdentifier() + " failed (SQLSTATE " + e.getSQLState() + "): " + e.getMessage(), e);
        }
        LOG.debugv("Firehose copied s3://{0}/{1} into {2} on cluster {3}", bucket, key,
                command.getDataTableName(), target.clusterIdentifier());
    }

    private String[] credentials(RedshiftDestination destination, Cluster cluster, String region) {
        RedshiftDestination.SecretsManagerConfiguration secrets = destination.getSecretsManagerConfiguration();
        if (secrets != null && Boolean.TRUE.equals(secrets.getEnabled())) {
            SecretVersion secret = secretsManagerService.getSecretValue(secrets.getSecretArn(), null, null, region);
            DatabaseSecretCredentials credentials = DatabaseSecretCredentials.parse(mapper, secret.getSecretString())
                    .orElseThrow(() -> new IllegalStateException("Secret " + secrets.getSecretArn()
                            + " does not contain username and password fields."));
            return new String[] {credentials.username(), credentials.password()};
        }
        if (destination.getPassword() == null) {
            throw new IllegalStateException("The Redshift destination for cluster " + cluster.getClusterIdentifier()
                    + " has no Password and no enabled SecretsManagerConfiguration, so Firehose cannot sign in.");
        }
        String user = destination.getUsername() != null ? destination.getUsername() : cluster.getMasterUsername();
        return new String[] {user, destination.getPassword()};
    }

    /**
     * Timeouts matter here: a connection that never answers would stall the Redshift delivery thread
     * and delay emulator shutdown.
     */
    static Properties connectionProperties(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
        return properties;
    }

    private static void runJdbc(String jdbcUrl, String user, String password, String sql) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, connectionProperties(user, password));
        executeAndClose(connection, sql);
    }

    /**
     * Runs the statement and closes the connection. Once the statement has returned the load is committed
     * (autocommit), so a failure while closing must not surface as a failed delivery.
     */
    static void executeAndClose(Connection connection, String sql) throws SQLException {
        try {
            // Closing the connection closes its statement, so the statement is not closed on its own: a failure there
            // would be reported as a failed COPY too.
            Statement statement = connection.createStatement();
            statement.execute(sql);
        } catch (SQLException e) {
            closeAfterFailure(connection, e);
            throw e;
        }
        closeAfterSuccess(connection);
    }

    private static void closeAfterFailure(Connection connection, SQLException failure) {
        try {
            connection.close();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private static void closeAfterSuccess(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warnv(e, "Closing the Redshift connection after a committed COPY failed: {0}", e.getMessage());
        }
    }
}

package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;
import software.amazon.awssdk.services.rds.model.ModifyDbInstanceRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RDS JDBC Proxy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsJdbcCompatTest {

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    private static final Region REGION = Region.US_EAST_1;
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "secret123";
    private static final String DATABASE = "app";
    /** RDS for PostgreSQL listens on 5432 unless a port is requested; each instance has its own host. */
    private static final int POSTGRES_PORT = 5432;

    private static RdsClient rds;
    private static String instanceId;
    private static String endpointAddress;
    private static Integer proxyPort;
    private static boolean instanceCreated;

    @AfterAll
    static void cleanup() {
        if (rds != null && instanceCreated && instanceId != null) {
            try {
                rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                        .dbInstanceIdentifier(instanceId)
                        .skipFinalSnapshot(true)
                        .build());
            } catch (Exception ignored) {
            }
            rds.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Create instance with IAM enabled and connect with password")
    void createDbInstanceAndConnectWithPassword() throws Exception {
        rds = TestFixtures.rdsClient();
        instanceId = TestFixtures.uniqueName("rds-pg");

        try {
            CreateDbInstanceResponse response = rds.createDBInstance(CreateDbInstanceRequest.builder()
                    .dbInstanceIdentifier(instanceId)
                    .dbInstanceClass("db.t3.micro")
                    .engine("postgres")
                    .masterUsername(USERNAME)
                    .masterUserPassword(PASSWORD)
                    .dbName(DATABASE)
                    .allocatedStorage(20)
                    .enableIAMDatabaseAuthentication(true)
                    .build());

            endpointAddress = response.dbInstance().endpoint().address();
            proxyPort = response.dbInstance().endpoint().port();
            instanceCreated = true;
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "RDS instance creation unavailable in this environment: " + e.getMessage());
            return;
        }

        assertThat(proxyPort).isEqualTo(POSTGRES_PORT);
        assertThat(endpointAddress).startsWith(instanceId.toLowerCase() + ".");

        Connection connection = awaitPostgresConnection(USERNAME, PASSWORD);
        try {
            assertThat(selectOne(connection)).isEqualTo(1);
        } finally {
            connection.close();
        }
    }

    @Test
    @Order(2)
    @DisplayName("Connect with IAM auth token")
    void connectWithIamAuthToken() throws Exception {
        assumeInstanceCreated();

        String token = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(endpointAddress)
                .port(proxyPort)
                .username(USERNAME)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build());

        Connection connection = awaitPostgresConnection(USERNAME, token);
        try {
            assertThat(selectOne(connection)).isEqualTo(1);
        } finally {
            connection.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Tampered IAM auth token is rejected")
    void rejectsTamperedIamAuthToken() {
        assumeInstanceCreated();

        String token = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(endpointAddress)
                .port(proxyPort)
                .username(USERNAME)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build());

        String tamperedToken = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> openPostgresConnection(USERNAME, tamperedToken))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("password authentication failed");
    }

    @Test
    @Order(4)
    @DisplayName("IAM auth rejected on instance created without IAM")
    void iamAuthRejectedWhenDisabledAtCreate() {
        assumeInstanceCreated();

        // Create a separate instance with IAM disabled
        String noIamId = TestFixtures.uniqueName("rds-noiam");
        try {
            CreateDbInstanceResponse response = rds.createDBInstance(CreateDbInstanceRequest.builder()
                    .dbInstanceIdentifier(noIamId)
                    .dbInstanceClass("db.t3.micro")
                    .engine("postgres")
                    .masterUsername(USERNAME)
                    .masterUserPassword(PASSWORD)
                    .dbName(DATABASE)
                    .allocatedStorage(20)
                    .enableIAMDatabaseAuthentication(false)
                    .build());

            String noIamAddress = response.dbInstance().endpoint().address();
            Integer noIamPort = response.dbInstance().endpoint().port();

            String token = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                    .hostname(noIamAddress)
                    .port(noIamPort)
                    .username(USERNAME)
                    .region(REGION)
                    .credentialsProvider(CREDENTIALS)
                    .build());

            // Non-IAM instance rejects IAM tokens. The rejection may happen at the
            // PostgreSQL auth layer ("password authentication failed") or at the TCP
            // level if the proxy doesn't forward non-IAM connections ("connection attempt failed").
            assertThatThrownBy(() -> openPostgresConnection(USERNAME, token, noIamAddress, noIamPort))
                    .isInstanceOf(SQLException.class);
        } finally {
            try {
                rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                        .dbInstanceIdentifier(noIamId)
                        .skipFinalSnapshot(true)
                        .build());
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("Toggle IAM via modify on a running instance")
    void toggleIamViaModifyOnRunningInstance() throws Exception {
        assumeInstanceCreated();

        String toggleId = TestFixtures.uniqueName("rds-toggle");
        try {
            CreateDbInstanceResponse response = rds.createDBInstance(CreateDbInstanceRequest.builder()
                    .dbInstanceIdentifier(toggleId)
                    .dbInstanceClass("db.t3.micro")
                    .engine("postgres")
                    .masterUsername(USERNAME)
                    .masterUserPassword(PASSWORD)
                    .dbName(DATABASE)
                    .allocatedStorage(20)
                    .enableIAMDatabaseAuthentication(false)
                    .build());

            String toggleAddress = response.dbInstance().endpoint().address();
            Integer togglePort = response.dbInstance().endpoint().port();

            // Should reject IAM when disabled
            String token1 = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                    .hostname(toggleAddress)
                    .port(togglePort)
                    .username(USERNAME)
                    .region(REGION)
                    .credentialsProvider(CREDENTIALS)
                    .build());

            assertThatThrownBy(() -> openPostgresConnection(USERNAME, token1, toggleAddress, togglePort))
                    .isInstanceOf(SQLException.class);

            // Enable IAM via modify
            rds.modifyDBInstance(ModifyDbInstanceRequest.builder()
                    .dbInstanceIdentifier(toggleId)
                    .enableIAMDatabaseAuthentication(true)
                    .applyImmediately(true)
                    .build());

            // Should accept IAM after enable
            String token2 = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                    .hostname(toggleAddress)
                    .port(togglePort)
                    .username(USERNAME)
                    .region(REGION)
                    .credentialsProvider(CREDENTIALS)
                    .build());

            Connection connection = awaitPostgresConnection(USERNAME, token2, toggleAddress, togglePort);
            try {
                assertThat(selectOne(connection)).isEqualTo(1);
            } finally {
                connection.close();
            }

            rds.modifyDBInstance(ModifyDbInstanceRequest.builder()
                    .dbInstanceIdentifier(toggleId)
                    .enableIAMDatabaseAuthentication(false)
                    .applyImmediately(true)
                    .build());

            String token3 = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                    .hostname(toggleAddress)
                    .port(togglePort)
                    .username(USERNAME)
                    .region(REGION)
                    .credentialsProvider(CREDENTIALS)
                    .build());

            assertThatThrownBy(() -> openPostgresConnection(USERNAME, token3, toggleAddress, togglePort))
                    .isInstanceOf(SQLException.class);
        } finally {
            try {
                rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                        .dbInstanceIdentifier(toggleId)
                        .skipFinalSnapshot(true)
                        .build());
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("Modify password keeps proxy reachable and delete releases port")
    void modifyKeepsProxyReachableAndDeleteReleasesPort() throws Exception {
        assumeInstanceCreated();

        rds.modifyDBInstance(ModifyDbInstanceRequest.builder()
                .dbInstanceIdentifier(instanceId)
                .masterUserPassword("secret456")
                .build());

        // Old password must be rejected after modify
        assertThatThrownBy(() -> openPostgresConnection(USERNAME, PASSWORD))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("password authentication failed");

        Connection modifiedPasswordConnection = awaitPostgresConnection(USERNAME, "secret456");
        try {
            assertThat(selectOne(modifiedPasswordConnection)).isEqualTo(1);
        } finally {
            modifiedPasswordConnection.close();
        }

        // IAM should still work after password change
        String token = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(endpointAddress)
                .port(proxyPort)
                .username(USERNAME)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build());

        Connection iamConnection = awaitPostgresConnection(USERNAME, token);
        try {
            assertThat(selectOne(iamConnection)).isEqualTo(1);
        } finally {
            iamConnection.close();
        }

        rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                .dbInstanceIdentifier(instanceId)
                .skipFinalSnapshot(true)
                .build());
        instanceCreated = false;

        // Real AWS faults DBInstanceNotFound when describing a deleted instance by
        // identifier — this is what lets the DBInstanceDeleted waiter complete.
        String deletedId = instanceId;
        assertThatThrownBy(() -> rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(deletedId)
                .build()))
                .isInstanceOf(DbInstanceNotFoundException.class);

        String replacementId = TestFixtures.uniqueName("rds-pg");
        CreateDbInstanceResponse replacement = rds.createDBInstance(CreateDbInstanceRequest.builder()
                .dbInstanceIdentifier(replacementId)
                .dbInstanceClass("db.t3.micro")
                .engine("postgres")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .dbName(DATABASE)
                .allocatedStorage(20)
                .enableIAMDatabaseAuthentication(true)
                .build());

        instanceId = replacementId;
        instanceCreated = true;
        endpointAddress = replacement.dbInstance().endpoint().address();
        proxyPort = replacement.dbInstance().endpoint().port();

        // The replacement listens on the same default port under its own host name.
        assertThat(proxyPort).isEqualTo(POSTGRES_PORT);
        assertThat(endpointAddress).startsWith(replacementId.toLowerCase() + ".");

        Connection replacementConnection = awaitPostgresConnection(USERNAME, PASSWORD);
        try {
            assertThat(selectOne(replacementConnection)).isEqualTo(1);
        } finally {
            replacementConnection.close();
        }
    }

    private static void assumeInstanceCreated() {
        Assumptions.assumeTrue(instanceCreated && endpointAddress != null && proxyPort != null,
                "RDS JDBC tests require a created DB instance from the first step");
    }

    private static Connection awaitPostgresConnection(String username, String password) throws Exception {
        return awaitPostgresConnection(username, password, endpointAddress, proxyPort);
    }

    private static Connection awaitPostgresConnection(String username, String password, String host, int port)
            throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                return openPostgresConnection(username, password, host, port);
            } catch (SQLException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last != null ? last : new SQLException("Timed out waiting for RDS proxy connection");
    }

    private static Connection openPostgresConnection(String username, String password) throws SQLException {
        return openPostgresConnection(username, password, endpointAddress, proxyPort);
    }

    /**
     * Connects to an instance's endpoint over TLS, as RDS clients do: several instances share the
     * PostgreSQL port, and the TLS server name is what selects this one.
     */
    private static Connection openPostgresConnection(String username, String password, String host, int port)
            throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "require");
        properties.setProperty("connectTimeout", "5");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + DATABASE,
                properties);
    }

    private static int selectOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select 1")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }
}

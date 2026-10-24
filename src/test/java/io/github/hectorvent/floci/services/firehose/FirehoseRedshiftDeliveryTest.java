package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseRedshiftDeliveryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private RedshiftService redshift;
    private SecretsManagerService secrets;
    private final List<String[]> executed = new ArrayList<>();
    private FirehoseRedshiftDelivery delivery;

    @BeforeEach
    void setUp() {
        redshift = mock(RedshiftService.class);
        secrets = mock(SecretsManagerService.class);
        Cluster cluster = mock(Cluster.class);
        when(cluster.getEndpoint()).thenReturn(new Endpoint("host.docker.internal", 41234));
        when(cluster.getMasterUsername()).thenReturn("admin");
        when(redshift.describeClustersForAccount("000000000000", "c1")).thenReturn(List.of(cluster));
        delivery = new FirehoseRedshiftDelivery(redshift, secrets, mapper,
                (url, user, password, sql) -> executed.add(new String[] {url, user, password, sql}));
    }

    private RedshiftDestination destination() throws Exception {
        return mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events", "CopyOptions": "json 'auto'"}}
                """, RedshiftDestination.class);
    }

    @Test
    void runsCopyWithoutAnIamRoleThroughTheProxyPortInSimpleMode() throws Exception {
        delivery.copy("000000000000", "us-east-1", destination(), "stage", "k1");

        assertEquals(1, executed.size());
        assertEquals("jdbc:postgresql://127.0.0.1:41234/dev?preferQueryMode=simple", executed.get(0)[0]);
        assertEquals("admin", executed.get(0)[1]);
        assertEquals("Secret123", executed.get(0)[2]);
        assertEquals("COPY events FROM 's3://stage/k1' json 'auto'", executed.get(0)[3]);
    }

    @Test
    void secretOverridesInlineCredentialsWhenEnabled() throws Exception {
        RedshiftDestination dest = destination();
        RedshiftDestination.SecretsManagerConfiguration cfg = new RedshiftDestination.SecretsManagerConfiguration();
        cfg.setEnabled(true);
        cfg.setSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:rs-creds-AbCdEf");
        dest.setSecretsManagerConfiguration(cfg);
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("{\"username\":\"fromsecret\",\"password\":\"pw-secret\"}");
        when(secrets.getSecretValue(eq(cfg.getSecretArn()), any(), any(), eq("us-east-1"))).thenReturn(version);

        delivery.copy("000000000000", "us-east-1", dest, "stage", "k1");

        assertEquals("fromsecret", executed.get(0)[1]);
        assertEquals("pw-secret", executed.get(0)[2]);
    }

    @Test
    void disabledSecretsManagerKeepsTheInlineCredentials() throws Exception {
        RedshiftDestination dest = destination();
        RedshiftDestination.SecretsManagerConfiguration cfg = new RedshiftDestination.SecretsManagerConfiguration();
        cfg.setEnabled(false);
        dest.setSecretsManagerConfiguration(cfg);

        delivery.copy("000000000000", "us-east-1", dest, "stage", "k1");

        assertEquals("Secret123", executed.get(0)[2]);
    }

    @Test
    void unknownClusterSurfacesAsAwsException() {
        when(redshift.describeClustersForAccount("000000000000", "c1"))
                .thenThrow(new AwsException("ClusterNotFound", "Cluster c1 not found", 404));

        assertThrows(AwsException.class,
                () -> delivery.copy("000000000000", "us-east-1", destination(), "stage", "k"));
    }

    @Test
    void jdbcFailureIsWrappedWithTableClusterAndSqlState() {
        FirehoseRedshiftDelivery failing = new FirehoseRedshiftDelivery(redshift, secrets, mapper,
                (url, user, password, sql) -> {
                    throw new SQLException("relation \"events\" does not exist", "42P01");
                });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> failing.copy("000000000000", "us-east-1", destination(), "stage", "k"));

        assertTrue(e.getMessage().contains("events"), e.getMessage());
        assertTrue(e.getMessage().contains("c1"), e.getMessage());
        assertTrue(e.getMessage().contains("42P01"), e.getMessage());
    }

    @Test
    void jdbcConnectionsCarryConnectAndSocketTimeouts() {
        Properties properties = FirehoseRedshiftDelivery.connectionProperties("admin", "Secret123");

        assertEquals("admin", properties.getProperty("user"));
        assertEquals("Secret123", properties.getProperty("password"));
        assertTrue(Integer.parseInt(properties.getProperty("connectTimeout")) > 0);
        assertTrue(Integer.parseInt(properties.getProperty("socketTimeout")) > 0);
    }

    @Test
    void closeFailureAfterACommittedCopyIsNotAFailedDelivery() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        doThrow(new SQLException("connection reset")).when(connection).close();

        FirehoseRedshiftDelivery.executeAndClose(connection, "COPY t FROM 's3://b/k'");

        verify(statement).execute("COPY t FROM 's3://b/k'");
    }

    @Test
    void failedCopyIsReportedAndTheConnectionStillClosed() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(any())).thenThrow(new SQLException("relation does not exist", "42P01"));

        SQLException e = assertThrows(SQLException.class,
                () -> FirehoseRedshiftDelivery.executeAndClose(connection, "COPY t FROM 's3://b/k'"));

        assertEquals("42P01", e.getSQLState());
        verify(connection).close();
    }

    @Test
    void destinationWithoutAnyPasswordFailsWithAClearMessage() throws Exception {
        RedshiftDestination dest = destination();
        dest.setPassword(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.copy("000000000000", "us-east-1", dest, "stage", "k"));

        assertTrue(e.getMessage().contains("Password"), e.getMessage());
    }
}

package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.services.dms.model.DmsEndpoint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The probe never reports success for a login it did not complete. */
class DmsConnectivityProbeTest {

    private final DmsConnectivityProbe probe = new DmsConnectivityProbe();

    @Test
    void aMysqlEndpointOnAClosedPortFails() throws IOException {
        DmsConnectivityProbe.Result result = probe.testConnection(endpoint("mysql", closedPort()));

        assertFalse(result.success());
        assertNotNull(result.failureMessage());
        assertFalse(result.failureMessage().isBlank());
    }

    @Test
    void aPostgresEndpointOnAClosedPortFailsToListSchemas() throws IOException {
        DmsConnectivityProbe.Result result = probe.listSchemas(endpoint("postgres", closedPort()));

        assertFalse(result.success());
        assertTrue(result.schemas().isEmpty());
        assertFalse(result.failureMessage().isBlank());
    }

    @Test
    void anEngineWithoutADriverFailsWithAnExplanation() {
        DmsConnectivityProbe.Result result = probe.testConnection(endpoint("oracle", 1521));

        assertFalse(result.success());
        assertTrue(result.failureMessage().contains("oracle"));
    }

    @Test
    void anEndpointWithoutAServerFails() {
        DmsEndpoint endpoint = endpoint("mysql", 3306);
        endpoint.setServerName(null);

        assertFalse(probe.testConnection(endpoint).success());
    }

    @Test
    void aPostgresEndpointWithoutADatabaseFails() {
        DmsEndpoint endpoint = endpoint("postgres", 5432);
        endpoint.setDatabaseName(null);

        DmsConnectivityProbe.Result result = probe.testConnection(endpoint);

        assertFalse(result.success());
        assertTrue(result.failureMessage().contains("DatabaseName"));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static DmsEndpoint endpoint(String engine, int port) {
        DmsEndpoint endpoint = new DmsEndpoint();
        endpoint.setEndpointIdentifier("probe");
        endpoint.setEngineName(engine);
        endpoint.setServerName("127.0.0.1");
        endpoint.setPort(port);
        endpoint.setUsername("admin");
        endpoint.setPassword("secret");
        endpoint.setDatabaseName("app");
        endpoint.setSslMode("none");
        return endpoint;
    }
}

package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.services.dsql.proxy.DsqlAuthProxy;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlSigV4Validator;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A missing/dead DSQL postgres container must fail Lambda {@code sslmode=require}
 * connects quickly instead of hanging the Alchemy {@code /health} probe.
 */
class DsqlAuthProxyTest {

    @Test
    void deadBackendConnectTimesOutInsteadOfHanging() throws Exception {
        DsqlAuthProxy proxy = new DsqlAuthProxy(
                "192.0.2.1", 5432, "admin", "unused", "postgres", mock(DsqlSigV4Validator.class));
        proxy.start(0);
        try (Socket client = new Socket("127.0.0.1", proxy.localPort())) {
            client.setSoTimeout(8_000);
            long started = System.nanoTime();
            int eof = client.getInputStream().read();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(-1, eof);
            assertTrue(elapsedMs >= 4_000, "expected ~5s backend connect timeout, was " + elapsedMs);
            assertTrue(elapsedMs < 8_000, "backend connect hung (" + elapsedMs + " ms)");
        } finally {
            proxy.stop();
        }
    }

    @Test
    void dummyBackendRefusesQuicklySoLambdaHealthDoesNotHang() throws Exception {
        DsqlAuthProxy proxy = new DsqlAuthProxy(
                "127.0.0.1", 1, "admin", "unused", "postgres", mock(DsqlSigV4Validator.class));
        proxy.start(0);
        try (Socket client = new Socket("127.0.0.1", proxy.localPort())) {
            client.setSoTimeout(2_000);
            long started = System.nanoTime();
            int eof = client.getInputStream().read();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(-1, eof);
            assertTrue(elapsedMs < 2_000, "dummy backend must fail fast, was " + elapsedMs + " ms");
        } finally {
            proxy.stop();
        }
    }
}

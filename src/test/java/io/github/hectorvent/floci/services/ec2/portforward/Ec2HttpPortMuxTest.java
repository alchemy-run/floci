package io.github.hectorvent.floci.services.ec2.portforward;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2HttpPortMuxTest {

    @Test
    void nginxConfigOmitsBackendsThatShareTheMuxIp() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("i-dead.localhost.floci.io", "172.17.0.20");
        routes.put("i-live.localhost.floci.io", "172.17.0.22");

        String config = Ec2HttpPortMux.nginxConfig(3000, routes, "172.17.0.20");

        assertFalse(config.contains("proxy_pass http://172.17.0.20:3000"),
                "proxying the mux's own bridge IP is a self-loop after Docker recycles the address");
        assertTrue(config.contains("server_name i-live.localhost.floci.io i-live.localhost.floci.io:3000"));
        assertTrue(config.contains("proxy_pass http://172.17.0.22:3000"));
        assertFalse(config.contains("i-dead.localhost.floci.io"));
    }

    @Test
    void nginxConfigDoesNotProxyUnknownHostsToTheFirstBackend() {
        Map<String, String> routes = Map.of(
                "i-one.localhost.floci.io", "172.17.0.8",
                "i-two.localhost.floci.io", "172.17.0.9");

        String config = Ec2HttpPortMux.nginxConfig(3000, routes);

        assertTrue(config.contains("listen 3000 default_server"));
        assertTrue(config.contains("return 502"),
                "unknown Host must 502 instead of stealing the first instance");
        int defaultIdx = config.indexOf("default_server");
        String defaultBlock = config.substring(defaultIdx);
        assertFalse(defaultBlock.contains("proxy_pass"),
                "default_server must not proxy_pass — that becomes a loop when the first IP is reused by the mux");
    }

    @Test
    void routesWithoutSelfDropsTheMuxAddress() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("i-a.localhost.floci.io", "172.17.0.20");
        routes.put("i-b.localhost.floci.io", "172.17.0.22");

        assertEquals(Map.of("i-b.localhost.floci.io", "172.17.0.22"),
                Ec2HttpPortMux.routesWithoutSelf(routes, "172.17.0.20"));
        assertEquals(routes, Ec2HttpPortMux.routesWithoutSelf(routes, "172.17.0.99"));
    }

    @Test
    void muxContainerNameIsDeterministic() {
        assertEquals("floci-ec2-mux-3000", Ec2HttpPortMux.muxContainerName(3000));
    }
}

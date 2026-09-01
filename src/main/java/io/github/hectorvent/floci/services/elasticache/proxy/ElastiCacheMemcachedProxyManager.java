package io.github.hectorvent.floci.services.elasticache.proxy;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one published TCP relay per provisioned Memcached cluster. */
@ApplicationScoped
public class ElastiCacheMemcachedProxyManager {

    private final ConcurrentHashMap<String, ElastiCacheTcpProxy> proxies = new ConcurrentHashMap<>();

    public void startProxy(String clusterId, int proxyPort, String backendHost, int backendPort) {
        ElastiCacheTcpProxy proxy = new ElastiCacheTcpProxy(backendHost, backendPort);
        try {
            proxy.start(proxyPort);
            proxies.put(clusterId, proxy);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Memcached proxy for cluster " + clusterId, e);
        }
    }

    public void stopProxy(String clusterId) {
        ElastiCacheTcpProxy proxy = proxies.remove(clusterId);
        if (proxy != null) proxy.stop();
    }
}

package io.floci.aws.services.redshiftserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.model.Endpoint;
import io.floci.aws.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Optional;

/**
 * Allocates the proxy port a workgroup listens on from the pool Redshift proxies share, so that a
 * workgroup and a provisioned cluster can never be given the same port, and the endpoint it
 * advertises.
 * <p>
 * Under {@code endpoint-domain} the endpoint is AWS's shape,
 * {@code <workgroup>.<account>.<region>.<domain>} on the workgroup's own port, and the workgroup's
 * runtime routes that name and port to its proxy by TLS server name. Otherwise the endpoint is the
 * proxy's own host and port.
 */
@ApplicationScoped
public class RedshiftServerlessEndpoints {

    private final RedshiftService redshiftService;
    private final EmulatorConfig config;

    @Inject
    public RedshiftServerlessEndpoints(RedshiftService redshiftService, EmulatorConfig config) {
        this.redshiftService = redshiftService;
        this.config = config;
    }

    /** Reserves a proxy port for {@code workgroup} and sets its endpoint. */
    public void allocate(Workgroup workgroup, String accountId, String region) {
        workgroup.setProxyPort(redshiftService.reserveProxyPort());
        workgroup.setEndpoint(advertised(workgroup, accountId, region));
    }

    /**
     * Re-reserves a persisted workgroup's proxy port after a restart and rebuilds its endpoint from
     * the current configuration, so a changed {@code endpoint-host} or {@code endpoint-domain} is
     * picked up. Allocates a fresh port if the persisted one is taken.
     */
    public void restore(Workgroup workgroup, String accountId, String region) {
        int persisted = workgroup.proxyListenPort();
        if (persisted > 0 && redshiftService.reserveProxyPort(persisted)) {
            workgroup.setProxyPort(persisted);
            workgroup.setEndpoint(advertised(workgroup, accountId, region));
            return;
        }
        allocate(workgroup, accountId, region);
    }

    public void release(Workgroup workgroup) {
        int port = workgroup.proxyListenPort();
        if (port > 0) {
            redshiftService.releaseProxyPort(port);
        }
    }

    /** The endpoint {@code workgroup} advertises for its current port. */
    public Endpoint advertised(Workgroup workgroup, String accountId, String region) {
        Optional<String> domain = domain();
        if (domain.isEmpty()) {
            return redshiftService.advertisedEndpoint(workgroup.getProxyPort());
        }
        return new Endpoint(workgroup.getWorkgroupName().toLowerCase(Locale.ROOT) + "." + accountId + "." + region
                + "." + domain.get(), workgroup.getPort());
    }

    /** Whether endpoints are routed by name ({@code endpoint-domain} is set). */
    public boolean routed() {
        return domain().isPresent();
    }

    private Optional<String> domain() {
        return config.services().redshiftServerless().endpointDomain().filter(d -> !d.isBlank());
    }
}

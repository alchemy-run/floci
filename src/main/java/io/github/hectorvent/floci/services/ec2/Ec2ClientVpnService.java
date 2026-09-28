package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnAuthentication;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnAuthorizationRule;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnRoute;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnTargetNetwork;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AWS Client VPN control plane: endpoints, target network associations, ingress authorization
 * rules and routes.
 *
 * <p>Transitional states are reported in the mutation responses exactly as AWS returns them
 * ({@code associating}, {@code authorizing}, {@code creating}, {@code deleting}, ...), while the
 * stored state settles immediately: describes return {@code associated} / {@code active}, and a
 * deleted endpoint, association, rule or route is gone from the next describe. No VPN data plane
 * runs; clients cannot connect.
 */
@ApplicationScoped
public class Ec2ClientVpnService {

    static final String ENDPOINT_PREFIX = "cvpn-endpoint-";
    private static final Set<String> AUTHENTICATION_TYPES = Set.of(
            "certificate-authentication", "directory-service-authentication", "federated-authentication");
    private static final Set<String> IP_ADDRESS_TYPES = Set.of("ipv4", "ipv6", "dual-stack");
    private static final Set<Integer> SESSION_TIMEOUT_HOURS = Set.of(8, 10, 12, 24);
    private static final Set<String> ENDPOINT_FILTERS = Set.of("endpoint-id", "transport-protocol", "tag-key", "tag-value");
    private static final Set<String> TARGET_NETWORK_FILTERS = Set.of("association-id", "target-network-id", "vpc-id");
    private static final Set<String> AUTHORIZATION_RULE_FILTERS = Set.of("description", "destination-cidr", "group-id");
    private static final Set<String> ROUTE_FILTERS = Set.of("destination-cidr", "origin", "target-subnet");
    private static final Pattern CERTIFICATE_ARN =
            Pattern.compile("arn:aws[a-z-]*:acm:([a-z0-9-]+):\\d{12}:certificate/[A-Za-z0-9-]+");
    private static final DateTimeFormatter LOG_STREAM_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")
            .withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Ec2Service ec2;
    // region::clientVpnEndpointId -> endpoint and its sub-resources
    private final StorageBackend<String, ClientVpnEndpoint> endpoints;
    private final Supplier<AcmService> acm;
    private final Object lock = new Object();

    /** Every setting of CreateClientVpnEndpoint and ModifyClientVpnEndpoint; null means omitted. */
    public static final class EndpointSettings {
        public String clientCidrBlock;
        public String serverCertificateArn;
        public List<ClientVpnAuthentication> authenticationOptions = new ArrayList<>();
        public Boolean connectionLogEnabled;
        public String connectionLogGroup;
        public String connectionLogStream;
        /** ModifyClientVpnEndpoint's DnsServers.Enabled; CreateClientVpnEndpoint leaves it null. */
        public Boolean dnsServersEnabled;
        public List<String> dnsServers;
        public String transportProtocol;
        public Integer vpnPort;
        public String description;
        public Boolean splitTunnel;
        public List<String> securityGroupIds = new ArrayList<>();
        public String vpcId;
        public String selfServicePortal;
        public Boolean clientConnectEnabled;
        public String clientConnectLambdaFunctionArn;
        public Integer sessionTimeoutHours;
        public Boolean clientLoginBannerEnabled;
        public String clientLoginBannerText;
        public Boolean clientRouteEnforced;
        public Boolean disconnectOnSessionTimeout;
        public String endpointIpAddressType;
        public String trafficIpAddressType;
        public boolean transitGatewayConfiguration;
        public String clientToken;
    }

    @Inject
    public Ec2ClientVpnService(Ec2Service ec2, StorageFactory storageFactory, Instance<AcmService> acm) {
        this(ec2, storageFactory.create("ec2", "ec2-client-vpn-endpoints.json",
                        new TypeReference<Map<String, ClientVpnEndpoint>>() {}),
                () -> acm != null && acm.isResolvable() ? acm.get() : null);
    }

    // Package-private for hermetic tests. A null ACM supplier result validates certificate ARNs by format only.
    Ec2ClientVpnService(Ec2Service ec2, StorageBackend<String, ClientVpnEndpoint> endpoints,
                        Supplier<AcmService> acm) {
        this.ec2 = ec2;
        this.endpoints = endpoints;
        this.acm = acm;
    }

    // ─── Endpoints ──────────────────────────────────────────────────────────

    public ClientVpnEndpoint createEndpoint(String region, EndpointSettings request) {
        synchronized (lock) {
            if (request.clientToken != null && !request.clientToken.isBlank()) {
                Optional<ClientVpnEndpoint> replay = regionEndpoints(region).stream()
                        .filter(endpoint -> request.clientToken.equals(endpoint.getClientToken()))
                        .findFirst();
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            rejectTransitGatewayConfiguration(request);
            requireParameter(request.serverCertificateArn, "ServerCertificateArn");
            if (request.authenticationOptions.isEmpty()) {
                throw new AwsException("MissingParameter",
                        "The request must contain the parameter AuthenticationOptions", 400);
            }
            validateAuthentication(request.authenticationOptions);
            String trafficType = orDefault(request.trafficIpAddressType, "ipv4");
            String endpointType = orDefault(request.endpointIpAddressType, "ipv4");
            requireOneOf(trafficType, IP_ADDRESS_TYPES, "TrafficIpAddressType");
            requireOneOf(endpointType, IP_ADDRESS_TYPES, "EndpointIpAddressType");
            if (!"ipv6".equals(trafficType)) {
                requireParameter(request.clientCidrBlock, "ClientCidrBlock");
            }
            if (request.clientCidrBlock != null) {
                validateClientCidr(request.clientCidrBlock);
            }
            String transport = orDefault(request.transportProtocol, "udp");
            requireOneOf(transport, Set.of("udp", "tcp"), "TransportProtocol");
            int vpnPort = validVpnPort(request.vpnPort == null ? 443 : request.vpnPort);
            List<String> dnsServers = validDnsServers(request.dnsServers == null ? List.of() : request.dnsServers);
            int sessionTimeout = validSessionTimeout(request.sessionTimeoutHours == null ? 24 : request.sessionTimeoutHours);
            String portal = orDefault(request.selfServicePortal, "disabled");
            requireOneOf(portal, Set.of("enabled", "disabled"), "SelfServicePortal");
            validateDescription(request.description);

            List<String> securityGroupIds = List.of();
            if (!request.securityGroupIds.isEmpty() && request.vpcId == null) {
                throw new AwsException("InvalidParameterValue",
                        "VpcId must be specified when SecurityGroupIds are specified", 400);
            }
            if (request.vpcId != null) {
                requireVpc(region, request.vpcId);
                securityGroupIds = resolveSecurityGroups(region, request.vpcId, request.securityGroupIds);
            }

            String id = ENDPOINT_PREFIX + randomHex(17);
            ClientVpnEndpoint endpoint = new ClientVpnEndpoint();
            endpoint.setClientVpnEndpointId(id);
            endpoint.setRegion(region);
            endpoint.setOwnerId(ec2.callerAccountId());
            endpoint.setStatus("pending-associate");
            endpoint.setCreationTime(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            endpoint.setDnsName("*." + id + ".prod.clientvpn." + region + ".amazonaws.com");
            endpoint.setClientCidrBlock(request.clientCidrBlock);
            endpoint.setDnsServers(new ArrayList<>(dnsServers));
            endpoint.setSplitTunnel(Boolean.TRUE.equals(request.splitTunnel));
            endpoint.setTransportProtocol(transport);
            endpoint.setVpnPort(vpnPort);
            endpoint.setAuthenticationOptions(new ArrayList<>(request.authenticationOptions));
            endpoint.setSecurityGroupIds(new ArrayList<>(securityGroupIds));
            endpoint.setVpcId(request.vpcId);
            endpoint.setSelfServicePortal(portal);
            endpoint.setSessionTimeoutHours(sessionTimeout);
            endpoint.setDisconnectOnSessionTimeout(Boolean.TRUE.equals(request.disconnectOnSessionTimeout));
            endpoint.setClientRouteEnforced(Boolean.TRUE.equals(request.clientRouteEnforced));
            endpoint.setEndpointIpAddressType(endpointType);
            endpoint.setTrafficIpAddressType(trafficType);
            endpoint.setDescription(request.description);
            endpoint.setClientToken(request.clientToken);
            applyConnectionLog(endpoint, region, request.connectionLogEnabled, request.connectionLogGroup,
                    request.connectionLogStream);
            applyClientConnect(endpoint, request.clientConnectEnabled, request.clientConnectLambdaFunctionArn);
            applyLoginBanner(endpoint, request.clientLoginBannerEnabled, request.clientLoginBannerText);

            String arn = endpointArn(endpoint);
            List<String> certificates = certificateArns(request.serverCertificateArn, request.authenticationOptions);
            for (String certificate : certificates) {
                requireCertificate(region, certificate);
            }
            endpoint.setServerCertificateArn(request.serverCertificateArn);
            for (String certificate : certificates) {
                markCertificateInUse(region, certificate, arn);
            }
            endpoints.put(key(region, id), endpoint);
            return endpoint;
        }
    }

    public List<ClientVpnEndpoint> describeEndpoints(String region, List<String> endpointIds,
                                                     Map<String, List<String>> filters) {
        requireSupportedFilters(filters, ENDPOINT_FILTERS, true);
        for (String endpointId : endpointIds) {
            requireEndpoint(region, endpointId);
        }
        return regionEndpoints(region).stream()
                .filter(endpoint -> endpointIds.isEmpty() || endpointIds.contains(endpoint.getClientVpnEndpointId()))
                .filter(endpoint -> filters.entrySet().stream().allMatch(filter -> {
                    List<String> values = filter.getValue();
                    List<Tag> tags = tags(endpoint);
                    return switch (filter.getKey()) {
                        case "endpoint-id" -> matchesAny(values, endpoint.getClientVpnEndpointId());
                        case "transport-protocol" -> matchesAny(values, endpoint.getTransportProtocol());
                        case "tag-key" -> tags.stream().anyMatch(tag -> matchesAny(values, tag.getKey()));
                        case "tag-value" -> tags.stream().anyMatch(tag -> matchesAny(values, tag.getValue()));
                        default -> {
                            String tagKey = filter.getKey().substring("tag:".length());
                            yield tags.stream().anyMatch(tag -> tagKey.equals(tag.getKey())
                                    && matchesAny(values, tag.getValue()));
                        }
                    };
                }))
                .collect(Collectors.toList());
    }

    public void modifyEndpoint(String region, String endpointId, EndpointSettings changes) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            rejectTransitGatewayConfiguration(changes);
            String arn = endpointArn(endpoint);

            if (changes.serverCertificateArn != null) {
                requireCertificate(region, changes.serverCertificateArn);
            }
            if (changes.vpnPort != null) {
                validVpnPort(changes.vpnPort);
            }
            if (changes.sessionTimeoutHours != null) {
                validSessionTimeout(changes.sessionTimeoutHours);
            }
            if (changes.selfServicePortal != null) {
                requireOneOf(changes.selfServicePortal, Set.of("enabled", "disabled"), "SelfServicePortal");
            }
            validateDescription(changes.description);
            // Everything is validated before the stored record is touched, so a rejected
            // modification leaves the endpoint unchanged.
            if (Boolean.TRUE.equals(changes.connectionLogEnabled)) {
                requireParameter(changes.connectionLogGroup, "ConnectionLogOptions.CloudwatchLogGroup");
            }
            if (Boolean.TRUE.equals(changes.clientConnectEnabled)) {
                requireParameter(changes.clientConnectLambdaFunctionArn, "ClientConnectOptions.LambdaFunctionArn");
            }
            if (Boolean.TRUE.equals(changes.clientLoginBannerEnabled)) {
                validateBannerText(changes.clientLoginBannerText);
            }
            List<String> dnsServers = null;
            if (changes.dnsServersEnabled != null) {
                dnsServers = changes.dnsServersEnabled
                        ? validDnsServers(changes.dnsServers == null ? List.of() : changes.dnsServers)
                        : List.of();
            }

            String vpcId = endpoint.getVpcId();
            List<String> securityGroupIds = null;
            if (!changes.securityGroupIds.isEmpty() && changes.vpcId == null) {
                throw new AwsException("InvalidParameterValue",
                        "VpcId must be specified when SecurityGroupIds are specified", 400);
            }
            if (changes.vpcId != null) {
                if (!changes.vpcId.equals(endpoint.getVpcId()) && hasActiveAssociations(endpoint)) {
                    throw new AwsException("InvalidParameterValue", "Cannot change the VPC of Client VPN endpoint "
                            + endpointId + " while it has associated target networks", 400);
                }
                requireVpc(region, changes.vpcId);
                vpcId = changes.vpcId;
                securityGroupIds = resolveSecurityGroups(region, vpcId, changes.securityGroupIds);
            }

            ClientVpnEndpoint updated = endpoint;
            applyConnectionLog(updated, region, changes.connectionLogEnabled,
                    changes.connectionLogGroup, changes.connectionLogStream);
            applyClientConnect(updated, changes.clientConnectEnabled, changes.clientConnectLambdaFunctionArn);
            applyLoginBanner(updated, changes.clientLoginBannerEnabled, changes.clientLoginBannerText);

            if (changes.serverCertificateArn != null
                    && !changes.serverCertificateArn.equals(updated.getServerCertificateArn())) {
                String previous = updated.getServerCertificateArn();
                updated.setServerCertificateArn(changes.serverCertificateArn);
                markCertificateInUse(region, changes.serverCertificateArn, arn);
                if (!certificateArns(updated.getServerCertificateArn(), updated.getAuthenticationOptions())
                        .contains(previous)) {
                    releaseCertificate(region, previous, arn);
                }
            }
            if (dnsServers != null) {
                updated.setDnsServers(new ArrayList<>(dnsServers));
            }
            if (changes.vpnPort != null) {
                updated.setVpnPort(changes.vpnPort);
            }
            if (changes.description != null) {
                updated.setDescription(changes.description);
            }
            if (changes.splitTunnel != null) {
                updated.setSplitTunnel(changes.splitTunnel);
            }
            if (securityGroupIds != null) {
                updated.setVpcId(vpcId);
                updated.setSecurityGroupIds(new ArrayList<>(securityGroupIds));
            }
            if (changes.selfServicePortal != null) {
                updated.setSelfServicePortal(changes.selfServicePortal);
            }
            if (changes.sessionTimeoutHours != null) {
                updated.setSessionTimeoutHours(changes.sessionTimeoutHours);
            }
            if (changes.clientRouteEnforced != null) {
                updated.setClientRouteEnforced(changes.clientRouteEnforced);
            }
            if (changes.disconnectOnSessionTimeout != null) {
                updated.setDisconnectOnSessionTimeout(changes.disconnectOnSessionTimeout);
            }
            endpoints.put(key(region, endpointId), updated);
        }
    }

    /** Deletes the endpoint and returns it as it was; AWS reports it {@code deleting}. */
    public ClientVpnEndpoint deleteEndpoint(String region, String endpointId) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            if (hasActiveAssociations(endpoint)) {
                throw new AwsException("DependencyViolation", "Client VPN endpoint " + endpointId
                        + " has associated target networks. Disassociate them before deleting the endpoint.", 400);
            }
            endpoints.delete(key(region, endpointId));
            ec2.deleteResourceTags(endpointId);
            String arn = endpointArn(endpoint);
            for (String certificate : certificateArns(endpoint.getServerCertificateArn(),
                    endpoint.getAuthenticationOptions())) {
                releaseCertificate(region, certificate, arn);
            }
            return endpoint;
        }
    }

    public ClientVpnEndpoint requireEndpoint(String region, String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter ClientVpnEndpointId", 400);
        }
        return endpoints.get(key(region, endpointId)).orElseThrow(() -> new AwsException(
                "InvalidClientVpnEndpointId.NotFound",
                "The Client VPN endpoint ID '" + endpointId + "' does not exist", 400));
    }

    public List<Tag> tags(ClientVpnEndpoint endpoint) {
        return ec2.resourceTags(endpoint.getClientVpnEndpointId());
    }

    public String endpointArn(ClientVpnEndpoint endpoint) {
        return AwsArnUtils.Arn.of("ec2", endpoint.getRegion(), endpoint.getOwnerId(),
                "client-vpn-endpoint/" + endpoint.getClientVpnEndpointId()).toString();
    }

    /**
     * The Client VPN endpoint that keeps {@code resourceId} (a subnet, security group or VPC) in
     * use, if any. AWS refuses to delete such a resource with {@code DependencyViolation}.
     */
    public Optional<String> dependentEndpoint(String region, String resourceId) {
        if (resourceId == null) {
            return Optional.empty();
        }
        return regionEndpoints(region).stream()
                .filter(endpoint -> resourceId.equals(endpoint.getVpcId())
                        || endpoint.getSecurityGroupIds().contains(resourceId)
                        || endpoint.getTargetNetworks().stream()
                                .anyMatch(network -> resourceId.equals(network.getSubnetId())))
                .map(ClientVpnEndpoint::getClientVpnEndpointId)
                .findFirst();
    }

    // ─── Target networks ────────────────────────────────────────────────────

    public ClientVpnTargetNetwork associateTargetNetwork(String region, String endpointId, String subnetId,
                                                         String clientToken) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            if (clientToken != null && !clientToken.isBlank()) {
                Optional<ClientVpnTargetNetwork> replay = endpoint.getTargetNetworks().stream()
                        .filter(network -> clientToken.equals(network.getClientToken()))
                        .findFirst();
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            requireParameter(subnetId, "SubnetId");
            if (!subnetId.startsWith("subnet-")) {
                throw new AwsException("InvalidSubnetID.Malformed", "Invalid id: \"" + subnetId + "\"", 400);
            }
            Subnet subnet = requireSubnet(region, subnetId);
            if (endpoint.getTargetNetworks().stream().anyMatch(network -> subnetId.equals(network.getSubnetId()))) {
                throw new AwsException("InvalidClientVpnDuplicateAssociationException",
                        "Subnet " + subnetId + " is already associated with Client VPN endpoint " + endpointId, 400);
            }
            if (endpoint.getVpcId() != null && !endpoint.getVpcId().equals(subnet.getVpcId())) {
                throw new AwsException("InvalidParameterValue", "Subnet " + subnetId
                        + " does not belong to VPC " + endpoint.getVpcId() + " of Client VPN endpoint " + endpointId, 400);
            }
            if (endpoint.getTargetNetworks().stream()
                    .anyMatch(network -> Objects.equals(subnet.getAvailabilityZone(), network.getAvailabilityZone()))) {
                throw new AwsException("InvalidClientVpnSubnetId.DuplicateAz", "Client VPN endpoint " + endpointId
                        + " already has a target network in Availability Zone " + subnet.getAvailabilityZone(), 400);
            }
            Vpc vpc = requireVpc(region, subnet.getVpcId());
            if (endpoint.getClientCidrBlock() != null) {
                for (String vpcCidr : vpcIpv4Cidrs(vpc)) {
                    if (Ipv4Cidrs.overlaps(vpcCidr, endpoint.getClientCidrBlock())) {
                        throw new AwsException("InvalidClientVpnSubnetId.OverlappingCidr", "The client CIDR "
                                + endpoint.getClientCidrBlock() + " overlaps the CIDR " + vpcCidr + " of VPC "
                                + vpc.getVpcId(), 400);
                    }
                }
            }
            if (endpoint.getVpcId() == null) {
                // AWS binds an endpoint created without a VPC to the first associated subnet's VPC.
                endpoint.setVpcId(vpc.getVpcId());
                endpoint.setSecurityGroupIds(new ArrayList<>(resolveSecurityGroups(region, vpc.getVpcId(), List.of())));
            }

            ClientVpnTargetNetwork network = new ClientVpnTargetNetwork();
            network.setAssociationId("cvpn-assoc-" + randomHex(17));
            network.setVpcId(vpc.getVpcId());
            network.setSubnetId(subnetId);
            network.setAvailabilityZone(subnet.getAvailabilityZone());
            network.setAvailabilityZoneId(subnet.getAvailabilityZoneId());
            network.setStatus("associated");
            network.setClientToken(clientToken);
            endpoint.getTargetNetworks().add(network);
            if (!"ipv6".equals(endpoint.getTrafficIpAddressType()) && vpc.getCidrBlock() != null
                    && endpoint.getRoutes().stream().noneMatch(route -> subnetId.equals(route.getTargetSubnet())
                            && sameCidr(vpc.getCidrBlock(), route.getDestinationCidr()))) {
                ClientVpnRoute route = new ClientVpnRoute();
                route.setDestinationCidr(vpc.getCidrBlock());
                route.setTargetSubnet(subnetId);
                route.setType("Nat");
                route.setOrigin("associate");
                route.setDescription("Default Route");
                route.setStatus("active");
                endpoint.getRoutes().add(route);
            }
            endpoint.setStatus("available");
            endpoints.put(key(region, endpointId), endpoint);
            return network;
        }
    }

    /** Removes the association and the routes that target its subnet; AWS reports it {@code disassociating}. */
    public ClientVpnTargetNetwork disassociateTargetNetwork(String region, String endpointId, String associationId) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            requireParameter(associationId, "AssociationId");
            ClientVpnTargetNetwork network = endpoint.getTargetNetworks().stream()
                    .filter(candidate -> associationId.equals(candidate.getAssociationId()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("InvalidClientVpnAssociationIdNotFound",
                            "The association ID '" + associationId + "' does not exist", 400));
            endpoint.getTargetNetworks().remove(network);
            endpoint.getRoutes().removeIf(route -> network.getSubnetId().equals(route.getTargetSubnet()));
            if (!hasActiveAssociations(endpoint)) {
                endpoint.setStatus("pending-associate");
            }
            endpoints.put(key(region, endpointId), endpoint);
            return network;
        }
    }

    public List<ClientVpnTargetNetwork> describeTargetNetworks(String region, String endpointId,
                                                               List<String> associationIds,
                                                               Map<String, List<String>> filters) {
        requireSupportedFilters(filters, TARGET_NETWORK_FILTERS, false);
        ClientVpnEndpoint endpoint = requireEndpoint(region, endpointId);
        return endpoint.getTargetNetworks().stream()
                .filter(network -> associationIds.isEmpty() || associationIds.contains(network.getAssociationId()))
                .filter(network -> filters.entrySet().stream().allMatch(filter -> switch (filter.getKey()) {
                    case "association-id" -> matchesAny(filter.getValue(), network.getAssociationId());
                    case "target-network-id" -> matchesAny(filter.getValue(), network.getSubnetId());
                    default -> matchesAny(filter.getValue(), network.getVpcId());
                }))
                .collect(Collectors.toList());
    }

    // ─── Authorization rules ────────────────────────────────────────────────

    public void authorizeIngress(String region, String endpointId, String targetNetworkCidr, String accessGroupId,
                                 Boolean authorizeAllGroups, String description, String clientToken) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            if (clientToken != null && !clientToken.isBlank()
                    && endpoint.getAuthorizationRules().stream().anyMatch(rule -> clientToken.equals(rule.getClientToken()))) {
                return;
            }
            requireParameter(targetNetworkCidr, "TargetNetworkCidr");
            requireCidr(targetNetworkCidr, "TargetNetworkCidr");
            boolean all = Boolean.TRUE.equals(authorizeAllGroups);
            boolean group = accessGroupId != null && !accessGroupId.isBlank();
            if (all == group) {
                throw new AwsException("InvalidParameterValue",
                        "Specify either AccessGroupId or AuthorizeAllGroups=true, but not both", 400);
            }
            validateDescription(description);
            boolean duplicate = endpoint.getAuthorizationRules().stream().anyMatch(rule ->
                    sameCidr(rule.getDestinationCidr(), targetNetworkCidr) && rule.isAccessAll() == all
                            && (all || accessGroupId.equals(rule.getGroupId())));
            if (duplicate) {
                throw new AwsException("InvalidClientVpnDuplicateAuthorizationRule",
                        "An authorization rule for " + targetNetworkCidr + " already exists", 400);
            }
            ClientVpnAuthorizationRule rule = new ClientVpnAuthorizationRule();
            rule.setDestinationCidr(targetNetworkCidr);
            rule.setAccessAll(all);
            rule.setGroupId(all ? null : accessGroupId);
            rule.setDescription(description);
            rule.setStatus("active");
            rule.setClientToken(clientToken);
            endpoint.getAuthorizationRules().add(rule);
            endpoints.put(key(region, endpointId), endpoint);
        }
    }

    public void revokeIngress(String region, String endpointId, String targetNetworkCidr, String accessGroupId,
                              Boolean revokeAllGroups) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            requireParameter(targetNetworkCidr, "TargetNetworkCidr");
            requireCidr(targetNetworkCidr, "TargetNetworkCidr");
            boolean all = Boolean.TRUE.equals(revokeAllGroups);
            boolean group = accessGroupId != null && !accessGroupId.isBlank();
            if (all == group) {
                throw new AwsException("InvalidParameterValue",
                        "Specify either AccessGroupId or RevokeAllGroups=true, but not both", 400);
            }
            boolean removed = endpoint.getAuthorizationRules().removeIf(rule ->
                    sameCidr(rule.getDestinationCidr(), targetNetworkCidr) && rule.isAccessAll() == all
                            && (all || accessGroupId.equals(rule.getGroupId())));
            if (!removed) {
                throw new AwsException("InvalidClientVpnEndpointAuthorizationRuleNotFound",
                        "No authorization rule for " + targetNetworkCidr + " exists on Client VPN endpoint "
                                + endpointId, 400);
            }
            endpoints.put(key(region, endpointId), endpoint);
        }
    }

    public List<ClientVpnAuthorizationRule> describeAuthorizationRules(String region, String endpointId,
                                                                       Map<String, List<String>> filters) {
        requireSupportedFilters(filters, AUTHORIZATION_RULE_FILTERS, false);
        ClientVpnEndpoint endpoint = requireEndpoint(region, endpointId);
        return endpoint.getAuthorizationRules().stream()
                .filter(rule -> filters.entrySet().stream().allMatch(filter -> switch (filter.getKey()) {
                    case "description" -> matchesAny(filter.getValue(), rule.getDescription());
                    case "destination-cidr" -> matchesAny(filter.getValue(), rule.getDestinationCidr());
                    default -> matchesAny(filter.getValue(), rule.getGroupId());
                }))
                .collect(Collectors.toList());
    }

    // ─── Routes ─────────────────────────────────────────────────────────────

    public void createRoute(String region, String endpointId, String destinationCidrBlock, String targetSubnetId,
                            String description, String clientToken) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            if (clientToken != null && !clientToken.isBlank()
                    && endpoint.getRoutes().stream().anyMatch(route -> clientToken.equals(route.getClientToken()))) {
                return;
            }
            requireParameter(destinationCidrBlock, "DestinationCidrBlock");
            requireParameter(targetSubnetId, "TargetVpcSubnetId");
            requireCidr(destinationCidrBlock, "DestinationCidrBlock");
            validateDescription(description);
            if (endpoint.getTargetNetworks().stream().noneMatch(network -> targetSubnetId.equals(network.getSubnetId())
                    && "associated".equals(network.getStatus()))) {
                throw new AwsException("InvalidClientVpnActiveAssociationNotFound", "Subnet " + targetSubnetId
                        + " is not associated with Client VPN endpoint " + endpointId, 400);
            }
            if (endpoint.getRoutes().stream().anyMatch(route -> targetSubnetId.equals(route.getTargetSubnet())
                    && sameCidr(route.getDestinationCidr(), destinationCidrBlock))) {
                throw new AwsException("InvalidClientVpnDuplicateRoute", "A route to " + destinationCidrBlock
                        + " via " + targetSubnetId + " already exists", 400);
            }
            ClientVpnRoute route = new ClientVpnRoute();
            route.setDestinationCidr(destinationCidrBlock);
            route.setTargetSubnet(targetSubnetId);
            route.setType("Nat");
            route.setOrigin("add-route");
            route.setDescription(description);
            route.setStatus("active");
            route.setClientToken(clientToken);
            endpoint.getRoutes().add(route);
            endpoints.put(key(region, endpointId), endpoint);
        }
    }

    public void deleteRoute(String region, String endpointId, String destinationCidrBlock, String targetSubnetId) {
        synchronized (lock) {
            ClientVpnEndpoint endpoint = copy(requireEndpoint(region, endpointId));
            requireParameter(destinationCidrBlock, "DestinationCidrBlock");
            requireCidr(destinationCidrBlock, "DestinationCidrBlock");
            Predicate<ClientVpnRoute> matches = route -> sameCidr(route.getDestinationCidr(), destinationCidrBlock)
                    && (targetSubnetId == null || targetSubnetId.equals(route.getTargetSubnet()));
            List<ClientVpnRoute> found = endpoint.getRoutes().stream().filter(matches).toList();
            if (found.isEmpty()) {
                throw new AwsException("InvalidClientVpnRouteNotFound", "No route to " + destinationCidrBlock
                        + (targetSubnetId == null ? "" : " via " + targetSubnetId) + " exists on Client VPN endpoint "
                        + endpointId, 400);
            }
            if (found.stream().anyMatch(route -> !"add-route".equals(route.getOrigin()))) {
                throw new AwsException("InvalidParameterValue", "The route to " + destinationCidrBlock
                        + " was added by a target network association and cannot be deleted", 400);
            }
            endpoint.getRoutes().removeAll(found);
            endpoints.put(key(region, endpointId), endpoint);
        }
    }

    public List<ClientVpnRoute> describeRoutes(String region, String endpointId, Map<String, List<String>> filters) {
        requireSupportedFilters(filters, ROUTE_FILTERS, false);
        ClientVpnEndpoint endpoint = requireEndpoint(region, endpointId);
        return endpoint.getRoutes().stream()
                .filter(route -> filters.entrySet().stream().allMatch(filter -> switch (filter.getKey()) {
                    case "destination-cidr" -> matchesAny(filter.getValue(), route.getDestinationCidr());
                    case "origin" -> matchesAny(filter.getValue(), route.getOrigin());
                    default -> matchesAny(filter.getValue(), route.getTargetSubnet());
                }))
                .collect(Collectors.toList());
    }

    // ─── Validation helpers ─────────────────────────────────────────────────

    private List<ClientVpnEndpoint> regionEndpoints(String region) {
        return endpoints.scan(k -> k.startsWith(region + "::")).stream()
                .filter(endpoint -> region.equals(endpoint.getRegion()))
                .collect(Collectors.toList());
    }

    private static boolean hasActiveAssociations(ClientVpnEndpoint endpoint) {
        return !endpoint.getTargetNetworks().isEmpty();
    }

    private static void rejectTransitGatewayConfiguration(EndpointSettings request) {
        if (request.transitGatewayConfiguration) {
            throw new AwsException("InvalidParameterValue",
                    "TransitGatewayConfiguration is not supported by this emulator", 400);
        }
    }

    private static void validateAuthentication(List<ClientVpnAuthentication> options) {
        if (options.size() > 2) {
            throw new AwsException("InvalidParameterValue", "At most two authentication options can be specified", 400);
        }
        Set<String> seen = new HashSet<>();
        for (ClientVpnAuthentication option : options) {
            String type = option.getType();
            requireParameter(type, "AuthenticationOptions.Type");
            requireOneOf(type, AUTHENTICATION_TYPES, "AuthenticationOptions.Type");
            if (!seen.add(type)) {
                throw new AwsException("InvalidParameterValue", "Duplicate authentication type " + type, 400);
            }
            switch (type) {
                case "certificate-authentication" -> requireParameter(option.getClientRootCertificateChainArn(),
                        "MutualAuthentication.ClientRootCertificateChainArn");
                case "directory-service-authentication" ->
                        requireParameter(option.getDirectoryId(), "ActiveDirectory.DirectoryId");
                default -> requireParameter(option.getSamlProviderArn(), "FederatedAuthentication.SAMLProviderArn");
            }
        }
        if (seen.contains("directory-service-authentication") && seen.contains("federated-authentication")) {
            throw new AwsException("InvalidParameterValue",
                    "Directory service and federated authentication cannot be combined", 400);
        }
    }

    private static void validateClientCidr(String cidr) {
        int slash = cidr.indexOf('/');
        int prefix;
        try {
            prefix = slash < 0 ? -1 : Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            prefix = -1;
        }
        if (!Ipv4Cidrs.isIpv4(cidr) || prefix < 12 || prefix > 22) {
            throw new AwsException("InvalidParameterValue", "ClientCidrBlock " + cidr
                    + " must be an IPv4 CIDR block with a prefix between /12 and /22", 400);
        }
    }

    private static void requireCidr(String cidr, String name) {
        if (CidrCanonicalizer.canonicalize(cidr).isEmpty()) {
            throw new AwsException("InvalidParameterValue", "Invalid " + name + ": " + cidr, 400);
        }
    }

    private static boolean sameCidr(String a, String b) {
        return Objects.equals(CidrCanonicalizer.canonicalize(a).orElse(a), CidrCanonicalizer.canonicalize(b).orElse(b));
    }

    private static int validVpnPort(int port) {
        if (port != 443 && port != 1194) {
            throw new AwsException("InvalidParameterValue", "VpnPort must be 443 or 1194", 400);
        }
        return port;
    }

    private static int validSessionTimeout(int hours) {
        if (!SESSION_TIMEOUT_HOURS.contains(hours)) {
            throw new AwsException("InvalidParameterValue", "SessionTimeoutHours must be one of 8, 10, 12 or 24", 400);
        }
        return hours;
    }

    private static List<String> validDnsServers(List<String> servers) {
        if (servers.size() > 2) {
            throw new AwsException("InvalidParameterValue", "At most two DNS servers can be specified", 400);
        }
        for (String server : servers) {
            boolean valid = server != null && (CidrCanonicalizer.canonicalize(server + "/32").isPresent()
                    || CidrCanonicalizer.canonicalize(server + "/128").isPresent());
            if (!valid) {
                throw new AwsException("InvalidParameterValue", "Invalid DNS server address " + server, 400);
            }
        }
        return servers;
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > 255) {
            throw new AwsException("InvalidParameterValue", "Description must be at most 255 characters", 400);
        }
    }

    private Subnet requireSubnet(String region, String subnetId) {
        try {
            return ec2.describeSubnets(region, List.of(subnetId), Map.of()).getFirst();
        } catch (AwsException e) {
            if (!"InvalidSubnetID.NotFound".equals(e.getErrorCode())) {
                throw e;
            }
            throw new AwsException("InvalidClientVpnSubnetId.NotFound",
                    "The subnet ID '" + subnetId + "' does not exist", 400);
        }
    }

    private Vpc requireVpc(String region, String vpcId) {
        return ec2.describeVpcs(region, List.of(vpcId), Map.of()).getFirst();
    }

    private static List<String> vpcIpv4Cidrs(Vpc vpc) {
        List<String> cidrs = new ArrayList<>();
        if (vpc.getCidrBlock() != null) {
            cidrs.add(vpc.getCidrBlock());
        }
        vpc.getCidrBlockAssociationSet().stream()
                .map(association -> association.getCidrBlock())
                .filter(cidr -> cidr != null && !cidrs.contains(cidr))
                .forEach(cidrs::add);
        return cidrs;
    }

    /** The requested groups, validated against the VPC, or the VPC's default group when none are requested. */
    private List<String> resolveSecurityGroups(String region, String vpcId, List<String> requested) {
        if (requested.size() > 5) {
            throw new AwsException("InvalidParameterValue", "At most five security groups can be specified", 400);
        }
        if (requested.isEmpty()) {
            return ec2.describeSecurityGroups(region, List.of(), List.of("default"),
                            Map.of("vpc-id", List.of(vpcId))).stream()
                    .map(SecurityGroup::getGroupId)
                    .limit(1)
                    .toList();
        }
        List<String> distinct = requested.stream().distinct().toList();
        for (SecurityGroup group : ec2.describeSecurityGroups(region, distinct, List.of(), Map.of())) {
            if (!vpcId.equals(group.getVpcId())) {
                throw new AwsException("InvalidParameterValue", "Security group " + group.getGroupId()
                        + " does not belong to VPC " + vpcId, 400);
            }
        }
        return distinct;
    }

    private void applyConnectionLog(ClientVpnEndpoint endpoint, String region, Boolean enabled, String group,
                                    String stream) {
        if (enabled == null) {
            return;
        }
        if (!enabled) {
            endpoint.setConnectionLogEnabled(false);
            endpoint.setConnectionLogGroup(null);
            endpoint.setConnectionLogStream(null);
            return;
        }
        requireParameter(group, "ConnectionLogOptions.CloudwatchLogGroup");
        endpoint.setConnectionLogEnabled(true);
        endpoint.setConnectionLogGroup(group);
        // AWS names a stream itself when only the group is given.
        endpoint.setConnectionLogStream(stream != null && !stream.isBlank() ? stream
                : endpoint.getClientVpnEndpointId() + "-" + region + "-" + LOG_STREAM_DATE.format(Instant.now())
                        + "-" + randomHex(12));
    }

    private static void applyClientConnect(ClientVpnEndpoint endpoint, Boolean enabled, String lambdaFunctionArn) {
        if (enabled == null) {
            return;
        }
        if (enabled) {
            requireParameter(lambdaFunctionArn, "ClientConnectOptions.LambdaFunctionArn");
        }
        endpoint.setClientConnectEnabled(enabled);
        endpoint.setClientConnectLambdaFunctionArn(enabled ? lambdaFunctionArn : null);
    }

    private static void applyLoginBanner(ClientVpnEndpoint endpoint, Boolean enabled, String text) {
        if (enabled == null) {
            return;
        }
        if (enabled) {
            validateBannerText(text);
        }
        endpoint.setClientLoginBannerEnabled(enabled);
        endpoint.setClientLoginBannerText(enabled ? text : null);
    }

    private static void validateBannerText(String text) {
        requireParameter(text, "ClientLoginBannerOptions.BannerText");
        if (text.length() > 1400) {
            throw new AwsException("InvalidParameterValue", "BannerText must be at most 1400 characters", 400);
        }
    }

    private static List<String> certificateArns(String serverCertificateArn,
                                                List<ClientVpnAuthentication> authenticationOptions) {
        List<String> arns = new ArrayList<>();
        if (serverCertificateArn != null) {
            arns.add(serverCertificateArn);
        }
        for (ClientVpnAuthentication option : authenticationOptions) {
            String chain = option.getClientRootCertificateChainArn();
            if (chain != null && !arns.contains(chain)) {
                arns.add(chain);
            }
        }
        return arns;
    }

    private void requireCertificate(String region, String certificateArn) {
        var match = CERTIFICATE_ARN.matcher(certificateArn);
        if (!match.matches()) {
            throw new AwsException("InvalidParameterValue", "Invalid certificate ARN " + certificateArn, 400);
        }
        if (!region.equals(match.group(1))) {
            throw new AwsException("InvalidParameterValue", "Certificate " + certificateArn
                    + " must be in the same region as the Client VPN endpoint (" + region + ")", 400);
        }
        AcmService certificates = acm.get();
        if (certificates == null) {
            return;
        }
        try {
            certificates.describeCertificate(certificateArn, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            throw new AwsException("InvalidParameterValue", "Certificate " + certificateArn + " does not exist", 400);
        }
    }

    private void markCertificateInUse(String region, String certificateArn, String endpointArn) {
        AcmService certificates = acm.get();
        if (certificates != null) {
            certificates.addInUseBy(certificateArn, endpointArn, region);
        }
    }

    private void releaseCertificate(String region, String certificateArn, String endpointArn) {
        AcmService certificates = acm.get();
        if (certificates != null && certificateArn != null) {
            certificates.removeInUseBy(certificateArn, endpointArn, region);
        }
    }

    private static void requireSupportedFilters(Map<String, List<String>> filters, Set<String> supported,
                                                boolean tagFilters) {
        for (String name : filters.keySet()) {
            if (!supported.contains(name) && !(tagFilters && name.startsWith("tag:"))) {
                throw new AwsException("InvalidParameterValue", "The filter '" + name + "' is invalid", 400);
            }
        }
    }

    private static boolean matchesAny(List<String> patterns, String value) {
        if (value == null) {
            return false;
        }
        for (String pattern : patterns) {
            StringBuilder regex = new StringBuilder();
            for (char ch : pattern.toCharArray()) {
                regex.append(switch (ch) {
                    case '*' -> ".*";
                    case '?' -> ".";
                    default -> Pattern.quote(String.valueOf(ch));
                });
            }
            if (value.matches(regex.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void requireParameter(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter " + name, 400);
        }
    }

    private static void requireOneOf(String value, Set<String> allowed, String name) {
        if (!allowed.contains(value)) {
            throw new AwsException("InvalidParameterValue", "Invalid value '" + value + "' for " + name, 400);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Mutations work on a copy so readers never observe a half-applied change of the stored record. */
    private static ClientVpnEndpoint copy(ClientVpnEndpoint endpoint) {
        return MAPPER.convertValue(endpoint, ClientVpnEndpoint.class);
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
    }
}

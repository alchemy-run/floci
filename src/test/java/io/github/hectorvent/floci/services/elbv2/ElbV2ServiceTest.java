package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.elbv2.model.TrustStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElbV2ServiceTest {

    private static final String REGION = "us-west-2";

    // Application Load Balancers require subnets in at least two Availability Zones.
    private static final List<String> ALB_SUBNETS = List.of("subnet-a", "subnet-b");

    @Mock
    ElbV2DataPlane dataPlane;

    @Mock
    ElbV2HealthChecker healthChecker;

    @Mock
    Ec2Service ec2Service;

    @Mock
    S3Service s3Service;

    private ElbV2Service service;

    private static final String CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIB
            -----END CERTIFICATE-----
            """;

    @BeforeEach
    void setUp() {
        service = new ElbV2Service();
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        service.s3Service = s3Service;
        stubAlbSubnets(ec2Service);
        stubCaBundle(s3Service, "ca-bundles", "ca-bundle.pem", CA_PEM);
    }

    @Test
    void modifyListenerDefaultActionsRecompilesRulesWithoutRestartingListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String oldTgArn = createTargetGroup("sample-old-tg");
        String newTgArn = createTargetGroup("sample-new-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 9999, null, List.of(),
                List.of(forwardAction(oldTgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(
                REGION, listenerArn, null, null, null, null,
                List.of(forwardAction(newTgArn)), null);

        ArgumentCaptor<List<Rule>> rulesCaptor = ArgumentCaptor.captor();
        verify(dataPlane).recompileRules(eq(listenerArn), rulesCaptor.capture());
        verify(dataPlane, never()).stopListener(anyString());
        verify(dataPlane, never()).startListener(any(Listener.class), anyString(), anyList());
        verify(dataPlane, never()).restartListener(any(Listener.class), anyString(), anyList());

        Rule defaultRule = rulesCaptor.getValue().stream()
                .filter(Rule::isDefault)
                .findFirst()
                .orElseThrow();
        assertEquals(newTgArn, defaultRule.getActions().getFirst().getTargetGroupArn());

        TargetGroup oldTargetGroup = service.describeTargetGroups(REGION, null, List.of(oldTgArn), null).getFirst();
        TargetGroup newTargetGroup = service.describeTargetGroups(REGION, null, List.of(newTgArn), null).getFirst();
        assertFalse(oldTargetGroup.getLoadBalancerArns().contains(lbArn));
        assertTrue(newTargetGroup.getLoadBalancerArns().contains(lbArn));
    }

    @Test
    void modifyListenerPortRestartsListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 9999, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(REGION, listenerArn, null, 10000, null, null, null, null);

        verify(dataPlane).restartListener(any(Listener.class), eq(REGION), anyList());
        verify(dataPlane, never()).stopListener(anyString());
        verify(dataPlane, never()).startListener(any(Listener.class), anyString(), anyList());
    }

    @Test
    void createLoadBalancerAcceptsSubnetsEc2StoreCannotSee() {
        when(ec2Service.findSubnetById(eq(REGION), anyString())).thenReturn(Optional.empty());

        var lb = service.createLoadBalancer(
                REGION, "foreign-subnets", "internal", "application", "ipv4",
                List.of("subnet-05d391f2554e440c8", "subnet-0abcdef1234567890"),
                List.of("sg-a"), Map.of());

        assertEquals("vpc-default", lb.getVpcId());
        assertEquals(2, lb.getAvailabilityZones().size());
        assertEquals("subnet-05d391f2554e440c8", lb.getAvailabilityZones().get(0).getSubnetId());
        assertEquals("subnet-0abcdef1234567890", lb.getAvailabilityZones().get(1).getSubnetId());
        assertEquals(REGION + "a", lb.getAvailabilityZones().get(0).getZoneName());
        assertEquals(REGION + "b", lb.getAvailabilityZones().get(1).getZoneName());
    }

    @Test
    void createLoadBalancerMissingSubnetUsesTypedSubnetNotFound() {
        AwsException error = assertThrows(AwsException.class, () -> service.createLoadBalancer(
                REGION, "blank-subnet", "internal", "application", "ipv4",
                List.of("subnet-a", "   "), List.of("sg-a"), Map.of()));
        assertEquals("SubnetNotFound", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createLoadBalancerUsesConfiguredHostnameForDnsSuffix() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        service.config = config;

        String dnsName = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getDnsName();

        assertTrue(dnsName.endsWith(".elb.floci"));
    }

    @Test
    void initializeStorageReloadsPersistedResourcesAndRebuildsIndexes() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2DataPlane firstDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker firstHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service first = serviceWithStorage(storageFactory, firstDataPlane, firstHealthChecker);
        String lbArn = first.createLoadBalancer(
                REGION, "persisted-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of("owner", "platform")).getLoadBalancerArn();
        String tgArn = first.createTargetGroup(
                REGION, "persisted-tg", "HTTP", "HTTP1", 8080, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/health", 15, 5, 3, 2, "200",
                "ipv4", Map.of("tier", "web")).getTargetGroupArn();
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");
        target.setPort(8080);
        first.registerTargets(REGION, tgArn, List.of(target));
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 9080, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of("listener", "frontdoor")).getListenerArn();
        Rule rule = first.createRule(
                REGION, listenerArn, List.of(pathPattern("/api/*")),
                10, List.of(forwardAction(tgArn)), Map.of("rule", "api"));
        first.addTags(List.of(lbArn, tgArn, listenerArn, rule.getRuleArn()), Map.of("env", "test"));

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker reloadedHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, reloadedHealthChecker);

        assertEquals(lbArn, reloaded.describeLoadBalancers(REGION, null, List.of("persisted-lb"), null, null)
                .getFirst().getLoadBalancerArn());
        TargetGroup reloadedTargetGroup = reloaded.describeTargetGroups(REGION, lbArn, null, null).getFirst();
        assertEquals(tgArn, reloadedTargetGroup.getTargetGroupArn());
        assertEquals(List.of(lbArn), reloadedTargetGroup.getLoadBalancerArns());
        assertEquals("i-1234567890abcdef0", reloadedTargetGroup.getTargets().getFirst().getId());
        Listener reloadedListener = reloaded.describeListeners(REGION, lbArn, null).getFirst();
        assertEquals(listenerArn, reloadedListener.getListenerArn());
        List<Rule> reloadedRules = reloaded.describeRules(REGION, listenerArn, null);
        assertEquals(2, reloadedRules.size());
        assertTrue(reloadedRules.stream().anyMatch(Rule::isDefault));
        assertTrue(reloadedRules.stream().anyMatch(candidate -> "10".equals(candidate.getPriority())));
        assertEquals("test", reloaded.describeTags(List.of(lbArn)).get(lbArn).get("env"));
        assertThrows(AwsException.class, () -> reloaded.deleteTargetGroup(REGION, tgArn));

        // Data-plane and health-check startup is deliberately not part of construction (#1913).
        reloaded.restorePersistedRuntime();
        verify(reloadedHealthChecker).startMonitoring(any(TargetGroup.class));
        ArgumentCaptor<List<TargetDescription>> targetsCaptor = ArgumentCaptor.captor();
        verify(reloadedHealthChecker).addTargets(eq(tgArn), targetsCaptor.capture(), any(TargetGroup.class));
        assertEquals("i-1234567890abcdef0", targetsCaptor.getValue().getFirst().getId());
        assertEquals(8080, targetsCaptor.getValue().getFirst().getPort());
        verify(reloadedDataPlane).startListener(any(Listener.class), eq(REGION), anyList());

        reloaded.removeTags(List.of(rule.getRuleArn()), List.of("env"));
        ElbV2Service updatedReload = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        assertFalse(updatedReload.describeTags(List.of(rule.getRuleArn()))
                .get(rule.getRuleArn()).containsKey("env"));
    }

    @Test
    void initializeStorageDoesNotTouchCollaborators() {
        // #1913: ElbV2DataPlane.binding() calls back into this service, and during @PostConstruct the
        // ApplicationScoped context has no instance yet — so CDI re-enters bean creation and recurses
        // until the stack (or, before the storage dedupe in #1931, the heap) is gone. Nothing reachable
        // from initializeStorage() may call an injected collaborator. A mock cannot reproduce the
        // recursion, since it never calls back, so assert the absence of the interaction instead.
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2Service first = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String lbArn = first.createLoadBalancer(
                REGION, "recursion-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String tgArn = first.createTargetGroup(
                REGION, "recursion-tg", "HTTP", "HTTP1", 8080, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/health", 15, 5, 3, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 9090, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker reloadedHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, reloadedHealthChecker);

        verifyNoInteractions(reloadedDataPlane, reloadedHealthChecker);

        reloaded.restorePersistedRuntime();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.captor();
        verify(reloadedDataPlane).startListener(listenerCaptor.capture(), eq(REGION), anyList());
        assertEquals(listenerArn, listenerCaptor.getValue().getListenerArn());
        verify(reloadedHealthChecker).startMonitoring(any(TargetGroup.class));
    }

    @Test
    void restorePersistedRuntimeStartsPersistedListenersWithoutTargetGroups() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2Service first = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String lbArn = first.createLoadBalancer(
                REGION, "listener-only-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 8080, null, List.of(),
                List.of(), List.of(), Map.of()).getListenerArn();

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, mock(ElbV2HealthChecker.class));
        reloaded.restorePersistedRuntime();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.captor();
        verify(reloadedDataPlane).startListener(listenerCaptor.capture(), eq(REGION), anyList());
        assertEquals(listenerArn, listenerCaptor.getValue().getListenerArn());
    }

    @Test
    void createTrustStoreReadsCaBundleAndReturnsActive() {
        TrustStore ts = service.createTrustStore(
                REGION, "mtls-store", "ca-bundles", "ca-bundle.pem", null, Map.of("env", "test"));

        assertEquals("ACTIVE", ts.getStatus());
        assertEquals(1, ts.getNumberOfCaCertificates());
        assertTrue(ts.getTrustStoreArn().contains(":truststore/mtls-store/"));
        assertEquals("test", service.describeTags(List.of(ts.getTrustStoreArn()))
                .get(ts.getTrustStoreArn()).get("env"));
    }

    @Test
    void createTrustStoreMissingBundleIsCaCertificatesBundleNotFound() {
        when(s3Service.getObject("missing-bucket", "missing.pem"))
                .thenThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));

        AwsException error = assertThrows(AwsException.class, () -> service.createTrustStore(
                REGION, "probe-store", "missing-bucket", "missing.pem", null, Map.of()));

        assertEquals("CaCertificatesBundleNotFound", error.getErrorCode());
    }

    @Test
    void createTrustStoreInvalidPemIsInvalidCaCertificatesBundle() {
        stubCaBundle(s3Service, "ca-bundles", "not-a-cert.txt", "not a certificate");

        AwsException error = assertThrows(AwsException.class, () -> service.createTrustStore(
                REGION, "bad-store", "ca-bundles", "not-a-cert.txt", null, Map.of()));

        assertEquals("InvalidCaCertificatesBundle", error.getErrorCode());
    }

    @Test
    void describeTrustStoresByNameAndArn() {
        TrustStore created = service.createTrustStore(
                REGION, "lookup-store", "ca-bundles", "ca-bundle.pem", null, Map.of());

        assertEquals(created.getTrustStoreArn(),
                service.describeTrustStores(REGION, null, List.of("lookup-store")).getFirst().getTrustStoreArn());
        assertEquals("lookup-store",
                service.describeTrustStores(REGION, List.of(created.getTrustStoreArn()), null)
                        .getFirst().getName());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeTrustStores(REGION, null, List.of("no-such-store")));
        assertEquals("TrustStoreNotFound", missing.getErrorCode());
    }

    @Test
    void modifyAndDeleteTrustStore() {
        TrustStore created = service.createTrustStore(
                REGION, "lifecycle-store", "ca-bundles", "ca-bundle.pem", null, Map.of());
        stubCaBundle(s3Service, "ca-bundles", "ca-bundle-2.pem", CA_PEM + CA_PEM);

        TrustStore modified = service.modifyTrustStore(
                REGION, created.getTrustStoreArn(), "ca-bundles", "ca-bundle-2.pem", null);
        assertEquals(2, modified.getNumberOfCaCertificates());

        assertEquals("https://s3.us-west-2.amazonaws.com/ca-bundles/ca-bundle-2.pem",
                service.getTrustStoreCaCertificatesBundleLocation(REGION, created.getTrustStoreArn()));

        AwsException revocation = assertThrows(AwsException.class,
                () -> service.getTrustStoreRevocationContentLocation(
                        REGION, created.getTrustStoreArn(), 424242L));
        assertEquals("RevocationIdNotFound", revocation.getErrorCode());

        service.deleteTrustStore(REGION, created.getTrustStoreArn());
        AwsException gone = assertThrows(AwsException.class,
                () -> service.describeTrustStores(REGION, List.of(created.getTrustStoreArn()), null));
        assertEquals("TrustStoreNotFound", gone.getErrorCode());
    }

    @Test
    void createListenerStoresAuthenticateOidcAction() {
        String lbArn = service.createLoadBalancer(
                REGION, "oidc-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Action oidc = new Action();
        oidc.setType("authenticate-oidc");
        oidc.setOidcIssuer("https://idp.example.test");
        oidc.setOidcAuthorizationEndpoint("https://idp.example.test/authorize");
        oidc.setOidcTokenEndpoint("https://idp.example.test/token");
        oidc.setOidcUserInfoEndpoint("https://idp.example.test/userinfo");
        oidc.setOidcClientId("alchemy-test-client");
        oidc.setOidcClientSecret("secret");
        oidc.setOidcSessionTimeout(604800L);
        oidc.setOidcOnUnauthenticatedRequest("deny");
        Action fixed = new Action();
        fixed.setType("fixed-response");
        fixed.setFixedResponseStatusCode("200");

        String listenerArn = service.createListener(
                REGION, lbArn, "HTTPS", 443, null, List.of(),
                List.of(oidc, fixed), List.of(), Map.of()).getListenerArn();

        Action stored = service.describeListeners(REGION, lbArn, List.of(listenerArn))
                .getFirst().getDefaultActions().getFirst();
        assertEquals("authenticate-oidc", stored.getType());
        assertEquals("alchemy-test-client", stored.getOidcClientId());
        assertEquals(604800L, stored.getOidcSessionTimeout());
        assertEquals("deny", stored.getOidcOnUnauthenticatedRequest());
    }

    @Test
    void createListenerWhenPersistedRegionMapIsImmutable() throws Exception {
        String lbArn = service.createLoadBalancer(
                REGION, "immutable-region-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        var field = ElbV2Service.class.getDeclaredField("listeners");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Listener>> persisted = (Map<String, Map<String, Listener>>) field.get(service);
        persisted.put(REGION, Map.of());

        Action fixed = new Action();
        fixed.setType("fixed-response");
        fixed.setFixedResponseStatusCode("200");
        Listener listener = service.createListener(
                REGION, lbArn, "HTTP", 80, null, List.of(),
                List.of(fixed), List.of(), Map.of());

        assertNotNull(listener.getListenerArn());
        assertEquals(1, service.describeListeners(REGION, lbArn, null).size());
    }

    @Test
    void createListenerOnNlbTcpSurvivesDataPlaneNpe() {
        doThrow(new NullPointerException()).when(dataPlane)
                .startListener(any(Listener.class), anyString(), anyList());
        String lbArn = service.createLoadBalancer(
                REGION, "nlb-tcp", "internal", "network", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        String tgArn = createTcpTargetGroup("nlb-tcp-tg");
        Action forward = forwardConfigOnly(tgArn);

        Listener listener = service.createListener(
                REGION, lbArn, "TCP", 80, null, List.of(),
                List.of(forward), List.of(), Map.of());

        assertNotNull(listener.getListenerArn());
        assertTrue(listener.getListenerArn().contains(":listener/net/"));
        assertEquals("TCP", listener.getProtocol());
        assertEquals(80, listener.getPort());
        assertEquals(1, service.describeListeners(REGION, lbArn, null).size());
    }

    @Test
    void createListenerHttpsWithCertificateAndNullPortStillPersists() {
        doThrow(new NullPointerException()).when(dataPlane)
                .startListener(any(Listener.class), anyString(), anyList());
        String lbArn = service.createLoadBalancer(
                REGION, "https-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Action fixed = new Action();
        fixed.setType("fixed-response");
        fixed.setFixedResponseStatusCode("200");

        Listener withCert = service.createListener(
                REGION, lbArn, "HTTPS", 443, "ELBSecurityPolicy-2016-08",
                List.of("arn:aws:acm:us-west-2:000000000000:certificate/default"),
                List.of(fixed), List.of(), Map.of());
        assertEquals("HTTPS", withCert.getProtocol());
        assertEquals("arn:aws:acm:us-west-2:000000000000:certificate/default",
                withCert.getCertificates().getFirst());

        Listener tls = service.createListener(
                REGION, lbArn, "TLS", 8443, null,
                List.of("arn:aws:acm:us-west-2:000000000000:certificate/tls"),
                List.of(fixed), List.of(), Map.of());
        assertEquals("TLS", tls.getProtocol());

        Listener noPort = service.createListener(
                REGION, lbArn, "TCP", null, null, List.of(),
                List.of(fixed), List.of(), Map.of());
        assertNotNull(noPort.getListenerArn());
        assertEquals("TCP", noPort.getProtocol());
    }

    @Test
    void listenerCertificatesSniAttachDetachPreservesDefault() {
        String lbArn = service.createLoadBalancer(
                REGION, "sni-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Action fixed = new Action();
        fixed.setType("fixed-response");
        fixed.setFixedResponseStatusCode("200");
        String defaultCert = "arn:aws:acm:us-west-2:000000000000:certificate/default";
        String sniCert = "arn:aws:acm:us-west-2:000000000000:certificate/sni";
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTPS", 443, null, List.of(defaultCert),
                List.of(fixed), List.of(), Map.of()).getListenerArn();

        service.addListenerCertificates(REGION, listenerArn, List.of(sniCert));
        List<String> attached = service.describeListenerCertificates(REGION, listenerArn);
        assertEquals(List.of(defaultCert, sniCert), attached);

        service.modifyListener(REGION, listenerArn, "HTTPS", 443, null,
                List.of(defaultCert), null, null);
        attached = service.describeListenerCertificates(REGION, listenerArn);
        assertEquals(List.of(defaultCert, sniCert), attached);

        service.removeListenerCertificates(REGION, listenerArn, List.of(sniCert));
        attached = service.describeListenerCertificates(REGION, listenerArn);
        assertEquals(List.of(defaultCert), attached);

        AwsException defaultRemove = assertThrows(AwsException.class,
                () -> service.removeListenerCertificates(REGION, listenerArn, List.of(defaultCert)));
        assertEquals("OperationNotPermitted", defaultRemove.getErrorCode());
    }

    @Test
    void modifyCapacityReservationResetIsNoOpSuccess() {
        String lbArn = service.createLoadBalancer(
                REGION, "capacity-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();

        ElbV2Service.CapacityReservation reset = service.modifyCapacityReservation(REGION, lbArn, null, true);
        assertEquals(null, reset.minimumCapacityUnits());

        ElbV2Service.CapacityReservation reserved = service.modifyCapacityReservation(REGION, lbArn, 10, false);
        assertEquals(10, reserved.minimumCapacityUnits());
        assertEquals(10, service.describeCapacityReservation(REGION, lbArn).minimumCapacityUnits());
    }

    @Test
    void describeTargetHealthReturnsUnusedForExplicitUnregisteredTarget() {
        String tgArn = createTargetGroup("sample-tg");
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");
        target.setPort(9999);

        var health = service.describeTargetHealth(REGION, tgArn, List.of(target)).getFirst();

        assertEquals("unused", health.getState());
        assertEquals("Target.NotRegistered", health.getReason());
        assertEquals("Target is not registered to the target group", health.getDescription());
    }

    private String createTargetGroup(String name) {
        return service.createTargetGroup(
                REGION, name, "HTTP", "HTTP1", 9999, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/v1/ready", 30, 5, 5, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
    }

    private String createTcpTargetGroup(String name) {
        return service.createTargetGroup(
                REGION, name, "TCP", null, 80, "vpc-a", "ip",
                "TCP", "traffic-port", true, "/", 30, 10, 3, 3, "200",
                "ipv4", Map.of()).getTargetGroupArn();
    }

    private static Action forwardAction(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(targetGroupArn);
        return action;
    }

    private static Action forwardConfigOnly(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        Action.TargetGroupTuple tuple = new Action.TargetGroupTuple();
        tuple.setTargetGroupArn(targetGroupArn);
        action.setTargetGroups(List.of(tuple));
        return action;
    }

    private static RuleCondition pathPattern(String value) {
        RuleCondition condition = new RuleCondition();
        condition.setField("path-pattern");
        condition.setValues(List.of(value));
        condition.setPathPatternValues(List.of(value));
        return condition;
    }

    private static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker) {
        Ec2Service ec2Service = mock(Ec2Service.class);
        stubAlbSubnets(ec2Service);
        return serviceWithStorage(storageFactory, dataPlane, healthChecker, ec2Service);
    }

    private static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker,
                                                   Ec2Service ec2Service) {
        ElbV2Service service = new ElbV2Service();
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        service.storageFactory = storageFactory;
        service.s3Service = mock(S3Service.class);
        stubCaBundle(service.s3Service, "ca-bundles", "ca-bundle.pem", """
                -----BEGIN CERTIFICATE-----
                MIIB
                -----END CERTIFICATE-----
                """);
        service.initializeStorage();
        return service;
    }

    private static void stubCaBundle(S3Service s3Service, String bucket, String key, String pem) {
        S3Object object = new S3Object(bucket, key, pem.getBytes(), "application/x-pem-file");
        lenient().when(s3Service.getObject(eq(bucket), eq(key))).thenReturn(object);
        lenient().when(s3Service.getObject(eq(bucket), eq(key), any())).thenReturn(object);
    }

    private static void stubAlbSubnets(Ec2Service ec2Service) {
        Subnet subnetA = subnet("subnet-a", REGION + "a");
        Subnet subnetB = subnet("subnet-b", REGION + "b");
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-a")).thenReturn(subnetA);
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-b")).thenReturn(subnetB);
        lenient().when(ec2Service.findSubnetById(REGION, "subnet-a")).thenReturn(Optional.of(subnetA));
        lenient().when(ec2Service.findSubnetById(REGION, "subnet-b")).thenReturn(Optional.of(subnetB));
        lenient().when(ec2Service.describeSubnets(eq(REGION), eq(ALB_SUBNETS), eq(Map.of())))
                .thenReturn(List.of(subnetA, subnetB));
    }

    private static Subnet subnet(String subnetId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setVpcId("vpc-a");
        return subnet;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}

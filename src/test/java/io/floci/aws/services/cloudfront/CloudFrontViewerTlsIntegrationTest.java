package io.floci.aws.services.cloudfront;

import io.floci.aws.config.SelfSignedTlsProfile;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.Certificate;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.cloudfront.model.DefaultCacheBehavior;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.services.cloudfront.model.Origin;
import io.floci.aws.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A viewer opening {@code https://<alias>/} gets the distribution's ACM certificate and its content,
 * the way AWS serves a distribution's alternate domain names.
 */
@QuarkusTest
@TestProfile(SelfSignedTlsProfile.class)
class CloudFrontViewerTlsIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    CloudFrontViewerTls viewerTls;

    @Inject
    AcmService acmService;

    @Inject
    S3Service s3Service;

    @Test
    void anAliasIsServedOverHttpsWithTheDistributionsCertificate() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String alias = "shop-" + suffix + ".example.test";
        String wildcardHost = "docs-" + suffix + ".wild.example.test";
        String bucket = "cf-viewer-tls-" + suffix;
        s3Service.createBucket(bucket, REGION);
        s3Service.putObject(bucket, "index.html", ("VIEWER-TLS-" + suffix).getBytes(StandardCharsets.UTF_8),
                "text/html", Map.of());

        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                alias, List.of("*.wild.example.test"), KeyAlgorithm.EC_prime256v1);
        Certificate certificate = acmService.importCertificate(pair.certificatePem(), pair.privateKeyPem(),
                null, null, Map.of(), REGION);

        Distribution created = cloudFrontService.createDistribution(
                distribution(bucket, List.of(alias, "*.wild.example.test"), certificate.getArn()),
                Map.of());
        try {
            assertNotNull(cloudFrontService.viewerCertificateFor(alias));
            assertNotNull(cloudFrontService.viewerCertificateFor(wildcardHost));
            assertNull(cloudFrontService.viewerCertificateFor("other-" + suffix + ".example.test"));
            assertNull(cloudFrontService.viewerCertificateFor(created.getDomainName()));

            Response exact = get(alias);
            assertEquals(alias, commonName(exact.certificate()));
            assertTrue(exact.body().startsWith("HTTP/1.1 200"), exact.body());
            assertTrue(exact.body().contains("VIEWER-TLS-" + suffix), exact.body());

            Response wildcard = get(wildcardHost);
            assertEquals(alias, commonName(wildcard.certificate()));
            assertTrue(wildcard.body().contains("VIEWER-TLS-" + suffix), wildcard.body());
        } finally {
            cloudFrontService.removeDistribution(created.getId());
        }
        assertNull(cloudFrontService.viewerCertificateFor(alias));
    }

    private record Response(X509Certificate certificate, String body) {}

    private Response get(String host) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new TrustEverything()}, null);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", viewerTls.port())) {
            socket.setSoTimeout(10_000);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(host)));
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            X509Certificate served = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            in.transferTo(body);
            return new Response(served, body.toString(StandardCharsets.UTF_8));
        }
    }

    private static String commonName(X509Certificate certificate) {
        String subject = certificate.getSubjectX500Principal().getName();
        for (String part : subject.split(",")) {
            if (part.startsWith("CN=")) {
                return part.substring(3);
            }
        }
        return subject;
    }

    private static Distribution distribution(String bucket, List<String> aliases, String certificateArn) {
        Origin origin = new Origin();
        origin.setId("content");
        origin.setDomainName(bucket + ".s3." + REGION + ".amazonaws.com");
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of("OriginAccessIdentity", "")));

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("content");
        behavior.setViewerProtocolPolicy("redirect-to-https");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setDefaultRootObject("index.html");
        config.setAliases(aliases);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put("ACMCertificateArn", certificateArn);
        viewerCertificate.put("SSLSupportMethod", "sni-only");
        config.setViewerCertificate(viewerCertificate);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    /** The test checks which certificate is served, not who issued it. */
    private static final class TrustEverything implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

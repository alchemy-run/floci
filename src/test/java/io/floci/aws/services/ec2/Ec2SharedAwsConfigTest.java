package io.floci.aws.services.ec2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A guest command run with a cleared environment ({@code env -i}, as a systemd unit's setup step runs) sees none
 * of the AWS variables Floci launches the instance with, so the AWS CLI it runs goes to AWS. The same settings
 * are written to root's shared config and credentials files, which the CLI reads whatever its environment.
 */
class Ec2SharedAwsConfigTest {

    @TempDir
    Path home;

    @Test
    void theGuestsAwsEnvironmentIsWrittenToItsSharedConfig() throws Exception {
        run(Map.of(
                "AWS_REGION", "us-east-1",
                "AWS_ENDPOINT_URL", "http://localhost.floci.io:4566",
                "AWS_EC2_METADATA_SERVICE_ENDPOINT", "http://172.17.0.2:9169",
                "AWS_CA_BUNDLE", "/etc/floci-ca-bundle.pem",
                "AWS_ACCESS_KEY_ID", "test",
                "AWS_SECRET_ACCESS_KEY", "test",
                "AWS_SESSION_TOKEN", "test-session-token"));

        assertEquals("""
                [default]
                region = us-east-1
                endpoint_url = http://localhost.floci.io:4566
                ec2_metadata_service_endpoint = http://172.17.0.2:9169
                ca_bundle = /etc/floci-ca-bundle.pem
                """, Files.readString(home.resolve(".aws/config")));
        assertEquals("""
                [default]
                aws_access_key_id = test
                aws_secret_access_key = test
                aws_session_token = test-session-token
                """, Files.readString(home.resolve(".aws/credentials")));
    }

    @Test
    void anInstanceProfileGuestGetsNoStaticCredentials() throws Exception {
        run(Map.of("AWS_REGION", "us-east-1", "AWS_ENDPOINT_URL", "http://localhost.floci.io:4566"));

        assertEquals(false, Files.exists(home.resolve(".aws/credentials")));
    }

    @Test
    void aGuestsOwnConfigIsLeftAsItIs() throws Exception {
        Files.createDirectories(home.resolve(".aws"));
        Files.writeString(home.resolve(".aws/config"), "[default]\nregion = eu-west-1\n");

        run(Map.of("AWS_REGION", "us-east-1", "AWS_ENDPOINT_URL", "http://localhost.floci.io:4566"));

        assertEquals("[default]\nregion = eu-west-1\n", Files.readString(home.resolve(".aws/config")));
    }

    private void run(Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(Ec2ContainerManager.sharedAwsConfigCommand());
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("HOME", home.toString());
        builder.environment().putAll(environment);
        Process process = builder.redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }
}

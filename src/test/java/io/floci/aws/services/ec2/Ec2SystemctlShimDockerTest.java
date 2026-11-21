package io.floci.aws.services.ec2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A guest without systemd runs its units through Floci's systemctl shim, so a unit has to start the way
 * systemd would start it. Docker's own unit is the demanding one: two ExecStartPre lines, the first making the
 * directory the second writes to; several EnvironmentFiles, some optional; variables in ExecStart; containerd
 * wanted first; and {@code -H fd://}, which only systemd's socket activation can serve.
 */
class Ec2SystemctlShimDockerTest {

    private static final String IMAGE = "public.ecr.aws/amazonlinux/amazonlinux:2023";

    private final String container = "floci-shim-test-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void removeContainer() throws Exception {
        docker(List.of("rm", "-f", container), 60);
    }

    @Test
    void aUnitStartsAsSystemdWouldStartIt() throws Exception {
        assertEquals(0, docker(List.of("run", "-d", "--name", container, IMAGE, "tail", "-f", "/dev/null"), 300)
                .exitCode());
        List<String> install = new ArrayList<>(List.of("exec", container));
        install.addAll(List.of(Ec2ContainerManager.systemctlShimInstallCommand()));
        assertEquals(0, docker(install, 60).exitCode());

        String units = String.join("\n",
                "mkdir -p /etc/systemd/system",
                "cat > /etc/systemd/system/needed.service <<'EOF'",
                "[Service]",
                "ExecStart=/bin/sh -c 'echo needed > /tmp/needed; sleep 600'",
                "EOF",
                "cat > /etc/systemd/system/app.service <<'EOF'",
                "[Unit]",
                "Wants=network-online.target needed.service",
                "Requires=app.socket",
                "[Service]",
                "EnvironmentFile=-/etc/sysconfig/missing",
                "EnvironmentFile=/run/app/one.env",
                "EnvironmentFile=-/run/app/two.env",
                "ExecStartPre=/bin/mkdir -p /run/app",
                "ExecStartPre=/bin/sh -c 'echo GREETING=hello > /run/app/one.env; echo NAME=world > /run/app/two.env'",
                "ExecStartPre=-/bin/false",
                "ExecStart=/bin/sh -c 'echo \"$GREETING $NAME\" \"$0\" \"$1\" > /tmp/app;"
                        + " [ -e /var/run/app.pid ] && echo taken > /tmp/app; sleep 600' -H fd://",
                "EOF",
                "systemctl enable --now app.service");
        assertEquals(0, docker(List.of("exec", container, "sh", "-c", units), 60).exitCode());

        Result app = awaitFile("/tmp/app");
        assertEquals("hello world -H unix:///var/run/docker.sock", app.output().strip(), app.output());
        assertTrue(awaitFile("/tmp/needed").output().contains("needed"));
    }

    private Result awaitFile(String path) throws Exception {
        Result result = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            result = docker(List.of("exec", container, "cat", path), 10);
            if (result.exitCode() == 0) {
                return result;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(path + " never appeared: " + result.output());
    }

    private record Result(int exitCode, String output) {}

    private static Result docker(List<String> args, long timeoutSeconds) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Result(-1, output);
        }
        return new Result(process.exitValue(), output);
    }
}

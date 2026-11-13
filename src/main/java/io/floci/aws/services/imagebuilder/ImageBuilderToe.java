package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PushResponseItem;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ssm.SsmCommandService;
import io.floci.aws.services.ssm.model.CommandInvocation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * How components run: with AWSTOE, AWS's component executor, as Image Builder runs it. Floci downloads the AWSTOE
 * binary for the instance's architecture once ({@code floci.services.imagebuilder.awstoe-url}), copies it and the
 * component documents into the instance, and runs it with SSM Run Command ({@code AWS-RunShellScript}), rebooting
 * the instance and running it again when a component asks to reboot (exit code 194). A container image is built
 * with Docker from the recipe's Dockerfile template, its components run by AWSTOE in a {@code RUN} step, and
 * pushed to ECR.
 */
@ApplicationScoped
public class ImageBuilderToe {

    private static final Logger LOG = Logger.getLogger(ImageBuilderToe.class);
    static final String WORK = "/tmp/imagebuilder";
    private static final int REBOOT_EXIT = 194;

    private final ContainerLifecycleManager containers;
    private final Ec2Service ec2;
    private final SsmCommandService ssm;
    private final ImageBuilderStore store;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    /** What a run of commands produced: its status, exit code and output. */
    record Result(String status, int exitCode, String output) {
        boolean success() {
            return "Success".equals(status);
        }
    }

    @Inject
    public ImageBuilderToe(ContainerLifecycleManager containers, Ec2Service ec2, SsmCommandService ssm,
                           ImageBuilderStore store) {
        this.containers = containers;
        this.ec2 = ec2;
        this.ssm = ssm;
        this.store = store;
    }

    private DockerClient docker() {
        return containers.getDockerClient();
    }

    // ---------------------------------------------------------------- running commands

    /** Runs shell commands on an instance with SSM Run Command, waiting for them to end. */
    Result runShell(String instanceId, List<String> commands, int timeoutSeconds, String region) {
        return runDocument(instanceId, "AWS-RunShellScript", Map.of("commands", commands), timeoutSeconds, region);
    }

    Result runDocument(String instanceId, String document, Map<String, List<String>> parameters, int timeoutSeconds,
                       String region) {
        ObjectNode request = store.mapper().createObjectNode();
        request.putArray("InstanceIds").add(instanceId);
        request.put("DocumentName", document).put("TimeoutSeconds", Math.max(30, timeoutSeconds));
        ObjectNode values = request.putObject("Parameters");
        parameters.forEach((name, list) -> {
            ArrayNode array = values.putArray(name);
            list.forEach(array::add);
        });
        if ("AWS-RunShellScript".equals(document)) {
            values.putArray("executionTimeout").add(Integer.toString(Math.min(172800, Math.max(1, timeoutSeconds))));
        }
        String commandId = ssm.sendCommand(request, region).getCommandId();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds) + 5000;
        while (System.currentTimeMillis() < deadline) {
            CommandInvocation invocation = ssm.getCommandInvocation(commandId, instanceId, region);
            String status = invocation == null ? "Pending" : invocation.getStatus();
            if (!"Pending".equals(status) && !"InProgress".equals(status) && !"Delayed".equals(status)) {
                String output = (invocation.getStandardOutputContent() == null ? ""
                        : invocation.getStandardOutputContent()) + (invocation.getStandardErrorContent() == null
                        ? "" : invocation.getStandardErrorContent());
                return new Result(status, invocation.getResponseCode(), output);
            }
            sleep(500);
        }
        return new Result("TimedOut", -1, "");
    }

    /** Whether an instance runs and takes commands: SSM connectivity. */
    boolean ready(String instanceId) {
        Instance instance = ec2.findInstanceById(store.account(), instanceId);
        return instance != null && "running".equals(instance.getState().getName())
                && ec2.isInstanceContainerRunning(store.account(), instanceId);
    }

    /** Whether an instance is running without a container, as EC2's mock mode runs them. */
    boolean mockInstance(String instanceId) {
        Instance instance = ec2.findInstanceById(store.account(), instanceId);
        return instance != null && "running".equals(instance.getState().getName())
                && (instance.getDockerContainerId() == null || instance.getDockerContainerId().isBlank());
    }

    // ---------------------------------------------------------------- AWSTOE

    /** The architecture an instance runs, as AWSTOE names it: amd64 or arm64. */
    private String architecture(String instanceId, String region) {
        Result result = runShell(instanceId, List.of("uname -m"), 60, region);
        String machine = result.output().trim();
        return machine.contains("aarch64") || machine.contains("arm64") ? "arm64" : "amd64";
    }

    /** The AWSTOE binary for an architecture, downloaded once. */
    private synchronized Path binary(String architecture, String region) {
        Path cached = Path.of(System.getProperty("java.io.tmpdir"), "floci-awstoe", architecture, "awstoe");
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        String url = store.config().awstoeUrl().replace("{region}", region).replace("{arch}", architecture);
        try {
            Files.createDirectories(cached.getParent());
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Downloading AWSTOE from " + url + " answered "
                        + response.statusCode() + ".");
            }
            Path partial = cached.resolveSibling("awstoe.partial");
            try (InputStream body = response.body()) {
                Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } catch (IOException e) {
            throw new IllegalStateException("AWSTOE could not be downloaded from " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted downloading AWSTOE.");
        }
    }

    /** Copies files into an instance's container under a directory. */
    void copy(String instanceId, String directory, Map<String, byte[]> files, boolean executable) {
        Instance instance = ec2.findInstanceById(store.account(), instanceId);
        if (instance == null || instance.getDockerContainerId() == null) {
            throw new IllegalStateException("The instance " + instanceId + " is not running.");
        }
        docker().copyArchiveToContainerCmd(instance.getDockerContainerId()).withRemotePath("/")
                .withTarInputStream(new ByteArrayInputStream(tar(directory, files, executable))).exec();
    }

    static byte[] tar(String directory, Map<String, byte[]> files, boolean executable) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                String base = directory.startsWith("/") ? directory.substring(1) : directory;
                String name = base.isEmpty() ? file.getKey() : base + "/" + file.getKey();
                TarArchiveEntry entry = new TarArchiveEntry(name);
                entry.setSize(file.getValue().length);
                entry.setMode(executable ? 0755 : 0644);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not package files for the instance", e);
        }
        return archive.toByteArray();
    }

    /**
     * Runs components' phases on an instance with AWSTOE: its documents, in recipe order, with their parameters.
     * Returns AWSTOE's summary.
     */
    Result runComponents(String instanceId, List<byte[]> documents, List<String> parameters, String phases,
                         int timeoutSeconds, String region) {
        String architecture = architecture(instanceId, region);
        byte[] awstoe;
        try {
            awstoe = Files.readAllBytes(binary(architecture, region));
        } catch (IOException e) {
            throw new IllegalStateException("AWSTOE could not be read: " + e.getMessage(), e);
        }
        copy(instanceId, WORK, Map.of("awstoe", awstoe), true);
        Map<String, byte[]> files = new LinkedHashMap<>();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            String name = "component-" + i + ".yaml";
            files.put(name, documents.get(i));
            names.append(names.isEmpty() ? "" : ",").append(WORK).append('/').append(name);
        }
        copy(instanceId, WORK, files, false);
        StringBuilder command = new StringBuilder("mkdir -p /var/lib/amazon/toe && " + WORK
                + "/awstoe run --documents " + names + " --phases " + phases
                + " --log-directory /var/lib/amazon/toe");
        if (!parameters.isEmpty()) {
            command.append(" --parameters ").append(String.join(",", parameters));
        }
        Result result = null;
        for (int run = 0; run < 6; run++) {
            result = runShell(instanceId, List.of(command.toString()), timeoutSeconds, region);
            if (result.exitCode() != REBOOT_EXIT) {
                break;
            }
            LOG.infov("A component asked to reboot {0}; rebooting and resuming", instanceId);
            ec2.rebootInstances(region, List.of(instanceId));
            long deadline = System.currentTimeMillis() + 120_000;
            while (!ready(instanceId) && System.currentTimeMillis() < deadline) {
                sleep(1000);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- containers

    /**
     * Builds a container image from a recipe's Dockerfile template: {@code {{{ imagebuilder:parentImage }}}},
     * {@code {{{ imagebuilder:environments }}}} and {@code {{{ imagebuilder:components }}}} filled in, the components
     * run by AWSTOE inside the build. Returns the local image ID.
     */
    String buildContainer(String template, String parentImage, List<byte[]> documents, List<String> parameters,
                          String tag, String region) {
        String architecture = System.getProperty("os.arch").contains("aarch64")
                || System.getProperty("os.arch").contains("arm") ? "arm64" : "amd64";
        byte[] awstoe;
        try {
            awstoe = Files.readAllBytes(binary(architecture, region));
        } catch (IOException e) {
            throw new IllegalStateException("AWSTOE could not be read: " + e.getMessage(), e);
        }
        Map<String, byte[]> context = new LinkedHashMap<>();
        context.put("awstoe", awstoe);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            context.put("component-" + i + ".yaml", documents.get(i));
            names.append(names.isEmpty() ? "" : ",").append("/imagebuilder/component-").append(i).append(".yaml");
        }
        String components = "COPY awstoe component-*.yaml /imagebuilder/\n"
                + "RUN chmod +x /imagebuilder/awstoe && mkdir -p /var/lib/amazon/toe && /imagebuilder/awstoe run "
                + "--documents " + names + " --phases build,validate --log-directory /var/lib/amazon/toe"
                + (parameters.isEmpty() ? "" : " --parameters " + String.join(",", parameters))
                + " && rm -rf /imagebuilder";
        String dockerfile = template.replaceAll("\\{\\{\\{\\s*imagebuilder:parentImage\\s*}}}", parentImage)
                .replaceAll("\\{\\{\\{\\s*imagebuilder:environments\\s*}}}", "")
                .replaceAll("\\{\\{\\{\\s*imagebuilder:components\\s*}}}", Matcher.quoteReplacement(components));
        context.put("Dockerfile", dockerfile.getBytes(StandardCharsets.UTF_8));
        StringBuilder log = new StringBuilder();
        String imageId;
        try {
            // The context is sent as an archive built in memory: an on-disk context under macOS's temporary
            // directory carries provenance attributes Docker can't apply, and the daemon then drops the upload.
            imageId = docker().buildImageCmd(new ByteArrayInputStream(tar("", context, false))).withTags(Set.of(tag))
                    .exec(new BuildImageResultCallback() {
                        @Override
                        public void onNext(BuildResponseItem item) {
                            if (item.getStream() != null) {
                                log.append(item.getStream());
                            }
                            super.onNext(item);
                        }
                    }).awaitImageId(30, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The container image did not build: " + e.getMessage() + "\n" + log, e);
        }
        LOG.debugv("Built the container image {0}: {1}", tag, log);
        return imageId;
    }

    /** Runs a phase of components inside a container image: its test phase. */
    Result testContainer(String image, List<byte[]> documents, List<String> parameters, int timeoutSeconds,
                         String region) {
        String architecture = System.getProperty("os.arch").contains("aarch64")
                || System.getProperty("os.arch").contains("arm") ? "arm64" : "amd64";
        byte[] awstoe;
        try {
            awstoe = Files.readAllBytes(binary(architecture, region));
        } catch (IOException e) {
            throw new IllegalStateException("AWSTOE could not be read: " + e.getMessage(), e);
        }
        String containerId = docker().createContainerCmd(image).withEntrypoint("sleep").withCmd("3600").exec()
                .getId();
        try {
            docker().startContainerCmd(containerId).exec();
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("awstoe", awstoe);
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < documents.size(); i++) {
                files.put("component-" + i + ".yaml", documents.get(i));
                names.append(names.isEmpty() ? "" : ",").append(WORK).append("/component-").append(i).append(".yaml");
            }
            docker().copyArchiveToContainerCmd(containerId).withRemotePath("/")
                    .withTarInputStream(new ByteArrayInputStream(tar(WORK, files, true))).exec();
            ExecCreateCmdResponse exec = docker().execCreateCmd(containerId).withAttachStdout(true)
                    .withAttachStderr(true).withCmd("sh", "-c", "mkdir -p /var/lib/amazon/toe && " + WORK
                            + "/awstoe run --documents " + names + " --phases test --log-directory /var/lib/amazon/toe"
                            + (parameters.isEmpty() ? "" : " --parameters " + String.join(",", parameters)))
                    .exec();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            docker().execStartCmd(exec.getId()).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    output.writeBytes(frame.getPayload());
                }
            }).awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
            Long code = docker().inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            int exit = code == null ? -1 : code.intValue();
            return new Result(exit == 0 ? "Success" : "Failed", exit, output.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("Failed", -1, "Interrupted");
        } finally {
            docker().removeContainerCmd(containerId).withForce(true).exec();
        }
    }

    /** Tags a local image as a repository URI and pushes it with ECR's credentials. */
    void push(String image, String repositoryUri, String tag, String authorizationToken) {
        docker().tagImageCmd(image, repositoryUri, tag).exec();
        String decoded = new String(Base64.getDecoder().decode(authorizationToken), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        AuthConfig auth = new AuthConfig().withUsername(decoded.substring(0, colon))
                .withPassword(decoded.substring(colon + 1))
                .withRegistryAddress(repositoryUri.substring(0, repositoryUri.indexOf('/')));
        try {
            docker().pushImageCmd(repositoryUri).withTag(tag).withAuthConfig(auth)
                    .exec(new ResultCallback.Adapter<PushResponseItem>() {
                        @Override
                        public void onNext(PushResponseItem item) {
                            if (item.getErrorDetail() != null) {
                                throw new IllegalStateException(item.getErrorDetail().getMessage());
                            }
                        }
                    }).awaitCompletion(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted pushing " + repositoryUri + ":" + tag);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

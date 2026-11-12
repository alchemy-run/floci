package io.floci.aws.services.kinesisvideo;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The ffmpeg Kinesis Video Streams transcodes stored media with, to MP4 clips, HLS and DASH segments, and images: one
 * idle container (the image IVS's media server uses, which ships ffmpeg) started on first use. Each job copies its
 * inputs into a scratch directory, runs a shell script there, and copies its outputs back.
 */
@ApplicationScoped
public class KvsTranscoder implements ContainerTeardown {

    static final String CONTAINER = "floci-kinesisvideo-media";
    private static final Logger LOG = Logger.getLogger(KvsTranscoder.class);

    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final EmulatorConfig config;
    private volatile String containerId;

    @Inject
    public KvsTranscoder(ContainerBuilder containers, ContainerLifecycleManager lifecycle, EmulatorConfig config) {
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.config = config;
    }

    /** A failed job, with ffmpeg's output. */
    static final class TranscodeException extends RuntimeException {
        TranscodeException(String message) {
            super(message);
        }
    }

    private synchronized String ensureStarted() {
        if (containerId != null && running(containerId)) {
            return containerId;
        }
        String image = config.services().kinesisVideo().ffmpegImage();
        LOG.infov("Starting the Kinesis Video transcoder {0} using image {1}", CONTAINER, image);
        lifecycle.removeIfExists(CONTAINER);
        ContainerSpec spec = containers.newContainer(image).withName(CONTAINER)
                .withEntrypoint(List.of("sleep", "infinity")).withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(KvsStore.SERVICE, "transcoder", "", ""))
                .build();
        String id = lifecycle.create(spec);
        lifecycle.startCreated(id, spec);
        containerId = id;
        return id;
    }

    private boolean running(String id) {
        try {
            InspectContainerResponse.ContainerState state = lifecycle.getDockerClient().inspectContainerCmd(id).exec()
                    .getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Runs a script over input files and returns the files it leaves in its directory (inputs excluded), by name.
     */
    Map<String, byte[]> run(Map<String, byte[]> inputs, String script) {
        String id = ensureStarted();
        String work = "/tmp/kvs-" + UUID.randomUUID();
        exec(id, List.of("mkdir", "-p", work));
        try {
            copyIn(id, work, inputs);
            String[] result = exec(id, List.of("sh", "-c", "cd " + work + " && " + script));
            if (!"0".equals(result[0])) {
                throw new TranscodeException(result[1].strip());
            }
            Map<String, byte[]> files = copyOut(id, work);
            inputs.keySet().forEach(files::remove);
            return files;
        } finally {
            exec(id, List.of("rm", "-rf", work));
        }
    }

    private String[] exec(String id, List<String> command) {
        ExecCreateCmdResponse created = lifecycle.getDockerClient().execCreateCmd(id)
                .withCmd(command.toArray(String[]::new)).withAttachStdout(true).withAttachStderr(true).exec();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            lifecycle.getDockerClient().execStartCmd(created.getId()).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    output.writeBytes(frame.getPayload());
                }
            }).awaitCompletion(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new String[] {"-1", output.toString(StandardCharsets.UTF_8)};
        }
        Long code = lifecycle.getDockerClient().inspectExecCmd(created.getId()).exec().getExitCodeLong();
        return new String[] {String.valueOf(code == null ? -1 : code), output.toString(StandardCharsets.UTF_8)};
    }

    private void copyIn(String id, String directory, Map<String, byte[]> files) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new TranscodeException("Could not package the media: " + e.getMessage());
        }
        lifecycle.getDockerClient().copyArchiveToContainerCmd(id).withRemotePath(directory)
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray())).exec();
    }

    private Map<String, byte[]> copyOut(String id, String directory) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream archive = lifecycle.getDockerClient().copyArchiveFromContainerCmd(id, directory).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            String root = directory.substring(directory.lastIndexOf('/') + 1) + "/";
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String name = entry.getName().startsWith(root) ? entry.getName().substring(root.length())
                            : entry.getName();
                    files.put(name, tar.readAllBytes());
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new TranscodeException("Could not read the transcoded media: " + e.getMessage());
        }
        return files;
    }

    @Override
    public synchronized void stopManagedContainers() {
        String id = containerId;
        containerId = null;
        if (id != null) {
            lifecycle.stopAndRemove(id, null);
        } else {
            lifecycle.removeIfExists(CONTAINER);
        }
    }
}

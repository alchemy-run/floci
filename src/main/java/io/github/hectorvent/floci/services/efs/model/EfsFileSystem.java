package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Storage model for an EFS file system. */
@RegisterForReflection
public class EfsFileSystem extends FileSystem {

    public boolean isEncrypted() {
        return Boolean.TRUE.equals(getEncrypted());
    }
}

package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.Response.Status;

import java.util.HashMap;
import java.util.Map;

public class EfsException extends AwsException {

    public EfsException(Status status, String errorCode, String message) {
        this(status, errorCode, message, Map.of());
    }

    public EfsException(Status status, String errorCode, String message, Map<String, Object> extendedData) {
        super(errorCode, message, status.getStatusCode(), errorData(errorCode, extendedData));
    }

    private static Map<String, Object> errorData(String errorCode, Map<String, Object> extendedData) {
        Map<String, Object> data = new HashMap<>(extendedData);
        data.put("ErrorCode", errorCode);
        return data;
    }

    public static EfsException fileSystemAlreadyExists(String token, String fileSystemId) {
        return new EfsException(Status.CONFLICT, "FileSystemAlreadyExists", "File system with creation token " + token + " already exists.", Map.of("FileSystemId", fileSystemId));
    }

    public static EfsException fileSystemNotFound(String fileSystemId) {
        return new EfsException(Status.NOT_FOUND, "FileSystemNotFound", "File system " + fileSystemId + " does not exist.");
    }

    public static EfsException fileSystemInUse(String fileSystemId) {
        return new EfsException(Status.CONFLICT, "FileSystemInUse", "File system " + fileSystemId + " is in use.");
    }

    public static EfsException mountTargetNotFound(String mountTargetId) {
        return new EfsException(Status.NOT_FOUND, "MountTargetNotFound", "Mount target " + mountTargetId + " does not exist.");
    }
    
    public static EfsException mountTargetConflict(String message) {
        return new EfsException(Status.CONFLICT, "MountTargetConflict", message);
    }

    public static EfsException accessPointAlreadyExists(String token, String accessPointId) {
        return new EfsException(Status.CONFLICT, "AccessPointAlreadyExists", "Access point with client token " + token + " already exists.", Map.of("AccessPointId", accessPointId));
    }
    
    public static EfsException accessPointNotFound(String accessPointId) {
        return new EfsException(Status.NOT_FOUND, "AccessPointNotFound", "Access point " + accessPointId + " does not exist.");
    }
    
    public static EfsException policyNotFound(String fileSystemId) {
        return new EfsException(Status.NOT_FOUND, "PolicyNotFound", "Policy for file system " + fileSystemId + " does not exist.");
    }
    
    public static EfsException replicationNotFound(String fileSystemId) {
        return new EfsException(Status.NOT_FOUND, "ReplicationNotFound",
                "Replication configuration for file system " + fileSystemId + " does not exist.");
    }

    public static EfsException networkError(String errorCode, String message) {
        Status status = switch (errorCode) {
            case "IpAddressInUse", "NoFreeAddressesInSubnet" -> Status.CONFLICT;
            default -> Status.BAD_REQUEST;
        };
        return new EfsException(status, errorCode, message);
    }

    public static EfsException badRequest(String message) {
        return new EfsException(Status.BAD_REQUEST, "BadRequest", message);
    }
}

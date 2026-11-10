package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.container.RedshiftContainerManager;
import io.floci.aws.services.redshiftserverless.model.Namespace;
import io.floci.aws.services.redshiftserverless.model.RecoveryPoint;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.floci.aws.services.redshiftserverless.model.TableRestoreStatus;
import io.floci.aws.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Recovery points and table restores.
 * <p>
 * Like AWS, a namespace gets a recovery point every {@code recovery-point-interval-seconds} (30
 * minutes) while its workgroup runs, and each point is kept for a day. A point is a dump of the
 * namespace's database, so {@code RestoreFromRecoveryPoint} puts the data back as it was, and
 * {@code ConvertRecoveryPointToSnapshot} keeps it as a snapshot. A point outlives neither its
 * namespace nor its day.
 * <p>
 * A table restore copies one table of a snapshot or recovery point into the workgroup's live
 * database under a new name. It runs while the request waits, so the returned status is already
 * {@code SUCCEEDED} or {@code FAILED}; a missing source table or an existing target table fails the
 * restore, not the request, as with AWS.
 */
@ApplicationScoped
public class RedshiftServerlessRecoveryService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessRecoveryService.class);
    static final Duration RETENTION = Duration.ofHours(24);

    private final AccountAwareStorageBackend<RecoveryPoint> recoveryPoints;
    private final AccountAwareStorageBackend<TableRestoreStatus> tableRestores;
    private final RedshiftServerlessService service;
    private final Duration interval;

    @Inject
    public RedshiftServerlessRecoveryService(StorageFactory storageFactory, RedshiftServerlessService service,
                                             EmulatorConfig config) {
        this(storageFactory, service,
                Duration.ofSeconds(config.services().redshiftServerless().recoveryPointIntervalSeconds()));
    }

    RedshiftServerlessRecoveryService(StorageFactory storageFactory, RedshiftServerlessService service,
                                      Duration interval) {
        this.recoveryPoints = storageFactory.create("redshiftserverless", "redshiftserverless-recovery-points.json",
                new TypeReference<Map<String, RecoveryPoint>>() {});
        this.tableRestores = storageFactory.create("redshiftserverless", "redshiftserverless-table-restores.json",
                new TypeReference<Map<String, TableRestoreStatus>>() {});
        this.service = service;
        this.interval = interval;
    }

    // ── The sweep ──────────────────────────────────────────────────────────

    /**
     * Takes a recovery point of every running workgroup whose namespace has none from the last
     * interval, and drops the points that are a day old or whose namespace is gone. A workgroup that
     * cannot be dumped is skipped until the next sweep.
     */
    synchronized void sweep(Instant now) {
        for (AccountAwareStorageBackend.AccountEntry<Workgroup> entry : service.workgroupsInAllAccounts()) {
            String region = RedshiftServerlessService.regionOfKey(entry.key());
            Workgroup workgroup = entry.value();
            if (!RedshiftServerlessService.isLive(workgroup)) {
                continue;
            }
            Optional<Namespace> namespace = service.findNamespaceForAccount(entry.accountId(),
                    workgroup.getNamespaceName(), region);
            if (namespace.isEmpty() || hasRecentPoint(entry.accountId(), region, namespace.get(), now)) {
                continue;
            }
            try {
                takePoint(entry.accountId(), region, workgroup, namespace.get(), now);
            } catch (IOException | RuntimeException e) {
                LOG.warnv("Could not take a recovery point of namespace {0}; will retry: {1}",
                        namespace.get().getNamespaceName(), e.getMessage());
            }
        }
        for (AccountAwareStorageBackend.AccountEntry<RecoveryPoint> entry
                : recoveryPoints.scanAllAccountEntries(key -> true)) {
            String region = RedshiftServerlessService.regionOfKey(entry.key());
            if (isExpired(entry.value(), now) || !namespaceMatches(entry.accountId(), region, entry.value())) {
                deleteDump(entry.value(), entry.accountId(), region);
                recoveryPoints.deleteForAccount(entry.accountId(), entry.key());
            }
        }
    }

    private boolean hasRecentPoint(String accountId, String region, Namespace namespace, Instant now) {
        return recoveryPoints.scanForAccount(accountId, key -> key.startsWith(region + "::")).stream()
                .anyMatch(point -> Objects.equals(point.namespaceId, namespace.getNamespaceId())
                        && point.recoveryPointCreateTime.plus(interval).isAfter(now));
    }

    private void takePoint(String accountId, String region, Workgroup workgroup, Namespace namespace, Instant now)
            throws IOException {
        String id = UUID.randomUUID().toString();
        Path dir = service.recoveryPointDir(accountId, region);
        Files.createDirectories(dir);
        Path file = dir.resolve(id + ".sql");
        try {
            service.dumpWorkgroup(accountId, region, workgroup, namespace, file);
            RecoveryPoint point = new RecoveryPoint();
            point.recoveryPointId = id;
            point.namespaceName = namespace.getNamespaceName();
            point.namespaceId = namespace.getNamespaceId();
            point.namespaceArn = namespace.getNamespaceArn();
            point.workgroupName = workgroup.getWorkgroupName();
            point.recoveryPointCreateTime = now;
            point.totalSizeInMegaBytes = Files.size(file) / (1024.0 * 1024.0);
            point.sqlDump = file.toString();
            recoveryPoints.putForAccount(accountId, key(region, id), point);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    private static boolean isExpired(RecoveryPoint point, Instant now) {
        return !point.recoveryPointCreateTime.plus(RETENTION).isAfter(now);
    }

    private boolean namespaceMatches(String accountId, String region, RecoveryPoint point) {
        return service.findNamespaceForAccount(accountId, point.namespaceName, region)
                .map(namespace -> Objects.equals(namespace.getNamespaceId(), point.namespaceId)).orElse(false);
    }

    private void deleteDump(RecoveryPoint point, String accountId, String region) {
        if (point.sqlDump == null) {
            return;
        }
        Path file = Paths.get(point.sqlDump).toAbsolutePath().normalize();
        if (file.startsWith(service.recoveryPointDir(accountId, region).toAbsolutePath().normalize())) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warnv(e, "Could not delete the recovery point dump {0}", file);
            }
        }
    }

    // ── Recovery points ────────────────────────────────────────────────────

    public RecoveryPoint getRecoveryPoint(String recoveryPointId, String region) {
        String accountId = recoveryPoints.accountId();
        return recoveryPoints.get(key(region, recoveryPointId))
                .filter(point -> namespaceMatches(accountId, region, point))
                .filter(point -> !isExpired(point, Instant.now()))
                .orElseThrow(() -> ServerlessSupport.notFound("The recovery point " + recoveryPointId
                        + " was not found."));
    }

    /** The Region's recovery points, newest first, narrowed by namespace and a creation-time window. */
    public PaginatedResult<RecoveryPoint> listRecoveryPoints(String namespaceName, String namespaceArn,
                                                             Instant startTime, Instant endTime, String region,
                                                             Integer maxResults, String nextToken) {
        String accountId = recoveryPoints.accountId();
        Instant now = Instant.now();
        List<RecoveryPoint> all = recoveryPoints.scan(key -> key.startsWith(region + "::")).stream()
                .filter(point -> namespaceName == null || namespaceName.equals(point.namespaceName))
                .filter(point -> namespaceArn == null || namespaceArn.equals(point.namespaceArn))
                .filter(point -> startTime == null || !point.recoveryPointCreateTime.isBefore(startTime))
                .filter(point -> endTime == null || !point.recoveryPointCreateTime.isAfter(endTime))
                .filter(point -> !isExpired(point, now) && namespaceMatches(accountId, region, point))
                .sorted(Comparator.comparing((RecoveryPoint point) -> point.recoveryPointCreateTime).reversed())
                .toList();
        return Pagination.paginate(all, point -> point.recoveryPointId, maxResults, nextToken, 100, 100,
                "ValidationException");
    }

    public Namespace restoreFromRecoveryPoint(String recoveryPointId, String namespaceName, String workgroupName,
                                              String region) {
        RecoveryPoint point = getRecoveryPoint(recoveryPointId, region);
        if (!point.namespaceName.equals(namespaceName)) {
            throw ServerlessSupport.validation("The recovery point " + recoveryPointId
                    + " does not belong to the namespace " + namespaceName + ".");
        }
        return service.restoreDump(namespaceName, workgroupName, dumpOf(point, region), region);
    }

    public RedshiftServerlessSnapshot convertRecoveryPointToSnapshot(String recoveryPointId, String snapshotName,
                                                                     Integer retentionPeriod,
                                                                     Map<String, String> tags, String region) {
        RecoveryPoint point = getRecoveryPoint(recoveryPointId, region);
        return service.createSnapshotFromDump(snapshotName, point.namespaceName, retentionPeriod, tags,
                dumpOf(point, region), region);
    }

    private Path dumpOf(RecoveryPoint point, String region) {
        Path dir = service.recoveryPointDir(recoveryPoints.accountId(), region).toAbsolutePath().normalize();
        Path file = point.sqlDump == null ? null : Paths.get(point.sqlDump).toAbsolutePath().normalize();
        if (file == null || !file.startsWith(dir) || !Files.exists(file)) {
            throw new AwsException("InternalServerException", "The data of recovery point " + point.recoveryPointId
                    + " is no longer available.", 500);
        }
        return file;
    }

    // ── Table restores ─────────────────────────────────────────────────────

    /** What a table restore request names, before its defaults are applied. */
    public record TableRestoreRequest(String namespaceName, String workgroupName, String sourceDatabaseName,
                                      String sourceSchemaName, String sourceTableName, String targetDatabaseName,
                                      String targetSchemaName, String newTableName,
                                      Boolean activateCaseSensitiveIdentifier) {}

    public TableRestoreStatus restoreTableFromSnapshot(String snapshotName, TableRestoreRequest request,
                                                       String region) {
        RedshiftServerlessSnapshot snapshot = service.getSnapshot(snapshotName, region);
        if (!snapshot.getNamespaceName().equals(request.namespaceName())) {
            throw ServerlessSupport.validation("The snapshot " + snapshotName + " does not belong to the namespace "
                    + request.namespaceName() + ".");
        }
        Optional<Path> dump = service.snapshotDump(snapshot, region);
        TableRestoreStatus status = newStatus(request, region);
        status.snapshotName = snapshotName;
        return run(status, dump.orElse(null), region);
    }

    public TableRestoreStatus restoreTableFromRecoveryPoint(String recoveryPointId, TableRestoreRequest request,
                                                            String region) {
        RecoveryPoint point = getRecoveryPoint(recoveryPointId, region);
        if (!point.namespaceName.equals(request.namespaceName())) {
            throw ServerlessSupport.validation("The recovery point " + recoveryPointId
                    + " does not belong to the namespace " + request.namespaceName() + ".");
        }
        TableRestoreStatus status = newStatus(request, region);
        status.recoveryPointId = recoveryPointId;
        return run(status, dumpOf(point, region), region);
    }

    public TableRestoreStatus getTableRestoreStatus(String tableRestoreRequestId, String region) {
        return tableRestores.get(key(region, tableRestoreRequestId))
                .orElseThrow(() -> ServerlessSupport.notFound("The table restore request " + tableRestoreRequestId
                        + " was not found."));
    }

    /** The Region's table restores, newest first, narrowed by namespace and workgroup. */
    public PaginatedResult<TableRestoreStatus> listTableRestoreStatus(String namespaceName, String workgroupName,
                                                                      String region, Integer maxResults,
                                                                      String nextToken) {
        List<TableRestoreStatus> all = tableRestores.scan(key -> key.startsWith(region + "::")).stream()
                .filter(status -> namespaceName == null || namespaceName.equals(status.namespaceName))
                .filter(status -> workgroupName == null || workgroupName.equals(status.workgroupName))
                .sorted(Comparator.comparing((TableRestoreStatus status) -> status.requestTime).reversed())
                .toList();
        return Pagination.paginate(all, status -> status.tableRestoreRequestId, maxResults, nextToken, 100, 100,
                "ValidationException");
    }

    private TableRestoreStatus newStatus(TableRestoreRequest request, String region) {
        Workgroup workgroup = service.workgroupOfNamespace(request.namespaceName(), request.workgroupName(), region);
        Namespace namespace = service.getNamespace(request.namespaceName(), region);
        boolean caseSensitive = Boolean.TRUE.equals(request.activateCaseSensitiveIdentifier());
        TableRestoreStatus status = new TableRestoreStatus();
        status.tableRestoreRequestId = UUID.randomUUID().toString();
        status.status = "IN_PROGRESS";
        status.requestTime = Instant.now();
        status.namespaceName = namespace.getNamespaceName();
        status.workgroupName = workgroup.getWorkgroupName();
        status.sourceDatabaseName = request.sourceDatabaseName();
        status.sourceSchemaName = identifier(request.sourceSchemaName() == null ? "public"
                : request.sourceSchemaName(), caseSensitive);
        status.sourceTableName = identifier(request.sourceTableName(), caseSensitive);
        status.targetDatabaseName = request.targetDatabaseName() == null ? request.sourceDatabaseName()
                : request.targetDatabaseName();
        status.targetSchemaName = request.targetSchemaName() == null ? status.sourceSchemaName
                : identifier(request.targetSchemaName(), caseSensitive);
        status.newTableName = identifier(request.newTableName(), caseSensitive);
        if (!RedshiftServerlessService.databaseOf(namespace).equals(request.sourceDatabaseName())) {
            throw ServerlessSupport.validation("The database " + request.sourceDatabaseName()
                    + " is not in the namespace " + namespace.getNamespaceName() + ".");
        }
        return status;
    }

    /** Redshift folds unquoted names to lower case unless case-sensitive identifiers are on. */
    private static String identifier(String name, boolean caseSensitive) {
        if (name == null || name.isEmpty() || name.length() > 127 || name.indexOf('\0') >= 0) {
            throw ServerlessSupport.validation("Invalid identifier: " + name);
        }
        return caseSensitive ? name : name.toLowerCase(Locale.ROOT);
    }

    private TableRestoreStatus run(TableRestoreStatus status, Path dump, String region) {
        Workgroup workgroup = service.getWorkgroup(status.workgroupName, region);
        try {
            if (dump == null) {
                throw ServerlessSupport.validation("The snapshot holds no data to restore a table from.");
            }
            status.totalDataInMegaBytes = Files.size(dump) / (1024.0 * 1024.0);
            service.restoreTable(workgroup, dump, new RedshiftContainerManager.TableRestore(status.sourceSchemaName,
                    status.sourceTableName, status.targetDatabaseName, status.targetSchemaName, status.newTableName),
                    region);
            status.status = "SUCCEEDED";
            status.progressInMegaBytes = status.totalDataInMegaBytes;
        } catch (AwsException | IOException e) {
            status.status = "FAILED";
            status.message = e.getMessage();
        }
        tableRestores.put(key(region, status.tableRestoreRequestId), status);
        return status;
    }

    /** The dumps live in the tree {@link RedshiftServerlessService#clear()} deletes. */
    @Override
    public void clear() {
        recoveryPoints.clear();
        tableRestores.clear();
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }
}

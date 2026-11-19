# Fiesta World Tier Paper integration

Experimental Paper 1.21.11 patch for restoring cold region bundles before
Paper treats missing local data as empty terrain and for coordinating an
explicit, leased offload call. This project is GPL-3.0
because it changes Paper's GPL-licensed storage code.

The implementation consists of seven ordered Paper patches and six small Java
helpers:

- `paper-server/patches/features/0034-...patch` checks cold-region markers
  before Moonrise's negative cache.
- `paper-server/patches/features/0035-...patch` restores the whole bundle before
  returning a cached file handle or creating a region. It closes a stale cached
  handle first, so a partial eviction cannot leave terrain cached while entity,
  POI, or oversized-chunk files are missing.
- `paper-server/patches/features/0036-...patch` preserves marked restore
  failures through Moonrise's chunk-loading tasks. The scheduler receives the
  error instead of converting it into a blank chunk.
- `paper-server/patches/features/0037-...patch` coordinates per-region
  shared/exclusive leases with Moonrise chunk I/O, rejects regions with chunk
  holders or tickets, and closes cached terrain/entity/POI files before a
  tiering operation proceeds.
- `paper-server/patches/features/0038-...patch` exposes an experimental
  offload call on a dedicated worker. It holds Paper's exclusive in-process
  lease and a Linux process lock while the companion command uploads and evicts
  the bundle.
- `paper-server/patches/features/0039-...patch` refuses offload when region
  access tracking has failed for that dimension.
- `paper-server/patches/features/0040-...patch` calls an optional idle-only
  budget scheduler from active and empty-server ticks. It stays disabled unless
  both a local byte target and scheduler interval are explicitly configured.
- `paper-server/src/main/java/.../FiestaRegionRestorer.java` invokes the
  companion `fiesta-world-tier restore` command when needed.
- `paper-server/src/main/java/.../FiestaWorldTierRestoreException.java`
  identifies restore failures through Paper and Moonrise exception wrappers.
- `paper-server/src/main/java/.../FiestaRegionLeaseManager.java` provides the
  fair asynchronous lease queue and the cross-process lease used by the child.
- `paper-server/src/main/java/.../FiestaRegionOffloader.java` invokes and
  validates the companion offload command.
- `paper-server/src/main/java/.../FiestaRegionAccessTracker.java` records
  synced, region-level read/write timestamps and fails closed on unknown or
  invalid history.
- `paper-server/src/main/java/.../FiestaWorldTierScheduler.java` runs the
  planner off-thread, asks for one oldest cold bundle at a time, and starts
  Paper's leased offload only while the main server is empty.

## Build against Paper 1.21.11

Use a clean Paper 1.21.11 source checkout. Copy the feature patches into
`paper-server/patches/features/` after the existing feature patches, copy all
six Java helpers to their matching `paper-server/src/main/java/` package paths,
then run:

```sh
./gradlew applyPatches
./gradlew createMojmapBundlerJar
```

All seven patches are ordered and version-specific. Rebase and rebuild them
when Paper changes; do not apply them blindly to another Paper release.

## Runtime wiring

Install the companion project
[`open-fiestasmp/FiestaWorldTier`](https://github.com/open-fiestasmp/FiestaWorldTier)
on the Paper host. Configure Paper with these JVM properties (adjust paths as
needed):

```text
-Dfiesta.worldTier.command=/usr/local/bin/fiesta-world-tier
-Dfiesta.worldTier.config=/etc/fiesta-world-tier/config.json
-Dfiesta.worldTier.restoreTimeoutSeconds=300
-Dfiesta.worldTier.offloadTimeoutSeconds=3600
-Dfiesta.worldTier.accessWriteIntervalMillis=60000
```

The config must be a regular file with mode `0600`; the SSH identity and pinned
`known_hosts` file stay outside the world directory. See
[`docs/paper-storage-integration.md`](docs/paper-storage-integration.md) and
the companion repository's example configuration.

Markers live at
`<dimension-root>/.world-tier/remote/r.<region-x>.<region-z>.json`. The marker
records `REMOTE_ONLY` after a verified upload. Any remaining marker triggers
full-bundle recovery, even if a terrain file survived an interrupted eviction.
The validator also accepts `EVICTING` and `RESTORING`, so recovery can resume
after interrupted local deletion or restore. Before download, the command
durably changes the marker to `RESTORING`; Paper retries that state even if
partial local files exist. A complete, checksum-verified bundle is published
before the marker and remote object are removed. Restore failures propagate as
I/O errors and never become empty-region reads.

## Scope and verification

This integration provides demand restore and an experimental
`MoonriseRegionFileIO.offloadRegion(...)` API. It checks the region on Paper's
main thread, closes cached files, then holds both the in-process lease and a
cross-process file lock while the companion command completes upload and local
eviction on a worker. The Python command verifies that Paper still holds the
OS lock before it will offload. Java/Python lock interoperability was checked
on Linux.

The optional idle scheduler is implemented but disabled unless both
`fiesta.worldTier.schedulerTargetBytes` and
`fiesta.worldTier.schedulerIntervalSeconds` are set. For example, a 60 GB local
world-data target with one planning pass per hour can be configured with:

```text
-Dfiesta.worldTier.schedulerTargetBytes=60000000000
-Dfiesta.worldTier.schedulerIntervalSeconds=3600
-Dfiesta.worldTier.schedulerPlanTimeoutSeconds=120
```

Each pass asks the companion CLI for at most one oldest eligible bundle, then
rechecks that the main server is empty before invoking Paper's exclusive lease.
The lease still rejects loaded or ticketed regions, and the CLI still checks
access age, local reserve, and Luna quota. With the default 30-day cold age,
regions accessed during rollout will not be candidates until they have aged
past that threshold. The scheduler is not installed on Fiesta. Its tick
dispatch and invocation of the leased Paper API were exercised on a disposable
Paper server with a fake CLI shim. After the batched transport change, a
disposable Paper 1.21.11 server restored a synthetic spawn-region bundle from
Luna after `/forceload add` requested the marked chunk. The restored region
SHA-256 matched the original,
the durable marker cleared, and the remote test object was consumed; the unique
test root was then removed. This used an isolated world and did not touch Fiesta
world data. Crash recovery, outage recovery, concurrent player movement,
player-facing restore latency, and production-scale region transfers remain
unverified. Automatic eviction stays off on Fiesta until those gates pass.

The companion CLI can seed an initial catalog, list age-eligible regions, and
dry-run a local world-data budget plan. No production caller invokes the
scheduler on Fiesta.

The patches build as part of the isolated Paper 1.21.11 checkout. A restore
failure becomes a fatal chunk-system error and stops the server rather than
allowing the failed region to load as empty terrain. Run the
standalone Java lease and marker fixtures with:

```sh
./tests/run-marker-fixture.sh
```

The fixtures check that shared leases wait fairly around exclusive requests,
cancelled waiters do not block progress, the process lease excludes another
file lock, `REMOTE_ONLY`, `EVICTING`, and
`RESTORING` markers force recovery when terrain exists, invalid markers fail
closed, and the tier-failure type remains detectable through wrapped
exceptions. The complete isolated Paper 1.21.11 checkout applied all seven
patches and built `createMojmapBundlerJar`; the bundled server JAR contains the
restore, lease, offload, access-tracker, and scheduler classes. The standalone
Java fixtures pass, all 46 companion Python unit tests pass, and a separate
Linux fixture confirmed that Python observes the Java process lock with
`fcntl.lockf`.

A synthetic 1,048,607-byte region bundle also passed publish, manifest/hash
read, verified batched fetch, checksum comparison, and hash-guarded deletion on
Luna. The unique test root was removed and remote usage returned to zero. With
SSH multiplexing enabled, the measured phases were 26.6 seconds to publish,
3.1 seconds to read the manifest, 9.4 seconds to fetch the complete bundle,
and 9.0 seconds to delete it. Read plus fetch used two SFTP batches. One earlier
multiplexed store attempt timed out, though a subsequent complete run passed;
transport stalls still need retry and outage testing.

This has not been installed on Fiesta or tested with a live world. No production
world data was transferred. The earlier disposable Paper restore through Luna
predates the batched transport change; repeating that forced-load scenario is
still required before considering deployment.

## License

GPL-3.0. The patch modifies Paper storage code; retain Paper's license notices
when redistributing a patched server.

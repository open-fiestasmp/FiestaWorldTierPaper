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
Paper server with a fake CLI shim. Earlier asynchronous probes reported 23.2 s
and 15.4 s, but inspection of their saved Java harness showed they passed
`generate=true`; those timings are not valid no-generation restore measurements
and are not used as evidence here.

The corrected disposable end-to-end cycle exercised the actual scheduler,
leased offload API, Luna SFTP store, and asynchronous demand restore. Paper
selected and offloaded a synthetic 8 KiB region, then restored its remote-only
bundle when `World.getChunkAtAsync(..., generate=false)` requested a chunk in
that region. The restored file SHA-256 matched, the marker cleared, Luna test
usage returned to zero, and a tick monitor saw no gap above 500 ms. The
callback took 27.379 s and returned `null`: the synthetic region file contained
no actual chunk. This validates marker-driven file restoration, but not loading
a real chunk or the player experience. A real-chunk fixture was attempted but
Paper's access tracker marked the region during startup, preventing the cold
offload precondition from being met. The zero-byte free-space reserve was
test-only. All runs used isolated worlds and did not touch Fiesta data. Crash
and outage recovery, concurrent player movement, normal-region throughput, and
production-scale transfers remain unverified. Automatic eviction stays off on
Fiesta until those gates pass.

A follow-up changed small manifest reads to use stdout over the pinned SSH
connection instead of a separate SFTP transfer. A real Luna smoke test using a
different synthetic 8 KiB bundle measured 0.50 seconds for manifest read, 5.40
seconds for verified fetch, and 4.43 seconds for hash-guarded deletion. The
downloaded SHA-256 matched and the test root was removed. This measured only
the store operations.

A second pass changed hash-guarded remote deletion from SFTP to a single SSH
command while holding the remote writer lock. A new real Luna smoke test
measured 0.50 seconds for manifest read, 5.45 seconds for verified fetch, and
2.02 seconds for deletion; the SHA-256 matched and the isolated remote root was
removed. Those store stages total 7.97 seconds. The post-optimization Paper
callback took 27.379 seconds, returned no chunk object, and remains too slow;
the gap between store-stage and Paper callback timings is unexplained.

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
Java fixtures pass, all 53 companion Python unit tests pass, and a separate
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
world data was transferred. The `/forceload add` restore check covers the
batched transport path, while the later asynchronous API probe confirms one
demand restore without a measured server tick stall. Neither establishes live
player latency or production rollout readiness.

## License

GPL-3.0. The patch modifies Paper storage code; retain Paper's license notices
when redistributing a patched server.

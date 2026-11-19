# Paper restore and offload integration

The companion Paper 1.21.11 patches add a narrow restore seam to
`RegionFileStorage`. When a region bundle has a marker at
`<dimension-root>/.world-tier/remote/r.<x>.<z>.json`, Paper calls the
`fiesta-world-tier restore` command before returning a cached region handle,
returning no data, or creating a new region. It also checks the marker before
trusting Moonrise's negative cache. Any marker triggers recovery, because a
surviving terrain `.mca` does not prove that the entity, POI, or oversized chunk
files survived an interrupted eviction.
The child receives an argument vector (no shell), a minimal environment, a
bounded timeout, and a private configuration path. A failed restore is marked
with a dedicated I/O exception. Moonrise preserves that exception through the
terrain, entity, and POI load tasks; the chunk scheduler raises a fatal chunk
system error instead of loading an empty chunk. This intentionally favors
preserving region data over keeping the server online during a remote-store
failure.

Patch `0038` exposes the experimental
`MoonriseRegionFileIO.offloadRegion(world, regionX, regionZ)` API. Paper checks
the region on its main thread, closes cached terrain/entity/POI files, then
holds the exclusive region lease while a worker runs the companion `offload`
command. The command refuses to proceed unless it can verify Paper's live
cross-process file lock. This Java/Python lock handoff has been checked on
Linux.

Patch `0039` refuses offload when Paper's region access tracker has failed for
that dimension. This keeps an unreadable or unwritable access catalog from
being treated as proof that a region is cold.

Patch `0037` adds a fair per-region lease around Moonrise asynchronous chunk
reads and writes and Paper's synchronous region read, scan, and write paths.
`MoonriseRegionFileIO.acquireTieringLease(world, regionX, regionZ)` waits for
those operations, checks on the server thread that the region has no chunk
holders or tickets, and closes the cached terrain, entity, and POI region
files. A caller must hold the returned lease through the complete offload and
durable journal transition, and run network and filesystem transfer work off
the server thread. There is not yet an automatic eviction caller; the lease
seam alone does not make production offload ready.

Install the Python package on the Paper host so the command is available at
`/usr/local/bin/fiesta-world-tier`. Place its configuration at
`/etc/fiesta-world-tier/config.json` with mode `0600`; keep the SSH private key
and pinned `known_hosts` file outside the server world and repository. Paper's
command/config paths and timeout can be overridden with these JVM properties:

```text
-Dfiesta.worldTier.command=/usr/local/bin/fiesta-world-tier
-Dfiesta.worldTier.config=/etc/fiesta-world-tier/config.json
-Dfiesta.worldTier.restoreTimeoutSeconds=300
-Dfiesta.worldTier.offloadTimeoutSeconds=3600
-Dfiesta.worldTier.accessWriteIntervalMillis=60000
```

Paper records region-level shared-I/O use in an atomically replaced timestamp
under `<dimension-root>/.world-tier/access/`. It syncs a first touch and
throttles repeated writes to the configured interval. Missing or invalid
history is treated as hot. The companion `catalog` command seeds existing
local regions conservatively, and `candidates` provides a dry-run list using
the configured age and `protected_regions`. Candidate listing is not a local
budget controller; the offload command applies the same configured age check
again while the Paper lock is held.

The command validates the marker's dimension, coordinates, state, and manifest hash,
checks the configured world root, verifies the complete downloaded bundle,
checks a local free-space reserve, atomically publishes all listed files, then
removes the marker and remote object. A marker is written only after a remote
bundle has been fully uploaded and verified. Paper recognizes `REMOTE_ONLY`,
`EVICTING`, and `RESTORING` markers. Before publishing restored files, the
command durably changes the marker to `RESTORING`. Paper retries any recognized
state even if some local files exist, so an interrupted eviction or multi-file
restore cannot be mistaken for a complete bundle. Paper closes any cached
region handle before invoking recovery, then opens a fresh handle only after
full-bundle verification.

## Scope and current safety boundary

This hook covers demand restore and prevents missing remote data from being
treated as new terrain. The Paper patch includes an explicit leased offload
API and the Python `offload` command, which requires Paper's held OS file lock.
Paper tracking failures disable offload for that dimension.
Paper patch `0040` adds an opt-in scheduler that is disabled unless its target
and interval system properties are both set. It defaults to idle-only mode.
With `fiesta.worldTier.schedulerAllowOnline=true`, it can try up to eight cold
regions while other areas remain active; Paper's exclusive lease rejects a
region with holders or tickets. A player request for the exact region being
uploaded waits for the transfer to finish before demand restore can start. That
per-region wait and online movement behavior still need latency testing. Paper
patch `0039` refuses offload after an access-tracking failure. On a disposable
Paper server, the scheduler's
tick dispatch and invocation of the leased API were exercised using a fake CLI
shim. A corrected disposable
Paper 1.21.11 cycle then used the actual scheduler, Luna SFTP store, and
`World.getChunkAtAsync(..., generate=false)` with a synthetic 8 KiB region.
Paper offloaded the remote-only bundle and restored it on demand. The file
SHA-256 matched, the marker cleared, Luna test usage returned to zero, and a
tick monitor saw no gap above 500 ms. The callback took 27.379 seconds and
returned `null` because the synthetic region file had no actual chunk. This
validates marker-driven file restoration, not loading a real chunk or
player-facing behavior.

Earlier callback figures of 23.2 and 15.4 seconds came from a saved harness
that passed `generate=true`, despite earlier notes saying otherwise; they are
not valid no-generation restore measurements. A real-chunk fixture was
attempted, but Paper's access tracker marked its region during startup, so it
could not meet the cold-offload precondition. A valid real-chunk restore test
is still needed. The zero-byte local free-space reserve was used only in the
disposable test; all runs were isolated and did not touch Fiesta data. The
scheduler is not installed on Fiesta.

A Luna store smoke test changed small manifest reads to stdout over the pinned
SSH connection. It measured 0.50 seconds for manifest read, 5.40 seconds for
verified fetch, and 4.43 seconds for hash-guarded deletion. A second pass moved
hash-guarded deletion to one SSH command under the remote writer lock and
measured 0.50 seconds for manifest read, 5.45 seconds for verified fetch, and
2.02 seconds for deletion. The fetched SHA-256 matched and each isolated test
root was removed. These store stages total 7.97 seconds. The post-optimization
Paper callback took 27.379 seconds; the gap is unexplained and needs
investigation. Crash/outage recovery, concurrent player movement, real-chunk
restore latency, and production-scale transfers remain unverified. Do not
enable offloading on a live world until those gates pass on disposable copies.

The integration is intentionally fail-closed. If the marker, command,
configuration, SSH host key, remote bundle, checksum, quota, or local disk
reserve check fails, the operation returns a typed I/O error and leaves the
world files untouched. Paper/Moonrise fails the chunk task and stops the server
with a crash report; restore the remote service and retry server startup. Keep
a separate complete backup before any production rollout.

# Paper restore and offload integration

The companion Paper 1.21.11 patches add a narrow restore seam to
`RegionFileStorage`. When a region bundle has a marker at
`<dimension-root>/.world-tier/remote/r.<x>.<z>.json`, Paper calls the
`fiesta-world-tier restore` command before returning a cached region handle,
returning no data, or creating a new region. It also checks the marker before
trusting Moonrise's negative cache. Any marker triggers recovery, because a
surviving terrain `.mca` does not prove that the entity, POI, or oversized chunk
files survived an interrupted eviction.

Before enabling any offload, set
`-Dfiesta.worldTier.journalLookupEnabled=true` and install the matching
companion CLI. When a marker is absent, Paper consults the durable region
journal before treating a missing region as new. Journaled remote regions are
restored even if their marker was lost; journaled local regions with missing
terrain fail closed. A region absent from the journal remains eligible for
normal world generation. The lookup is cached for local and untracked regions,
then cleared when Paper creates or offloads the region. Moonrise's no-I/O
negative-cache check reads only this in-memory classification; an unknown region
stays possibly present until the regular I/O path checks its marker or journal.
Run `fiesta-world-tier catalog --config /etc/fiesta-world-tier/config.json`
before enabling this JVM property so the journal exists and local region
history is initialized. Keep the journal with the world-tier config and include
it in backups. Paper refuses to start an offload snapshot or finalize eviction
while journal lookup is disabled.
The child receives an argument vector (no shell), a minimal environment, a
bounded timeout, and a private configuration path. A failed restore is marked
with a dedicated I/O exception. Moonrise preserves that exception through the
terrain, entity, and POI load tasks; the chunk scheduler raises a fatal chunk
system error instead of loading an empty chunk. This intentionally favors
preserving region data over keeping the server online during a remote-store
failure.

Patch `0038` exposes the experimental
`MoonriseRegionFileIO.offloadRegion(world, regionX, regionZ, coldAgeMillis)`
API and fails closed if access tracking is unhealthy. It copies the complete
region bundle to a private staging directory while holding Paper's exclusive
region lease and a Linux process lock. Network upload and verification run
after the lease is released. Paper then reacquires the lease, checks that the
region is still cold and every source file still has the same identity, and
only then writes the durable marker and evicts the local bundle. Companion
commands verify Paper's process lock during snapshot and finalization. The
Java/Python lock handoff has been checked on Linux.

Patch `0037` adds a fair per-region lease around Moonrise asynchronous chunk
reads and writes and Paper's synchronous region read, scan, and write paths.
`MoonriseRegionFileIO.acquireTieringLease(world, regionX, regionZ)` waits for
those operations, checks on the server thread that the region has no chunk
holders or tickets, and closes the cached terrain, entity, and POI region
files. Network transfers run outside this lease. The short snapshot and final
eviction phases run on a worker, not the server thread. Staging needs extra
local disk space equal to the selected region bundle.

Install the Python package on the Paper host so the command is available at
`/usr/local/bin/fiesta-world-tier`. Place its configuration at
`/etc/fiesta-world-tier/config.json` with mode `0600`; keep the SSH private key
and pinned `known_hosts` file outside the server world and repository. Paper's
command/config paths and timeout can be overridden with these JVM properties:

```text
-Dfiesta.worldTier.command=/usr/local/bin/fiesta-world-tier
-Dfiesta.worldTier.config=/etc/fiesta-world-tier/config.json
-Dfiesta.worldTier.journalLookupEnabled=true
-Dfiesta.worldTier.restoreTimeoutSeconds=300
-Dfiesta.worldTier.offloadTimeoutSeconds=3600
-Dfiesta.worldTier.accessWriteIntervalMillis=60000
```

Paper records region-level shared-I/O use in an atomically replaced timestamp
under `<dimension-root>/.world-tier/access/`. It syncs a first touch and
throttles repeated writes to the configured interval. Missing or invalid
history is treated as hot. The companion `catalog` command seeds existing
local regions conservatively, and `candidates` provides a dry-run list using
the configured age and `protected_regions`. The `plan` command estimates
complete local bundles against the configured byte budget and advisory Luna
quota; it does not start a live-region offload. Before measuring quota, it
cleans abandoned stages older than seven days. It removes a matching remote
duplicate only if there is no remote-only marker and the full local bundle
remains. Each actual upload rechecks quota under the remote writer lock. The
staged offload checks cold age under Paper's lease before and after upload.
The Paper scheduler reads the validated `cold_age_days` from this plan output
and passes the corresponding millisecond age to the leased offload API, keeping
the final age check aligned with the CLI configuration.

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

When the marker is missing, the `region-state` command performs a read-only
journal lookup. The `recover-region` command uses that state to restore a remote
bundle, reject missing terrain recorded as local, or allow an untracked region
to follow normal generation. A missing or unreadable journal fails closed.

## Scope and current safety boundary

This hook covers demand restore and prevents missing remote data from being
treated as new terrain. The staged offload API uses Paper's OS lock for a short
snapshot and finalization, while the network upload runs without the region
lease. If a player accesses or changes a region during upload, final checks
cancel eviction and preserve its local files. The opt-in scheduler in patch
`0039` is disabled unless its target and interval system properties are set;
it defaults to idle-only mode. With
`fiesta.worldTier.schedulerAllowOnline=true`, it can try up to eight cold
regions while other areas remain active. Paper rejects loaded or ticketed
regions and access-tracking failures disable offload. On a disposable Paper
server, the scheduler's tick dispatch and invocation of the staged API were
exercised using a fake CLI shim. A corrected disposable
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
not valid no-generation restore measurements. A later saved-chunk test is
recorded below. The zero-byte local free-space reserve was used only in the
synthetic disposable test; all runs were isolated and did not touch Fiesta
data. The scheduler is not installed on Fiesta.

### Staged access and saved-chunk restore test

On 2026-11-19, a disposable patched Paper 1.21.11 server exercised the actual
staged offload hook and Python CLI using a local directory-object-store fixture.
The fixture contained saved chunks with known diamond and gold blocks. During
an 8-second delayed publish for the diamond region, the test requested the
existing chunk with generation disabled and verified its block. Paper's final
lease/access checks canceled eviction: the local region remained, no
remote-only marker was written, and no remote object was published. The maximum
observed tick gap during that upload was 71 ms. This demonstrates that this
Paper path preserved a region used during upload; it was an internal chunk
request, not a connected player session.

A separate saved gold-block region completed offload and demand restore. The
restore returned the existing chunk with its gold block intact, cleared the
marker, left the local region present, and removed the fixture's remote object.
This verifies saved-chunk serialization and restore against the local fixture.
It does not test Luna networking or player-facing latency. The earlier
27.379-second synthetic-region callback remains unexplained and is not
representative of this test. The scheduler is not installed on Fiesta.

### Luna endpoint integration test

On 2026-11-19, the same disposable Paper 1.21.11 harness ran against a
dedicated temporary object root on Luna, using the pinned SSH host key and a
short private `ControlPath`. During the staged upload of a 1.19 MiB saved
region, Paper loaded the chunk; remote usage showed the published object, then
final validation canceled eviction. The local region remained, no marker was
written, and abort cleanup returned remote usage to zero. Maximum observed
tick gap was 61 ms.

A second saved region (651 KiB) completed offload and demand restore through
Luna. The publish step took 17.065 seconds, finalization 0.534 seconds, and
the restore command 8.486 seconds. `getChunkAtAsync(..., generate=false)`
returned the saved gold-block chunk, the marker cleared, and remote usage
returned to zero. In an earlier run without SSH multiplexing, the same-size
region took 41.027 seconds to publish and 29.815 seconds to restore. Thus
multiplexing materially reduces connection overhead, but the remaining restore
time is still visible and needs improvement before a player-facing rollout.
The test used only synthetic disposable-world chunks; it did not connect a
player or touch Fiesta's live worlds. All temporary Luna roots were removed.

A repeat with SSH compression enabled took 16.604 seconds to publish and 8.632
seconds to restore, effectively unchanged from the multiplexed run without
compression (17.065 and 8.486 seconds). Compression remains configurable for
bandwidth-limited links, but these end-to-end measurements do not show a
reliable latency gain from it; the measured improvement came from connection
multiplexing.

A direct Luna SFTP benchmark downloaded the same 651 KiB synthetic region
twice with each buffer setting. The default 32 KiB buffer took 5.116 and 4.068
seconds; 256 KiB took 3.671 and 3.573 seconds. The client uses the tested
256 KiB buffer for download-only batches through
`sftp_download_buffer_size_bytes`. Uploads retain OpenSSH's 32 KiB default
because Luna rejected 256 KiB write packets. This small download benchmark
improved by about 21%; repeat the full Paper restore cycle before treating that
as an end-to-end result. OpenSSH documents the round-trip and memory tradeoff
for larger buffers ([manual](https://man.openbsd.org/sftp)).

With the larger download buffer and hash-guarded single-command cleanup, a
disposable 651 KiB storage restore took 0.50 seconds for the manifest, 6.00
seconds for fetch, and 0.57 seconds for cleanup (7.07 seconds total). The
checksum matched and Luna test usage returned to zero. This measured the
storage API, not a Paper callback or connected-player latency.

The test also exposed an OpenSSH Unix-domain socket path limit: a long
`ControlPath` failed before transfer. `OpenSSHConfig.validate()` now rejects a
template whose `%C`-expanded path exceeds 80 bytes, leaving room for OpenSSH's
temporary socket suffix. Keep the control socket directory private and the
path short.

If a process crashes during upload, local region files remain authoritative
and the staged snapshot is retained temporarily for recovery. Seven-day cleanup
removes an abandoned remote duplicate only when the local bundle is complete,
the manifest exactly matches the snapshot, and there is no remote-only marker.
If a durable marker exists, cleanup preserves the remote bundle.

A Luna store smoke test changed small manifest reads to stdout over the pinned
SSH connection. It measured 0.50 seconds for manifest read, 5.40 seconds for
verified fetch, and 4.43 seconds for hash-guarded deletion. A second pass moved
hash-guarded deletion to one SSH command under the remote writer lock and
measured 0.50 seconds for manifest read, 5.45 seconds for verified fetch, and
2.02 seconds for deletion. The fetched SHA-256 matched and each isolated test
root was removed. These store stages total 7.97 seconds. The post-optimization
synthetic-region Paper callback took 27.379 seconds; the gap is unexplained.
Later saved-chunk testing against Luna passed, with an 8.5-second restore.
Automatic offloading remains disabled on Fiesta until crash recovery and
player-facing movement/latency are tested on disposable copies.

The integration is intentionally fail-closed. If the marker, command,
configuration, SSH host key, remote bundle, checksum, quota, or local disk
reserve check fails, the operation returns a typed I/O error and leaves the
world files untouched. Paper/Moonrise fails the chunk task and stops the server
with a crash report; restore the remote service and retry server startup. Keep
a separate complete backup before any production rollout.

### Paper chunk-load restore with streamed Luna download

On 2026-11-19, a disposable Paper 1.21.11 server exercised the current
streamed SSH restore path through Paper's normal saved-chunk load. The test
seeded a gold block in chunk `(0, 0)`, published its 606 KiB region bundle to a
unique Luna test root, removed the local region file, and wrote a
`REMOTE_ONLY` marker. A second server process loaded the chunk through the
patched Paper hook; the test plugin observed the gold block and logged
`FIESTA_TIER_E2E_RESTORED`. The local region file was restored, the durable
marker was cleared, and Luna's test-root usage returned to zero before the
test root was removed. This confirms the new streamed fetch works at the Paper
hook boundary as well as through the standalone store API. The run used only a
synthetic test world; it did not touch Fiesta's production world or measure a
connected player's wait time.

### Luna outage and retry recovery

On 2026-11-19, a second disposable Paper server tested a remote outage against
a saved chunk in region `r.10.10`, away from spawn. With the chunk marked
`REMOTE_ONLY` and the local region file absent, the test pointed only its
private config at a closed localhost port. Paper surfaced
`FiestaWorldTierRestoreException`, stopped that chunk load, and did not create
an empty replacement region; the remote marker and bundle remained available.
After restoring the test config's Luna endpoint, the restore command fetched
the same region, cleared the marker, and returned remote test usage to zero.
A fresh Paper process loaded the saved gold block successfully. The unique
remote and local test roots were then removed. This validates fail-closed
outage behavior and a later retry on disposable data, but not a process crash
during transfer, live-player latency, or production rollout.

On 2026-11-24, a separate disposable test repeatedly returned exit code `1`
from the restore command. Paper kept the chunk pending, left the marker in
place, and continued loading an unrelated saved chunk. A per-region retry
cooldown limited the helper to three calls during the five-second injected
outage. After the fixture was allowed to restore the saved data, Paper loaded
the gold block and cleared the marker in 6.6 seconds. This checks a local
fixture, not Luna failure handling or player-facing latency.

A second 2026-11-24 run returned exit code `75` during the same five-second
injected failure window. The helper ran four times; Paper completed the chunk
future in 12.25 seconds and restored the saved block. The delay includes the
injected outage and is not a player-route latency measurement.

A 2026-11-24 disposable crash/restart test force-stopped Paper while a marked
region had no local terrain file and the restore fixture returned an error.
The marker and absent terrain file survived the process stop. After restart,
Paper retried the fixture, loaded an unrelated saved chunk, then restored the
saved gold-block chunk when the fixture recovered. This confirms marker-based
recovery across a process crash on local disposable data; it does not test a
crash during transfer or a production restart. The repeatable probe is
[`check-restore-crash-restart.sh`](../tests/e2e/check-restore-crash-restart.sh).
In its 2026-11-24 exit-code-`1` run, two restore calls occurred before the
forced stop and six total before recovery. An exit-code-`75` run made three
calls before the forced stop and seven total before recovery.

The marker fixture also forces a one-second restore timeout while the helper
has a child process writing to a liveness file. It checks the helper's child
stops, the remote marker remains, and no terrain file is published. This
verifies local process cleanup; it does not simulate a network outage.

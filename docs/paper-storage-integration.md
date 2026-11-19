# Paper restore integration

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
```

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
treated as new terrain. The new lease coordinates Paper's region-file I/O
paths and rejects a region with any holder or ticket. Automatic eviction still
requires a caller that holds this lease across the durable remote transfer,
local removal, and journal commit, plus disposable-server crash and concurrency
tests. Do not call the prototype's `TierManager.offload` against a live Fiesta
world. The patch has only been built against the isolated Paper 1.21.11
checkout; it has not been installed on Fiesta or exercised against a disposable
Paper world yet.

The integration is intentionally fail-closed. If the marker, command,
configuration, SSH host key, remote bundle, checksum, quota, or local disk
reserve check fails, the operation returns a typed I/O error and leaves the
world files untouched. Paper/Moonrise fails the chunk task and stops the server
with a crash report; restore the remote service and retry server startup. Keep
a separate complete backup before any production rollout.

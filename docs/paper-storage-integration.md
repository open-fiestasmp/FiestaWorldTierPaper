# Paper restore integration

The companion Paper 1.21.11 patch adds a narrow restore seam to
`RegionFileStorage`. When a terrain region is absent locally and has a marker
at `<dimension-root>/.world-tier/remote/r.<x>.<z>.json`, Paper calls the
`fiesta-world-tier restore` command before returning no data or creating a new
region. It also checks this marker before trusting Moonrise's negative cache.
The child receives an argument vector (no shell), a minimal environment, a
bounded timeout, and a private configuration path. A failed restore propagates
as an I/O error; it cannot silently become an empty world region.

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

The command validates the marker's dimension, coordinates, and manifest hash,
checks the configured world root, verifies the complete downloaded bundle,
checks a local free-space reserve, atomically publishes all listed files, then
removes the marker and remote object. A marker is written only after a remote
bundle has been fully uploaded and verified. Before publishing restored files,
the command durably changes the marker from `REMOTE_ONLY` to `RESTORING`. Paper
will retry a `RESTORING` marker even if some local files exist, so a failed or
interrupted multi-file publication cannot be mistaken for a complete restore.

## Scope and current safety boundary

This hook covers demand restore and prevents missing remote data from being
treated as new terrain. It does **not** implement automatic eviction. A file
lock serializes tier commands but cannot prove that Paper has unloaded every
chunk in a region or drained all chunk reads, writes, tickets, and save tasks.
Do not call the prototype's `TierManager.offload` against a live Paper world
until a Paper-side per-region lease enforces those conditions. The patch has
only been built against the isolated Paper 1.21.11 checkout; it has not been
installed on Fiesta or exercised against a disposable Paper world yet.

The integration is intentionally fail-closed. If the marker, command,
configuration, SSH host key, remote bundle, checksum, quota, or local disk
reserve check fails, the operation returns an I/O error and leaves the world
files untouched. Keep a separate complete backup before any production rollout.

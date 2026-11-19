# Fiesta World Tier Paper integration

Experimental Paper 1.21.11 patch for restoring cold region bundles before
Paper treats missing local data as empty terrain. This project is GPL-3.0
because it changes Paper's GPL-licensed storage code.

The implementation consists of three ordered Paper patches and two small Java
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
- `paper-server/src/main/java/.../FiestaRegionRestorer.java` invokes the
  companion `fiesta-world-tier restore` command when needed.
- `paper-server/src/main/java/.../FiestaWorldTierRestoreException.java`
  identifies restore failures through Paper and Moonrise exception wrappers.

## Build against Paper 1.21.11

Use a clean Paper 1.21.11 source checkout. Copy the feature patch into
`paper-server/patches/features/` after the existing feature patches, copy both
Java helpers to the matching `paper-server/src/main/java/` package path, then
run:

```sh
./gradlew applyPatches
./gradlew createMojmapBundlerJar
```

All three patches are ordered and version-specific. Rebase and rebuild them
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

This integration provides demand restore only. It does not implement automatic
eviction or prove that a region is unloaded and has no pending Paper reads,
writes, tickets, or saves. The prototype's offload manager must not be used on a
live Paper world until a Paper-side per-region unload/drain lease exists.

The patches build as part of the isolated Paper 1.21.11 checkout. A restore
failure becomes a fatal chunk-system error and stops the server rather than
allowing the failed region to load as empty terrain. Run the
standalone Java marker fixture with:

```sh
./tests/run-marker-fixture.sh
```

It checks that `REMOTE_ONLY`, `EVICTING`, and `RESTORING` markers force recovery
when terrain exists, invalid markers fail closed, and the tier-failure type
remains detectable through wrapped exceptions. A separate fixture also exercised
restore invocation and retry after a partial restore. The companion Python
package's 24 unit tests pass.
It has not been installed on Fiesta, tested against Luna, or exercised in a
disposable Paper server. No production world data was transferred.

## License

GPL-3.0. The patch modifies Paper storage code; retain Paper's license notices
when redistributing a patched server.

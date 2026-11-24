# Disposable Paper world-tier probe

`FiestaTierE2E` tests marked-region restore on a **disposable Paper server and
world**. It seeds or checks a saved gold block and verifies that Paper can keep
ticking while a restore attempt is temporarily unavailable.
Chunk timings cover Paper's async chunk future, not a player connection or
end-to-end network latency.

## Current result

An earlier synchronous outage test stopped the requested chunk load when the
restore endpoint was unreachable. The current patch returns marked-region
restore failures to Moonrise as retryable, which reschedules the region read
asynchronously with capped backoff.

On a fresh disposable server, a test restore command returned exit code `75`
until the plugin enabled it five seconds later. During that outage, a second
saved chunk containing a diamond block loaded successfully, showing that the
Moonrise I/O thread remained available. The result file contained
`OUTAGE_SERVER_ALIVE_AND_LOCAL_CHUNK_LOADED` and
`OUTAGE_RECOVERED:<milliseconds>ms` in about 6.5 seconds; after connectivity
returned, the requested chunk's gold block loaded and the remote marker was
removed. Paper rewrites region data when it saves the loaded chunk, so the
post-shutdown region file is not a raw
checksum comparison. The Python suite separately checks bundle checksums and
publication rules. This test uses an injected temporary failure; it does not
simulate Luna becoming unreachable or measure player-facing latency.

The 2026-11-24 repeat used the per-region cooldown and exit code `75` for the
five-second injected failure. The fixture ran four times; the chunk future
completed in 12.25 seconds, including the injected delay. That is a disposable
Paper restore-path timing, not a player's end-to-end cold-load measurement.

A separate probe verifies recovery from nonzero restore failures. Its fixture
command first exits with code `1`, keeping the region unavailable. Paper keeps
the chunk pending and applies a per-region cooldown to limit restore-command
retries. In the 2026-11-24 run, the fixture failed for five seconds and was
called three times; Paper stayed alive, loaded an unrelated saved chunk, and
kept the marker while the terrain file was absent. Once the fixture was allowed
to restore the saved region, the gold block loaded and the marker cleared in
6.6 seconds. The harness fails if more than six restore commands run during
that outage. Run it with [`check-restore-retry.sh`](check-restore-retry.sh).
This checks a local test fixture, not a Luna outage or player-facing latency.

The permanent-failure check leaves the fixture returning exit code `1` for a
22-second observation window. Two runs on 2026-11-24 had five and six failed
restore calls. Paper stayed running, loaded a separate saved local chunk, kept
the remote marker, and did not create the missing terrain region. The cold
chunk request remained pending until the disposable process was stopped. Run it with
[`check-permanent-restore-pending.sh`](check-permanent-restore-pending.sh),
using the same seeded marked-region setup and leaving the fixture's allow file
absent. This checks that a permanent restore failure does not stop Paper or
stall unrelated local chunk I/O; it does not measure player-facing behavior.

[`check-restore-crash-restart.sh`](check-restore-crash-restart.sh) repeats the
same marked-region setup, force-stops only its disposable Paper process while
the restore is pending, and starts that world again. It checks that the marker
and absent region survive the process crash, the new process retries the
restore, unrelated local chunks still load, and the saved gold block appears
after the fixture is allowed to recover. Its restore config, command, world,
logs, and marker paths must all stay under `/tmp`.
With exit code `1`, two helper calls occurred before the crash and six total
before recovery. With exit code `75`, three occurred before the crash and seven
total before recovery.

The standalone marker fixture also sets a one-second timeout for a restore
helper with a child process that writes a liveness file. It verifies that the
child stops, the marker remains, and no terrain region is published. This is a
local process-cleanup check, not a network-outage test.

The restore fixture defaults to exit code `1`. Set `failure_exit_code` to `75`
in its disposable JSON config to cover the command's temporary-failure status
as a separate case.

Earlier disposable cold restores of the same 638,976-byte region took 9.381 and
7.528 seconds. Those timings were single-region reads, not player-latency
measurements.

## Full component restore

`seed-rich` writes a gold terrain marker, a persistent armor-stand entity, a
lectern POI, and 16 barrels containing deterministic binary item data. The
barrel data forces Paper to create an external `.mcc` chunk file. A disposable
restore test then removes the local bundle, leaves a `REMOTE_ONLY` marker, and
uses a hash-checking fixture command to publish all four bundle members again.
`verify-rich` loads the chunk through Paper and checks the gold block, entity,
lectern, barrel payload, entity and POI region files, and the oversized sidecar.

On 2026-11-24, the fixture produced a 1,787,248-byte `.mcc` file containing the
chunk at `320,320`; its decompressed NBT was 1,891,278 bytes. The Paper restore
hook invoked the hash-checking local fixture command, which restored the terrain,
entity, POI, and `.mcc` files before Paper loaded them. The verification callback
completed in 889 ms. This used a local disposable fixture, not Luna, and is not
a player-facing latency measurement.

A second run used the real OpenSSH/SFTP store with a unique, application-capped
test namespace on Luna. The complete four-file object was 2,435,026 bytes.
Paper's restore callback completed in 8,417 ms; all four files loaded, the
marker cleared, and remote usage returned to zero. This is a connected Paper
callback measurement on an empty disposable server, not a player session or
player-perceived latency test. Keep that delay in mind: cold regions are not
instant, and automatic production eviction stays disabled until player-facing
latency is measured and accepted.

To repeat it, run the plugin once in `seed-rich` mode with the target chunk
coordinates. Confirm the matching `r.x.z.mca` files in `region`, `entities`,
and `poi`, plus a `.mcc` sidecar larger than 1 MiB. Copy those four files into a
disposable remote fixture and record their SHA-256 hashes. Remove only those
test-world files, write a `REMOTE_ONLY` marker, and run the plugin in
`verify-rich` mode with a restore command that verifies the hashes, republishes
all four files, and clears the marker. Keep the server, config, and fixture
under `/tmp`; never point this test at Fiesta's live world.

## Run it

Build the plugin against the Paper API matching the patched server, put its JAR
in the disposable server's `plugins` directory, and run with:

```text
-Dfiesta.test.mode=seed|verify|outage|seed-rich|verify-rich
-Dfiesta.test.result=/path/in/the/disposable/test/root/result.txt
-Dfiesta.test.chunkX=320
-Dfiesta.test.chunkZ=320
-Dfiesta.worldTier.config=/path/to/private/test-config.json
-Dfiesta.worldTier.command=/path/to/disposable/restore-command
```

For `outage`, also provide:

```text
-Dfiesta.test.restoreAvailable=/path/to/disposable/restore-ready
-Dfiesta.test.outageMillis=5000
```

To run the nonzero-failure probe, seed a target saved chunk and a separate local
probe chunk, save a copy of the target region under `/tmp`, then remove the
target region and create its `REMOTE_ONLY` marker. Copy
[`retry-restore-command.sh`](retry-restore-command.sh) under `/tmp` and set the
required environment variables listed by `check-restore-retry.sh`. The harness
keeps the fixture command returning the configured nonzero code (default `1`)
until the local probe chunk has loaded; it then permits the command to restore
the saved region. Set `failure_exit_code` to `75` to check the
temporary-failure status. The harness expects multiple nonzero attempts, the
marker and absent region to remain intact during failure, and the saved gold
block to load after recovery. Keep the server, config,
backup, scripts, and result files under `/tmp`. Never use Fiesta's live world
or mark a production region remote-only. Keep private config, keys, host-key
files, and test-world data out of source control.

For crash/restart recovery, use the same seeded target and local probe chunks
with a fresh attempt log and an absent allow file, then run
[`check-restore-crash-restart.sh`](check-restore-crash-restart.sh). It requires
two unused readiness paths and three unused log paths under `/tmp`. The test
intentionally kills the Paper process it starts; never point it at a live
server.

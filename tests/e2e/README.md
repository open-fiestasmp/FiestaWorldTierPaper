# Disposable Paper world-tier probe

`FiestaTierE2E` tests marked-region restore on a **disposable Paper server and
world**. It seeds or checks a saved gold block and verifies that Paper can keep
ticking while a restore attempt is temporarily unavailable.
Chunk timings cover Paper's async chunk future, not a player connection or
end-to-end network latency.

## Current result

Both retry layers cap their delay at five seconds. In the 2026-11-24
localhost player probe, a test client joined at spawn, teleported to a marked
region, and received that region's `map_chunk` packet 11.3 seconds after the
teleport command. The fixture cleared the marker and Paper exited cleanly.
This measured a local client and a file-copy fixture, not a player's internet
connection or a Luna outage. A previous run with a ten-second cap took 17
seconds to recover after teleport.

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
publication rules. That earlier run used a local injected failure and did not observe the client.

A follow-up disposable Paper 1.21.11 run held a marked region behind four
retryable fixture attempts. Paper stayed up and loaded a separate saved local
chunk during the wait. The test plugin sent a local client to the cold chunk;
the client received the target chunk packet for 320,320 12.473 seconds after
the client process started. Separately, Paper's cold-chunk request completed
13.405 seconds after that request began. The fixture used a controlled
five-second outage and a local file copy. The client timing includes its login
and retry wait; neither measurement represents Luna SFTP or a player's route to
Fiesta. The real Luna cold-load path still needs a connected-client
measurement.

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
absent. With the five-second retry cap, a repeat made seven restore calls in
the same window. Paper stayed running, the marker remained, and no local
terrain region appeared. This checks that a permanent restore failure does
not stop Paper or stall unrelated local chunk I/O; it does not measure
player-facing behavior.

On 2026-11-25, `check-markerless-recovery.sh` tested journal lookup when the
remote region had no marker and no local region file. Its local fixture reported
`REMOTE`, then held restore at exit code `75`. Paper stayed up, loaded a
separate saved local chunk, and made three failed restore attempts without
creating terrain or a marker. After the fixture allowed restore, Paper loaded
the saved gold block and shut down cleanly; the test reported 10 seconds to
recover. The script uses a local file copy and fake journal/restore command in
`/tmp`. It does not test Luna SFTP, a player connection, or production data.

The same test passed again on 2026-11-25 against a fresh bundler built from PR
head `86fde41`. Paper stayed up through three simulated restore failures, loaded
the separate saved chunk, and loaded the saved gold block after the fixture
allowed recovery. This repeated run still used a local fake journal and file
copy; it does not exercise Luna SFTP or a player connection.

A second disposable case forced a Paper process stop while the same markerless
restore was pending. Two restore attempts failed before the stop. After restart,
Paper retried the journal lookup, kept the terrain file and marker absent, and
loaded a separate saved local chunk. The fixture then allowed restore; Paper
loaded the saved gold block after five total attempts. This used the same local
fake journal and file copy, not Luna SFTP or production data.

On 2026-11-25, two disposable Paper 1.21.11 cases checked permanent failure and
recovery. After six failed restores, Paper remained up, loaded a saved local
chunk, kept the remote marker, and left the terrain region absent. In the
recovery case, the fixture returned exit code `75` until enabled. Paper retried
the pending chunk, restored its saved gold block after three attempts, and
cleared the marker. Both cases used loopback and temporary world data; they do
not measure player connections or production restore latency.

A separate disposable integration run used a private test config and a unique
Luna object root capped at 5 MB. After publishing a 622,798-byte saved region,
the test config pointed Paper at a closed local port. Paper retried the marked
region while the unrelated saved chunk loaded. Switching only that test
config back to Luna restored the saved gold block in 13.7 seconds; the marker
cleared and remote usage returned to zero. This tests retry after an unreachable
configured endpoint followed by a real Luna SFTP restore. It does not interrupt
an active Luna connection or measure a player's view of a cold chunk.

A disposable Paper restart probe then used a private test config with a unique
5 MB Luna root. The target region was 622,798 bytes. Paper first stayed on a
closed test endpoint while an unrelated saved chunk loaded. The test switched
to Luna through a TCP relay that reset the active SSH restore stream after
65,536 response bytes. The marker remained and the local terrain file stayed
absent. After that restore attempt unwound, the test stopped and restarted only
the disposable Paper process while the test endpoint was closed. The separate
local chunk loaded again; switching the test config back to Luna restored the
saved gold block and cleared the marker. Remote test usage returned to zero.
Paper's chunk future completed in 40.8 seconds across the outage and restart;
this does not measure a player's login or view of a cold chunk, and the relay
reset is not a Luna provider outage.

[`check-restore-crash-restart.sh`](check-restore-crash-restart.sh) repeats the
same marked-region setup, force-stops only its disposable Paper process while
the restore is pending, and starts that world again. It checks that the marker
and absent region survive the process crash, the new process retries the
restore, unrelated local chunks still load, and the saved gold block appears
after the fixture is allowed to recover. Its restore config, command, world,
logs, and marker paths must all stay under `/tmp`.
With exit code `1`, two helper calls occurred before the crash and six total
before recovery. With exit code `75`, three occurred before the crash and seven
total before recovery. A repeat with the five-second retry cap made three
calls before the forced stop and six total before recovery.

The standalone marker fixture also sets a one-second timeout for a restore
helper with a child process that writes a liveness file. It verifies that the
child stops, the marker remains, and no terrain region is published. This is a
local process-cleanup check, not a network-outage test.

The concurrency fixture starts twelve callers for one marked region. It
expects one restore command and confirms every caller sees the published local
region, including callers that reach the marker after the winning restore.

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

## Entity external sidecar

`seed-entity-rich` saves a lectern POI and a persistent armor stand with a
1,500,000-byte payload in its persistent data. Paper wrote
`entities/c.320.320.mcc` at 1,501,233 bytes. After a clean server shutdown, a
fresh Paper process ran `verify-entity-rich`, loaded the same entity chunk, and
checked the payload and lectern. The callback took 818 ms, and the sidecar hash
was unchanged across the restart.

The companion store then collected the terrain, entity and POI `.mca` files
plus the actual entity `.mcc`, published them to a local directory store, and
restored all four with matching hashes. This verifies Paper's entity sidecar
format and the bundle collector together. It does not send this Paper-generated
sidecar to Luna. The OpenSSH/SFTP tests cover sidecars in each component with
fixture files.

To repeat the full component restore, run the plugin in `seed-rich` mode with
the target chunk coordinates. Confirm the matching `r.x.z.mca` files in
`region`, `entities`, and `poi`, plus the terrain `.mcc` sidecar larger than
1 MiB. Copy those four files into a disposable remote fixture and record their
SHA-256 hashes. Remove only those test-world files, write a `REMOTE_ONLY`
marker, and run the plugin in `verify-rich` mode with a restore command that
verifies the hashes, republishes all four files, and clears the marker.

To repeat the entity sidecar check, run `seed-entity-rich`, let Paper shut down
cleanly, then start the same disposable server in `verify-entity-rich` mode.
Keep the server, config, and fixture under `/tmp`; never point either test at
Fiesta's live world.

### Paper restart during partial restore

On 2026-11-24, a separate disposable run used the same four-member rich region
bundle. A test-only restore command changed the marker to `RESTORING`, wrote
only the first 8 KiB of the terrain file locally, and then waited. Paper was
still running, the chunk request had not completed, and the entity, POI, and
oversized-sidecar files were absent. The test then killed the Paper process and
restore helper. The marker and partial terrain file remained.

After Paper restarted, the helper checked the manifest and all four file hashes,
staged and published the complete bundle, then cleared the marker. The
`verify-rich` callback completed in 876 ms and checked the gold block, entity,
lectern, barrel payload, region files, and oversized sidecar. This covers a
process stop after partial terrain publication; it does not cover a crash at
every individual file-publication step, Luna SFTP recovery, or player latency.
It used disposable files under `/tmp` and did not touch Fiesta's live world.

## Run it

Build the plugin against the Paper API matching the patched server, put its JAR
in the disposable server's `plugins` directory, and set:

```text
-Dfiesta.test.mode=seed|verify|outage|cold|seed-rich|verify-rich|seed-entity-rich|verify-entity-rich
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

To run the markerless journal recovery case, build the plugin against the same
Paper source and run:

```sh
SERVER_JAR=/absolute/path/to/paper-bundler.jar \
PLUGIN_JAR=/absolute/path/to/FiestaTierE2E.jar \
  tests/e2e/check-markerless-recovery.sh
```

The script creates and removes its own disposable server under `/tmp`. Set
`FIESTA_TEST_KEEP_ROOT=1` to keep the files after a run for inspection.

### Client chunk delivery

Build the plugin with `tests/e2e/build.sh` and `PAPER_TEST_CLASSPATH` from the
matching disposable Paper server. In outage mode, a joining client is sent to
the target chunk. The `cold` mode waits for a client before requesting the
chunk; the teleport starts the cold load and the plugin records when the saved
block is available. Keep the server alive for the result with
`-Dfiesta.test.keepAliveSeconds=10`.

Install the client dependency with `npm ci` in `tests/e2e/client`, then run:

```sh
FIESTA_TEST_USERNAME=TierProbeFresh node tests/e2e/client/probe.js /tmp/test/client-ready /tmp/test/client-results.log
```

Use a fresh `FIESTA_TEST_USERNAME` each time. The probe connects to loopback
port 25671 by default; set `FIESTA_TEST_HOST` and `FIESTA_TEST_PORT` for another
disposable endpoint. It records the first chunk and target chunk packet times.

One disposable Oracle Paper run restored a marked bundle from Luna over SFTP.
Paper loaded the target chunk 4.579 seconds after the join-triggered teleport;
the loopback client received the packet about 5.047 seconds after login. The
gold-block check passed and the marker cleared. This is one local-host client
observation, not an external route, a concurrent test, or a production eviction
result. A later SFTP publish timed out after 180 seconds without creating a
remote object; a simple SFTP read worked afterward. Publishing reliability still
needs investigation before using this as a latency target.

A separate local-file outage test delivered the target chunk to a localhost
client 12.473 seconds after client start; Paper's chunk request completed
13.405 seconds after it began. The test branch also measured a localhost packet
arrival 11.3 seconds after teleport during retries. These use local fixtures,
not Luna SFTP or an external player route.

### Failure and restart checks

For repeated nonzero restore failures, seed a target saved chunk and a separate
local probe chunk, save a copy of the target region under `/tmp`, then remove
the target region and create its `REMOTE_ONLY` marker. Copy
[`retry-restore-command.sh`](retry-restore-command.sh) under `/tmp` and set the
environment variables listed by `check-restore-retry.sh`. The fixture returns
the configured failure code until the local probe chunk loads, then restores
the saved region. Test both the default exit code `1` and temporary-failure
code `75`. The harness expects Paper to stay running, keep the marker and
missing local region during failures, and load the saved block after recovery.

For crash/restart recovery, use seeded target and local probe chunks with fresh
readiness paths and logs under `/tmp`, then run
[`check-restore-crash-restart.sh`](check-restore-crash-restart.sh). It
intentionally kills only the disposable Paper process it starts; never point it
at a live server.

For a permanent failure, run
[`check-permanent-restore-pending.sh`](check-permanent-restore-pending.sh).
It keeps the fixture failing while Paper loads an unrelated chunk, then verifies
the marker and missing terrain remain while the affected request stays pending.
The fail-closed behavior must not return an empty region.

The tests use a disposable server, world, journal, remote root and private test
config. Keep keys, host-key files, backups, scripts, and results in that test
setup. Never point a test at Fiesta's live world or mark a production region
remote-only.

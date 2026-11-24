# Disposable Paper world-tier probe

`FiestaTierE2E` tests marked-region restore on a **disposable Paper server and
world**. It seeds or checks a saved gold block and verifies that Paper can keep
ticking while a restore attempt is temporarily unavailable.
Chunk timings cover Paper's async chunk future, not a player connection or
end-to-end network latency.

## Current result

An earlier synchronous outage test stopped Paper when the restore endpoint was
unreachable. The current patch returns retryable transport failures to Moonrise,
which reschedules the region read asynchronously with capped backoff.

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

A second disposable run used a restore command that always exited nonzero.
Paper stopped on its unrecoverable chunk-system failure; the remote marker
remained and no local terrain region was created. This verifies fail-closed
behavior, but permanent restore or configuration errors still stop the whole
Paper process. Repeat it with
[`check-permanent-restore-failure.sh`](check-permanent-restore-failure.sh).

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
-Dfiesta.test.mode=seed|verify|outage|cold|seed-rich|verify-rich
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

To observe client chunk delivery, build the plugin with tests/e2e/build.sh and
PAPER_TEST_CLASSPATH from the matching disposable Paper server. In outage mode,
a joining test client is teleported to the target chunk. The `cold` mode does
not start a chunk request until a test client joins. Its teleport triggers the
cold load, and the plugin logs time until the target chunk is loaded and checks
the restored block. The client probe records elapsed time through receipt of
the target chunk packet. Connect the test client after the server starts and
wait for that packet in its results. Use a fresh `FIESTA_TEST_USERNAME` on
each run so the client has no saved position near the cold region. Keep the
server alive after the teleport with
`-Dfiesta.test.keepAliveSeconds=10`. Install this plugin only on the disposable
server.

Install the client dependency with `npm ci` in `tests/e2e/client`, then run
`FIESTA_TEST_USERNAME=TierProbeFresh node tests/e2e/client/probe.js /tmp/test/client-ready /tmp/test/client-results.log`.
The probe reports the first chunk and target chunk packet with elapsed time. It
connects to loopback port 25671 by default; set `FIESTA_TEST_HOST` and
`FIESTA_TEST_PORT` to use another disposable endpoint.

One disposable Oracle Paper run restored a marked region bundle from Luna over
the real SFTP transport. With a fresh test username on a loopback client,
Paper reported the target chunk ready 4.579 seconds after the join-triggered
teleport. The client received its login packet 2.063 seconds after the probe
started and the target chunk packet at 7.110 seconds, about 5.047 seconds after
login. The restored gold-block check passed and the remote marker cleared.
This measures a single disposable cold restore and packet delivery from a
client on the Oracle host; it is not an external player route, a concurrent
load test, or a production eviction result. A later attempt to republish the
test bundle timed out after 180 seconds before any remote object was created;
the SFTP connection answered a simple read-only probe afterward. Repeated
publishing and restore reliability need more investigation before treating
these timings as a service target.

Configure the disposable restore command to exit `75` until the ready file
exists. Once it exists, the command must restore the saved region and remove the
test marker. For the permanent-failure check, mark a separate seeded region
remote-only, leave the local region file absent, and make the command exit
nonzero. Run the script with paths under `/tmp`; it expects Paper to stop, the
marker to remain, and the local region file to stay absent. Point the config,
journal, world and remote fixture at disposable locations. Never reuse Fiesta's
live world or mark a production region remote-only. Check the result file,
server logs, region marker and restored block. Keep private config, keys,
host-key files and test-world data out of source control.

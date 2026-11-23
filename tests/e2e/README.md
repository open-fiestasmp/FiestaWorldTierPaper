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
publication rules. This test uses an injected temporary failure; it does not
simulate Luna becoming unreachable or measure player-facing latency.

A second disposable run used a restore command that always exited nonzero.
Paper stopped on its unrecoverable chunk-system failure; the remote marker
remained and no local terrain region was created. This verifies fail-closed
behavior, but permanent restore or configuration errors still stop the whole
Paper process. Repeat it with
[`check-permanent-restore-failure.sh`](check-permanent-restore-failure.sh).

Earlier disposable cold restores of the same 638,976-byte region took 9.381 and
7.528 seconds. Those timings were single-region reads, not player-latency
measurements.

## Run it

Build the plugin against the Paper API matching the patched server, put its JAR
in the disposable server's `plugins` directory, and run with:

```text
-Dfiesta.test.mode=seed|verify|outage
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

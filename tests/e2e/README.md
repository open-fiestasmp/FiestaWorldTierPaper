# Disposable Paper world-tier probe

`FiestaTierE2E` is a tiny plugin for exercising a patched Paper server against
a **disposable test world**. It can seed a saved block, verify it after demand
restore (logging the asynchronous Paper chunk-load completion duration), or
request a marked region while the configured remote store is unreachable. The
probe uses Paper's async chunk API so the restore itself does not block the
server's main thread. Its timing measures the chunk-load future, not a complete
player connection or end-to-end network latency.

## Current result for the Fiesta Paper 1.21.11 patch

On a disposable world, two async cold restores of the same 638,976-byte region
completed in 9.381 seconds and 7.528 seconds. Both preserved the seeded gold
block and produced no watchdog warning. These are single-region storage timings,
not a player-facing latency guarantee.

The async outage probe is currently a **failed safety test**: with only its
test endpoint pointed at a closed localhost port, Paper's chunk I/O worker
surfaced `FiestaWorldTierRestoreException` into Moonrise's unrecoverable chunk
system path and shut down the disposable server before the plugin could write
`outage-result.txt`. The remote bundle remained recoverable, and a later async
retry restored the region. Do not run this outage probe on production, and do
not enable automatic region eviction until this async failure path is handled
without taking down the server. A typed restore error alone is not evidence
that async chunk requests fail safely.

Build it against the Paper API matching the patched server, put the resulting
JAR in the disposable server's `plugins` directory, and run with:

```text
-Dfiesta.test.mode=seed|verify|outage
-Dfiesta.test.result=/path/in/the/disposable/test/root/result.txt
-Dfiesta.worldTier.config=/path/to/private/test-config.json
-Dfiesta.test.chunkX=320
-Dfiesta.test.chunkZ=320
```

The test configuration must point `server_root`, journal, and the dedicated
remote object root at disposable locations. For the outage pass, make only the
test endpoint unreachable. Never reuse the live server root or mark a
production region remote-only for this test. Inspect the result file, Paper
logs, and crash reports; then reconnect the test endpoint and restore the
region before removing the test files. The private config, SSH key, host-key
file, and test-world data must stay out of source control.

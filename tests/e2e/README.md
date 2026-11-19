# Disposable Paper world-tier probe

`FiestaTierE2E` is a tiny plugin for exercising a patched Paper server against
a **disposable test world**. It can seed a saved block, verify it after demand
restore, or request a marked region while the configured remote store is
unreachable. The outage mode reports `FAILED_CLOSED` only when Paper surfaces
the typed world-tier restore failure; a returned empty chunk is reported as
`OUTAGE_RETURNED` and is a test failure.

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
production region remote-only for this test. Inspect the result file and Paper
logs, then reconnect the test endpoint and restore the region before removing
the test files. The private config, SSH key, host-key file, and test-world data
must stay out of source control.

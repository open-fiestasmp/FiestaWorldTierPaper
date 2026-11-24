#!/bin/sh
set -eu

: "${FIESTA_TEST_DISPOSABLE:?set FIESTA_TEST_DISPOSABLE=YES}"
: "${SERVER_DIR:?set SERVER_DIR to a disposable server under /tmp}"
: "${WORLD_DIR:?set WORLD_DIR to that server's world folder}"
: "${RESTORE_COMMAND:?set RESTORE_COMMAND to the disposable retry fixture}"
: "${RESTORE_CONFIG:?set RESTORE_CONFIG to a disposable config file}"
: "${RESTORE_BACKUP_REGION:?set RESTORE_BACKUP_REGION under /tmp}"
: "${RESTORE_READY_FILE:?set RESTORE_READY_FILE under /tmp}"
: "${RESTORE_ALLOW_FILE:?set RESTORE_ALLOW_FILE under /tmp}"
: "${RESTORE_ATTEMPT_LOG:?set RESTORE_ATTEMPT_LOG under /tmp}"
: "${CHUNK_X:?set CHUNK_X to the marked chunk's X coordinate}"
: "${CHUNK_Z:?set CHUNK_Z to the marked chunk's Z coordinate}"
: "${LOCAL_CHUNK_X:?set LOCAL_CHUNK_X to a saved local chunk's X coordinate}"
: "${LOCAL_CHUNK_Z:?set LOCAL_CHUNK_Z to a saved local chunk's Z coordinate}"
: "${RESULT_LOG:?set RESULT_LOG under /tmp}"

if [ "$FIESTA_TEST_DISPOSABLE" != YES ]; then
  echo "refusing to run outside an explicitly disposable test" >&2
  exit 2
fi

test ! -L "$SERVER_DIR"
SERVER_DIR=$(realpath -e -- "$SERVER_DIR")
WORLD_DIR=$(realpath -e -- "$WORLD_DIR")
RESTORE_COMMAND=$(realpath -e -- "$RESTORE_COMMAND")
RESTORE_CONFIG=$(realpath -e -- "$RESTORE_CONFIG")
RESTORE_BACKUP_REGION=$(realpath -e -- "$RESTORE_BACKUP_REGION")
RESTORE_READY_PARENT=$(realpath -e -- "$(dirname -- "$RESTORE_READY_FILE")")
RESTORE_ALLOW_PARENT=$(realpath -e -- "$(dirname -- "$RESTORE_ALLOW_FILE")")
RESTORE_ATTEMPT_PARENT=$(realpath -e -- "$(dirname -- "$RESTORE_ATTEMPT_LOG")")
RESULT_PARENT=$(realpath -e -- "$(dirname -- "$RESULT_LOG")")

case "$SERVER_DIR" in /tmp/*) ;; *) echo "SERVER_DIR must resolve under /tmp" >&2; exit 2 ;; esac
test "$WORLD_DIR" = "$SERVER_DIR/world" || { echo "WORLD_DIR must be the disposable server's world" >&2; exit 2; }
case "$RESTORE_COMMAND" in /tmp/*) ;; *) echo "RESTORE_COMMAND must resolve under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_CONFIG" in /tmp/*) ;; *) echo "RESTORE_CONFIG must resolve under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_BACKUP_REGION" in /tmp/*) ;; *) echo "RESTORE_BACKUP_REGION must resolve under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_READY_PARENT" in /tmp/*) ;; *) echo "RESTORE_READY_FILE must be under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_ALLOW_PARENT" in /tmp/*) ;; *) echo "RESTORE_ALLOW_FILE must be under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_ATTEMPT_PARENT" in /tmp/*) ;; *) echo "RESTORE_ATTEMPT_LOG must be under /tmp" >&2; exit 2 ;; esac
case "$RESULT_PARENT" in /tmp/*) ;; *) echo "RESULT_LOG must be under /tmp" >&2; exit 2 ;; esac

region_x=$((CHUNK_X >> 5))
region_z=$((CHUNK_Z >> 5))
local_region_x=$((LOCAL_CHUNK_X >> 5))
local_region_z=$((LOCAL_CHUNK_Z >> 5))
if [ "$region_x" -eq "$local_region_x" ] && [ "$region_z" -eq "$local_region_z" ]; then
  echo "marked and local probe chunks must be in different region files" >&2
  exit 2
fi

marker="$WORLD_DIR/.world-tier/remote/r.$region_x.$region_z.json"
region="$WORLD_DIR/region/r.$region_x.$region_z.mca"
test -f "$SERVER_DIR/server.jar"
test ! -L "$SERVER_DIR/server.jar"
test -f "$SERVER_DIR/plugins/FiestaTierE2E.jar"
test ! -L "$SERVER_DIR/plugins/FiestaTierE2E.jar"
test -x "$RESTORE_COMMAND"
test -f "$RESTORE_CONFIG"
test -f "$RESTORE_BACKUP_REGION"
grep -Fxq 'server-ip=127.0.0.1' "$SERVER_DIR/server.properties"
grep -Fxq 'server-port=0' "$SERVER_DIR/server.properties"
test -f "$marker"
test ! -e "$region"
test ! -e "$RESTORE_READY_FILE"
test ! -e "$RESTORE_ALLOW_FILE"
test ! -e "$RESTORE_ATTEMPT_LOG"
test ! -L "$RESULT_LOG"

server_pid=
cleanup() {
  if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT HUP INT TERM

(cd "$SERVER_DIR" && java \
    -Dfiesta.test.mode=outage \
    -Dfiesta.test.chunkX="$CHUNK_X" \
    -Dfiesta.test.chunkZ="$CHUNK_Z" \
    -Dfiesta.test.localChunkX="$LOCAL_CHUNK_X" \
    -Dfiesta.test.localChunkZ="$LOCAL_CHUNK_Z" \
    -Dfiesta.test.result="$RESULT_LOG" \
    -Dfiesta.test.restoreAvailable="$RESTORE_READY_FILE" \
    -Dfiesta.test.outageMillis=5000 \
    -Dfiesta.worldTier.command="$RESTORE_COMMAND" \
    -Dfiesta.worldTier.config="$RESTORE_CONFIG" \
    -jar server.jar nogui) > "$RESULT_LOG.server.log" 2>&1 &
server_pid=$!

start_time=$(date +%s)
while kill -0 "$server_pid" 2>/dev/null; do
  if [ -f "$RESTORE_READY_FILE" ]; then
    test -f "$marker" || { echo "marker cleared before restore was allowed" >&2; exit 1; }
    test ! -e "$region" || { echo "local terrain appeared before restore was allowed" >&2; exit 1; }
    : > "$RESTORE_ALLOW_FILE"
    break
  fi
  now=$(date +%s)
  if [ "$((now - start_time))" -ge 90 ]; then
    echo "disposable Paper probe did not reach its recovery gate" >&2
    exit 1
  fi
  sleep 0.1
done

set +e
wait "$server_pid"
server_status=$?
set -e
server_pid=

if [ "$server_status" -ne 0 ]; then
  echo "expected disposable Paper to recover and stop cleanly (exit $server_status)" >&2
  exit 1
fi
grep -Fq 'OUTAGE_SERVER_ALIVE_AND_LOCAL_CHUNK_LOADED' "$RESULT_LOG"
grep -Eq 'OUTAGE_RECOVERED:[0-9]+ms' "$RESULT_LOG"
grep -Fq 'retrying in' "$RESULT_LOG.server.log"
if grep -Fq 'unrecoverableChunkSystemFailure' "$RESULT_LOG.server.log"; then
  echo "Paper stopped on the tier restore failure" >&2
  exit 1
fi
attempt_count=$(wc -l < "$RESTORE_ATTEMPT_LOG")
if [ "$attempt_count" -lt 2 ]; then
  echo "expected multiple nonzero restore attempts before recovery" >&2
  exit 1
fi
if [ "$attempt_count" -gt 6 ]; then
  echo "restore helper was called too often during the injected outage" >&2
  exit 1
fi
test ! -e "$marker"
test -f "$region"
printf '%s\n' "RESTORE_RETRIED: nonzero restore errors kept Paper alive; saved chunk restored after $attempt_count attempts"

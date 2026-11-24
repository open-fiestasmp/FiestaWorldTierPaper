#!/bin/sh
set -eu

: "${FIESTA_TEST_DISPOSABLE:?set FIESTA_TEST_DISPOSABLE=YES}"
: "${SERVER_DIR:?set SERVER_DIR to a disposable server under /tmp}"
: "${WORLD_DIR:?set WORLD_DIR to its world folder}"
: "${RESTORE_COMMAND:?set RESTORE_COMMAND to the disposable restore fixture}"
: "${RESTORE_CONFIG:?set RESTORE_CONFIG to the disposable fixture config}"
: "${RESTORE_ATTEMPT_LOG:?set RESTORE_ATTEMPT_LOG under /tmp}"
: "${RESTORE_ALLOW_FILE:?set RESTORE_ALLOW_FILE under /tmp}"
: "${READY_FILE:?set READY_FILE under /tmp}"
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
ATTEMPT_PARENT=$(realpath -e -- "$(dirname -- "$RESTORE_ATTEMPT_LOG")")
ALLOW_PARENT=$(realpath -e -- "$(dirname -- "$RESTORE_ALLOW_FILE")")
READY_PARENT=$(realpath -e -- "$(dirname -- "$READY_FILE")")
RESULT_PARENT=$(realpath -e -- "$(dirname -- "$RESULT_LOG")")

case "$SERVER_DIR" in
  /tmp/*) ;;
  *) echo "SERVER_DIR must resolve under /tmp" >&2; exit 2 ;;
esac
test "$WORLD_DIR" = "$SERVER_DIR/world" || {
  echo "WORLD_DIR must be the disposable server's world" >&2
  exit 2
}
for path in "$RESTORE_COMMAND" "$RESTORE_CONFIG"; do
  case "$path" in
    /tmp/*) ;;
    *) echo "restore fixture files must resolve under /tmp" >&2; exit 2 ;;
  esac
done
for parent in "$ATTEMPT_PARENT" "$ALLOW_PARENT" "$READY_PARENT" "$RESULT_PARENT"; do
  case "$parent" in /tmp/*) ;; *) echo "test outputs must be under /tmp" >&2; exit 2 ;; esac
done

region_x=$((CHUNK_X >> 5))
region_z=$((CHUNK_Z >> 5))
local_region_x=$((LOCAL_CHUNK_X >> 5))
local_region_z=$((LOCAL_CHUNK_Z >> 5))
if [ "$region_x" -eq "$local_region_x" ] && [ "$region_z" -eq "$local_region_z" ]; then
  echo "marked and local probe chunks must use different region files" >&2
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
grep -Fxq 'server-ip=127.0.0.1' "$SERVER_DIR/server.properties"
grep -Fxq 'server-port=0' "$SERVER_DIR/server.properties"
for path in \
  "$RESTORE_ATTEMPT_LOG" "$RESTORE_ALLOW_FILE" "$READY_FILE" \
  "$RESULT_LOG" "$RESULT_LOG.server.log"
do
  test ! -L "$path"
  test ! -e "$path"
done
test ! -L "$marker"
test -f "$marker"
test ! -e "$region"

python3 - "$RESTORE_CONFIG" "$marker" "$region" "$RESTORE_ATTEMPT_LOG" "$RESTORE_ALLOW_FILE" <<'PY'
import json
import sys
from pathlib import Path

config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "marker": sys.argv[2],
    "target_region": sys.argv[3],
    "attempt_log": sys.argv[4],
    "allow_file": sys.argv[5],
}
for name, value in expected.items():
    path = Path(config[name]).resolve()
    if not path.is_relative_to("/tmp") or path != Path(value):
        raise SystemExit(f"{name} must match the disposable test path")
if int(config.get("failure_exit_code", 1)) == 0:
    raise SystemExit("fixture must keep returning a nonzero failure")
PY

server_pid=
cleanup() {
  if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
    kill -TERM "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT HUP INT TERM

(cd "$SERVER_DIR" && exec java \
    -Dfiesta.test.mode=outage \
    -Dfiesta.test.chunkX="$CHUNK_X" \
    -Dfiesta.test.chunkZ="$CHUNK_Z" \
    -Dfiesta.test.localChunkX="$LOCAL_CHUNK_X" \
    -Dfiesta.test.localChunkZ="$LOCAL_CHUNK_Z" \
    -Dfiesta.test.result="$RESULT_LOG" \
    -Dfiesta.test.restoreAvailable="$READY_FILE" \
    -Dfiesta.test.outageMillis=5000 \
    -Dfiesta.worldTier.command="$RESTORE_COMMAND" \
    -Dfiesta.worldTier.config="$RESTORE_CONFIG" \
    -jar server.jar nogui) > "$RESULT_LOG.server.log" 2>&1 &
server_pid=$!

start_time=$(date +%s)
while [ ! -f "$READY_FILE" ]; do
  process_state=$(ps -o stat= -p "$server_pid" 2>/dev/null | tr -d ' ' || true)
  case "$process_state" in ''|Z*)
    echo "Paper exited before the unrelated local chunk loaded" >&2
    exit 1
    ;;
  esac
  if [ "$(( $(date +%s) - start_time ))" -ge 90 ]; then
    echo "Paper did not complete the unrelated local chunk probe" >&2
    exit 1
  fi
  sleep 0.1
done

test -f "$marker"
test ! -e "$region"
test ! -e "$RESTORE_ALLOW_FILE"
grep -Fq 'OUTAGE_SERVER_ALIVE_AND_LOCAL_CHUNK_LOADED' "$RESULT_LOG"

probe_seconds=${PROBE_SECONDS:-22}
case "$probe_seconds" in ''|*[!0-9]*) echo "PROBE_SECONDS must be an integer" >&2; exit 2 ;; esac
if [ "$probe_seconds" -lt 15 ] || [ "$probe_seconds" -gt 45 ]; then
  echo "PROBE_SECONDS must be between 15 and 45" >&2
  exit 2
fi
sleep "$probe_seconds"

process_state=$(ps -o stat= -p "$server_pid" 2>/dev/null | tr -d ' ' || true)
case "$process_state" in
  ''|Z*) echo "Paper stopped during the permanent restore outage" >&2; exit 1 ;;
esac
test -f "$marker"
test ! -e "$region"
test ! -e "$RESTORE_ALLOW_FILE"
if grep -Fq 'unrecoverableChunkSystemFailure' "$RESULT_LOG.server.log"; then
  echo "Paper hit an unrecoverable chunk-system failure" >&2
  exit 1
fi
attempt_count=$(wc -l < "$RESTORE_ATTEMPT_LOG")
if [ "$attempt_count" -lt 3 ] || [ "$attempt_count" -gt 8 ]; then
  echo "expected 3 to 8 backoff-limited retries, found $attempt_count" >&2
  exit 1
fi
grep -Fq 'retrying in' "$RESULT_LOG.server.log"

kill -TERM "$server_pid"
wait "$server_pid" || true
server_pid=
if grep -Fq 'unrecoverableChunkSystemFailure' "$RESULT_LOG.server.log"; then
  echo "Paper hit an unrecoverable chunk-system failure during shutdown" >&2
  exit 1
fi
result="PERMANENT_RESTORE_PENDING: Paper stayed running and loaded a local chunk."
result="$result Failed restores: $attempt_count. The cold marker remained; no terrain region was created."
printf '%s\n' "$result"

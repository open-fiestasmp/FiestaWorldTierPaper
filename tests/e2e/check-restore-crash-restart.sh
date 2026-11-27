#!/bin/sh
set -eu

: "${FIESTA_TEST_DISPOSABLE:?set FIESTA_TEST_DISPOSABLE=YES}"
: "${SERVER_DIR:?set SERVER_DIR to a disposable server under /tmp}"
: "${WORLD_DIR:?set WORLD_DIR to that server's world folder}"
: "${RESTORE_COMMAND:?set RESTORE_COMMAND to the disposable retry fixture}"
: "${RESTORE_CONFIG:?set RESTORE_CONFIG to a disposable config file}"
: "${RESTORE_BACKUP_REGION:?set RESTORE_BACKUP_REGION under /tmp}"
: "${RESTORE_ALLOW_FILE:?set RESTORE_ALLOW_FILE under /tmp}"
: "${RESTORE_ATTEMPT_LOG:?set RESTORE_ATTEMPT_LOG under /tmp}"
: "${RESTART_READY_ONE:?set RESTART_READY_ONE under /tmp}"
: "${RESTART_READY_TWO:?set RESTART_READY_TWO under /tmp}"
: "${RESTART_RESULT_LOG:?set RESTART_RESULT_LOG under /tmp}"
: "${RESTART_SERVER_LOG_ONE:?set RESTART_SERVER_LOG_ONE under /tmp}"
: "${RESTART_SERVER_LOG_TWO:?set RESTART_SERVER_LOG_TWO under /tmp}"
: "${CHUNK_X:?set CHUNK_X to the marked chunk's X coordinate}"
: "${CHUNK_Z:?set CHUNK_Z to the marked chunk's Z coordinate}"
: "${LOCAL_CHUNK_X:?set LOCAL_CHUNK_X to a saved local chunk's X coordinate}"
: "${LOCAL_CHUNK_Z:?set LOCAL_CHUNK_Z to a saved local chunk's Z coordinate}"

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
tmp_file_path() {
  parent=$(realpath -e -- "$(dirname -- "$1")")
  case "$parent" in /tmp|/tmp/*) ;; *) echo "test files must resolve under /tmp" >&2; exit 2 ;; esac
  printf '%s/%s\n' "$parent" "$(basename -- "$1")"
}
RESTORE_ALLOW_FILE=$(tmp_file_path "$RESTORE_ALLOW_FILE")
RESTORE_ATTEMPT_LOG=$(tmp_file_path "$RESTORE_ATTEMPT_LOG")
RESTART_READY_ONE=$(tmp_file_path "$RESTART_READY_ONE")
RESTART_READY_TWO=$(tmp_file_path "$RESTART_READY_TWO")
RESTART_RESULT_LOG=$(tmp_file_path "$RESTART_RESULT_LOG")
RESTART_SERVER_LOG_ONE=$(tmp_file_path "$RESTART_SERVER_LOG_ONE")
RESTART_SERVER_LOG_TWO=$(tmp_file_path "$RESTART_SERVER_LOG_TWO")
case "$SERVER_DIR" in /tmp/*) ;; *) echo "SERVER_DIR must resolve under /tmp" >&2; exit 2 ;; esac
test "$WORLD_DIR" = "$SERVER_DIR/world" || { echo "WORLD_DIR must be the disposable server's world" >&2; exit 2; }
case "$RESTORE_COMMAND" in /tmp/*) ;; *) echo "RESTORE_COMMAND must resolve under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_CONFIG" in /tmp/*) ;; *) echo "RESTORE_CONFIG must resolve under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_BACKUP_REGION" in /tmp/*) ;; *) echo "RESTORE_BACKUP_REGION must resolve under /tmp" >&2; exit 2 ;; esac

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
for path in "$RESTORE_ALLOW_FILE" "$RESTORE_ATTEMPT_LOG" "$RESTART_READY_ONE" "$RESTART_READY_TWO" \
  "$RESTART_RESULT_LOG" "$RESTART_SERVER_LOG_ONE" "$RESTART_SERVER_LOG_TWO"; do
  test ! -L "$path"
  test ! -e "$path"
done

python3 - "$RESTORE_CONFIG" "$RESTORE_ATTEMPT_LOG" "$RESTORE_ALLOW_FILE" \
  "$RESTORE_BACKUP_REGION" "$region" "$marker" <<'PY'
import json
import sys
from pathlib import Path

config_path, attempt_log, allow_file, backup, target, marker = sys.argv[1:]
config = json.loads(Path(config_path).read_text(encoding="utf-8"))
expected = {
    "attempt_log": attempt_log,
    "allow_file": allow_file,
    "backup_region": backup,
    "target_region": target,
    "marker": marker,
}
for key, path in expected.items():
    if Path(config[key]).resolve() != Path(path).resolve():
        raise SystemExit(f"restore fixture config points outside this disposable test: {key}")
PY

server_pid=
cleanup() {
  if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
    kill -TERM "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT HUP INT TERM

start_server() {
  ready_file="$1"
  server_log="$2"
  (cd "$SERVER_DIR" && exec java \
      -Dfiesta.test.mode=outage \
      -Dfiesta.test.chunkX="$CHUNK_X" \
      -Dfiesta.test.chunkZ="$CHUNK_Z" \
      -Dfiesta.test.localChunkX="$LOCAL_CHUNK_X" \
      -Dfiesta.test.localChunkZ="$LOCAL_CHUNK_Z" \
      -Dfiesta.test.result="$RESTART_RESULT_LOG" \
      -Dfiesta.test.restoreAvailable="$ready_file" \
      -Dfiesta.test.outageMillis=5000 \
      -Dfiesta.worldTier.command="$RESTORE_COMMAND" \
      -Dfiesta.worldTier.config="$RESTORE_CONFIG" \
      -jar server.jar nogui) > "$server_log" 2>&1 &
  server_pid=$!
}

wait_ready() {
  ready_file="$1"
  server_log="$2"
  start_time=$(date +%s)
  while kill -0 "$server_pid" 2>/dev/null; do
    if [ -f "$ready_file" ]; then
      test -f "$marker" || { echo "marker cleared during outage" >&2; exit 1; }
      test ! -e "$region" || { echo "terrain appeared before restore was allowed" >&2; exit 1; }
      return 0
    fi
    now=$(date +%s)
    if [ "$((now - start_time))" -ge 90 ]; then
      echo "Paper did not reach the outage gate; see $server_log" >&2
      exit 1
    fi
    sleep 0.1
  done
  echo "Paper exited before the outage gate; see $server_log" >&2
  exit 1
}

start_server "$RESTART_READY_ONE" "$RESTART_SERVER_LOG_ONE"
wait_ready "$RESTART_READY_ONE" "$RESTART_SERVER_LOG_ONE"
grep -Fq 'OUTAGE_SERVER_ALIVE_AND_LOCAL_CHUNK_LOADED' "$RESTART_RESULT_LOG"
before_crash=$(wc -l < "$RESTORE_ATTEMPT_LOG")
if [ "$before_crash" -lt 1 ] || [ "$before_crash" -gt 6 ]; then
  echo "unexpected number of restore attempts before forced stop: $before_crash" >&2
  exit 1
fi

kill -KILL "$server_pid"
set +e
wait "$server_pid"
crash_status=$?
set -e
server_pid=
if [ "$crash_status" -ne 137 ]; then
  echo "expected test Paper process to be killed with status 137, got $crash_status" >&2
  exit 1
fi
test -f "$marker"
test ! -e "$region"

start_server "$RESTART_READY_TWO" "$RESTART_SERVER_LOG_TWO"
wait_ready "$RESTART_READY_TWO" "$RESTART_SERVER_LOG_TWO"
after_restart=$(wc -l < "$RESTORE_ATTEMPT_LOG")
if [ "$after_restart" -le "$before_crash" ]; then
  echo "the restore helper was not retried after Paper restarted" >&2
  exit 1
fi
if [ "$((after_restart - before_crash))" -gt 6 ]; then
  echo "too many restore attempts after Paper restarted: $((after_restart - before_crash))" >&2
  exit 1
fi
test -f "$marker"
test ! -e "$region"
: > "$RESTORE_ALLOW_FILE"

set +e
wait "$server_pid"
server_status=$?
set -e
server_pid=
if [ "$server_status" -ne 0 ]; then
  echo "Paper failed after the fixture recovered (exit $server_status)" >&2
  exit 1
fi
grep -Eq 'OUTAGE_RECOVERED:[0-9]+ms' "$RESTART_RESULT_LOG"
if grep -Fq 'unrecoverableChunkSystemFailure' "$RESTART_SERVER_LOG_ONE" "$RESTART_SERVER_LOG_TWO"; then
  echo "Paper hit an unrecoverable chunk failure" >&2
  exit 1
fi
test ! -e "$marker"
test -f "$region"
total_attempts=$(wc -l < "$RESTORE_ATTEMPT_LOG")
printf 'CRASH_RESTART_RECOVERY: marker survived Paper crash; restore resumed after restart and recovered the saved chunk (%s attempts before crash, %s total)\n' \
  "$before_crash" "$total_attempts"

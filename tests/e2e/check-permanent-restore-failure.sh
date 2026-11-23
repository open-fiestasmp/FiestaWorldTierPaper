#!/bin/sh
set -eu

: "${FIESTA_TEST_DISPOSABLE:?set FIESTA_TEST_DISPOSABLE=YES}"
: "${SERVER_DIR:?set SERVER_DIR to a disposable server under /tmp}"
: "${WORLD_DIR:?set WORLD_DIR to that server's world folder}"
: "${RESTORE_COMMAND:?set RESTORE_COMMAND to a command that exits nonzero}"
: "${RESTORE_CONFIG:?set RESTORE_CONFIG to a disposable config file}"
: "${CHUNK_X:?set CHUNK_X to the marked chunk's X coordinate}"
: "${CHUNK_Z:?set CHUNK_Z to the marked chunk's Z coordinate}"
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
RESULT_PARENT=$(realpath -e -- "$(dirname -- "$RESULT_LOG")")
case "$SERVER_DIR" in /tmp/*) ;; *) echo "SERVER_DIR must resolve under /tmp" >&2; exit 2 ;; esac
test "$WORLD_DIR" = "$SERVER_DIR/world" || { echo "WORLD_DIR must be the disposable server's world" >&2; exit 2; }
case "$RESTORE_COMMAND" in /tmp/*) ;; *) echo "RESTORE_COMMAND must resolve under /tmp" >&2; exit 2 ;; esac
case "$RESTORE_CONFIG" in /tmp/*) ;; *) echo "RESTORE_CONFIG must resolve under /tmp" >&2; exit 2 ;; esac
case "$RESULT_PARENT" in /tmp/*) ;; *) echo "RESULT_LOG must be under /tmp" >&2; exit 2 ;; esac
test ! -L "$RESULT_LOG"

region_x=$((CHUNK_X >> 5))
region_z=$((CHUNK_Z >> 5))
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
test -f "$marker"
test ! -e "$region"

set +e
(cd "$SERVER_DIR" && java \
  -Dfiesta.test.mode=verify \
  -Dfiesta.test.chunkX="$CHUNK_X" \
  -Dfiesta.test.chunkZ="$CHUNK_Z" \
  -Dfiesta.test.result="$RESULT_LOG" \
  -Dfiesta.worldTier.command="$RESTORE_COMMAND" \
  -Dfiesta.worldTier.config="$RESTORE_CONFIG" \
  -jar server.jar nogui) > "$RESULT_LOG.server.log" 2>&1
server_status=$?
set -e

if ! grep -Fq "unrecoverableChunkSystemFailure" "$RESULT_LOG.server.log"; then
  echo "expected Paper to stop on permanent marked-region restore failure (exit $server_status)" >&2
  exit 1
fi
grep -Fq "Fiesta world-tier restore failed with exit code 1" "$RESULT_LOG.server.log"
test -f "$marker"
test ! -e "$region"
printf '%s\n' "FAIL_CLOSED: marker retained, local region absent; Paper stopped (exit $server_status)"

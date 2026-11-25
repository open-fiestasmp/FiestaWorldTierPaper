#!/usr/bin/env bash
set -euo pipefail

: "${SERVER_JAR:?set SERVER_JAR to the patched Paper bundler JAR}"
: "${PLUGIN_JAR:?set PLUGIN_JAR to tests/e2e/build/FiestaTierE2E.jar}"

for file in "$SERVER_JAR" "$PLUGIN_JAR"; do
  [[ "$file" = /* && -f "$file" && ! -L "$file" ]] || {
    echo "test JAR must be an absolute regular file: $file" >&2
    exit 2
  }
done

server_jar=$(realpath -e -- "$SERVER_JAR")
plugin_jar=$(realpath -e -- "$PLUGIN_JAR")
root=$(mktemp -d /tmp/fiesta-markerless-paper-XXXXXX)
server_dir="$root/server"
server_pid=
keep_root=${FIESTA_TEST_KEEP_ROOT:-0}
case "$keep_root" in 0|1) ;; *) echo "FIESTA_TEST_KEEP_ROOT must be 0 or 1" >&2; exit 2 ;; esac

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill -TERM -- "-$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  if [[ "$status" -eq 0 && "$keep_root" == 0 ]]; then
    rm -rf -- "$root"
  else
    echo "test files kept in $root" >&2
  fi
  exit "$status"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$server_dir/plugins" "$root/remote"
cp -- "$server_jar" "$server_dir/server.jar"
cp -- "$plugin_jar" "$server_dir/plugins/FiestaTierE2E.jar"
cat > "$server_dir/eula.txt" <<'EOF'
eula=true
EOF
cat > "$server_dir/server.properties" <<'EOF'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-name=world
level-seed=20261125
level-type=minecraft:normal
gamemode=survival
difficulty=peaceful
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=1
allow-flight=true
enable-query=false
enable-rcon=false
EOF

run_seed() {
  (cd "$server_dir" && timeout --kill-after=5s 180s nice -n 19 java \
    -XX:ActiveProcessorCount=2 -Xms512M -Xmx1G \
    -Dfiesta.test.mode=seed \
    -Dfiesta.test.chunkX=320 -Dfiesta.test.chunkZ=320 \
    -Dfiesta.test.result="$root/seed-result.log" \
    -Dfiesta.test.keepAliveSeconds=0 \
    -jar server.jar nogui) > "$root/seed-server.log" 2>&1
}

run_seed
grep -Eq 'FIESTA_TIER_E2E_SEEDED:[0-9]+ms' "$root/seed-server.log"
terrain="$server_dir/world/region/r.10.10.mca"
marker="$server_dir/world/.world-tier/remote/r.10.10.json"
test -s "$terrain"
test ! -e "$marker"
cp -- "$terrain" "$root/remote/r.10.10.mca"
rm -- "$terrain"
printf 'REMOTE\n' > "$root/state"
printf '{}\n' > "$root/tier-test-config.json"
chmod 600 "$root/tier-test-config.json"
: > "$root/attempts"

cat > "$root/restore-command.sh" <<'EOF'
#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
operation=$1
shift
dimension_root=
region_x=
region_z=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dimension-root) dimension_root=$2; shift 2 ;;
    --region-x) region_x=$2; shift 2 ;;
    --region-z) region_z=$2; shift 2 ;;
    *) shift ;;
  esac
done
if [ "$dimension_root" != "$root/server/world" ]; then
  exit 2
fi
case "$operation" in
  region-state)
    if [ "$region_x" = 10 ] && [ "$region_z" = 10 ]; then
      cat "$root/state"
    else
      printf 'UNTRACKED\n'
    fi
    ;;
  recover-region)
    printf 'recover-region %s %s\n' "$region_x" "$region_z" >> "$root/attempts"
    if [ ! -e "$root/allow-restore" ]; then
      exit 75
    fi
    if [ "$region_x" != 10 ] || [ "$region_z" != 10 ] || [ "$(cat "$root/state")" != REMOTE ]; then
      exit 2
    fi
    cp -- "$root/remote/r.$region_x.$region_z.mca" "$dimension_root/region/r.$region_x.$region_z.mca"
    printf 'LOCAL\n' > "$root/state"
    printf 'LOCAL\n'
    ;;
  *)
    exit 2
    ;;
esac
EOF
chmod 700 "$root/restore-command.sh"

(cd "$server_dir" && exec setsid timeout --kill-after=5s 150s nice -n 19 java \
  -XX:ActiveProcessorCount=2 -Xms512M -Xmx1G \
  -Dfiesta.test.mode=outage \
  -Dfiesta.test.chunkX=320 -Dfiesta.test.chunkZ=320 \
  -Dfiesta.test.localChunkX=128 -Dfiesta.test.localChunkZ=128 \
  -Dfiesta.test.result="$root/outage-result.log" \
  -Dfiesta.test.restoreAvailable="$root/outage-ready" \
  -Dfiesta.test.outageMillis=5000 \
  -Dfiesta.worldTier.command="$root/restore-command.sh" \
  -Dfiesta.worldTier.config="$root/tier-test-config.json" \
  -Dfiesta.worldTier.journalLookupEnabled=true \
  -Dfiesta.worldTier.restoreTimeoutSeconds=60 \
  -jar server.jar nogui) > "$root/outage-server.log" 2>&1 &
server_pid=$!

for _ in $(seq 1 450); do
  [[ -f "$root/outage-ready" ]] && break
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Paper exited before the outage gate" >&2
    exit 1
  fi
  sleep 0.2
done
test -f "$root/outage-ready"
test ! -e "$terrain"
test ! -e "$marker"
test "$(cat "$root/state")" = REMOTE

for _ in $(seq 1 150); do
  attempts=$(wc -l < "$root/attempts")
  [[ "$attempts" -ge 2 ]] && break
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Paper exited while markerless restore was unavailable" >&2
    exit 1
  fi
  test ! -e "$terrain"
  test ! -e "$marker"
  sleep 0.2
done
attempts=$(wc -l < "$root/attempts")
test "$attempts" -ge 2
test ! -e "$terrain"
test ! -e "$marker"
test "$(cat "$root/state")" = REMOTE
touch "$root/allow-restore"

set +e
wait "$server_pid"
result=$?
set -e
server_pid=
test "$result" -eq 0
grep -Fq 'OUTAGE_SERVER_ALIVE_AND_LOCAL_CHUNK_LOADED' "$root/outage-result.log"
grep -Eq 'OUTAGE_RECOVERED:[0-9]+ms' "$root/outage-result.log"
test -s "$terrain"
test ! -e "$marker"
test "$(cat "$root/state")" = LOCAL

printf 'Markerless recovery passed after %s failed attempts; Paper stayed up and loaded the saved block.\n' "$attempts"

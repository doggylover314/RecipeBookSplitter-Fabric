#!/usr/bin/env bash
# End-to-end scenarios for RecipeBookSplitter: a real Fabric server with a data pack that makes the recipe book packet
# larger than the 8 MiB limit, and a minimal protocol client that records what arrives. See e2e/README.md.
#
#   ACCEPT_EULA=true e2e/run_e2e.sh <scenario>...     scenarios: E0 E0b E1 E2 E3 E4 E5 E6 E6b E6x E7a E7b E7c, or "all"
#
# The mod jar is taken from build/libs (run ./gradlew build first) unless RBS_JAR points at one.
# Other environment variables: JAVA (backend server, default "java"), VELOCITY_JAVA (Java 25+, downloaded if unset
# and not found), E2E_BACKEND_PORT (25565), E2E_PROXY_PORT (25577), E2E_WORK (e2e/work), E2E_CACHE (E2E_WORK/cache).
set -uo pipefail

E=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$E/.." && pwd)
export E2E_WORK=${E2E_WORK:-$E/work}
export E2E_CACHE=${E2E_CACHE:-$E2E_WORK/cache}
JAVA=${JAVA:-java}
BACKEND_PORT=${E2E_BACKEND_PORT:-25565}
PROXY_PORT=${E2E_PROXY_PORT:-25577}
ALL_SCENARIOS=(E0 E0b E1 E2 E3 E4 E5 E6 E6b E6x E7a E7b E7c)

FABRIC_API=fabric-api-0.141.6+1.21.11.jar
POLYMER=polymer-bundled-0.15.2+1.21.11.jar
FPL=FabricProxy-Lite-2.11.0.jar
VELOCITY=velocity-4.2.0-30.jar
LOGIN_RE='Tester\[.*\] logged in'

# Sets the scenario variables. Defaults: the mod with Fabric API + Polymer, backend compression 256, two phases
# (join + /recipe give, then relog).
define_scenario() {
  DESC=; KIND=split; RBS=1; POLYMER_MOD=1; PROXY=0; COMP=256; CFG=default; LOG_OVERSIZED=false
  HUGE=0; MAX_CHUNK=1048576; PHASES=2; CONFIG_CHECK=none
  case $1 in
    E0)  DESC="baseline: no mod, compression 256"; KIND=baseline; RBS=0 ;;
    E0b) DESC="baseline: no mod, compression off"; KIND=baseline; RBS=0; COMP=-1 ;;
    E1)  DESC="mod + Fabric API + Polymer, compression 256" ;;
    E2)  DESC="mod + Fabric API + Polymer, compression off"; COMP=-1 ;;
    E3)  DESC="mod + Fabric API, no Polymer, compression 256"; POLYMER_MOD=0 ;;
    E4)  DESC="E1 plus /reload with the client connected"; PHASES=3 ;;
    E5)  DESC="E1 plus one 4.5 MB recipe entry (synthetic), logOversizedPackets"; HUGE=4500000; LOG_OVERSIZED=true ;;
    E6)  DESC="E1 behind Velocity (modern forwarding, FabricProxy-Lite), backend compression 256"; PROXY=1 ;;
    E6b) DESC="E1 behind Velocity, backend compression off"; PROXY=1; COMP=-1 ;;
    E6x) DESC="baseline behind Velocity: no mod, backend compression 256"; KIND=baseline; RBS=0; PROXY=1 ;;
    E7a) DESC="config smoke: no config file, created with defaults"; CFG=none; PHASES=1; CONFIG_CHECK=created ;;
    E7b) DESC="config smoke: maxChunkBytes 10 is clamped to 65,536"; CFG='{"maxChunkBytes": 10}'; MAX_CHUNK=65536
         PHASES=1; CONFIG_CHECK=clamp ;;
    E7c) DESC="config smoke: malformed config, defaults used and file left alone"; CFG='{"maxChunkBytes": '
         PHASES=1; CONFIG_CHECK=malformed ;;
    *) return 1 ;;
  esac
}

die() { echo "run_e2e: $*" >&2; exit 2; }

# wait_for <regex> <file> <min matching lines> <timeout s> [pid that must stay alive]
wait_for() {
  local end=$((SECONDS + $4))
  while [ $SECONDS -lt $end ]; do
    [ "$(grep -cE "$1" "$2" 2>/dev/null)" -ge "$3" ] && return 0
    [ -z "${5:-}" ] || kill -0 "$5" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

# wait_exit <pid> <timeout s>: true if the process ended in time
wait_exit() {
  local end=$((SECONDS + $2))
  while kill -0 "$1" 2>/dev/null; do
    [ $SECONDS -lt $end ] || return 1
    sleep 1
  done
}

java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }

find_velocity_java() {
  [ -z "${VELOCITY_JAVA:-}" ] || return 0
  if [ "$(java_major "$JAVA")" -ge 25 ] 2>/dev/null; then
    VELOCITY_JAVA=$JAVA
    return 0
  fi
  "$E/fetch.sh" jre25 || die "could not get a Java 25 runtime for Velocity; set VELOCITY_JAVA"
  VELOCITY_JAVA=$(ls -d "$E2E_CACHE"/jre25/*/bin/java | head -1)
}

rbs_jar() {
  if [ -n "${RBS_JAR:-}" ]; then echo "$RBS_JAR"; return; fi
  ls -t "$REPO"/build/libs/recipebooksplitter-*.jar 2>/dev/null | grep -v -e '-sources' -e '-dev' | head -1
}

cleanup() {
  exec 3>&- 4>&-
  for pid in ${CLIENT_PID:-} ${SERVER_PID:-} ${VELOCITY_PID:-}; do kill "$pid" 2>/dev/null; done
}

# Velocity's first run only generates velocity.toml and forwarding.secret; patch them for the scenario.
prepare_velocity() {
  local v=$W/velocity
  mkdir -p "$v"
  cp "$E2E_CACHE/$VELOCITY" "$v/velocity.jar"
  ( cd "$v" && exec "$VELOCITY_JAVA" -Xmx512M -jar velocity.jar > first-run.log 2>&1 < /dev/null ) &
  local pid=$!
  wait_for 'Done \(' "$v/first-run.log" 1 90 "$pid" || { kill "$pid" 2>/dev/null; die "Velocity did not start, see $v/first-run.log"; }
  kill "$pid"
  wait_exit "$pid" 30 || kill -9 "$pid"
  python3 "$E/patch_velocity.py" "$v/velocity.toml" "$PROXY_PORT" "$BACKEND_PORT" || die "could not patch velocity.toml"
  printf 'secret = "%s"\n' "$(tr -d '\r\n' < "$v/forwarding.secret")" > "$S/config/FabricProxy-Lite.toml"
}

start_velocity() {
  local v=$W/velocity
  mkfifo "$v/console"
  ( cd "$v" && exec "$VELOCITY_JAVA" -Xmx512M -jar velocity.jar < console > "$W/velocity.log" 2>&1 ) &
  VELOCITY_PID=$!
  exec 4> "$v/console"
  wait_for 'Done \(' "$W/velocity.log" 1 90 "$VELOCITY_PID" || die "Velocity did not start, see $W/velocity.log"
}

start_backend() {
  mkfifo "$S/console"
  ( cd "$S" && exec "$JAVA" -Xmx3G -Drecipebooksplitter.debugDigest=true -jar fabric-server-launch.jar nogui \
      < console > "$W/server.log" 2>&1 ) &
  SERVER_PID=$!
  exec 3> "$S/console"
  wait_for 'Done \(' "$W/server.log" 1 300 "$SERVER_PID" || die "the server did not start, see $W/server.log"
}

# start_client <phase> <seconds>: runs the client in the background (CLIENT_PID)
start_client() {
  python3 "$E/client.py" --port "$CLIENT_PORT" --name Tester --seconds "$2" --out "$W/phase$1.json" > "$W/phase$1.txt" 2>&1 &
  CLIENT_PID=$!
}

run_phases() {
  # Phase 1: join, then give the player every recipe (a second, big recipe_book_add with replace=false).
  start_client 1 30
  wait_for "$LOGIN_RE" "$W/server.log" 1 60 && sleep 3
  echo "recipe give Tester *" >&3
  wait "$CLIENT_PID"
  [ "$PHASES" -ge 2 ] || return 0
  sleep 2
  # Phase 2: relog; the initial recipe book now holds every recipe (replace=true).
  start_client 2 20
  wait "$CLIENT_PID"
  [ "$PHASES" -ge 3 ] || return 0
  sleep 2
  # Phase 3: relog and /reload while connected; the reload resends the recipe book (replace=true).
  start_client 3 40
  wait_for "$LOGIN_RE" "$W/server.log" 3 60 && sleep 8
  echo "reload" >&3
  wait "$CLIENT_PID"
}

stop_servers() {
  echo "stop" >&3
  wait_exit "$SERVER_PID" 90 || kill "$SERVER_PID"
  if [ -n "${VELOCITY_PID:-}" ]; then
    echo "end" >&4
    wait_exit "$VELOCITY_PID" 30 || kill "$VELOCITY_PID"
  fi
  exec 3>&- 4>&-
}

run_scenario() {
  trap cleanup EXIT
  NAME=$1
  define_scenario "$NAME" || die "unknown scenario '$NAME' (known: ${ALL_SCENARIOS[*]})"
  W=$E2E_WORK/$NAME
  S=$W/server
  CLIENT_PORT=$BACKEND_PORT
  [ "$PROXY" = 1 ] && CLIENT_PORT=$PROXY_PORT
  SERVER_PID= VELOCITY_PID= CLIENT_PID=
  echo "== $NAME: $DESC"

  local fetch_targets=(server mods)
  [ "$PROXY" = 1 ] && fetch_targets+=(velocity)
  "$E/fetch.sh" "${fetch_targets[@]}" || die "download failed"
  local jar=
  if [ "$RBS" = 1 ]; then
    jar=$(rbs_jar)
    [ -n "$jar" ] || die "no mod jar in $REPO/build/libs; run ./gradlew build (or set RBS_JAR)"
  fi
  [ "$PROXY" = 1 ] && find_velocity_java

  rm -rf "$W"
  mkdir -p "$W" "$S"
  # Hard links: the server template is large and its files are only ever read.
  cp -al "$E2E_CACHE/server-template/." "$S/" 2>/dev/null || cp -a "$E2E_CACHE/server-template/." "$S/"
  rm -rf "$S/logs"
  mkdir -p "$S/mods" "$S/config" "$S/world/datapacks"
  cp "$E2E_CACHE/$FABRIC_API" "$S/mods/"
  [ "$POLYMER_MOD" = 1 ] && cp "$E2E_CACHE/$POLYMER" "$S/mods/"
  [ "$PROXY" = 1 ] && cp "$E2E_CACHE/$FPL" "$S/mods/"
  [ -n "$jar" ] && cp "$jar" "$S/mods/"

  cat > "$S/server.properties" <<PROPS
online-mode=false
level-type=flat
server-ip=127.0.0.1
server-port=$BACKEND_PORT
network-compression-threshold=$COMP
view-distance=2
simulation-distance=2
spawn-protection=0
max-tick-time=-1
PROPS
  case $CFG in
    none) ;;
    default) printf '{\n  "maxChunkBytes": 1048576,\n  "logSplits": true,\n  "logOversizedPackets": %s\n}\n' "$LOG_OVERSIZED" > "$S/config/recipebooksplitter.json" ;;
    *) printf '%s' "$CFG" > "$S/config/recipebooksplitter.json" ;;
  esac
  [ "$CFG" = none ] || cp "$S/config/recipebooksplitter.json" "$W/config.initial"

  local datapack_args=(--out "$S/world/datapacks/rbs_e2e" --count 3000 --pad 3000)
  [ "$HUGE" -gt 0 ] && datapack_args+=(--huge-entry-bytes "$HUGE")
  python3 "$E/gen_datapack.py" "${datapack_args[@]}" > /dev/null || die "data pack generation failed"

  NAME=$NAME DESC=$DESC KIND=$KIND RBS=$RBS POLYMER_MOD=$POLYMER_MOD PROXY=$PROXY COMP=$COMP MAX_CHUNK=$MAX_CHUNK \
    PHASES=$PHASES HUGE=$HUGE LOG_OVERSIZED=$LOG_OVERSIZED CONFIG_CHECK=$CONFIG_CHECK \
    python3 -c '
import json, os
env = os.environ
print(json.dumps({
    "name": env["NAME"], "description": env["DESC"], "kind": env["KIND"], "rbs": env["RBS"] == "1",
    "polymer": env["POLYMER_MOD"] == "1", "proxy": env["PROXY"] == "1", "compression": int(env["COMP"]),
    "max_chunk_bytes": int(env["MAX_CHUNK"]), "phases": int(env["PHASES"]), "huge_entry_bytes": int(env["HUGE"]),
    "log_oversized": env["LOG_OVERSIZED"] == "true", "config_check": env["CONFIG_CHECK"],
}, indent=2))' > "$W/scenario.json"

  [ "$PROXY" = 1 ] && prepare_velocity
  start_backend
  [ "$PROXY" = 1 ] && start_velocity
  run_phases
  stop_servers
  python3 "$E/check.py" "$NAME"
}

scenarios=("$@")
[ ${#scenarios[@]} -gt 0 ] || die "usage: run_e2e.sh <scenario>... | all   (scenarios: ${ALL_SCENARIOS[*]})"
[ "${scenarios[0]}" != all ] || scenarios=("${ALL_SCENARIOS[@]}")

failed=()
for scenario in "${scenarios[@]}"; do
  (run_scenario "$scenario") || failed+=("$scenario")
done
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}"
  exit 1
fi
echo "all scenarios passed: ${scenarios[*]}"

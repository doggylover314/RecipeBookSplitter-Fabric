# Shared helpers of the e2e scripts (run_e2e.sh, fetch.sh, realclient/run_client_e2e.sh). Source it: it only defines
# variables and functions. The functions use the caller's globals the way the scripts always did: W (scenario
# directory), S (server directory, W/server), CLIENT_PORT, and the pids SERVER_PID / VELOCITY_PID / CLIENT_PID.
#
# Environment (all optional): E2E_WORK (default e2e/work, git-ignored), E2E_CACHE (default E2E_WORK/cache),
# E2E_LOADER (Fabric Loader of the server, default 0.19.5), JAVA (server, default java), VELOCITY_JAVA (Java 25+),
# E2E_BACKEND_PORT (25565), E2E_PROXY_PORT (25577), E2E_AUX_PORT (25578, the throttle relay), SERVER_XMX (3G),
# RBS_JAR, E2E_TEMPLATE_LINK=1 (hard-link the server template instead of copying it), and E2E_STDIN: the stdin of the
# processes this kit starts without a console of their own (the server-template run, Velocity's first run, the real
# client's Gradle and Xvfb). The servers themselves read their console from a FIFO. Default /dev/null; point it at an
# empty regular file where /dev/null is not usable.

E=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$E/.." && pwd)

DEFAULT_LOADER=0.19.5
FABRIC_API=fabric-api-0.141.6+1.21.11.jar
POLYMER=polymer-bundled-0.15.2+1.21.11.jar
FPL=FabricProxy-Lite-2.11.0.jar
VELOCITY=velocity-4.2.0-30.jar
VIAFABRIC=ViaFabric-0.4.21+166-1.14-1.21.jar
POLYDECORATIONS=polydecorations-0.10.4+1.21.11.jar
POLYFACTORY=polyfactory-0.10.4+1.21.11.jar
LOGIN_RE='Tester\[.*\] logged in'

e2e_defaults() {
  export E2E_WORK=${E2E_WORK:-$E/work}
  export E2E_CACHE=${E2E_CACHE:-$E2E_WORK/cache}
  export E2E_LOADER=${E2E_LOADER:-$DEFAULT_LOADER}
  export E2E_STDIN=${E2E_STDIN:-/dev/null}
  JAVA=${JAVA:-java}
  BACKEND_PORT=${E2E_BACKEND_PORT:-25565}
  PROXY_PORT=${E2E_PROXY_PORT:-25577}
  AUX_PORT=${E2E_AUX_PORT:-25578}
  SERVER_XMX=${SERVER_XMX:-3G}
}

die() { echo "${E2E_PROG:-e2e}: $*" >&2; exit 2; }

# wait_for <regex> <file> <min matching lines> <timeout s> [pid that must stay alive]
wait_for() {
  local end=$((SECONDS + $4)) count
  while [ $SECONDS -lt $end ]; do
    count=$(grep -cE "$1" "$2" 2>/dev/null || true)
    [ "${count:-0}" -ge "$3" ] && return 0
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

# The mod jar: RBS_JAR, else the jar of the version in gradle.properties, else the newest one in build/libs.
rbs_jar() {
  if [ -n "${RBS_JAR:-}" ]; then echo "$RBS_JAR"; return; fi
  local version jar
  version=$(sed -n 's/^mod_version=//p' "$REPO/gradle.properties")
  jar=$REPO/build/libs/recipebooksplitter-$version.jar
  if [ -f "$jar" ]; then echo "$jar"; return; fi
  ls -t "$REPO"/build/libs/recipebooksplitter-*.jar 2>/dev/null | grep -v -e '-sources' -e '-dev' | head -1
}

# Velocity 4.2.0 needs Java 25: VELOCITY_JAVA, else JAVA if that is new enough, else the pinned JRE from fetch.sh.
find_velocity_java() {
  [ -z "${VELOCITY_JAVA:-}" ] || return 0
  if [ "$(java_major "$JAVA")" -ge 25 ] 2>/dev/null; then
    VELOCITY_JAVA=$JAVA
    return 0
  fi
  "$E/fetch.sh" jre25 || die "could not get a Java 25 runtime for Velocity; set VELOCITY_JAVA"
  VELOCITY_JAVA=$(ls -d "$E2E_CACHE"/jre25/*/bin/java | head -1)
}

# The server template of E2E_LOADER, made by fetch.sh. The default loader keeps the plain name, so caches made by
# earlier versions of the kit stay valid.
template_dir() {
  if [ "$E2E_LOADER" = "$DEFAULT_LOADER" ]; then
    echo "$E2E_CACHE/server-template"
  else
    echo "$E2E_CACHE/server-template-$E2E_LOADER"
  fi
}

# make_server <server dir> <mod jar>...: a server directory from the template, with the jars in mods/. A real copy:
# the template is never written to. (E2E_TEMPLATE_LINK=1 hard-links it instead, which is faster on a small disk but
# lets a server that writes into its files change the cache.)
make_server() {
  local dir=$1 template
  shift
  template=$(template_dir)
  [ -d "$template/libraries" ] || die "no server template in $template; run: ACCEPT_EULA=true $E/fetch.sh server"
  mkdir -p "$dir"
  if [ "${E2E_TEMPLATE_LINK:-0}" = 1 ]; then
    cp -al "$template/." "$dir/" 2>/dev/null || cp -a "$template/." "$dir/"
  else
    cp -a "$template/." "$dir/"
  fi
  rm -rf "$dir/logs"
  mkdir -p "$dir/mods" "$dir/config" "$dir/world/datapacks"
  local jar
  for jar in "$@"; do cp "$jar" "$dir/mods/"; done
}

# write_properties <server dir> <network-compression-threshold>
write_properties() {
  cat > "$1/server.properties" <<PROPS
online-mode=false
level-type=flat
server-ip=127.0.0.1
server-port=$BACKEND_PORT
network-compression-threshold=$2
view-distance=2
simulation-distance=2
spawn-protection=0
max-tick-time=-1
PROPS
}

# write_config <server dir> <maxChunkBytes> <logOversizedPackets> <undeliverableEntries> <bundleChunks>: the mod's
# config file in the format the mod itself writes; also kept as W/config.initial for the config checks.
write_config() {
  printf '{\n  "maxChunkBytes": %s,\n  "logSplits": true,\n  "logOversizedPackets": %s,\n  "undeliverableEntries": "%s",\n  "bundleChunks": %s\n}\n' \
    "$2" "$3" "$4" "$5" > "$1/config/recipebooksplitter.json"
  cp "$1/config/recipebooksplitter.json" "$W/config.initial"
}

# prepare_velocity <W> <S> [velocity compression-threshold]: Velocity's first run only generates velocity.toml and
# forwarding.secret; patch them for the scenario and give the backend the secret. The first run listens on the proxy
# port (--port): without it Velocity would bind its default 0.0.0.0:25565 for that second, whatever E2E_PROXY_PORT says,
# and fail to start if something else holds 25565.
prepare_velocity() {
  local v=$1/velocity
  mkdir -p "$v"
  cp "$E2E_CACHE/$VELOCITY" "$v/velocity.jar"
  ( cd "$v" && exec "$VELOCITY_JAVA" -Xmx512M -jar velocity.jar --port "$PROXY_PORT" > first-run.log 2>&1 < "$E2E_STDIN" ) &
  local pid=$!
  wait_for 'Done \(' "$v/first-run.log" 1 90 "$pid" || { kill "$pid" 2>/dev/null; die "Velocity did not start, see $v/first-run.log"; }
  kill "$pid"
  wait_exit "$pid" 30 || kill -9 "$pid"
  python3 -I "$E/patch_velocity.py" "$v/velocity.toml" "$PROXY_PORT" "$BACKEND_PORT" ${3:+"$3"} || die "could not patch velocity.toml"
  printf 'secret = "%s"\n' "$(tr -d '\r\n' < "$v/forwarding.secret")" > "$2/config/FabricProxy-Lite.toml"
}

# start_velocity <W>: sets VELOCITY_PID, console on fd 4
start_velocity() {
  local v=$1/velocity
  mkfifo "$v/console"
  ( cd "$v" && exec "$VELOCITY_JAVA" -Xmx512M -jar velocity.jar < console > "$1/velocity.log" 2>&1 ) &
  VELOCITY_PID=$!
  exec 4> "$v/console"
  wait_for 'Done \(' "$1/velocity.log" 1 90 "$VELOCITY_PID" || die "Velocity did not start, see $1/velocity.log"
}

# start_backend <W> <S> <jvm argument>...: sets SERVER_PID, console on fd 3
start_backend() {
  local w=$1 s=$2
  shift 2
  mkfifo "$s/console"
  ( cd "$s" && exec "$JAVA" -Xmx"$SERVER_XMX" "$@" -jar fabric-server-launch.jar nogui < console > "$w/server.log" 2>&1 ) &
  SERVER_PID=$!
  exec 3> "$s/console"
  wait_for 'Done \(' "$w/server.log" 1 300 "$SERVER_PID" || die "the server did not start, see $w/server.log"
}

# console <command>: runs a command on the server console and records when, for the checkers (W/commands.log)
console() {
  printf '%s %s\n' "$(date +%s.%N)" "$1" >> "$W/commands.log"
  echo "$1" >&3
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

# Kills everything the scenario started (only those pids).
cleanup() {
  exec 3>&- 4>&-
  local pid
  for pid in ${CLIENT_PID:-} ${PROBE_PID:-} ${THROTTLE_PID:-} ${XVFB_PID:-} ${SERVER_PID:-} ${VELOCITY_PID:-}; do
    kill "$pid" 2>/dev/null
  done
}

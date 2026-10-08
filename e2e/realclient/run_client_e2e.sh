#!/usr/bin/env bash
# Runs a REAL Minecraft 1.21.11 client (headless: Xvfb + Mesa llvmpipe) against the e2e kit's server and records what
# the client does with the recipe book packets. See README.md in this directory.
#
#   ACCEPT_EULA=true run_client_e2e.sh <scenario>...        scenarios: A B C D E F G H I J K, or "all"
#
#   A  baseline, no Recipe Book Splitter, compression 256   the client is expected to be disconnected
#   B  mod, default config (1 MiB chunks), compression 256  join, "recipe give Tester *", relog
#   C  mod, maxChunkBytes 262,144, compression 256          as B, about 36 chunks per book
#   D  mod behind Velocity + FabricProxy-Lite (modern forwarding), compression 256
#   E  mod, default config, network compression off         as B
#   F  mod + one recipe whose entry is 4.5 MB               the real client is expected to reject it (NBT quota)
#   G  mod with bundleChunks, compression 256               each book must be handled in a single frame
#   H  mod, 1 MiB chunks, the link limited to THROTTLE_KBIT as B, over a slow link
#   I  H with bundleChunks                                  as G, over a slow link
#   J  mod, /reload while connected                         the reload resends the book (replace=true)
#   K  D with bundleChunks                                  as G, through Velocity
#
# Environment (see also lib.sh):
#   RBS_JAR          the mod jar (default: the one in build/libs)
#   E2E_WORK         output directory (default ./work next to this script); E2E_CACHE defaults to the kit's cache
#   E2E_DISPLAY      X display for Xvfb (default :77); set E2E_XVFB=0 to use the DISPLAY that is already set
#   CLIENT_XMX       client heap (default 2G)
#   RELOGS           how often the client relogs after the "recipe give" (default 1); every join gets its own summary
#   QUIET_MS         quiet period that ends a "run" in the client (default 4000)
#   CLIENT_TIMEOUT   seconds before the client is killed (default 420)
#   THROTTLE_KBIT    server-to-client rate of H and I in kilobit/s (default 8000; the server drops a client that does not
#                    answer a keep-alive within 15 s, so the book must arrive in well under that)
#   RBS_DIGEST       1 (default): the client re-encodes every entry it received and hashes the bytes, to compare them with
#                    the server's digest; 0: it does not (-Prbs.harness.digest=false) and the assertions about the digests
#                    are skipped. The re-encoding runs on the client's render thread inside the frame that handles the
#                    packets, so it adds several hundred ms to the frame that handles a whole book (a bundle). The frame
#                    times in the output are only meaningful with RBS_DIGEST=0. (The gradlew script runs under /bin/sh, and
#                    shells such as dash drop environment variables whose names contain a dot, as
#                    ORG_GRADLE_PROJECT_rbs.harness.digest does, before Gradle starts, so the script passes -P itself.)
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
E2E_PROG=run_client_e2e.sh
export E2E_WORK=${E2E_WORK:-$HERE/work}
export E2E_CACHE=${E2E_CACHE:-$HERE/../work/cache}
source "$HERE/../lib.sh"
e2e_defaults

DISPLAY_NUM=${E2E_DISPLAY:-:77}
CLIENT_XMX=${CLIENT_XMX:-2G}
QUIET_MS=${QUIET_MS:-4000}
CLIENT_TIMEOUT=${CLIENT_TIMEOUT:-420}
THROTTLE_KBIT=${THROTTLE_KBIT:-8000}
RELOGS_DEFAULT=${RELOGS:-1}
RBS_DIGEST=${RBS_DIGEST:-1}
case $RBS_DIGEST in 0|1) ;; *) die "RBS_DIGEST must be 0 or 1, not '$RBS_DIGEST'" ;; esac
ALL=(A B C D E F G H I J K)

define_scenario() {
  DESC=; HUGE=0; RBS=1; PROXY=0; COMP=256; MAX_CHUNK=1048576; BUNDLE_CHUNKS=false; THROTTLE=0; RELOAD=0
  RELOGS=$RELOGS_DEFAULT; SUMMARIES=; RECONNECT=0
  case $1 in
    A) DESC="baseline: no mod, compression 256"; RBS=0; RELOGS=0; SUMMARIES=99; RECONNECT=1 ;;
    B) DESC="mod, 1 MiB chunks, compression 256" ;;
    C) DESC="mod, 262,144-byte chunks, compression 256"; MAX_CHUNK=262144 ;;
    D) DESC="mod behind Velocity 4.2.0 + FabricProxy-Lite, compression 256"; PROXY=1 ;;
    E) DESC="mod, 1 MiB chunks, network compression off"; COMP=-1 ;;
    F) DESC="mod, 1 MiB chunks, plus one recipe whose entry is 4.5 MB (more than the client's 2 MiB NBT quota)"; HUGE=4500000
       RELOGS=0; SUMMARIES=99; RECONNECT=1 ;;
    G) DESC="mod with bundleChunks, 1 MiB chunks, compression 256"; BUNDLE_CHUNKS=true ;;
    H) DESC="mod, 1 MiB chunks, compression 256, the link limited to $THROTTLE_KBIT kbit/s"; THROTTLE=1 ;;
    I) DESC="mod with bundleChunks, 1 MiB chunks, compression 256, the link limited to $THROTTLE_KBIT kbit/s"; BUNDLE_CHUNKS=true; THROTTLE=1 ;;
    J) DESC="mod, 1 MiB chunks, compression 256, /reload while connected"; RELOAD=1; RELOGS=0 ;;
    K) DESC="mod with bundleChunks behind Velocity 4.2.0 + FabricProxy-Lite, compression 256"; PROXY=1; BUNDLE_CHUNKS=true ;;
    *) return 1 ;;
  esac
  [ -n "$SUMMARIES" ] || SUMMARIES=$((2 + RELOGS + RELOAD))
}

kill_client() {
  # The Gradle daemon starts the client JVM outside our process group; find it by its unique output directory.
  [ -n "${CLIENT_MARK:-}" ] || return 0
  pkill -f -- "$CLIENT_MARK" 2>/dev/null
}

cleanup_all() {
  kill_client
  cleanup
}

start_xvfb() {
  [ "${E2E_XVFB:-1}" = 1 ] || { export DISPLAY=${DISPLAY:?E2E_XVFB=0 needs DISPLAY}; return 0; }
  [ -z "${XVFB_PID:-}" ] || return 0
  local n=${DISPLAY_NUM#:}
  [ ! -e "/tmp/.X11-unix/X$n" ] || die "X display $DISPLAY_NUM already exists; set E2E_DISPLAY"
  Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 +extension GLX +render -noreset > "$E2E_WORK/xvfb.log" 2>&1 < "$E2E_STDIN" &
  XVFB_PID=$!
  sleep 2
  kill -0 "$XVFB_PID" 2>/dev/null || die "Xvfb did not start, see $E2E_WORK/xvfb.log"
  export DISPLAY=$DISPLAY_NUM
}

write_client_options() {
  # Skips the first-run narrator screen and the multiplayer warning, keeps rendering cheap, mutes sound.
  mkdir -p "$HERE/run"
  cat > "$HERE/run/options.txt" <<OPTS
version:4671
onboardAccessibility:false
joinedFirstServer:true
skipMultiplayerWarning:true
pauseOnLostFocus:false
renderDistance:2
simulationDistance:5
maxFps:20
enableVsync:false
soundCategory_master:0.0
OPTS
  rm -f "$HERE/run/servers.dat"
}

start_client() {
  write_client_options
  mkdir -p "$W/client"
  CLIENT_MARK="-Drbs.harness.out=$W/client"
  local digest_args=()
  [ "$RBS_DIGEST" = 1 ] || digest_args+=(-Prbs.harness.digest=false)
  ( cd "$HERE" && LIBGL_ALWAYS_SOFTWARE=1 LP_NUM_THREADS=2 exec setsid "$REPO/gradlew" -p "$HERE" runClient --no-daemon -q \
      -Prbs.server="127.0.0.1:$CLIENT_PORT" -Prbs.xmx="$CLIENT_XMX" \
      -Prbs.harness.out="$W/client" -Prbs.harness.relogs="$RELOGS" -Prbs.harness.summaries="$SUMMARIES" \
      -Prbs.harness.reconnectOnDisconnect="$RECONNECT" -Prbs.harness.quietMs="$QUIET_MS" ${digest_args[@]+"${digest_args[@]}"} \
      > "$W/client.log" 2>&1 < "$E2E_STDIN" ) &
  CLIENT_PID=$!
}

# The client logs a summary once the recipe book has been quiet for QUIET_MS: summary n is on disk when n lines match.
summaries_seen() { wait_for '"event":"summary"' "$W/client/events.jsonl" "$1" "$2" "$CLIENT_PID"; }

run_scenario() {
  trap cleanup_all EXIT
  NAME=$1
  define_scenario "$NAME" || die "unknown scenario '$NAME' (known: ${ALL[*]})"
  W=$E2E_WORK/$NAME
  S=$W/server
  CLIENT_PORT=$BACKEND_PORT
  [ "$PROXY" = 1 ] && CLIENT_PORT=$PROXY_PORT
  [ "$THROTTLE" = 1 ] && CLIENT_PORT=$AUX_PORT
  SERVER_PID= VELOCITY_PID= CLIENT_PID= THROTTLE_PID= XVFB_PID= CLIENT_MARK=
  echo "== $NAME: $DESC"

  local targets=(server mods)
  [ "$PROXY" = 1 ] && targets+=(velocity)
  "$E/fetch.sh" "${targets[@]}" || die "download failed"
  [ "$PROXY" = 1 ] && find_velocity_java
  local jars=("$E2E_CACHE/$FABRIC_API" "$E2E_CACHE/$POLYMER") rbs=
  [ "$PROXY" = 1 ] && jars+=("$E2E_CACHE/$FPL")
  if [ "$RBS" = 1 ]; then
    rbs=$(rbs_jar)
    [ -n "$rbs" ] || die "no mod jar; run ./gradlew build in the repository (or set RBS_JAR)"
    jars+=("$rbs")
  fi

  rm -rf "$W"
  mkdir -p "$W"
  make_server "$S" "${jars[@]}"
  write_properties "$S" "$COMP"
  # Tester is an operator, as in the runs the numbers in the README come from (the offline-mode UUID is the MD5-based
  # one of "OfflinePlayer:Tester").
  python3 -I - "$S/ops.json" <<'PY'
import hashlib, json, sys, uuid
d = bytearray(hashlib.md5(b"OfflinePlayer:Tester").digest())
d[6] = (d[6] & 0x0F) | 0x30
d[8] = (d[8] & 0x3F) | 0x80
json.dump([{"uuid": str(uuid.UUID(bytes=bytes(d))), "name": "Tester", "level": 4, "bypassesPlayerLimit": False}],
          open(sys.argv[1], "w"), indent=2)
PY
  [ "$RBS" != 1 ] || write_config "$S" "$MAX_CHUNK" "$([ "$HUGE" -gt 0 ] && echo true || echo false)" drop "$BUNDLE_CHUNKS"
  local pack_args=(--out "$S/world/datapacks/rbs_e2e" --count 3000 --pad 3000)
  [ "$HUGE" -gt 0 ] && pack_args+=(--huge-entry-bytes "$HUGE")
  python3 -I "$E/gen_datapack.py" "${pack_args[@]}" > "$W/gen_pack.log" 2>&1 || die "data pack generation failed, see $W/gen_pack.log"
  printf '{"name": "%s", "description": "%s", "rbs": %s, "proxy": %s, "compression": %s, "max_chunk_bytes": %s, "relogs": %s, "reloads": %s, "huge_entry_bytes": %s, "bundle_chunks": %s, "throttle_kbit": %s}\n' \
    "$NAME" "$DESC" "$([ "$RBS" = 1 ] && echo true || echo false)" "$([ "$PROXY" = 1 ] && echo true || echo false)" "$COMP" "$MAX_CHUNK" \
    "$RELOGS" "$RELOAD" "$HUGE" "$BUNDLE_CHUNKS" "$([ "$THROTTLE" = 1 ] && echo "$THROTTLE_KBIT" || echo null)" > "$W/scenario.json"

  start_xvfb
  [ "$PROXY" = 1 ] && prepare_velocity "$W" "$S"
  start_backend "$W" "$S" -Drecipebooksplitter.debugDigest=true
  [ "$PROXY" = 1 ] && start_velocity "$W"
  if [ "$THROTTLE" = 1 ]; then
    python3 -I "$E/throttle.py" --listen "$AUX_PORT" --target "127.0.0.1:$BACKEND_PORT" --kbit "$THROTTLE_KBIT" > "$W/throttle.log" 2>&1 < "$E2E_STDIN" &
    THROTTLE_PID=$!
    sleep 1
  fi
  echo "load average before the client starts: $(cut -d' ' -f1-3 /proc/loadavg)" | tee "$W/loadavg.txt"
  start_client

  # The first summary is the fresh player's (almost empty) recipe book: now give the player every recipe.
  if summaries_seen 1 240; then
    console "recipe give Tester *"
    echo "sent: recipe give Tester *"
    # J: once the give has been handled, /reload resends the book while the client stays connected.
    if [ "$RELOAD" = 1 ] && summaries_seen 2 240; then
      console "reload"
      echo "sent: reload"
    fi
  else
    echo "WARNING: no client summary within 240 s (the client may have been disconnected or failed to start)"
  fi
  local end=$((SECONDS + CLIENT_TIMEOUT))
  while kill -0 "$CLIENT_PID" 2>/dev/null && [ $SECONDS -lt $end ]; do sleep 1; done
  if kill -0 "$CLIENT_PID" 2>/dev/null; then
    echo "WARNING: the client did not finish within $CLIENT_TIMEOUT s, killing it"
    kill_client
    wait_exit "$CLIENT_PID" 30 || kill "$CLIENT_PID"
  fi
  sleep 2
  stop_servers
  python3 -I "$HERE/analyze.py" "$W"
}

scenarios=("$@")
[ ${#scenarios[@]} -gt 0 ] || die "usage: run_client_e2e.sh <scenario>... | all   (scenarios: ${ALL[*]})"
[ "${scenarios[0]}" != all ] || scenarios=("${ALL[@]}")
for scenario in "${scenarios[@]}"; do
  define_scenario "$scenario" || die "unknown scenario '$scenario' (known: ${ALL[*]})"
done
mkdir -p "$E2E_WORK"

failed=()
for scenario in "${scenarios[@]}"; do
  (run_scenario "$scenario") || failed+=("$scenario")
done
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}"
  exit 1
fi
echo "done: ${scenarios[*]}"

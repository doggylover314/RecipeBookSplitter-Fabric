#!/usr/bin/env bash
# End-to-end scenarios for RecipeBookSplitter: a real Fabric server with a data pack that makes the recipe book packet
# larger than the 8 MiB limit (or with test content: Polymer items, a ViaFabric translation, bundles), and a minimal
# protocol client that records what arrives. See e2e/README.md for the scenario catalogue.
#
#   ACCEPT_EULA=true e2e/run_e2e.sh <scenario>...     one or more ids, or a group: all (E* and L*), polymer (P*),
#                                                     via (V*), perf (X*)
#   e2e/run_e2e.sh --list                             every id with its description
#
# The mod jar is taken from build/libs (run ./gradlew build first) unless RBS_JAR points at one. Scenarios that need a
# test mod want it built: ./gradlew -p e2e/testmod build, ./gradlew -p e2e/polytest-mod build (or TESTMOD_JAR /
# POLYTEST_JAR). VIAFABRIC_JAR replaces the pinned ViaFabric jar. The other variables (JAVA, VELOCITY_JAVA, E2E_*)
# are described at the top of lib.sh.
set -uo pipefail

E2E_PROG=run_e2e.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_defaults

CORE_SCENARIOS=(E0 E0b E1 E1v E1o E2 E3 E3b E4 E5 E6 E6b E6x E7a E7b E7c E7d E7e E8 E8b E8x E8c E8d E8e E9 E9b E9s L2 L3 L4 L5 L6)
POLYMER_SCENARIOS=(P0 P1 P1b P2 P3 P4 P4v P5 P6 P7 PV PR1 PR2 PR2v PR3 PL1 PL2 PX1 PX1b PX2 PX2b PE6 PE6b PE6c)
VIA_SCENARIOS=(VB0 VB0b V1 V1b V2 V2b V5 VC VW1 VW2 VW3)
PERF_SCENARIOS=(X1 X1b X1c)

# Presets of the scenario groups.
polymer_preset() {
  CHECKER=check_poly.py; EXTRA_MODS=(polytest); DATAPACK=polytest; LOG_OVERSIZED=true
  PACK_ARGS=(--tagged 300 --direct 1200 --fat 0)
  PHASES=(give "cmd:polytest measure Tester" "cmd:polytest measure Tester send")
}
# Polymer items in the recipes (the P1 pack) together with ViaFabric: the ViaFabric checker and phases (a 774 control and a
# newer-protocol client), the Polymer data pack.
polymer_via_preset() {
  polymer_preset
  CHECKER=check_via.py; EXTRA_MODS=(polytest viafabric)
  PHASES=(774/give_take 774/give_take newer/give 774/relog newer/relog)
}
via_preset() {
  CHECKER=check_via.py; EXTRA_MODS=(viafabric); LOG_OVERSIZED=true
  PHASES=(774/give_take 774/give_take newer/give 774/relog newer/relog)
}
perf_preset() {
  KIND=perf; DIGEST=false; PROBE=1; JVM_ARGS=(-Dio.netty.eventLoopThreads=1); ENCODE_ONCE=any
  PHASES=(give_cycles:10 relog)
}
BUNDLE_COMMAND="cmd:rbstest bundle Tester 3000 3000"

# Sets the scenario variables. Defaults: the mod with Fabric API + Polymer, backend compression 256, default config, the
# 9.2 MB data pack, two phases (join + /recipe give, then relog). Phase specs: [<protocol|newer>/]<action>[:<arg>], see
# run_phase.
define_scenario() {
  DESC=; KIND=split; CHECKER=check.py; RBS=1; FABRIC_API_MOD=1; POLYMER_MOD=1; EXTRA_MODS=(); PROXY=0; VCOMP=; COMP=256
  CFG=default; MAX_CHUNK=1048576; LOG_OVERSIZED=false; UNDELIVERABLE=drop; BUNDLE_CHUNKS=false
  DATAPACK=e2e; PACK_ARGS=(); JVM_ARGS=(); DIGEST=true; PROBE=0; POLYMER_SPLIT=0; NEWER_PROTOCOL=775
  PHASES=(give relog); CONFIG_CHECK=none; ENCODE_ONCE=reused; HUGE=0; POLYTEST_BOUND=0; VANILLA=0
  local incompressible=(--mode incompressible --count 42 --pad 261000)
  case $1 in
    E0)  DESC="baseline: no mod, compression 256"; KIND=baseline; RBS=0 ;;
    E0b) DESC="baseline: no mod, compression off"; KIND=baseline; RBS=0; COMP=-1 ;;
    E1)  DESC="mod + Fabric API + Polymer, compression 256" ;;
    E1v) DESC="E1 with verifyEncodeOnce: every packet written from measured bytes is also encoded normally and compared"
         JVM_ARGS=(-Drecipebooksplitter.verifyEncodeOnce=true); ENCODE_ONCE=verified ;;
    E1o) DESC="E1 with encodeOnce=false: the chunks are encoded again after measuring, as in 1.0.0"
         JVM_ARGS=(-Drecipebooksplitter.encodeOnce=false); ENCODE_ONCE=off ;;
    E2)  DESC="mod + Fabric API + Polymer, compression off"; COMP=-1 ;;
    E3)  DESC="mod + Fabric API, no Polymer, compression 256"; POLYMER_MOD=0 ;;
    E3b) DESC="mod alone: no Fabric API, no Polymer, compression 256"; FABRIC_API_MOD=0; POLYMER_MOD=0 ;;
    E4)  DESC="E1 plus /reload with the client connected"; PHASES=(give relog reload) ;;
    E5)  DESC="E1 plus one 4.5 MB recipe entry (synthetic), logOversizedPackets"; HUGE=4500000; LOG_OVERSIZED=true ;;
    E6)  DESC="E1 behind Velocity (modern forwarding, FabricProxy-Lite), backend compression 256"; PROXY=1 ;;
    E6b) DESC="E1 behind Velocity, backend compression off"; PROXY=1; COMP=-1 ;;
    E6x) DESC="baseline behind Velocity: no mod, backend compression 256"; KIND=baseline; RBS=0; PROXY=1 ;;
    E7a) DESC="config smoke: no config file, created with the defaults"; CFG=none; PHASES=(give); CONFIG_CHECK=created ;;
    E7b) DESC="config smoke: maxChunkBytes 10 is clamped to 262,144"; CFG='{"maxChunkBytes": 10}'; MAX_CHUNK=262144
         PHASES=(give); CONFIG_CHECK=clamp ;;
    E7c) DESC="config smoke: malformed config, defaults used and file left alone"; CFG='{"maxChunkBytes": '
         PHASES=(give); CONFIG_CHECK=malformed ;;
    E7d) DESC="config smoke: a 1.0.0 file (maxChunkBytes 2,000,000, no new keys) is clamped to the ceiling 1,048,576 and left alone"
         CFG='{"maxChunkBytes": 2000000, "logSplits": true, "logOversizedPackets": false}'; MAX_CHUNK=1048576
         PHASES=(give); CONFIG_CHECK=upgrade ;;
    E7e) DESC="config smoke: invalid undeliverableEntries and bundleChunks, defaults used"
         CFG='{"undeliverableEntries": "SEND", "bundleChunks": "yes"}'; PHASES=(give); CONFIG_CHECK=invalid ;;
    E8)  DESC="a 9.2 MB recipe book packet inside a bundle (test mod), compression 256"; KIND=bundle; EXTRA_MODS=(testmod)
         DATAPACK=none; PHASES=("$BUNDLE_COMMAND") ;;
    E8b) DESC="as E8, compression off"; KIND=bundle; EXTRA_MODS=(testmod); DATAPACK=none; COMP=-1; PHASES=("$BUNDLE_COMMAND") ;;
    E8x) DESC="baseline for E8: no mod, the bundle disconnects the client"; KIND=baseline; RBS=0; EXTRA_MODS=(testmod)
         DATAPACK=none; PHASES=("$BUNDLE_COMMAND") ;;
    E8c) DESC="bundleChunks: the chunks of each split book arrive in one bundle, compression 256"; KIND=bundlechunks
         BUNDLE_CHUNKS=true ;;
    E8d) DESC="E8c behind Velocity"; KIND=bundlechunks; BUNDLE_CHUNKS=true; PROXY=1 ;;
    E8e) DESC="as E8 with bundleChunks: the packet is in a bundle already, so it is split in place and nothing is nested"
         KIND=bundle; EXTRA_MODS=(testmod); DATAPACK=none; BUNDLE_CHUNKS=true; PHASES=("$BUNDLE_COMMAND") ;;
    E9)  DESC="entries the connection cannot send (test mod), compression off: left out"; KIND=undeliverable
         EXTRA_MODS=(testmod); DATAPACK=none; COMP=-1
         PHASES=("cmd:rbstest huge Tester 3000000 false;rbstest bundlehuge Tester 3000000 false") ;;
    E9b) DESC="entries the connection cannot send (test mod), compression 256: the exact limits"; KIND=undeliverable
         EXTRA_MODS=(testmod); DATAPACK=none
         PHASES=("cmd:rbstest huge Tester 3000000 true;rbstest huge Tester 4500000 false;rbstest huge Tester 9000000 false") ;;
    E9s) DESC="as E9 with undeliverableEntries=send: the client is disconnected as without the mod"; KIND=undeliverable
         EXTRA_MODS=(testmod); DATAPACK=none; COMP=-1; UNDELIVERABLE=send
         PHASES=("cmd:rbstest huge Tester 3000000 false") ;;

    L2)  DESC="E2 (compression off) behind Velocity, which sends raw frames to the client (compression-threshold -1): chunks at the ceiling"
         COMP=-1; PROXY=1; VCOMP=-1 ;;
    L3)  DESC="incompressible payload (42 entries of about 261 KB, four of them fill a chunk to within 0.5 % of the ceiling 1,048,576), compression 256"
         PACK_ARGS=("${incompressible[@]}") ;;
    L4)  DESC="L3 data, backend compression off, behind Velocity with compression-threshold 256"; COMP=-1
         PROXY=1; VCOMP=256; PACK_ARGS=("${incompressible[@]}") ;;
    L5)  DESC="maxChunkBytes 262,144 (the floor): many chunks"; MAX_CHUNK=262144 ;;
    L6)  DESC="config smoke: maxChunkBytes 4,000,000 is clamped to the ceiling 1,048,576"; CFG='{"maxChunkBytes": 4000000}'
         MAX_CHUNK=1048576; PHASES=(give); CONFIG_CHECK=clamp-max ;;

    P0)  polymer_preset; DESC="baseline: Polymer items in recipes, no Recipe Book Splitter, compression 256"; KIND=baseline; RBS=0 ;;
    P1)  polymer_preset; DESC="mod + Polymer items in recipes, compression 256, 1 MiB budget" ;;
    P1b) polymer_preset; DESC="as P1, compression off"; COMP=-1 ;;
    P2)  polymer_preset; DESC="as P1 with maxChunkBytes 262,144"; MAX_CHUNK=262144 ;;
    P3)  polymer_preset; DESC="as P2 plus 'fat' recipes (four large-tag ingredients) that are over the budget on the wire only because of Polymer"
         MAX_CHUNK=262144; PACK_ARGS=(--tagged 300 --direct 1200 --fat 6 --fat-tags 4) ;;
    P4)  polymer_preset; DESC="as P1 with player-bound items (the client-side stack depends on the player)"; POLYTEST_BOUND=2 ;;
    P4v) polymer_preset; DESC="as P4 with verifyEncodeOnce: the measured bytes of context-dependent encodings equal a normal encode"
         POLYTEST_BOUND=2; JVM_ARGS=(-Drecipebooksplitter.verifyEncodeOnce=true); ENCODE_ONCE=verified ;;
    P5)  polymer_preset; DESC="as P4 with compression off (raw frames), at the ceiling 1,048,576: a bare-codec size estimate would be too small"
         COMP=-1; POLYTEST_BOUND=2 ;;
    P6)  polymer_preset; DESC="as P1, but phase 3 is a relog followed by /reload while connected (the reload resends the recipe book)"
         PHASES=(give "cmd:polytest measure Tester" reload) ;;
    P7)  polymer_preset; DESC="as P1 with Polymer's own count-based splitter enabled (split_recipe_book_packet_amount 500) in front of this mod"
         POLYMER_SPLIT=500 ;;
    PV)  polymer_preset; DESC="vanilla twin of the P1 pack (same recipes and tags, vanilla items): the size without Polymer's rewriting"
         VANILLA=1 ;;
    PR1) polymer_preset; DESC="mod + the real Polymer-based mod PolyDecorations 0.10.4, 1 MiB budget"
         EXTRA_MODS=(polytest polydecorations); DATAPACK=none ;;
    PR2) polymer_preset; DESC="mod + the real Polymer-based mod PolyFactory 0.10.4, 1 MiB budget"
         EXTRA_MODS=(polytest polyfactory); DATAPACK=none ;;
    PR2v) polymer_preset; DESC="as PR2 with verifyEncodeOnce (PolyFactory's context-dependent sizes)"
         EXTRA_MODS=(polytest polyfactory); DATAPACK=none; JVM_ARGS=(-Drecipebooksplitter.verifyEncodeOnce=true); ENCODE_ONCE=verified ;;
    PR3) polymer_preset; DESC="mod + PolyDecorations and PolyFactory together, maxChunkBytes 262,144"
         EXTRA_MODS=(polytest polydecorations polyfactory); DATAPACK=none; MAX_CHUNK=262144 ;;
    PL1) polymer_preset; DESC="as P1, but phase 3 is a relog followed by a client language change (Polymer resends the recipe book)"
         PHASES=(give "cmd:polytest measure Tester" locale:de_de) ;;
    PL2) polymer_preset; DESC="as PR2 (PolyFactory), phase 3 a language change: does the de_de book differ from the en_us one?"
         EXTRA_MODS=(polytest polyfactory); DATAPACK=none; PHASES=(give "cmd:polytest measure Tester" locale:de_de) ;;
    PX1)  polymer_via_preset; DESC="Polymer items in recipes + ViaFabric, compression 256, 26.1 client (the P1 pack under translation)" ;;
    PX1b) polymer_via_preset; DESC="as PX1 with a 26.2 client (two translation steps)"; NEWER_PROTOCOL=776 ;;
    PX2)  polymer_via_preset; DESC="as PX1, compression off"; COMP=-1 ;;
    PX2b) polymer_via_preset; DESC="as PX2 with a 26.2 client (two translation steps)"; COMP=-1; NEWER_PROTOCOL=776 ;;
    PE6)  polymer_preset; DESC="P1 (Polymer items in recipes) behind Velocity 4.2.0 + FabricProxy-Lite, backend compression 256"; PROXY=1 ;;
    PE6b) polymer_preset; DESC="as PE6, backend compression off"; PROXY=1; COMP=-1 ;;
    PE6c) polymer_preset; DESC="as PE6b with Velocity sending raw frames to the client (compression-threshold -1), like L2 with the Polymer book"
          PROXY=1; COMP=-1; VCOMP=-1 ;;

    VB0)  via_preset; DESC="baseline: ViaFabric, no mod, compression 256, 26.1 client"; KIND=baseline; RBS=0; PHASES=(newer/give newer/relog) ;;
    VB0b) via_preset; DESC="baseline: ViaFabric, no mod, compression off, 26.1 client"; KIND=baseline; RBS=0; COMP=-1
          PHASES=(newer/give newer/relog) ;;
    V1)   via_preset; DESC="mod + ViaFabric + Polymer, compression 256, default budget, 26.1 client" ;;
    V1b)  via_preset; DESC="as V1 with a 26.2 client (two translation steps)"; NEWER_PROTOCOL=776 ;;
    V2)   via_preset; DESC="mod + ViaFabric + Polymer, compression off, default budget, 26.1 client"; COMP=-1 ;;
    V2b)  via_preset; DESC="as V2 with a 26.2 client (two translation steps)"; COMP=-1; NEWER_PROTOCOL=776 ;;
    V5)   via_preset; DESC="mod + ViaFabric, no Polymer, compression 256, default budget, 26.1 client"; POLYMER_MOD=0 ;;
    VC)   via_preset; DESC="V1 with bundleChunks: the chunks of each book arrive in one bundle, translated by ViaVersion"; BUNDLE_CHUNKS=true ;;
    VW1)  via_preset; DESC="worst case for translation growth: 59,400 recipes of only the 27 items whose id grows by a VarInt byte in the 26.1 to 26.2 translation; 26.2 client, compression off, the ceiling 1,048,576: must not disconnect"
          DATAPACK=items-worst; PACK_ARGS=(--only "$E/growth-items-26.2.txt" --repeat 2200); COMP=-1; NEWER_PROTOCOL=776 ;;
    VW2)  via_preset; DESC="VW1 with a 1.0.0 config file (maxChunkBytes 2,000,000, the 1.0.0 maximum, where 1.0.0 disconnected this client): clamped to 1,048,576, must not disconnect"
          DATAPACK=items-worst; PACK_ARGS=(--only "$E/growth-items-26.2.txt" --repeat 2200); COMP=-1; NEWER_PROTOCOL=776; LOG_OVERSIZED=false
          CFG='{"maxChunkBytes": 2000000, "logSplits": true, "logOversizedPackets": false}'; MAX_CHUNK=1048576; CONFIG_CHECK=upgrade ;;
    VW3)  via_preset; DESC="worst case with direct item lists: 5,994 recipes whose every slot is a direct list of the 27 growth items (+63 % under translation); 26.2 client, compression off, the ceiling 1,048,576: must not disconnect"
          DATAPACK=items-worst; PACK_ARGS=(--only "$E/growth-items-26.2.txt" --lists --repeat 222); COMP=-1; NEWER_PROTOCOL=776 ;;

    X1)  perf_preset; DESC="latency: ten take/give cycles of the whole book while another connection is pinged every 10 ms, one event-loop thread, compression 256" ;;
    X1b) perf_preset; DESC="as X1, compression off"; COMP=-1 ;;
    X1c) perf_preset; DESC="as X1 with bundleChunks"; BUNDLE_CHUNKS=true ;;
    *) return 1 ;;
  esac
}

all_ids() { echo "${CORE_SCENARIOS[*]} ${POLYMER_SCENARIOS[*]} ${VIA_SCENARIOS[*]} ${PERF_SCENARIOS[*]}"; }

testmod_jar() {
  if [ -n "${TESTMOD_JAR:-}" ]; then echo "$TESTMOD_JAR"; return; fi
  ls -t "$E"/testmod/build/libs/rbs-e2e-testmod-*.jar 2>/dev/null | grep -v -e '-sources' | head -1
}

polytest_jar() {
  if [ -n "${POLYTEST_JAR:-}" ]; then echo "$POLYTEST_JAR"; return; fi
  ls -t "$E"/polytest-mod/build/libs/rbs-polytest-*.jar 2>/dev/null | grep -v -e '-sources' | head -1
}

has_extra() {
  local m
  for m in ${EXTRA_MODS[@]+"${EXTRA_MODS[@]}"}; do [ "$m" = "$1" ] && return 0; done
  return 1
}

json_array() { python3 -I -c 'import json, sys; print(json.dumps(sys.argv[1:]))' ${1+"$@"}; }

write_scenario_json() {
  NAME=$NAME DESC=$DESC KIND=$KIND CHECKER=$CHECKER RBS=$RBS FABRIC_API_MOD=$FABRIC_API_MOD POLYMER_MOD=$POLYMER_MOD PROXY=$PROXY \
    COMP=$COMP VCOMP=$VCOMP MAX_CHUNK=$MAX_CHUNK LOG_OVERSIZED=$LOG_OVERSIZED UNDELIVERABLE=$UNDELIVERABLE BUNDLE_CHUNKS=$BUNDLE_CHUNKS \
    PHASES_JSON=$(json_array "${PHASES[@]}") EXTRA_JSON=$(json_array ${EXTRA_MODS[@]+"${EXTRA_MODS[@]}"}) JVM_JSON=$(json_array ${JVM_ARGS[@]+"${JVM_ARGS[@]}"}) \
    CONFIG_CHECK=$CONFIG_CHECK HUGE=$HUGE ENCODE_ONCE=$ENCODE_ONCE DIGEST=$DIGEST PROBE=$PROBE NEWER_PROTOCOL=$NEWER_PROTOCOL \
    POLYMER_SPLIT=$POLYMER_SPLIT POLYTEST_BOUND=$POLYTEST_BOUND VANILLA=$VANILLA DATAPACK=$DATAPACK LOADER=$E2E_LOADER \
    JAVA_VERSION=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1) \
    python3 -I -c '
import json, os
env = os.environ
extra = json.loads(env["EXTRA_JSON"])
print(json.dumps({
    "name": env["NAME"], "description": env["DESC"], "kind": env["KIND"], "checker": env["CHECKER"], "rbs": env["RBS"] == "1",
    "fabric_api": env["FABRIC_API_MOD"] == "1", "polymer": env["POLYMER_MOD"] == "1", "proxy": env["PROXY"] == "1",
    "compression": int(env["COMP"]), "velocity_compression": int(env["VCOMP"]) if env["VCOMP"] else None,
    "max_chunk_bytes": int(env["MAX_CHUNK"]), "log_oversized": env["LOG_OVERSIZED"] == "true",
    "undeliverable_entries": env["UNDELIVERABLE"], "bundle_chunks": env["BUNDLE_CHUNKS"] == "true",
    "phases": json.loads(env["PHASES_JSON"]), "extra_mods": extra, "jvm_args": json.loads(env["JVM_JSON"]),
    "config_check": env["CONFIG_CHECK"], "huge_entry_bytes": int(env["HUGE"]), "encode_once": env["ENCODE_ONCE"],
    "digest": env["DIGEST"] == "true", "probe": env["PROBE"] == "1", "newer_protocol": int(env["NEWER_PROTOCOL"]),
    "polymer_split": int(env["POLYMER_SPLIT"]), "polytest_items": 400, "polytest_bound": int(env["POLYTEST_BOUND"]),
    "vanilla_twin": env["VANILLA"] == "1", "real_mods": [m for m in extra if m in ("polydecorations", "polyfactory")],
    "datapack": env["DATAPACK"], "loader": env["LOADER"], "java": env["JAVA_VERSION"],
}, indent=2))' > "$W/scenario.json"
}

# start_client <phase> <protocol or empty or "newer"> <seconds> [client.py arguments...]: runs in the background (CLIENT_PID)
start_client() {
  local n=$1 proto=$2 seconds=$3
  shift 3
  [ "$proto" != newer ] || proto=$NEWER_PROTOCOL
  python3 -I "$E/client.py" --protocol "${proto:-774}" --port "$CLIENT_PORT" --name Tester --seconds "$seconds" \
      --out "$W/phase$n.json" "$@" > "$W/phase$n.txt" 2>&1 &
  CLIENT_PID=$!
}

# One phase = one login of Tester. Spec: [<protocol|newer>/]<action>[:<argument>]; protocol 774 is the default.
#   give            30 s   +3 s after the login: recipe give Tester *
#   give_take       32 s   +3 s give, +15 s later recipe take Tester *
#   relog           20 s   nothing: the initial recipe book is sent at the join
#   reload          40 s   +8 s reload
#   cmd:<c1>;<c2>   21+6n  +6 s after the login the console commands, 6 s apart
#   locale:<lang>   40 s   the client announces <lang> 8 s after it entered the play phase
#   give_cycles:<n> 12+12n n times: recipe take, +3 s, recipe give, +9 s
run_phase() {
  local n=$1 spec=$2 proto= action arg=
  if [[ $spec =~ ^([0-9]+|newer)/(.*)$ ]]; then
    proto=${BASH_REMATCH[1]}
    spec=${BASH_REMATCH[2]}
  fi
  action=${spec%%:*}
  [ "$action" = "$spec" ] || arg=${spec#*:}
  local cmds cmd i
  case $action in
    give)
      start_client "$n" "$proto" 30
      wait_for "$LOGIN_RE" "$W/server.log" "$n" 60 && sleep 3
      console "recipe give Tester *" ;;
    give_take)
      start_client "$n" "$proto" 32
      wait_for "$LOGIN_RE" "$W/server.log" "$n" 60 && sleep 3
      console "recipe give Tester *"
      sleep 15
      console "recipe take Tester *" ;;
    relog)
      start_client "$n" "$proto" 20 ;;
    reload)
      start_client "$n" "$proto" 40
      wait_for "$LOGIN_RE" "$W/server.log" "$n" 60 && sleep 8
      console "reload" ;;
    cmd)
      IFS=';' read -ra cmds <<< "$arg"
      start_client "$n" "$proto" $((21 + 6 * ${#cmds[@]}))
      wait_for "$LOGIN_RE" "$W/server.log" "$n" 60 && sleep 6
      for cmd in "${cmds[@]}"; do
        console "$cmd"
        sleep 6
      done ;;
    locale)
      start_client "$n" "$proto" 40 --switch-locale "$arg" --switch-after 8 ;;
    give_cycles)
      start_client "$n" "$proto" $((12 + 12 * arg))
      wait_for "$LOGIN_RE" "$W/server.log" "$n" 60
      for ((i = 0; i < arg; i++)); do
        console "recipe take Tester *"
        sleep 3
        console "recipe give Tester *"
        sleep 9
      done ;;
    *) die "unknown phase action '$action' in '$2'" ;;
  esac
  wait "$CLIENT_PID"
  CLIENT_PID=
  sleep 2
}

# PROBE=1: another player who only pings the server every 10 ms for the whole run; the checker reads its round trips.
start_probe() {
  python3 -I "$E/client.py" --protocol 774 --port "$CLIENT_PORT" --name Prober --seconds 7200 --ping-interval-ms 10 \
      --out "$W/probe.json" > "$W/probe.txt" 2>&1 &
  PROBE_PID=$!
  wait_for 'Prober\[.*\] logged in' "$W/server.log" 1 60 "$PROBE_PID" || die "the prober could not join, see $W/probe.txt"
}

stop_probe() {
  [ -n "${PROBE_PID:-}" ] || return 0
  kill -TERM "$PROBE_PID" 2>/dev/null   # the client writes its result when it is told to stop
  wait_exit "$PROBE_PID" 20 || kill -9 "$PROBE_PID" 2>/dev/null
  PROBE_PID=
}

run_scenario() {
  trap cleanup EXIT
  NAME=$1
  define_scenario "$NAME" || die "unknown scenario '$NAME'; known: $(all_ids)"
  W=$E2E_WORK/$NAME
  S=$W/server
  CLIENT_PORT=$BACKEND_PORT
  [ "$PROXY" = 1 ] && CLIENT_PORT=$PROXY_PORT
  SERVER_PID= VELOCITY_PID= CLIENT_PID= PROBE_PID=
  echo "== $NAME: $DESC"

  local fetch_targets=(server mods)
  [ "$PROXY" = 1 ] && fetch_targets+=(velocity)
  if has_extra viafabric && [ -z "${VIAFABRIC_JAR:-}" ]; then fetch_targets+=(viafabric); fi
  if has_extra polydecorations || has_extra polyfactory; then fetch_targets+=(polymer-real); fi
  "$E/fetch.sh" "${fetch_targets[@]}" || die "download failed"
  [ "$PROXY" = 1 ] && find_velocity_java

  local jars=() jar= rbs=
  [ "$FABRIC_API_MOD" = 1 ] && jars+=("$E2E_CACHE/$FABRIC_API")
  [ "$POLYMER_MOD" = 1 ] && jars+=("$E2E_CACHE/$POLYMER")
  [ "$PROXY" = 1 ] && jars+=("$E2E_CACHE/$FPL")
  if has_extra testmod; then
    jar=$(testmod_jar)
    [ -n "$jar" ] || die "no test mod jar; build it with: ./gradlew -p e2e/testmod build --no-daemon (or set TESTMOD_JAR)"
    jars+=("$jar")
  fi
  if has_extra polytest; then
    jar=$(polytest_jar)
    [ -n "$jar" ] || die "no polytest jar; build it with: ./gradlew -p e2e/polytest-mod build --no-daemon (or set POLYTEST_JAR)"
    jars+=("$jar")
  fi
  if has_extra viafabric; then
    jar=${VIAFABRIC_JAR:-$E2E_CACHE/$VIAFABRIC}
    [ -f "$jar" ] || die "VIAFABRIC_JAR does not exist: $jar"
    jars+=("$jar")
  fi
  has_extra polydecorations && jars+=("$E2E_CACHE/$POLYDECORATIONS")
  has_extra polyfactory && jars+=("$E2E_CACHE/$POLYFACTORY")
  if [ "$RBS" = 1 ]; then
    rbs=$(rbs_jar)
    [ -n "$rbs" ] || die "no mod jar in $REPO/build/libs; run ./gradlew build (or set RBS_JAR)"
    jars+=("$rbs")
  fi

  rm -rf "$W"
  mkdir -p "$W"
  make_server "$S" "${jars[@]}"
  [ -z "$rbs" ] || sha256sum "$rbs" > "$W/mod-jar.sha256"
  write_properties "$S" "$COMP"
  # The Prober only stands there for the whole run; without this a monster would kill it (seen once: a slime).
  [ "$KIND" != perf ] || echo "difficulty=peaceful" >> "$S/server.properties"
  if [ "$RBS" = 1 ]; then
    case $CFG in
      none) ;;
      default) write_config "$S" "$MAX_CHUNK" "$LOG_OVERSIZED" "$UNDELIVERABLE" "$BUNDLE_CHUNKS" ;;
      *) printf '%s' "$CFG" > "$S/config/recipebooksplitter.json"
         cp "$S/config/recipebooksplitter.json" "$W/config.initial" ;;
    esac
  fi
  if [ "$POLYMER_SPLIT" -gt 0 ]; then
    mkdir -p "$S/config/polymer"
    printf '{\n  "split_recipe_book_packet_amount": %s\n}\n' "$POLYMER_SPLIT" > "$S/config/polymer/server.json"
  fi

  case $DATAPACK in
    e2e)
      local pack_args=(--out "$S/world/datapacks/rbs_e2e" --count 3000 --pad 3000)
      [ "$HUGE" -gt 0 ] && pack_args+=(--huge-entry-bytes "$HUGE")
      python3 -I "$E/gen_datapack.py" "${pack_args[@]}" ${PACK_ARGS[@]+"${PACK_ARGS[@]}"} > "$W/gen_pack.log" 2>&1 || die "data pack generation failed, see $W/gen_pack.log" ;;
    polytest)
      local twin=()
      [ "$VANILLA" = 1 ] && twin=(--vanilla)
      python3 -I "$E/gen_polytest_pack.py" --out "$S/world/datapacks/polypack" ${twin[@]+"${twin[@]}"} ${PACK_ARGS[@]+"${PACK_ARGS[@]}"} > "$W/gen_pack.log" 2>&1 \
        || die "data pack generation failed, see $W/gen_pack.log" ;;
    items-worst)
      python3 -I "$E/gen_items_datapack.py" --out "$S/world/datapacks/rbs_items" ${PACK_ARGS[@]+"${PACK_ARGS[@]}"} > "$W/gen_pack.log" 2>&1 \
        || die "data pack generation failed, see $W/gen_pack.log" ;;
    none) ;;
    *) die "unknown data pack '$DATAPACK'" ;;
  esac
  write_scenario_json || die "could not write scenario.json"

  local jvm=(${JVM_ARGS[@]+"${JVM_ARGS[@]}"})
  [ "$DIGEST" = true ] && jvm+=(-Drecipebooksplitter.debugDigest=true)
  has_extra polytest && jvm+=(-Dpolytest.items=400 "-Dpolytest.bound=$POLYTEST_BOUND")
  [ "$PROXY" = 1 ] && prepare_velocity "$W" "$S" "$VCOMP"
  start_backend "$W" "$S" "${jvm[@]}"
  [ "$PROXY" = 1 ] && start_velocity "$W"
  [ "$PROBE" = 1 ] && start_probe
  local n=0 spec
  for spec in "${PHASES[@]}"; do
    n=$((n + 1))
    run_phase "$n" "$spec"
  done
  stop_probe
  stop_servers
  python3 -I "$E/$CHECKER" "$NAME"
}

# main
scenarios=("$@")
if [ "${scenarios[0]:-}" = --list ]; then
  for id in $(all_ids); do
    define_scenario "$id" || die "internal: $id is listed but not defined"
    echo "$id: $DESC"
  done
  exit 0
fi
[ ${#scenarios[@]} -gt 0 ] || die "usage: run_e2e.sh <scenario>... | all | polymer | via | perf | --list   (known: $(all_ids))"
expanded=()
for id in "${scenarios[@]}"; do
  case $id in
    all) expanded+=("${CORE_SCENARIOS[@]}") ;;
    polymer) expanded+=("${POLYMER_SCENARIOS[@]}") ;;
    via) expanded+=("${VIA_SCENARIOS[@]}") ;;
    perf) expanded+=("${PERF_SCENARIOS[@]}") ;;
    *) define_scenario "$id" || die "unknown scenario '$id'; known: $(all_ids)"; expanded+=("$id") ;;
  esac
done

failed=()
for scenario in "${expanded[@]}"; do
  (run_scenario "$scenario") || failed+=("$scenario")
done
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}"
  exit 1
fi
echo "all scenarios passed: ${expanded[*]}"

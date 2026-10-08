# End-to-end kit

Runs the built mod on a real Fabric 1.21.11 server and checks what a client actually receives. The unit and
integration tests (`./gradlew build`) cover the logic; this kit covers the real server, the real network stack and
other mods: Polymer, ViaFabric (ViaVersion), Velocity, and with an optional sub-kit a real Minecraft client.

How it works: a generated data pack adds 3000 recipes whose result items carry a 3000-character `custom_data` payload
each. A player who knows all of them gets a recipe book packet of about 9.2 MB, which a vanilla server cannot send
(`Packet too big (is 9227553, should be less than 8388608)`). `client.py` is a minimal protocol client (offline mode)
that logs in, stays connected and records every `recipe_book_add` packet. `check.py` compares that with the digest
lines the mod logs on the server (`-Drecipebooksplitter.debugDigest=true`, which `run_e2e.sh` sets). Other scenarios
replace the data pack with Polymer items, a newer client protocol, bundles sent by a test mod, or measure latency.

## Quick start

```sh
./gradlew build                                  # builds build/libs/recipebooksplitter-<version>.jar
ACCEPT_EULA=true e2e/run_e2e.sh E1               # one scenario, about 70 seconds
ACCEPT_EULA=true e2e/run_e2e.sh all              # every E* and L* scenario
e2e/run_e2e.sh --list                            # every scenario id with its description
```

Running a Minecraft server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA); the kit refuses to
set up the server until `ACCEPT_EULA=true` is set.

Scenarios that use a test mod need it built first (it is a separate Gradle project, built with the repository's
wrapper):

```sh
./gradlew -p e2e/testmod build --no-daemon       # E8*, E9*: e2e/testmod/build/libs/rbs-e2e-testmod-0.0.1.jar
./gradlew -p e2e/polytest-mod build --no-daemon  # P*, PV, PR*, PL*: e2e/polytest-mod/build/libs/rbs-polytest-1.0.0.jar
```

Groups: `all` is every `E*` and `L*` scenario, `polymer` every `P*`, `via` every `V*`, `perf` every `X*`.

Needs: `bash`, `curl`, `python3` (3.8+), JDK 21+ for the server, about 3 GB of free memory, network access for the
downloads, and disk space: about 0.5 GB for the cache and 150-260 MB for each scenario's server directory (measured on
E1, E8, PL1, V1, X1; they stay in `e2e/work` until deleted). The exit status is 0 when every assertion of every named scenario holds.

## Environment

| Variable | Meaning |
|---|---|
| `RBS_JAR` | the mod jar; default `build/libs/recipebooksplitter-<mod_version>.jar`, else the newest jar there |
| `JAVA` | the java binary of the backend server (default `java`) |
| `VELOCITY_JAVA` | a Java 25+ binary for Velocity; default `JAVA` if that is Java 25+, else the pinned Temurin 25 JRE from `fetch.sh` |
| `E2E_LOADER` | Fabric Loader of the server: 0.19.5 (default), 0.19.3, 0.19.0 or 0.18.6 (which refuses the mod) |
| `E2E_BACKEND_PORT`, `E2E_PROXY_PORT`, `E2E_AUX_PORT` | server (25565), Velocity (25577) and the throttle relay of the real-client scenarios (25578). Velocity's first run, which only generates its config, also listens on the proxy port (`--port`), never on its own default 25565. |
| `E2E_WORK` | output directory (default `e2e/work`, git-ignored) |
| `E2E_CACHE` | downloads (default `E2E_WORK/cache`) |
| `E2E_STDIN` | stdin of the processes the kit starts without a console: the server-template run, Velocity's first run, the real client's Gradle and Xvfb. Default `/dev/null`; point it at an empty regular file where `/dev/null` is not usable. The servers themselves read a console FIFO. |
| `E2E_TEMPLATE_LINK=1` | hard-link the server template into each scenario instead of copying it (faster on a small disk; a server that writes into its files then changes the cache) |
| `SERVER_XMX` | heap of the backend server (default 3G) |
| `TESTMOD_JAR`, `POLYTEST_JAR`, `VIAFABRIC_JAR` | use these jars instead of the built or pinned ones |

The shared bash helpers (waiting, server directories, config file, Velocity, console) are in `lib.sh`.

## Scenarios

Unless a row says otherwise: Fabric API 0.141.6 and Polymer 0.15.2 are loaded, the protocol client speaks 1.21.11
(protocol 774), the server's network compression threshold is 256, the config is the default (1 MiB chunks), and there
are two phases, each with its own login: phase 1 joins and then runs `recipe give Tester *` (a `replace=false` packet
with all recipes); phase 2 relogs (the initial recipe book, `replace=true`, with all recipes). "As E1" means: the client
is not disconnected; every digest line of the server matches one sequence of the client's packets with the digest's
chunk count; `replace` is set only on the first packet of a sequence, as on the original; every packet is at most
`maxChunkBytes` (except single entries the mod reported as too big on their own); the split lines agree with the digest
lines; and, unless the row says otherwise, the split lines say that the measured bytes were reused for every packet
(`measured bytes reused for N of N packets`) and the log has no `encode once is not used` line.

### Core

| ID | Setup | What is expected |
|---|---|---|
| E0, E0b | no Recipe Book Splitter, compression 256 / off | each phase ends in `Packet too big` / `Packet too large` (server log or client text), no digest lines |
| E1 | default | as E1 |
| E1v | E1 with `-Drecipebooksplitter.verifyEncodeOnce=true` | as E1, but `N of N packets verified against a normal encode`, no ERROR, the startup line about verify mode |
| E1o | E1 with `-Drecipebooksplitter.encodeOnce=false` | as E1, but no encode-once suffix on the split lines, the startup line `encode once is off` |
| E2 | compression off | as E1; no frame over 2,097,151 bytes |
| E3, E3b | no Polymer / neither Fabric API nor Polymer | as E1 |
| E4 | phases give, relog, reload | as E1; the `/reload` resends the recipe book (`replace=true`) in chunks too |
| E5 | E1 plus one 4.5 MB recipe entry (synthetic), `logOversizedPackets` | as E1, plus a WARN for the entry that is bigger than the budget on its own (with the note that network compression lets this connection send it) and a WARN for the oversized packet |
| E6, E6b, E6x | behind Velocity 4.2.0 (modern forwarding, FabricProxy-Lite 2.11.0): compression 256 / off / no mod | as E1 / as E1 / as E0 |
| E7a | no config file, one phase | the default config file with the five keys is created; the loaded line shows the defaults |
| E7b | `{"maxChunkBytes": 10}` | WARN that 10 is below the minimum 262144; the budget is 262,144 and respected; the file is not changed |
| E7c | `{"maxChunkBytes": ` (malformed) | ERROR that the config cannot be read; defaults used; the file is not changed |
| E7d | a 1.0.0 file with `maxChunkBytes` 2,000,000 and without the new keys | WARN that 2,000,000 is above the maximum 1048576, with the reasons (the frame limit applies to the packet as sent, ViaVersion growth of 25 % and 63 % measured, 1 MiB fits a growth of up to 99 %); the budget is 1,048,576; an INFO for each missing new key; the file is not changed |
| E7e | `{"undeliverableEntries": "SEND", "bundleChunks": "yes"}` | a WARN for each; the loaded line shows `drop` and `false`; the file is not changed |

### Bundles and entries a connection cannot send (the test mod, `e2e/testmod`)

The test mod adds `/rbstest bundle|huge|bundlehuge`, which send recipe book packets vanilla never sends. These
scenarios have no data pack.

| ID | Setup | What is expected |
|---|---|---|
| E8, E8b | `/rbstest bundle`: a bundle `[remove, add (3,000 entries, 9.2 MB), remove]`, compression 256 / off | one bundle remove, add x N, remove; 3,000 entries; the adds are at most the budget; `replace=false`; the digest has `bundle=true` and matches; the split line says `in a bundle` |
| E8x | E8 without the mod | the client is disconnected with `Packet too big` |
| E8c | the data pack, `bundleChunks` on | the chunks of each split book arrive inside one bundle that holds nothing else; the split line says `in one bundle` |
| E8d | E8c behind Velocity | as E8c |
| E8e | E8 with `bundleChunks` on | as E8: the packet was in a bundle already, so it is split in place and nothing is nested |
| E9 | compression off: `/rbstest huge ... 3000000 false` and `bundlehuge ... 3000000 false` | ERROR `left out` for display ids 2000001 and 3000002 (reason: compression is off, frame limit); the loose packet arrives with its 2 small entries, the bundle as remove, add (1 entry), remove; the client stays connected |
| E9b | compression 256: a 3 MB random, a 4.5 MB zero and a 9 MB zero entry | the 3 MB entry is left out (`compresses to a ...-byte frame`), the 4.5 MB entry is sent alone with a WARN, the 9 MB entry is left out (`over 8,388,608 bytes`); the last five packets are [2], [1], [1 x 4.5 MB], [1], [2] entries |
| E9s | E9 with `undeliverableEntries` `send` | ERROR `sending it anyway`; the client is disconnected (`Packet too large`) |

### Limits

`maxChunkBytes` can be 1,048,576 at most, which is also the default, so these scenarios run at the ceiling and cannot
ask for more: a larger value is clamped (L6, E7d). The scenarios of earlier revisions that ran at 1,500,000 (L1, V3, V3b,
V4, VW3x) can no longer be expressed without a patched jar; their evidence is in the main README.

| ID | Setup | What is expected |
|---|---|---|
| L2 | E2 (the default budget, which is the ceiling, compression off) behind Velocity, which sends raw frames to the client (`compression-threshold -1`) | as E2, seen by the client: no frame over 2,097,151 bytes, no chunk over 1,048,576 |
| L3 | an incompressible payload (42 entries of about 261 KB, four of which fill a chunk to within 0.5 % of 1,048,576), compression 256 | as E1; no frame over 2,097,151 bytes (a chunk of incompressible data makes a frame a few hundred bytes bigger than itself) |
| L4 | L3 data, backend compression off, behind Velocity with `compression-threshold 256` | as E1 |
| L5 | `maxChunkBytes` 262,144 (the minimum) | as E1; the chunk count is printed |
| L6 | config `{"maxChunkBytes": 4000000}` | WARN that it is above the maximum 1048576 (same text as E7d); the budget is 1,048,576 |

All scenarios that do not test the clamp also assert that the mod logged no clamp WARN, so the default is taken as
written.

### Polymer (`check_poly.py`, the test mod `e2e/polytest-mod`)

`e2e/polytest-mod` registers 400 server-side Polymer items `polytest:p000`.. (each shown to the client as a vanilla
item, with a name, lore and for every third item a custom item model), ships a small data pack, and adds the debug
command `/polytest measure <player> [send]`. The command sizes the player's recipe book twice without going through the
connection: `bare` is `Entry.STREAM_CODEC.encode` on a fresh buffer outside any packet-tweaker `PacketContext`, `ctx` is
the same inside `PacketContext.supplyWithContext` for that player. It logs both totals and SHA-256 digests (computed
like the mod's debug digest) and writes the per-entry sizes to `polytest-measure-<n>.json`; with `send` it then sends
that exact entry list as one `replace=true` packet. `gen_polytest_pack.py` generates the large data pack: 300 recipes
with a tag of 150 Polymer items as one ingredient, 1,200 recipes with direct Polymer items (shapeless, shaped, item
lists, smelting), optionally "fat" recipes with several ingredients from a 300-item tag (`--fat`, `--fat-tags`), or
`--vanilla` for the same pack with vanilla items.

Phases: give; `/polytest measure Tester`; `/polytest measure Tester send` (P6: `reload` instead; PL1 and PL2: a
language change instead). `check_poly.py` reuses the assertions of `check.py` and adds the comparison of four views of
the same book: bare codec, codec in the player's context, what the mod measured, what the client received.

| ID | Setup | What is expected |
|---|---|---|
| P0 | Polymer items, no Recipe Book Splitter | every phase ends in `Packet too big` |
| P1, P1b | mod + Polymer items, compression 256 / off | the book is split; bare and in-context bytes identical and equal to the digest and to the client's bytes |
| P2 | as P1, `maxChunkBytes` 262,144 | many chunks, none over the budget |
| P3 | as P2 plus 6 "fat" recipes (four large-tag ingredients each) | those entries are over the budget on their own: WARN for each, each sent alone; every other packet within the budget |
| P4 | as P1, every second item player-bound (its client stack depends on the player) | bare bytes differ from in-context bytes; the mod's digest and the client's bytes equal the in-context ones |
| P4v | P4 with `verifyEncodeOnce` | as P4; the measured bytes equal a normal encode (`N of N packets verified`, no ERROR) |
| P5 | as P4, compression off (raw frames), at the ceiling 1,048,576 | no frame over 2,097,151 bytes; every packet within the budget |
| P6 | as P1, phase 3 is a relog plus `/reload` | join and reload each send the full book in chunks |
| P7 | as P1 with Polymer's own `split_recipe_book_packet_amount` set to 500, in front of this mod | Polymer's chunks of 500 entries pass through the mod, which splits the ones over the budget; every packet within the budget; the client gets every recipe once |
| PV | the P1 pack with vanilla items (the "vanilla twin") | the size of the book without Polymer's rewriting, for comparison |
| PR1 | mod + the real Polymer-based mod PolyDecorations 0.10.4 | bare vs in-context bytes are reported, not asserted |
| PR2, PR2v | mod + PolyFactory 0.10.4 (PR2v with `verifyEncodeOnce`) | as PR1; PR2v: no mismatch |
| PR3 | both, `maxChunkBytes` 262,144 | as PR1 |
| PL1 | as P1, phase 3: the client announces another language (`de_de`) 8 s after joining | Polymer resends the recipe book: two complete `replace=true` books in phase 3, both matched against the client |
| PL2 | as PR2, phase 3 as in PL1 | as PL1; reports whether the `de_de` book differs from the `en_us` one |

### ViaFabric (`check_via.py`)

ViaFabric 0.4.21+166 (it bundles ViaVersion 5.10.0) lets a client of a newer protocol join the 1.21.11 server: 775 is
26.1, 776 is 26.2. The protocol client speaks the newer protocol (`newer/...` phases); a 774 client in the same server
run is the control. Phases: `774/give_take`, `774/give_take`, `newer/give`, `774/relog`, `newer/relog`. Compared: the
data size of every translated chunk against its control chunk, and every frame against the 2,097,151-byte limit. The
growth by translation is printed per scenario.

| ID | Setup | What is expected |
|---|---|---|
| VB0, VB0b | no Recipe Book Splitter, compression 256 / off, 26.1 client | the client is disconnected |
| V1 | mod + ViaFabric + Polymer, compression 256, 26.1 client | as E1 for both clients; same chunk counts, entry counts and replace flags as the control |
| V1b | V1 with a 26.2 client (two translation steps) | as V1 |
| V2 | compression off | as V1 |
| V2b | V2 with a 26.2 client | as V1 |
| V5 | no Polymer | as V1 |
| VC | V1 with `bundleChunks` | as V1; the chunks of each book arrive in one bundle, translated or not |
| VW1 | 59,400 recipes of only the 27 items whose id grows by a VarInt byte in the 26.1 to 26.2 translation (`growth-items-26.2.txt`), 26.2 client, compression off, the ceiling 1,048,576 | the client is **not** disconnected; the largest translated frame is below 2,097,151 bytes; translation grew the book by at least 20 % (+25.3 % with 1.0.0 at 2,000,000, which disconnected the client) |
| VW2 | VW1 with a 1.0.0 config file (`maxChunkBytes` 2,000,000, the 1.0.0 maximum) | the WARN of E7d and an INFO for each missing key; the budget is 1,048,576; as VW1 |
| VW3 | 5,994 recipes whose every slot is a direct list of the same 27 items (`gen_items_datapack.py --lists`; about +63 % under translation), 26.2 client, compression off, the ceiling 1,048,576, with the 774 control clients of the other ViaFabric scenarios | the client is **not** disconnected; the largest translated frame is below 2,097,151 bytes (1,706,228 in the review run); translation grew the book by at least 20 % |

The "at least 20 %" assertion of VW1 to VW3 keeps them worst cases: if a translation stopped growing these books, the
scenarios would pass without testing the ceiling. The same books disconnected the 26.2 client at budgets of 2,000,000
(VW1's book, 1.0.0) and 1,500,000 (VW3's book, review run); the kit can no longer run them there.

`VIAFABRIC_JAR` replaces the pinned jar. ViaFabric 0.4.21+166 is the newest 1.21.11 build that starts: 0.4.21+168 and
all later ones up to 0.4.22+184 crash at startup on Java 21 and 25 (`NoSuchMethodError ...J_L_Runtime$Version.feature`, an
empty stub class inside the ViaFabric jar; see the main README).

### Latency (`KIND=perf`)

| ID | Setup | What is expected |
|---|---|---|
| X1 | ten times: `recipe take Tester *`, `recipe give Tester *`, while a second player, the Prober, pings the server every 10 ms; compression 256; phases give_cycles:10 and relog | nobody is disconnected; ten complete books within the budget; at least 90 % of the pings answered |
| X1b | X1 with compression off | as X1 |
| X1c | X1 with `bundleChunks` | as X1 |

The Prober sends a `ping_request` (answered on the server's Netty thread) every 10 ms, and `-Dio.netty.eventLoopThreads=1`
makes all connections share one event-loop thread, so its round trips show how long that thread was busy. Besides the
assertions the checker prints the Prober's slowest ping within 5 s after each give (median, p90, max) and the mod's
measure and write times. With ten gives the p90 is the largest value, which is the first give after the start (a cold
JVM, usually 100 ms or more above the others), so the checker prints that give apart from the other nine. There are no
timing thresholds: compare jars by running the scenario with each (`RBS_JAR`), and compare the warm gives and the first
one separately.
With the 1.0.0 jar the new config keys give two harmless `unknown config key` WARNs, and the split lines carry no write
time.

## Files

| File | Purpose |
|---|---|
| `run_e2e.sh <scenario>...` | Builds the server directory for a scenario, starts the server (and Velocity), runs the phases, stops everything and runs the scenario's checker. The scenario catalogue is `define_scenario` in this file. |
| `lib.sh` | Shared bash helpers (also used by `fetch.sh` and `realclient/run_client_e2e.sh`). |
| `fetch.sh [server] [mods] [velocity] [jre25] [viafabric] [polymer-real]` | Downloads the pinned third-party files into the cache (SHA-256 verified) and prepares a server template for `E2E_LOADER`, so Minecraft is downloaded and remapped only once; the Loader jars the launcher downloads for the template are checked against pins too. `run_e2e.sh` calls it. |
| `client.py` | The protocol client: `--protocol` 774/775/776/777, bundle tracking, `--locale`, `--switch-locale`, `--ping-interval-ms`. Writes `phaseN.json` (per-packet frame and data sizes, entry counts, replace flags, bundle index, runs with SHA-256 over the entry bytes, packet sequence, disconnect reason, size violations) and `phaseN.json.bin` (the entry bytes). |
| `check.py <scenario>` | The assertions for the core, bundle, undeliverable, limit and latency scenarios; the library of the other checkers. Reads `work/<scenario>`. |
| `check_poly.py`, `check_via.py` | The assertions for the Polymer and ViaFabric scenarios. |
| `gen_datapack.py` | The 9.2 MB data pack. `--count`, `--pad`, `--seed`, `--mode alnum\|compressible\|incompressible`, `--grow K:D`, `--huge-entry-bytes N`. |
| `gen_polytest_pack.py`, `vanilla_items.txt` | The Polymer data pack and the item list of its vanilla twin. |
| `gen_items_datapack.py`, `growth-items-26.2.txt` | One 3x3 recipe per item, for the ViaFabric growth scenarios; `--lists` puts the whole item list into every slot. |
| `patch_velocity.py` | Patches the generated `velocity.toml` (loopback, offline mode, modern forwarding, one backend, optional compression threshold). |
| `throttle.py` | A TCP relay that limits the server-to-client bandwidth (real-client scenarios H and I). |
| `testmod/`, `polytest-mod/` | The two test mods (Loom projects, built as above; never install them on a real server). |
| `realclient/` | The optional real-client harness, see below. |

## Real client

`realclient/` runs a real, unmodified Minecraft 1.21.11 client (Xvfb and Mesa software rendering) with a small
client-side mod that times the handling of every recipe book packet and checks the resulting recipe book. It needs
Xvfb and about 700 MB of Gradle downloads on the first run, so it is not part of the scenario groups. See
`realclient/README.md`.

## Downloads

All with a SHA-256 pinned in `fetch.sh`: the Fabric server launcher for 1.21.11 and `E2E_LOADER` (`meta.fabricmc.net`),
Fabric API, Polymer (bundled), FabricProxy-Lite, ViaFabric, PolyDecorations and PolyFactory (Modrinth), Velocity
(`fill-data.papermc.io`), and the Temurin 25 JRE (GitHub, Adoptium).

The launcher is only a small installer that names the Loader version. On the template run it fetches a server JSON from
`meta.fabricmc.net` and downloads the Loader, its mappings, Mixin and ASM from `maven.fabricmc.net` without checking any
hash (it checks only Minecraft's jar). Those eight jars per Loader are therefore pinned in `fetch.sh` as well
(`loader_library_pins`), checked after the template is built and on every later `fetch.sh server`; on a mismatch the
template is deleted and the script stops. They were recorded from a download of every jar; the ASM jars and Mixin also
match the `sha256` fields of the meta server JSON, and the pins of all four Loaders (0.19.5, 0.19.3, 0.19.0, 0.18.6)
passed in a template run through the pinned launcher. The server JSON itself is not pinned: if Fabric changes the
libraries it lists, the template run fails the check.

Not pinned by the kit: Minecraft's own server jar and libraries, which the launcher downloads for the server template
(Loom and the launcher check them against Mojang's manifest checksums); the Gradle artifacts the two test mods resolve
(the same Loom, Minecraft and Fabric artifacts as the main build; `polytest-mod/build.gradle` checks the SHA-256 of the
five Polymer jars it compiles against); and the real client's Minecraft files. The real client's other Gradle
artifacts are pinned by `realclient/gradle/verification-metadata.xml`.

## Output

Everything for a scenario is in `e2e/work/<scenario>/`:

- `server.log`: the server's console output. The mod's lines start with `[RecipeBookSplitter]`; the digest lines
  (`digest player=Tester entries=... sha256=...`) are what the checkers match against the client.
- `velocity.log`: the proxy's console output (Velocity scenarios).
- `phase1.json`, `phase2.json`, ...: the client's results; `phaseN.txt` is its console output. `probe.json` is the
  Prober's (latency scenarios).
- `scenario.json`: the parameters the checker uses; `config.initial`: the config file as it was written before the start;
  `commands.log`: the console commands with their epoch time; `mod-jar.sha256`.
- `server/`: the server directory itself (config, world, mods).

The checker prints one PASS/FAIL line per assertion. To re-check without re-running, call
`python3 e2e/<checker>.py <scenario>` (`check.py`, `check_poly.py` or `check_via.py`, as in `scenario.json`).

## Status

Which jar each result comes from. "1.0.0" is the investigation that preceded 1.1.0, run with earlier copies of the
harnesses that are now in this kit (same scenario ids unless a row says otherwise; the logs are not in the repository).
"Smoke" is one run while this kit was built, with a jar (SHA-256 `9af29acea8255cc3010b13ce5b08f1802dc31956f649703f2d086fa7dc94c0f4`)
built from the main code of commit `0ad829b`, before the review fixes of `d49437f`: JDK 21, Loader 0.19.5, Fabric API
0.141.6, Polymer 0.15.2, the protocol client, and no timing conclusions. Those fixes changed code that every scenario runs
(the check on the codec call that decides whether measured bytes are reused, the clamp WARN text), so a smoke result is not
a result of the current jar and checkers; the E7d cell says so where it matters.

"Ceiling smoke" is one run each of the scenarios named in the cells, made when the ceiling of `maxChunkBytes` was lowered
from 1,500,000 to 1,048,576 (the default): jar SHA-256 `24a82085e4ad49bb3894b25624f854ae943610d368d152d4883a153d6cfc249f`,
`./gradlew build` on JDK 21 from the tree of that change; server on Java 21.0.11, Fabric Loader 0.19.5, Fabric API 0.141.6,
Polymer 0.15.2 (loaded in all of them), ViaFabric 0.4.21+166 for V2b and the VW scenarios (SHA-256
`aa0e19d929913cbb276e7732fed06997d777e2a129580dded7443f2fb0f0327f`, downloaded by `fetch.sh` from its pinned URL),
Velocity 4.2.0 for L2 and L4, the protocol client, ports 25791/25792, no timing conclusions. These runs say that the
changed scenarios and checkers work against the changed jar; they are not the verification phase.

`TBD(verify)` is what the verification phase fills in by running the kit in full; it replaces these cells with the
command, the jar's SHA-256, Loader, Java and the result.

| Scenarios | 1.0.0 jar | 1.1.0 |
|---|---|---|
| E0 to E7c | all passed. Baselines: E0 `Packet too big (is 9227553, should be less than 8388608)`, E0b `Packet too large: size 9227553 is over 8`. With the mod the 9,227,553-byte packet arrived as 9 packets of at most 1,048,576 bytes and their entry bytes hashed to the server's digest (E7b then expected the 65,536 minimum) | smoke (before the review fixes): E0, E1, E1o, E6, E7e pass; E7d passed with an earlier clamp WARN text (ceiling 1,500,000), which the current checker does not accept (the ceiling is 1,048,576 now and the WARN gives the reasons for it), so that result is not one of the current jar and checker. Ceiling smoke: E7a, E7b, E7d (the clamp WARN of the ceiling, 1,048,576, the budget respected) pass. `TBD(verify)`: the whole group on Java 21 / Loader 0.19.5, direct and behind Velocity |
| E1 on other Loaders and Java versions | 26 of 26 assertions on Loader 0.19.0, 0.19.3 and 0.19.5, each on Java 21 and Java 25. Loader 0.18.6 refused the mod | `TBD(verify)`: E1, E1v, E2, E3b, E5, E6, E6b, E8, E8c and E9b on Java 25 / Loader 0.19.5, Java 21 / Loader 0.19.0 and Java 25 / Loader 0.19.0 (MixinExtras 0.5.3: `reused for N of N packets`) |
| E8 to E9s | a prototype of the bundle and undeliverable-entry code (not the 1.0.0 jar) passed E8, E8b, E8x, E9, E9b and E9s | smoke (before the review fixes): E8, E8c, E9b, E9s pass. `TBD(verify)`: the rest, E8d behind Velocity |
| L1 to L6 | the bounds were measured on a real server with the ceiling at 2,000,000 and with a raised ceiling (frame limits to the byte, incompressible chunks, Velocity with `compression-threshold -1`); the L scenarios are adapted from those runs, and L1 (1,500,000, compression off) is E2 now | smoke (before the review fixes): L2 passed at 1,500,000, a budget that is no longer accepted, so it says nothing about the current L2. Ceiling smoke: L2, L3, L4 and L6 pass. L2: largest packet 1,048,544 bytes (frames of the same size, raw). L3: the 42 entries of 261 KB made chunks of four entries, 1,044,191 to 1,044,199 bytes on the give (99.6 % of the ceiling, frames 329 bytes bigger than the data), and 1,048,573 bytes at most on the relog, whose entries come in another order. L4: largest 1,044,199 bytes (frame 1,044,298) on the give and 1,048,537 on the relog. `TBD(verify)`: L2 to L6 in the full run; L5 was not run in the ceiling smoke |
| P0 to P7, PV, PR1 to PR3 | 13 scenarios passed (the kit's PV and PR1 to PR3 were called V1 and R1 to R3), with other parameters than the kit uses now: P2 and PR3 at `maxChunkBytes` 65,536 (the kit: 262,144, the new minimum), P3 with fat recipes of two large tags (the kit: four, `--fat-tags 4`) and P5 at 2,000,000 (the kit: 1,048,576, the ceiling). A mutant of the mod that sizes with the bare codec was run on P1, P4, P5 and PR2 (R2): P1 passed, P4, P5 and PR2 failed; PR3 was only computed | smoke (before the review fixes): PL1 passes. `TBD(verify)`: all of them with the kit's parameters, which have not been run before, with P4v and PR2v (no mismatch) and PL2 |
| VB0 to VW3 | V1, V1b, V2, V3, V3b, V4 and V5 passed on Java 25 (V1 also on Java 21), with budgets of 1,048,576 and 2,000,000 (the kit had V3, V3b and V4 at 1,500,000 instead; they are not in the kit any more, and V2b, the 26.2 client with compression off at the default, is new). The worst case, VW1 at 2,000,000, disconnected the 26.2 client (a 1,999,931-byte chunk became 2,507,176 bytes) | review: the direct-list book of VW3 and of the retired VW3x was run in a scratch copy of the kit (ViaFabric 0.4.21+166, Java 25, Loader 0.19.5): at the default budget, now the ceiling, it was delivered (largest translated frame 1,706,228), at 1,500,000, a budget that is no longer accepted, the client was disconnected; the kit's VW3 checker passes on the first of those outputs. Smoke (before the review fixes): V1 passes. Ceiling smoke: VW1, VW2, VW3 and V2b pass. VW1: the book grew +25.3 % (5,597,058 against 4,468,135 bytes), largest translated chunk 1,318,277 bytes (give) and 1,313,837 (relog), the client stayed connected; VW2 (the 1.0.0 file with 2,000,000, clamped with the WARN): the same sizes, 1,318,277 and 1,313,545. VW3: +61.7 % (7,652,212 against 4,732,811 bytes), largest translated chunk 1,706,228 bytes (give) and 1,696,233 (relog), the client stayed connected. V2b: +323 bytes (9,228,072 against 9,227,749), largest translated frame 1,048,569. `TBD(verify)`: VB0 to VW3 in the full run, with the release jar and on Java 25 |
| X1 to X1c | not run as scenarios (the per-split times were measured with probe timers) | verification (jar `24a82085e4ad...` against the 1.0.0 jar, JDK 21.0.11, Loader 0.19.5; X1 eight runs of each jar, three of the verification lane and five of a rerun in a fresh clone of commit `4b39263`; X1b three runs of each; X1c three runs of the new jar; all passed, no ping unanswered): the slowest ping within 5 s after a give, median of all gives (of the warm gives, those after the first of a run) in ms: X1 533.4 (518.2) with 1.0.0, 479.7 (472.6) with 1.1.0; X1b 143.1 (139.0) and 80.9 (77.6); X1c, 1.1.0 only, 485.4 (477.3). The first give of a run, on a cold JVM, is not faster with compression 256 and not slower either: medians 723.0 (1.0.0) and 697.0 ms (1.1.0) over 8 runs, spread 630 to 910 ms; with compression off it was lower (3 runs). More in the main README under Performance |
| `realclient` A to F | A 3 of 3, B 23 of 23, C 23 of 23, D 13 of 13, E 13 of 13, F 2 of 2 assertions; B and C three times each | verification, six runs each (three in the verification lane, three in a fresh clone of commit `4b39263`): H and I at 8000 and 20000 kbit/s and K passed (digest off: 11 assertions for H, 13 for I and K; digest on: 13, 15, 15, fewer runs), no disconnect, every book of I and K in one frame and one tick; numbers in the main README under `bundleChunks`. `TBD(verify)`: A to G and J |

## What this does not cover

- The protocol client is a recorder, not Minecraft: it does not enforce the 2 MiB NBT quota or build a recipe book.
  What a real client does is covered by `realclient/` (not part of the scenario groups) and by reading its source.
- The kit has no real 26.x client. The investigation ran real 26.1 and 26.2 clients through ViaFabric with a throwaway
  script (joined, split book on give and relog, no disconnect), and did not inspect their recipe books.
- No ViaBackwards (older clients), and no proxy other than Velocity 4.2.0 with FabricProxy-Lite.
- Polymer items are covered for vanilla clients only: no Polymer client mod, no resource pack, and the protocol client's
  default language and client options (except PL1 and PL2, which announce `de_de`).
- No modded client, no operating system other than Linux, and no Java other than 21 and 25.

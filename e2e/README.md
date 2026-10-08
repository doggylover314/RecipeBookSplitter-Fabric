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
| `E2E_BACKEND_PORT`, `E2E_PROXY_PORT`, `E2E_AUX_PORT` | server (25565), Velocity (25577) and the throttle relay of the real-client scenarios (25578) |
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
(`measured bytes reused for N of N packets`).

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
| E7d | a 1.0.0 file with `maxChunkBytes` 2,000,000 and without the new keys | WARN that 2,000,000 is above the maximum 1500000; an INFO for each missing new key; the file is not changed |
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

| ID | Setup | What is expected |
|---|---|---|
| L1 | `maxChunkBytes` 1,500,000 (the maximum), compression off | as E1; no frame over 2,097,151 bytes |
| L2 | L1 behind Velocity, which sends raw frames to the client (`compression-threshold -1`) | as L1, seen by the client |
| L3 | an incompressible payload (42 entries of about 244 KB), 1,500,000, compression 256 | as E1; no frame over 2,097,151 bytes |
| L4 | L3 data, backend compression off, behind Velocity with `compression-threshold 256` | as E1 |
| L5 | `maxChunkBytes` 262,144 (the minimum) | as E1; the chunk count is printed |
| L6 | config `{"maxChunkBytes": 4000000}` | WARN that it is above the maximum 1500000; the budget is 1,500,000 |

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
| P5 | as P4, compression off, `maxChunkBytes` 1,500,000 | no frame over 2,097,151 bytes |
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
| V3, V3b | compression off, `maxChunkBytes` 1,500,000, 26.1 / 26.2 client | as V1 |
| V4 | compression 256, 1,500,000 | as V1 |
| V5 | no Polymer | as V1 |
| VC | V1 with `bundleChunks` | as V1; the chunks of each book arrive in one bundle, translated or not |
| VW1 | 59,400 recipes of only the 27 items whose id grows by a VarInt byte in the 26.1 to 26.2 translation (`growth-items-26.2.txt`), 26.2 client, compression off, 1,500,000 | the client is **not** disconnected; the largest translated frame is below 2,097,151 bytes |
| VW2 | VW1 with the default budget | as VW1 |

`VIAFABRIC_JAR` replaces the pinned jar. ViaFabric builds newer than 0.4.21+166 were not used here.

### Latency (`KIND=perf`)

| ID | Setup | What is expected |
|---|---|---|
| X1 | ten times: `recipe take Tester *`, `recipe give Tester *`, while a second player, the Prober, pings the server every 10 ms; compression 256; phases give_cycles:10 and relog | nobody is disconnected; ten complete books within the budget; at least 90 % of the pings answered |
| X1b | X1 with compression off | as X1 |
| X1c | X1 with `bundleChunks` | as X1 |

The Prober sends a `ping_request` (answered on the server's Netty thread) every 10 ms, and `-Dio.netty.eventLoopThreads=1`
makes all connections share one event-loop thread, so its round trips show how long that thread was busy. Besides the
assertions the checker prints the Prober's slowest ping within 5 s after each give (median, p90, max) and the mod's
measure and write times. There are no timing thresholds: compare jars by running the scenario with each (`RBS_JAR`).
With the 1.0.0 jar the new config keys give two harmless `unknown config key` WARNs, and the split lines carry no write
time.

## Files

| File | Purpose |
|---|---|
| `run_e2e.sh <scenario>...` | Builds the server directory for a scenario, starts the server (and Velocity), runs the phases, stops everything and runs the scenario's checker. The scenario catalogue is `define_scenario` in this file. |
| `lib.sh` | Shared bash helpers (also used by `fetch.sh` and `realclient/run_client_e2e.sh`). |
| `fetch.sh [server] [mods] [velocity] [jre25] [viafabric] [polymer-real]` | Downloads the pinned third-party files into the cache (SHA-256 verified) and prepares a server template for `E2E_LOADER`, so Minecraft is downloaded and remapped only once. `run_e2e.sh` calls it. |
| `client.py` | The protocol client: `--protocol` 774/775/776/777, bundle tracking, `--locale`, `--switch-locale`, `--ping-interval-ms`. Writes `phaseN.json` (per-packet frame and data sizes, entry counts, replace flags, bundle index, runs with SHA-256 over the entry bytes, packet sequence, disconnect reason, size violations) and `phaseN.json.bin` (the entry bytes). |
| `check.py <scenario>` | The assertions for the core, bundle, undeliverable, limit and latency scenarios; the library of the other checkers. Reads `work/<scenario>`. |
| `check_poly.py`, `check_via.py` | The assertions for the Polymer and ViaFabric scenarios. |
| `gen_datapack.py` | The 9.2 MB data pack. `--count`, `--pad`, `--seed`, `--mode alnum\|compressible\|incompressible`, `--grow K:D`, `--huge-entry-bytes N`. |
| `gen_polytest_pack.py`, `vanilla_items.txt` | The Polymer data pack and the item list of its vanilla twin. |
| `gen_items_datapack.py`, `growth-items-26.2.txt` | One 3x3 recipe per item, for the ViaFabric growth scenarios. |
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

All with a SHA-256 pinned in `fetch.sh`: the Fabric server launcher for 1.21.11 and the Loader of `E2E_LOADER`
(`meta.fabricmc.net`), Fabric API, Polymer (bundled), FabricProxy-Lite, ViaFabric, PolyDecorations and PolyFactory
(Modrinth), Velocity (`fill-data.papermc.io`), and the Temurin 25 JRE (GitHub, Adoptium).

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

The kit was reworked for 1.1.0: the scenarios E0-E7c are the ones of 1.0.0 (their results with the 1.0.0 jar are in the
repository history); everything else is new. Smoke runs of the reworked kit against the 1.1.0 jar are recorded in the
commit that introduced it; the full verification runs are done in the verification phase and recorded in the README.

## What this does not cover

- The protocol client is a recorder, not Minecraft: it does not enforce the 2 MiB NBT quota or build a recipe book.
  What a real client does is covered by `realclient/` (not part of the scenario groups) and by reading its source.
- No ViaBackwards, and no proxy other than Velocity 4.2.0 with FabricProxy-Lite.
- Polymer items are covered for vanilla clients only: no Polymer client mod, no resource pack, and the protocol client's
  default language and client options (except PL1 and PL2, which announce `de_de`).
- No modded client.

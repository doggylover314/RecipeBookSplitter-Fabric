# End-to-end kit

Runs the built mod on a real Fabric 1.21.11 server and checks what a client actually receives. The unit and
integration tests (`./gradlew build`) cover the logic; this kit covers the real server, the real network stack and
other mods.

How it works: a generated data pack adds 3000 recipes whose result items carry a 3000-character `custom_data` payload
each. A player who knows all of them gets a recipe book packet of about 9.2 MB, which a vanilla server cannot send
(`Packet too big (is 9227553, should be less than 8388608)`). `client.py` is a minimal protocol client (offline mode,
protocol 774) that logs in, stays connected and records every `recipe_book_add` packet. `check.py` compares that with
the digest lines the mod logs on the server (`-Drecipebooksplitter.debugDigest=true`, which `run_e2e.sh` sets).

## Quick start

```sh
./gradlew build                                  # builds build/libs/recipebooksplitter-<version>.jar
ACCEPT_EULA=true e2e/run_e2e.sh E1               # one scenario, about 70 seconds
ACCEPT_EULA=true e2e/run_e2e.sh all              # every scenario, about 15 minutes
```

Running a Minecraft server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA); the kit refuses to
set up the server until `ACCEPT_EULA=true` is set.

Needs: `bash`, `curl`, `python3` (3.8+), JDK 21+ for the server, about 3 GB of free memory and 1 GB of disk, and
network access for the downloads. Ports 25565 (server) and 25577 (Velocity) must be free; override them with
`E2E_BACKEND_PORT` and `E2E_PROXY_PORT`. The exit status is 0 when every assertion of every named scenario holds.

## Scenarios

Every mod scenario has two phases, each with its own login: phase 1 joins and then runs `recipe give Tester *`
(a `replace=false` packet with all recipes); phase 2 relogs (the initial recipe book, `replace=true`, with all
recipes). "Mods" always include Fabric API 0.141.6.

| ID | Mods | Network compression of the server | What is expected |
|---|---|---|---|
| E0 | Polymer, no Recipe Book Splitter | 256 | Both phases: the client is disconnected, the server logs `Packet too big` |
| E0b | Polymer, no Recipe Book Splitter | off | Both phases: disconnect (`Packet too large` or `Packet too big`) |
| E1 | Polymer + Recipe Book Splitter | 256 | No disconnect; every digest matches one client sequence of 9 packets; `replace` only on the first; every packet at most 1 MiB; split log lines |
| E2 | as E1 | off | as E1; no frame over 2,097,151 bytes |
| E3 | Recipe Book Splitter, no Polymer | 256 | as E1 |
| E4 | as E1 | 256 | as E1, plus a third phase: relog, then `/reload` while connected; the reload resends the recipe book (`replace=true`) in chunks too |
| E5 | as E1, plus one 4.5 MB recipe, `logOversizedPackets` on | 256 | as E1, plus a WARN for the entry that is bigger than the budget on its own and a WARN for the oversized packet. Synthetic: a real client rejects item NBT over 2 MiB, this client does not |
| E6 | as E1 + FabricProxy-Lite 2.11.0, behind Velocity 4.2.0 (modern forwarding) | 256 | as E1, with the client connected to Velocity |
| E6b | as E6 | off | as E6 |
| E6x | Polymer + FabricProxy-Lite behind Velocity, no Recipe Book Splitter | 256 | as E0 |
| E7a | as E1, no config file | 256 | one phase; the default config file is created; split works |
| E7b | as E1, config `{"maxChunkBytes": 10}` | 256 | one phase; WARN that 10 is below the minimum; the budget is 65,536 and respected; the file is not changed |
| E7c | as E1, config `{"maxChunkBytes": ` (malformed) | 256 | one phase; ERROR that the config cannot be read; defaults used; the file is not changed |

Velocity 4.2.0 needs Java 25. The kit uses `VELOCITY_JAVA` if set, then `JAVA` if that is Java 25 or newer, and
otherwise downloads a Temurin 25 JRE into the cache.

## Files

| File | Purpose |
|---|---|
| `run_e2e.sh <scenario>...` | Builds the server directory for a scenario, starts the server (and Velocity), runs the phases, stops everything and runs `check.py`. |
| `fetch.sh [server] [mods] [velocity] [jre25]` | Downloads the pinned third-party files into `work/cache` (SHA-256 verified) and prepares a server template, so Minecraft is downloaded and remapped only once. `run_e2e.sh` calls it. |
| `gen_datapack.py` | Generates the data pack. `--count`, `--pad`, `--seed`, and `--huge-entry-bytes N` for one extra recipe with N bytes of highly compressible data (E5). |
| `client.py` | The protocol client. Writes `phaseN.json` (per-packet frame and data sizes, entry counts, replace flags, runs with SHA-256 over the entry bytes, disconnect reason, size violations) and `phaseN.json.bin` (the entry bytes). |
| `check.py <scenario>` | The assertions. Reads `work/<scenario>`. |
| `patch_velocity.py` | Patches the generated `velocity.toml` (loopback, offline mode, modern forwarding, one backend). |

Downloaded: the Fabric server launcher for 1.21.11 / Loader 0.19.5 from `meta.fabricmc.net`; Fabric API, Polymer
(bundled) and FabricProxy-Lite from Modrinth; Velocity from `fill-data.papermc.io`; the Java 25 JRE from the Adoptium
API (not pinned: it is the latest GA build). Everything except the JRE has a pinned SHA-256 in `fetch.sh`.

Environment variables: `RBS_JAR` (mod jar, default: the newest non-sources jar in `build/libs`), `JAVA`,
`VELOCITY_JAVA`, `E2E_BACKEND_PORT`, `E2E_PROXY_PORT`, `E2E_WORK` (default `e2e/work`, git-ignored), `E2E_CACHE`
(default `E2E_WORK/cache`).

## Output

Everything for a scenario is in `e2e/work/<scenario>/`:

- `server.log`: the server's console output. The mod's lines start with `[RecipeBookSplitter]`; the digest lines
  (`digest player=Tester entries=... sha256=...`) are what `check.py` matches against the client.
- `velocity.log`: the proxy's console output (E6 scenarios).
- `phase1.json`, `phase2.json`, `phase3.json`: the client's results; `phaseN.txt` is its console output.
- `scenario.json`: the parameters `check.py` uses; `config.initial`: the config file as it was written before the start.
- `server/`: the server directory itself (config, world, mods).

`check.py` prints one PASS/FAIL line per assertion. To re-check without re-running, call
`python3 e2e/check.py <scenario>`.

## Status

All scenarios in the table were run against the 1.0.0 jar on Linux with JDK 21 for the server and a Temurin 25 JRE for
Velocity, and all assertions passed. In particular the baselines reproduce the failures (E0: `Packet too big (is
9227553, should be less than 8388608)`, E0b: `Packet too large: size 9227553 is over 8`), and with the mod the same
9,227,553-byte packet arrives as 9 packets of at most 1,048,576 bytes each, and their entry bytes hash to the digest the server logged.

## What this does not cover

- The client is a protocol recorder, not Minecraft: it does not enforce the 2 MiB NBT quota or build a recipe book.
  What a real client does with the packets is covered only by reading its source and by the unit tests.
- No ViaVersion/ViaFabric and no Polymer-based content mod with Polymer items in recipes. Polymer itself is loaded
  in E0, E1, E2, E4, E5 and E6.

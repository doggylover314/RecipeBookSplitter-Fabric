# Real-client harness

An optional part of the e2e kit. It runs a real, unmodified Minecraft 1.21.11 Java client (rendering with Mesa llvmpipe
on an Xvfb display, no GPU, no sound) against the kit's server and checks what the client does with the recipe book
packets. It complements the protocol recorder `../client.py`, which does not build a recipe book and does not enforce
the client's limits.

The client is started through Fabric Loom's `runClient` task with one small client-side mod (`src/`), which depends on
Fabric Loader only (no Fabric API). The mod:

- logs every `ClientboundRecipeBookAddPacket`: decode time on the Netty thread (`PacketDecoder.decode`), entry count,
  replace flag, and on the render thread the time spent in `ClientPacketListener.handleRecipeBookAdd`, split into the
  entry loop, `refreshRecipeBook`, `ClientRecipeBook.rebuildCollections` and `SessionSearchTrees.updateRecipes`, plus
  the number of frames and ticks that had completed (`frame`, `tick`);
- times the background build of the recipe search tree (the lambda that `SessionSearchTrees.updateRecipes` hands to
  `CompletableFuture.supplyAsync`) and counts how many builds actually ran;
- after the recipe book has been quiet for `quietMs` it logs a summary: number of known recipe display ids in the
  `ClientRecipeBook`, highlighted ids, a SHA-256 over the sorted id list, and a SHA-256 over the entries re-encoded with
  `ClientboundRecipeBookAddPacket.Entry.STREAM_CODEC` (the same bytes the mod's `debugDigest` hashes on the server);
- can disconnect and reconnect (`rbs.harness.relogs`) and logs every disconnect reason;
- stops the client when it is done.

All output goes to `<out>/events.jsonl` (one JSON object per line, also logged with the prefix `[RBSH]`).

## Running

```sh
./gradlew build                                   # in the repository root: builds the mod jar
ACCEPT_EULA=true e2e/realclient/run_client_e2e.sh B
```

The script uses the root Gradle wrapper (`gradlew -p e2e/realclient runClient`); this directory has no wrapper of its
own. The first run downloads Minecraft, its libraries and the assets through Loom, about 700 MB in the Gradle cache.

| ID | Setup | What is expected |
|---|---|---|
| A | no Recipe Book Splitter, compression 256 | the client is disconnected (`Packet too big`) |
| B | mod, default config (1 MiB chunks) | give and relog deliver every recipe; ids, entry digests and packet counts agree with the server |
| C | mod, `maxChunkBytes` 262,144 | as B, about 36 chunks per book |
| D | mod behind Velocity + FabricProxy-Lite | as B (`RELOGS`, default 1) |
| E | mod, network compression off | as B |
| F | mod plus one recipe whose entry is 4.5 MB | the client rejects it (`NbtAccounterException`, the 2 MiB NBT quota of the client): documents why the mod does not drop entries because of that quota |
| G | mod with `bundleChunks` | as B; every book is handled in a single frame and tick |
| H | as B over a link limited to `THROTTLE_KBIT` (default 8000) by `../throttle.py` | as B |
| I | H with `bundleChunks` | as G, over the slow link |
| J | as B, then `/reload` while the client stays connected | the reload book (`replace=true`) is complete too |
| K | D with `bundleChunks` | as G, through Velocity |

Each scenario starts a server with the kit's data pack (3000 recipes, 9.2 MB book), starts Xvfb and the client, sends
`recipe give Tester *` on the server console after the client's first summary, then lets the client relog (`RELOGS`,
default 1; scenario J sends `/reload` instead) and runs `analyze.py`. It prints the numbers (render-thread time in
`handleRecipeBookAdd`, background search builds that ran, frames and ticks spanned by each book, time from the first
decode to the last handle) and PASS/FAIL assertions (client recipe count against the entries the server sent, packet
and chunk counts, id-list hash after the give and after every relog, client-side entry digest against the server's
digest, single-frame handling with `bundleChunks`).

Environment variables are listed at the top of `run_client_e2e.sh` and `../lib.sh`. The client needs: `Xvfb`, Mesa's
software rasterizer (`libgl1-mesa-dri` with `swrast_dri.so`, `libglx-mesa0`), about 2 GB of heap, JDK 21 and network
access for the first run. Set `E2E_STDIN` to an empty file where `/dev/null` is not usable: Gradle and Xvfb read it.

## Pinned third-party files

- The Gradle distribution is pinned by `distributionSha256Sum` in the root `gradle/wrapper/gradle-wrapper.properties`.
- Every Gradle-resolved artifact of this project (Loom, Fabric Loader, Mixin, MixinExtras, ...) is pinned by
  `gradle/verification-metadata.xml` (SHA-256). After changing a version, regenerate it with
  `../../gradlew -p e2e/realclient --write-verification-metadata sha256 compileJava generateDLIConfig`.
- Minecraft's client jar, libraries and assets are downloaded by Loom from Mojang and checked by Loom against the SHA-1
  values of Mojang's version manifest; they are not pinned by SHA-256 here.
- The server side (server template, Fabric API, Polymer, FabricProxy-Lite, Velocity, JRE 25) comes from the kit's
  `fetch.sh`, which pins them.

## Status

With the 1.0.0 jar (the investigation that preceded 1.1.0, with an earlier copy of this harness that had scenarios A to F;
Xvfb with Mesa 25.2.8 llvmpipe, OpenGL 4.5, vanilla 1.21.11 client, offline mode; the logs are not in the repository):

- A: the client was disconnected after the give and again when it rejoined (`Packet too big (is 9227553, should be less
  than 8388608)`, rejoin `is 9227717`) and never held more than one recipe.
- B, C, D, E: no disconnect, 4,458 recipes after the give and after each of three relogs, the same hash of the sorted id
  list every time, the client's re-encoded entry bytes equal to the server's digest (about 40 comparisons), packet
  counts equal to the chunk counts (9, or 145 and 143 at 65,536 bytes), `replace=true` only on the first packet. B and
  C ran three times each (23 of 23 assertions), D 13 of 13, E 13 of 13.
- F: `DecoderException: Failed to decode packet 'clientbound/minecraft:recipe_book_add'` caused by `NbtAccounterException:
  Tried to read NBT tag that was too big; tried to allocate: 2043742 + 60000 bytes where max allowed: 2097152`, then a
  disconnect. The 618 entries before the 4.5 MB one had been handled.
- Cost of the handler on the render thread, medians of three runs on a loaded 4-CPU VM (spread 2 to 4 times): 9 chunks
  84.5 ms for the first book of a run and 28.3 ms for later joins, 145 chunks 634.9 ms and 110.9 ms (143 chunks). Of the
  145 background search-tree builds scheduled for the first book, 20 to 23 ran.

With the 1.1.0 jar: `TBD(verify)`: A to K. What has not run at all yet is the frame and tick counter added to the harness
(the `Minecraft.runTick` hook was only compiled and checked against the decompiled source), scenario J (`/reload`), the
throttled scenarios H and I, and the single-frame assertions of G, I and K.

Not covered: Polymer items and ViaFabric with this harness (the investigation drove real 26.1 and 26.2 clients through
ViaFabric with a throwaway script, and did not inspect their recipe books), online mode, a GPU, sound, other operating
systems than Linux.

## Notes

- The mixins use the intermediary name `method_60361` for the synthetic lambda that builds the search tree. It is valid
  for 1.21.11 only; the other targets use Mojang names.
- Timings are wall-clock on a software-rendered client and move with CPU load; compare runs made under similar load
  and prefer the later joins of a run (the first one includes JIT warm-up).
- `run/options.txt` is rewritten before every run (no onboarding screen, render distance 2, 20 fps, sound off).

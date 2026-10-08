# Recipe Book Splitter

A server-side Fabric mod for Minecraft 1.21.11 that splits oversized recipe book packets into several smaller ones, so
that players who know thousands of recipes can join.

How to read the evidence in this file: numbers named "1.0.0" were measured with the 1.0.0 jar by investigation runs that
used the harnesses now in [e2e/](e2e/README.md) (their logs are not in the repository). Numbers named "first
encode-once build" come from the tree of the first 1.1.0 commit (before bundles and undeliverable entries), which was
never released. Numbers from the opt-in benchmark are named as such: it runs the mod's code in a unit-test pipeline, not
a release jar. Numbers named "review" come from checks made while the 1.1.0 code was reviewed (scratch copies of the
repository, the kit's scenarios or unit tests; not repeated with the release jar). Numbers named "verification" come
from the verification runs of 1.1.0 with the release jar `recipebooksplitter-1.1.0.jar` (SHA-256
`24a82085e4ad49bb3894b25624f854ae943610d368d152d4883a153d6cfc249f`, built with `./gradlew build` from the main code of
commit `1b87fb7`; the later commits changed only the e2e kit and the documentation, and a rebuild gives the same
jar). Unless a cell says otherwise, the server ran on JDK 21.0.11 with Fabric Loader 0.19.5, Fabric API 0.141.6 and
Polymer 0.15.2, each scenario was run once, and the machine was a 4-CPU VM that another job shared, so timings carry
noise (the logs are not in the repository). What was not run, or not measured, is listed under
[Verification gaps](#verification-gaps).

## What it does

When a player knows thousands of recipes (a modpack with many custom recipes, recipes with Polymer-backed items, or
`/recipe give @s *`), the server sends their whole recipe book in one `recipe_book_add` packet. With enough recipes that
single packet is too big and the player is disconnected on join or when the recipes are given:

- vanilla with network compression: `Packet too big (is 9227553, should be less than 8388608)`;
- network compression off (common behind a proxy): the 3-byte frame length limit of 2,097,151 bytes,
  `Packet too large: size 9227553 is over 8`.

Recipe Book Splitter replaces such a packet with several smaller `recipe_book_add` packets that together carry the same
entries in the same order with the same flags. Packets that fit are sent as they are.

A real, unmodified 1.21.11 client ended up with the same recipe book (tested with 1.0.0): 4,458 recipes after
`recipe give` and after each of three relogs, the same hash of the recipe id list every time, all 4,458 highlighted as
the server had flagged them, and a hash of the entries it had received that equals the digest the server logged (about
40 comparisons; directly, behind Velocity, with compression off and with 65,536-byte chunks). Without the mod the same
client was disconnected after the give and again when it rejoined. The 1.1.0 jar gave the same result (verification, the
real-client scenarios A to G, J and K): every scenario passed on the first run; 14 repeats with three relogs each (B, C,
D, E, G, J and K, twice each) passed too, and so did B and D on a Java 25 server with Loader 0.19.0 (two runs each), 27
runs in all. With the mod the client got 9 packets per book at 1 MiB (36 at 262,144 bytes, scenario C), 4,457 entries on
the give and 4,458 on every relog, `replace` only on the first packet, the same id-list hash after the give and after
every relog, and entry hashes equal to the server's digests, directly, behind Velocity (D, K), with compression off (E),
with `bundleChunks` (G, K) and after a `/reload` (J). Without the mod (A) the client was disconnected twice with
`Packet too big`. Scenario F (one 4.5 MB entry) is the documented limit: the client's 2 MiB NBT quota rejected it. The
real client scenarios H, I and K measured `bundleChunks` (see [bundleChunks](#bundlechunks)). Toasts were not observed.
By the client code, a split gives one toast with the same items, unless chunks that carry notifications arrive more than
about 5.6 seconds apart.

One kind of entry cannot be helped by splitting: a single recipe display entry that the connection cannot carry even in
a packet of its own. By default the mod leaves such an entry out and logs an ERROR that names it, so the player can
still join (see `undeliverableEntries`).

## Requirements

- Minecraft 1.21.11.
- Fabric Loader 0.19.0 or newer, with Java 21 or newer. The 1.0.0 jar loaded, applied its mixins and split the book on
  Loader 0.19.0, 0.19.3 and 0.19.5 (bundled sponge-mixin 0.17.1, 0.17.3 and 0.17.4), each on OpenJDK 21.0.11 and
  Temurin 25.0.4.1: the same 26 assertions of scenario E1 passed in all six runs. Loader 0.18.6 refuses the mod at
  startup (`requires version 0.19.0 or later of mod 'Fabric Loader'`); the 1.1.0 jar is refused the same way
  (verification: `Mod 'Recipe Book Splitter' (recipebooksplitter) 1.1.0 requires version 0.19.0 or later of mod 'Fabric
  Loader' (fabricloader), but only the wrong version is present: 0.18.6!`, and the same Loader starts a server without
  the mod). The floor is the tested range and not a known incompatibility: a copy of the 1.0.0 jar with the version
  requirement removed also passed E1 on Loader 0.18.6 and 0.17.3 on Java 21.
- 1.1.0 reuses the bytes it measured (see [How it works](#how-it-works)) through a MixinExtras `@WrapOperation`. The mod
  does not declare MixinExtras: it uses the copy Fabric Loader bundles (0.5.3 in Loader 0.19.0, 0.5.5 in 0.19.5). The
  1.1.0 jar ran on both (verification): E1, E1v, E2, E3b, E5, E6, E6b, E8, E8c and E9b passed on Java 25 with Loader
  0.19.5, on Java 21 with Loader 0.19.0 (the log says
  `Initializing MixinExtras via ...MixinExtrasServiceImpl(version=0.5.3)`) and on Java 25 with Loader 0.19.0, 30 runs,
  and every one of their 54 split lines ends in `reused for N of N packets`
  (`N of N packets verified against a normal encode` in E1v), so the wrap is applied with MixinExtras 0.5.3 and on Java
  25. If the wrap cannot be applied (another mod replaced the method or the call), the measuring notices that the hook
  never ran, keeps nothing, logs one INFO line
  (`encode once is not used: the hook around the codec call ... did not run while measuring`) and the packets are
  encoded as 1.0.0 did; the split line then ends in `reused for 0 of N packets`. The checkers of the scenarios that
  expect reuse fail on that line, and none of the verification runs logged it. The measuring side is covered by unit
  tests. The mixin itself was run with its target removed only in a scratch copy during the review (the bytes on the
  wire stayed identical; a simulation, not a real conflicting mod and not a server run, and that copy still kept the
  bytes, which the check now prevents).
- On Java 25 the server log has JVM warnings about `sun.misc.Unsafe::objectFieldOffset` called by JOML. They come from
  Minecraft's bundled JOML, not from this mod.
- Server side only. Nothing changes on clients, on Velocity or on other proxies.
- Fabric API is not required: the mod needs only Fabric Loader (and the MixinExtras it bundles). Scenario E3b ran 1.0.0
  on a server with neither Fabric API nor Polymer, and the 1.1.0 jar alone in `mods/` (verification, Loader 0.19.5 and
  0.19.0, Java 21 and 25) split the book with `reused for 9 of 9 packets`, so encode once works without Fabric API. (The
  unit and integration tests and the other scenarios have Fabric API loaded.)

## Installation

1. Put `recipebooksplitter-<version>.jar` into the `mods/` folder of every Fabric backend server that has the problem.
   Not on Velocity, not on clients.
2. Start the server. The log shows `[RecipeBookSplitter] loaded: ...`, and `config/recipebooksplitter.json` is created
   on the first start.

To build the jar yourself, see [Building from source](#building-from-source).

### Upgrading from 1.0.0

A 1.0.0 config file loads unchanged and is not rewritten. Each of the two new keys that is missing gets an INFO line and
its default. Two things can change behaviour: a `maxChunkBytes` outside 262,144 to 1,048,576 is clamped with a WARN
(65,536 becomes 262,144, 2,000,000 becomes 1,048,576: the default is also the maximum now, so the config can only lower
the budget), and with the default `undeliverableEntries` an entry that could never have been delivered is now left out
where 1.0.0 disconnected the player.

## Configuration

`config/recipebooksplitter.json`, read once at startup (restart the server to apply changes; `/reload` does not re-read
it):

```json
{
  "maxChunkBytes": 1048576,
  "logSplits": true,
  "logOversizedPackets": false,
  "undeliverableEntries": "drop",
  "bundleChunks": false
}
```

| Key | Default | Meaning |
|---|---|---|
| `maxChunkBytes` | `1048576` (1 MiB) | Upper bound for the encoded size of one recipe book packet: packet id plus payload, as the server's packet encoder produces it, before compression and before any ViaVersion translation. Whole numbers only: a value like `1.5` falls back to the default (it is not clamped). Whole numbers are clamped to 262,144 to 1,048,576 with a warning in the log: the default is the maximum, so the config can only lower the budget (see [Chunk size bounds](#chunk-size-bounds)). |
| `logSplits` | `true` | Log one INFO line every time a packet is split. |
| `logOversizedPackets` | `false` | Log a WARN line for every clientbound packet (of any type) that encodes to more than 4 MiB, before compression and ViaVersion. Useful to find other packets that are close to the limits. |
| `undeliverableEntries` | `"drop"` | What to do with a single entry that the connection cannot send even alone: `"drop"` leaves it out, `"send"` sends it anyway (the player is then disconnected). Exactly these two lowercase strings. |
| `bundleChunks` | `false` | Send the chunks of one split inside one bundle, so the client handles them together. |

Missing keys use their defaults, and the file is not rewritten. Wrong types for single keys fall back to that key's
default with a warning, and unknown keys are warned about (this catches typos). A file that cannot be read or is not
valid JSON is reported with an ERROR, the defaults are used, and the file is left untouched.

### Chunk size bounds

**Lower bound, 262,144.** Every chunk makes a vanilla client rebuild its recipe book and start a background rebuild of
the recipe search index. A real client spent 85 ms of render-thread time on the first 9.2 MB book of a run when it came
in 9 chunks (1 MiB) and 635 ms when it came in 145 chunks (65,536 bytes). On later joins of the same run it was 28 ms
against 111 ms (tested with 1.0.0, software rendering, medians of three runs, spread 2 to 4 times). 262,144 keeps that
book at 36 chunks (9,227,553 / 262,144 = 35.2 before packing losses; verification, scenarios L5 and E7b: 36 chunks, the
largest 262,095 bytes on the give; the real client, scenario C, received 36 packets per book).

**Upper bound, 1,048,576 (the default).** The config can only lower the budget. A frame holds at most 2,097,151 bytes,
and that limit applies to the packet as it is sent:

| Connection | What the 2,097,151 limit applies to |
|---|---|
| network compression off | the raw packet |
| network compression on | the compressed packet; the raw packet may be up to 8,388,608 bytes |
| behind a proxy that forwards uncompressed (Velocity with `compression-threshold = -1`) | the raw packet again, even if the backend compresses |
| ViaVersion in the pipeline | the translated bytes, which are produced after the mod has measured |

These limits were measured on a real server with the 1.0.0 jar, a tuned data pack and the ceiling raised in a test
build. With compression off a chunk of exactly 2,097,151 bytes was delivered and 2,097,152 failed
(`Packet too large: size 2097152 is over 8`). With compression on, exactly 8,388,608 bytes was delivered and 8,388,609
failed (`Packet too big`). An incompressible chunk of 2,096,399 bytes made a 2,097,048-byte frame and passed; 2,096,699
bytes made a 2,097,348-byte frame and failed. Behind Velocity with `compression-threshold = -1` the client was handed raw
frames of 3,997,410 bytes at a 4,000,000 budget, and a vanilla client's frame decoder reads at most three length bytes
(from its source; not run).

ViaVersion is the reason for the ceiling. It translates after the mod has measured, so a chunk can arrive bigger than
the budget, and it made chunks bigger by up to 63 % in the two worst-case books that were built (a 26.2 client joining
through ViaFabric, network compression off; ordinary data grew by 0 to 0.37 %, see [Compatibility](#compatibility)):

- A book of recipes with one item per recipe, made only of items whose ids cross 127 in the 26.1 to 26.2 translation
  (1.0.0, budget 2,000,000): one 1,999,931-byte chunk became 2,507,176 bytes (+25.4 %; the whole book +25.3 %), which
  disconnected the player. At the default 1 MiB budget the same book was delivered (largest translated chunk 1,318,277
  bytes). At the ceiling of 1.1.0 it is delivered too (verification, scenario VW1, ViaFabric 0.4.21+166, a 26.2 client,
  compression off: the book grew +25.3 %, 5,597,058 against 4,468,135 bytes, in 5 chunks, and the largest translated
  chunk was 1,318,277 bytes on the give and 1,314,050 on the relog on Java 25, 1,313,733 on Java 21; scenario VW2 with a
  1.0.0 file asking for 2,000,000 gave the same sizes after the clamp).
- A book whose slots are direct lists of those 27 items (review, scenarios VW3 and, in an earlier revision of the kit,
  VW3x): each id then appears twice per item, as a 2-byte slot display and as a 1-byte holder-set entry, and each of
  them grows by a byte, so such an entry grows by up to 2/3 by that arithmetic. At a budget of 1,500,000, which was the
  ceiling of the earlier 1.1.0 development builds, the largest chunk, 1,499,629 bytes, became 2,416,673 bytes (+61 %) and the player
  was disconnected (`Packet too large: size 2416673 is over 8`); on relog, 2,422,720 bytes. At the default the largest
  chunk, 1,048,524 bytes, became 1,706,228 bytes (+62.7 %), below the frame limit, and the book was delivered on give
  and relog (largest 1,696,235 on relog). In the verification (scenario VW3, Java 25) the book grew +61.7 % (7,652,212
  against 4,732,811 bytes), the largest translated chunk was again 1,706,228 bytes on the give and 1,695,742 on the
  relog (390,923 and 401,409 bytes below the frame limit), and the client was not disconnected.

So the ceiling is 1,048,576, the default: a chunk of that size still fits a frame if translation makes it bigger by up
to 99 % (2,097,151 / 1,048,576 is just under 2), which is more than the +25 % and +63 % measured, while 1,500,000 did not
absorb the +61 % to +63 % of the second book. The measured growths are examples and not a bound of the translation. The
same book translated by ViaBackwards or for another protocol was not tried. Bigger chunks would not buy much: the 9.2 MB
test book is 9 chunks at 1 MiB and would be about 7 at 1,500,000 (9,227,553 / 1,500,000 = 6.2 before packing losses; by
arithmetic, not run), which saves the client two rebuilds of its recipe book. Budgets that earlier builds accepted (1.0.0:
up to 2,000,000; the earlier 1.1.0 development builds: up to 1,500,000) are clamped with a WARN that gives these reasons.

### undeliverableEntries

A split cannot make a single entry smaller. If one entry is too big for the connection even in a packet of its own, the
player is disconnected on every join and on every `/reload`, so they never get a recipe book at all. With `"drop"` the
mod leaves out only entries that certainly cannot pass this connection's own pipeline:

- network compression on: the packet with just that entry is over 8,388,608 bytes before compression, or compresses to a
  frame of over 2,097,151 bytes (a packet below the compression threshold is sent raw, so its raw size counts, plus one
  byte). Only an entry that is bigger than `maxChunkBytes` on its own is checked at all, and only those between
  2,000,000 and 8,388,608 bytes are deflated to find out, once each; the prototype took 27 to 77 ms for that.
- network compression off: the packet with just that entry is over 2,097,151 bytes.

Each entry that is left out gets an ERROR with its position, display id, recipe and the reason. The player stays
connected and their recipe book lacks that recipe. The `replace` flag moves to the first chunk that is actually sent, and
if every entry is left out an empty packet with the original `replace` flag is still sent. With `"send"` nothing is left
out, as in 1.0.0.

What the decision knows and does not know:

- It uses the bytes this server writes, before ViaVersion translation (which changes sizes in either direction) and
  before any proxy. An entry above 2,097,151 bytes that the backend can send compressed is only a WARN: a proxy or a
  client that receives it uncompressed may not be able to.
- If the compression or framing handler is not vanilla's (looked up by the vanilla handler names), whether a packet fits
  is unknown and nothing is left out.
- A mod that raises a limit inside the vanilla handler classes cannot be seen that way. Packet Fixer 3.3.5 for 1.21.11
  (`packetfixer-fabric-3.3.5-1.21.11.jar`, SHA-256 `64d5128bd67522edfed81f316b3c8dd65c69081d0a9e4777142e145c42650516`,
  default config, `allSizesUnlimited`) does. In a unit-level check made in the review and repeated on the final code
  (vanilla handlers in an `EmbeddedChannel` with its mixins applied; no real server or client) a 9 MB packet of zeros
  was written as an 8,821-byte frame with the connection staying open, and a client-side compression decoder read it.
  It does not lift the receiving frame decoder's 3-byte length limit, so a frame over 2,097,151 bytes still fails
  there. When the mod `packetfixer` is loaded (an INFO line at startup says so), an entry over 8,388,608 bytes is
  therefore no longer left out for its size alone: only a frame over 2,097,151 bytes after compression leaves it out,
  and otherwise it is sent with a WARN (whether the encoder sends it depends on that mod's config). Other mods that
  patch these limits are not recognised; if entries are left out that your setup can deliver, set `"send"`.
- It does not use the client's own limit. Item components such as `custom_data` are read with a 2 MiB NBT quota, and a
  real client rejected a 4.5 MB entry (`NbtAccounterException: Tried to read NBT tag that was too big; tried to allocate:
  2043742 + 60000 bytes where max allowed: 2097152`) and disconnected (tested with 1.0.0). The quota counts decoded
  bytes, not wire bytes: a client-style decode in a test refused a 41 KB list of empty compounds and accepted 3.1 MB of
  three-byte characters. With ViaVersion, client mods or Polymer-changed registries the server cannot predict it, so keep
  single recipes well below 2 MiB.

### bundleChunks

Without it the chunks of a split are sent one after another. With `true` they are sent inside one
`ClientboundBundlePacket`, which the client handles in a single call on its main thread: the whole book is handled in
one frame and one tick, and the recipe book is never half filled between two ticks. Every chunk still rebuilds the
recipe book, and by the client code a build of the recipe search index that has not started when the next one is
scheduled is cancelled, so fewer of them run. It applies when the original packet was not already inside a bundle, the
split makes 2 to 4,096 chunks (the most a client accepts in one bundle) and the connection has a bundle unpacker;
otherwise the chunks go out loose, with a DEBUG line that says why.

What a real client showed (verification; vanilla 1.21.11 client on Xvfb with software rendering, capped at 20 fps; the
9.2 MB book in 9 chunks at 1 MiB, compression 256; the client's entry digest off, because it runs inside the frame;
medians of 12 runs per row, three from the verification lane and three each from three reruns in fresh clones of commits
`4b39263` and `51af71a`, same jar; the lane's frame timers had the same hooks as the ones `4b39263` committed, applied
as an uncommitted patch; "give" is `recipe give Tester *`, "relog" the initial book of a later join). H and I limit the
server-to-client link to 8 or 20 Mbit/s, without and with `bundleChunks`; K is `bundleChunks` behind Velocity, with no
loose twin:

| Run | bundleChunks | Ticks from the first chunk to the last, give / relog | Search builds scheduled and run, give; relog | Slowest frame that handled book packets, give / relog | Background CPU of the builds, give / relog |
|---|---|---|---|---|---|
| H, 8 Mbit/s | off | 129 / 130 | 9 and 9; 10 and 10 | 104 / 125 ms | 671 / 551 ms |
| I, 8 Mbit/s | on | 0 / 0 | 9 and 4; 10 and 6 | 228 / 240 ms | 709 / 368 ms |
| H, 20 Mbit/s | off | 54 / 55 | 9 and 9; 10 and 10 | 110 / 142 ms | 668 / 560 ms |
| I, 20 Mbit/s | on | 0 / 0 | 9 and 4; 10 and 6 | 264 / 243 ms | 656 / 343 ms |
| K, Velocity | on | 0 / 0 | 9 and 4; 10 and 6 | 249 / 227 ms | 656 / 312 ms |

All 60 runs of the table passed their assertions (the client's recipe book equals what the server sent, one packet per
chunk, `replace` only on the first; 11 assertions for H, 13 for I and K) with no disconnect, and 22 more runs with the
digest on (13 assertions for H, 15 for I and K, which add the comparison of the entry hashes with the server's) passed
too. With the bundle the book was handled in one frame and one tick every time. The ranges of single runs overlap
(slowest frame, give at 8 Mbit/s: 158 to 428 ms with the bundle, 89 to 201 ms without), so the medians carry the claims.

**Why `bundleChunks` is off by default.** The rule for turning it on had four conditions: no acceptance failure; fewer
search builds or fewer ticks per book; no worse maximum frame time; bundles that pass through Velocity and ViaVersion.
The first, second and fourth held:

- Nothing failed: besides the real client, E8d (Velocity 4.2.0 + FabricProxy-Lite 2.11.0, 40 of 40 assertions) passed on
  a Java 21 and a Java 25 backend, and VC (ViaFabric 0.4.21+166, a 26.1 client, 60 of 60) on Java 25 and 21; there all 9
  chunks of every book arrived translated in one bundle that held nothing else (`recipe_book_add` as packet id 74 where
  the 774 control got 72; largest translated frame 785,825 bytes). Polymer 0.15.2 was loaded in these runs.
- The bundle cut the search builds that run from 9 to 4 for a give and from 10 to 6 (5 to 7) for a relog, and the
  book was no longer partly there for 129 to 130 ticks (about 6.5 s) at 8 Mbit/s or 54 to 55 ticks (about 2.7 s) at
  20 Mbit/s. The background CPU of the builds is about the same for a give (709 against 671 ms at 8 Mbit/s, 656 against
  668 ms at 20) and 33 to 39 % lower for a relog (368 against 551 ms, 343 against 560 ms).

The third did not hold: the slowest frame that handled book packets took 228 / 240 ms (give / relog) against 104 / 125
ms at 8 Mbit/s and 264 / 243 ms against 110 / 142 ms at 20 Mbit/s, which is 1.7 to 2.4 times as long (and 249 / 227 ms
through Velocity). The extra time is packet handling that a bundle puts into one frame: 64 to 142 ms in the slowest
frame of the book's window with the bundle, against 15 to 22 ms with loose chunks; the rest of such a frame is mostly
rendering. The server cannot change this: by the decompiled client code `ClientPacketListener.handleBundlePacket` calls
the handler of every sub-packet in one call, and each `handleRecipeBookAdd` refreshes the recipe book. The sum of the
handler times per book is not lower with the bundle (give: 47 and 48 ms loose at 8 and 20 Mbit/s, 53, 71 and 69 ms for I
at 8, I at 20 and K). Several smaller bundles would bring back part of the half-filled book (an inference, not tried).
On a client with a GPU rendering would take a smaller share of the frame, so by the same code the packet phase would
weigh more there (inferred, not measured).

The keep-alive margin is the same with and without the bundle. A keep-alive queued behind the book arrived 7.6 s late
(H) and 7.5 s late (I) at 8 Mbit/s, and 3.2 s (H) and 3.6 s (I) at 20 Mbit/s (probe runs, one per case; the first to the
last decoded chunk takes about 6.5 s and 2.75 s, and the delay also includes the first chunk), which leaves about 7.4 s
and 11.4 s of the server's 15 s keep-alive window; no `Timed out` appeared in any server log. In the 60 runs of the
table keep-alive packets reached the client within 14 ms. Frame times need the digest off: a client that also computes
the digest re-encodes the entries inside the frame that handles them, and measured slowest frames about 2 to 3 times
higher (I at 8 Mbit/s, give: 512 ms, median of three digest-on runs of the lane, against 228 ms with the digest off; 703
ms in one digest-on run of a rerun, with 590 ms of packet handling in that frame; see
[e2e/realclient](e2e/realclient/README.md)).

So the default stays `false`. Opting in is safe on the tested stack (Velocity with FabricProxy-Lite, ViaFabric,
Polymer): no run disconnected. Turn it on if a half-filled book on slow links, or the number of builds, matters more to
you than one frame of roughly 200 to 300 ms on a slow client. Scenario G (unthrottled, direct) passed with every book in
one frame and one tick, but that does not tell the two apart: loose chunks on the same loopback link were handled in one
frame as well (42 of 42 books in B, C, E, J and the Java 25 run of B), and G's frame time was not measured. Also not
measured: a GPU client, a 4 Mbit/s link, older clients through ViaBackwards, and a recipe screen open with search text
while the book arrives (by the client code each chunk then waits for a full index build, and with a bundle all those
waits would fall into the same frame; a headless model of the client gave 0.28 to 0.65 s in total for 9 chunks).

## How it works

- The mod hooks `Connection.send(Packet, ChannelFutureListener, boolean)`, the one method every server-side send ends up
  in. This runs after the listener-level hooks of other mods (Polymer's packet replacing and preventing, its optional
  count-based recipe splitter), and it also covers packets that mods hand directly to the connection.
- Only `recipe_book_add` packets with at least two entries are looked at. With `"drop"` a packet with one entry is
  looked at too, because a lone entry may be one that no connection can send; that costs about 5 microseconds and 14 KiB
  per packet in the benchmark (see [Performance](#performance)). A bundle is taken
  over only if it holds such a packet, and every other bundle (all that vanilla sends) costs one look at each
  sub-packet.
- To measure, each entry is encoded with the connection's real packet encoder (the same code that will write the packet).
  So the sizes are exact for everything that happens inside `PacketEncoder.encode`, including what other mods change
  there, for example Polymer's per-player item rewriting and Fabric API's custom ingredient fallback (the Fabric API part
  follows from the construction and has no test of its own). Anything that handlers between the connection and the
  encoder in the pipeline might do to this packet is not part of the measurement; with the mods tested here that is
  nothing. The measuring, the splitting and the writing run as one task on the connection's Netty thread, not on the
  server thread (see [Performance](#performance) for what that costs).
- **Encode once.** The bytes of the measuring are kept, and while the chunks (or an unsplit packet) are written,
  `PacketEncoder` copies them in place of running the codec on the same entries again. Only the codec call is replaced,
  so everything else in `encode()` still runs: other mods' hooks, JFR's packet statistics, the debug log. The kept bytes
  are used only for the exact packet object that this task is writing, to the exact encoder instance that measured it,
  and only once. Anything else (a handler that copies or delays the packet, another connection, a second write of the
  same object) finds nothing and the packet is encoded normally, as 1.0.0 did. Every probe must have the expected layout
  (packet id, one entry, `replace` flag), and the hook around the codec call must report that the codec call wrote
  exactly that packet and nothing else wrote into the buffer (a byte that another mod's hook writes before or after the
  codec call would otherwise sit in the kept header and be written twice); if not, nothing is kept and one INFO line says
  why. A packet whose kept bytes do not add up to its planned size is encoded normally, with an ERROR.
- The entries are packed greedily, in order, into chunks that stay within `maxChunkBytes`. If the whole packet fits, the
  original packet object is sent unchanged.
- The first chunk keeps the `replace` flag of the original (the client clears its recipe book when it is set), all
  later chunks have `replace=false`. The entries, and with them their notification and highlight flags, are the same
  objects as in the original.
- All chunks are written back to back inside that one task, so no other packet can end up between them. The listener
  and the flush flag of the original send apply to the last chunk.
- **Bundles.** A bundle reaches `Connection.send` as one object; the `unbundler` handler further down the pipeline
  takes it apart. So a recipe book packet inside a bundle is replaced, in place, by its chunks, and one rebuilt bundle is
  sent with the original listener and flush flag. A bundle with nothing to change is sent as the same object. Vanilla
  bundles only entity packets, so this is for other mods; the e2e test mod sends such a bundle (scenario E8).
- If something goes wrong, the original packet (or bundle) is sent as it was, as without the mod: when the measurement
  fails, when the channel closed while the task was queued, when splitting a bundle would make it hold more than 4,096
  packets (ERROR), and when another mod has replaced the connection's `encoder` handler with something that is not a
  `PacketEncoder` (one WARN, once; fake-player connections without an encoder, such as Carpet's, are only logged at
  DEBUG).

Example log line from a verification run (scenario E1, Java 21, Loader 0.19.5), 4,457 recipes with 3000-character
payloads (one run on a loaded 4-CPU VM: the milliseconds are not typical):

```
[RecipeBookSplitter] Tester: split 8.8 MiB recipe book packet (9,227,553 bytes, 4,457 entries, replace=false) into 9 chunks (largest 1,048,106 bytes, limit 1,048,576 bytes, 380 ms, written in 440 ms); measured bytes reused for 9 of 9 packets
```

An entry that cannot be sent (a 3 MB incompressible entry from the e2e test mod, compression 256; `recipe unknown` is what
a display id without a real recipe prints; for real recipes the recipe's id is logged, as the WARN for an entry above
the budget shows in E5, `recipe rbs_e2e:huge`, and in P3, `recipe polypack:r01500`; no run left out a real recipe):

```
[RecipeBookSplitter] Tester: left out recipe display entry #1 (display id 2000001, recipe unknown): it is 3,000,040 bytes on its own and this connection cannot send it (it compresses to a 3,000,970-byte frame, and a frame can hold at most 2,097,151 bytes). The player stays connected, but their recipe book does not show this recipe. Make the recipe smaller, or set "undeliverableEntries": "send" to send it anyway (the player is then disconnected)
```

An entry above `maxChunkBytes` that the connection can still send (4.5 MB of zeros, compression 256):

```
[RecipeBookSplitter] Tester: recipe display entry #1 (display id 2000001, recipe unknown) is 4,500,040 bytes on its own, more than maxChunkBytes (1,048,576); sending it in a chunk by itself; network compression lets this connection send it, but a proxy or client that receives it uncompressed cannot
```

## Compatibility

### Polymer

The mod does not depend on Polymer. It ran together with Polymer 0.15.2 (bundled) in every scenario that loads it, and
with Polymer-backed items in recipes (tested with 1.0.0: scenarios P0 to P7, PR1 to PR3). The 1.1.0 jar passed all of
them with the kit's parameters, and more (verification): P0 to P7, P1b, P4v, PV, PR1 to PR3, PR2v, PL1 and PL2 on Java
21 with Loader 0.19.5 (17 runs), and P1, P4, PR2 and PL2 on Java 25 with Loader 0.19.0 (4 runs); every split line ends in
`reused for N of N packets` or `N of N packets verified`, and the sizes below are those of 1.0.0. Polymer makes entries
much bigger: with 400 server-side Polymer items the entries of a book of 2,988 recipes added up to 13,445,253 bytes,
against 237,456 for the same recipes with vanilla items (PV: 56.6 times less). A recipe whose ingredient is a tag of 150
Polymer items took 39,849 bytes (58 for the vanilla twin, 688 times as much), and other recipes with Polymer ingredients
took 17.6 to 32.9 times as much (about 265 bytes per Polymer item stack). As packets the book was 13,445,257 bytes (the
entries plus packet id, count and replace flag) on relog and `/reload`, and 13,445,093 bytes for the 2,987 entries of
the give. Vanilla's `Packet too big (is 13445093 ...)` for the give and `(is 13445257 ...)` on relog are exactly what
the mod measured (its digest lines say 13445093 and 13445257; P0 without the mod gave the same two sizes again).

- **The measurement must go through the real encoder.** Sizing an entry with the bare codec outside the connection is
  exact for static Polymer items (PolyDecorations 0.10.4, 214 entries, 122,676 bytes in 1.0.0; 291,928 bytes for its 1,702
  recipes in PR1, bare and in-context identical) and wrong when the encoding depends on the player's packet context.
  PolyFactory 0.10.4 is a real example: its Server Translations API adds a translation fallback to text only inside a
  packet context, so the bare codec gave 6,063,836 bytes and the real encode 8,053,797 (32.8 % more; a size taken from
  the bare codec is 24.7 % too small; PR2, reproduced with 1.1.0 on Java 21 and 25). The synthetic player-bound items of
  P4 and P5 differ in the same way: 13,445,253 bytes bare against 17,130,428 in the player's context (+27.4 %). A mutant
  of the mod that sized with the bare codec sent PolyFactory packets of up to 1,398,482 bytes at the 1 MiB budget. With
  synthetic player-bound items, compression off and a 2,000,000 budget, the server disconnected the player (`Packet too
  large: size 2507427 is over 8`). In those runs P4, P5 and PR2 told such a mutant from the mod (P1, with static items,
  passed for both). The checkers of the 1.1.0 verification compute the plan on the bare sizes (arithmetic; no mutant was
  run): at the ceiling 1,048,576 the book of P4 and P5 would be 13 chunks whose true largest is 1,336,404 bytes, all 13
  over the budget (the mod: 17 chunks, largest 1,046,656), PR2's book 6 chunks with the true largest 1,398,482 bytes,
  all 6 over (the mod: 8 chunks), and PR3 at 262,144 bytes 27 chunks with the true largest 347,466 bytes, 26 over (the
  mod: 35 chunks). So at the ceiling such a mutant fails the kit's budget check and, with every chunk below 2,097,151
  bytes, does not disconnect the player. At the former ceiling 1,500,000 the largest chunk planned on bare sizes was
  1,908,244 bytes by computation. The kit's P5 cannot use a budget above the ceiling 1,048,576 any more.
- **Polymer's own count-based splitter** (`split_recipe_book_packet_amount`, default -1): keep the default. Set to 500 it
  produced one chunk of 12,179,083 bytes, over 8 MiB on its own. The mod split that chunk into 12 packets and passed
  smaller ones whole; every packet the client got was within 1 MiB and every recipe arrived once (1.0.0; P7 with the
  1.1.0 jar: the 12,179,083-byte chunk became 12 chunks, the largest 1,043,632 bytes, and no packet the client got was
  over 1,045,678 bytes).
- **Language change.** Polymer resends the recipe book when a player changes their client language. PL1 (verification):
  phase 3 has two complete `replace=true` books of 2,988 entries and 13,445,257 bytes, 13 chunks each, matched against
  the client; the second began 0.926 s after the client announced `de_de`, and it is byte-identical to the book of the
  join. PL2, with PolyFactory (1,897 recipes), gave two books of 8,053,801 bytes in 8 chunks each with the same SHA-256,
  so `de_de` did not change the size or the content in this setup (second book 0.586 s after the announcement; the same
  on Java 25 with Loader 0.19.0). This test is weak for language dependence: the client's bytes of both books hold
  40,564 `fallback` fields each, all equal to the translation key, and none of the translated texts (`Zerkleinert`,
  `Crushed Raw Gold`, `Faucet`), although PolyFactory ships a `de_de.json`. Whether a resource pack or another mod makes
  the book grow with the language was not tested.
- **Encode once with Polymer items.** Reusing the bytes of a lone-entry probe is only correct if an entry encodes to the
  same bytes inside a chunk. For vanilla items with Polymer loaded that held in a unit-test prototype with Polymer's codec
  wrappers active and in an E1 run with `verifyEncodeOnce` on the first encode-once build (9 of 9 packets verified against
  a normal encode; that build's jar still said 1.0.0 and had no bundle or undeliverable-entry code). With the 1.1.0 jar
  (verification): E1v verified 9 of 9 packets in both books on four Java and Loader combinations, P4v 68 of 68 packets
  (4 books of 17 chunks) and PR2v 32 of 32 (4 books of 8), the last two with entries whose bare and in-context bytes
  differ; there was no ERROR from the mod, and no log has the mismatch text (`differ from a normal encode`).
- Not tested: a Polymer client mod, a Polymer resource pack, other Polymer-based mods than PolyDecorations and
  PolyFactory.

### ViaVersion / ViaFabric

ViaFabric works on the encoded bytes after the packet encoder (the server pipeline reads `... prepender, compress,
via-encoder, encoder, ...` and packets pass from the encoder to Via, then to compression) and does not hook the methods
this mod uses, so each chunk is translated like any other packet. The limits above therefore apply to the translated
bytes. Tested with 1.0.0 against ViaFabric 0.4.21+166 (ViaVersion 5.10.0) on Java 25 (scenario V1 also on Java 21),
with the protocol client at protocol 775 (26.1) and 776 (26.2) next to a 774 control, and with real vanilla 26.1 and 26.2
clients:

| What was translated | Growth by translation |
|---|---|
| 9.2 MB book, 26.1 client | +0 bytes |
| 9.2 MB book, 26.2 client | +323 bytes (+0.0035 %) |
| 61,617 small recipes (one 3x3 recipe per item, 40 times), 26.2 client | +20,843 bytes (+0.37 %) |
| 59,400 recipes made only of the 27 items whose id passes 127 in that step, 26.2 client | +25.3 % for the book, +25.4 % for the worst chunk (1,999,931 to 2,507,176 bytes) |
| 5,994 recipes whose every slot is a direct list of those 27 items, 26.2 client (review) | +62.7 % for the largest chunk at the default (1,048,524 to 1,706,228 bytes); +61 % at 1,500,000, a budget that 1.1.0 no longer accepts (1,499,629 to 2,416,673 bytes: disconnected) |
| 9.2 MB book, protocol 777 (26.3) | +709 bytes, but only with a locally patched ViaFabric 0.4.22+184 that is not distributed |

- With the mod there was no disconnect in any ordinary scenario: compression 256 and off, budgets 1,048,576 and
  2,000,000, with and without Polymer; chunk counts, entry counts and `replace` flags were the same as for the control
  client, and no frame was over 2,097,151 bytes. The real 26.1 and 26.2 clients joined, got the split book on the give
  and on relog, and stayed connected for 35 to 45 seconds with no disconnect or decode error in their logs. What their
  recipe book contained was not inspected.
- Verification of the 1.1.0 jar (ViaFabric 0.4.21+166, the protocol client, Loader 0.19.5): VB0, VB0b, V1, V1b, V2, V2b,
  V5, VC, VW1, VW2 and VW3 passed on Java 25, and V1, VW1 and VC on Java 21. Only the two baselines without the mod were
  disconnected (VB0 `Packet too big (is 9227553 ...)`, VB0b `Packet too large: size 9227553 is over 8`). For the 9.2 MB
  book the translated chunks of the 26.1 client (V1, V2, V5) were as big as the server's (ratio 1.0000) and the largest
  frame was 784,945 bytes with compression 256 and 1,048,433 with it off; the 26.2 client (V1b, V2b) got 9,228,072 bytes
  against 9,227,749 (+323 bytes), largest frame 785,462 and 1,048,520. VW1 (+25.3 %, 5 chunks), VW2 and VW3 (+61.7 %, 5
  chunks) were delivered with the sizes given under [Chunk size bounds](#chunk-size-bounds). VW1 to VW3 also require
  that translation really grew the book by at least 20 %, so that they cannot pass because the book stopped being a
  worst case. VC (`bundleChunks` through ViaVersion) is described under [bundleChunks](#bundlechunks). The kit once had
  scenarios at budgets above the ceiling (V3, V3b, V4, and VW3x, which documented the disconnect of the direct-list book
  at 1,500,000); they cannot be expressed any more without a patched jar, were not run, and their evidence is the text
  above.

- **ViaFabric 0.4.21+168 and every later 1.21.11 build up to 0.4.22+184 do not start on Java 21 or 25**
  (`NoSuchMethodError ... J_L_Runtime$Version.feature`). This is a ViaFabric packaging problem and not this mod's: the
  shaded JvmDowngrader stub class is an empty class in `META-INF/versions/9` of the jar, and it still has no methods when
  loaded with plain `java -cp` and no Fabric Loader. Builds +168, +171, +173, +177, +180, +181 and +184 were tried and
  crash; +163 and +166 start. Only 9 of the 28 builds that list 1.21.11 were started. The kit pins 0.4.21+166.
- Not tested: ViaBackwards and older clients, recipe displays other than crafting recipes under translation, and a real
  26.3 client.

### Velocity with FabricProxy-Lite

Nothing to configure, and nothing to install on the proxy. Tested with Velocity 4.2.0 (modern forwarding) and
FabricProxy-Lite 2.11.0 on the backend (1.0.0: scenarios E6, E6b, E6x with the backend's compression on at threshold 256
and off, and a real client). Every recipe book packet arrived complete and in order, none over 1 MiB, and without the
mod the same setup with compression on disconnects the player (`Packet too big`). With the 1.1.0 jar (verification,
Velocity on Temurin 25.0.4.1, backend Java 21 and, for E6 and E6b, also Java 25) E6, E6b, E6x, L2, L4, E8d and the real
client D and K passed: chunks of at most 1 MiB (9 for the 9.2 MB book), no frame over 2,097,151 bytes (L2, Velocity with
`compression-threshold -1`, raw frames: 1,048,313 bytes at most), and with `bundleChunks` (E8d, K) the bundle arrived
intact. Other Velocity versions and other proxies were not tested.

Velocity compresses on its own too, and it refuses a packet that compresses to more than 2 MiB (`The server sent a very
large (over 2MiB compressed) packet`): with the backend uncompressed, a raw incompressible chunk of 2,097,147 bytes
passed the backend and got the player kicked by Velocity. The ceiling 1,048,576 stays far below that.

### Network compression

On or off: both work. The rules for what a connection can send are in [Chunk size bounds](#chunk-size-bounds).

## Performance

- Per packet in general: two `instanceof` checks in the send hook (recipe book add, bundle) and one in the encode hook
  (plus one volatile read of the configuration there, for `logOversizedPackets`, and a thread-local read for recipe book
  adds). A bundle is also scanned for recipe book packets, one look at each sub-packet. In a micro-benchmark of the prototype the encode hook cost nothing
  measurable: 41.7 to 43.5 ns per encode with it, 40.7 to 43.5 ns without. Recipe book adds below the minimum entry
  count are passed on after a few field reads.
- **Measuring is not free, and encode once makes it cheap.** Every recipe book add that is looked at is encoded once,
  entry by entry, to measure it, on the Netty event loop thread of that connection. 1.0.0 then encoded the chunks a
  second time; 1.1.0 copies the measured bytes, so the codec runs once per entry, as it would for any packet. Benchmark
  (verification: the opt-in `EncodeOnceBenchmarkTest` of the release code, three runs on each of JDK 21.0.11 and
  Temurin 25.0.4.1, interleaved, 4 CPUs, load average 1.4 to 2.3 at the start of a run; in each run the median of 30
  interleaved rounds after 10 warm-up rounds; the table gives the median of the three runs, in ms): V is vanilla's
  single encode of the unsplit packet; "reuse off" is the whole send through `Connection.send` into an encoder with
  `-Drecipebooksplitter.encodeOnce=false`, which does what 1.0.0 did (measure, split, encode every chunk again) but is
  the current code and not the 1.0.0 jar; "encode once" is the same send with reuse on; "+ compression" adds the vanilla
  compression handler with threshold 256.

  | Book | JDK | V | reuse off | encode once | reuse off + compression | encode once + compression |
  |---|---|---|---|---|---|---|
  | 4,457 entries, 9.2 MB, split into 9 chunks | 21 | 46.64 | 76.73 | 42.96 | 464.91 | 433.89 |
  | | 25 | 24.51 | 36.44 | 25.41 | 514.89 | 489.00 |
  | 140,000 tiny entries, split | 21 | 260.73 | 493.45 | 262.78 | 612.88 | 384.90 |
  | | 25 | 258.57 | 516.29 | 262.69 | 650.36 | 375.34 |
  | 1,457 entries, within the limit | 21 | 1.85 | 4.22 | 2.19 | 4.96 | 3.28 |
  | | 25 | 1.91 | 3.73 | 1.94 | 4.90 | 3.15 |
  | 1,707 entries with custom data, within the limit | 21 | 5.76 | 9.53 | 5.73 | 42.56 | 40.01 |
  | | 25 | 4.22 | 7.26 | 4.43 | 45.20 | 42.60 |
  | 1,457 packets of one entry each (a burst of recipe unlocks), all of them (see below; an earlier run) | 21 | 4.70 | 8.20 | 6.99 | 8.74 | 7.29 |

  For the first book a send allocates 32.44 MiB with encode once against 45.56 MiB with reuse off (vanilla's own encode:
  22.42 MiB) on JDK 21, and 32.19, 45.06 and 22.17 MiB on JDK 25: 13.1 and 12.9 MiB less than with reuse off.

  What the runs showed, run by run. JDK 21: encode once cost no more than vanilla's single encode on the book (M1/V
  0.954, 0.970 and 0.855), saved 35.4, 36.1 and 34.2 ms of the send (paired medians of encode once minus reuse off), and
  with compression 46.2, 40.5 and 28.1 ms; the small packets cost +0.2 to +0.6 ms more than vanilla's single encode;
  the 140,000 tiny entries cost 0.994, 1.008 and 1.130 times vanilla's. JDK 25: the saving is smaller, 14.3, 18.7 and
  15.1 ms on the book, 24.7, 29.1 and 30.7 ms with compression; an earlier spike on JDK 21 had led to expect 25 ms or
  more in both cases, which JDK 25 did not reach (once by 0.35 ms with compression, where the paired p90 is positive:
  deflate noise). The likely reason is that JDK 25 encodes the book about twice as fast (V 24.0 to 34.6 ms against 44.3
  to 47.3 on JDK 21), so there is less to save; the share saved is still large (encode once is 0.58 to 0.70 of reuse off
  on JDK 25, 0.53 to 0.56 on JDK 21), encode once is at or below vanilla's single encode (-1.1, -3.6 and -3.1 ms), and
  the small packets cost at most +0.5 ms more. So the absolute gain depends on the JDK: about 35 ms for the book on JDK 21
  and about 15 ms on JDK 25.

  The last row of the table comes from an earlier benchmark run (the verification did not include its dataset),
  made after a review finding about the cost of the common case, a recipe unlock of one entry (the unlock row of the run
  before the fix was 4.96 | 10.54 | 10.50 | 10.93 | 10.98). It is the middle one of three runs
  (`-PrbsBench.datasets=unlocks`, each the median of 30 interleaved rounds after 10 warm-up rounds, load average 2.4 to
  3.8 at the start). A packet of one entry took 4.8 to 4.9 microseconds and 14.4 KiB through the mod (5.6 to 5.9
  microseconds and 14.1 KiB with reuse off) against 3.2 microseconds and 9.1 KiB for a plain write in the same test
  pipeline. Before the fix it was 6.8 to 7.2 microseconds and 19.9 to 20.1 KiB (6.6 to 7.4 microseconds and 15.5 to 15.8
  KiB with reuse off), measured in alternation with the new code in the same session. The fix has two parts: the report
  of a packet that was sent unsplit no longer builds its DEBUG line (its player name, encode-once note and formatted
  sizes) when DEBUG is off and there is nothing else to log, and the kept bytes begin with a 256-byte array that doubles
  up to 256 KiB (they began with 4 KiB, and in the first encode-once build with a full 256 KiB array, which made that
  case 271 KB per packet in a review check of Connection.send in a unit-test pipeline, 100 times what the entry needs).
  What remains with compression is vanilla's deflate: on a real server the chunks took 384 to 390 ms to compress
  (1.0.0, warm).
- **What this costs on a real server** (1.0.0, scenario E1 with Polymer loaded, a warm JVM, three runs of ten
  `recipe take`/`recipe give` cycles): measuring the 9.2 MB book took a median of 70 ms (100 ms with
  `-Drecipebooksplitter.debugDigest=true`, which also hashes the entries), and writing the 9 chunks 473 to 483 ms, 80 %
  of it deflate. The first split after a start took 0.29 to 0.64 s on a loaded VM (a cold JVM needs about 250 to 300 ms
  for the first measuring call alone). Polymer books of 8 to 17 MB took 0.6 to 1.8 s warm and 1.9 to 4.9 s for the first
  measurement after a start (debug digest on). With 1.1.0 (verification, scenario X1, eight runs of ten gives, digest
  off) measuring took a median of 63 ms on the warm gives (2 to 10) against 58.5 ms with the 1.0.0 jar: keeping the
  bytes costs about 5 ms. Writing took 402 ms with compression 256 (almost all deflate) and, in X1b with three runs,
  8 ms with compression off (the 1.0.0 jar logs no write time).
- **Other players.** The task runs on the connection's Netty thread, and other connections on that thread wait while it
  runs; the server thread is not blocked. In the 1.0.0 runs the whole task took about 0.55 to 0.6 s warm and 1.1 to
  2.6 s in the worst cold, loaded case. How long a second player waits was measured with X1 (compression 256), X1b
  (compression off) and X1c (X1 with `bundleChunks`): a second player, the Prober, pings every 10 ms while ten
  `recipe take`/`recipe give` cycles run, and all connections share one event loop thread
  (`-Dio.netty.eventLoopThreads=1`). The table is the slowest ping within 5 s after a give (verification; X1 eight runs
  of ten gives for each jar, three from the verification lane and five from a rerun in a fresh clone of commit
  `4b39263`, same jars; X1b and X1c three runs; no ping went unanswered in any run; "warm" is every give but the first
  of a run; the 1.0.0 jar has SHA-256 `dbd1226840c4...`):

  | Scenario | Jar | All gives, median | Warm gives, median / p90 / max | The first give of each run, sorted |
  |---|---|---|---|---|
  | X1, compression 256 | 1.0.0 | 533.4 ms | 518.2 / 589.1 / 703.6 ms | 632.9, 647.1, 662.3, 722.0, 723.9, 725.5, 852.9, 896.9 ms |
  | X1, compression 256 | 1.1.0 | 479.7 ms | 472.6 / 532.4 / 571.4 ms | 656.2, 657.0, 661.8, 677.1, 716.9, 727.3, 743.5, 910.2 ms |
  | X1b, compression off | 1.0.0 | 143.1 ms | 139.0 / 177.4 / 202.9 ms | 260.0, 303.1, 314.7 ms |
  | X1b, compression off | 1.1.0 | 80.9 ms | 77.6 / 108.6 / 132.5 ms | 193.3, 231.2, 279.5 ms |
  | X1c, compression 256, `bundleChunks` | 1.1.0 | 485.4 ms | 477.3 / 543.9 / 589.4 ms | 605.7, 648.0, 659.9 ms |

  Warm, the new jar is better: 46 ms (9 %) with compression 256, where deflate (about 400 ms) dominates the write and
  only the repeated chunk encode is saved, and 61 ms (44 %) with compression off. **The first give after a start is not
  faster with compression 256**, and not slower either: the medians of the eight first gives are 697.0 ms (1.1.0) and
  723.0 ms (1.0.0), and the spread between runs of the same jar (630 to 900 ms) is larger than that difference. The
  first three runs of each jar had looked worse for the new one (743.5, 661.8 and 716.9 ms against 632.9, 647.1 and
  723.9 ms); five more runs did not repeat it. What is slower on a cold JVM is the first measuring call, a median of
  210.5 ms against 179.5 ms with the 1.0.0 jar (and 63.0 against 58.5 ms warm), and the chunk encode that is saved
  cancels it. With compression off the first give was lower with the new jar too. In a unit-test pipeline (a scratch
  test, no compression, 4,457 entries, a fresh JVM for each of 8 samples) keeping the bytes did not make the first
  measuring call slower (median 168 ms, 140 to 213, against 188 ms, 129 to 239, without) and the first whole send was
  faster with encode once (median 303 ms, 269 to 361, against 369 ms, 341 to 400, with reuse off), so the extra copy does
  not explain the slower first measuring call on the server; what does is not established.
- **Memory.** The kept bytes are about as large as the entries of the book (9.2 MB for the test book), held in 256 KiB
  arrays until the task ends. The first array starts at 256 bytes and doubles, so a packet of one or a few entries keeps
  a few hundred bytes, not 256 KiB (see the cost per unlock above). There is no cap, so a bigger book needs that much heap for a moment
  (not measured).
- **The client** rebuilds its recipe book once per chunk. By the client code each rebuild also starts a background build
  of the recipe search index on a pool that also builds chunk meshes. On a fast link the chunks arrive together; over a
  slow link they arrive spread out and every one can start its own build. Cancelling a build skips it if it has not
  started yet, and lets it finish if it has: of the 145 builds scheduled for a 145-chunk book, 20 to 23 ran. So the background
  cost is bounded by the worker threads and not by the chunk count. With a recipe screen open and text in its search
  box, the main thread waits for the newest build. In a headless model of the client, with the screen open and a search
  term, a 4,500-entry book cost 55 to 126 ms unsplit, 0.28 to 0.65 s in 9 chunks, 1.1 to 2.4 s in 36 and 4.2 to 9.1 s
  in 140; this was not measured with a real client.
- The extra encodes call the real `PacketEncoder.encode`. While a `/jfr` recording runs they are counted in its network
  statistics, and if DEBUG is on for `net.minecraft.network.PacketEncoder` they appear in its debug log. Other mods that
  hook `PacketEncoder.encode` with side effects (such as packet counters) would see these probe packets too; none of the
  mods tested here do.

## Debugging

- `logOversizedPackets` (see above) lists every packet over 4 MiB, including packet types this mod does not split.
- `-Drecipebooksplitter.debugDigest=true` additionally logs a SHA-256 over the entry bytes of every measured packet
  (`digest player=... entries=... replace=... bytes=... chunks=... sha256=...`, with ` dropped=N` if entries were left
  out and ` bundle=true` if the book went out in a bundle). The end-to-end kit uses this to compare what the server sent
  with what a client received.
- `-Drecipebooksplitter.verifyEncodeOnce=true` encodes every packet that would be written from measured bytes the
  normal way too, compares the two and sends the normally encoded bytes. A difference is logged as an ERROR. For testing
  only; the startup log announces it.
- `-Drecipebooksplitter.encodeOnce=false` turns reuse off: the chunks are encoded again after measuring, as in 1.0.0.
  The startup log announces it, and the split lines lose their `measured bytes reused for N of M packets` ending.

## Limitations

- The client's 2 MiB NBT quota (see `undeliverableEntries`): a single entry above `maxChunkBytes` that the connection can
  send goes out in a packet of its own with a WARN, and a real client may be unable to read it.
- ViaVersion translation is not measured at runtime. The ceiling 1,048,576 absorbs a growth of up to about 99 %, and the
  two worst books built grew 25 % and 63 % (see [Chunk size bounds](#chunk-size-bounds)), but that is a measurement and
  not a bound of the translation. Other translations, such as ViaBackwards for much older clients, were not tried.
- Whether an entry can be sent is decided on this server's own bytes and handlers (see `undeliverableEntries`), not on a
  proxy's.
- Encode once assumes that an entry encodes to the same bytes inside a chunk as in its measuring probe. The layout of
  every probe, the span the codec call wrote and the size of every written packet are checked, but a codec whose output
  depends on the packet around the entry would not be noticed in normal mode. Verify mode finds it, and
  `encodeOnce=false` switches reuse off.
- Bundles that would hold more than 4,096 packets after splitting are sent unsplit with an ERROR, so an oversized
  recipe packet in one fails as it does without the mod. A bundle whose sub-packets are not a `Collection` is left alone;
  Fabric API copies every bundle's sub-packets into a list, and without Fabric API this path has no test.
- The extra encoding work described under Performance runs on a thread that other players share.
- Not tested: what a real 26.1 or 26.2 client shows in its recipe book, modded clients (a recipe-book mod may do work per
  packet), other proxies, Java versions between 21 and 25, other operating systems than Linux.

## Verification gaps

Every row of the verification of 1.1.0 was run with the release jar: the build and the 148 unit and integration tests on
JDK 21 and 25, the benchmark, the core, bundle and limit scenarios, the Loader and Java combinations, the Polymer and
ViaFabric scenarios, the latency scenarios, the real client A to K, and Loader 0.18.6. Every scenario passed. Two
results are not passes: the benchmark has no pass criterion, and on JDK 25 its saving is smaller than expected (see
[Performance](#performance)); and the rule for switching `bundleChunks` on was not met (frame time), so it stays off
(see [bundleChunks](#bundlechunks)). Per-scenario results are in the status table of [e2e/README.md](e2e/README.md#status).
What was not run or not measured:

- Not run: L1, V3, V3b, V4 and VW3x need a budget above the ceiling 1,048,576 and cannot be expressed without a patched
  jar (their evidence is under [Chunk size bounds](#chunk-size-bounds)).
- Not measured: the frame time of an unthrottled real client with `bundleChunks` (G passed, but the harness at that
  commit had no frame timers and ran with the digest on), a GPU client, a 4 Mbit/s link, older clients through
  ViaBackwards, and a recipe screen open with search text while the book arrives (see [bundleChunks](#bundlechunks)).
- Not established: why the first measuring call on a cold JVM is about 30 ms slower with 1.1.0 than with 1.0.0 (median
  210.5 against 179.5 ms, eight runs each; the saved chunk encode cancels it, see Performance).
- Not covered by any run (see Limitations): Polymer items or ViaFabric with the real-client harness, real 26.x clients, a
  modded client, proxies other than Velocity 4.2.0 with FabricProxy-Lite 2.11.0, other ViaFabric builds (the newer ones
  do not start), Java versions between 21 and 25, operating systems other than Linux.
- The two checks of the real-client harness have limits that the verification found: its F assertion passes on any
  unexpected disconnect (the `NbtAccounterException` was read from the event log by hand), and the single-frame
  assertion of G and K cannot tell bundled from loose delivery on a loopback link (see
  [e2e/realclient](e2e/realclient/README.md)).

## Building from source

Gradle runs on JDK 21 and on JDK 25 (the two that were tried). The repository includes the Gradle 9.5.0 wrapper, pinned
by `distributionSha256Sum` (the published checksum of `gradle-9.5.0-bin.zip`; verified for 1.1.0: a build with an empty
Gradle home downloaded the wrapper distribution with that checksum and produced a byte-identical jar, and a copy with a
wrong checksum failed with `Verification of Gradle distribution failed`). Fabric Loom 1.17.21 is used.

```sh
./gradlew build
```

The mod is `build/libs/recipebooksplitter-<version>.jar`; do not use the `-sources` jar. The version comes from
`mod_version` in `gradle.properties`.

The 1.0.0 sources built with no change on Temurin 25.0.4.1 (all 56 tests passed, class files at version 65, 17 of the
18 files of the jar byte-identical to the JDK 21 build; the one difference is a lambda name in
`RecipeBookSendInterceptor`). The 1.1.0 sources built with no change on Temurin 25.0.4.1 too (verification:
`./gradlew build --no-daemon` green on JDK 21.0.11 and on Temurin 25.0.4.1 with 148 tests, none failed or skipped, on
each, the tests running in a JDK 25 JVM on the second; all 30 class files at version 65 in both jars; both jars have the
same entries and differ in one class file, `RecipeBookSendInterceptor`, by the names of synthetic lambdas; the
`@WrapOperation` target is remapped to `net/minecraft/class_9139;encode` in both). SHA-256 of the JDK 21 jar
`24a82085e4ad...`, of the JDK 25 jar `7dd3f975900f...`.

Loom 1.18 (1.18.3 was the newest stable release on 2026-10-07) needs JDK 25 and Gradle 9.7.0 or newer, so it is not used
here. With 1.0.0 it built unchanged and produced the same classes. A recipe that worked on a scratch copy and has not
been applied here: set the wrapper to 9.7.0 while Loom 1.17.21 is still in use
(`./gradlew wrapper --gradle-version 9.7.0 --gradle-distribution-sha256-sum <published checksum>`), set `loom_version`
to 1.18.3 in `gradle.properties`, run `wrapper` once more to refresh the wrapper jar and scripts, and build on JDK 25.
Loom 1.18.3 fails on Gradle 9.5.0 (`plugin.api-version` 9.7.0) and on JDK 21 (`requires at least JVM runtime version
25`).

## Testing

`./gradlew build` also runs the tests (148 tests in 14 classes; all passed on JDK 21 and on JDK 25):

- `ChunkPlannerTest`: the packing algorithm without Minecraft (budget boundaries, VarInt growth, oversized entries,
  empty and single-entry input, `replace` ordering, flags kept, every chunk under budget, entry totals, randomized
  invariants).
- `SplitterConfigTest`, `SizesTest`: configuration parsing and clamping (the new bounds, both new keys, a 1.0.0 file),
  number formatting.
- `RecipeBookSplitMinecraftTest`: with Minecraft's real codecs, sizes are exact, split packets decode to the same
  entries, the kept bytes equal the codec's bytes for every chunk, and a simulation of the client's
  `handleRecipeBookAdd` ends up in the same state as for the unsplit packet.
- `ConnectionSplitIntegrationTest`: a real `Connection` and `PacketEncoder` in a Netty `EmbeddedChannel` (and one
  test on a real event loop thread) with the mod's mixins applied: replace flags, entries, flush, the send listener
  on the last chunk only, one flush for a whole split packet, pass-through and fallbacks.
- `RecipeBookSendInterceptorTest`: the recursion guard (a recipe packet sent to another player while chunks are
  written is still split), closed channels, missing encoders (log levels), a broken packet.
- `OversizedPacketLoggerTest`: the `logOversizedPackets` warning, including that the measuring probes are not
  reported.
- `EncodedEntriesTest`, `PreparedPacketTest`, `EncodeOnceIntegrationTest`: the kept bytes (a one-entry packet keeps 256
  bytes, not a full segment), and a vanilla-like pipeline (prepender, optional compression, encoder, unbundler). The frames are
  identical with and without reuse for replace true and false, split and unsplit, compression off and on, and entries
  across the storage borders. A handler that copies or delays a packet, another connection, a second write, verify mode,
  the kill switch and a throwing handler are covered too, and none of them leaves state behind. So are bytes that another
  mod's hook writes before or after the codec call (nothing is kept, the frames are those of 1.0.0, one INFO line), a
  measurement whose hook did not run, and the allocation per one-entry packet (the unlock case).
- `ConnectionLimitsTest`: the exact limits of a connection, checked against the real `CompressionEncoder` and
  `Varint21LengthFieldPrepender` at their boundaries, and what changes where a mod such as Packet Fixer lifts the
  8 MiB check.
- `BundleSplitIntegrationTest`, `UndeliverableEntryIntegrationTest`, `BundleChunksIntegrationTest`: recipe packets
  split in place inside bundles (both sides of the 4,096 limit: a rebuilt bundle of exactly 4,096 sub-packets is split
  and a client's bundler accepts it, one of 4,097 is sent unsplit with an ERROR), entries left out or sent, and chunks
  sent in one bundle. The undeliverable-entry and bundle tests run with encode once on and off, because with it on the
  encoder writes a list of entries kept apart from the packet and would hide an entry that was left in the packet; they
  also cover the entry-count boundary of a bundle's recipe packet (one entry with `"drop"`, two with `"send"`) and the
  log output at INFO, where a plain recipe unlock logs nothing.

`EncodeOnceBenchmarkTest` is not part of the build. Run it with
`./gradlew test -PrbsBench --tests '*EncodeOnceBenchmarkTest' --no-daemon`; `-PrbsBench.rounds`, `-PrbsBench.warmup`
and `-PrbsBench.datasets` change it.

The `e2e/` folder holds an end-to-end kit that runs the jar on a real Fabric server with a data pack that produces a
9.2 MB recipe book, optionally behind Velocity, with Polymer items, with ViaFabric and a newer-protocol client, with a
test mod that sends bundles and entries no connection can send, and with a minimal protocol client that records what
arrives; see [e2e/README.md](e2e/README.md). That client is not Minecraft: it does not build a recipe book or enforce the
2 MiB NBT limit. The kit therefore also has an optional sub-kit, `e2e/realclient`, that drives a real client.

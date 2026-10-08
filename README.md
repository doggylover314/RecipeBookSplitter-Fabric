# Recipe Book Splitter

A server-side Fabric mod for Minecraft 1.21.11 that splits oversized recipe book packets into several smaller ones, so
that players who know thousands of recipes can join.

How to read the evidence in this file: numbers named "1.0.0" were measured with the 1.0.0 jar by investigation runs that
used the harnesses now in [e2e/](e2e/README.md) (their logs are not in the repository). Numbers named "smoke" come from
single runs of the 1.1.0 jar while the e2e kit was built. Numbers named "first encode-once build" come from the tree of
the first 1.1.0 commit (before bundles and undeliverable entries), which was never released. Numbers from the opt-in
benchmark are named as such: it runs the mod's code in a unit-test pipeline, not a release jar. Numbers named "review"
come from checks made while the 1.1.0 code was reviewed (scratch copies of the repository, the kit's scenarios or unit
tests; not repeated with the release jar). `TBD(verify)` marks what has not been run with 1.1.0 yet; the list is under
[Pending verification](#pending-verification).

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
client was disconnected after the give and again when it rejoined. With 1.1.0 this is `TBD(verify)`: real client
scenarios B, C, D, E, G, J and K. Toasts were not observed. By the client code, a split gives one toast with the same
items, unless chunks that carry notifications arrive more than about 5.6 seconds apart.

One kind of entry cannot be helped by splitting: a single recipe display entry that the connection cannot carry even in
a packet of its own. By default the mod leaves such an entry out and logs an ERROR that names it, so the player can
still join (see `undeliverableEntries`).

## Requirements

- Minecraft 1.21.11.
- Fabric Loader 0.19.0 or newer, with Java 21 or newer. The 1.0.0 jar loaded, applied its mixins and split the book on
  Loader 0.19.0, 0.19.3 and 0.19.5 (bundled sponge-mixin 0.17.1, 0.17.3 and 0.17.4), each on OpenJDK 21.0.11 and
  Temurin 25.0.4.1: the same 26 assertions of scenario E1 passed in all six runs. Loader 0.18.6 refuses the mod at
  startup (`requires version 0.19.0 or later of mod 'Fabric Loader'`). The floor is the tested range and not a known
  incompatibility: a copy of the 1.0.0 jar with the version requirement removed also passed E1 on Loader 0.18.6 and
  0.17.3 on Java 21.
- 1.1.0 reuses the bytes it measured (see [How it works](#how-it-works)) through a MixinExtras `@WrapOperation`. The mod
  does not declare MixinExtras: it uses the copy Fabric Loader bundles (0.5.3 in Loader 0.19.0, 0.5.5 in 0.19.5). The
  1.1.0 jar has run with Loader 0.19.5 (MixinExtras 0.5.5) only. `TBD(verify)`: 1.1.0 on Loader 0.19.0 (MixinExtras
  0.5.3) and on Java 25 at runtime. If the wrap cannot be applied (another mod replaced the method or the call), the
  measuring notices that the hook never ran, keeps nothing, logs one INFO line (`encode once is not used: the hook around
  the codec call ... did not run while measuring`) and the packets are encoded as 1.0.0 did; the split line then ends in
  `reused for 0 of N packets`. The measuring side is covered by unit tests. The mixin itself was run with its target
  removed only in a scratch copy during the review (the bytes on the wire stayed identical; a simulation, not a real
  conflicting mod and not a server run, and that copy still kept the bytes, which the check now prevents).
- On Java 25 the server log has JVM warnings about `sun.misc.Unsafe::objectFieldOffset` called by JOML. They come from
  Minecraft's bundled JOML, not from this mod.
- Server side only. Nothing changes on clients, on Velocity or on other proxies.
- Fabric API is not required: the mod needs only Fabric Loader (and the MixinExtras it bundles), and scenario E3b ran
  1.0.0 on a server with neither Fabric API nor Polymer. (The unit and integration tests and the other scenarios have
  Fabric API loaded.) E3b with encode once is `TBD(verify)`.

## Installation

1. Put `recipebooksplitter-<version>.jar` into the `mods/` folder of every Fabric backend server that has the problem.
   Not on Velocity, not on clients.
2. Start the server. The log shows `[RecipeBookSplitter] loaded: ...`, and `config/recipebooksplitter.json` is created
   on the first start.

To build the jar yourself, see [Building from source](#building-from-source).

### Upgrading from 1.0.0

A 1.0.0 config file loads unchanged and is not rewritten. Each of the two new keys that is missing gets an INFO line and
its default. Two things can change behaviour: a `maxChunkBytes` outside 262,144 to 1,500,000 is clamped with a WARN
(65,536 becomes 262,144, 2,000,000 becomes 1,500,000), and with the default `undeliverableEntries` an entry that could
never have been delivered is now left out where 1.0.0 disconnected the player.

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
| `maxChunkBytes` | `1048576` (1 MiB) | Upper bound for the encoded size of one recipe book packet: packet id plus payload, as the server's packet encoder produces it, before compression and before any ViaVersion translation. Whole numbers only: a value like `1.5` falls back to the default (it is not clamped). Whole numbers are clamped to 262,144 to 1,500,000 with a warning in the log. With ViaVersion and no network compression keep the default (see [Chunk size bounds](#chunk-size-bounds)). |
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
book at about 36 chunks (9,227,553 / 262,144 = 35.2 before packing losses); the real count is `TBD(verify)`: scenario
L5.

**Upper bound, 1,500,000.** A frame holds at most 2,097,151 bytes, and that limit applies to the packet as it is sent:

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

ViaVersion is the reason for the margin, and the margin is not a bound. It translates after the mod has measured, and it
made chunks bigger by up to 63 % in the two worst-case books that were built (a 26.2 client joining through ViaFabric,
network compression off; ordinary data grew by 0 to 0.37 %, see [Compatibility](#compatibility)):

- A book of recipes with one item per recipe, made only of items whose ids cross 127 in the 26.1 to 26.2 translation
  (1.0.0): one 1,999,931-byte chunk became 2,507,176 bytes (+25.4 %; the whole book +25.3 %), which disconnected the
  player. 1,500,000 x 1.254 is about 1.88 MB, below the frame limit. At the default 1 MiB budget the same book was
  delivered (largest translated chunk 1,318,277 bytes). That the 1,500,000 ceiling delivers it too is `TBD(verify)`:
  scenario VW1.
- A book whose slots are direct lists of those 27 items (review, scenarios VW3x and VW3): each id then appears twice per
  item, as a 2-byte slot display and as a 1-byte holder-set entry, and each of them grows by a byte, so such an entry
  grows by up to 2/3 by that arithmetic. At 1,500,000 the largest chunk, 1,499,629 bytes, became 2,416,673 bytes
  (+61 %) and the player was disconnected (`Packet too large: size 2416673 is over 8`); on relog, 2,422,720 bytes. At
  the default the largest chunk, 1,048,524 bytes, became 1,706,228 bytes (+62.7 %), below the frame limit, and the book
  was delivered on give and relog (largest 1,696,235 on relog).

So 1,500,000 is the ceiling for connections where nothing grows the chunks, and with ViaVersion and no compression (or a
proxy that forwards uncompressed) the default is what the measurements support; 1,250,000 x 1.627 is 2.03 MB by
arithmetic, which would fit the second book, but that was not run. The same book translated by ViaBackwards or for another
protocol was not tried.

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
`ClientboundBundlePacket`, which the client handles in a single call on its main thread. Every chunk still rebuilds the
recipe book, but all of it happens in one frame (client code, and a headless model of the client): fewer background
builds of the search index get to start (for 9 chunks 71 to 100 ms of background CPU when they arrive together, 283 ms
when they arrive spread out), and no half-filled book is visible between two frames. On a fast link the chunks arrive
together and the client drains all queued packets in a frame anyway, so the difference should show on slow links. It
applies when the original packet was not already inside a bundle, the split makes 2 to 4,096 chunks (the most a client
accepts in one bundle) and the connection has a bundle unpacker; otherwise the chunks go out loose, with a DEBUG line
that says why.

The default is `false` because it has only been run with the protocol client and in unit tests (smoke E8c: 9 chunks in
one bundle, no disconnect, digests matching). `TBD(verify)`: real client with and without a throttled link (G, H, I),
behind Velocity (K) and through ViaFabric (VC). It becomes the default only if those show a benefit and no failure.

## How it works

- The mod hooks `Connection.send(Packet, ChannelFutureListener, boolean)`, the one method every server-side send ends up
  in. This runs after the listener-level hooks of other mods (Polymer's packet replacing and preventing, its optional
  count-based recipe splitter), and it also covers packets that mods hand directly to the connection.
- Only `recipe_book_add` packets with at least two entries are looked at. With `"drop"` a packet with one entry is
  looked at too, because a lone entry may be one that no connection can send; that costs about 7 microseconds and 20 KiB
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

Example log lines from a 1.1.0 smoke run, 4,457 recipes with 3000-character payloads (one run on a small, loaded VM:
the milliseconds are not typical):

```
[RecipeBookSplitter] Tester: split 8.8 MiB recipe book packet (9,227,553 bytes, 4,457 entries, replace=false) into 9 chunks (largest 1,048,106 bytes, limit 1,048,576 bytes, 245 ms, written in 436 ms); measured bytes reused for 9 of 9 packets
```

An entry that cannot be sent (a 3 MB incompressible entry from the e2e test mod, compression 256; `recipe unknown` is what
a display id without a real recipe prints; for real recipes the recipe's id is logged, a lookup no run has exercised
yet):

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
with Polymer-backed items in recipes (tested with 1.0.0: scenarios P0 to P7, PR1 to PR3). Polymer makes entries much
bigger: with 400 server-side Polymer items a book of 2,988 entries was 13,445,253 bytes, against 237,456 for the same
recipes with vanilla items. A recipe whose ingredient is a tag of 150 Polymer items took 39,849 bytes (58 for the vanilla
twin, 688 times as much), and other recipes with Polymer ingredients took 17.6 to 32.9 times as much (about 265 bytes per
Polymer item stack). Vanilla's `Packet too big (is 13445093 ...)` for that book is exactly what the mod measured.

- **The measurement must go through the real encoder.** Sizing an entry with the bare codec outside the connection is
  exact for static Polymer items (PolyDecorations 0.10.4, 214 entries, 122,676 bytes) and wrong when the encoding depends
  on the player's packet context. PolyFactory 0.10.4 is a real example: its Server Translations API adds a translation
  fallback to text only inside a packet context, so the bare codec gave 6,063,836 bytes and the real encode 8,053,797
  (32.8 % more; a size taken from the bare codec is 24.7 % too small). A mutant of the mod that sized with the bare codec sent PolyFactory packets of up to 1,398,482 bytes at
  the 1 MiB budget. With synthetic player-bound items, compression off and a 2,000,000 budget, the server disconnected
  the player (`Packet too large: size 2507427 is over 8`). In those runs P4, P5 and PR2 told such a mutant from the mod
  (P1, with static items, passed for both); PR3 would, by computation only (planned on bare sizes, its 262,144-byte
  budget gives chunks up to 347,466 bytes, which the budget check of the kit rejects). The kit's P5 uses the new ceiling
  1,500,000, not 2,000,000: planned on bare sizes the largest chunk is then 1,908,244 bytes by computation, over the
  budget but below the frame limit, so there the mutant fails the budget check and does not disconnect the player.
- **Polymer's own count-based splitter** (`split_recipe_book_packet_amount`, default -1): keep the default. Set to 500 it
  produced one chunk of 12,179,083 bytes, over 8 MiB on its own. The mod split that chunk into 12 packets and passed
  smaller ones whole; every packet the client got was within 1 MiB and every recipe arrived once.
- **Language change.** Polymer resends the recipe book when a player changes their client language. Smoke PL1 (1.1.0):
  two complete `replace=true` books of 2,988 entries in 13 chunks each, matched against the client. `TBD(verify)`: the
  full PL1 and PL2 runs, including whether PolyFactory's `de_de` book differs from the `en_us` one.
- **Encode once with Polymer items.** Reusing the bytes of a lone-entry probe is only correct if an entry encodes to the
  same bytes inside a chunk. For vanilla items with Polymer loaded that held in a unit-test prototype with Polymer's codec
  wrappers active and in an E1 run with `verifyEncodeOnce` on the first encode-once build (9 of 9 packets verified against
  a normal encode; that build's jar still said 1.0.0 and had no bundle or undeliverable-entry code). E1v with the 1.1.0
  jar is `TBD(verify)`, and so are P4v and PR2v (`verifyEncodeOnce`, no mismatch) for Polymer items and PolyFactory.
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
| 5,994 recipes whose every slot is a direct list of those 27 items, 26.2 client (review) | +62.7 % for the largest chunk at the default (1,048,524 to 1,706,228 bytes); +61 % at 1,500,000 (1,499,629 to 2,416,673 bytes: disconnected) |
| 9.2 MB book, protocol 777 (26.3) | +709 bytes, but only with a locally patched ViaFabric 0.4.22+184 that is not distributed |

- With the mod there was no disconnect in any ordinary scenario: compression 256 and off, budgets 1,048,576 and
  2,000,000, with and without Polymer; chunk counts, entry counts and `replace` flags were the same as for the control
  client, and no frame was over 2,097,151 bytes. The real 26.1 and 26.2 clients joined, got the split book on the give
  and on relog, and stayed connected for 35 to 45 seconds with no disconnect or decode error in their logs. What their
  recipe book contained was not inspected.
- Smoke (1.1.0): V1 passed, with translated sizes equal to the control (growth 0). `TBD(verify)`: V1b, V2 to V5, VC
  (`bundleChunks` through ViaVersion), VW1 to VW3x, and the 26.1 baselines VB0 and VB0b. VW3 and VW3x are the review
  run of the direct-list book as kit scenarios; their checkers pass on that run's output, the scenarios themselves have
  not been run.
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
and off, and a real client). Every recipe book packet arrived complete and in order, none over 1 MiB, and without the mod
the same setup with compression on disconnects the player (`Packet too big`). Smoke (1.1.0): E6 and L2 passed. Other
Velocity versions and other proxies were not tested.

Velocity compresses on its own too, and it refuses a packet that compresses to more than 2 MiB (`The server sent a very
large (over 2MiB compressed) packet`): with the backend uncompressed, a raw incompressible chunk of 2,097,147 bytes
passed the backend and got the player kicked by Velocity. The 1,500,000 ceiling stays below that.

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
  (the opt-in `EncodeOnceBenchmarkTest`, run on the 1.1.0 sources after the review fixes, JDK 21.0.11, 4 CPUs, load
  average 1.4 to 1.9 during the run, median of 30 interleaved rounds after 10 warm-up rounds, in ms): V is vanilla's
  single encode of the unsplit packet; "reuse off" is the whole send through `Connection.send` into an encoder with
  `-Drecipebooksplitter.encodeOnce=false`, which does what 1.0.0 did (measure, split, encode every chunk again) but is
  the current code and not the 1.0.0 jar; "encode once" is the same send with reuse on; "+ compression" adds the vanilla
  compression handler with threshold 256.

  | Book | V | reuse off | encode once | reuse off + compression | encode once + compression |
  |---|---|---|---|---|---|
  | 4,457 entries, 9.2 MB, split into 9 chunks | 40.73 | 67.90 | 37.51 | 444.86 | 395.01 |
  | 140,000 tiny entries, split | 227.97 | 447.28 | 236.15 | 544.64 | 336.58 |
  | 1,457 entries, within the limit | 1.65 | 3.46 | 1.92 | 4.48 | 2.97 |
  | 1,707 entries with custom data, within the limit | 4.64 | 8.91 | 5.19 | 39.67 | 35.68 |
  | 1,457 packets of one entry each (a burst of recipe unlocks), all of them | 4.96 | 10.54 | 10.50 | 10.93 | 10.98 |

  For the first book a send allocates 32.64 MiB with encode once against 45.97 MiB with reuse off (vanilla's own encode:
  22.63 MiB). A packet of one entry, the case of a single recipe unlock, took 7.2 microseconds and 20.1 KiB through the
  mod (15.7 KiB with reuse off) against 3.4 microseconds and 9.1 KiB for a plain write in the same test pipeline. In
  the first encode-once build the kept bytes began with a full 256 KiB array, which made that case 271 KB per packet
  (review, Connection.send in a unit-test pipeline), 100 times what the entry needs; the first array now starts at 4 KiB.
  What remains with compression is vanilla's deflate: on a real server the chunks took 384 to 390 ms to compress
  (1.0.0, warm). The same benchmark on Java 25 is `TBD(verify)`.
- **What this costs on a real server** (1.0.0, scenario E1 with Polymer loaded, a warm JVM, three runs of ten
  `recipe take`/`recipe give` cycles): measuring the 9.2 MB book took a median of 70 ms (100 ms with
  `-Drecipebooksplitter.debugDigest=true`, which also hashes the entries), and writing the 9 chunks 473 to 483 ms, 80 %
  of it deflate. The first split after a start took 0.29 to 0.64 s on a loaded VM (a cold JVM needs about 250 to 300 ms
  for the first measuring call alone). Polymer books of 8 to 17 MB took 0.6 to 1.8 s warm and 1.9 to 4.9 s for the first
  measurement after a start (debug digest on). With 1.1.0 the measured and written times of the same scenario are
  `TBD(verify)`: X1 and X1b with the old and the new jar.
- **Other players.** The task runs on the connection's Netty thread, and other connections on that thread wait while it
  runs; the server thread is not blocked. In the 1.0.0 runs the whole task took about 0.55 to 0.6 s warm and 1.1 to
  2.6 s in the worst cold, loaded case. How long a second player actually waits, old jar against new jar, is
  `TBD(verify)`: scenarios X1, X1b and X1c (a second connection pings the server every 10 ms; all connections share one
  event loop thread).
- **Memory.** The kept bytes are about as large as the entries of the book (9.2 MB for the test book), held in 256 KiB
  arrays until the task ends. The first array starts at 4 KiB and doubles, so a packet of one or a few entries keeps
  4 KiB, not 256 KiB (see the cost per unlock above). There is no cap, so a bigger book needs that much heap for a moment
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
- ViaVersion translation is not measured at runtime, and the 1,500,000 ceiling does not protect against all of its
  growth: a book of direct item lists grew 61 to 63 % and did not fit at 1,500,000 with compression off (see
  [Chunk size bounds](#chunk-size-bounds)). With ViaVersion and no compression keep the default. Other translations,
  such as ViaBackwards for much older clients, were not tried.
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

## Pending verification

The items below are not yet tested with 1.1.0. Each one is marked `TBD(verify)` above, and the evidence for 1.0.0 stays
valid where it is named.

- Build and tests with 1.1.0 on JDK 25 (on JDK 21 `./gradlew build` passes).
- The benchmark on Java 21 and 25 (table under Performance).
- The full e2e suite: all core and limit scenarios (including E3b with encode once and L5's chunk count), directly and
  behind Velocity.
- E1, E1v, E2, E3b, E5, E6, E6b, E8, E8c, E9b with `reused for N of N packets` on Loader 0.19.0 (MixinExtras 0.5.3) and on
  Java 25.
- That no real-server scenario logs `encode once is not used` (the check that the codec call wrote exactly the packet
  is new and has only run in unit tests and the Fabric API test runtime): in particular with Polymer (P1, P4, PR2) and
  Fabric API, whose hooks sit in `PacketEncoder.encode`.
- Polymer P0 to P7, PV, PR1 to PR3, with P4v and PR2v (no mismatch) and PL1 and PL2 (language change).
- ViaFabric VB0 to VW3x, in particular VW1 (the +25 % worst case is delivered at 1,500,000), VW3 (the +63 % direct-list
  book is delivered at the default) and VW3x (the same book at 1,500,000 disconnects, as in the review run), and VC.
- Real server timing and the stall of other players, old jar against new jar (X1, X1b, X1c).
- Real client: B, C, D, E, G, J, K, then H against I on a throttled link, then B and D with a Java 25 server on Loader
  0.19.0. `bundleChunks` is switched on by default only if H and I show a benefit and nothing fails, and VC and K pass.
- Optional: the 1.1.0 jar refused by Loader 0.18.6.

## Building from source

Gradle runs on JDK 21 and on JDK 25 (the two that were tried). The repository includes the Gradle 9.5.0 wrapper, pinned
by `distributionSha256Sum` (the published checksum of `gradle-9.5.0-bin.zip`; a wrapper download with that checksum
succeeded, and with a wrong one it failed with `Verification of Gradle distribution failed`). Fabric Loom 1.17.21 is
used.

```sh
./gradlew build
```

The mod is `build/libs/recipebooksplitter-<version>.jar`; do not use the `-sources` jar. The version comes from
`mod_version` in `gradle.properties`.

The 1.0.0 sources built with no change on Temurin 25.0.4.1 (all 56 tests passed, class files at version 65, 17 of the
18 files of the jar byte-identical to the JDK 21 build; the one difference is a lambda name in
`RecipeBookSendInterceptor`). The same check for 1.1.0 is `TBD(verify)`.

Loom 1.18 (1.18.3 was the newest stable release on 2026-10-07) needs JDK 25 and Gradle 9.7.0 or newer, so it is not used
here. With 1.0.0 it built unchanged and produced the same classes. A recipe that worked on a scratch copy and has not
been applied here: set the wrapper to 9.7.0 while Loom 1.17.21 is still in use
(`./gradlew wrapper --gradle-version 9.7.0 --gradle-distribution-sha256-sum <published checksum>`), set `loom_version`
to 1.18.3 in `gradle.properties`, run `wrapper` once more to refresh the wrapper jar and scripts, and build on JDK 25.
Loom 1.18.3 fails on Gradle 9.5.0 (`plugin.api-version` 9.7.0) and on JDK 21 (`requires at least JVM runtime version
25`).

## Testing

`./gradlew build` also runs the tests:

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
  on the last chunk only, pass-through and fallbacks.
- `RecipeBookSendInterceptorTest`: the recursion guard (a recipe packet sent to another player while chunks are
  written is still split), closed channels, missing encoders (log levels), a broken packet.
- `OversizedPacketLoggerTest`: the `logOversizedPackets` warning, including that the measuring probes are not
  reported.
- `EncodedEntriesTest`, `PreparedPacketTest`, `EncodeOnceIntegrationTest`: the kept bytes (a one-entry packet keeps 4 KiB,
  not a full segment), and a vanilla-like pipeline (prepender, optional compression, encoder, unbundler). The frames are
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
  sent in one bundle.

`EncodeOnceBenchmarkTest` is not part of the build. Run it with
`./gradlew test -PrbsBench --tests '*EncodeOnceBenchmarkTest' --no-daemon`; `-PrbsBench.rounds`, `-PrbsBench.warmup`
and `-PrbsBench.datasets` change it.

The `e2e/` folder holds an end-to-end kit that runs the jar on a real Fabric server with a data pack that produces a
9.2 MB recipe book, optionally behind Velocity, with Polymer items, with ViaFabric and a newer-protocol client, with a
test mod that sends bundles and entries no connection can send, and with a minimal protocol client that records what
arrives; see [e2e/README.md](e2e/README.md). That client is not Minecraft: it does not build a recipe book or enforce the
2 MiB NBT limit. The kit therefore also has an optional sub-kit, `e2e/realclient`, that drives a real client.

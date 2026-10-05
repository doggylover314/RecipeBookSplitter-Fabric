# Recipe Book Splitter

A server-side Fabric mod for Minecraft 1.21.11 that splits oversized recipe book packets into several smaller ones.

## What it does

When a player knows thousands of recipes (a modpack with many custom recipes, or `/recipe give @s *`), the server sends
their whole recipe book in one `recipe_book_add` packet. With enough recipes that single packet is too big and the
player is disconnected on join or when the recipes are given:

- vanilla with network compression: `Packet too big (is 9227553, should be less than 8388608)`;
- network compression disabled (common behind a proxy): the 3-byte frame length limit of 2,097,151 bytes,
  `Packet too large: size 9227553 is over 8`;
- Velocity refuses backend packets over 8 MiB as well.

Recipe Book Splitter replaces such a packet with several smaller `recipe_book_add` packets that together carry the same
entries in the same order with the same flags. The client ends up with the same recipe book, highlights and toasts.
Packets that fit are sent as they are.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.19.0 or newer (built and tested against 0.19.5)
- Java 21 or newer
- Server side only. Nothing changes on clients, on Velocity or on other proxies.
- Fabric API is not required: the mod only uses Fabric Loader's API, and the end-to-end scenario E3b ran it on a server
  with neither Fabric API nor Polymer. (The unit and integration tests, and the other scenarios, have Fabric API loaded.)

## Installation

1. Put `recipebooksplitter-<version>.jar` into the `mods/` folder of every Fabric backend server that has the problem.
   Not on Velocity, not on clients.
2. Start the server. The log shows `[RecipeBookSplitter] loaded: ...`, and `config/recipebooksplitter.json` is created
   on the first start.

To build the jar yourself, see [Building from source](#building-from-source).

## Configuration

`config/recipebooksplitter.json`, read once at startup (restart the server to apply changes; `/reload` does not re-read
it):

```json
{
  "maxChunkBytes": 1048576,
  "logSplits": true,
  "logOversizedPackets": false
}
```

| Key | Default | Meaning |
|---|---|---|
| `maxChunkBytes` | `1048576` (1 MiB) | Upper bound for the encoded size of one recipe book packet: packet id plus payload, as produced by the server's packet encoder, before compression and before any ViaVersion translation. Whole numbers only: a value like `1.5` falls back to the default (it is not clamped). Whole numbers are clamped to 65,536 to 2,000,000 with a warning in the log. The upper bound is below the 2,097,151-byte frame limit that applies when network compression is off. |
| `logSplits` | `true` | Log one INFO line every time a packet is split. |
| `logOversizedPackets` | `false` | Log a WARN line for every clientbound packet (of any type) that encodes to more than 4 MiB, before compression and ViaVersion. Useful to find other packets that are close to the limits. |

Missing keys use their defaults, and the file is not rewritten. Wrong types for single keys fall back to that key's
default with a warning, and unknown keys are warned about (this catches typos). A file that cannot be read or is not
valid JSON is reported with an ERROR, the defaults are used, and the file is left untouched.

## How it works

- The mod hooks `Connection.send(Packet, ChannelFutureListener, boolean)`, the one method every server-side send ends up
  in. This runs after the listener-level hooks of other mods (Polymer's packet replacing and preventing, its optional
  count-based recipe splitter), and it also covers packets that mods hand directly to the connection.
- Only `recipe_book_add` packets with at least two entries are looked at; everything else is passed on untouched.
- To measure, each entry is encoded with the connection's real packet encoder (the same code that will write the packet).
  So the sizes are exact for everything that happens inside `PacketEncoder.encode`, including what other mods change
  there, for example Polymer's per-player item rewriting and Fabric API's custom ingredient fallback (the Fabric API part
  follows from the construction and has no test of its own). Anything that handlers between the connection and the
  encoder in the pipeline might do to this packet is not part of the measurement; with the mods tested here that is
  nothing. The measuring, the splitting and the writing run as one task on the connection's Netty thread, not on the
  server thread (see Performance for what that costs).
- The entries are packed greedily, in order, into chunks that stay within `maxChunkBytes`. If the whole packet fits, the
  original packet object is sent unchanged.
- The first chunk keeps the `replace` flag of the original (the client clears its recipe book when it is set), all
  later chunks have `replace=false`. The entries, and with them their notification and highlight flags, are the same
  objects as in the original.
- All chunks are written back to back inside that one task, so no other packet can end up between them. The listener
  and the flush flag of the original send apply to the last chunk.
- If something goes wrong, the original packet is sent unsplit, as without the mod: when the measurement fails, when the
  channel closed while the task was queued, and when another mod has replaced the connection's `encoder` handler with
  something that is not a `PacketEncoder` (one WARN, once; fake-player connections without an encoder, such as Carpet's,
  are only logged at DEBUG).

Example log line from a test server with 4,457 recipes:

```
[RecipeBookSplitter] Tester: split 8.8 MiB recipe book packet (9,227,553 bytes, 4,457 entries, replace=false) into 9 chunks (largest 1,048,106 bytes, limit 1,048,576 bytes, 328 ms)
```

## Compatibility

- **Polymer**: the mod does not depend on Polymer. It was run together with Polymer 0.15.2 (bundled). The measurement
  uses the real encoder and so includes Polymer's encode-time item rewriting; this was checked by reading the code and
  was not tested with a content mod that puts Polymer-backed items into recipes. Keep Polymer's own
  `split_recipe_book_packet_amount` at its default (`-1`); if you enable it anyway, its chunks pass through this mod one
  by one and are split further only if they are still too big.
- **ViaVersion / ViaFabric**: ViaFabric works on the encoded bytes after the packet encoder and does not hook the
  methods this mod uses, so each chunk is translated like any other packet. This was checked by reading ViaFabric
  0.4.22 and was not tested at runtime. Translation can change packet sizes and the mod does not measure that; if you
  support much older clients, keep the default 1 MiB budget, which leaves a lot of headroom below 8 MiB and about half
  of the 2 MiB frame limit.
- **Velocity with FabricProxy-Lite**: nothing to configure, and nothing to install on the proxy. Tested with Velocity
  4.2.0 (modern forwarding) and FabricProxy-Lite 2.11.0 on the backend, with the backend's network compression both on
  (threshold 256) and off, and a protocol client connected through Velocity: every recipe book packet arrived complete
  and in order, none over 1 MiB. Without the mod, the same setup with compression on disconnects the player
  (`Packet too big`). Other Velocity versions were not tested.
- **Network compression** on or off: both work (both were tested). `maxChunkBytes` cannot be set above 2,000,000, which
  keeps packets under the 2,097,151-byte frame limit that applies when compression is off.

## Performance

- Per packet in general: one `instanceof` check in the send hook, and one volatile read of the configuration in the
  encode hook (for `logOversizedPackets`). Recipe book adds with fewer than two entries are passed on after a few more
  field reads.
- **Measuring is not free.** Every recipe book add with two or more entries is encoded one extra time in full (entry by
  entry) to measure it, on the Netty event loop thread of that connection, in addition to the normal encode of the
  packet or its chunks. Other connections on the same event loop thread wait while that runs. The split log line shows
  the time: between 0.13 and 0.46 s for the 9.2 MB book in the end-to-end runs on a small test VM
  (the kit runs with the debug digest on, which also hashes the entry bytes). It happens when a player with a big
  recipe book joins, on `/reload`, when Polymer resends the recipe book after a player changes their client language,
  and when many recipes are unlocked at once (`/recipe give`). Small packets take microseconds. The server thread is
  not blocked by it, and the stall is far below the keep-alive timeouts, but expect a short hitch for the players that
  share the thread.
- The client rebuilds its recipe book once per chunk, so a 9 MB recipe book costs the client about nine rebuilds
  instead of one. By reading the client code, each rebuild also starts a new background build of the recipe search
  index and cancelling the previous one does not stop it, so a small `maxChunkBytes` is costly: 65,536 means about 140
  rebuilds for a 9.2 MB book. This was not measured with a real client.
- The extra encodes call the real `PacketEncoder.encode`. While a `/jfr` recording runs they are counted in its network
  statistics, and if DEBUG is on for `net.minecraft.network.PacketEncoder` they appear in its debug log. Other mods that
  hook `PacketEncoder.encode` with side effects (such as packet counters) would see these probe packets too; none of the
  mods tested here do.

## Debugging

- `logOversizedPackets` (see above) lists every packet over 4 MiB, including packet types this mod does not split.
- Starting the server with `-Drecipebooksplitter.debugDigest=true` additionally logs a SHA-256 over the entry bytes of
  every measured packet (`digest player=... entries=... sha256=...`). The end-to-end kit uses this to compare what the
  server sent with what a client received.

## Limitations

- Recipe book packets inside a `ClientboundBundlePacket` are not split. Vanilla only bundles entity packets, and
  neither Polymer nor Fabric API bundles recipe packets; `logOversizedPackets` would reveal it if another mod does.
- A single recipe display entry that is bigger than `maxChunkBytes` is sent alone in its own packet, with a WARN in the
  log. Splitting cannot help such an entry, and a real client may not be able to read it at all: some item components
  (for example `custom_data`) are decoded with vanilla's 2 MiB NBT quota.
- Size changes caused by ViaVersion's translation are not measured.
- The extra encoding work described under Performance, on a thread that other players share.

## Building from source

Needs JDK 21 or newer to run Gradle (the repository includes the Gradle 9.5.0 wrapper; Fabric Loom 1.17.21 is used).

```sh
./gradlew build
```

The mod is `build/libs/recipebooksplitter-<version>.jar`; do not use the `-sources` jar. The version comes from
`mod_version` in `gradle.properties`.

## Testing

`./gradlew build` also runs the tests:

- `ChunkPlannerTest`: the packing algorithm without Minecraft (budget boundaries, VarInt growth, oversized entries,
  empty and single-entry input, `replace` ordering, flags kept, every chunk under budget, entry totals, randomized
  invariants).
- `SplitterConfigTest`, `SizesTest`: configuration parsing and clamping, number formatting.
- `RecipeBookSplitMinecraftTest`: with Minecraft's real codecs, sizes are exact, split packets decode to the same
  entries, and a simulation of the client's `handleRecipeBookAdd` ends up in the same state as for the unsplit packet.
- `ConnectionSplitIntegrationTest`: a real `Connection` and `PacketEncoder` in a Netty `EmbeddedChannel` (and one
  test on a real event loop thread) with the mod's mixins applied: replace flags, entries, flush, the send listener
  on the last chunk only, pass-through and fallbacks.
- `RecipeBookSendInterceptorTest`: the recursion guard (a recipe packet sent to another player while chunks are
  written is still split), closed channels, missing encoders (log levels), a broken packet.
- `OversizedPacketLoggerTest`: the `logOversizedPackets` warning, including that the measuring probes are not
  reported.

The `e2e/` folder holds an end-to-end kit that runs the jar on a real Fabric server with a data pack that produces a
9.2 MB recipe book, optionally behind Velocity, with a minimal protocol client that records what arrives; see
[e2e/README.md](e2e/README.md). The scenarios in it were all run against the 1.0.0 jar (with and without network
compression, with and without Polymer, without Fabric API, `/reload`, a 4.5 MB single entry, config edge cases, behind
Velocity). That client
is not Minecraft: it does not build a recipe book or enforce the 2 MiB NBT limit, so the end-to-end runs do not replace
a test with a real client.

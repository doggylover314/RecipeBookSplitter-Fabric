#!/usr/bin/env python3
"""Generates a Minecraft 1.21.11 data pack with many shapeless recipes whose result items carry a large
minecraft:custom_data payload. A player who knows all of them gets a recipe book packet of several MiB.

With the defaults (3000 recipes of 3000 random characters) the full recipe book packet is about 9.2 MB, which is
over the 8 MiB limit that disconnects a vanilla server's client.

--mode sets how well the payload compresses (network compression is on in most scenarios):

  alnum          random characters [A-Za-z0-9] in string keys p0, p1, ... (about 6 bits of entropy per byte, so
                 deflate gets it to roughly 75 percent). The default.
  compressible   characters from the two-letter alphabet "ab" (1 bit of entropy per byte, deflate gets it to roughly
                 14 percent).
  incompressible --pad/4 random 32-bit integers (outside the short range, so the data pack loader keeps them as NBT
                 ints) in a list "p"; on the wire every list element is 4 random bytes, so deflate cannot shrink it.
                 The JSON files are about 2.9 times bigger than the payload they carry.
"""
import argparse
import json
import os
import random
import string

# The NBT string encoding (modified UTF-8) limits a string to 65535 bytes; stay well below it.
MAX_STRING_CHARS = 30000
INGREDIENTS = ["minecraft:stick", "minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:sand"]


def chunked_strings(prefix, text):
    return {f"{prefix}{k}": text[k * MAX_STRING_CHARS:(k + 1) * MAX_STRING_CHARS]
            for k in range((len(text) + MAX_STRING_CHARS - 1) // MAX_STRING_CHARS)}


def recipe(ingredients, custom_data):
    return {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "ingredients": ingredients,
        "result": {"id": "minecraft:paper", "count": 1, "components": {"minecraft:custom_data": custom_data}},
    }


def random_int32(rnd):
    while True:
        v = rnd.randrange(-2**31, 2**31)
        if not -32768 <= v <= 32767:  # a value in the short range would be stored as a (smaller) short/byte tag
            return v


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="data pack directory, e.g. <world>/datapacks/rbs_e2e")
    parser.add_argument("--count", type=int, default=3000, help="number of recipes")
    parser.add_argument("--pad", type=int, default=3000, help="payload bytes of custom_data per recipe")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--mode", choices=["alnum", "compressible", "incompressible"], default="alnum")
    parser.add_argument("--grow", action="append", default=[], metavar="K:D",
                        help="make recipe number K carry D extra payload bytes (size tuning; in --mode incompressible "
                             "D is rounded down to a multiple of 4). May be given several times.")
    parser.add_argument("--huge-entry-bytes", type=int, default=0,
                        help="also add the recipe rbs_e2e:huge with this many bytes of (highly compressible) custom_data")
    args = parser.parse_args()

    grow = {}
    for spec in args.grow:
        k, d = spec.split(":")
        grow[int(k)] = int(d)
    rnd = random.Random(args.seed)
    alphabet = string.ascii_letters + string.digits
    recipe_dir = os.path.join(args.out, "data", "rbs_e2e", "recipe")
    os.makedirs(recipe_dir, exist_ok=True)

    with open(os.path.join(args.out, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "RecipeBookSplitter E2E", "min_format": 94, "max_format": 94}}, f)

    for i in range(args.count):
        if args.mode in ("alnum", "compressible"):
            letters = alphabet if args.mode == "alnum" else "ab"
            text = "".join(rnd.choice(letters) for _ in range(args.pad))
            if i in grow:  # extra chars come from their own generator, so no other recipe changes
                extra = random.Random(f"grow{i}")
                text += "".join(extra.choice(letters) for _ in range(grow[i]))
            data = chunked_strings("p", text)
        else:
            ints = [random_int32(rnd) for _ in range(args.pad // 4)]
            if i in grow:  # extra ints come from their own generator, so no other recipe changes
                extra = random.Random(f"grow{i}")
                ints += [random_int32(extra) for _ in range(grow[i] // 4)]
            data = {"p": ints}
        data["i"] = i
        ingredients = [INGREDIENTS[i % 5], INGREDIENTS[(i // 5) % 5]]
        with open(os.path.join(recipe_dir, f"r{i:05d}.json"), "w") as f:
            json.dump(recipe(ingredients, data), f, separators=(",", ":") if args.mode == "incompressible" else None)

    if args.huge_entry_bytes > 0:
        data = chunked_strings("h", "a" * args.huge_entry_bytes)
        with open(os.path.join(recipe_dir, "huge.json"), "w") as f:
            json.dump(recipe(["minecraft:stick", "minecraft:dirt"], data), f)

    print(f"wrote {args.count} recipes ({args.mode}, {args.pad} payload bytes each)"
          + (f" and rbs_e2e:huge ({args.huge_entry_bytes} bytes)" if args.huge_entry_bytes > 0 else "")
          + f" to {args.out}")


if __name__ == "__main__":
    main()

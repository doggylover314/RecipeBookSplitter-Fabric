#!/usr/bin/env python3
"""Generates a Minecraft 1.21.11 data pack with many shapeless recipes whose result items carry a large
minecraft:custom_data payload. A player who knows all of them gets a recipe book packet of several MiB.

With the defaults (3000 recipes of 3000 random characters) the full recipe book packet is about 9.2 MB, which is
over the 8 MiB limit that disconnects a vanilla server's client.
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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="data pack directory, e.g. <world>/datapacks/rbs_e2e")
    parser.add_argument("--count", type=int, default=3000, help="number of recipes")
    parser.add_argument("--pad", type=int, default=3000, help="random characters of custom_data per recipe")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--huge-entry-bytes", type=int, default=0,
                        help="also add the recipe rbs_e2e:huge with this many bytes of (highly compressible) custom_data")
    args = parser.parse_args()

    rnd = random.Random(args.seed)
    alphabet = string.ascii_letters + string.digits
    recipe_dir = os.path.join(args.out, "data", "rbs_e2e", "recipe")
    os.makedirs(recipe_dir, exist_ok=True)

    # Data pack format 94.1 is 1.21.11's.
    with open(os.path.join(args.out, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "RecipeBookSplitter E2E", "min_format": 94, "max_format": 94}}, f)

    for i in range(args.count):
        pad = "".join(rnd.choice(alphabet) for _ in range(args.pad))
        data = chunked_strings("p", pad)
        data["i"] = i
        ingredients = [INGREDIENTS[i % 5], INGREDIENTS[(i // 5) % 5]]
        with open(os.path.join(recipe_dir, f"r{i:05d}.json"), "w") as f:
            json.dump(recipe(ingredients, data), f)

    if args.huge_entry_bytes > 0:
        data = chunked_strings("h", "a" * args.huge_entry_bytes)
        with open(os.path.join(recipe_dir, "huge.json"), "w") as f:
            json.dump(recipe(["minecraft:stick", "minecraft:dirt"], data), f)

    print(f"wrote {args.count} recipes"
          + (f" and rbs_e2e:huge ({args.huge_entry_bytes} bytes)" if args.huge_entry_bytes > 0 else "")
          + f" to {args.out}")


if __name__ == "__main__":
    main()

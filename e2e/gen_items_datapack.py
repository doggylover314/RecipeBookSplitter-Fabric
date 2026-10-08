#!/usr/bin/env python3
"""Generates a Minecraft 1.21.11 data pack with one 3x3 shaped recipe per item: the item in all nine slots, and the same item
as the result. Every item id then appears about 19 times per recipe entry (9 slot displays, 9 ingredients, the result), so
any item whose registry id changes its VarInt length when ViaVersion translates the recipe book for a newer client makes
the packet grow, as much as it can for item ids. Small entries (about 80 bytes), unlike gen_datapack.py's 2 KB ones.

With --lists every slot takes the whole --only list as a direct list of items (not a tag), so each of those ids appears
twice per item in the entry (as an item slot display and as an ingredient entry): with the 27 items whose id passes
127 the entries grow by about 63 % under translation, against about 25 % for one item per recipe.

The items come from --only (a file with one item id per line, or a comma-separated list; they are not validated), or
from the registries report of the vanilla data generator (<out>/reports/registries.json, produced with
`java -cp <server jar and libraries> net.minecraft.data.Main --reports --output <out>`), which then lists every item.
"""
import argparse
import json
import os


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="data pack directory, e.g. <world>/datapacks/rbs_items")
    parser.add_argument("--registries", help="path of reports/registries.json (required unless --only is given)")
    parser.add_argument("--repeat", type=int, default=1, help="write each recipe this many times under different names")
    parser.add_argument("--only", default="", help="comma-separated item ids (minecraft:...) or a file with one per line: "
                        "write recipes for these items only")
    parser.add_argument("--lists", action="store_true", help="every slot takes the whole --only list as a direct item list "
                        "(an ItemSlotDisplay and an Ingredient entry per item) instead of the one item of its recipe")
    args = parser.parse_args()

    entries = None
    if args.registries:
        with open(args.registries) as f:
            entries = json.load(f)["minecraft:item"]["entries"]
    if args.only:
        if os.path.isfile(args.only):
            with open(args.only) as f:
                items = [line.strip() for line in f if line.strip()]
        else:
            items = [x.strip() for x in args.only.split(",") if x.strip()]
        unknown = [w for w in items if entries is not None and w not in entries]
        if unknown:
            raise SystemExit(f"unknown items: {unknown}")
    elif entries is not None:
        items = [k for k, _ in sorted(entries.items(), key=lambda kv: kv[1]["protocol_id"]) if k != "minecraft:air"]
    else:
        parser.error("give --registries or --only")

    recipe_dir = os.path.join(args.out, "data", "rbs_items", "recipe")
    os.makedirs(recipe_dir, exist_ok=True)
    with open(os.path.join(args.out, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "RecipeBookSplitter E2E items", "min_format": 94, "max_format": 94}}, f)
    n = 0
    for rep in range(args.repeat):
        for k, item in enumerate(items):
            recipe = {
                "type": "minecraft:crafting_shaped",
                "category": "misc",
                "pattern": ["AAA", "AAA", "AAA"],
                "key": {"A": items if args.lists else item},
                "result": {"id": item, "count": 1},
            }
            with open(os.path.join(recipe_dir, f"i{rep}_{k:05d}.json"), "w") as f:
                json.dump(recipe, f)
            n += 1
    print(f"wrote {n} recipes ({len(items)} items x {args.repeat}) to {args.out}")


if __name__ == "__main__":
    main()

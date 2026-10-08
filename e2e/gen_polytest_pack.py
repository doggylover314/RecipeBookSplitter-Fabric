#!/usr/bin/env python3
"""Generates a Minecraft 1.21.11 data pack with recipes and item tags for the Polymer e2e scenarios.

The recipes use the server-side Polymer items polytest:p000.. that the rbs-polytest mod registers (default 400 items),
both as direct ingredients and through two item tags (<namespace>:small and <namespace>:large). A Polymer-aware server turns
a tag ingredient into a list of item stacks when it encodes the recipe book packet, so entries with a tag ingredient
are much bigger on the wire than they look in the data pack.

  --vanilla   writes the "twin" of the same pack: the same recipes and tags with vanilla items instead of Polymer
              items (from vanilla_items.txt next to this script), as the baseline for "what the book would weigh without
              Polymer's rewriting".

Pack layout: <out>/pack.mcmeta, <out>/data/<namespace>/tags/item/{small,large}.json, <out>/data/<namespace>/recipe/*.json (namespace polypack)
"""
import argparse
import json
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="data pack directory, e.g. <world>/datapacks/polypack")
    parser.add_argument("--items", type=int, default=400, help="number of Polymer items (-Dpolytest.items of the server)")
    parser.add_argument("--tag-small", type=int, default=150, help="items in the tag polypack:small")
    parser.add_argument("--tag-large", type=int, default=300, help="items in the tag polypack:large")
    parser.add_argument("--tagged", type=int, default=300, help="shapeless recipes with the small tag as one ingredient")
    parser.add_argument("--direct", type=int, default=1200, help="shaped/shapeless/smelting recipes with direct item ingredients only")
    parser.add_argument("--fat", type=int, default=0, help="recipes with several ingredients from the large tag (entries over 64 KiB with Polymer)")
    parser.add_argument("--fat-tags", type=int, default=2, help="ingredients from the large tag in each --fat recipe (each one is about 79 KB on the wire with Polymer 0.15.2 and the default 300-item tag)")
    parser.add_argument("--namespace", default="polypack", help="namespace of the recipes and tags")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--vanilla", action="store_true", help="vanilla-item twin of the pack (no Polymer items)")
    args = parser.parse_args()

    if args.vanilla:
        with open(os.path.join(HERE, "vanilla_items.txt")) as f:
            names = [f"minecraft:{line.strip()}" for line in f if line.strip()]
        if len(names) < args.items:
            parser.error(f"vanilla_items.txt has only {len(names)} items")
        items = names[:args.items]
        result_item = "minecraft:paper"
    else:
        items = [f"polytest:p{i:03d}" for i in range(args.items)]
        result_item = None
    if max(args.tag_small, args.tag_large) > len(items):
        parser.error("a tag cannot hold more items than --items")

    rnd = random.Random(args.seed)
    out = args.out
    recipe_dir = os.path.join(out, "data", args.namespace, "recipe")
    os.makedirs(recipe_dir, exist_ok=True)
    # Data pack format 94.1 is 1.21.11's.
    write_json(os.path.join(out, "pack.mcmeta"), {"pack": {"description": "RecipeBookSplitter Polymer e2e", "min_format": 94, "max_format": 94}})
    write_json(os.path.join(out, "data", args.namespace, "tags", "item", "small.json"), {"values": items[:args.tag_small]})
    write_json(os.path.join(out, "data", args.namespace, "tags", "item", "large.json"), {"values": items[:args.tag_large]})

    def pick():
        return rnd.choice(items)

    def result(count=1):
        item = pick()  # always draw, so the vanilla twin makes the same random choices as the Polymer pack
        return {"id": result_item or item, "count": count}

    n = 0
    kinds = {}

    def emit(recipe, kind):
        nonlocal n
        write_json(os.path.join(recipe_dir, f"r{n:05d}.json"), recipe)
        kinds[f"{args.namespace}:r{n:05d}"] = kind
        n += 1

    for _ in range(args.tagged):
        emit({"type": "minecraft:crafting_shapeless", "category": "misc",
              "ingredients": [f"#{args.namespace}:small", pick()], "result": result(rnd.randint(1, 4))}, "tagged")
    for k in range(args.direct):
        kind = k % 4
        if kind == 0:
            emit({"type": "minecraft:crafting_shapeless", "category": "misc",
                  "ingredients": [pick() for _ in range(rnd.randint(1, 4))], "result": result(rnd.randint(1, 8))}, "direct-shapeless")
        elif kind == 1:
            a, b = pick(), pick()
            emit({"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": ["AB", "BA"],
                  "key": {"A": a, "B": b}, "result": result()}, "direct-shaped")
        elif kind == 2:
            # an ingredient that is a list of items, like {"item": [...]} in older formats
            emit({"type": "minecraft:crafting_shapeless", "category": "building",
                  "ingredients": [[pick() for _ in range(rnd.randint(2, 6))], pick()], "result": result(2)}, "direct-list")
        else:
            emit({"type": "minecraft:smelting", "category": "misc", "ingredient": pick(), "result": result(),
                  "experience": 0.1, "cookingtime": 200}, "direct-smelting")
    for _ in range(args.fat):
        emit({"type": "minecraft:crafting_shapeless", "category": "misc",
              "ingredients": [f"#{args.namespace}:large"] * args.fat_tags, "result": result()}, "fat")

    # Not part of the pack format: lets the checker group recipes by kind.
    write_json(os.path.join(out, "kinds.json"), kinds)
    print(f"wrote {n} recipes ({args.tagged} tagged, {args.direct} direct, {args.fat} fat) and 2 tags "
          f"({args.tag_small}/{args.tag_large} items){' [vanilla twin]' if args.vanilla else ''} to {out}")


if __name__ == "__main__":
    main()

# Warehouse

A client-only Fabric mod that remembers where your items live. Draw regions around your storage, open chests as you normally would, and the mod indexes what it sees. It can then tell you where any item is stored, decide which chest each item belongs in, list items sitting in the wrong chest, and light up the chests you need to visit to empty your inventory. Nothing runs on the server.

## Requirements

| Thing | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | 0.161.0+26.3 |
| Java | 25 |

Install the jar and Fabric API in your `mods` folder. The mod never loads on a dedicated server.

## Quick start

1. **Pick a wand.** The default is a dead brain coral. Change it with `/warehouse wand <item>`.
2. **Draw a region.** Hold the wand and right-click one corner of your storage area, then the opposite corner. Sneak-right-click cancels. Rename it with `/warehouse region rename "Warehouse 1" Main Base`.
3. **Open chests.** Any chest, barrel, shulker box or ender chest you open inside a region is indexed. Armor stands and item frames inside regions are indexed automatically. Chests outside a region are ignored unless you pin them with the small **W** button at the top right of the chest screen.
4. **Hover an item.** The tooltip shows where it is stored and, once a plan exists, where it belongs.
5. **Hold an item inside the region.** The chest it belongs in stays highlighted, with a trail of colored particles along a walkable path to it, for as long as you hold it and stand inside a Warehouse region. With an accepted plan this is the planned destination; without one it is where the item is currently stored. Turn it off with `/warehouse guide off`.
6. **Sneak to check stock.** Hold sneak and a card appears next to the crosshair. Holding an item shows how many of it the warehouse holds, where the most are, how many you carry, and where it belongs. With empty hands it describes the block you are looking at instead. Looking at a chest shows its zone, fill level, last-seen time and top contents. `lookHud` in the config turns it off.
7. **Find anything.** `/warehouse find` (or the *Find held item* key) highlights where the item in your hand is stored, from anywhere. `/warehouse find all` lights every chest holding it.
8. **Search.** Press the **Warehouse** button above your inventory, bind the *Open warehouse search* key in Controls (unbound by default), or run `/warehouse search`. Click a result to highlight that chest in the world for 30 seconds.

## GriefPrevention claims

If the server runs GriefPrevention, `/warehouse claims import` sends `/claimlist` and records each claim's lesser corner. GriefPrevention does not print the far corner, so walk to each claim and right-click a block with a stick. The mod captures the glowstone corner markers it shows you and turns them into a full-height claim region. Containers inside claims are indexed, but the organizer only plans inside Warehouse regions.

## The organizer

Stand near the entrance of your warehouse and run `/warehouse plan run` (or press **Run** on the Plan tab). The mod finds every chest in your Warehouse regions and hands contiguous runs of chests to categories so each zone is physically adjacent, biggest categories nearest to you. Zone sizes come from what it has seen plus a typical warehouse distribution for the capacity it has not seen yet, so a fresh plan is already usable and a replan after opening more chests follows your real contents. Inside each zone every sub-family (oak, spruce, deepslate, red, ...) gets a home chest, so related items cluster instead of piling into the first empty chest.

Filled shulker boxes are filed by the category of their dominant contents; empty or mixed ones, chests, barrels and bundles go to **Storage**. Review it with `/warehouse plan show` or **Preview** (chests are colored by category in the world), then `/warehouse plan accept` or `reject`.

After accepting:

- Tooltips say **Belongs in** for every item, even ones you have never stored.
- The **Condense** tab (see below) tells you when a zone is running out of slots and which items to pack into shulkers.
- The **Misplaced** tab lists stacks sitting in a chest whose zone does not match. Click one to highlight both chests with an arrow between them. Opening the source chest pulses the misplaced slots and shows a **Take misplaced** button; highlights update live as you move items.
- When you open a chest you reached through search, find or the held-item guide, the slots holding that item pulse gold so you can spot it in a full chest.
- **Sort route** (button on the Misplaced tab, or `/warehouse sort`) walks you through the fix: chests with misplaced items and the destinations for whatever you are carrying are ordered into a short tour, numbered in the world, with a particle trail to the next stop. When your inventory is nearly full it steers you to drop-offs first.
- `/warehouse plan replan` keeps zone assignments that still fit and only moves new chests or overflowing categories.
- `/warehouse plan override here` pins the item in your hand to the chest you are looking at. `/warehouse plan category <item or group> <category>` changes a category for this server.

Chests you break or move are forgotten automatically within a few seconds of walking near their old spot, and the mod reminds you to replan once you are done rearranging.

Chunks that are not loaded cannot be scanned. The run report says how many were missed; walk the area and rerun.

## Condensing into shulkers

When a zone gets close to full (80% by default, `condenseAtFillPercent`) the mod looks for items that take up many slots in that zone and works out what packing them into shulker boxes would free. While you stand in the warehouse it says so in chat, once per zone every 30 minutes:

> Building is 88% full. Packing Cobblestone, Stone and 2 more into 4 shulkers would free 61 slots. See /warehouse condense.

The **Condense** tab (or `/warehouse condense`, `/warehouse condense list` for chat) lists every zone with its fill level and, per item: how many slots it uses now, how many shulkers it fits in, how many slots that frees, and which chest to put the packed shulker back into (the chest that held most of it, which has room once emptied). Click a row to highlight the chests holding that item, shift-click for only the biggest one. The footer shows how many empty shulkers you carry and how many are indexed. Items already inside shulkers and the Storage category itself are never suggested; `condenseMinStacks` and `condenseMinSlotsFreed` set the bar for a suggestion.

Filled shulkers are filed by the category of their dominant contents, so once packed the shulker resolves back to the same zone.

## Clear inventory mode

Bind the *Toggle clear-inventory mode* key or run `/warehouse clearmode`. Every non-essential stack in your inventory lights up its destination chest, colored by category and labeled with the item and count. The chests are ordered into a short walking route from where you stand, numbered, with a particle trail leading to stop 1. Open a lit chest and press **Deposit matching** to quick-move everything that belongs there. The mode exits on its own when nothing is left to put away.

Essentials are kept: equipped armor, offhand, tools and weapons on the hotbar, food, custom gear, and anything on the `alwaysKeep` list in the config.

## Lost items

Dropping an item (Q, ctrl-Q, throwing out of a GUI, or clicking outside a window with a stack in hand) writes an entry to the lost log and removes the item from tracking. Dying writes one entry per stack at the death position. The **Lost** tab shows what, how many, where, when and why; click an entry to highlight the spot. Entries expire after 5 minutes, deaths after 60 (both configurable). Picking items back up is treated as a brand-new item; nothing is matched back to the log.

## Custom items

Server items with special components are identified by item id plus a fingerprint of their data components (shulker and bundle contents excluded), grouped by display name. The planner then looks at the components it sampled to decide what the item really is:

- Vanilla variants (enchanted books, potions, tipped arrows, dyed leather, fireworks, written books) keep the category of their base item.
- Plugin gear (attribute modifiers, custom equippable or enchanted tools) stays in Tools & Weapons or Armor.
- Crate keys, vouchers, tokens and anything whose lore says it is currency go to **Keys & Currency**.
- "Infinite" or "unlimited" placeable blocks go to **Infinite Items**, grouped by colour.
- Plushies, pendants, hats, crates, satchels and similar go to **Collectibles**, grouped by the plugin pack they came from.
- Anything else from a plugin lands in **Custom**, also grouped by pack, so one event's items share a chest.

Plugin markers recognised: ExecutableItems (`ei-id`), ExcellentCrates (`crate_key.id`), ItemsAdder and MMOItems custom data, plus `custom_model_data` / `item_model`. Chest-style plugin menus (crates, editors, shops) are detected and skipped so their buttons do not end up in the index. For every variant it meets, the mod also keeps one full sample of the item's components and lore in `items.json` (capped at 40 per group), so group splits and planner mistakes can be diagnosed from an export. Set `collectItemSamples` to `false` in the config to turn that off. Volatile components (damage, repair cost, custom name) and lore lines that look like durability or ownership are stripped before hashing so one item type does not split into many groups. Adjust the lists in the config if your server's items still split. Manage groups with:

```
/warehouse items list
/warehouse items rename <group> <new name>
/warehouse items merge <groupA> <groupB>
/warehouse items category <group> <category>
```

## Controller support (Controlify add-on)

Controlify already turns the mod's keys into controller bindings and can click the screen's buttons, but the result list is not made of widgets, so it cannot be scrolled or selected with a stick. The separate **warehouse-controlify** jar fixes that. Put it in `mods` next to the warehouse jar and [Controlify](https://modrinth.com/mod/controlify); it does nothing without both.

- **Warehouse screen:** down from the bottom row of controls enters the result list; up/down move the selection (hold to repeat), left/right or the bumpers switch tabs, up from the first row returns to the controls. Press selects the row (highlight), *GUI abstract action 1* does the shift-click variant (all locations), *GUI abstract action 2* opens the on-screen keyboard on the search box. The selected row's tooltip is shown beside it and a button legend sits at the bottom right. The search box is no longer focused automatically while a controller is in use, so the on-screen keyboard only opens when you ask for it.
- **Bindings:** *Open warehouse search*, *Find held item*, *Toggle clear-inventory mode* and *Toggle region outlines* appear under a Warehouse category in Controlify's binding menu and can be put on the radial menu. Two container-screen bindings, *Take misplaced* and *Deposit matching*, are unbound by default; bind them in Controlify's settings and they act on the chest you have open.

The add-on builds from the `controlify-addon` folder and is published with each release.

## Tuning custom item sorting without a new jar

Plugin items are classified by the rules in `custom_item_rules.json`. The jar ships defaults; `config/warehouse/custom_item_rules.json` overrides them, and a copy in the shared folder (below) overrides both, matched by rule id. A rule matches when every field it gives matches, as case-insensitive regexes:

```json
{ "id": "ei-infinite-blocks", "priority": 100, "note": "why",
  "match": { "pluginId": "^(GLASS|CONCRETE)_", "name": "...", "lore": "...", "itemId": "...", "customDataKey": "...", "crateKey": true, "pluginMarked": true },
  "category": "Infinite Items", "family": "$base" }
```

`family` is a literal, `$base` (the base item's family such as its colour or wood type) or `$pack` (the plugin pack prefix). `/warehouse rules list` shows what is active and where each rule came from; `/warehouse rules reload` picks up edits, and both files are also watched while you play.

Two things keep this unobtrusive:

- **Stable fingerprints.** Plugins stamp per-copy ids and counters into an item's custom data (ExecutableItems' `ei-disablestack` UUID and usage score, for example), which would give every copy its own fingerprint and a wrong first guess. `strippedCustomDataPaths` in the config lists the paths removed before hashing. Items in your hand are also registered on sight, so a fresh fingerprint is read by name before the guide sends you anywhere.
- **Custom items stay put.** When a rule changes where a plugin item belongs, copies already sitting in a planned chest keep that chest as their home and are never flagged misplaced (`customItemsStayPut`). Only new copies follow the new rule.

### Shared folder and the manifest

Set `sharedDir` in the config to a folder synced by Google Drive, Dropbox or similar. The mod then writes `warehouse-manifest-<server>.json` there every `manifestExportIntervalMinutes` (also `/warehouse manifest export`): every custom item group with its samples, how it currently classifies and why, and where it is stored. Anything that edits `custom_item_rules.json` in that folder, a person or a scheduled Claude session, changes sorting the next time the mod reloads rules. Check a rules file against a manifest before using it:

```
./gradlew rulesDryRun --args="warehouse-manifest-server.json custom_item_rules.json --all"
```

It prints each group's category before and after, without starting Minecraft.

## Export and import

`/warehouse export [name]` writes everything for the current server to `config/warehouse/exports/<name>.json` (gzipped above 5 MB). Copy the file to another PC and run `/warehouse import <file>` to merge, or `/warehouse import <file> replace` to wipe and load. The Plan tab also has an **Export** button.

## Commands

```
/warehouse region list | rename <old> <new> | delete <name> | outlines [on|off] | cancel
/warehouse wand [item]
/warehouse claims import | reimport | list | clear
/warehouse find [all]
/warehouse guide [on|off]
/warehouse search [query]      /warehouse misplaced      /warehouse lost      /warehouse unsorted
/warehouse condense [list]     (what to pack into shulkers when a zone is low on space)
/warehouse rules list | reload   /warehouse manifest export
/warehouse plan run | replan | accept | reject | show | clear | count <n> | preview [on|off]
/warehouse plan override here | clear
/warehouse plan category <item or group> <category>
/warehouse items list | rename | merge | category
/warehouse clearmode               /warehouse sort
/warehouse forget here             (drop the container you are looking at from the index)
/warehouse items prune             (remove item groups that no longer match anything known)
/warehouse highlight clear
/warehouse export [name] | export withlost
/warehouse import [file] [merge|replace]
/warehouse clear index | lost | plan | items | all   (then /warehouse clear confirm)
/warehouse debug index [full] | hand | inventory | categories
```

## Keys

| Key | Default |
|---|---|
| Toggle region outlines | O |
| Open warehouse search | unbound |
| Find held item | unbound |
| Toggle clear-inventory mode | unbound |

## Files

```
config/warehouse/
  config.json              global settings: wand, colors, particle guide, prior zone shares, essentials rules, strip lists, shared folder
  custom_item_rules.json   your overrides for plugin item sorting (optional)
  <server-key>/            one folder per server address, or sp-<world> for singleplayer
    regions.json  claims.json  index.json  items.json  plan.json  lost.json
  exports/                 export bundles
```

Everything the mod knows is what the client has seen. Every location is "last seen at" a time, never a guarantee, and the UI colors entries by how stale they are.

## Building

```
./gradlew build
```

Needs JDK 25. The mod jar lands in `build/libs/`, the Controlify add-on in `controlify-addon/build/libs/`.

## Releasing

Push a tag like `v0.1.0`, or open the **Actions** tab, pick **Release** and press *Run workflow*. GitHub builds both jars and publishes a release named after `mod_version` and `minecraft_version` in `gradle.properties`, using `.github/release-notes.md` as the description. Bump `mod_version` before tagging a new version.

## Categories

Building Blocks, Colored Blocks (wool, carpet, concrete, terracotta, glass), Natural, Wood, Stone, Ores & Minerals, Redstone, Tools & Weapons, Armor, Food, Farming, Mob Drops, Dyes & Decoration, Potions & Brewing, Enchanting, Workstations, Transport, Storage, Keys & Currency, Infinite Items, Collectibles, Custom, Misc.

`categoryPriorShares` in the config sets how unexplored capacity is split between them; `guideParticles` / `guideLine` choose how you are led to a chest.

## License

MIT. See `LICENSE`.

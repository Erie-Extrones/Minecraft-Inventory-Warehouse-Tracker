Client-only Fabric mod that remembers where your items live, decides which chest each item belongs in, and guides you there.

**Requirements:** Minecraft 26.3 · Fabric Loader 0.19.5+ · Fabric API 0.161.0+26.3 · Java 25. Drop the jar into your `mods` folder next to Fabric API. Nothing runs on the server.

### Highlights
- Draw Warehouse regions with a wand (default dead brain coral); GriefPrevention claims can be imported.
- Every chest, barrel, shulker box, ender chest, armor stand and item frame you open inside a region is indexed, including shulker contents.
- Hover an item to see where it is stored and where it belongs. Hold sneak to see the warehouse stock of the item in your hand or the block you look at.
- Hold an item inside the warehouse and its destination chest stays highlighted with a particle trail along a walkable path.
- Organizer: `/warehouse plan run` assigns categories to zones of adjacent chests and gives every sub-family (oak, spruce, red, deepslate...) a home chest. Replan keeps what still fits.
- Misplaced tab and **Sort route**: walk a numbered tour to pull wrong-chest items and put them where they belong. Clear-inventory mode does the same for whatever you carry.
- 23 categories including Colored Blocks, Workstations, Enchanting, Keys & Currency, Infinite Items and Collectibles; plugin items (ExecutableItems, ExcellentCrates, ItemsAdder, MMOItems) are recognised and grouped by pack.
- Lost log for drops and deaths, export/import between PCs, chests that disappear are forgotten automatically.

### Getting started
1. Hold the wand, right-click a floor corner and the opposite ceiling corner of your storage room.
2. Open your chests once.
3. Stand near the entrance, run `/warehouse plan run`, check `/warehouse plan show`, then `/warehouse plan accept`.
4. Bind *Open warehouse search* in Controls, or press the **Warehouse** button above your inventory.

Full command list and configuration in the README. Data lives in `config/warehouse/` and survives updates.

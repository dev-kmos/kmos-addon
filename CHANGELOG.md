# Changelog

## 2.7

Release `2.7` is the main feature update after `v0.2.0`.

### Added

- Added `Auto Enchanting`, a full max-enchant workflow for the item in the main hand.
- Added enchanted-book storage tracking for chests, barrels, ender chests and shulker boxes.
- Added scanned storage entries with position, enable toggle, last scanned title, stored book counts and per-book reports.
- Added support for learning opened containers through `scan-opened-containers`, while still allowing manually configured storages.
- Added enchanted-book aggregation from tracked storages and from books already in the player inventory.
- Added the `EnchantingPlanSolver`, which calculates low-cost anvil merge plans from available books.
- Added missing-book reporting when a target cannot be planned from cached books.
- Added `Preview Max Cost` for calculating the route and level cost without executing it.
- Added action buttons for `Max Held Item Now`, `Preview Max Cost` and `Continue After Table Enchant`.
- Added clean-item bootstrap: when enabled, a clean supported item can first receive a 30-level enchanting-table roll, then show a post-table preview before continuing.
- Added automatic movement to nearby storages, enchanting tables and anvils using Baritone commands.
- Added per-run Baritone handling for Auto Enchanting, including pause/resume and temporary local `allowBreak=false` support.
- Added final item rename support with `<item>` and `<shortitem>` placeholders.
- Added rename-only handling for items that are already maxed but still need the configured final name.
- Added final-rename safeguards and fallback rename-only flow when the final result syncs without the requested name.
- Added retry handling for interrupted anvil steps, broken anvils and failed result syncs.
- Added target presets for swords, pickaxes, axes, shovels, hoes, fishing rods, helmets, chestplates, leggings and boots.
- Added custom target groups for each supported item type, with individual enchant levels where `0` disables an enchant.
- Added configurable enchant preferences for maxing, including Fortune vs Silk Touch, Depth Strider vs Frost Walker, Thorns, Knockback, Fire Aspect, Swift Sneak, Soul Speed, Blast Protection on leggings, Sharpness on axes and Efficiency on axes.
- Added fishing rod maxing support.
- Added `Auto Enchant Table Batch`, which enchants selected clean inventory items at the nearest enchanting table.
- Added batch enchant-table preflight checks for selected item count, required lapis and minimum starting level.
- Added configurable item/material selection for batch table enchanting, including diamond/netherite armor and tools by default plus optional hoes, bows, crossbows, tridents and fishing rods.
- Added `Auto Shulker Pack`, which packs filtered inventory items into a placed shulker when inventory space is low.
- Added shulker packing filters with blacklist/whitelist modes and default protection for valuable/utility items.
- Added shulker placement, opening, transfer, closing, breaking and pickup handling.
- Added Baritone stop/resume integration for shulker packing, including remembering the last Baritone work command when possible.
- Added shared interaction ownership for automation flows to avoid storage, anvil, shulker and table actions fighting each other.
- Added custom Meteor settings widgets for action buttons and the enchanted-storage list UI.
- Added release-build copying so `gradlew build` also writes the remapped jar to a local sibling `release-builds` directory by default.

### Changed

- Improved `Auto Anti AFK` activity detection so keyboard/mouse input, use/attack keys, movement keys, open screens, item use, hand swings and inventory changes refresh activity.
- Updated README module documentation for the newly added automation modules.
- Updated version metadata from `0.2.0` to `2.7` for the public release.

### Compatibility

- Minecraft target remains `1.21.10`.
- Java target remains `21`.
- Meteor Client target remains `1.21.10-15`.

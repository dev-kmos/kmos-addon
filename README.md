# KMOS Addon

Author: `_KMOS_`

KMOS Addon is a Meteor Client addon focused on utility and automation modules for Minecraft 1.21.10.

## Requirements

- Minecraft `1.21.10`
- Java `21`
- Meteor Client `1.21.10-15`

## Modules

- `Auto Anti AFK` - enables Meteor's `AntiAFK` after inactivity and can optionally enable `KillAura`
- `Auto Chest Transfer` - opens configured chests and moves selected items in or out automatically
- `Auto Enchanting` - scans enchanted-book storages, plans low-cost anvil routes and max-enchants the main-hand item
- `Auto Enchant Table Batch` - enchants selected clean inventory items at the nearest enchanting table after checking required lapis and levels
- `Auto Shulker Pack` - packs filtered inventory items into a placed shulker box and resumes the remembered Baritone command
- `Auto Trade Plus` - executes configured villager trades from a priority list
- `Auto Villager Click` - interacts with nearby villagers one by one
- `Baritone Material Debug Beta` - captures Baritone missing-material pauses and can test shulker refills
- `Elytra Auto Swap` - swaps out a low-durability elytra for a better one
- `Master Mute` - temporarily mutes master volume and restores it on disable
- `Module Setups Manager` - manages saved Meteor module profiles

## 2.7 Highlights

Release `2.7` is a major update over `v0.2.0`.

### Auto Enchanting

- Tracks enchanted-book storages from configured or scanned containers.
- Caches book counts per storage and shows scanned reports in the module settings.
- Aggregates matching books from tracked storages and player inventory.
- Calculates low-cost anvil merge plans before starting the automation.
- Supports preview-only planning through `Preview Max Cost`.
- Can max supported swords, tools, armor and fishing rods.
- Provides per-item target settings and configurable enchant preferences.
- Can bootstrap clean items through a nearby 30-level enchanting table, then wait for confirmation before maxing.
- Can rename the finished item during the final anvil step.
- Handles already-maxed items with rename-only anvil flow.
- Retries interrupted anvil steps and handles broken anvils where possible.
- Uses Baritone for movement to storages, enchanting tables and anvils.

### Auto Enchant Table Batch

- Finds the nearest enchanting table within the configured range.
- Enchants selected clean inventory items.
- Checks the planned item count, required lapis and required starting level before beginning.
- Defaults to diamond/netherite armor and diamond/netherite sword, pickaxe, axe and shovel.
- Can optionally include hoes, bows, crossbows, tridents and fishing rods.

### Auto Shulker Pack

- Starts when inventory free space drops below the configured threshold.
- Selects a usable shulker, places it, opens it, moves filtered items into it, closes it and picks it back up.
- Supports blacklist and whitelist item filters.
- Protects tools, armor, food, totems and shulkers by default in blacklist mode.
- Stops Baritone before packing and can resume the remembered work command afterward.

### Other Changes

- `Auto Anti AFK` now treats more user activity as real activity, including inventory changes and common interaction keys.
- Automation modules use shared interaction ownership to avoid multiple flows fighting over containers or screens.
- Build output is copied automatically to the shared `release-builds` directory.

## Logging

The addon writes diagnostic logs to `logs/kmos-addon.log`.

Log output is sanitized before being written to disk so that common sensitive values such as world coordinates and local file paths are redacted. Users can attach logs to GitHub issues more safely, but should still review them before sharing.

## Building

```powershell
.\gradlew.bat build
```

The built jar is created in `build/libs`.

Release builds are also copied to a sibling `release-builds` directory by default:

```text
../release-builds/
```

Override this with `-Pkmos.releaseBuildsDir=<path>` or `KMOS_RELEASE_BUILDS_DIR`.

## Versioning

This repository uses manual versioning for public releases. The current public release target is `2.7`.


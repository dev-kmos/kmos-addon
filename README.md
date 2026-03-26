# KMOS Addon\n\nAuthor: `_KMOS_`

KMOS Addon is a Meteor Client addon focused on utility and automation modules for Minecraft 1.21.10.

## Requirements

- Minecraft `1.21.10`
- Java `21`
- Meteor Client `1.21.10-15`

## Modules

- `Auto Anti AFK` - enables Meteor's `AntiAFK` after inactivity and can optionally enable `KillAura`
- `Auto Chest Transfer` - opens configured chests and moves selected items in or out automatically
- `Auto Trade Plus` - executes configured villager trades from a priority list
- `Auto Villager Click` - interacts with nearby villagers one by one
- `Baritone Material Debug Beta` - captures Baritone missing-material pauses and can test shulker refills
- `Elytra Auto Swap` - swaps out a low-durability elytra for a better one
- `Master Mute` - temporarily mutes master volume and restores it on disable
- `Module Setups Manager` - manages saved Meteor module profiles

## Logging

The addon writes diagnostic logs to `logs/kmos-addon.log`.

Log output is sanitized before being written to disk so that common sensitive values such as world coordinates and local file paths are redacted. Users can attach logs to GitHub issues more safely, but should still review them before sharing.

## Building

```powershell
.\gradlew.bat build
```

The built jar is created in `build/libs`.

## Versioning

This repository uses manual versioning for public releases. The current public release target is `0.2.0`.


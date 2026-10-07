# ModLoader-Agent

> **Run Fabric mods on Leaf servers** — a compatibility layer that bridges Fabric mods and Paper/Leaf.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-green.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![Platform](https://img.shields.io/badge/Platform-Leaf-blue.svg)](https://www.leafmc.one/)

---

## ⚠️ Important

**ModLoader-Agent v2 only supports the Leaf server.**

- **Download Leaf:** [leafmc.one/download](https://www.leafmc.one/en/download)
- `fabric-server-launcher.properties` must be edited to point to the correct server jar.
- Not compatible with vanilla Paper, Purpur, or other forks.
- **EXPERIMENTAL** — always test before production use.

---

## 📖 What is ModLoader-Agent?

ModLoader-Agent is a **Java agent** that allows you to run **Fabric mods** on a **Leaf server** — without switching to a full Fabric server.

It works by:
- Loading Fabric Loader + Mixin inside the Leaf server process
- Patching Paper/Leaf bytecode changes via a custom mapping system
- Bridging Bukkit events ↔ Fabric events
- Registering Fabric mods as Bukkit plugins

This is **not** a full Fabric server. It's a compatibility layer for **simple to moderately complex mods**.

---

## ✨ Features

- ✅ Loads Fabric mods (`.jar` with `fabric.mod.json`) on Leaf
- ✅ Supports Fabric API (all modules) + Mixin + MixinExtras
- ✅ Access Widener (partial support)
- ✅ Bridges Bukkit ↔ Fabric events
- ✅ Registers Fabric mods as Bukkit plugins (visible in `/plugins`)
- ✅ Custom mapping system (`mappings.tiny`) for Paper/Leaf ↔ Fabric compat
- ✅ Supports Minecraft **26.2** and **26.1.2** (Java 25)

---

## 🚀 How to use

### Requirements

- **Java 25** (Oracle JDK or OpenJDK)
- **Leaf server** 26.2 or 26.1.2
- Fabric mods placed in `/mods/` folder

### Steps

1. **Download** `modloader-agent.jar` from [Modrinth](https://modrinth.com/mod/modloader) or [Releases](https://github.com/562012bo/modloader-agent/releases).

2. **Place** `modloader-agent.jar` in your server directory.

3. **Place** Fabric mods in `/mods/` folder.

4. **Edit** `fabric-server-launcher.properties` to point to the correct server jar:
   ```properties
   serverJar=leaf.jar

# VCF Content Factory UniFi Controller Documentation

> Generated index. The SVG diagram and per-kind table are regenerated on every
> build; prose sections (overview, installing) are hand-curated.

## Contents

| Section | Description |
|---------|-------------|
| [Overview](overview.md) | What's in the pack, resource kinds, cross-adapter notes |
| [Installing & Configuring](installing.md) | Prerequisites, configuration fields, step-by-step guide |
| [Inventory Tree](inventory-tree.md) | Traversal spec, per-kind table with identifying keys |
| [Metrics Reference](../REFERENCE.md) | Full metrics and properties reference (generated) |

## Inventory Tree

![Inventory Tree](inventory-tree.svg)

## Quick Reference

- **Adapter kind:** `unifi_controller`
- **Version:** 0.0.0.18
- **Traversal spec:** UniFi Controller Infrastructure
- **Resource kinds:** 11

## Adapter-Specific Documentation

<!-- Merged from docs/_index_extra.md; edit that file, not this section. -->

### Alerts

The pak ships eight alerts with their symptoms and recommendations (build
16): gateway, switch, access point and camera offline, uplink port down,
camera not recording, PoE budget nearly exhausted and WAN degraded. What
each fires on, its object and severity: [REFERENCE.md, Alerts](../REFERENCE.md#alerts).

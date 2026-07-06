# Inventory Tree — VCF Content Factory UniFi Controller

> Generated from `describe.xml` v0.0.0.10. Do not edit — regenerated on every build.

**Traversal Spec:** UniFi Controller Infrastructure

## Traversal Tree

- **UniFi Controller** (`unifi_controller`)
  - **UniFi World** (`UniFiWorld`)
    - **UniFi Site** (`UniFiSite`)
      - **UniFi Gateway** (`UniFiGateway`)
        - **UniFi WAN Interface** (`UniFiWanInterface`)
      - **UniFi Switch** (`UniFiSwitch`)
        - **UniFi Switch Port** (`UniFiSwitchPort`)
      - **UniFi Access Point** (`UniFiAccessPoint`)
        - **UniFi Radio** (`UniFiRadio`)
      - **UniFi NVR** (`UniFiNvr`)
        - **UniFi Camera** (`UniFiCamera`)
      - **UniFi Wireless Summary** (`UniFiWirelessAggregate`)

> \* = identifying (unique) key

## Resource Kinds Reference

| Kind | Display Label | Identifying Keys | Parent(s) |
|------|--------------|-----------------|-----------|
| `UniFiWorld` | UniFi World | `world_id` * | UniFi Controller |
| `UniFiSite` | UniFi Site | `site_name` * | UniFi World |
| `UniFiGateway` | UniFi Gateway | `mac` * | UniFi Site |
| `UniFiWanInterface` | UniFi WAN Interface | `wan_name` * | UniFi Gateway |
| `UniFiSwitch` | UniFi Switch | `mac` * | UniFi Site |
| `UniFiSwitchPort` | UniFi Switch Port | `port_key` * | UniFi Switch |
| `UniFiAccessPoint` | UniFi Access Point | `mac` * | UniFi Site |
| `UniFiRadio` | UniFi Radio | `radio_key` * | UniFi Access Point |
| `UniFiWirelessAggregate` | UniFi Wireless Summary | `aggregate_id` * | UniFi Site |
| `UniFiNvr` | UniFi NVR | `nvr_mac` * | UniFi Site |
| `UniFiCamera` | UniFi Camera | `camera_mac` * | UniFi NVR |

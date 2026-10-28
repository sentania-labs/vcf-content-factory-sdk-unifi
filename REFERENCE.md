# VCF Content Factory UniFi Controller: Reference

Generated from `describe.xml` and `resources.properties` for build 18 (dev 0.0.0.18).

## Adapter

| Field | Value |
|---|---|
| Adapter Kind | `unifi_controller` |
| Tier | 2 (Java SDK) |
| Monitoring Interval | 5 minutes |
| License Required | No |

### Credentials

| Field | Key | Type |
|---|---|---|
| Username | `username` | string |
| Password | `password` | string (masked) |

### Connection Settings

| Field | Key | Default | Required |
|---|---|---|---|
| Host / IP Address | `host` | - | Yes |
| Port (HTTPS) | `port` | 443 | No |
| Allow Insecure SSL | `allowInsecure` | false | No |

---

## Object Types

### UniFi World

**Identifier**: `world_id` (World ID)

---

### UniFi Site

**Identifier**: `site_name` (Site Name)

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `description` | Description | property | - | - |
| `version` | Version | property | - | - |
| `timezone` | Timezone | property | - | - |
| `device_count` | Device Count | property | - | - |

---

### UniFi Gateway

**Identifier**: `mac` (MAC Address)

#### System

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `cpu_pct` | CPU % | metric | % | yes |
| `mem_pct` | Memory % | metric | % | yes |
| `uptime` | Uptime | metric | sec | yes |
| `num_sta` | Connected Clients | metric | - | yes |

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `state` | State (1 = connected) | metric | - | yes |
| `state_code` | State Name | property | - | - |

#### Temperature

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `temp_cpu` | CPU Temperature | metric | C | yes |
| `temp_local` | Board Temperature | metric | C | yes |
| `temp_phy` | PHY Temperature | metric | C | no |

#### Speedtest

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `xput_up` | Upload Speed | metric | Mbps | yes |
| `xput_down` | Download Speed | metric | Mbps | yes |
| `speedtest_latency` | Speedtest Latency | metric | ms | yes |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `model` | Model | property | - | - |
| `firmware` | Firmware | property | - | - |
| `serial` | Serial Number | property | - | - |
| `ip` | IP Address | property | - | - |
| `mac_address` | MAC Address | property | - | - |
| `name` | Name | property | - | - |

---

### UniFi WAN Interface

**Identifier**: `wan_name` (Interface Name)

#### Traffic

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `tx_bytes` | TX Bytes | metric | bytes | yes |
| `rx_bytes` | RX Bytes | metric | bytes | yes |
| `tx_rate` | TX Rate | metric | bytes/s | yes |
| `rx_rate` | RX Rate | metric | bytes/s | yes |

#### Health

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `latency` | Latency | metric | ms | yes |
| `availability` | Availability | metric | % | yes |
| `speed` | Speed | metric | Mbps | no |

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `up` | Link Up | property | - | - |

> Build 17: `true` or `false` from the gateway's `wan1` / `wan2` object
> (`up`), published only when the controller sends the field, never
> `false` for a missing one. An unused second WAN (nothing plugged in)
> reads `false`; WAN Degraded is scoped to `true`. The controller sends no
> field that tells a configured WAN from an unused one (see Alerts, Limits).

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `type` | Type | property | - | - |
| `ip` | IP Address | property | - | - |
| `netmask` | Netmask | property | - | - |
| `gateway_ip` | Gateway IP | property | - | - |
| `dns` | DNS | property | - | - |

---

### UniFi Switch

**Identifier**: `mac` (MAC Address)

#### System

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `cpu_pct` | CPU % | metric | % | yes |
| `mem_pct` | Memory % | metric | % | yes |
| `uptime` | Uptime | metric | sec | yes |
| `num_sta` | Connected Clients | metric | - | yes |
| `satisfaction` | Satisfaction | metric | % | yes |

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `state` | State (1 = connected) | metric | - | yes |
| `state_code` | State Name | property | - | - |

#### PoE

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `total_max_power` | PoE Budget Max | metric | W | yes |
| `poe_consumption` | PoE Consumption | metric | W | yes |
| `poe_budget_remaining` | PoE Budget Remaining | metric | W | yes |
| `poe_budget_used_pct` | PoE Budget Used % | metric | % | yes |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `model` | Model | property | - | - |
| `firmware` | Firmware | property | - | - |
| `serial` | Serial Number | property | - | - |
| `ip` | IP Address | property | - | - |
| `mac_address` | MAC Address | property | - | - |
| `name` | Name | property | - | - |
| `port_count` | Port Count | property | - | - |
| `poe_capable` | PoE Capable | property | - | - |
| `has_fan` | Has Fan | property | - | - |

---

### UniFi Switch Port

**Identifier**: `port_key` (Port Key)

#### Traffic

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `tx_bytes` | TX Bytes | metric | bytes | yes |
| `rx_bytes` | RX Bytes | metric | bytes | yes |
| `tx_errors` | TX Errors | metric | - | yes |
| `rx_errors` | RX Errors | metric | - | yes |
| `tx_rate` | TX Rate | metric | bytes/s | yes |
| `rx_rate` | RX Rate | metric | bytes/s | yes |

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `satisfaction` | Satisfaction | metric | % | yes |
| `mac_table_count` | MAC Table Count | metric | - | no |
| `up` | Link Up | property | - | - |
| `speed` | Speed | property | - | - |
| `duplex` | Duplex | property | - | - |
| `is_uplink` | Is Uplink | property | - | - |
| `media` | Media | property | - | - |
| `stp_state` | STP State | property | - | - |
| `port_name` | Port Name | property | - | - |

> Build 17: `up` and `is_uplink` (`true` / `false`) and `duplex` (`Full` /
> `Half`) are published only when the controller sends `up`, `is_uplink`
> and `full_duplex`; a missing field publishes nothing (before build 17 it
> read `false`, or `Half`).

#### PoE

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `poe_power` | PoE Power | metric | W | yes |
| `poe_voltage` | PoE Voltage | metric | V | no |
| `poe_current` | PoE Current | metric | mA | no |
| `poe_enable` | PoE Enabled | property | - | - |
| `poe_class` | PoE Class | property | - | - |
| `poe_mode` | PoE Mode | property | - | - |

#### LLDP

> Build 9: repurposed from the dead controller-side read. The controller's
> own `port_table[].lldp_table` is empty on Network App 10.2.105 (see
> `context/investigations/unifi-lldp-switchport-esxi-2026-07-05.md`), so these
> two properties are now populated from the vCenter-side vmnic→port join
> (`net:vmnic<N>|discoveryProtocol|lldp|*` on the matched VMWARE HostSystem):
> the same join that emits the `HostSystem → UniFiSwitchPort` foreign
> relationship edge. `lldp_chassis_id` had no honest source under either
> design and is removed. Build 11: if two *different* hosts both claim the
> same switch port as an LLDP neighbour in the same collect cycle
> (contradictory data), the property is written for neither claimant:
> the portKey is marked conflicted for the rest of that cycle and the
> conflict is counted in the adapter's per-cycle stitch summary log line.
> The `HostSystem → UniFiSwitchPort` relationship edge is unaffected and
> is still emitted for every match, including conflicted ones.

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `lldp_system_name` | LLDP System Name | property | - | - |
| `lldp_port_id` | LLDP Port ID | property | - | - |

---

### UniFi Access Point

**Identifier**: `mac` (MAC Address)

#### System

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `cpu_pct` | CPU % | metric | % | yes |
| `mem_pct` | Memory % | metric | % | yes |
| `uptime` | Uptime | metric | sec | yes |
| `num_sta` | Connected Clients | metric | - | yes |
| `satisfaction` | Satisfaction | metric | % | yes |

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `state` | State (1 = connected) | metric | - | yes |
| `state_code` | State Name | property | - | - |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `model` | Model | property | - | - |
| `firmware` | Firmware | property | - | - |
| `serial` | Serial Number | property | - | - |
| `ip` | IP Address | property | - | - |
| `mac_address` | MAC Address | property | - | - |
| `name` | Name | property | - | - |

---

### UniFi Radio

**Identifier**: `radio_key` (Radio Key)

#### RF

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `channel` | Channel | metric | - | yes |
| `tx_power` | TX Power | metric | dBm | yes |
| `cu_total` | Channel Utilization | metric | % | yes |
| `satisfaction` | Satisfaction | metric | % | yes |
| `tx_retries_pct` | TX Retries % | metric | % | yes |

#### Clients

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `user_num_sta` | Client Count | metric | - | yes |

#### Traffic

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `tx_bytes` | TX Bytes | metric | bytes | yes |
| `rx_bytes` | RX Bytes | metric | bytes | yes |
| `tx_rate` | TX Rate | metric | bytes/s | yes |
| `rx_rate` | RX Rate | metric | bytes/s | yes |

> `tx_rate` / `rx_rate` on the radio are read from the access point's
> `stat.ap.<radio name>-tx_bytes-r` / `-rx_bytes-r`. That field is not yet
> confirmed on a live controller (build 15); when the access point does not
> report it, the radio publishes no rate. The switch port and WAN rate
> fields are recorded on a live controller.

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `radio_type` | Radio Type | property | - | - |
| `radio_name` | Radio Name | property | - | - |
| `ht` | HT Mode | property | - | - |
| `min_txpower` | Min TX Power | property | - | - |
| `max_txpower` | Max TX Power | property | - | - |

---

### UniFi Wireless Summary

**Identifier**: `aggregate_id` (Aggregate ID)

#### Clients

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `num_user` | Users | metric | - | yes |
| `num_guest` | Guests | metric | - | yes |
| `num_iot` | IoT Devices | metric | - | yes |
| `num_disconnected` | Disconnected | metric | - | yes |

#### Performance

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `num_ap` | AP Count | metric | - | yes |
| `tx_bytes_r` | TX Rate | metric | bytes/s | yes |
| `rx_bytes_r` | RX Rate | metric | bytes/s | yes |

---

### UniFi NVR

**Identifier**: `nvr_mac` (MAC Address)

#### System

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `uptime` | Uptime | metric | sec | yes |

#### Storage

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `used_bytes` | Used | metric | bytes | yes |
| `total_bytes` | Total | metric | bytes | yes |
| `usage_pct` | Usage % | metric | % | yes |
| `source` | Source | property | - | - |
| `volume_health` | Volume Health | property | - | - |
| `volume_display_health` | Volume Display Health | property | - | - |
| `volume_issues` | Volume Issues | property | - | - |
| `volume_action` | Volume Action | property | - | - |
| `volume_device` | Volume Device | property | - | - |
| `drive_count` | Drives | metric | - | yes |
| `drives_not_healthy` | Drives Not Healthy | metric | - | yes |
| `drives_failed` | Drives Failed | metric | - | yes |
| `unhealthy_drive_slot` | First Unhealthy Drive Slot | property | - | - |
| `unhealthy_drive_state` | First Unhealthy Drive State | property | - | - |
| `drive_bad_sectors` | Drive Bad Sectors | metric | - | yes |
| `hard_drive_state` | Hard Drive State | property | - | - |

> Build 18 (issue #39): bytes, from the Protect bootstrap's
> `nvr.systemInfo.storage` (`size`, `used`: the recording volume; on the
> devel UDM Pro the console reports the same total as the primary space's
> `total_bytes`), or, only when that pair is missing,
> `nvr.storageStats.recordingSpace` (`total`, `used`: Protect's share of
> the volume). A total and a used are never taken from different sources.
> All three are published together and only when a usable pair is sent
> (total above 0, used not negative and not above the total); `usage_pct`
> is used / total * 100, so never above 100. `source` (build 18 review)
> names the pair that fed them, `systemInfo` (the volume) or
> `recordingSpace` (Protect's share), so a step in `total_bytes` from a
> change of source can be told apart. A pair reporting more used than total
> is not published and is logged at WARN once per streak of cycles.
> Builds up to 17 read `nvr.storageInfo`, which current Protect does not
> send, so the three never published. Protect fills the volume and then
> recycles the oldest footage, so a usage near 100 % is normal for a
> recording NVR (devel: 99.1 %).

> Build 19: **storage health**, from the bootstrap's `nvr.systemInfo.ustorage`
> (the same block UniFi OS serves at `/api/system` as `ustorage`), values
> verbatim and each only when Protect sends it. The volume is the
> `space[]` entry with `space_type` `primary`, else the only one that is
> neither `root` nor `swap` (the UniFi OS storage page's choice); nothing is
> published when there is no such one. `volume_health` is its `health`:
> `health` (Protect's word for healthy; the UI also accepts `healthy`),
> `atrisk` (UI: "Storage At Risk of Failure", orange) or `failed` ("Storage
> has Failed", red). `volume_issues` is its `reasons[]` as `type:level`,
> comma separated in Protect's order; the UI's types are `disk_atrisk`,
> `disk_failed`, `space_degrade`, `space_failed`, `space_ro`,
> `lacking_disk`, `io_err`, `raid_bad_blocks`; `none` when the list is
> empty. A reason's `slot` is not published: on devel it reads 0 while the
> only drive is in slot 1.
>
> `volume_display_health` (build 19 review) is the health the storage page
> displays, by the console UI's own rule (main bundle, read 2026-10-28):
> `health` and `healthy` display as `healthy`, `failed` as `failed`, and
> `atrisk` as `atrisk`, except that a `primary` volume whose only reason is
> `lacking_disk:atrisk` displays `healthy` when it has no RAID block or its
> RAID never had all its members (`raid.had_most` below `raid.expected`).
> Protect's bootstrap trims the RAID block, so the pack reads it from UniFi
> OS `/api/system` (`ustorage.space[]`, GET only, same session, once per
> cycle while Protect sends an NVR), matched by `device`. When that block
> cannot be read (the call fails or is refused, or the device is not
> listed), the exception is not applied and the volume displays `atrisk`.
> An `atrisk` volume with no reasons list displays `atrisk`. A value
> outside `healthy`, `atrisk` and `failed` is published verbatim (the UI
> cannot render it), and nothing is published when the volume carries no
> `health`. Devel reads `atrisk` (`io_err:atrisk`; RAID expected 2, had
> most 1). The bundled alert reads this property, not `volume_health`.
> A failed or refused `/api/system` costs only the exception: everything
> else is collected, a refusal (401, 403 or 429) is not asked again for 15
> minutes (one WARN), and any other failure is logged at INFO once per
> streak of cycles.
> `volume_action` is `none`, `syncing`, `repairing`, `expanding`,
> `formatting`, `erasing`, `checking` or `scanning`.
>
> The drive values come from `disks[]` and are published together, and
> only when every entry has a `state` and a `slot` (a count over a list
> the pack cannot read in full would vouch for a drive it never saw). An
> empty bay (`state` `nodisk`) is not a drive. `drives_not_healthy` counts
> the drives the UniFi OS storage page shows orange or red: state `risk`,
> `spare_at_risk`, `foreign`, `repairing`, `incompatible`, `broken`,
> `faulty` or `not_support`, or a `health` of `at_risk` or `broken`, or a
> `reason` naming a risk (`abnormal_smart`, `too_many_read_error`,
> `too_many_bad_sectors`, `too_short_life_span`, `too_many_unc_count`,
> `too_many_get_smart_error`, `investigation_needed`); a state this pack
> does not know also counts, so it is surfaced, never passed. A `spare`
> with any `reason` counts too (the UI shows it as `spare_at_risk`: "cannot
> serve as a hot spare"). Green: `normal`, `initializing`, `expanding`;
> black: `spare`; neutral: `probing`. During a two-member mirror resync
> (the volume displays `healthy`, its `action` is `repairing`, RAID expected
> 2 with had most at least 2, one drive `normal` and one `repairing`) the
> UI shows the `repairing` drive as `initializing`, and so does the pack:
> it is not counted. Without the RAID block a `repairing` drive counts.
> `drives_failed` counts the red ones only (`broken`, `faulty`,
> `not_support`, or `health` `broken`). `unhealthy_drive_slot` and
> `unhealthy_drive_state` name the lowest-slot unhealthy drive, verbatim,
> and read `none` when none is. `drive_bad_sectors` is the sum of
> `bad_sector` over the drives, only when every drive carries it. The
> drive's own `healthy` field (`good` on devel) is not used: its other
> values are not known. `hard_drive_state` is Protect's NVR-level
> `hardDriveState`, verbatim (`hddNotCompatible` on devel, whose only
> drive reads state `normal`); its meaning and vocabulary are not
> known, so no alert reads it.
>
> A part of `ustorage` the pack cannot use (no `ustorage` object, no
> recording volume, a disk list it cannot read in full, or an `atrisk`
> volume whose RAID block was not read) is logged at INFO once per streak of
> cycles, per NVR. Only JSON scalars are read as text: an object or list
> where Protect's word is expected publishes nothing.

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `model` | Model | property | - | - |
| `firmware` | Firmware | property | - | - |
| `name` | Name | property | - | - |
| `host_type` | Host Type | property | - | - |
| `recording_retention_days` | Recording Retention (days) | property (numeric) | - | - |
| `camera_count` | Camera Count | property | - | - |

---

### UniFi Camera

**Identifier**: `camera_mac` (MAC Address)

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `uptime` | Uptime | metric | sec | yes |
| `seconds_since_last_motion` | Seconds Since Last Motion | metric | sec | no |
| `state` | State | property | - | - |
| `is_connected` | Connected | property | - | - |
| `is_recording` | Recording | property | - | - |

> Build 17: `is_connected` and `is_recording` (`true` / `false`) are
> published only when Protect sends `isConnected` and `isRecording`; a
> missing field publishes nothing (before build 17 it read `false`).

#### Hardware

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `model` | Model | property | - | - |
| `firmware` | Firmware | property | - | - |
| `type` | Type | property | - | - |
| `is_wireless` | Wireless | property | - | - |
| `phy_rate` | PHY Rate | property | - | - |

#### Network

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `ip` | IP Address | property | - | - |
| `mac_address` | MAC Address | property | - | - |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `recording_mode` | Recording Mode | property | - | - |

> Build 17: the camera's Protect recording mode
> (`recordingSettings.mode`), as Protect sends it: `always`, `detections`,
> `schedule` or `never` on current Protect releases (older releases sent
> `motion` and `smartDetect`). Not checked against a list, so a new mode
> still publishes. Published only when present and not blank.

---

## Traversal Spec

**Name**: UniFi Controller Infrastructure

```
UniFi World
    └── UniFi Site
        ├── UniFi Gateway
        │   ├── UniFi WAN Interface
        │   ├── UniFi Switch (nested) ...
        │   └── UniFi Access Point (nested) ...
        ├── UniFi Switch (nested: a switch under a switch, any depth)
        │   ├── UniFi Switch Port
        │   └── UniFi Access Point (nested: an AP under an AP, any depth)
        │       └── UniFi Radio
        ├── UniFi Access Point (nested)
        │   └── UniFi Radio
        ├── UniFi NVR
        │   └── UniFi Camera
        └── UniFi Wireless Summary
```

The spec is rooted at the World kind (`rootResourceKind="UniFiWorld"`, each
path starting `unifi_controller::UniFiWorld||unifi_controller::UniFiSite::child||...`),
as VMware's own vSphere adapter roots "vSphere Hosts and Clusters" at
`VMWARE::vSphere World`. Up to build 17 it was rooted at the adapter instance
kind; the instance resource has no children (its only parent is Universe), so
the tree showed the instance and nothing under it (devel and devel_9_1,
2026-10-28). The adapter instance is not linked to the World.

Build 18 (issue #15): a switch or access point is a child of the device
it uplinks to (`uplink.uplink_mac`), so the tree follows the cabling and a
device going down shows on everything behind it, as vSphere's Cluster >
Host > VM does. Nesting follows Gateway > Switch (any depth) > Access Point
(any depth); describe.xml declares the repeated levels with
`::child/recursive`. The captured vendor form is VMware's vSphere Hosts and
Clusters `VMWARE::HostFolder::child/recursive/preferred`; this pak leaves
`/preferred` off on purpose. The SDK's describe validator
(`TraversalSpecUtil.validateResKindPath`, identical bytecode in the 8.18.7
and 9.1.1 SDKs) treats `/recursive` and `/preferred` as independent
attributes and accepts all nine paths as written; it only limits where
`/preferred` may go (not on the first or last level, not with `/skip`, not
before a wildcard level). What `/preferred` does at runtime is not
documented anywhere we have, and each device here has exactly one parent, so
there is no alternative path for it to prefer. That Ops renders the bare
`/recursive` paths as intended is a devel install check. A device is placed directly under its
Site, and logged once at INFO, when its uplink is not a discovered device of
the site, when the uplinks loop (every device on the loop), or when the pair
cannot nest in the tree (a switch behind an access point, through a wireless
or pass-through uplink). A gateway is always under its Site. The devel
console (17 devices) nests up to five deep below the gateway: Switch >
Switch > Switch > Switch > Access Point, and an access point behind an
in-wall access point.

### Object names

Build 18 (issue #27, item 4): an object that exists once per parent is
named `<label> (<parent>)`, the form Broadcom's own adapters use (for
example vSAN "Storage Pool (<host>)"): switch ports `Port 3 (<switch>)` (or
`<custom port name> (<switch>)`), WAN interfaces `WAN 1 (<gateway>)`,
radios `5 GHz (<access point>)`, and `Wireless Summary (<site>)`. The
parent's name is its display name (a device's name, or model and MAC tail
when it has none; a site's description). Only the name changes, never the
identifier, so an object keeps its history. The pack opts into renames
(`isResourceRenameAllowed()` returns `true`, as Broadcom's vSphere and vSAN
adapters do; the SDK default is `false`), so objects discovered before
build 18 take the new names, and a device or site renamed on the controller
is renamed in VCF Operations on the next cycle (see CHANGELOG, build 18;
not yet seen on an upgraded instance).

### Removed objects

Build 18 (issue #20, framework buildkit 1.0.22): an object the controller no
longer lists is marked **Not Existing** after it has been missing from 12
consecutive complete collection cycles (an hour at the default 5-minute
interval), the way the vSphere, vSAN and NSX adapters mark a deleted
object. VCF Operations then stops collecting it, shows it as Not Existing
(health grey) and deletes it, with its history, after the global **Deleted
Objects** retention setting (168 hours by default). The pack declares a kind
read in full only when nothing about its read failed that cycle, and only a
kind read in full counts a miss:

| Kinds | Counted only when |
|---|---|
| Site, Wireless Summary | `/self/sites` was read with `meta.rc` `ok`, listed at least one site, and every site row carried a `name` |
| Gateway, Switch, Access Point | every site's device list was read (no failed or cooling-down site, `meta.rc` `ok`), at least one device record came back across all sites, no gateway, switch or access point came without a MAC, and no device record came without a `type` |
| WAN Interface, Switch Port, Radio | as their device, and every gateway (WAN), switch (port) or access point (radio) is informing the controller; every gateway's record carries `wan1`, and every switch and access point sent a non-empty port or radio table with an index or radio code on every row |
| NVR, Camera | the Protect bootstrap was read and carried an NVR; the NVR also needs its MAC, cameras need a non-empty camera list with every camera's MAC |

These empty answers are not taken as proof that the objects are gone,
and count nothing:

- an empty site list, or one whose `meta.rc` is not `ok`: no kind at all
  (a controller always has its default site);
- no device record on any site, each answering `meta.rc` `ok` with
  `data: []`: no gateway, switch, access point, WAN, port or radio (a
  UniFi OS console lists itself; a Network app mid-start or a proxy stub
  answers this way);
- `cameras: []` with an NVR present: no camera;
- an empty or missing port table on a listed switch, or radio table on a
  listed access point: no port or radio (every switch has ports and every
  access point a radio);
- a listed gateway whose record has no `wan1`: no WAN (every gateway has
  a WAN 1; a missing `wan2` is a real configuration and is not guarded).
- a site row with no `name`: the row is skipped (no Site with an empty
  key), and no site, Wireless Summary, gateway, switch, access point, WAN,
  port or radio is counted that cycle. Its `stat/device` is not asked for,
  it is never the NVR's parent site (when it is the first row, the NVR
  keeps the site edge it already has), and the "no longer in the
  controller's site list" WARN is not raised that cycle.

The cost: **the last site, the last device on the controller, the last
camera under an NVR, and the last port, radio or WAN 1 of a listed device
are never removed automatically**; delete them by hand.

Nothing else is guarded, and the last object *of a type* is removed when
other devices are still listed. Remove the only switch while a gateway or
access point is still listed, and the switch kind is read: that switch and
its ports are marked and removed, which is correct. Removing a gateway
removes its WANs, a `wan2` the gateway stops reporting is removed, and a
site whose devices are all gone loses them while another site still lists
devices (a site can be empty).

Removal that is off for a kind is never silent: each cycle that leaves a
kind out logs the kinds and the reason once at INFO, until a cycle without
that reason ("Removal of vanished objects is off for ..."). A site that
drops out of the site list is named at WARN on the first cycle it is
missing.

So one unreadable site, one disconnected switch or one Protect outage
counts nothing for the kinds it touches, however long it lasts; the count
resumes when the read is complete again. A cycle in which the whole snapshot
failed counts nothing at all. UniFi World is never marked. The counts are
held in memory, so a collector restart delays a mark and never makes one
early. A device left pending adoption on the controller is not discovered
(build 15), so an object an earlier build registered for it is marked like
any other device the controller no longer lists. A marked object that comes
back is registered again by discovery; that path is not yet seen live
(framework note, `knowledge/context/sdk/vanished_object_lifecycle.md` in the
factory).

Removal follows what the controller lists, not why it stopped listing it.
Narrowing the account's site access removes those sites and their devices
(the WARN above names each site when it goes missing),
and a radio the controller no longer reports (for example a band disabled
in configuration, if UniFi drops it from `radio_table_stats`; not verified)
is removed too. If it comes back, it returns as a new object with no
history.

### Relationships when a read fails

Build 18 (issue #20): a relationship is never removed because one read
failed. VCF Operations replaces a parent's children only when the pack
sends that parent; a parent not sent keeps what it had. A site whose device
list could not be read is not sent (build 14). The NVR's site (the first
site) is not sent on a cycle where Protect was not read, so the Site > NVR
edge stays; this holds for at most 12 consecutive cycles without Protect,
and only while VCF Operations holds a UniFi NVR for the instance. The NVR
is sent with its cameras only on a cycle that read Protect, so NVR > Camera
edges stay through an outage. The vmnic stitch's HostSystem > Switch Port
edges are only ever added, never replaced, so a cycle without the Suite API
removes none of them. The cost of this rule: a relationship that really
changed during such a cycle shows on the next good one. An NVR left behind
after Protect is uninstalled is never marked Not Existing (the NVR kind is
counted only on a cycle that read Protect), so VCF Operations keeps holding
a UniFi NVR for the instance, and the 12-cycle count is in memory: after
every collector restart the first site's edges are frozen again for up to
12 cycles, with the same INFO and WARN. Delete the leftover NVR object by
hand to stop it.

---

## Alerts

Build 16 (issue #30) ships the alert definitions, their symptoms and
recommendations as bundled content (`symptoms/`, `alerts/`,
`recommendations/`, listed under `bundled_content` in `adapter.yaml`): eight
alerts, twelve symptoms and six recommendations in build 16, fourteen
symptoms from build 17 (two scoping symptoms), and from build 19 nine
alerts, nineteen symptoms and seven recommendations (NVR Storage At Risk,
its five symptoms and one recommendation). They install with the pak. Every
name starts with `[VCF Content Factory] ` (omitted in the tables below).

Timing: each symptom waits 3 collection cycles before it triggers and 3
before it clears (about 15 minutes at the default 5-minute interval); the
alert itself adds no further wait. All symptom sets are evaluated on the
object itself.

| Alert | Object | Severity | Badge | Fires when |
|---|---|---|---|---|
| UniFi Gateway Offline | UniFi Gateway | Critical | Health | `Status\|state_code` is `disconnected` or `heartbeat missed` |
| UniFi Switch Offline | UniFi Switch | Critical | Health | `Status\|state_code` is `disconnected` or `heartbeat missed` |
| UniFi Access Point Offline | UniFi Access Point | Critical | Health | `Status\|state_code` is `disconnected` or `heartbeat missed` |
| UniFi Uplink Port Down | UniFi Switch Port | Critical | Health | `Status\|up` is `false` **and** `Status\|is_uplink` is `true` (an access port going down does not fire; a switch that loses its only uplink shows as Switch Offline instead, see Limits) |
| UniFi Camera Offline | UniFi Camera | Critical | Health | `Status\|is_connected` is `false` |
| UniFi Camera Not Recording | UniFi Camera | Warning | Health | `Status\|is_recording` is `false` **and** `Status\|is_connected` is `true` **and** `Configuration\|recording_mode` is not `never` (a disconnected camera fires Camera Offline instead; a camera set never to record does not fire) |
| UniFi PoE Budget Nearly Exhausted | UniFi Switch | Warning | Risk | `PoE\|poe_budget_used_pct` >= 90 **and** `Status\|state` = 1 (connected, so a disconnected switch's last PoE value does not hold the alert) |
| UniFi WAN Degraded | UniFi WAN Interface | Warning | Health | `Status\|up` is `true` **and** (`Health\|availability` < 99 **or** `Health\|latency` > 100 ms); an unused WAN (link down) does not fire |
| UniFi NVR Storage At Risk | UniFi NVR | Symptom-based (Critical when failed, else Warning) | Health | `Storage\|volume_display_health` is `failed` **or** `atrisk` **or** not one of `healthy`, `atrisk`, `failed`, **or** `Storage\|drives_failed` > 0 **or** `Storage\|drives_not_healthy` > 0. The volume value is what Protect's storage page displays, so a volume at risk only for a missing drive in an array that never had all its members does not fire, as in Protect; when the RAID block cannot be read it does fire. Fullness (`usage_pct`) is not read |

The device offline alerts match only `disconnected` and `heartbeat missed`:
upgrading, provisioning, adopting, pending adoption and inform error do not
fire them, so a firmware upgrade does not alert (state codes:
`docs/overview.md`, Device state codes). PoE Budget Nearly Exhausted never
fires on a non-PoE switch, which publishes no `PoE|poe_budget_used_pct`.

### Limits

What these alerts do not catch, or hold after the fact. Every property
these alerts read is published only when the controller sends its field
(build 17), so a missing field raises nothing rather than a false `false`.

- **WAN down is not alerted.** WAN Degraded fires only on a WAN whose link
  is up, so an unused second WAN (link down, no address, availability 0)
  does not fire. The same scope means a configured WAN that loses its link
  does not fire either: the controller's `wan1` / `wan2` object carries no
  field known to tell a configured WAN from an unused one (`up` is the
  link, and an address is not a reliable sign of configuration), so the pak
  ships no WAN Down alert. An ISP outage with the link still up lowers
  availability and does fire WAN Degraded.
- **A switch that loses its only uplink shows as Switch Offline, not
  Uplink Port Down.** The port with `is_uplink` is the switch's own port
  toward the controller. When that link fails, the switch stops informing
  the controller, its own port keeps the controller's last `up` (true), and
  the upstream switch's port facing it is not an uplink. Uplink Port Down
  fires only where the switch keeps another path to the controller (a LAG,
  a redundant uplink, STP failover).
- **Inform error and unknown state codes raise nothing.** A device in
  `inform error` (9), or in a state code this pak does not know (published
  as its number), publishes no metrics and does not match the Offline
  symptoms (`disconnected` or `heartbeat missed` only), so it raises no
  Offline alert. Its `Status|state_code` shows the code; a view or a custom
  symptom on it can catch these.
- **Camera Not Recording needs the recording mode.** It fires only on a
  camera that publishes `Configuration|recording_mode` and whose mode is
  not `never`. A camera whose Protect sends no `recordingSettings.mode`
  does not fire. A camera on a `schedule` mode is not excluded and can fire
  outside its scheduled hours; disable the alert for that camera if so.
- **NVR storage: Protect's verdict only.** NVR Storage At Risk fires on
  the health Protect's storage page displays and on its drive states and
  risk reasons, never on the pak's own thresholds: a drive with a few
  remapped sectors that Protect still calls healthy does not fire
  (`Storage|drive_bad_sectors` is published for context), nor does an
  `io_err` reason on a volume Protect still displays healthy
  (`Storage|volume_issues` shows it). Storage fullness is never alerted:
  Protect fills the volume to its retention limit by design. An NVR whose
  Protect sends no `health` for the volume publishes no
  `volume_display_health`, so the volume part cannot fire (logged once per
  streak).
- **Stale holds.** VCF Operations keeps a property's or metric's last value
  when the pak stops publishing it, so:
  - While a gateway is not informing the controller its WAN interfaces
    publish no metrics, and WAN Degraded holds its last state until the
    gateway returns.
  - A device removed (forgotten) in the controller while offline is no
    longer in `stat/device`, so its `Status|state_code` keeps its last
    value and its Offline alert stays until the object is removed from VCF
    Operations.
  - On an integrated console (UDM, UDM Pro, UDR, Cloud Gateway), the
    gateway is the controller: when it is down, collection itself fails
    (the adapter instance goes DOWN or ERROR), so Gateway Offline cannot
    fire for it. Watch the adapter instance's health instead.

### Symptoms

| Symptom | Object | Severity | Condition |
|---|---|---|---|
| UniFi Gateway Offline | UniFi Gateway | Critical | property `Status\|state_code` matches `^(disconnected\|heartbeat missed)$` |
| UniFi Switch Offline | UniFi Switch | Critical | property `Status\|state_code` matches `^(disconnected\|heartbeat missed)$` |
| UniFi Access Point Offline | UniFi Access Point | Critical | property `Status\|state_code` matches `^(disconnected\|heartbeat missed)$` |
| UniFi Switch Port Link Down | UniFi Switch Port | Critical | property `Status\|up` = `false` |
| UniFi Switch Port Is Uplink | UniFi Switch Port | Info | property `Status\|is_uplink` = `true` (scoping only) |
| UniFi Camera Disconnected | UniFi Camera | Critical | property `Status\|is_connected` = `false` |
| UniFi Camera Connected | UniFi Camera | Info | property `Status\|is_connected` = `true` (scoping only) |
| UniFi Camera Not Recording | UniFi Camera | Warning | property `Status\|is_recording` = `false` |
| UniFi Camera Recording Enabled | UniFi Camera | Info | property `Configuration\|recording_mode` != `never` (scoping only) |
| UniFi Switch PoE Budget High | UniFi Switch | Warning | metric `PoE\|poe_budget_used_pct` >= 90 |
| UniFi Switch Connected | UniFi Switch | Info | metric `Status\|state` = 1 (scoping only) |
| UniFi WAN Availability Low | UniFi WAN Interface | Warning | metric `Health\|availability` < 99 |
| UniFi WAN Latency High | UniFi WAN Interface | Warning | metric `Health\|latency` > 100 |
| UniFi WAN Link Up | UniFi WAN Interface | Info | property `Status\|up` = `true` (scoping only) |
| UniFi NVR Volume Failed | UniFi NVR | Critical | property `Storage\|volume_display_health` = `failed` |
| UniFi NVR Volume At Risk | UniFi NVR | Warning | property `Storage\|volume_display_health` = `atrisk` |
| UniFi NVR Volume Health Unrecognised | UniFi NVR | Warning | property `Storage\|volume_display_health` does not match `^(healthy\|atrisk\|failed)$` |
| UniFi NVR Drive Failed | UniFi NVR | Critical | metric `Storage\|drives_failed` > 0 |
| UniFi NVR Drive Not Healthy | UniFi NVR | Warning | metric `Storage\|drives_not_healthy` > 0 |

### Recommendations

| Recommendation | Used by |
|---|---|
| UniFi Investigate Offline Network Device | Gateway Offline, Switch Offline, Access Point Offline |
| UniFi Investigate Uplink Port Down | Uplink Port Down |
| UniFi Investigate Disconnected Camera | Camera Offline |
| UniFi Investigate Camera Not Recording | Camera Not Recording |
| UniFi Reduce Switch PoE Load | PoE Budget Nearly Exhausted |
| UniFi Investigate WAN Degradation | WAN Degraded |
| UniFi Investigate NVR Storage At Risk | NVR Storage At Risk |

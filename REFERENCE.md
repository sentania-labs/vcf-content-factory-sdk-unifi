# VCF Content Factory UniFi Controller: Reference

Generated from `describe.xml` and `resources.properties` for build 17 (dev 0.0.0.17).

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
unifi_controller
    └── UniFi World
        └── UniFi Site
            ├── UniFi Gateway
            │   └── UniFi WAN Interface
            ├── UniFi Switch
            │   └── UniFi Switch Port
            ├── UniFi Access Point
            │   └── UniFi Radio
            ├── UniFi NVR
            │   └── UniFi Camera
            └── UniFi Wireless Summary
```

---

## Alerts

Build 16 (issue #30) ships eight alert definitions, their symptoms
(twelve in build 16, fourteen from build 17, which adds two scoping
symptoms) and six recommendations as bundled content (`symptoms/`,
`alerts/`, `recommendations/`, listed under `bundled_content` in
`adapter.yaml`). They install with the pak. Every name starts with `[VCF Content Factory] `
(omitted in the tables below).

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

### Recommendations

| Recommendation | Used by |
|---|---|
| UniFi Investigate Offline Network Device | Gateway Offline, Switch Offline, Access Point Offline |
| UniFi Investigate Uplink Port Down | Uplink Port Down |
| UniFi Investigate Disconnected Camera | Camera Offline |
| UniFi Investigate Camera Not Recording | Camera Not Recording |
| UniFi Reduce Switch PoE Load | PoE Budget Nearly Exhausted |
| UniFi Investigate WAN Degradation | WAN Degraded |

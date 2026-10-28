### Upgrading to build 18

The first cycle after upgrading to build 18 renames every existing switch
port, WAN interface, radio and Wireless Summary to `<label> (<parent>)`
(for example `Port 3 (<switch>)`). History is kept, but saved searches,
custom groups, views filtered by name and notification rules keyed on the
old names need updating. A device renamed on the controller since it was
first discovered takes its current name too (devel: "udm" became
"Bentwood"), so check name-keyed device filters as well. See [README.md, Upgrading to build 18](../README.md#upgrading-to-build-18).

### Alerts

The pak ships eight alerts with their symptoms and recommendations (build
16): gateway, switch, access point and camera offline, uplink port down,
camera not recording, PoE budget nearly exhausted and WAN degraded. What
each fires on, its object and severity: [REFERENCE.md, Alerts](../REFERENCE.md#alerts).

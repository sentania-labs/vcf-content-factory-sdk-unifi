### Upgrading to build 18

The first cycle after upgrading to build 18 renames every existing switch
port, WAN interface, radio and Wireless Summary to `<label> (<parent>)`
(for example `Port 3 (<switch>)`). History is kept, but saved searches,
custom groups, views filtered by name and notification rules keyed on the
old names need updating. A device renamed on the controller since it was
first discovered takes its current name too (devel: "udm" became
"Bentwood"), so check name-keyed device filters as well. See [README.md, Upgrading to build 18](../README.md#upgrading-to-build-18).

Build 18 also marks objects the controller no longer lists as Not
Existing after 12 consecutive complete cycles, and VCF Operations deletes
them after its Deleted Objects retention (issue #20). The first upgrade
counts every leftover from older builds at once: `<mac>_0` switch ports, a
camera removed from Protect, a gateway no longer listed, all marked about
an hour after the upgrade and then deleted with their history. This cannot
be undone. Removal follows what the controller lists, so narrowing the
account's site access removes those sites and their devices too. An empty
list is never taken as proof that everything is gone, so the last object
of a kind is not removed automatically. See [REFERENCE.md, Removed objects](../REFERENCE.md#removed-objects).

### Alerts

The pak ships eight alerts with their symptoms and recommendations (build
16): gateway, switch, access point and camera offline, uplink port down,
camera not recording, PoE budget nearly exhausted and WAN degraded. What
each fires on, its object and severity: [REFERENCE.md, Alerts](../REFERENCE.md#alerts).

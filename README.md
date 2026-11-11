# VCF Content Factory UniFi Controller

Monitors UniFi infrastructure on a **UniFi OS console** (for example UDM, UDM Pro, UDR, Cloud Gateway or Cloud Key Gen2 Plus) through its Network application and Protect APIs. Classic and self-hosted UniFi Network controllers are not supported. Discovers sites, gateways, switches, access points, NVR, and cameras. Per-switch-port and per-AP-radio child objects with PoE tracking. Switch ports are linked to the ESXi hosts cabled to them from the vCenter side: each host's vmnic LLDP neighbour (published by vCenter) is matched to the UniFi switch and port, read over the VCF Operations Suite API on the collector (optional; skipped where the collector has no local Suite API access). Replaces the Tier 1 UniFi Network and UniFi Network Integration management packs.

## Documentation

Full docset (overview, installing & configuring, inventory tree): [`docs/README.md`](docs/README.md).

## Installing: allow unsigned paks first

This pak is unsigned, like every pak the VCF Content Factory builds (Broadcom
offers no way for a third party to sign one). VCF Operations 9.0 and 9.1 refuse
unsigned paks out of the box; behavior on 8.x is unverified. The setting below
must be on for each install or upgrade of an unsigned pak:

1. Log in to `https://<primary node>/admin` as `admin`.
2. Administrator Settings, Security Settings: click **Activate Unsigned PAK
   Installation** on 9.1 (**Enable Unsigned PAK Installation** on 9.0). The
   change is refused while any cluster node is down.
3. When uploading the pak (**Repository** tab of Integrations, see
   Installation below), tick **Ignore the PAK file signature checking**. The
   box only appears once the setting is on.

With the setting off, the install fails with
`unsigned_pak_installation.not.allowed`. This is a cluster-wide security
setting: it lets any unsigned pak be installed, not only this one, so decide
it deliberately and consider turning it off again after installing. If you do,
it has to go back on before you install the next build. Upgraded appliances
keep whatever state the setting had before the upgrade.

## Supported configuration

- **UniFi OS consoles only**: the console's own login (`/api/auth/login`)
  and its Network application (`/proxy/network`), plus Protect when it is
  installed. Classic and self-hosted UniFi Network controllers (the Network
  Application on its own server, usually on port 8443) are not supported;
  Test Connection fails with "This adapter supports UniFi OS consoles only"
  when the host answers 404 on either path.

## Installation

Install the pak from the **Repository** tab and create the adapter instance
under the **Accounts** tab, both under Integrations (paths checked
2026-10-28; no supported release has a Data Sources menu item):

| VCF Operations | Integrations is at | Then |
|---|---|---|
| 9.1.1 | **Operate** tab > **Administration** > **Integrations** | **Accounts** tab (opens by default) or **Repository** tab |
| 9.0.2, 8.18.7 | **Administration** > **Integrations** | **Accounts** or **Repository** |

Upload the `.pak` file under **Repository > Add**.

## Upgrading to build 18

On its first collection cycle after the upgrade, build 18 (1.1.0.18) renames
every existing switch port, WAN interface, radio and Wireless Summary to
`<label> (<parent>)`, for example `Port 3 (<switch>)`, `WAN 1 (<gateway>)`,
`5 GHz (<access point>)` and `Wireless Summary (<site>)`. The objects keep
their identity and their metric history; only the name changes. Anything
keyed on the old names stops matching and needs updating: saved searches,
custom groups and views filtered by name, and notification rules. Later
renames on the controller carry over the same way. Devices are renamed too
when their name on the controller changed after VCF Operations first
discovered them: on devel the gateway first seen as "udm" became "Bentwood"
on the first build 18 cycle. Filters, groups and rules keyed on device names
need the same check. Details: REFERENCE.md, Object names.

**Build 18 also deletes objects the controller no longer lists, and the
first upgrade deletes every leftover at once (issue #20).** An object
missing from 12 consecutive complete collection cycles (an hour at the
default 5-minute interval) is marked Not Existing, and VCF Operations
deletes it, with its metric history, alerts and group membership, after the
Deleted Objects retention (Global Settings; 168 hours by default, 6 hours on
some labs). Counting starts on the first build 18 cycle and covers every
object the pack registered in the past under a key it no longer produces,
so about an hour after the upgrade these are marked, and deleted at the end
of the retention window. This cannot be undone. On devel that includes:

- switch ports registered as `<mac>_0` by builds before 15 (issue #23);
- a camera removed from Protect (the second "Backyard" camera, which still
  reads Disconnected) and anything else Protect no longer lists;
- a gateway the controller no longer lists, with its WAN interfaces;
- a device an earlier build registered while it was pending adoption, and
  any radio registered as `<mac>_` before build 15.

Removal follows what the controller lists, so a configuration or
permission change counts too: narrowing the account's site access removes
those sites and their devices (the pack logs a WARN naming a site the
first cycle it is missing), and a radio the controller stops reporting is
removed. An object that comes back returns as a new object with no history.

Only these empty answers count nothing, so the objects they cover are
never removed automatically and the last one goes by hand: an empty site
list (no kind at all); no device on any site (no gateway, switch, access
point, WAN, port or radio); an empty camera list while the NVR is listed;
an empty or missing port table on a listed switch, radio table on a listed
access point, or `wan1` on a listed gateway. A site row with no `name`
is skipped and counts nothing for sites or devices that cycle; it is not
read, not the NVR's parent, and raises no "no longer listed" WARN. Everything else is removed
when it stops being listed, the last of its type included: remove the only
switch while a gateway or access point is still listed and that switch and
its ports are removed; remove a gateway and its WANs go with it; a site
whose devices are all gone loses them while another site still lists
devices. While a kind's removal is off, the pack logs why once at INFO.

Before upgrading, list the pack's objects (Inventory, filter by adapter
type UniFi Controller) and export anything whose history you want to keep.
Details: REFERENCE.md, Removed objects.

## Configuration

Create an adapter instance under **Accounts > Add Account** (Integrations, as above) and provide:

| Field | Description | Default |
|---|---|---|
| Host / IP Address | | — |
| Port (HTTPS) | | 443 |
| Allow Insecure SSL | `true` turns off certificate validation for the controller (lab use only). Any other value validates. A self-signed console does not need it: Validate Connection offers its certificate for review, you accept it once and validation stays on. Enter the console by a name its certificate carries; when it is entered by an IP address the certificate does not list, the review dialog is unproven and Allow Insecure SSL may be the only path (tracked in the fork issue #33 follow-up). Existing instances keep their stored value on upgrade; see [docs/installing.md](docs/installing.md). | false |

### Credentials

| Field | Description |
|---|---|
| Username | |
| Password | |

## Object Types

- UniFi World
- UniFi Site
- UniFi Gateway
- UniFi WAN Interface
- UniFi Switch
- UniFi Switch Port
- UniFi Access Point
- UniFi Radio
- UniFi Wireless Summary
- UniFi NVR
- UniFi Camera

## Building from source

You don't need this repo's CI or the VCF Content Factory checkout to
build the `.pak` — the toolchain is a portable tarball. You need:

- **JDK 17+** (`javac` + `jar` on PATH; the 9.0.2 SDK jar's class files are Java 17)
- **python3** with `pyyaml` (`python3 -m pip install pyyaml`)
- **The GitHub CLI** (`gh`) — used to download the build toolchain
  below. The factory repo is public, so no `gh auth login` is needed
  for the download (authenticate only if you hit anonymous API rate
  limits). No `gh`? See the `curl` alternative under step 1.
- **The Broadcom adapter SDK jar for VCF Operations 9.0.2**, and
  **JDK 17 or newer** to read it. This is a Broadcom build artifact
  with no public redistribution channel — it is **never** bundled in
  the toolchain or this repo. The jar is different on every platform
  version; the build compiles against the 9.0.2 one. Get it from a VCF
  Operations 9.0.2 appliance, where it is
  `/usr/lib/vmware-vcops/common/lib/vrops-adapters-sdk.jar`. Keep it
  **outside this repo's working tree**, so it can never be committed
  by accident:

  ```
  mkdir -p ~/vcf-ops-sdk && cd ~/vcf-ops-sdk
  scp root@<appliance>:/usr/lib/vmware-vcops/common/lib/vrops-adapters-sdk.jar .
  sha256sum vrops-adapters-sdk.jar
  ```

  Look the hash up in the table under "Consumer contract" in the
  buildkit release notes. It lists the builds the kit recognises and the
  file name it uses for each, `vrops-adapters-sdk-<version>.<build>.jar`
  (for example `vrops-adapters-sdk-9.0.2.0.25137845.jar`); rename your
  copy to match. The kit identifies the jar by its hash, so a jar from a
  patched appliance is a different build with a different name. If the
  hash is not in the table, the appliance is at a build the kit does not
  know yet: the jar still compiles, the build reports it as an unknown
  jar, and you can name it `vrops-adapters-sdk-<version>.<build>.jar`
  from the appliance's own version and build. The same
  section covers the optional 8.18.7 and 9.1.1 jars for the
  compatibility checks. Partners can pull the jar from the Broadcom TAP
  / partner SDK portal instead.

Then, from the root of this repo:

```bash
# 1. Fetch the build toolchain. Pin a full sdk-buildkit-vX.Y.Z tag (the
#    floating sdk-buildkit-v1 tag moves under you) and verify the published
#    checksum before unpacking. 1.0.27 is the version this repo's release
#    workflow pins.
BUILDKIT_VERSION=1.0.27
gh release download "sdk-buildkit-v$BUILDKIT_VERSION" \
  --repo sentania-labs/vcf-content-factory \
  --pattern "sdk-buildkit-$BUILDKIT_VERSION.tgz*"
# No gh? The assets are public; fetch both with curl instead:
#   base=https://github.com/sentania-labs/vcf-content-factory/releases/download/sdk-buildkit-v$BUILDKIT_VERSION
#   curl -fsSLO "$base/sdk-buildkit-$BUILDKIT_VERSION.tgz"
#   curl -fsSLO "$base/sdk-buildkit-$BUILDKIT_VERSION.tgz.sha256"
# Verify before unpacking. The strong check is against the hash this repo
# commits for its release builds (BUILDKIT_SHA256 in the release workflow),
# which a replaced release asset cannot match:
echo "$(awk '/^  BUILDKIT_SHA256:/{print $2}' .github/workflows/build-pak-on-tag.yml)  sdk-buildkit-$BUILDKIT_VERSION.tgz" | sha256sum -c -   # macOS: shasum -a 256 -c -
# For a version other than the pinned one, the release's own sidecar only
# proves the download is intact:
#   sha256sum -c "sdk-buildkit-$BUILDKIT_VERSION.tgz.sha256"
tar xzf "sdk-buildkit-$BUILDKIT_VERSION.tgz"

# 2. Point the kit at your SDK jar and build
export VCFCF_SDK_JAR=~/vcf-ops-sdk/vrops-adapters-sdk-9.0.2.0.25137845.jar
python3 -m sdk_buildkit validate-sdk .   # cheap loop: compile-check
python3 -m sdk_buildkit build-sdk .      # emits the .pak
```

The kit carries everything else it needs (including the
`vcfcf-adapter-base.jar` framework runtime that ends up in the pak's
`lib/`). `validate-sdk` is the fast iteration loop; exhaust it before
building paks.

**Dev builds vs releases.** Anything you build this way is a *dev
build*. The **official** artifact for this repo is the one its own CI
builds and attaches to a GitHub Release when a `v*` tag is pushed —
deterministic, no developer machine in the path.

**If you fork this repo**, the CI workflow
(`.github/workflows/build-pak-on-tag.yml`) needs one adjustment
before your own `v*` tags will build (it already runs on GitHub-hosted
`ubuntu-latest`, so no runner change is needed).

**SDK jar sourcing**: the upstream workflow fetches the Broadcom
   jar from a private repo via an `SDK_RUNTIME_SSH_KEY` deploy-key
   secret you won't have. Replace that step with your own source:
   store the appliance-extracted jar in **a private repo only**, and
   fetch it from there. Never upload the jar as a workflow artifact:
   on a public repo anyone can download workflow artifacts, which
   would amount to redistributing it. Then **also update the
   `--sdk-jar` argument** on the `build-sdk` line of the workflow to
   point at wherever your replacement step puts the jar. The explicit
   `--sdk-jar` flag overrides `VCFCF_SDK_JAR`, so setting the env var
   alone is not enough — if you leave `--sdk-jar _sdk_runtime/...` in
   place the build will look for the upstream path and fail. Do **not**
   commit the jar to a public repo (no redistribution).

## License

MIT, see `LICENSE` in this repository (the same license as the VCF Content Factory).

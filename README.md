# VCF Content Factory UniFi Controller

Monitors UniFi infrastructure via the classic Network API and Protect API. Discovers sites, gateways, switches, access points, NVR, and cameras. Per-switch-port and per-AP-radio child objects with PoE tracking. Switch ports are linked to the ESXi hosts cabled to them from the vCenter side: each host's vmnic LLDP neighbour (published by vCenter) is matched to the UniFi switch and port, read over the VCF Operations Suite API on the collector (optional; skipped where the collector has no local Suite API access). Replaces the Tier 1 UniFi Network and UniFi Network Integration management packs.

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
3. When uploading the pak (**Administration > Integrations > Repository >
   Add**), tick **Ignore the PAK file signature checking**. The box only
   appears once the setting is on.

With the setting off, the install fails with
`unsigned_pak_installation.not.allowed`. This is a cluster-wide security
setting: it lets any unsigned pak be installed, not only this one, so decide
it deliberately and consider turning it off again after installing. If you do,
it has to go back on before you install the next build. Upgraded appliances
keep whatever state the setting had before the upgrade.

## Installation

Upload the `.pak` file via **Administration > Integrations > Repository > Add** in VCF Operations (9.x; on 8.x, **Data Sources > Integrations > Repository**).

## Configuration

Create an adapter instance under **Administration > Integrations > Accounts > Add Account** (9.x; on 8.x, **Data Sources > Integrations > Accounts**) and provide:

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

- **JDK 11+** (`javac` + `jar` on PATH)
- **python3** with `pyyaml` (`python3 -m pip install pyyaml`)
- **The GitHub CLI** (`gh`) — used to download the build toolchain
  below. The factory repo is public, so no `gh auth login` is needed
  for the download (authenticate only if you hit anonymous API rate
  limits). No `gh`? See the `curl` alternative under step 1.
- **The Broadcom adapter SDK jar** (`vrops-adapters-sdk-2.2.jar`).
  This is a Broadcom build artifact with no public redistribution
  channel — it is **never** bundled in the toolchain or this repo.
  Get it from your own VCF Operations appliance:

  ```
  scp root@<appliance>:/usr/lib/vmware-vcops/common-lib/vrops-adapters-sdk-2.2.jar .
  ```

  (Also present at
  `/usr/lib/vmware-vcops/suite-api/WEB-INF/lib/vrops-adapters-sdk.jar`.
  Partners can pull it from the Broadcom TAP / partner SDK portal
  instead.)

Then, from the root of this repo:

```bash
# 1. Fetch the build toolchain (pin a full sdk-buildkit-vX.Y.Z tag for
#    reproducibility, or use the floating major sdk-buildkit-v1)
gh release download sdk-buildkit-v1 \
  --repo sentania-labs/vcf-content-factory \
  --pattern 'sdk-buildkit-*.tgz'
# No gh? The asset is public — fetch it with curl instead:
#   curl -sL https://github.com/sentania-labs/vcf-content-factory/releases/download/sdk-buildkit-v1/sdk-buildkit-v1.tgz -o sdk-buildkit-v1.tgz
tar xzf sdk-buildkit-*.tgz

# 2. Point the kit at your SDK jar and build
export VCFCF_SDK_JAR=/path/to/vrops-adapters-sdk-2.2.jar
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
   secret you won't have. Replace that step with your own source —
   e.g. store the appliance-extracted jar in your own private repo or
   an Actions secret/artifact store. Then **also update the
   `--sdk-jar` argument** on the `build-sdk` line of the workflow to
   point at wherever your replacement step puts the jar. The explicit
   `--sdk-jar` flag overrides `VCFCF_SDK_JAR`, so setting the env var
   alone is not enough — if you leave `--sdk-jar _sdk_runtime/...` in
   place the build will look for the upstream path and fail. Do **not**
   commit the jar to a public repo (no redistribution).

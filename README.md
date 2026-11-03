# CD-DVD Manager Plugin

> **Community Plugins — Not Officially Supported by HPE**
>
> These plugins are built by HPE as community contributions. They are **not** official HPE products and carry no HPE support, SLA or warranty. You are free to use them, fork them, and extend them to suit your environment. Pull requests and adaptations are welcome.

---

A Morpheus plugin that adds a **CD/DVD** tab to the instance page and to the server page of VMs running on VMware vSphere. Users can browse vCenter datastores, mount an ISO file to the VM's CD/DVD drive, and eject it, without leaving Morpheus.

This one plugin replaces the separate *CD-DVD Manager Server Tab* plugin. Uninstall that plugin when you install this one, otherwise the server page shows two tabs.

## Features
- Shows the VM's CD/DVD drives, the mounted ISO (if any) and whether the drive is connected.
- Browse the datastores the VM's host can access, open folders, and pick an ISO file.
- Mount the selected ISO to the VM's CD/DVD drive.
- Eject the disc, with a confirmation step.
- **Check vCenter** looks for a pending vCenter question on the VM (for example a locked CD/DVD drive) and lets the user answer it.
- On the **instance page** of an instance with several VMs, a **Virtual machine** dropdown lists each VM and **All VMs**. Nothing is selected at first and the buttons stay disabled until a choice is made. With one VM selected, **Mount** and **Eject** change that VM only and the tab shows its drive. With **All VMs**, **Mount** is applied to every VM and skips a VM whose drive already holds an ISO, **Eject** is applied to every VM, and the tab shows the drive of the primary VM (the one with the lowest workload id). A VM that fails does not stop the others, and the result is reported per VM. An instance with one VM has no dropdown.
- On the **server page** the tab acts on that one VM only.
- The tab appears only for VMware clouds (vmware, vsphere or esxi). On the server page it is hidden for servers that are not a vCenter VM.
- Access is controlled by a Morpheus role permission (none, read, full), the same for both tabs.

## Requirements
- Morpheus appliance (manifest minimum version 6.3.0) with a VMware vCenter cloud integration.
- vCenter account with permission to reconfigure the VM's virtual hardware and browse datastores.
- To build: JDK 11 or later. Gradle is included as `gradlew`.

## Build
```
.\gradlew clean build shadowJar
```
The plugin jar is `build/libs/CD-DVD Manager-<version>-all.jar`.

## Install
1. In Morpheus, go to **Administration > Integrations > Plugins** and upload the `-all.jar`.
2. Sign out and back in so the new permission appears in your session.
3. Go to **Administration > Roles**, open a role, and set **CD/DVD Tab** to `Read` or `Full`.

## Configuration

### Role permission
| Permission | Code | Levels |
|---|---|---|
| CD/DVD Tab | `cd-dvd-tab` | `none` hides the tab. `read` shows drive status only. `full` also shows **Browse Datastores**, **Eject** and **Check vCenter**. |

### vCenter connection
The plugin uses the vCenter URL and credentials of the instance's Morpheus cloud integration. It looks for credentials in this order:
1. The cloud's account credential data.
2. The credentials stored for that cloud.
3. The cloud's configuration map.
4. The cloud's service username and password.

If the cloud does not provide complete credentials, the plugin falls back to the plugin settings (**Administration > Integrations > Plugins > CD-DVD Manager Plugin > Settings**):

| Setting | Purpose |
|---|---|
| vCenter Username (fallback) | vCenter service account, used only for the cloud's own vCenter host |
| vCenter Password (fallback) | Password for that account |
| Allow untrusted vCenter certificate | Off by default. Accepts a self-signed certificate and shows a warning in the tab |

The plugin does not store credentials itself and does not log passwords.

## Usage
1. Open an instance or a server on a VMware cloud and select the **CD/DVD** tab.
2. Select **Browse Datastores**, choose a datastore, open the folder that holds the ISO, select the file and confirm to mount it. The datastore list is based on the host of the primary VM.
3. Select **Eject** to remove the disc, and confirm.

The plugin finds each VM in vCenter by the VM reference (for example `vm-4135`) that Morpheus stores as the external id of the VM's server. It does not look VMs up by name, so renaming a VM does not affect the tab. If any VM has no usable reference, the tab shows an error and makes no change.

## How it works
```
Browser -> Morpheus (instance or server page) -> CD-DVD Manager plugin -> vCenter (HTTPS, SOAP and REST)
```
- The tab's JavaScript calls these routes under `/plugin/isoMount/`: `vmData`, `datastores`, `browse`, `mount`, `unmount`, `question` and `answerQuestion`. Each call carries exactly one of `instanceId` or `serverId`, and for an instance with several VMs a `target` (a server id of that instance, or `all`). The plugin checks that the target belongs to the instance, resolves the VMs, the cloud and the vCenter credentials on the server, and finds each VM's first CD/DVD drive itself.
- For each request the plugin reads the credentials from Morpheus, logs in to vCenter once, does the work for every VM, and logs out.

## Known limitations
- A pending vCenter question is checked on the selected VM only. With **All VMs** selected it is not checked; select the VM instead.
- The vCenter certificate must be trusted unless **Allow untrusted vCenter certificate** is turned on in the plugin settings, in which case the tab shows a warning.
- An instance may have at most 25 VMs.

## Third-party software
The jar bundles `slf4j-api` (MIT). The license text and copyright notice are in [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) and inside the jar under `META-INF/`. Groovy and the Morpheus plugin API are provided by Morpheus and are not bundled.

## Versioning and branches
Versions follow MAJOR.MINOR.PATCH and are set in `gradle.properties`. `main` holds the latest release, `release/vX.Y.Z` holds each release, and `osrb-review` holds the code submitted for open source review. Releases are tagged `vX.Y.Z`.
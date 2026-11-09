# Cloudies ☁️

A personal Android cloud-file app inspired by the **files-on-demand** idea in [Helumi](https://www.helumi.app/). Built for Samsung Galaxy phones with Kotlin and Jetpack Compose.

Cloudies connects directly to **your own storage accounts**. There is no Cloudies storage subscription, hosted application server, advertising, or analytics SDK. Your provider's storage and transfer limits still apply.

## What the first version does

- Connect HTTPS WebDAV accounts: Koofr, Nextcloud, pCloud, and compatible servers.
- Connect Google Drive after [one-time personal OAuth setup](docs/GOOGLE_DRIVE.md). Uses the narrow `drive.file` permission and a dedicated Cloudies folder.
- Browse folders; search the current folder; create folders; rename and delete with confirmation.
- Stream video and audio with Media3, with the full video fitted on screen.
- Expose a read-only cloud location in Android's system file picker using `DocumentsProvider` and seekable proxy file descriptors.
- Upload files selected through the Android picker; receive files through Android's Share menu.
- Queue background transfers with progress, cancellation, and retry. Wi-Fi only is the default for new transfers.
- Save selected files offline; resume partial offline downloads when retried if the remote version is unchanged.
- Choose light, dark, or device appearance.
- Encrypt saved connection credentials with Android Keystore; require HTTPS; disable Android backup of application data.

**Current limits:** Upload retries restart the upload. File contents are **not yet end-to-end encrypted**. File-picker access is read-only; external editors cannot save back through the provider yet. There is no automatic camera backup, public link service, universal Samsung My Files integration, or automatic removal of originals. These are not represented as working features.

Streaming content uses a globally bounded **16 MiB RAM buffer** and is not written to disk by Cloudies. App code, the file index, explicit offline files, and interrupted offline downloads do use local storage. Other apps may download their own copy when opening or sharing a file. This does not expand the phone's physical storage or let Android applications run from cloud storage.

## Start with free storage

The following are provider allowances checked on **9 November 2026**, not storage supplied by Cloudies. Existing account usage reduces available space.

| Provider | Free allowance | Integration / important limit |
| --- | --- | --- |
| [Google Drive](https://support.google.com/accounts/answer/6374270) | Up to 15 GB | Shared with Gmail, Photos, and other Google storage; personal OAuth setup needed. |
| [Koofr](https://koofr.eu/pricing/) | 10 GB | WebDAV support; use an app password. Good first connection. |
| [OneDrive](https://support.microsoft.com/en-us/onedrive/microsoft-storage-faqs) | 5 GB | Microsoft Graph integration is on the roadmap, not included in v0.1. |
| [MEGA](https://apps.apple.com/in/app/mega/id706857885) | 20 GB | A separate MEGA SDK integration is needed; not included in v0.1. Free transfer limits also apply. |
| [Filen](https://filen.io/hub/an-update-on-free-account-creation/) | 10 GB for eligible signups | Free allocation is conditional at signup; a separate Filen API integration is needed. |
| [pCloud](https://help.pcloud.com/article/plan-details) | 2 GB initially; up to 10 GB unlockable | WebDAV available; regional URL and traffic limits apply. |
| [Cloudflare R2](https://developers.cloudflare.com/r2/pricing/) | 10 GB-month of Standard storage | Billed product with included usage, request allowances, and possible overages; not enabled by Cloudies. |
| [Self-hosted Nextcloud](https://nextcloud.com/home-users/) | Software is free | Capacity comes from your own computer/NAS/disks; hardware, power, and network access are your responsibility. |

Google Drive + Koofr can provide up to **25 GB across two separate accounts**, less existing usage. Cloudies does not merge provider quotas into one larger upload limit, create accounts, bypass quotas, or promise unlimited storage.

Telegram's [Bot Platform terms](https://telegram.org/tos/bot-developers) expressly exclude external cloud-storage services from intended Bot Platform uses. A Telegram bot is not Cloudies' storage backend. A specialized personal Telegram client is a separate design question and is not implemented here.

Helumi itself [describes using Cloudflare R2](https://www.helumi.app/#faq) and currently advertises paid storage. Free-to-download software does not make terabytes of hosted storage free.

## Connect Koofr

1. Create or use your own [Koofr account](https://koofr.eu/).
2. In Koofr's web settings, generate an application-specific password for Cloudies. See [official WebDAV instructions](https://app.koofr.net/help/webdav).
3. In Cloudies, tap **Connect** → **Koofr**.
4. Keep `https://app.koofr.net/dav/Koofr/`, enter your account email and app password, and connect.
5. Upload a small test file, play or open it, then choose **Keep offline** to verify offline access.

For Nextcloud, copy your own WebDAV address from Files settings, commonly `https://YOUR-SERVER/remote.php/dav/files/YOUR-USERNAME/`. Use an app password. For pCloud, use `https://webdav.pcloud.com/` (US) or `https://ewebdav.pcloud.com/` (EU).

## Build for free

Requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, Gradle 8.11.1. Android Studio can install the SDK components. The project pins Android Gradle Plugin 8.9.2 and Kotlin 2.1.20.

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The [Android APK workflow](.github/workflows/android.yml) runs on pushes and pull requests and uploads an APK artifact. Standard GitHub-hosted runners are [free for public repositories](https://docs.github.com/en/actions/concepts/billing-and-usage); larger runners and additional storage can be charged. Artifacts expire after seven days.

For a personal release that can update an earlier installation, preserve your signing keystore. Debug APKs from different CI runs may have different certificates and are for testing.

```bash
# Set these in your local shell or private CI secrets. Never commit them.
export CLOUDIES_KEYSTORE=/absolute/path/to/your/cloudies.jks
export CLOUDIES_STORE_PASSWORD=...
export CLOUDIES_KEY_ALIAS=cloudies
export CLOUDIES_KEY_PASSWORD=...
gradle testDebugUnitTest lintRelease assembleRelease
```

Install the signed APK directly on your own Samsung device; a public Play Store listing is unnecessary for personal use. Samsung Auto Blocker or Android install-source settings may require an explicit installation decision. Android also offers [free limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution) for personal and hobby projects.

## Data and permissions

- Internet and network-state access for your cloud connection.
- Foreground data-sync service for long transfers. Notification permission is optional; declining does not grant broad storage access.
- Selected-file URI permissions through the Android picker. No contacts, SMS, location, or all-files permission.
- Connection credentials are stored encrypted in the app's private directory using a non-exportable Android Keystore key. Cloud file contents are protected in transit by HTTPS; provider-side encryption is distinct from end-to-end encryption.
- Account disconnection cancels its queued transfers and removes its saved credentials, index, and offline copies. Cloud files are not deleted.
- An upload never automatically deletes the phone original. WebDAV uploads use a temporary destination and a move with overwrite disabled, so an interrupted upload cannot silently replace a good existing file. A provider that does not support these operations may reject the upload.
- Deleting through WebDAV may be permanent, depending on your server. Google Drive deletion moves the remote item to its provider trash.

## Build order and next steps

1. **v0.1:** Real cloud connections, range reads, bounded streaming cache, file browser/player, background transfers, offline files, system file-picker provider, personal APK builds.
2. **Reliability:** Persisted upload checkpoints, transfer pause/resume, provider quota reporting, device tests on the user's Samsung model, large-file and unstable-network testing.
3. **Privacy:** Versioned encrypted vault using an established streaming AEAD library, encrypted names/metadata, Keystore-wrapped master key, recovery export/import, and seekable authenticated decryption. A screen lock is not a substitute for file encryption.
4. **Providers and convenience:** OneDrive, MEGA, and Filen adapters, selected-folder backup, thumbnails, duplicate detection, provider-aware trash, and optional existing-PC/NAS storage.

Public encrypted links and upload-request links would need separate web/key-sharing design and hosting. They are outside the first personal-use app.

See [architecture](docs/ARCHITECTURE.md), [Google Drive setup](docs/GOOGLE_DRIVE.md), and [research sources](docs/RESEARCH.md).

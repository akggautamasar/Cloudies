# Architecture

Cloudies is an Android application. There is no required application backend or cloud database.

| Component | Purpose |
| --- | --- |
| Kotlin / Jetpack Compose | Native UI, adaptive text, light/dark theme, lifecycle-aware state |
| `CloudClient` | Provider interface for metadata, range reads, uploads, and mutations |
| `WebDavClient` | HTTPS PROPFIND/GET/PUT/MOVE/MKCOL/DELETE; no credential-bearing redirects |
| `DriveClient` | Google Identity AuthorizationClient, drive.file scope, REST API |
| `Catalog` | Private SQLite metadata index and explicit offline-copy records |
| `ConnectionStore` | AES-GCM encryption of credentials using Android Keystore |
| `RangeCache` | One globally bounded 16 MiB RAM cache; provider/version/block keys |
| `CloudDocumentsProvider` | Android system picker and read-only seekable file descriptors |
| Media3 | Stream playback through content URIs; fit video without cropping |
| WorkManager | Durable transfer requests, network constraints, foreground progress |
| `myfiles/LoopbackFtpServer` | Read-only localhost FTP for Samsung My Files Network storage |
| `myfiles/MyFilesBridgeService` | Explicit session with Stop notification and idle/system timeout handling |

## Samsung My Files

v0.2 adds a one-time setup panel on Home and Settings. A non-exported `dataSync`
foreground service exposes the virtual account tree over password-protected
FTP on `127.0.0.1:2121`. Passive data sockets are also loopback-only. It uses
the shared range cache and current cloud clients; provider credentials stay
private. There are no mutation methods in the bridge filesystem. Active FTP,
uploads, rename and delete commands are refused. The service stops on explicit
Stop, 15 minutes of inactivity, process termination or the Android service
timeout, and never restarts automatically. A saved network location and any
favourite/main-screen shortcut require user actions in Samsung My Files.
This does not register a Samsung built-in cloud tile.

See [Samsung My Files setup](SAMSUNG_MY_FILES.md) for behavior, privacy boundaries
and device verification limits.

## Upload

The Android file picker grants access to selected URIs. Cloudies stores only that selected access. The worker reads from the URI and sends directly to the provider. Originals are not removed. WebDAV stages under a randomized filename, then moves without overwriting an existing destination. Failed/cancelled uploads attempt to clean up staging files; a lost process or lost connection may leave a staging file that needs removal.

Google Drive uses resumable-upload requests inside a single running transfer, but v0.1 does not persist upload sessions or resume failed uploads. Retry starts over. Shared URIs can have shorter-lived grants than picker-selected files.

## Stream

A consumer requests bytes from a proxy file descriptor. The range cache requests only the corresponding 256 KiB blocks through authenticated HTTPS. A seek changes which blocks are requested. The globally bounded cache evicts older blocks. The application does not create a full local streaming file.

Nonzero range requests fail if the server ignores ranges, rather than silently fetching and discarding a large file prefix. For such servers, choose an offline download. Other apps may independently choose to copy content into their own cache; Cloudies cannot control that.

## Offline

An explicit download creates a private partial file and records the remote version. Retry continues the partial file if the version is unchanged. The version is checked again before the partial file becomes an offline copy. The phone must have enough free space for the selected content. Offline content is stored in the app sandbox and removed if app data is cleared or the app is uninstalled.

## Security boundary

Saved connection passwords are encrypted at rest; HTTPS protects transfers. Ordinary uploaded file contents and their names remain visible to the storage provider. File E2EE is a future feature and must not be claimed before implementation and recovery testing.

The public repository must never contain app passwords, OAuth access tokens, sessions, signing keystores, or private user files. The Android documents provider uses MANAGE_DOCUMENTS protection plus individual URI grants. It exposes read-only handles and no universal filesystem mount. Samsung's own My Files app can differ from the Android system picker.

## Verification boundary

Unit tests exercise byte-range validation, refusal of ignored seeks, cache bounds, version separation, cross-block reads, EOF behavior, unsafe names, and HTTPS endpoint rules. A compiled APK and those tests do not establish compatibility with every Samsung firmware, WebDAV implementation, or external editor. Account-backed and physical-device testing remains necessary.

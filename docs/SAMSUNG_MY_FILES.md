# Samsung My Files connection (v0.2)

Cloudies can expose connected accounts to Samsung My Files through a saved
**Network storage → FTP server** connection. This is a read-only connection on
the phone itself. It does not register a new built-in cloud tile beside
Google Drive or OneDrive, and cannot change Samsung's main-screen layout.

Samsung documents Network storage as a place to add a server and copy files:
<https://www.samsung.com/us/support/answer/ANS10002531/>. Its Galaxy Store
listing also notes that available features vary by model:
<https://galaxystore.samsung.com/detail/com.sec.android.app.myfiles>.
There is no public Samsung cloud-tile registration API used by this project.
The saved network connection is the available integration route, rather than
an undocumented intent or a change to Samsung's application.

## One-time setup

1. Connect at least one cloud account in Cloudies.
2. Tap **Samsung My Files** on Cloudies Home or Settings.
3. Tap **Start connection**. Notification permission is optional.
4. Tap **Open Samsung My Files**, then open **Network storage → + → FTP server**.
   Install/enable Samsung's Network storage component if My Files requests it.
5. Enter the following settings:

   | Field | Value |
   | --- | --- |
   | Server address | `127.0.0.1` |
   | Port | `2121` |
   | Username | `cloudies` |
   | Password | Use **Copy password** in Cloudies |
   | Anonymous sign-in | Off |
   | Encryption / SSL | Off (the FTP connection is loopback-only) |
   | Display name | `Cloudies` |

6. Save the connection. Each account appears as a folder with its name and a
   stable short suffix, so accounts with the same name do not overlap.
7. If your My Files version offers a favourite/main-screen shortcut for a
   network folder, use that option on the folder. Its availability and menu
   wording are controlled by Samsung. The setup targets a saved
   **Network storage** entry, not an automatically added
   top-level cloud tile.

## Supported actions and limits

- Browse folders, open files, and copy files **from** cloud storage to your phone.
- Streaming reads request bounded byte ranges; Cloudies does not stage a full
  local download. Samsung My Files or a receiving application may save its own
  full local copy. A cloud location is not additional physical phone storage.
- Upload with **My Files → select files → Share → Cloudies**. Direct copying
  **to** this network connection, editing, moving, deleting and creating folders
  through FTP are refused. No upload or delete capability is advertised.
- Google Drive remains limited to the Cloudies folder and files available under
  the app's `drive.file` authorization. Duplicate file names in a Drive folder
  cannot be distinguished by FTP path and are rejected; rename them in Cloudies.
- Start the connection in Cloudies whenever needed. Stop it in the setup panel
  or its notification. It stops after 15 minutes without activity and may also
  stop when Android ends the service. It is not started at boot or after process
  death. Android 15+ also limits background `dataSync` service time; the service
  handles that timeout by stopping.
- Cloud reads may use mobile data. The transfer queue's Wi-Fi-only setting does
  not constrain streaming/file-manager reads.
- Samsung's Network storage feature and its acceptance of loopback FTP must
  be verified on the target phone/One UI version. No Samsung firmware/emulator
  is bundled with the repository; successful compilation and protocol tests
  do not constitute a physical Samsung device test.

## Connection security and implementation

The control and passive data sockets bind only to `127.0.0.1`. Nothing listens
on the phone's Wi-Fi/mobile addresses. Active FTP is disabled. Sign-in requires
a randomly generated 192-bit password kept in a separate encrypted
`ConnectionStore`; provider passwords are never exposed as FTP credentials.
Other apps on the same phone can reach loopback, so this is not an isolation
boundary against a malicious app. Keep the generated password private.

The bridge is read-only at its filesystem interface as well as at the protocol
layer. It limits concurrent sessions and command lengths, validates virtual
paths, rejects unsafe names and ambiguous matches, checks account revocation
before each read, and closes pending sockets when stopped. Remote identifiers
come from the connected provider's listings, never from client-supplied IDs.
The shared 16 MiB `RangeCache` bounds cloud data cached in RAM.

The service is non-exported and starts only after an explicit action in the
visible Cloudies app. Its ongoing notification opens setup and offers Stop.
It uses the existing `dataSync` foreground-service permission; no all-files,
contacts, location, Wi-Fi management or boot-start permission is added.

Unit tests exercise actual loopback FTP sessions and virtual cloud accounts,
including authentication, LIST/MLSD/NLST, UTF-8 names, passive connections,
REST seeks, bounded reads, read-only rejection, shutdown and account revocation.

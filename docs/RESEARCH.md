# Research notes · 9 November 2026

This is a technical proposal based on public product pages and official documentation, not a security audit of Helumi or independent measurement of its advertised performance.

## Helumi

- [Product and pricing](https://www.helumi.app/): macOS virtual drive, cloud-backed files, partial fetching, offline pinning, resumable uploads, shared links, file requests, trash, and browser access. Current displayed storage plan: 1 TB at US$10/month. macOS 26.2 or later is listed.
- [Security](https://www.helumi.app/security): the vendor describes client-side encryption of contents and names, version-specific file keys, authenticated 1 MiB chunks, device pairing, and recovery keys.
- [Privacy](https://www.helumi.app/privacy): Cloudflare stores files/metadata; Vercel hosts the website. The product FAQ specifies Cloudflare R2.
- [Changelog](https://www.helumi.app/changelog): useful historical context, but an earlier free offer must not be assumed to be an ongoing entitlement.

Cloudies implements the general files-on-demand idea using Android interfaces. It does not copy proprietary code, branding, credentials, or bypass Helumi subscription checks.

## Android feasibility

- [DocumentsProvider](https://developer.android.com/guide/topics/providers/create-document-provider): expose a cloud source through the system file picker.
- [StorageManager.openProxyFileDescriptor](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,android.os.ProxyFileDescriptorCallback,android.os.Handler)): seekable on-demand file access, including cloud files; available since API 26.
- [Android authorization](https://developer.android.com/identity/authorization): AuthorizationClient can request Drive access independently of authentication.
- [Tink streaming AEAD](https://developers.google.com/tink/streaming-aead): an established candidate for future segmented, authenticated encryption and partial decryption. v0.1 does not implement it.
- [Limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution): free personal/hobby distribution is available; avoid assuming that a public app-store fee is required for a personal APK.
- [Samsung Auto Blocker](https://www.samsung.com/in/support/mobile-devices/protect-your-galaxy-device-with-the-new-auto-blocker-feature/): can block APK installation from unauthorized sources; the device owner controls installation decisions.

## Free storage and infrastructure

- [Google: shared 15 GB allowance](https://support.google.com/accounts/answer/6374270)
- [Google: Drive API quotas and pricing](https://developers.google.com/workspace/drive/api/guides/limits)
- [Koofr: 10 GB plan](https://koofr.eu/pricing/) and [WebDAV](https://app.koofr.net/help/webdav)
- [Microsoft: free 5 GB cloud storage](https://support.microsoft.com/en-us/onedrive/microsoft-storage-faqs)
- [MEGA's published app description: 20 GB](https://apps.apple.com/in/app/mega/id706857885) and [SDK](https://github.com/meganz/sdk)
- [MEGA transfer quota](https://help.mega.io/plans-storage/space-storage/transfer-quota): free storage capacity must not be mistaken for unlimited streaming bandwidth.
- [Filen's free allocation changes](https://filen.io/hub/an-update-on-free-account-creation/) and [API](https://docs.filen.io/docs/api/): free allocation can depend on signup eligibility.
- [pCloud free plan](https://help.pcloud.com/article/plan-details) and [WebDAV](https://help.pcloud.com/article/connect-to-pcloud-using-webdav-and-rsync)
- [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/) and [subscription setup](https://developers.cloudflare.com/r2/get-started/): included usage is 10 GB-month Standard storage, 1M Class A and 10M Class B requests monthly; overages can be billed. Not a no-billing unlimited drive.
- [Nextcloud for home users](https://nextcloud.com/home-users/): software is free; storage hardware and availability remain necessary.
- [Syncthing FAQ](https://docs.syncthing.net/users/faq.html): sync exchanges data between your devices; it does not supply cloud capacity. Ordinary full-folder sync does not solve files-on-demand by itself.
- [GitHub Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage): standard public-repository runners are free; storage and other chargeable resources still have limits.

## Telegram

[Telegram FAQ](https://telegram.org/faq) describes cloud file sharing with up to 2 GB per file for free users. That does not establish suitability as a guaranteed cloud-drive service.

[Bot Platform terms](https://telegram.org/tos/bot-developers), section 5(e), exclude external services that significantly diverge from intended platform uses, explicitly including cloud-storage sites. The [hosted Bot API](https://core.telegram.org/bots/api) also has tighter upload/download limits. Therefore a Telegram bot is not the foundation of Cloudies.

A personal MTProto/TDLib client would be a different integration subject to [Telegram API terms](https://core.telegram.org/api/terms), its own API identity, client behavior requirements, and operational limits. Cloudies v0.1 does not claim or implement that integration.

# Google Drive setup for your personal APK

Google Drive access is implemented using Google's Android `AuthorizationClient`. Cloudies requests only `https://www.googleapis.com/auth/drive.file` and creates a `Cloudies` folder. The app can manage its own files and files explicitly authorized for it, not automatically browse the whole Drive account.

## One-time free setup

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/) under your own account.
2. Enable **Google Drive API** in APIs & Services.
3. Configure the OAuth consent screen for your personal application. If using Testing mode, add your own email as a test user. Testing-mode authorization may need renewal; do not assume permanent login.
4. Create an **Android OAuth client**. Set the package name to `app.cloudies.android` and use the SHA-1 fingerprint of the certificate signing the exact APK you install.
5. A native Android client has no client secret to paste into the app. The package and signing certificate identify the client through Google Play services.
6. Install the APK, open **Connect your cloud → Google Drive**, and approve the requested access.

To inspect a local signing certificate:

```bash
keytool -list -v -keystore /path/to/your/cloudies.jks -alias cloudies
```

Use the same private keystore for future personal releases. Register another Android OAuth client if you also want to test a debug APK signed with a different certificate. A developer configuration error such as error 10 commonly means the package/SHA-1 registration does not match the installed APK.

Cloudies currently supports one Google Drive connection. It does not manage arbitrary multi-account Google sessions.

## Costs and quotas

[Google Account storage](https://support.google.com/accounts/answer/6374270) includes up to 15 GB shared with Drive, Gmail, Photos, and other applicable backups. Existing usage reduces what Cloudies can use.

Google currently describes [standard Drive API usage as available at no additional cost](https://developers.google.com/workspace/drive/api/guides/limits), within quotas. Its documentation also describes planned charges above API thresholds. Cloudies is designed for normal personal use, does not need a billed hosting service, and should not be treated as a guarantee of unlimited or permanently free API usage. Do not enable paid quotas solely for this application.

Google's own account-selection and consent UI must complete on your device. Never paste your Google password, OTP, or recovery codes into repository files or send them to another person.

Official references:

- [Android authorization](https://developer.android.com/identity/authorization)
- [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Partial downloads](https://developers.google.com/workspace/drive/api/guides/manage-downloads)
- [Resumable uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads)

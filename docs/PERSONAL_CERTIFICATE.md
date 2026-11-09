# Personal APK certificate

Package: `app.cloudies.android`

Public signing certificate fingerprints for the first personal APK:

```text
SHA1: 01:00:2C:38:75:D8:E6:2B:D9:82:DE:02:B3:5C:60:26:89:DD:2C:EE
SHA256: 4E:19:89:08:E8:D1:BA:0F:D5:69:44:2C:04:55:F4:F7:15:B0:F9:49:87:AF:94:D2:ED:3E:0E:97:3C:93:7D:FF
```

Use the SHA-1 when creating the Android OAuth client for Google Drive. Future APK updates must use the same private keystore. The keystore is kept separately and is not in this public repository. CI debug builds use a different certificate.

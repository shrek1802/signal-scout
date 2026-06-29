Signal Scout v3.9.2 Version Compile Fix

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.9.2
6. Channel: beta

Changes:
- Fixes v3.9.1 build failure
- Removes BuildConfig dependency from WebView bridge
- Reads installed version using Android PackageManager instead
- App version display remains synced with the installed APK
- Keeps Auto Band + Combo Optimiser unchanged

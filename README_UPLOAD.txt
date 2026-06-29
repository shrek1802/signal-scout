Signal Scout v3.9.1 Version Fix

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.9.1
6. Channel: beta

Changes:
- Gradle version updated to 3.9.1 / versionCode 3910
- App exposes BuildConfig.VERSION_NAME to WebView
- Menu/About versions now read from the actual Android build
- About page shows build code
- Prevents future mismatch like app showing v3.0.0 while release is v3.9.0
- Keeps Band Optimiser and router logic unchanged

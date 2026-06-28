Signal Scout v3.6.3 Installer Button Fix

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.6.3
6. Channel: beta

Changes:
- Fixes Installer Mode button doing nothing
- Tries to open native InstallerActivity
- Adds fallback to the in-app Installer overlay if Activity launch fails
- Adds visible feedback when pressing Installer Mode
- Exit works for fallback overlay
- Home, Dashboard, Signal Optimiser and router engine otherwise unchanged

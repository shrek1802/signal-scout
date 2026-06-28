Signal Scout v3.6.1 Installer Mode Stability

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.6.1
6. Channel: beta

Changes:
- Fixes Installer Mode returning to Home after rotating
- Normal app is portrait
- Installer Mode switches to landscape
- Exit returns to portrait and Signal Optimiser
- Current page is remembered
- Installer Mode state is remembered
- Activity handles orientation/screen size changes without recreating the WebView
- Home, Dashboard, Signal Optimiser and router engine otherwise unchanged

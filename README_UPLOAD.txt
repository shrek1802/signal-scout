Signal Scout v3.6.2 Installer Activity Fix

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.6.2
6. Channel: beta

Changes:
- Installer Mode is now a real Android Activity
- InstallerActivity is landscape-only
- Exit button calls finish(), so it should reliably return to Signal Optimiser
- Arrows use matching text glyphs in native TextViews
- Main app remains portrait
- Home, Dashboard, Signal Optimiser and router engine otherwise unchanged

This fixes:
- Installer Mode not rotating
- Android returning to Home on rotation
- Exit button not responding
- Uneven emoji arrow sizing

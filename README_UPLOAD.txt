Signal Scout v3.6.4 Native Installer Only

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.6.4
6. Channel: beta

Changes:
- Removes broken Installer overlay fallback
- Installer Mode button now only opens native InstallerActivity
- If native Activity fails, it shows an error instead of opening the old overlay
- InstallerActivity is landscape-only
- Exit button uses native Android finish()
- Arrows replaced with equal-style native glyphs
- Main app remains portrait

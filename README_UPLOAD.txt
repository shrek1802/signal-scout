Signal Scout v3.9.0 Auto Band + Combo Optimiser

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.9.0
6. Channel: beta

Changes:
- Adds Auto Scan Single Bands
- Adds Auto Scan Band Combos
- Adds results table
- Scores each result using Signal Scout quality score
- Recommends best result
- Keeps manual band lock buttons
- Keeps unlock all bands / AUTO button
- Keeps raw debug output for troubleshooting

Important:
- This sends Huawei HiLink band-lock commands.
- Each test waits about 15 seconds for reconnect.
- If a band loses signal, use AUTO / All LTE bands or reboot the router.
- Combo support depends on router/firmware accepting LTEBand masks.

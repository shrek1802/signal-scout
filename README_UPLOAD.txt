Signal Scout v3.6.0 Signal Optimiser

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.6.0
6. Channel: beta

Changes:
- Dashboard kept from v3.5.6
- Home kept unchanged
- Adds Signal Optimiser page
- Adds Installer Mode
- Installer Mode tries to rotate/lock landscape
- Huge arrows for antenna guidance
- Shows live SINR, RSRP, RSRQ, band and best SINR
- Basic guidance logic:
  improving = keep going
  drop after best = go back
  close to best = stop
- Router engine unchanged

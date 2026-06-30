Signal Scout v3.9.0 Live Router Engine

Use:
1. Rename this ZIP to upload.zip
2. Upload to repo root
3. Run Apply upload.zip Update
4. Run Release Manager
5. Version: 3.9.0
6. Channel: beta

Changes:
- Starts v3.9 live router engine work from the good v3.8.16 UI base
- Adds router driver selector: Auto / Huawei HiLink / TP-Link MR600
- Keeps Huawei HiLink login and live /api/device/signal support
- Adds TP-Link MR600 probe driver and debug endpoints
- Sends one shared live reading object to Dashboard, Signal Optimiser, Tower Finder, Live Graphs and Band Optimiser
- Adds TP-Link endpoint logging so MR600 firmware-specific fields can be mapped if needed
- No home screen redesign

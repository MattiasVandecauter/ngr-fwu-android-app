# NGR BLE Firmware Updater Android App

Native Android mirror of `ble_fw_upgrade.py`.

The app:

- scans by BLE name `DKN_SDG_BLE_HCI_HOST` or connects to a typed MAC address
- pairs/bonds through Android
- requests MTU `498`
- discovers the same FWU write, capability read, and SMP characteristics
- writes `{"fwuMode":true}`
- reads slots from `mainFreeSlot` and `radioFreeSlot`
- uploads main firmware to `mainFreeSlot`
- uploads radio firmware to `radioFreeSlot + 2`
- waits for `readyForInfo` and `uploadSuccess`
- uses the same MCUmgr/SMP image upload framing, CBOR payloads, 384-byte chunks,
  10-request window, and 3 retries as the Python script

Open this directory in Android Studio and run the `app` module on a BLE-capable
Android device. The local shell in this environment does not include Gradle or
an Android SDK, so the project is scaffolded for Android Studio rather than
validated with a local command-line build here.

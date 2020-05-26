# Lock Scanner - Beacon Scanning App

## Intro

This is an application that demonstrates the use of the Nordic BLE Scanner Library. Based on an
implementation by Acute Technology.

Specifically, we program the dev board as the "lock" with a modified version of the Heart RateService example as beacon.
Once booted, it advertises with a HR UUID. When the app detects the device, it checks whether it is included in a hardcoded list of devices.
If so, it GATT-connects to it.

For background, see the documentation of the library here:
https://github.com/NordicSemiconductor/Android-Scanner-Compat-Library
and this discussion on an example of the use of the library:
https://devzone.nordicsemi.com/f/nordic-q-a/50642/background-operation-of-ble-library-with-android-8---request-for-example

The LockScannerService runs all the time. It can be started by the application's MainActivity,
but also by code in StartupReceiver, which is invoked when the Android device wakes from a reset. This has the effect
that the app appears to be always listening for advertisements, even after the Android device has been reset.


## Steps to Configure the App

1. Change UUID_HEART_RATE_SERVICE to the uuid advertised by your device (LockManager.java).
2. Edit the list of devices to connect to (MAC address and optionally name) in initialiseAfterReboot() (BleReceiver.java).

## How to Use the App

The button starts/stops scanning. Events are printed to the main view.


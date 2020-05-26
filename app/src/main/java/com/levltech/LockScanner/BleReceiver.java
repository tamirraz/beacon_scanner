package com.levltech.LockScanner;


/*
The code here manages the scanner library. In particular, it enables or disables the scanning.
It also listens for incoming PendingIntents when a matching BLE device is found.

It also listens for events associated with enabling and disabling the device'ss Bluetooth.
 */


import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanRecord;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

public class BleReceiver extends BroadcastReceiver {

    private static final String TAG = "APP_BleReceiver";

    public static final String ACTION_SCANNER_FOUND_DEVICE = "com.levltech.LockScanner.ACTION_SCANNER_FOUND_DEVICE";

    public static final String DEVICE_FOUND = "com.levltech.LockScanner.DEVICE_FOUND";
    public static final String BLE_DEVICE = "com.levltech.LockScanner.BLE_DEVICE";
    public static final String BLE_DEVICE_NAME = "com.levltech.LockScanner.BLE_DEVICE_NAME";
    public static final String BLE_DEVICE_RSSI = "com.levltech.LockScanner.BLE_DEVICE_RSSI";
    public static final String BLE_SERVICE_UUID = "com.levltech.LockScanner.BLE_SERVICE_UUID";

    public static final String SCANNING_STATE = "com.levltech.LockScanner.SCANNING_STATE";
    public static final String EXTRA_SCANNING_STATE = "com.levltech.LockScanner.EXTRA_SCANNING_STATE";
    public static final String EXTRA_SCANNING_UUID = "com.levltech.LockScanner.EXTRA_SCANNING_UUID";

    private static PendingIntent mPendingIntent;

    private static Boolean mScanning = false;
    private static Boolean mShouldScan = false;

    private static Context mContext;

    private static SharedPreferences sSharedPreferences;

    private static String mUuid = "";

    // for re-scheduling scans
    private static Handler mScheduleHandler;

    // A list of "our" devices, so we can ignore other people's devices
    private static HashMap<String, String> sDeviceList;

    // Set true when we are processing a device and false when this is finished.
    private static Boolean mProcessingDevice = false;


    /**
     * Constructor
     */
    public BleReceiver() {
        Log.v(TAG, "in Constructor");
    }

    @Override
    public void onReceive(Context context, Intent intent) {

        Log.v(TAG, "  ");
        Log.v(TAG, "onReceive() ");

        if (intent.getAction() == null) {
            Log.e(TAG, "ERROR: action is null");
            return;
        }
        else {
            Log.v(TAG, "DEBUG: action is " + intent.getAction());
        }

        //NOTE: actions must be registered in AndroidManifest.xml
        switch (intent.getAction()) {

            // Look whether we find our device
            case ACTION_SCANNER_FOUND_DEVICE: {
                Bundle extras = intent.getExtras();

                if (extras != null) {
                    Object o = extras.get(BluetoothLeScannerCompat.EXTRA_LIST_SCAN_RESULT);
                    if (o instanceof ArrayList) {
                        ArrayList<ScanResult> scanResults = (ArrayList<ScanResult>) o;
                        Log.v(TAG, "There are " + scanResults.size() + " results");

                        if (!mShouldScan) {
                            Log.d(TAG, "*** Unexpected device found: not scanning");
                        }

                        for (ScanResult result : scanResults) {
                            if (result.getScanRecord() == null) {
                                Log.d(TAG, "getScanRecord is null");
                                continue;
                            }

                            BluetoothDevice device = result.getDevice();
                            ScanRecord scanRecord = result.getScanRecord();
                            String scanName = scanRecord.getDeviceName();
                            String deviceName = device.getName();
                            int rssi = result.getRssi();
                            //mHeader.setText("Single device found: " + device.getName() + " RSSI: " + result.getRssi() + "dBm");
                            Log.i(TAG, "Found: " + device.getAddress()
                                    + " scan name: " + scanName
                                    + " device name: " + deviceName
                                    + " RSSI: " + result.getRssi() + "dBm");

                            // Sometimes the same device is found again, even though we have stopped scanning as soon as it was found.
                            // Discard these events.
                            if (mProcessingDevice) {
                                Log.d(TAG, "Ignoring " + scanName + " (already processing).");
                                return;
                            }

                            // There could be devices we are not interested in. For now, any are accepted.
                            // Later a list of devices of interest could be constructed.
                            if (isInOurList(device)) {
                                Log.d(TAG, "Hey! ours!");
                                stopScan();
                                notifyDeviceFound(device, scanRecord, rssi);  // broadcast this back to the activity
                                scheduleScan(10000);     // restart if no one else allows the scan to resume
                            }
                            else {
                                Log.d(TAG, "Not our device");
                            }
                        }

                    } else {
                        // Received something, but not a list of scan results...
                        Log.d(TAG, "   no ArrayList but " + o);
                    }
                } else {
                    Log.d(TAG, "no extras");
                }

                break;
            }

            // Look at BLE adapter state
            case BluetoothAdapter.ACTION_STATE_CHANGED: {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        Log.d(TAG, "BLE off");
                        // Need to take some action or app will fail...
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        Log.d(TAG, "BLE turning off");
                        stopScan();
                        break;
                    case BluetoothAdapter.STATE_ON:
                        Log.d(TAG, "BLE on");
                        startScan();    // restart scanning (provided the activity wants this to happen)
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        Log.d(TAG, "BLE turning on");
                        break;
                }
                break;
            }

            default:
                // should not happen
                Log.d(TAG, "Received unexpected action " + intent.getAction());
        }

    }

    /**
     * After reboot we need to get saved state from SharedPreferences
     * This is called when LockScannerService starts after reboot.
     */
    public static void initialiseAfterReboot() {

        HashMap<String, String> wantedDeviceList = new HashMap<String, String>();
        wantedDeviceList.put("FF:35:F8:80:00:F5", "Levl SmartLock");
        onDeviceListChanged(wantedDeviceList);

        Log.d(TAG, "Initialising state saved from SharedPreferences");
        LockScannerApplication.setupSharedPreferences();

        mContext = LockScannerApplication.getAppContext();

        mUuid = LockScannerApplication.getSavedUuid();
        mShouldScan = LockScannerApplication.getScanning();

        if (mShouldScan) {
            Log.d(TAG, "Looks like we were scanning before reboot, so we will start again");
            startScan();
        }
        else {
            Log.d(TAG, "Looks like we were not scanning before reboot.");
        }
    }

    /**
     * MainActivity asks to know what we are doing.
     * We reply with a Broadcast
     */
    public static void requestScanningState() {
        notifyScanState();
    }

    /**
     * Called externally only
     *
     * @param context
     * @param uuid       Calling code defines the 128-bit UUID
     */
    public static void startScanning(Context context, String uuid) {
        mContext = context;
        mUuid = uuid;
        mShouldScan = true;

        // Save these in SharedPreferences, so they are available after reboot
        LockScannerApplication.saveUuid(uuid);
        LockScannerApplication.saveScanning(true);

        startScan();
    }

    /**
     * Used internally only
     */
    private static void startScan() {

        mProcessingDevice = false;

        if (!mShouldScan) {
            Log.d(TAG, "User has not requested scanning, so won't scan");
            return;
        }
        else {
            Log.d(TAG, "Trying to start scan.");
        }

        // cancel the scheduled restart, if any
        if (mScheduleHandler != null) {
            mScheduleHandler.removeCallbacksAndMessages(null);
        }

        ScanSettings settings = new ScanSettings.Builder()
                //.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                //.setReportDelay(0)
                .build();


            List<ScanFilter> filters = new ArrayList<>();
            filters.clear();
            Log.d(TAG, "Starting to scan for: " + mUuid);
             filters.add(new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(mUuid)).build());

            Intent intent = new Intent(mContext, BleReceiver.class); // explicit intent
            intent.setAction(BleReceiver.ACTION_SCANNER_FOUND_DEVICE);
            int id = 0;     // "Private request code for the sender"

            mPendingIntent = PendingIntent.getBroadcast(mContext, id, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            // Now start the scanner

            try {
                Log.d(TAG, "Asking library to start scanning.");
                BluetoothLeScannerCompat.getScanner().startScan(filters, settings, mContext, mPendingIntent);
                mScanning = true;
                notifyScanState();
            }
            catch (Exception e) {
                Log.e(TAG, "ERROR in startScan() " + e.getMessage());
            }
    }

    /**
     * Called externally only
     */
    public static void stopScanning() {
        mProcessingDevice = false;
        mShouldScan = false;
        stopScan();

        // Save this in SharedPreferences, so it is available after reboot
        LockScannerApplication.saveScanning(false);
    }

    /**
     * Used internally only
     */
    private static void stopScan() {
        //if (mScanning) {
        // do this unconditionally? maybe we could be still scanning for some reason?

        //if (mPendingIntent != null && mContext != null) {
        Log.d(TAG, "Stop scanning");

        if (mContext== null || mPendingIntent == null) {
            Log.d(TAG, "Can't stop: parameters are null");
            return;
        }

        // cancel the scheduled restart, if any
        if (mScheduleHandler != null) {
            mScheduleHandler.removeCallbacksAndMessages(null);
        }

        try {
            Log.d(TAG, "Asking library to stop scanning.");
            BluetoothLeScannerCompat.getScanner().stopScan(mContext, mPendingIntent);
            mScanning = false;

            notifyScanState();
        } catch (Exception e) {
            Log.e(TAG, "ERROR in stopScan() " + e.getMessage());
        }
    }

    /** Allows the calling activity to know what is happening
     *
     * @return true if scanning is underway.
     */
    public static Boolean isScanning() {
        return mScanning;
    }

    /**
     * Restart scanning after a delay
     */

    private void scheduleScan(int delayMillis) {

        Log.v(TAG, "Scheduling a restart for " + delayMillis + "ms time");
        mScheduleHandler = new Handler();
        mScheduleHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.v(TAG, "Time up - scan again");
                setProcessingDevice(false); // This will also re-start the scanning
            }
        }, delayMillis); // milliseconds

    }


    /**
     * The main application sends us this message when it fetches a deviceList after the user logs on.
     * If there are entries in it then we can start scanning
     * @param deviceList
     */
    public static void onDeviceListChanged(HashMap<String, String> deviceList) {

        sDeviceList = deviceList;
        if (deviceList == null) {
            Log.i(TAG, "Device list is null, so stopping scanning");
            stopScan();
        }
        else if(sDeviceList.size() == 0) {
            Log.i(TAG, "Device list is empty, so stopping scanning");
            stopScan();
        }
        else {
            Log.i(TAG, "Device list has entries, so starting scanning");
            // Not sure if we should stop before starting?
            //stopScan();
            startScan();
        }
    }


    /**
     * Check whether the device is actually one we are interested in.
     *
     * TODO - think about how to maintain the right values in sDeviceList
     *  e.g. when a user logs out. Could two different users be using the same app,
     *  so we need a different list of each user? Or in that case would we have to
     *  combine the devices from each user?
     *
     * @param device
     * @return true if it is one of our devices
     */
    private Boolean isInOurList(BluetoothDevice device) {
        Boolean foundMatchingDevice = false;

        if (sDeviceList != null) {
            for (Map.Entry<String, String> entry : sDeviceList.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                Log.d(TAG, "key: '" + key + "', value: '" + value + "'");
                if (key.equalsIgnoreCase(device.getAddress())) {
                    foundMatchingDevice = true;
                    break;
                }
            }
        }
        // return a list of "our" devices.
        return foundMatchingDevice;
    }

    /**
     * Called when the scanner finds one of our devices. Broadcasts an Intent to LockScannerService
     * which will then connect to it. Also can broadcast to MainActivity
     * @param device
     * @param scanRecord
     * @param rssi
     */
    private void notifyDeviceFound(BluetoothDevice device, ScanRecord scanRecord, int rssi) {

        Intent intent = new Intent(DEVICE_FOUND);
        intent.putExtra(BLE_DEVICE, device);
        intent.putExtra(BLE_DEVICE_NAME, scanRecord.getDeviceName());
        intent.putExtra(BLE_DEVICE_RSSI, rssi);
        intent.putExtra(BLE_SERVICE_UUID, mUuid);
        // NOTE: often device.getName() is null!
        LocalBroadcastManager.getInstance(LockScannerApplication.getAppContext()).sendBroadcast(intent);
    }

    /**
     * Inform the MainActivity what we are doing in terms of scanning
     */
    private static void notifyScanState() {
        Intent intent = new Intent(SCANNING_STATE);
        intent.putExtra(EXTRA_SCANNING_STATE, mScanning);
        intent.putExtra(EXTRA_SCANNING_UUID, mUuid);
        LocalBroadcastManager.getInstance(LockScannerApplication.getAppContext()).sendBroadcast(intent);
    }

    // getter and setter for mProcessingDevice
    public static Boolean getProcessingDevice() {
        return mProcessingDevice;
    }

    // called by LockScannerService

    /**
     * Inhibits processing of spurious ACTION_SCANNER_FOUND_DEVICE messages once we have decided to connect to a device.
     * Called by LockScannerService twice:
     *  - when it gets a DEVICE_FOUND message from BleReceiver
     *  - when disconnected from the  device.
     * @param state
     */
    public static void setProcessingDevice(Boolean state) {
        mProcessingDevice = state;

        // if scanning has been temporarily suspended while we process one device, restart scanning
        if (state == false) {
            startScan();
        }
    }

}

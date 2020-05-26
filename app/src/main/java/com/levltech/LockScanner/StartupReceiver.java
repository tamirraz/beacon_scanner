package com.levltech.LockScanner;


/**
 * Listens for the ACTION_BOOT_COMPLETED message and starts the LockScannerService.
 *
 * Note that for Android 8 (26) you cannot start a service while the app is in background. See here:
 * https://stackoverflow.com/questions/51587863/bad-notification-for-start-foreground-invalid-channel-for-service-notification
 * https://stackoverflow.com/questions/46445265/android-8-0-java-lang-illegalstateexception-not-allowed-to-start-service-inten
 */

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;


public class StartupReceiver extends BroadcastReceiver {

    private static final String TAG = "APP_StartupRx";

    @Override
    public void onReceive(Context sContext, Intent intent) {

        switch (intent.getAction()) {

            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_BOOT_COMPLETED: {
                Log.i(TAG, "Boot completed (" + intent.getAction() + ")");

                // If not already started, start it.
                // This will read preferences from SharedPreferences and initialise BleReceiver so it scans properly

                if (sContext != null) {
                    Intent startServiceIntent = new Intent(sContext, LockScannerService.class);
                    startServiceIntent.putExtra(LockScannerService.STARTUP_SOURCE, "Started from reboot");

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            sContext.startForegroundService(startServiceIntent);
                            Log.d(TAG, "Starting foreground service");
                        } else {
                            sContext.startService(startServiceIntent);
                            Log.d(TAG, "Starting background service");
                        }
                    }
                    catch (Exception e) {
                        // I got this when using startService() with Android 26:
                        // Not allowed to start service Intent: app is in background
                        // https://stackoverflow.com/questions/52013545/android-9-0-not-allowed-to-start-service-app-is-in-background-after-onresume
                        Log.e(TAG, "ERROR " + e.getMessage());
                    }
                }
                else {
                    Log.e(TAG, "ERROR: Context is null");
                }

                break;
            }
        }
    }


}


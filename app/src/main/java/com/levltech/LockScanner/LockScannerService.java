package com.levltech.LockScanner;

/**
 * See here on services: https://developer.android.com/guide/components/services
 *
 * Note that for Android 8 (26) you cannot start a service while the app is in background. See here:
 * https://stackoverflow.com/questions/51587863/bad-notification-for-start-foreground-invalid-channel-for-service-notification
 * https://stackoverflow.com/questions/46445265/android-8-0-java-lang-illegalstateexception-not-allowed-to-start-service-inten
 */

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

import no.nordicsemi.android.ble.BleManagerCallbacks;
import no.nordicsemi.android.log.ILogSession;
import no.nordicsemi.android.log.Logger;

public class LockScannerService extends Service implements LockManagerCallbacks {

    private static final String TAG = "APP_LockScannerService";
    public static final String STARTUP_SOURCE = "com.levltech.LockScanner.STARTUP_SOURCE";
    private static final String NOTIFICATION_CHANNEL_ID = "com.levltech.LockScanner.startup_CHANNEL";
    private static final String NOTIFICATIONS_CHANNEL_NAME = "com.levltech.LockScanner.startup_NOTIFICATIONS_CHANNEL_NAME";


    // These are topics of broadcast messages to be sent to BleProfileServiceReadyActivity

    public static final String BROADCAST_CONNECTION_STATE = "com.levltech.LockScanner.BROADCAST_CONNECTION_STATE";
    public static final String BROADCAST_BOND_STATE = "com.levltech.LockScanner.BROADCAST_BOND_STATE";
    public static final String BROADCAST_ERROR = "com.levltech.LockScanner.BROADCAST_ERROR";

    public static final String EXTRA_ERROR_MESSAGE = "com.levltech.LockScanner.EXTRA_ERROR_MESSAGE";
    public static final String EXTRA_ERROR_CODE = "com.levltech.LockScanner.EXTRA_ERROR_CODE";

    public static final String EXTRA_DEVICE = "com.levltech.LockScanner.EXTRA_DEVICE";
    public static final String EXTRA_CONNECTION_STATE = "com.levltech.LockScanner.EXTRA_CONNECTION_STATE";
    public static final String EXTRA_BOND_STATE = "com.levltech.LockScanner.EXTRA_BOND_STATE";
    public static final String EXTRA_SERVICE_PRIMARY = "com.levltech.LockScanner.EXTRA_SERVICE_PRIMARY";
    public static final String EXTRA_SERVICE_SECONDARY = "com.levltech.LockScanner.EXTRA_SERVICE_SECONDARY";

    public static final int STATE_LINK_LOSS = -1;
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_SERVICES_DISCOVERED = 3;
    public static final int STATE_DEVICE_READY = 4;
    public static final int STATE_DISCONNECTING = 5;


    private final IBinder mBinder = new StartServiceBinder();

    private static String startupReason;

    private String mThing;

    private static BluetoothDevice sBleDevice;

    private static String sDeviceName;

    private static String sUuid;

    private LockManager mManager;

    // This is the BroadcastReceiver in the BleReceiver class
    BroadcastReceiver mBleBroadcastReceiver;

    private ILogSession mLogSession;

    private static int seqNum = 0;

    public LockScannerService() {
        Log.d(TAG, "in Constructor");
    }

    /**
     * "If the startService(intent) method is called and the service is not yet running,
     * the service object is created and the onCreate() method of the service is called."
     * <p>
     * see this for foreground services:
     * https://stackoverflow.com/questions/51587863/bad-notification-for-start-foreground-invalid-channel-for-service-notification
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // O is 26
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "onCreate() - registering BleReceiver (Version O or greater)");
            startNotificationChannel();
        }
        else {
            Log.d(TAG, "onCreate() - registering BleReceiver (less than Version O)");
        }
        startForegroundWithNotification();

        // This sets up the receiver that listens for BLE devices advertising
        // NOTE: actions must be registered in AndroidManifest.xml
        mBleBroadcastReceiver = new BleReceiver();    // My Receiver class, extends BroadcastReceiver
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBleBroadcastReceiver, intentFilter);      // Now .ACTION_STATE_CHANGED events arrive on onReceive()

        // This receives messages for purposes of connecting to the device and getting its data
        LocalBroadcastManager.getInstance(this).registerReceiver(mLocalBroadcastReceiver, makeIntentFilter());
    }

    /**
     *
     * Starting in Android 8.0 (API level 26), all notifications must be assigned to a channel.
     *  See: https://developer.android.com/training/notify-user/channels
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private void startNotificationChannel() {
        NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATIONS_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        //notificationChannel.setLightColor(R.color.colorBlue);
        notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(notificationChannel);
    }

    /**
     * set up an activity to be run if the user clicks on the notification
     * See https://developer.android.com/training/notify-user/navigation
     */
    private void startForegroundWithNotification() {

        // Create an Intent for the activity you want to start
        Intent resultIntent = new Intent(this, ResultActivity.class);
        // Create the TaskStackBuilder and add the intent, which inflates the back stack
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntentWithParentStack(resultIntent);
        // Get the PendingIntent containing the entire back stack
        PendingIntent pendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(true)
                //.setSmallIcon(R.drawable.cross_notification)
                .setContentTitle("Lock Scanner app is running in background")
                .setContentText("(Detects events even when the device is asleep)")
                .setContentInfo("Info about Lock Scanner app is provided here.")
                .setContentIntent(pendingIntent)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        startForeground(3, notification);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "onDestroy()");

        unregisterReceiver(mBleBroadcastReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mLocalBroadcastReceiver);
    }

    @Override
    /**
     * Called by the OS: - can be called several times
     * "Called by the system every time a client explicitly starts the service by calling startService()"
     * "Once the service is started, the onStartCommand(intent) method in the service is called.
     *  It passes in the Intent object from the startService(intent) call."
     */
    public int onStartCommand(final Intent intent, final int flags, final int startId) {

        if (intent == null) {
            // I seems to have had this, for some reason... doc says this:
            // "This may be null if the service is being restarted after
            //   its process has gone away, and it had previously returned anything
            //    except {@link #START_STICKY_COMPATIBILITY}.
            Log.e(TAG, "onStartCommand() WITH NULL INTENT! id = " + startId + ", received " + startupReason);
        }
        else {

            startupReason = intent.getStringExtra(STARTUP_SOURCE);
            Log.d(TAG, "onStartCommand() id = " + startId + ", startup reason: '" + startupReason + "'");
        }

        // Ask the BleReceiver to restore its state
        // TODO - should this be called only after a reboot?
        BleReceiver.initialiseAfterReboot();

        //return START_REDELIVER_INTENT;
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class StartServiceBinder extends Binder {

        public LockScannerService getService() {
            return LockScannerService.this;
        }

        // unused API placeholders:
        public void setSomething(String thing) {
            mThing = thing;
            Log.d(TAG, "Set: " + thing);
        }

        public String getSomething() {
            return mThing;
        }
    }

    public String getStartupReason() {
        return startupReason;
    }


    /*********************************************************************************************
     *
     * Broadcast receiver
     *
     *********************************************************************************************/

    // Used by the activity to identify the broadcast items it wants to subscribe to
    private static IntentFilter makeIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BleReceiver.DEVICE_FOUND);    // BleReceiver sends this when the scan has found a device

        return intentFilter;
    }

    /**
     * This processes incoming broadcast messages
     */
    private final BroadcastReceiver mLocalBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();

            switch (action) {

                // Message from BleReceiver when a matching device is found.
                // We connect to the device.
                case BleReceiver.DEVICE_FOUND: {
                    // here when the BleReceiver detects that a device has been found
                    sBleDevice = intent.getParcelableExtra(BleReceiver.BLE_DEVICE);
                    sDeviceName = intent.getStringExtra(BleReceiver.BLE_DEVICE_NAME);
                    sUuid = intent.getStringExtra(BleReceiver.BLE_SERVICE_UUID);
                    int rssi = intent.getIntExtra(BleReceiver.BLE_DEVICE_RSSI, 0);
                    Log.d(TAG, "Device found: " + sDeviceName + ", " + sBleDevice.getAddress() + " " + rssi + "dBm");

                    BleReceiver.setProcessingDevice(true);  // inhibit further device found events

                    // start a logger session
                    mLogSession = Logger.newSession(getApplicationContext(), getString(R.string.app_name), sBleDevice.getAddress(), sDeviceName);
                    Logger.d(mLogSession, "Started log session");

                    connectToDevice(sBleDevice, sDeviceName, sUuid);
                    break;
                }

            }

        }
    };

    /*********************************************************************************************
     *
     * BLUETOOTH MANAGER STUFF
     *
     *********************************************************************************************/

    private void connectToDevice(BluetoothDevice bleDevice, String deviceName, String uuid) {

        Log.v(TAG, "Creating LockManager now");

        mManager = new LockManager(this, bleDevice, deviceName, uuid);
        mManager.setGattCallbacks(this);
        mManager.setLogger(mLogSession);

        Log.d(TAG, "Attempting to connect to " + bleDevice.getAddress());

        if (mManager != null) {
            // This is where we request a connection to the BLE device.
            mManager.connect(bleDevice).enqueue();
        } else {
            Log.e(TAG, "ERROR: mManager is null!");
        }
    }


    /********************************************************************************************
     *
     * BleManagerCallbacks - implementation of this Interface
     *
     ********************************************************************************************/

    /**
     * These are a series of Intents that are broadcast from BleProfileService
     * to BleProfileServiceReadyActivity. They then propagate through to MainActivity
     *
     * @param device
     */
    @Override
    public void onDeviceConnecting(final BluetoothDevice device) {
        Log.d(TAG, "  ... BleManager reports Device Connecting");
        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
        broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_CONNECTING);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onDeviceConnected(final BluetoothDevice device) {
        Log.d(TAG, "  ... BleManager reports Device Connected");
        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
        broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_CONNECTED);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onDeviceDisconnecting(final BluetoothDevice device) {
        // Notify user about changing the state to DISCONNECTING
        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
        broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_DISCONNECTING);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onDeviceDisconnected(final BluetoothDevice device) {
        // Note 1: Do not use the device argument here unless you change calling onDeviceDisconnected from the binder above

        // Note 2: if BleManager#shouldAutoConnect() for this device returned true, this callback will be
        // invoked ONLY when user requested disconnection (using Disconnect button). If the device
        // disconnects due to a link loss, the onLinkLossOccurred(BluetoothDevice) method will be called instead.
        Log.d(TAG, "  ... BleManager reports Device Disconnected");

        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
        broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_DISCONNECTED);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);

        // now tell the BleReceiver it can resume scanning
        BleReceiver.setProcessingDevice(false);
    }

    @Override
    public void onLinkLossOccurred(final BluetoothDevice device) {
        Log.d(TAG, "  ... BleManager reports Link Loss Occurred");

        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
        broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_LINK_LOSS);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onServicesDiscovered(final BluetoothDevice device, final boolean optionalServicesFound) {
        Log.d(TAG, "  ... BleManager reports Services Discovered");

        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
         broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_SERVICES_DISCOVERED);

        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);
        broadcast.putExtra(EXTRA_SERVICE_PRIMARY, true);
        broadcast.putExtra(EXTRA_SERVICE_SECONDARY, optionalServicesFound);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onDeviceReady(final BluetoothDevice device) {
        Log.d(TAG, "  ... BleManager reports Device Ready");
        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
        broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_DEVICE_READY);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onBondingRequired(final BluetoothDevice device) {
        Log.d(TAG, "  ... BleManager reports Bonding Required");

        final Intent broadcast = new Intent(BROADCAST_BOND_STATE);
        broadcast.putExtra(EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onBonded(final BluetoothDevice device) {

        Log.d(TAG, "  ... BleManager reports Bonded");
        final Intent broadcast = new Intent(BROADCAST_BOND_STATE);
         broadcast.putExtra(EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onBondingFailed(final BluetoothDevice device) {

        Log.d(TAG, "  ... BleManager reports Bonding Failed");
        final Intent broadcast = new Intent(BROADCAST_BOND_STATE);
        broadcast.putExtra(EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void onError(final BluetoothDevice device, final String message, final int errorCode) {

        final Intent broadcast = new Intent(BROADCAST_ERROR);
        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);
        broadcast.putExtra(EXTRA_ERROR_MESSAGE, message);
        broadcast.putExtra(EXTRA_ERROR_CODE, errorCode);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);

        Log.e(TAG, "  ... BleManager reports Error: " +  message + " (" + errorCode + ")");
    }

    @Override
    public void onDeviceNotSupported(final BluetoothDevice device) {

        Log.d(TAG, "  ... BleManager reports Device Not Supported");

        final Intent broadcast = new Intent(BROADCAST_CONNECTION_STATE);
        broadcast.putExtra(EXTRA_CONNECTION_STATE, STATE_SERVICES_DISCOVERED);

        broadcast.putExtra(EXTRA_DEVICE, sBleDevice);
        broadcast.putExtra(EXTRA_SERVICE_PRIMARY, false);
        broadcast.putExtra(EXTRA_SERVICE_SECONDARY, false);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);

        // no need for disconnecting, it will be disconnected by the manager automatically
    }

    /**
     * This allows the user etc to disconnect the device and so delete the service
     */
    public void disconnectAndStopService() {

        final int state = mManager.getConnectionState();
        if (state == BluetoothGatt.STATE_DISCONNECTED || state == BluetoothGatt.STATE_DISCONNECTING) {
            Log.d(TAG, "Attempting to disconnect from " + sBleDevice.getName());
            mManager.close();
            onDeviceDisconnected(sBleDevice);
            return;
        }
        else {

            Log.d(TAG, "  ... asking BleManager to disconnect ");
            // This ends up in BleManager.internalDisconnect() which calls the OS mBluetoothGatt.disconnect();
            // NOTE: if the BLE device appears not to disconnect it might be because nRF Connect is also listening: see
            // https://devzone.nordicsemi.com/f/nordic-q-a/38422/android-disconnect-and-close-do-not-disconnect
            mManager.disconnect().enqueue();
        }
    }

}

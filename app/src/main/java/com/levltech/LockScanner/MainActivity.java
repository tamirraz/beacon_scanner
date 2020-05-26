package com.levltech.LockScanner;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import no.nordicsemi.android.support.v18.scanner.ScannerService;

public class MainActivity extends AppCompatActivity {


    private static final String TAG = "APP_MainActivity";

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    protected static final int REQUEST_ENABLE_BT = 2;


    private static Context sContext;

    // determines which service to listen for
    private String mSelectedUUID = LockManager.UUID_HEART_RATE_SERVICE.toString();

    // Report status
    TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mStatus = findViewById(R.id.app_status);

        sContext = getApplicationContext();

        // If not already started, start .
        // This will read preferences from SharedPreferences and initialise BleReceiver so it scans properly
        startScannerService("Activity created");

        // This receives messages for purposes of painting the screen etc
        LocalBroadcastManager.getInstance(this).registerReceiver(mLocalBroadcastReceiver, makeIntentFilter());

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean scanState = toggleScanning();
                if (scanState) {
                    Snackbar.make(view, "Scanning is now on", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                    mStatus.setText("Scanning for " + mSelectedUUID);
                }
                else {
                    Snackbar.make(view, "Scanning is now off", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                    mStatus.setText("Not scanning");
                }
            }
        });
    }
    /**
     * Called when screen is rotated!
     */
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy()");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mLocalBroadcastReceiver);

        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart()");

        /* Check access to BLE is granted */
        verifyBluetooth();
        requestBluetoothPermission();
        // TODO: check location on

        // If the service has not been started before, the following lines will not start it.
        // However, if it's running, the Activity will bind to it and notified via mServiceConnection.

        final Intent service = new Intent(this, ScannerService.class);
        // We pass 0 as a flag so the service will not be created if not exists.
        Log.d(TAG, "Binding service");
        bindService(service, mServiceConnection, 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop()");
        unbindService(mServiceConnection);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume()");
        BleReceiver.requestScanningState(); // see what we are doing and paint the screen
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /*********************************************************************************************
     *
     * Service Connection: allows bi-directional communication with the service.
     *
     *********************************************************************************************/

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        // Interface for monitoring the state of an application service

        @Override
        // We get here when the StartService service has connected to this activity.
        public void onServiceConnected(final ComponentName name, final IBinder binder) {
            LockScannerService.StartServiceBinder b = (LockScannerService.StartServiceBinder) binder;
            LockScannerService mService = b.getService();

            // Now
            String reason = mService.getStartupReason();
            String msg = "Activity connected to the service. Reason: '" + reason +"'";
            Log.d(TAG, msg);
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            // Note: this method is called only when the service is killed by the system,
            // not when it stops itself or is stopped by the activity.
            // It will be called only when there is critically low memory, in practice never
            // when the activity is in foreground.
            String msg = "Activity disconnected from the service";
            Log.d(TAG, msg);

        }
    };


    /**
     * Starts the LockScannerService
     * Called from onCreate() in case it has not been auto-started already.
     * Can also be called from StartupReceiover following a reboot.
     */
    private void startScannerService(String reason) {
        Intent serviceIntent = new Intent(getApplicationContext(), LockScannerService.class);
        serviceIntent.putExtra(LockScannerService.STARTUP_SOURCE, reason);
        getApplicationContext().startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, 0);
    }

    /**
     * Stops the Startup Service
     * Could be called from a menu for testing
     */
    private void stopStartupService() {
        Intent serviceIntent = new Intent(getApplicationContext(), LockScannerService.class);
        serviceIntent.putExtra(LockScannerService.STARTUP_SOURCE, "From Menu");
        getApplicationContext().stopService(serviceIntent);
    }

    /**
     * Toggles the scanning
     * @return true if we are now scanning
     */
    private boolean toggleScanning() {

        if (BleReceiver.isScanning()) {
            // now turn it off
            Log.d(TAG, "Request for scanning to stop");

            // call stop scanning in the BleReceiver
            BleReceiver.stopScanning();
            return false;
        }
        else {
            // now turn it on
            Log.d(TAG, "Request for scanning to start");

            // call start scanning in the BleReceiver
            BleReceiver.startScanning(sContext, mSelectedUUID );
            return true;
        }
    }



    /*********************************************************************************************
     *
     * BLUETOOTH PERMISSIONS
     *
     *********************************************************************************************/

    /**
     * Request permission to use Bluetooth:
     *     pops up a dialog explaining things then gets the OS to request permission
     * Note - if this is not done the the scanner will fail, often silently!
     */
    public void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android M Permission check
            Log.d(TAG, "Checking Bluetooth permissions");
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED) {

                Log.d(TAG, "  Permission is not granted");
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Permission Required for BLE Device Detection");
                builder.setMessage("Bluetooth operation requires 'location' access.\nPlease grant this so the app can detect BLE devices");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                    @TargetApi(23)
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        // User replies then there is a call to onRequestPermissionsResult() below
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                PERMISSION_REQUEST_COARSE_LOCATION);
                    }

                });

                builder.show();
            }
            else {
                Log.d(TAG, "  Permission is granted");
            }
        }
    }

    /* This is called when the user responds to the request permission dialog */
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {

        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Coarse location permission granted");
                }
                else {
                    Log.d(TAG, "Coarse location permission refused.");
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("This App will not Work as Intended");
                    builder.setMessage("Android requires you to grant access to device\'s location in order to scan for Bluetooth devices.");
                    //builder.setIcon(R.drawable.cross);
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                        @Override
                        public void onDismiss(DialogInterface dialog) { }
                    });
                    builder.show();
                }
                return;
            }
        }
    }


    /**
     * Check BLE is enabled, and pop up a dialog if not
     */
    public void verifyBluetooth() {

        try {
            if (!checkAvailability()) {
                Log.d(TAG, "BLE not available.");
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Bluetooth is Not Enabled");
                builder.setMessage("Bluetooth must be on for this app to work.\nPlease allow the app to turn on Bluetooth when asked, or the app will be terminated.");
                //builder.setIcon(R.drawable.cross);
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        // Ask for permission to turn on BLE
                        askToTurnOnBLE();
                    }
                });
                builder.show();
            }
            else {
                Log.d(TAG, "BLE is available.");
            }
        }
        catch (RuntimeException e) {
            Log.d(TAG, "BLE not supported.");
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Bluetooth Low Energy not available");
            builder.setMessage("Sorry, this device does not support Bluetooth Low Energy.");
            //builder.setIcon(R.drawable.cross);
            builder.setPositiveButton(android.R.string.ok, null);
            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                @Override
                public void onDismiss(DialogInterface dialog) {
                    // kill the app
                    finish();
                    System.exit(0);
                }

            });
            builder.show();
        }
    }

    /**
     * Check if Bluetooth LE is supported by this Android device, and if so, make sure it is enabled.
     *
     * @return false if it is supported and not enabled
     * @throws RuntimeException if Bluetooth LE is not supported.  (Note: The Android emulator will do this)
     */
    @TargetApi(18)
    public boolean checkAvailability() throws RuntimeException {
        if (!isBleAvailable()) {
            throw new RuntimeException("Bluetooth LE not supported by this device");
        }
        return ((BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().isEnabled();
    }

    /**
     * Checks if the device supports BLE
     * @return true if it does
     */
    private boolean isBleAvailable() {
        boolean available = false;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Log.w(TAG, "Bluetooth LE not supported prior to API 18.");
        } else if (!this.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "This device does not support bluetooth LE.");
        } else {
            available = true;
        }
        return available;
    }
    /**
     * Asks user's permission to turn on the Bluetooth
     */
    protected void askToTurnOnBLE() {
        final Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
    }

    @Override
    /**
     * Processes the user's response when asked to turn on Bluetooth
     */
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            super.onActivityResult(requestCode, resultCode, data);

            if (requestCode == REQUEST_ENABLE_BT  && resultCode  == RESULT_OK) {

                Log.d(TAG, "DEBUG: permission granted");
            }
            else {
                Log.d(TAG, "DEBUG: permission denied");
                // kill the app
                finish();
                System.exit(0);
            }
        } catch (Exception ex) {
            Toast.makeText(this, ex.toString(),
                    Toast.LENGTH_SHORT).show();
        }

    }

    /*********************************************************************************************
     *
     * Broadcast receiver
     *
     *********************************************************************************************/

    // Used by the activity to identify the broadcast items it wants to subscribe to
    private static IntentFilter makeIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BleReceiver.DEVICE_FOUND);	// BleReceiver sends this when teh scan has found a device
        intentFilter.addAction(BleReceiver.SCANNING_STATE);
        intentFilter.addAction(LockScannerService.BROADCAST_CONNECTION_STATE);
        intentFilter.addAction(LockScannerService.BROADCAST_BOND_STATE);
        intentFilter.addAction(LockScannerService.BROADCAST_ERROR);
        return intentFilter;
    }

    /**
     * This processes incoming broadcast messages
     */
    private BroadcastReceiver mLocalBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();

            switch (action) {

                // can listen to other broadcasts here also... Currently used to report status of connection etc.

                // Message from BleReceiver to inform us of the scanning state
                case BleReceiver.SCANNING_STATE: {
                    Boolean isScanning = intent.getBooleanExtra(BleReceiver.EXTRA_SCANNING_STATE, false);
                    String uuid = intent.getStringExtra(BleReceiver.EXTRA_SCANNING_UUID);
                    String msg = "Not Scanning";
                    if (isScanning) {
                        msg = "Scanning for " + uuid;
                    }

                    Log.v(TAG, msg);
                    mStatus.setText(msg);
                    break;
                }

                // Message from BleReceiver when a matching device is found.
                // Here we just paint the screen, but LockScannerService should takes steps to connect
                case BleReceiver.DEVICE_FOUND: {
                    // here when the BleReceiver detects that a device has been found
                    BluetoothDevice sBleDevice = intent.getParcelableExtra(BleReceiver.BLE_DEVICE);
                    String sDeviceName = intent.getStringExtra(BleReceiver.BLE_DEVICE_NAME);
                    int rssi = intent.getIntExtra(BleReceiver.BLE_DEVICE_RSSI, 0);
                    String msg = "Found " + sDeviceName + " (" + sBleDevice.getAddress() + ") " + rssi + "dBm";
                    Log.v(TAG, msg);
                    mStatus.setText(msg);
                    break;
                }

                case LockScannerService.BROADCAST_CONNECTION_STATE: {
                    int state = intent.getIntExtra(LockScannerService.EXTRA_CONNECTION_STATE, LockScannerService.STATE_DISCONNECTED);
                    BluetoothDevice device = intent.getParcelableExtra(LockScannerService.EXTRA_DEVICE);

                    String msg;
                    if (device != null) {
                        msg = device.getName() + " state: ";
                    }
                    else {
                        msg = "state: ";
                    }

                    switch (state) {
                        case LockScannerService.STATE_LINK_LOSS:
                            msg += "link loss";
                            break;

                        case LockScannerService.STATE_DISCONNECTED:
                            msg += "disconnected";
                            break;

                        case LockScannerService.STATE_CONNECTED:
                            msg += "connected";
                            break;

                        case LockScannerService.STATE_CONNECTING:
                            msg += "connecting";
                            break;

                        case LockScannerService.STATE_SERVICES_DISCOVERED:
                            if (intent.getBooleanExtra(LockScannerService.EXTRA_SERVICE_PRIMARY, false)) {
                                msg += "services discovered";
                            }
                            else {
                                msg += "services not supported";
                            }
                            break;

                        case LockScannerService.STATE_DEVICE_READY:
                            msg += "ready";
                            break;

                        case LockScannerService.STATE_DISCONNECTING:
                            msg += "disconnecting";
                            break;
                    }
                    mStatus.setText(msg);
                    break;
                }

                case LockScannerService.BROADCAST_BOND_STATE: {
                    int state = intent.getIntExtra(LockScannerService.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    BluetoothDevice device = intent.getParcelableExtra(LockScannerService.EXTRA_DEVICE);
                    String msg;
                    if (device != null) {
                        msg = device.getName() + " bond state: ";
                    }
                    else {
                        msg = "Bond state: ";
                    }

                    switch (state) {
                        case BluetoothDevice.BOND_NONE:
                            msg += "not bonded";
                            break;
                        case BluetoothDevice.BOND_BONDING:
                            msg += "bonding";
                            break;
                        case BluetoothDevice.BOND_BONDED:
                            msg += "bonded";
                            break;

                    }
                    mStatus.setText(msg);
                    break;
                }

                case LockScannerService.BROADCAST_ERROR: {
                    BluetoothDevice device = intent.getParcelableExtra(LockScannerService.EXTRA_DEVICE);
                    int errorCode = intent.getIntExtra(LockScannerService.EXTRA_ERROR_CODE, 0);
                    String errorMessage = intent.getStringExtra(LockScannerService.EXTRA_ERROR_MESSAGE);

                    if (device != null) {
                        String msg = device.getName() + " reports error " + errorMessage + " (" + errorCode +")";
                        mStatus.setText(msg);
                    }
                    else {
                        // error...
                        Log.e(TAG, "BROADCAST_ERROR with no device, " + errorMessage);
                    }
                    break;
                }
            }
        }
    };
}

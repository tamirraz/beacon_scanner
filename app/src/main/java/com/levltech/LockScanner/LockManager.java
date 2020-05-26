/*
 * Copyright (c) 2015, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.levltech.LockScanner;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.Calendar;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.log.ILogSession;
import no.nordicsemi.android.log.LogContract;
import no.nordicsemi.android.log.Logger;

/**
 * LockManager class performs BluetoothGatt operations for connection, service discovery, enabling
 * indication and reading characteristics. All operations required to connect to device and reading
 * parameters are performed here.
 * LockActivity implements LockManagerCallbacks in order to receive callbacks of BluetoothGatt operations.
 */

public class LockManager extends BleManager<LockManagerCallbacks> {

	private final String TAG = "APP_LockManager";

	/** Heart Rate (Our Lock) service UUID */
	public final static UUID UUID_HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
	/** Heart Rate (Our Lock) Measurement characteristic UUID */
	private static final UUID UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");

	private BluetoothGattCharacteristic mLockCharacteristic;

	// Supports nRF Logger
	private ILogSession mLogSession;
	private UUID mServiceUuid;

	private BluetoothDevice mBleDevice;
	private String mDeviceName;


	public LockManager(final Context context, BluetoothDevice bleDevice, String deviceName, String uuid) {
		super(context);
		mBleDevice = bleDevice;
		mDeviceName = deviceName;
		mServiceUuid = UUID.fromString(uuid);
		Log.d(TAG, "LockManger created for " + mDeviceName);
	}

	@NonNull
	@Override
	protected BleManagerGattCallback getGattCallback() {
		return mGattCallback;
	}

	/**
	 * BluetoothGatt callbacks for connection/disconnection, service discovery,
	 * receiving indication, etc..
	 */
	private final BleManagerGattCallback mGattCallback = new BleManagerGattCallback() {
		@Override
		protected void initialize() {
			Log.d(TAG, "Initialising BLE device.");
			super.initialize();
			enableIndications(mLockCharacteristic).enqueue();
		}

		@Override
		protected boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt) {
			final BluetoothGattService service = gatt.getService(UUID_HEART_RATE_SERVICE);
			if (service != null) {
				mLockCharacteristic = service.getCharacteristic(UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT);
			}
			return mLockCharacteristic != null;
		}

		@Override
		protected void onDeviceDisconnected() {
			//super.onDeviceDisconnected();
			mLockCharacteristic = null;
			Log.v(TAG, "in onDeviceDisconnected()");
		}
	};


	/********************************************************************************************
	 *
	 * nRF Logger
	 *
	 ********************************************************************************************/


	/**
	 * Called by the service that creates the SavedLockManager object.
	 * @param session - an ILogSession previously created by the service
	 */

	public void setLogger(ILogSession session) {
		mLogSession = session;
	}

	/**
	 * Called by this class and also by BleManager
	 * @param priority - one of these:
	 * DEBUG = 0;
	 * VERBOSE = 1;
	 * INFO = 5;
	 * APPLICATION = 10;
	 * WARNING = 15;
	 * ERROR = 20;
	 *
	 * @param msg - the message to write
	 */

	@Override
	public void log(int priority, String msg) {
		Logger.log(mLogSession, LogContract.Log.Level.fromPriority(priority), msg);
	}
}

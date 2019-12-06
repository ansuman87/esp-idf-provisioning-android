// Copyright 2018 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.espressif.provision.transport;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.espressif.AppConstants;
import com.espressif.ui.activities.ProvisionActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Bluetooth implementation of the Transport protocol.
 */
public class BLETransportLatest extends BLETransport {

    private static final String TAG = "Espressif::" + BLETransportLatest.class.getSimpleName();

    private Activity context;
    private BluetoothDevice currentDevice;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattService service;
    private BLETransportListener transportListener;
    private ResponseListener currentResponseListener;
    private Semaphore transportToken;
    private ExecutorService dispatcherThreadPool;
    private HashMap<String, String> uuidMap = new HashMap<>();
    private ArrayList<String> charUuidList = new ArrayList<>();
    private String serviceUuid;
    private boolean isReadingDescriptors = false;

    /***
     * Create BLETransport implementation
     *
     * @param context
     * @param transportListener listener implementation which will receive resulting events
     */
    public BLETransportLatest(Activity context, BLETransportListener transportListener) {

        this.context = context;
        this.transportToken = new Semaphore(1);
        this.dispatcherThreadPool = Executors.newSingleThreadExecutor();
        this.transportListener = transportListener;
    }

    /***
     * BLE implementation of Transport protocol
     * @param data data to be sent
     * @param listener listener implementation which receives events when response is received.
     */
    @Override
    public void sendSessionData(byte[] data, ResponseListener listener) {

        if (uuidMap.containsKey(AppConstants.HANDLER_PROV_SESSION)) {

            BluetoothGattCharacteristic sessionCharacteristic = service.getCharacteristic(UUID.fromString(uuidMap.get(AppConstants.HANDLER_PROV_SESSION)));

            if (sessionCharacteristic == null) {
                sessionCharacteristic = service.getCharacteristic(UUID.fromString("0000ff51-0000-1000-8000-00805f9b34fb"));
            }

            if (sessionCharacteristic != null) {
                try {
                    this.transportToken.acquire();
                    sessionCharacteristic.setValue(data);
                    bluetoothGatt.writeCharacteristic(sessionCharacteristic);
                    currentResponseListener = listener;
                } catch (Exception e) {
                    e.printStackTrace();
                    listener.onFailure(e);
                    this.transportToken.release();
                }
            } else {
                Log.e(TAG, "Session Characteristic is not available.");
            }
        }
    }

    /***
     * BLE implementation of Transport protocol
     * @param path path of the config endpoint.
     * @param data config data to be sent
     * @param listener listener implementation which receives events when response is received.
     */
    @Override
    public void sendConfigData(String path, byte[] data, ResponseListener listener) {

        if (uuidMap.containsKey(path)) {

            BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(uuidMap.get(path)));

            if (characteristic == null) {
                characteristic = service.getCharacteristic(UUID.fromString("0000ff52-0000-1000-8000-00805f9b34fb"));
            }

            if (characteristic != null) {
                try {
                    this.transportToken.acquire();
                    characteristic.setValue(data);
                    bluetoothGatt.writeCharacteristic(characteristic);
                    currentResponseListener = listener;
                } catch (Exception e) {
                    e.printStackTrace();
                    listener.onFailure(e);
                    this.transportToken.release();
                }
            } else {
                Log.e(TAG, "Characteristic is not available for given path.");
            }
        }
    }

    /**
     * Connect to a BLE peripheral device.
     *
     * @param bluetoothDevice    The peripheral device
     * @param primaryServiceUuid Primary Service UUID
     */
    public void connect(BluetoothDevice bluetoothDevice, UUID primaryServiceUuid) {
        this.currentDevice = bluetoothDevice;
        this.serviceUuid = primaryServiceUuid.toString();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = this.currentDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = this.currentDevice.connectGatt(context, false, gattCallback);
        }
    }

    /**
     * Disconnect from the current connected peripheral
     */
    public void disconnect() {
        if (this.bluetoothGatt != null) {
            this.bluetoothGatt.disconnect();
            bluetoothGatt = null;
        }
    }

    private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            super.onConnectionStateChange(gatt, status, newState);
            Log.d(TAG, "onConnectionStateChange, New state : " + newState + ", Status : " + status);

            if (status == BluetoothGatt.GATT_FAILURE) {
                if (transportListener != null) {
                    transportListener.onFailure(new Exception("GATT failure in connection"));
                }
                return;
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.e(TAG, "Connected to GATT server.");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG, "Disconnected from GATT server.");
                if (transportListener != null) {
                    transportListener.onPeripheralDisconnected(new Exception("Bluetooth device disconnected"));
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Status not success");
                return;
            }

            service = gatt.getService(UUID.fromString(serviceUuid));

            if (service == null) {
                Log.e(TAG, "Service not found!");
                return;
            }

            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {

                if (characteristic == null) {
                    Log.e(TAG, "Tx characteristic not found!");
                    return;
                }

                String uuid = characteristic.getUuid().toString();
                Log.d(TAG, "Characteristic UUID : " + uuid);
                charUuidList.add(uuid);

                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }

            readNextDescriptor();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Read Descriptor : " + bluetoothGatt.readDescriptor(descriptor));
            } else {
                Log.e(TAG, "Fail to read descriptor");
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {

            Log.d(TAG, "DescriptorRead, : Status " + status + " Data : " + new String(descriptor.getValue(), StandardCharsets.UTF_8));

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to read descriptor");
                return;
            }

            byte[] data = descriptor.getValue();

            String value = new String(data, StandardCharsets.UTF_8);
            uuidMap.put(value, descriptor.getCharacteristic().getUuid().toString());
            Log.d(TAG, "Value : " + value + " for UUID : " + descriptor.getCharacteristic().getUuid().toString());

            if (isReadingDescriptors) {

                readNextDescriptor();

            } else {

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(uuidMap.get(AppConstants.HANDLER_PROTO_VER)));

                if (characteristic != null) {
                    // Write V0.2 to read characteristic.
                    characteristic.setValue("V0.2");
                    bluetoothGatt.writeCharacteristic(characteristic);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Supported MTU = " + mtu);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "onCharacteristicChanged");
            super.onCharacteristicChanged(gatt, characteristic);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         final BluetoothGattCharacteristic characteristic,
                                         int status) {

            Log.d(TAG, "onCharacteristicRead, status " + status + " UUID : " + characteristic.getUuid().toString());
            super.onCharacteristicRead(gatt, characteristic, status);

            if (uuidMap.get((AppConstants.HANDLER_PROTO_VER)).equals(characteristic.getUuid().toString())) {

                String data = new String(characteristic.getValue(), StandardCharsets.UTF_8);
                Log.d(TAG, "Value : " + data);

                try {
                    JSONObject jsonObject = new JSONObject(data);
                    JSONObject provInfo = jsonObject.getJSONObject("prov");

                    String versionInfo = provInfo.getString("ver");
                    Log.d(TAG, "Device Version : " + versionInfo);

                    JSONArray capabilities = provInfo.getJSONArray("cap");
                    deviceCapabilities = new ArrayList<>();

                    for (int i = 0; i < capabilities.length(); i++) {
                        String cap = capabilities.getString(i);
                        deviceCapabilities.add(cap);
                    }
                    Log.d(TAG, "Capabilities : " + deviceCapabilities);

                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Capabilities JSON not available.");
                    Log.e(TAG, "Value : " + data);

                    // If received data is "SUCCESS" then consider that WiFi auth mode will be available in WiFi Scan list.
                    if (!TextUtils.isEmpty(data) && data.equalsIgnoreCase("SUCCESS")) {
                        Log.e(TAG, "Received version V0.2");
                        ProvisionActivity.isWiFiAuthModeAvailable = true;
                    }
                }

                if (transportListener != null) {

                    if (uuidMap.containsKey(AppConstants.HANDLER_PROV_SESSION)) {
                        // This is where provisionSession will get called.
                        Log.d(TAG, "Session characteristic not NULL " + currentDevice.getAddress());
                        transportListener.onPeripheralConfigured(currentDevice);
                    } else {
                        Log.d(TAG, "Session characteristic is NULL");
                        transportListener.onPeripheralNotConfigured(currentDevice);
                    }
                }
            }

            if (currentResponseListener != null) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    /*
                     * Need to dispatch this on another thread since the caller
                     * might decide to enqueue another send operation on success
                     * of the first.
                     */
                    final ResponseListener responseListener = currentResponseListener;
                    dispatcherThreadPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            byte[] charValue = characteristic.getValue();
                            responseListener.onSuccess(charValue);
                        }
                    });
                    currentResponseListener = null;
                } else {

                    currentResponseListener.onFailure(new Exception("Read from BLE failed"));
                }
            }
            transportToken.release();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

            Log.d(TAG, "onCharacteristicWrite, status : " + status);
            Log.d(TAG, "UUID : " + characteristic.getUuid().toString());
//            super.onCharacteristicWrite(gatt, characteristic, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                bluetoothGatt.readCharacteristic(characteristic);
            } else {
                if (currentResponseListener != null) {
                    currentResponseListener.onFailure(new Exception("Write to BLE failed"));
                }
                transportToken.release();
            }
        }
    };

    private void readNextDescriptor() {

        boolean found = false;

        for (int i = 0; i < charUuidList.size(); i++) {

            String uuid = charUuidList.get(i);

            if (!uuidMap.containsValue(uuid)) {

                // Read descriptor
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(uuid));
                if (characteristic == null) {
                    Log.e(TAG, "Tx characteristic not found!");
                    return;
                }

                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {

                    Log.d(TAG, "Descriptor : " + descriptor.getUuid().toString());
                    Log.d(TAG, "Des read : " + bluetoothGatt.readDescriptor(descriptor));
                }
                found = true;
                break;
            }
        }

        if (found) {
            isReadingDescriptors = true;
        } else {

            isReadingDescriptors = false;

            BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(uuidMap.get(AppConstants.HANDLER_PROTO_VER)));

            if (characteristic != null) {
                characteristic.setValue("V0.2");
                bluetoothGatt.writeCharacteristic(characteristic);
            }
        }
    }
}

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
package com.espressif.ui.activities;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.Vibrator;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.espressif.AppConstants;
import com.espressif.ble_scanner.BLEScanListener;
import com.espressif.ble_scanner.BLEScanner;
import com.espressif.provision.Provision;
import com.espressif.provision.R;
import com.espressif.provision.security.Security;
import com.espressif.provision.security.Security0;
import com.espressif.provision.security.Security1;
import com.espressif.provision.session.Session;
import com.espressif.provision.transport.BLETransport;
import com.espressif.provision.transport.BLETransportLatest;
import com.espressif.provision.transport.BLETransportLegacy;
import com.espressif.provision.transport.ResponseListener;
import com.espressif.ui.adapters.BleDeviceListAdapter;
import com.espressif.ui.models.EspBtDevice;
import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import avs.Avsconfig;

public class BLEProvisionLanding extends AppCompatActivity {

    private static final String TAG = "Espressif::" + BLEProvisionLanding.class.getSimpleName();

    // Request codes
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_FINE_LOCATION = 2;

    // Time out
    private static final long SCAN_TIMEOUT = 5000;
    private static final long DEVICE_CONNECT_TIMEOUT = 20000;

    public static boolean isBleWorkDone = false;

    private ImageView btnScan;
    private ProgressBar progressBar;
    private ListView listView;

    private Session session;
    private Security security;
    public static BLETransport bleTransport;
    private BLETransport.BLETransportListener transportListener;

    private String deviceName;
    private String deviceNamePrefix;
    private String pop;
    private boolean isDeviceConnected;
    private boolean isConnecting = false;

    private BleDeviceListAdapter adapter;
    private BluetoothAdapter bleAdapter;
    private BLEScanner bleScanner;
    private ArrayList<BluetoothDevice> deviceList;
    private HashMap<BluetoothDevice, EspBtDevice> bluetoothDevices;
    private SharedPreferences sharedPreferences;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bleprovision_landing);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.title_activity_connect_device);
        setSupportActionBar(toolbar);

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.error_ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bleAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (bleAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        isConnecting = false;
        isDeviceConnected = false;
        handler = new Handler();
        bluetoothDevices = new HashMap<>();
        Collection<BluetoothDevice> keySet = bluetoothDevices.keySet();
        deviceList = new ArrayList<>(keySet);
        sharedPreferences = getSharedPreferences(AppConstants.ESP_PREFERENCES, Context.MODE_PRIVATE);
        deviceNamePrefix = sharedPreferences.getString(AppConstants.KEY_BLE_DEVICE_NAME_PREFIX, "");

        initViews();
        bleScanner = new BLEScanner(this, SCAN_TIMEOUT, bleScanListener);

        transportListener = new BLETransport.BLETransportListener() {

            @Override
            public void onPeripheralConfigured(BluetoothDevice device) {
                handler.removeCallbacks(disconnectDeviceTask);
                bleDeviceConfigured(true);
            }

            @Override
            public void onPeripheralNotConfigured(BluetoothDevice device) {
                btnScan.setVisibility(View.VISIBLE);
                progressBar.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
                bleDeviceConfigured(false);
            }

            @Override
            public void onPeripheralDisconnected(Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(BLEProvisionLanding.this,
                                "Bluetooth device disconnected.",
                                Toast.LENGTH_LONG)
                                .show();
                    }
                });
            }

            @Override
            public void onFailure(final Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(BLEProvisionLanding.this,
                                "Bluetooth connection failed : " + e.getMessage(),
                                Toast.LENGTH_LONG)
                                .show();
                    }
                });
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(TAG, "ON RESUME");

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!bleAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            if (!isDeviceConnected && !isConnecting) {
                startScan();
            }

            if (isBleWorkDone) {
                if (bleTransport != null) {
                    bleTransport.disconnect();
                }
                btnScan.setVisibility(View.VISIBLE);
                startScan();
            }
        }
    }

    @Override
    public void onBackPressed() {
        isBleWorkDone = true;
        if (bleTransport != null) {
            bleTransport.disconnect();
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        Log.d(TAG, "onActivityResult, requestCode : " + requestCode + ", resultCode : " + resultCode);

        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }

        if (requestCode == Provision.REQUEST_PROVISIONING_CODE && resultCode == RESULT_OK) {
            setResult(resultCode);
            finish();
        } else if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            startScan();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {

        Log.d(TAG, "onRequestPermissionsResult : requestCode : " + requestCode);

        switch (requestCode) {

            case REQUEST_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startScan();
                } else if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    finish();
                }
            }
            break;
        }
    }

    private void initViews() {

        btnScan = findViewById(R.id.btn_refresh);
        listView = findViewById(R.id.ble_devices_list);
        progressBar = findViewById(R.id.ble_landing_progress_indicator);

        adapter = new BleDeviceListAdapter(this, R.layout.item_ble_scan, deviceList);

        // Assign adapter to ListView
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(onDeviceCLickListener);

        btnScan.setOnClickListener(btnScanClickListener);
    }

    private boolean hasPermissions() {

        if (bleAdapter == null || !bleAdapter.isEnabled()) {

            requestBluetoothEnable();
            return false;

        } else if (!hasLocationPermissions()) {

            requestLocationPermission();
            return false;
        }
        return true;
    }

    private void requestBluetoothEnable() {

        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        Log.d(TAG, "Requested user enables Bluetooth. Try starting the scan again.");
    }

    private boolean hasLocationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
        }
    }

    private void startScan() {

        if (!hasPermissions() || bleScanner.isScanning()) {
            return;
        }

        if (bleTransport != null) {
            bleTransport.disconnect();
        }

        deviceList.clear();
        bluetoothDevices.clear();
        bleScanner.startScan();
        updateProgressAndScanBtn();
    }

    private void stopScan() {

        bleScanner.stopScan();
        updateProgressAndScanBtn();

        if (deviceList.size() <= 0) {

            Toast.makeText(BLEProvisionLanding.this,
                    "No Bluetooth devices found!",
                    Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void bleDeviceConfigured(final Boolean isConfigured) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                if (isConfigured) {

                    isConnecting = false;
                    isDeviceConnected = true;
                    final String securityVersion = getIntent().getStringExtra(Provision.CONFIG_SECURITY_KEY);

                    if (bleTransport.deviceCapabilities != null) {

                        if (!bleTransport.deviceCapabilities.contains("no_pop") && securityVersion.equals(Provision.CONFIG_SECURITY_SECURITY1)) {

                            goToProofOfPossessionActivity();
                        }
                    } else {
                        pop = getResources().getString(R.string.proof_of_possesion);
                        initSession();
                    }

                } else {
                    Toast.makeText(BLEProvisionLanding.this,
                            "Bluetooth device could not be configured. Please try another device.",
                            Toast.LENGTH_LONG)
                            .show();
                }
            }
        });
    }

    /**
     * This method will update UI (Scan button enable / disable and progressbar visibility)
     */
    private void updateProgressAndScanBtn() {

        if (bleScanner.isScanning()) {

            btnScan.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);

        } else {

            btnScan.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }
    }

    private void getStatus() {

        Avsconfig.CmdSignInStatus configRequest = Avsconfig.CmdSignInStatus.newBuilder()
                .setDummy(123)
                .build();
        Avsconfig.AVSConfigMsgType msgType = Avsconfig.AVSConfigMsgType.TypeCmdSignInStatus;
        Avsconfig.AVSConfigPayload payload = Avsconfig.AVSConfigPayload.newBuilder()
                .setMsg(msgType)
                .setCmdSigninStatus(configRequest)
                .build();

        byte[] message = security.encrypt(payload.toByteArray());

        bleTransport.sendConfigData(AppConstants.HANDLER_AVS_CONFIG, message, new ResponseListener() {

            @Override
            public void onSuccess(byte[] returnData) {

                Avsconfig.AVSConfigStatus deviceStatus = processSignInStatusResponse(returnData);
                Log.d(TAG, "SignIn Status Received : " + deviceStatus);

                if (deviceStatus.equals(Avsconfig.AVSConfigStatus.SignedIn)) {

                    goToWiFiScanActivity();

                } else {
                    goToLoginActivity();
                }
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Error in getting status");
                e.printStackTrace();
            }
        });
    }

    private Avsconfig.AVSConfigStatus processSignInStatusResponse(byte[] responseData) {

        Avsconfig.AVSConfigStatus status = Avsconfig.AVSConfigStatus.InvalidState;
        byte[] decryptedData = this.security.decrypt(responseData);

        try {

            Avsconfig.AVSConfigPayload payload = Avsconfig.AVSConfigPayload.parseFrom(decryptedData);
            Avsconfig.RespSignInStatus signInStatus = payload.getRespSigninStatus();
            status = signInStatus.getStatus();
            Log.d(TAG, "SignIn Status message " + status.getNumber() + status.toString());

        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return status;
    }

    private void goToProofOfPossessionActivity() {

        Intent alexaProvisioningIntent = new Intent(getApplicationContext(), ProofOfPossessionActivity.class);
        alexaProvisioningIntent.putExtras(getIntent());
        alexaProvisioningIntent.putExtra(LoginWithAmazon.KEY_IS_PROVISIONING, true);
        alexaProvisioningIntent.putExtra(LoginWithAmazon.KEY_DEVICE_NAME, deviceName);
        startActivity(alexaProvisioningIntent);
    }

    private void goToLoginActivity() {

        Intent alexaProvisioningIntent = new Intent(getApplicationContext(), LoginWithAmazon.class);
        alexaProvisioningIntent.putExtras(getIntent());
        alexaProvisioningIntent.putExtra(LoginWithAmazon.KEY_DEVICE_NAME, deviceName);
        alexaProvisioningIntent.putExtra(LoginWithAmazon.KEY_IS_PROVISIONING, true);
        alexaProvisioningIntent.putExtra(AppConstants.KEY_PROOF_OF_POSSESSION, pop);
        startActivity(alexaProvisioningIntent);
    }

    private void goToWiFiScanActivity() {

        Intent launchProvisionInstructions = new Intent(getApplicationContext(), WiFiScanActivity.class);
        launchProvisionInstructions.putExtras(getIntent());
        launchProvisionInstructions.putExtra(LoginWithAmazon.KEY_IS_PROVISIONING, true);
        launchProvisionInstructions.putExtra(AppConstants.KEY_PROOF_OF_POSSESSION, pop);
        startActivityForResult(launchProvisionInstructions, Provision.REQUEST_PROVISIONING_CODE);
    }

    private void initSession() {

        Log.e(TAG, "POP : " + pop);
        final String securityVersion = getIntent().getStringExtra(Provision.CONFIG_SECURITY_KEY);

        if (securityVersion.equals(Provision.CONFIG_SECURITY_SECURITY1)) {
            security = new Security1(pop);
        } else {
            security = new Security0();
        }

        session = new Session(BLEProvisionLanding.bleTransport, security);

        session.sessionListener = new Session.SessionListener() {

            @Override
            public void OnSessionEstablished() {
                Log.d(TAG, "Session established");
                getStatus();
            }

            @Override
            public void OnSessionEstablishFailed(Exception e) {
                Log.e(TAG, "Session failed");
                e.printStackTrace();
            }
        };
        session.init(null);
    }

    private void alertForDeviceConnectionFailed() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);

        builder.setTitle("Error!");
        builder.setMessage(R.string.error_device_connection_failed);

        // Set up the buttons
        builder.setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

                finish();
            }
        });

        builder.show();
    }

    private BLEScanListener bleScanListener = new BLEScanListener() {

        @Override
        public void onPeripheralFound(BluetoothDevice device, ScanResult scanResult) {

            ScanRecord scanRecord = scanResult.getScanRecord();
            byte[] scanData = scanRecord.getBytes();
            Log.e(TAG, "====== onPeripheralFound ===== " + device.getName());
            List<ParcelUuid> uuidList = scanRecord.getServiceUuids();

            if (uuidList != null) {
                for (int i = 0; i < uuidList.size(); i++) {
                    Log.d(TAG, "UUID " + i + " : " + uuidList.get(i));
                }
            } else {
                Log.d(TAG, "UUID list is null");
            }
            boolean isNewProvMethod = parseAdvertisementPacket(scanData);

            boolean deviceExists = false;
            String serviceUuid = "";

            if (scanResult.getScanRecord().getServiceUuids() != null && scanResult.getScanRecord().getServiceUuids().size() > 0) {
                serviceUuid = scanResult.getScanRecord().getServiceUuids().get(0).toString();
            }
            Log.d(TAG, "Add service UUID : " + serviceUuid);

            if (bluetoothDevices.containsKey(device)) {
                deviceExists = true;
            }
            Log.d(TAG, "Device exist : " + deviceExists);
            Log.e(TAG, "Prefix : " + deviceNamePrefix);

            if (!deviceExists && device.getName().startsWith(deviceNamePrefix)) {

                listView.setVisibility(View.VISIBLE);
                EspBtDevice espBtDevice = new EspBtDevice(device, serviceUuid, isNewProvMethod);
                bluetoothDevices.put(device, espBtDevice);
                deviceList.add(device);
                adapter.notifyDataSetChanged();
            }
        }

        @Override
        public void scanCompleted() {
            updateProgressAndScanBtn();
        }

        @Override
        public void onFailure(Exception e) {
            e.printStackTrace();
        }
    };

    private View.OnClickListener btnScanClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {

            Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            vib.vibrate(HapticFeedbackConstants.VIRTUAL_KEY);
            bluetoothDevices.clear();
            adapter.clear();
            startScan();
        }
    };

    private AdapterView.OnItemClickListener onDeviceCLickListener = new AdapterView.OnItemClickListener() {

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {

            isConnecting = true;
            isDeviceConnected = false;
            btnScan.setVisibility(View.GONE);
            listView.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
            BluetoothDevice device = adapter.getItem(position);
            String uuid = bluetoothDevices.get(device).getServiceUuid();
            deviceName = device.getName();
            Log.d(TAG, "=================== Connect to device : " + deviceName + " UUID : " + uuid);

            if (bluetoothDevices.get(device).isLatestProvMethod()) {
                bleTransport = new BLETransportLatest(BLEProvisionLanding.this, transportListener);
            } else {
                bleTransport = new BLETransportLegacy(BLEProvisionLanding.this, transportListener);
            }

            if (bleTransport != null) {
                bleTransport.connect(device, UUID.fromString(uuid));
            }
            handler.postDelayed(disconnectDeviceTask, DEVICE_CONNECT_TIMEOUT);
        }
    };

    private Runnable disconnectDeviceTask = new Runnable() {

        @Override
        public void run() {
            Log.e(TAG, "Disconnect device");

            if (bleTransport != null) {
                bleTransport.disconnect();
            }
            progressBar.setVisibility(View.GONE);
            alertForDeviceConnectionFailed();
        }
    };

    private boolean parseAdvertisementPacket(final byte[] scanRecord) {

        byte[] advertisedData = Arrays.copyOf(scanRecord, scanRecord.length);
        boolean isLatestFw = false;

        int offset = 0;
        while (offset < (advertisedData.length - 2)) {
            int len = advertisedData[offset++];
            if (len == 0)
                break;

            int type = advertisedData[offset++];
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    Log.e(TAG, "This is Old firmware device");
                    while (len > 1) {
                        int uuid16 = advertisedData[offset++] & 0xFF;
                        uuid16 |= (advertisedData[offset++] << 8);
                        len -= 2;
                    }
                    break;

                case 0x06:// Partial list of 128-bit UUIDs
                case 0x07:// Complete list of 128-bit UUIDs
                    // Loop through the advertised 128-bit UUID's.
                    Log.e(TAG, "This is New firmware device");
                    isLatestFw = true;
                    while (len >= 16) {
                        try {
                            // Wrap the advertised bits and order them.
                            ByteBuffer buffer = ByteBuffer.wrap(advertisedData,
                                    offset++, 16).order(ByteOrder.LITTLE_ENDIAN);
                            long mostSignificantBit = buffer.getLong();
                            long leastSignificantBit = buffer.getLong();
                        } catch (IndexOutOfBoundsException e) {
                            // Defensive programming.
                            Log.e(TAG, e.toString());
                            continue;
                        } finally {
                            // Move the offset to read the next uuid.
                            offset += 15;
                            len -= 16;
                        }
                    }
                    break;
                case 0xFF:  // Manufacturer Specific Data
                    Log.d(TAG, "Manufacturer Specific Data size:" + len + " bytes");
                    break;
                default:
                    offset += (len - 1);
                    break;
            }
        }
        return isLatestFw;
    }
}
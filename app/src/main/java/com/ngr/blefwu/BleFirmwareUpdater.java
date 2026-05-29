package com.ngr.blefwu;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class BleFirmwareUpdater {
    static final String DEFAULT_TARGET = "BRC_70D6";
    private static final int SMP_MIN_PAYLOAD_SIZE = 32;
    private static final UUID FWU_WRITE_UUID = UUID.fromString("3CE06519-BC5C-432C-AD3A-8801B224EE2C");
    private static final UUID CAPABILITY_READ_UUID = UUID.fromString("3CE06519-BC5C-432C-AD3A-8801B224EE2D");
    private static final UUID SMP_UUID = UUID.fromString("DA2E7828-FBCE-4E01-AE9E-261174997C48");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int BLE_MTU_SIZE = 498;
    private static final int CONNECT_TIMEOUT_SECONDS = 60;
    private static final int RESOLVE_TIMEOUT_SECONDS = 30;
    private static final String FWU_READY_FOR_INFO = "readyForInfo";
    private static final String FWU_UPLOAD_SUCCESS = "uploadSuccess";

    private final Context context;
    private final Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic fwuWrite;
    private BluetoothGattCharacteristic capabilityRead;
    private BluetoothGattCharacteristic smp;
    private CountDownLatch connectLatch;
    private CountDownLatch mtuLatch;
    private CountDownLatch discoverLatch;
    private CountDownLatch readLatch;
    private CountDownLatch writeLatch;
    private CountDownLatch descriptorLatch;
    private byte[] lastRead;
    private int lastStatus;
    private final Queue<byte[]> smpNotifications = new ArrayDeque<>();

    BleFirmwareUpdater(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void run(String target, Uri mainFirmware, Uri radioFirmware, SmpOptions smpOptions, boolean forceNewPairing) throws Exception {
        smpOptions.validate();
        byte[] main = readAll(mainFirmware);
        byte[] radio = readAll(radioFirmware);

        BluetoothDevice device = findDevice(target == null || target.trim().isEmpty() ? DEFAULT_TARGET : target.trim());
        ensureBonded(device, forceNewPairing);
        log("Connecting to " + device.getAddress() + " ...");
        connect(device);
        requestMtu();
        discoverCharacteristics();

        int[] slots = getSlots();
        log("Main FW slot: " + slots[0] + ", radio FW slot: " + slots[1]);
        writeJson(fwuWrite, "{\"fwuMode\":true}");

        waitForState(FWU_READY_FOR_INFO, 15);
        uploadImage("main", main, slots[0], smpOptions);
        waitForState(FWU_READY_FOR_INFO, 0);
        uploadImage("radio", radio, slots[1] + 2, smpOptions);
        waitForState(FWU_UPLOAD_SUCCESS, 0);
        log("Firmware update completed");
    }

    void close() {
        if (gatt != null) {
            if (hasConnectPermission()) {
                gatt.disconnect();
                gatt.close();
            }
            gatt = null;
        }
    }

    private BluetoothDevice findDevice(String target) throws Exception {
        BluetoothAdapter adapter = bluetoothAdapter();
        if (BluetoothAdapter.checkBluetoothAddress(target)) {
            return adapter.getRemoteDevice(target);
        }
        log("Resolving address for BLE target " + target + " ...");
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            throw new IllegalStateException("Bluetooth LE scanner is unavailable");
        }
        CountDownLatch scanLatch = new CountDownLatch(1);
        final BluetoothDevice[] found = new BluetoothDevice[1];
        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
                if (name == null && hasConnectPermission()) {
                    name = device.getName();
                }
                if (target.equals(name)) {
                    found[0] = device;
                    scanLatch.countDown();
                }
            }
        };
        scanner.startScan(callback);
        boolean ok = scanLatch.await(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        scanner.stopScan(callback);
        if (!ok || found[0] == null) {
            throw new IllegalStateException("Unable to resolve target " + target);
        }
        log("Resolved address: " + found[0].getAddress());
        return found[0];
    }

    private void connect(BluetoothDevice device) throws Exception {
        connectLatch = new CountDownLatch(1);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (!connectLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || lastStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException("BLE connection failed, status=" + lastStatus);
        }
    }

    private static String bondStateName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE:    return "NONE";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            case BluetoothDevice.BOND_BONDED:  return "BONDED";
            default:                           return "UNKNOWN(" + state + ")";
        }
    }

    private void ensureBonded(BluetoothDevice device, boolean forceNewPairing) throws Exception {
        log("Bond state: " + bondStateName(device.getBondState())
                + ", forceNewPairing=" + forceNewPairing);
        if (device.getBondState() == BluetoothDevice.BOND_BONDED && !forceNewPairing) {
            log("Device already bonded");
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            log("Removing existing Android bond before pairing ...");
            removeBond(device);
            waitForBondState(device, BluetoothDevice.BOND_NONE, 20);
            log("Bond removed");
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDING) {
            log("Bond already in progress, waiting ...");
            waitForBondState(device, BluetoothDevice.BOND_BONDED, 60);
            log("Pairing completed");
            return;
        }
        attemptBond(device);
    }

    private static final int BOND_ATTEMPTS = 3;
    private static final int BOND_RETRY_DELAY_MS = 2000;

    private void attemptBond(BluetoothDevice device) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= BOND_ATTEMPTS; attempt++) {
            try {
                log("Calling createBond(), attempt " + attempt + "/" + BOND_ATTEMPTS + " ...");
                boolean started = device.createBond();
                log("createBond() returned: " + started);
                if (!started) {
                    throw new IllegalStateException("createBond() returned false");
                }
                waitForBondState(device, BluetoothDevice.BOND_BONDED, 60);
                log("Pairing completed");
                return;
            } catch (Exception e) {
                lastException = e;
                log("Pairing attempt " + attempt + "/" + BOND_ATTEMPTS
                        + " failed: " + e.getMessage());
                if (attempt < BOND_ATTEMPTS) {
                    log("Waiting " + BOND_RETRY_DELAY_MS + "ms before retry ...");
                    Thread.sleep(BOND_RETRY_DELAY_MS);
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        log("Pairing completed (detected on retry check)");
                        return;
                    }
                }
            }
        }
        throw lastException;
    }

    private void removeBond(BluetoothDevice device) throws Exception {
        Method removeBond = device.getClass().getMethod("removeBond");
        boolean started = (Boolean) removeBond.invoke(device);
        if (!started) {
            throw new IllegalStateException("Android bond removal could not be started");
        }
    }

    private void waitForBondState(BluetoothDevice device, int expectedState, int timeoutSeconds) throws Exception {
        if (device.getBondState() == expectedState) {
            return;
        }
        CountDownLatch bondLatch = new CountDownLatch(1);
        final int[] bondState = new int[]{device.getBondState()};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                BluetoothDevice changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (changed == null || !device.getAddress().equals(changed.getAddress())) {
                    return;
                }
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                int next = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                log("Bond state change: " + bondStateName(prev) + " -> " + bondStateName(next));
                bondState[0] = next;
                if (bondState[0] == expectedState || bondState[0] == BluetoothDevice.BOND_NONE) {
                    bondLatch.countDown();
                }
            }
        };
        registerBondReceiver(receiver);
        try {
            boolean reached = bondLatch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!reached) {
                throw new IllegalStateException(
                        "Timed out waiting for bond state " + bondStateName(expectedState)
                        + " (stuck at " + bondStateName(bondState[0]) + ")");
            }
            if (bondState[0] != expectedState) {
                throw new IllegalStateException(
                        "Bond state " + bondStateName(bondState[0])
                        + ", expected " + bondStateName(expectedState));
            }
        } finally {
            context.unregisterReceiver(receiver);
        }
    }

    private void registerBondReceiver(BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private void requestMtu() throws Exception {
        mtuLatch = new CountDownLatch(1);
        if (!gatt.requestMtu(BLE_MTU_SIZE)) {
            throw new IllegalStateException("MTU request could not be started");
        }
        if (!mtuLatch.await(10, TimeUnit.SECONDS) || lastStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException("MTU request failed, status=" + lastStatus);
        }
    }

    private void discoverCharacteristics() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            resetCharacteristics();
            log("Discovering GATT services, attempt " + attempt + "/3 ...");
            discoverServicesOnce();
            log("Discovered " + gatt.getServices().size() + " GATT service(s)");
            findRequiredCharacteristics();
            if (fwuWrite != null && capabilityRead != null && smp != null) {
                enableSmpNotifications();
                return;
            }
            logMissingCharacteristics();
            logDiscoveredGatt();
            if (attempt < 3) {
                log("Refreshing Android GATT cache before retry ...");
                refreshGattCache();
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("Required FWU characteristics were not found");
    }

    private void discoverServicesOnce() throws Exception {
        discoverLatch = new CountDownLatch(1);
        if (!gatt.discoverServices()) {
            throw new IllegalStateException("Service discovery could not be started");
        }
        if (!discoverLatch.await(20, TimeUnit.SECONDS) || lastStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException("Service discovery failed, status=" + lastStatus);
        }
    }

    private void resetCharacteristics() {
        fwuWrite = null;
        capabilityRead = null;
        smp = null;
    }

    private void findRequiredCharacteristics() {
        for (BluetoothGattService service : gatt.getServices()) {
            if (fwuWrite == null) fwuWrite = service.getCharacteristic(FWU_WRITE_UUID);
            if (capabilityRead == null) capabilityRead = service.getCharacteristic(CAPABILITY_READ_UUID);
            if (smp == null) smp = service.getCharacteristic(SMP_UUID);
        }
    }

    private void logMissingCharacteristics() {
        if (fwuWrite == null) {
            log("Missing FWU write characteristic: " + FWU_WRITE_UUID);
        }
        if (capabilityRead == null) {
            log("Missing capability read characteristic: " + CAPABILITY_READ_UUID);
        }
        if (smp == null) {
            log("Missing SMP characteristic: " + SMP_UUID);
        }
    }

    private void logDiscoveredGatt() {
        for (BluetoothGattService service : gatt.getServices()) {
            log("Service " + service.getUuid());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                log("  Characteristic " + characteristic.getUuid()
                        + " props=0x" + Integer.toHexString(characteristic.getProperties()));
            }
        }
    }

    private void refreshGattCache() {
        try {
            Method refresh = gatt.getClass().getMethod("refresh");
            boolean refreshed = (Boolean) refresh.invoke(gatt);
            log("GATT cache refresh result: " + refreshed);
        } catch (Exception e) {
            log("GATT cache refresh unavailable: " + e.getMessage());
        }
    }

    private int[] getSlots() throws Exception {
        JSONObject data = new JSONObject(new String(readValue(capabilityRead), StandardCharsets.UTF_8));
        int mainSlot = data.isNull("mainFreeSlot") ? -1 : data.getInt("mainFreeSlot");
        int radioSlot = data.isNull("radioFreeSlot") ? -1 : data.getInt("radioFreeSlot");
        if (mainSlot < 0 || radioSlot < 0) {
            throw new IllegalStateException("Unable to determine slots from capability characteristic");
        }
        return new int[]{mainSlot, radioSlot};
    }

    private void waitForState(String state, int initialDelaySeconds) throws Exception {
        log("Waiting for state " + state + " ...");
        if (initialDelaySeconds > 0) {
            Thread.sleep(initialDelaySeconds * 1000L);
        }
        while (true) {
            JSONObject data = new JSONObject(new String(readValue(capabilityRead), StandardCharsets.UTF_8));
            String mainState = data.getJSONObject("main").getString("state");
            String radioState = data.getJSONObject("radio").getString("state");
            if (state.equals(mainState) || state.equals(radioState)) {
                log("State reached: " + state);
                return;
            }
            Thread.sleep(5000);
        }
    }

    private void uploadImage(String label, byte[] image, int slot, SmpOptions options) throws Exception {
        log("Uploading " + label + " image, " + image.length + " bytes ...");
        log("SMP options: window=" + options.windowSize
                + ", retries=" + options.retries
                + ", payload=" + options.payloadSize
                + ", writeWithoutResponse=" + options.writeWithoutResponse);
        int totalSent = 0;
        int sequence = 0;
        long started = System.currentTimeMillis();
        while (totalSent < image.length) {
            List<PendingRequest> pending = new ArrayList<>();
            int windowOffset = totalSent;
            int windowSize = totalSent == 0 ? 1 : options.windowSize;
            for (int i = 0; i < windowSize && windowOffset < image.length; i++) {
                int chunkSize = Math.min(options.payloadSize, image.length - windowOffset);
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(image, windowOffset, chunk, 0, chunkSize);
                byte[] request = SmpCodec.imageUploadRequest(sequence, slot, windowOffset, chunk, image.length);
                pending.add(new PendingRequest(sequence, windowOffset, chunkSize, request));
                windowOffset += chunkSize;
                sequence = (sequence + 1) & 0xFF;
            }
            totalSent = sendSmpWindowWithRetry(pending, options);
            listener.onProgress(label, totalSent, image.length);
        }
        long seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000);
        log("Uploaded " + label + " image in " + seconds + "s");
    }

    private int sendSmpWindowWithRetry(List<PendingRequest> pending, SmpOptions options) throws Exception {
        for (int attempt = 0; attempt <= options.retries; attempt++) {
            drainSmpNotifications();
            try {
                for (PendingRequest request : pending) {
                    writeBytes(smp, request.packet, options.writeWithoutResponse);
                }
                Map<Integer, Integer> responses = collectSmpResponses(pending);
                int nextOffset = pending.get(pending.size() - 1).offset + pending.get(pending.size() - 1).chunkSize;
                for (PendingRequest request : pending) {
                    Integer responseOffset = responses.get(request.sequence);
                    if (responseOffset == null || responseOffset < 0) {
                        responseOffset = request.offset + request.chunkSize;
                    }
                    int expected = request.offset + request.chunkSize;
                    if (responseOffset != expected) {
                        log("SMP resync at seq=" + request.sequence + ", next offset=" + responseOffset);
                        return responseOffset;
                    }
                }
                return nextOffset;
            } catch (SmpCodec.SmpResponseException e) {
                throw e;
            } catch (Exception e) {
                if (attempt == options.retries) {
                    throw e;
                }
                log("SMP window retry " + (attempt + 1) + "/" + options.retries + ": " + e.getMessage());
            }
        }
        throw new IllegalStateException("Unreachable SMP retry state");
    }

    private Map<Integer, Integer> collectSmpResponses(List<PendingRequest> pending) throws Exception {
        Map<Integer, PendingRequest> pendingBySequence = new HashMap<>();
        for (PendingRequest request : pending) {
            pendingBySequence.put(request.sequence, request);
        }
        Map<Integer, Integer> responses = new HashMap<>();
        long deadline = System.currentTimeMillis() + 30000;
        while (responses.size() < pending.size()) {
            byte[] packet = pollSmpNotification(deadline - System.currentTimeMillis());
            SmpCodec.SmpResponse response;
            try {
                response = SmpCodec.parseImageUploadResponse(packet);
            } catch (SmpCodec.SmpResponseException e) {
                log("SMP response raw: " + bytesToHex(packet));
                throw e;
            }
            if (pendingBySequence.containsKey(response.sequence)) {
                responses.put(response.sequence, response.nextOffset);
            }
        }
        return responses;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return hex.toString();
    }

    private byte[] pollSmpNotification(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (smpNotifications) {
            while (smpNotifications.isEmpty()) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    throw new IllegalStateException("Timed out waiting for SMP response");
                }
                smpNotifications.wait(wait);
            }
            return smpNotifications.remove();
        }
    }

    private void drainSmpNotifications() {
        synchronized (smpNotifications) {
            smpNotifications.clear();
        }
    }

    private byte[] readValue(BluetoothGattCharacteristic characteristic) throws Exception {
        readLatch = new CountDownLatch(1);
        lastRead = null;
        if (!gatt.readCharacteristic(characteristic)) {
            throw new IllegalStateException("Read could not be started for " + characteristic.getUuid());
        }
        if (!readLatch.await(30, TimeUnit.SECONDS) || lastStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException("Read failed for " + characteristic.getUuid() + ", status=" + lastStatus);
        }
        return lastRead;
    }

    private void writeJson(BluetoothGattCharacteristic characteristic, String json) throws Exception {
        log("Writing JSON " + json + " to " + characteristic.getUuid());
        writeBytes(characteristic, json.getBytes(StandardCharsets.UTF_8), false);
    }

    private void writeBytes(BluetoothGattCharacteristic characteristic, byte[] value, boolean noResponse) throws Exception {
        writeLatch = new CountDownLatch(1);
        characteristic.setWriteType(noResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            int type = noResponse
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            started = gatt.writeCharacteristic(characteristic, value, type) == BluetoothGatt.GATT_SUCCESS;
        } else {
            characteristic.setValue(value);
            started = gatt.writeCharacteristic(characteristic);
        }
        if (!started) {
            throw new IllegalStateException("Write could not be started for " + characteristic.getUuid());
        }
        if (!writeLatch.await(30, TimeUnit.SECONDS) || lastStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException("Write failed for " + characteristic.getUuid() + ", status=" + lastStatus);
        }
    }

    private void enableSmpNotifications() throws Exception {
        if (!gatt.setCharacteristicNotification(smp, true)) {
            throw new IllegalStateException("Could not enable local SMP notifications");
        }
        BluetoothGattDescriptor cccd = smp.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            throw new IllegalStateException("SMP CCCD descriptor was not found");
        }
        descriptorLatch = new CountDownLatch(1);
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS;
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            started = gatt.writeDescriptor(cccd);
        }
        if (!started || !descriptorLatch.await(10, TimeUnit.SECONDS) || lastStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException("Could not subscribe to SMP notifications, status=" + lastStatus);
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("Cannot open firmware file " + uri);
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
            throw new IllegalStateException("Bluetooth is disabled or unavailable");
        }
        return manager.getAdapter();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void log(String message) {
        listener.onLog(message);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            lastStatus = status;
            if (newState == BluetoothProfile.STATE_CONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connectLatch.countDown();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            lastStatus = status;
            log(String.format(Locale.US, "MTU negotiated: %d", mtu));
            mtuLatch.countDown();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            lastStatus = status;
            discoverLatch.countDown();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            lastStatus = status;
            lastRead = characteristic.getValue();
            readLatch.countDown();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            lastStatus = status;
            lastRead = value;
            readLatch.countDown();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            lastStatus = status;
            writeLatch.countDown();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            lastStatus = status;
            descriptorLatch.countDown();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            onNotification(characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            onNotification(value);
        }

        private void onNotification(byte[] value) {
            synchronized (smpNotifications) {
                smpNotifications.add(value);
                smpNotifications.notifyAll();
            }
        }
    };

    interface Listener {
        void onLog(String message);

        void onProgress(String label, int sent, int total);
    }

    static final class SmpOptions {
        final int windowSize;
        final int payloadSize;
        final int retries;
        final boolean writeWithoutResponse;

        SmpOptions(int windowSize, int payloadSize, int retries, boolean writeWithoutResponse) {
            this.windowSize = windowSize;
            this.payloadSize = payloadSize;
            this.retries = retries;
            this.writeWithoutResponse = writeWithoutResponse;
        }

        void validate() {
            if (windowSize <= 0 || windowSize > 255) {
                throw new IllegalArgumentException("SMP window moet tussen 1 en 255 liggen");
            }
            if (payloadSize < SMP_MIN_PAYLOAD_SIZE || payloadSize > maxSmpPayloadSizeForMtu(BLE_MTU_SIZE)) {
                throw new IllegalArgumentException("SMP payload moet tussen "
                        + SMP_MIN_PAYLOAD_SIZE + " en " + maxSmpPayloadSizeForMtu(BLE_MTU_SIZE)
                        + " liggen voor MTU " + BLE_MTU_SIZE);
            }
            if (retries < 0) {
                throw new IllegalArgumentException("SMP retries moet 0 of hoger zijn");
            }
        }
    }

    static int maxSmpPayloadSizeForMtu(int mtu) {
        int maxWriteValueSize = mtu - 3;
        int payloadSize = 0;
        while (SmpCodec.imageUploadRequest(0, 255, 0, new byte[payloadSize + 1], 0x7FFFFFFF).length <= maxWriteValueSize) {
            payloadSize++;
        }
        return payloadSize;
    }

    private static final class PendingRequest {
        final int sequence;
        final int offset;
        final int chunkSize;
        final byte[] packet;

        PendingRequest(int sequence, int offset, int chunkSize, byte[] packet) {
            this.sequence = sequence;
            this.offset = offset;
            this.chunkSize = chunkSize;
            this.packet = packet;
        }
    }
}

package com.example.tvremote;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.os.Build;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Real Bluetooth HID mouse device. The phone advertises itself as a standard relative mouse. */
final class HidMouse {
    private static final byte[] REPORT_DESC = new byte[] {
        0x05,0x01, 0x09,0x02, (byte)0xA1,0x01,
        0x05,0x09, 0x19,0x01, 0x29,0x03, 0x15,0x00, 0x25,0x01,
        (byte)0x95,0x03, (byte)0x75,0x01, (byte)0x81,0x02,
        (byte)0x95,0x01, (byte)0x75,0x05, (byte)0x81,0x01,
        0x05,0x01, 0x09,0x30, 0x09,0x31, 0x09,0x38,
        (byte)0x15,(byte)0x81, 0x25,0x7F, (byte)0x75,0x08, (byte)0x95,0x03,
        (byte)0x81,0x06, (byte)0xC0
    };

    private final Activity activity;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered;
    private int buttons;

    HidMouse(Activity a) { activity = a; adapter = BluetoothAdapter.getDefaultAdapter(); }

    private boolean permissionOk() {
        if (Build.VERSION.SDK_INT >= 31) {
            return activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && activity.checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    void start() {
        if (Build.VERSION.SDK_INT < 28) { toast("Bluetooth HID mouse ke liye Android 9+ chahiye"); return; }
        if (adapter == null) { toast("Phone me Bluetooth available nahi hai"); return; }
        if (!permissionOk()) {
            activity.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_ADVERTISE"}, 74);
            toast("Bluetooth permission allow karo, phir cursor icon dobara dabao");
            return;
        }
        if (!adapter.isEnabled()) { toast("Bluetooth ON karo"); return; }
        if (hid != null) { connectBestHost(); return; }
        adapter.getProfileProxy(activity, new BluetoothProfile.ServiceListener() {
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hid = (BluetoothHidDevice) proxy;
                    register();
                }
            }
            public void onServiceDisconnected(int profile) { if (profile == BluetoothProfile.HID_DEVICE) { hid = null; registered = false; } }
        }, BluetoothProfile.HID_DEVICE);
    }

    private void register() {
        if (hid == null || registered) { connectBestHost(); return; }
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "TV Remote Mouse", "Real Bluetooth mouse control", "Remote13", (byte)0x00, REPORT_DESC);
        registered = hid.registerApp(sdp, null, null, executor, callback);
        if (registered) connectBestHost();
        else toast("Bluetooth HID register nahi ho saka");
    }

    private void connectBestHost() {
        if (hid == null) return;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n != null && (n.toLowerCase().contains("tv") || n.toLowerCase().contains("google") || n.toLowerCase().contains("android") || n.toLowerCase().contains("chromecast"))) {
                    host = d; break;
                }
            }
            if (host == null && !adapter.getBondedDevices().isEmpty()) host = adapter.getBondedDevices().iterator().next();
            if (host == null) { toast("Pehle TV ko Bluetooth se pair karo, phir cursor icon dabao"); return; }
            hid.connect(host);
        } catch (Throwable t) { toast("Bluetooth mouse connect nahi hua"); }
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice device, boolean registeredNow) {
            registered = registeredNow;
            if (registeredNow) connectBestHost();
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { host = device; toast("Real Bluetooth Mouse connected"); send(0,0,0,0); }
            else if (state == BluetoothProfile.STATE_DISCONNECTED) { buttons = 0; }
        }
        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hid != null) hid.replyReport(device, type, id, new byte[]{0,0,0,0});
        }
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) { }
        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) { }
        @Override public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) { }
        @Override public void onVirtualCableUnplug(BluetoothDevice device) { if (device != null && device.equals(host)) host = null; }
    };

    private void send(int button, int dx, int dy, int wheel) {
        if (hid == null || host == null) return;
        byte[] r = new byte[]{(byte)button, (byte)dx, (byte)dy, (byte)wheel};
        try { hid.sendReport(host, 0, r); } catch (Throwable ignored) { }
    }

    void move(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        send(buttons, clamp(dx), clamp(dy), 0);
    }
    void button(int b, boolean down) {
        if (b < 1 || b > 3) return;
        int mask = 1 << (b - 1);
        if (down) buttons |= mask; else buttons &= ~mask;
        send(buttons, 0, 0, 0);
    }
    void wheel(int delta) { if (delta != 0) send(buttons, 0, 0, clamp(delta)); }

    private static int clamp(int n) { return Math.max(-127, Math.min(127, n)); }
    private void toast(String s) { activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show()); }

    void close() {
        try { if (hid != null && host != null) hid.disconnect(host); } catch (Throwable ignored) { }
        try { if (hid != null && registered) hid.unregisterApp(); } catch (Throwable ignored) { }
        try { if (adapter != null && hid != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (Throwable ignored) { }
        hid = null; host = null; registered = false;
    }
}

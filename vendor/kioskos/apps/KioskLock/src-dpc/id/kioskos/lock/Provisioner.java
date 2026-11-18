package id.kioskos.lock;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Edisi Device Owner (tanpa ROM custom).
 *
 * Aplikasi biasa tidak bisa menjadikan dirinya Device Owner. Caranya:
 *   - pengujian: adb shell dpm set-device-owner id.kioskos.lock/.KioskAdminReceiver
 *   - produksi : QR code saat setup awal HP, atau zero-touch (lihat docs/DPC.md)
 *
 * Setelah menjadi Device Owner, aplikasi memberi dirinya izin yang dibutuhkan (serial/IMEI,
 * pemindaian Wi-Fi) dan menyalakan lokasi, karena hasil scan Wi-Fi butuh lokasi aktif.
 */
public final class Provisioner {
    private static final String TAG = "KioskLock.Provision";
    private static final String[] PERMISSIONS = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
    };

    private Provisioner() {}

    public static boolean ensureDeviceOwner(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.w(TAG, "Belum Device Owner. Jalankan: adb shell dpm set-device-owner "
                    + context.getPackageName() + "/.KioskAdminReceiver");
            return false;
        }
        ComponentName admin = KioskAdminReceiver.component(context);
        for (String p : PERMISSIONS) {
            try {
                dpm.setPermissionGrantState(admin, context.getPackageName(), p,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Exception e) {
                Log.w(TAG, "Gagal memberi izin " + p, e);
            }
        }
        try {
            dpm.setLocationEnabled(admin, true);
        } catch (Exception e) {
            Log.w(TAG, "Gagal menyalakan lokasi", e);
        }
        return true;
    }
}

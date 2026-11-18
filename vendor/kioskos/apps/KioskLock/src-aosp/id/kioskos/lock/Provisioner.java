package id.kioskos.lock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

/**
 * Menjadikan KioskLock sebagai Device Owner secara otomatis di first boot,
 * termasuk first boot setelah factory reset. Ini pengganti "Knox Mobile Enrollment"
 * / zero-touch: karena aplikasinya ada di partisi system, ia selalu ada lagi setelah reset.
 */
public final class Provisioner {
    private static final String TAG = "KioskLock.Provision";

    private Provisioner() {}

    public static boolean ensureDeviceOwner(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm.isDeviceOwnerApp(context.getPackageName())) {
            return true;
        }
        ComponentName admin = KioskAdminReceiver.component(context);
        try {
            // Hidden API (butuh MANAGE_DEVICE_ADMINS / MANAGE_PROFILE_AND_DEVICE_OWNERS).
            dpm.setActiveAdmin(admin, /* refreshing= */ true);
            // Hidden API. Signature-nya berbeda antar versi Android; sesuaikan dengan
            // versi AOSP Anda (Android 14/15: setDeviceOwner(ComponentName, int userId)).
            // Hanya berhasil jika setup belum selesai & belum ada akun di perangkat,
            // karena itu SetupActivity berjalan sebelum Setup Wizard.
            boolean ok = dpm.setDeviceOwner(admin, UserHandle.USER_SYSTEM);
            Log.i(TAG, "setDeviceOwner -> " + ok);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "Gagal menjadi device owner", e);
            return false;
        }
    }
}

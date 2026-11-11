package id.kioskos.lock;

import android.content.Context;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

/** Identitas perangkat untuk dicocokkan di server (seperti Knox Guard memakai IMEI/serial). */
public final class DeviceIdentity {
    private static final String TAG = "KioskLock.Identity";

    private DeviceIdentity() {}

    /** Serial number hardware; tidak berubah setelah factory reset. */
    public static String serial() {
        try {
            return Build.getSerial(); // butuh READ_PRIVILEGED_PHONE_STATE
        } catch (SecurityException e) {
            Log.w(TAG, "Tidak bisa membaca serial", e);
            return Build.UNKNOWN;
        }
    }

    /** IMEI slot pertama, atau string kosong (perangkat tanpa modem). */
    public static String imei(Context context) {
        try {
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            String imei = tm == null ? null : tm.getImei(0);
            return imei == null ? "" : imei;
        } catch (Exception e) {
            Log.w(TAG, "Tidak bisa membaca IMEI", e);
            return "";
        }
    }
}

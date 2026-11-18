package id.kioskos.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

/**
 * Edisi Device Owner: status disimpan di penyimpanan aplikasi (device-protected, agar terbaca
 * sebelum layar dibuka setelah reboot).
 *
 * BEDA PENTING dengan ROM AOSP: data ini IKUT TERHAPUS saat factory reset. Setelah reset,
 * perangkat harus didaftarkan ulang (QR / zero-touch), lalu status kunci diambil lagi dari
 * server. Untuk bertahan dari reset tanpa server, butuh partisi FRP (hanya di ROM sendiri).
 */
public final class PersistentLockStore {
    private static final String TAG = "KioskLock.Store";
    private static final String PREFS = "lock_state";
    private static final String KEY = "state";

    private final SharedPreferences mPrefs;

    public PersistentLockStore(Context context) {
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isAvailable() {
        return true;
    }

    public LockState read() {
        String json = mPrefs.getString(KEY, null);
        if (json == null) return LockState.NONE;
        try {
            return LockState.fromJson(new JSONObject(json));
        } catch (Exception e) {
            Log.e(TAG, "Status tersimpan rusak", e);
            return LockState.NONE;
        }
    }

    public boolean write(LockState state) {
        return mPrefs.edit().putString(KEY, state.toString()).commit();
    }

    /** Tidak tersedia untuk Device Owner biasa (butuh izin sistem). */
    public void disableOemUnlock() {
    }
}

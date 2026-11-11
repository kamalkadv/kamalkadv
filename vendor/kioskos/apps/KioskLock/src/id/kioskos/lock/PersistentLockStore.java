package id.kioskos.lock;

import android.content.Context;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Menyimpan {@link LockState} di PersistentDataBlock (partisi "frp").
 *
 * Partisi ini sengaja TIDAK dihapus oleh factory reset (baik dari Settings maupun dari
 * recovery), sama seperti mekanisme Factory Reset Protection Google. Karena overlay
 * config_persistentDataPackageName menunjuk ke paket ini, hanya KioskLock yang boleh menulis.
 */
public final class PersistentLockStore {
    private static final String TAG = "KioskLock.Store";
    private static final byte[] MAGIC = "KIOSKv1\0".getBytes(StandardCharsets.US_ASCII);

    private final PersistentDataBlockManager mPdb;

    public PersistentLockStore(Context context) {
        mPdb = (PersistentDataBlockManager)
                context.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
    }

    public boolean isAvailable() {
        return mPdb != null;
    }

    public LockState read() {
        if (mPdb == null) {
            Log.w(TAG, "PersistentDataBlock tidak tersedia (ro.frp.pst belum di-set?)");
            return LockState.NONE;
        }
        try {
            byte[] data = mPdb.read();
            if (data == null || data.length <= MAGIC.length
                    || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) {
                return LockState.NONE;
            }
            String json = new String(data, MAGIC.length, data.length - MAGIC.length,
                    StandardCharsets.UTF_8);
            return LockState.fromJson(new JSONObject(json));
        } catch (Exception e) {
            Log.e(TAG, "Gagal membaca persistent block", e);
            return LockState.NONE;
        }
    }

    public boolean write(LockState state) {
        if (mPdb == null) return false;
        byte[] json = state.toString().getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[MAGIC.length + json.length];
        System.arraycopy(MAGIC, 0, data, 0, MAGIC.length);
        System.arraycopy(json, 0, data, MAGIC.length, json.length);
        try {
            int written = mPdb.write(data);
            if (written < 0) {
                Log.e(TAG, "Persistent block menolak penulisan");
                return false;
            }
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Tidak diizinkan menulis persistent block; cek overlay "
                    + "config_persistentDataPackageName", e);
            return false;
        }
    }

    /** Matikan opsi "OEM unlocking" agar bootloader tidak bisa di-unlock dari Developer Options. */
    public void disableOemUnlock() {
        if (mPdb == null) return;
        try {
            mPdb.setOemUnlockEnabled(false);
        } catch (Exception e) {
            Log.w(TAG, "Gagal mematikan OEM unlock", e);
        }
    }
}

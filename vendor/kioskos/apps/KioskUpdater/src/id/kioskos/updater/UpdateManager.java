package id.kioskos.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import id.kioskos.common.SignedJsonClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cek update ke server KioskOS lalu serahkan ke update_engine (perangkat A/B).
 *
 * update_engine sendiri yang mengunduh payload (HTTP Range), memverifikasi tanda tangan
 * payload dengan kunci rilis yang tertanam di ROM, lalu memasangnya ke slot cadangan.
 * Jika gagal, perangkat tetap boot dari slot lama. Server tidak bisa memaksa ROM yang
 * tidak ditandatangani kunci Anda.
 */
public final class UpdateManager {
    private static final String TAG = "KioskUpdater";
    private static final String PREFS = "updater";
    private static final String KEY_APPLYING = "applying_id";
    private static final String KEY_REBOOT_PENDING = "reboot_pending";

    private static UpdateManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final UpdateEngine mEngine = new UpdateEngine();
    private final CountDownLatch mFirstStatus = new CountDownLatch(1);
    private volatile int mStatus = -1;
    private boolean mBound;

    public static synchronized UpdateManager get(Context context) {
        if (sInstance == null) sInstance = new UpdateManager(context.getApplicationContext());
        return sInstance;
    }

    private UpdateManager(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Boot apa pun (termasuk restart manual oleh pengguna) sudah menyelesaikan update yang
     * menunggu restart, jadi tanda & job restart yang tersimpan harus dibuang. Tanpa ini,
     * job restart yang persisten bisa me-restart perangkat sekali lagi tanpa alasan.
     */
    void onBootCompleted() {
        mPrefs.edit().remove(KEY_REBOOT_PENDING).remove(KEY_APPLYING).apply();
        mContext.getSystemService(android.app.job.JobScheduler.class).cancel(UpdateJobService.JOB_REBOOT);
    }

    boolean isRebootPending() {
        return mPrefs.getBoolean(KEY_REBOOT_PENDING, false);
    }

    private final UpdateEngineCallback mCallback = new UpdateEngineCallback() {
        @Override
        public void onStatusUpdate(int status, float percent) {
            if (status != mStatus) Log.i(TAG, "Status update_engine: " + status + " (" + (int) (percent * 100) + "%)");
            mStatus = status;
            if (status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT) {
                markRebootPending();
            }
            mFirstStatus.countDown();
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
                Log.i(TAG, "Update terpasang, menunggu restart");
                markRebootPending();
            } else {
                Log.e(TAG, "Update gagal, kode error update_engine " + errorCode);
                mPrefs.edit().remove(KEY_APPLYING).apply();
            }
        }
    };

    private void markRebootPending() {
        mPrefs.edit().putBoolean(KEY_REBOOT_PENDING, true).apply();
        UpdateJobService.scheduleReboot(mContext);
    }

    /** Dipanggil dari thread job. Cepat selesai; unduhan berjalan di update_engine. */
    synchronized void checkAndApply() {
        if (!SystemProperties.getBoolean("ro.build.ab_update", false)) {
            Log.w(TAG, "Perangkat bukan A/B; KioskUpdater hanya mendukung update A/B");
            return;
        }
        if (!mBound) {
            mBound = mEngine.bind(mCallback);
        }
        try {
            mFirstStatus.await(10, TimeUnit.SECONDS); // bind() langsung mengirim status saat ini
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        switch (mStatus) {
            case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT:
                markRebootPending();
                return;
            case UpdateEngine.UpdateStatusConstants.IDLE:
            case UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT:
                mPrefs.edit().remove(KEY_REBOOT_PENDING).apply();
                break;
            default:
                Log.i(TAG, "update_engine sedang bekerja (status " + mStatus + "), cek lagi nanti");
                return;
        }

        JSONObject update = fetchUpdate();
        if (update == null) return;
        try {
            String url = update.getString("url");
            long offset = update.getLong("offset");
            long size = update.getLong("size");
            JSONArray h = update.getJSONArray("headers");
            String[] headers = new String[h.length()];
            for (int i = 0; i < headers.length; i++) headers[i] = h.getString(i);

            Log.i(TAG, "Memasang update " + update.optString("version") + " ("
                    + (update.optBoolean("incremental") ? "inkremental" : "full") + ")");
            mPrefs.edit().putString(KEY_APPLYING, update.optString("id")).apply();
            mEngine.applyPayload(url, offset, size, headers);
        } catch (Exception e) {
            Log.e(TAG, "Gagal memulai update", e);
            mPrefs.edit().remove(KEY_APPLYING).apply();
        }
    }

    /** @return objek "update" dari server, atau null jika tidak ada / gagal. */
    private JSONObject fetchUpdate() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serial", serial());
        params.put("device", Build.DEVICE);
        params.put("fingerprint", Build.FINGERPRINT);
        params.put("build_utc", Long.toString(Build.TIME / 1000));
        params.put("channel", mContext.getString(R.string.config_ota_channel));
        JSONObject json = new SignedJsonClient(
                mContext.getString(R.string.config_server_url),
                mContext.getString(R.string.config_server_public_key),
                TAG).get("api/v1/ota/check", params);
        if (json == null) return null;
        JSONObject update = json.optJSONObject("update");
        if (update == null) {
            Log.i(TAG, "Tidak ada update");
            return null;
        }
        // Lapisan tambahan: jangan pernah "update" ke build yang lebih lama.
        if (update.optLong("build_utc") <= Build.TIME / 1000) {
            Log.w(TAG, "Server menawarkan build yang tidak lebih baru, diabaikan");
            return null;
        }
        return update;
    }

    private static String serial() {
        try {
            return Build.getSerial();
        } catch (SecurityException e) {
            return Build.UNKNOWN;
        }
    }
}

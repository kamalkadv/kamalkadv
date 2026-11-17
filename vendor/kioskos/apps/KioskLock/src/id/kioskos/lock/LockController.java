package id.kioskos.lock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

/** Menerapkan kebijakan Device Owner sesuai {@link LockState}. */
public final class LockController {
    private static final String TAG = "KioskLock.Controller";

    /** Paket yang boleh jalan saat mode kiosk: aplikasi ini, dialer darurat, panel Wi-Fi. */
    private static final String PKG_PHONE = "com.android.phone";
    private static final String PKG_SETTINGS = "com.android.settings";

    /** Restriksi yang selalu aktif selama perangkat terdaftar. */
    private static final String[] BASE_RESTRICTIONS = {
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
    };

    private static LockController sInstance;

    private final Context mContext;
    private final DevicePolicyManager mDpm;
    private final PersistentLockStore mStore;
    private final ComponentName mAdmin;

    public static synchronized LockController get(Context context) {
        if (sInstance == null) {
            sInstance = new LockController(context.getApplicationContext());
        }
        return sInstance;
    }

    private LockController(Context context) {
        mContext = context;
        mDpm = context.getSystemService(DevicePolicyManager.class);
        mStore = new PersistentLockStore(context);
        mAdmin = KioskAdminReceiver.component(context);
    }

    public LockState currentState() {
        return mStore.read();
    }

    /** Dipanggil saat boot: terapkan status terakhir dari partisi persisten (tanpa internet). */
    public void applyFromStore() {
        apply(mStore.read());
    }

    /**
     * Status baru dari server (sudah diverifikasi tanda tangannya).
     * Status yang lebih tua dari yang tersimpan diabaikan (anti-replay).
     */
    public synchronized void update(LockState incoming) {
        LockState stored = mStore.read();
        if (incoming.issuedAt < stored.issuedAt) {
            Log.w(TAG, "Abaikan status lama dari server: " + incoming);
            return;
        }
        if (!mStore.write(incoming)) {
            Log.e(TAG, "Gagal menyimpan status ke partisi persisten");
        }
        apply(incoming);
    }

    private synchronized void apply(LockState state) {
        if (!mDpm.isDeviceOwnerApp(mContext.getPackageName())) {
            Log.w(TAG, "Belum menjadi device owner, kebijakan tidak diterapkan");
            return;
        }
        Log.i(TAG, "Terapkan status: " + state);

        boolean blockReset = mContext.getResources().getBoolean(R.bool.config_block_factory_reset);
        setRestriction(UserManager.DISALLOW_FACTORY_RESET, state.enrolled && blockReset);
        for (String r : BASE_RESTRICTIONS) {
            setRestriction(r, state.enrolled);
        }
        // Sembunyikan Developer Options & matikan ADB. Di build debug (userdebug/eng) boleh
        // dilewati lewat config agar pengujian otomatis via ADB tetap bisa berjalan.
        boolean blockDebug = !Build.IS_DEBUGGABLE || mContext.getResources().getBoolean(
                R.bool.config_block_debugging_on_debuggable_builds);
        setRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, state.enrolled && blockDebug);
        if (state.enrolled) {
            mStore.disableOemUnlock();
        }

        if (state.locked) {
            lock();
        } else {
            unlock();
        }
    }

    private void lock() {
        String self = mContext.getPackageName();
        mDpm.setLockTaskPackages(mAdmin, new String[] {self, PKG_PHONE, PKG_SETTINGS});
        mDpm.setLockTaskFeatures(mAdmin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO);
        mDpm.setStatusBarDisabled(mAdmin, true);

        // Jadikan LockActivity sebagai HOME permanen, mengalahkan launcher & Setup Wizard.
        setLockActivityEnabled(true);
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        mDpm.addPersistentPreferredActivity(mAdmin, home,
                new ComponentName(mContext, LockActivity.class));

        mContext.startActivity(new Intent(mContext, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private void unlock() {
        mDpm.clearPackagePersistentPreferredActivities(mAdmin, mContext.getPackageName());
        mDpm.setStatusBarDisabled(mAdmin, false);

        // Minta LockActivity keluar dari lock task, lalu sembunyikan dari daftar HOME.
        if (LockActivity.isRunning()) {
            mContext.startActivity(new Intent(mContext, LockActivity.class)
                    .setAction(LockActivity.ACTION_RELEASE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            onLockActivityReleased();
        }
    }

    /** Dipanggil LockActivity setelah stopLockTask(). */
    void onLockActivityReleased() {
        mDpm.setLockTaskPackages(mAdmin, new String[0]);
        setLockActivityEnabled(false);
    }

    private void setLockActivityEnabled(boolean enabled) {
        mContext.getPackageManager().setComponentEnabledSetting(
                new ComponentName(mContext, LockActivity.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void setRestriction(String key, boolean on) {
        if (on) {
            mDpm.addUserRestriction(mAdmin, key);
        } else {
            mDpm.clearUserRestriction(mAdmin, key);
        }
    }
}

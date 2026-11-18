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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Menerapkan kebijakan Device Owner sesuai {@link LockState}. */
public final class LockController {
    private static final String TAG = "KioskLock.Controller";

    /**
     * Paket yang boleh jalan saat mode kiosk: aplikasi ini + dialer darurat.
     * Settings sengaja TIDAK diizinkan: dari panel Wi-Fi bawaan pengguna bisa masuk ke
     * Settings lengkap. Wi-Fi dipilih lewat WifiActivity milik KioskLock sendiri.
     */
    private static final String PKG_PHONE = "com.android.phone";

    /**
     * Tidak pernah dimasukkan ke daftar Lock Task walau dikirim server: bisa dipakai untuk keluar
     * dari kunci, mencabut Device Owner, memasang/menghapus aplikasi, atau mengganti launcher.
     * Server juga menolak daftar yang sama (server/server.py: DENIED_PACKAGES).
     */
    private static final Set<String> DENIED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.settings", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.android.vending", "com.android.shell",
            "com.android.documentsui", "com.google.android.documentsui", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.android.systemui", "com.android.provision",
            "com.google.android.setupwizard", "com.android.managedprovisioning",
            "id.kioskos.lock", "id.kioskos.updater"));

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
        apply(mStore.read(), /* bringToFront= */ true);
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
        // Layar kunci hanya dipaksa ke depan saat BARU dikunci. Jika sudah terkunci, pengguna
        // mungkin sedang memakai aplikasi yang diizinkan (mis. membayar di aplikasi bank) dan
        // tidak boleh dilempar balik ke layar kunci setiap sinkron.
        apply(incoming, /* bringToFront= */ !stored.locked);
    }

    private synchronized void apply(LockState state, boolean bringToFront) {
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
        boolean blockDebug = "user".equals(Build.TYPE) || mContext.getResources().getBoolean(
                R.bool.config_block_debugging_on_debuggable_builds);
        setRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, state.enrolled && blockDebug);
        if (state.enrolled) {
            mStore.disableOemUnlock();
        }

        if (state.locked) {
            lock(state, bringToFront);
        } else {
            unlock();
        }
    }

    private void lock(LockState state, boolean bringToFront) {
        List<String> packages = new ArrayList<>();
        packages.add(mContext.getPackageName());
        packages.add(PKG_PHONE);
        packages.addAll(launchableAllowedApps(state));
        mDpm.setLockTaskPackages(mAdmin, packages.toArray(new String[0]));
        // HOME: tombol Home tampil dan selalu membawa ke layar kunci (LockActivity = HOME),
        // jadi pengguna bisa kembali dari aplikasi yang diizinkan.
        mDpm.setLockTaskFeatures(mAdmin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME);
        mDpm.setStatusBarDisabled(mAdmin, true);

        // Jadikan LockActivity sebagai HOME permanen, mengalahkan launcher & Setup Wizard.
        setLockActivityEnabled(true);
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        mDpm.addPersistentPreferredActivity(mAdmin, home,
                new ComponentName(mContext, LockActivity.class));

        if (bringToFront || !LockActivity.isRunning()) {
            mContext.startActivity(new Intent(mContext, LockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } else {
            LockActivity.requestRefresh();
        }
    }

    /**
     * Aplikasi yang diizinkan server, tersaring: terpasang, punya ikon launcher, dan tidak ada di
     * {@link #DENIED_PACKAGES}. Dipakai untuk daftar Lock Task dan ikon di layar kunci.
     */
    List<String> launchableAllowedApps(LockState state) {
        PackageManager pm = mContext.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String pkg : state.allowedApps) {
            if (DENIED_PACKAGES.contains(pkg)) {
                Log.w(TAG, "Paket ditolak untuk mode kunci: " + pkg);
            } else if (pm.getLaunchIntentForPackage(pkg) == null) {
                Log.i(TAG, "Aplikasi diizinkan tapi tidak terpasang: " + pkg);
            } else {
                out.add(pkg);
            }
        }
        return out;
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

package id.kioskos.lock;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Layar kunci kiosk. Berjalan dalam Lock Task Mode: hanya aplikasi ini, dialer darurat, dan
 * aplikasi yang diizinkan server yang bisa dibuka. Tombol Home selalu kembali ke layar ini.
 */
public class LockActivity extends Activity {
    private static final String TAG = "KioskLock.Lock";
    public static final String ACTION_RELEASE = "id.kioskos.lock.action.RELEASE";

    private static volatile boolean sRunning;
    private static volatile WeakReference<LockActivity> sInstance = new WeakReference<>(null);

    private TextView mMessage;
    private TextView mContact;
    private TextView mStatus;
    private Button mCheck;
    private TextView mAppsLabel;
    private GridLayout mAppGrid;

    static boolean isRunning() {
        return sRunning;
    }

    /** Perbarui isi layar (pesan, aplikasi) tanpa memaksa layar kunci ke depan. */
    static void requestRefresh() {
        LockActivity a = sInstance.get();
        if (a != null) a.runOnUiThread(a::refresh);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sRunning = true;
        sInstance = new WeakReference<>(this);
        setContentView(R.layout.activity_lock);
        mAppsLabel = findViewById(R.id.apps_label);
        mAppGrid = findViewById(R.id.app_grid);
        mMessage = findViewById(R.id.message);
        mContact = findViewById(R.id.contact);
        mStatus = findViewById(R.id.status);
        mCheck = findViewById(R.id.btn_check);
        ((TextView) findViewById(R.id.device_id)).setText(
                getString(R.string.device_id_label, DeviceIdentity.serial()));

        mCheck.setOnClickListener(v -> checkNow());
        findViewById(R.id.btn_emergency).setOnClickListener(v -> {
            try {
                startActivity(new Intent("com.android.phone.EmergencyDialer.DIAL")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "Emergency dialer tidak tersedia", e);
            }
        });
        // Pemilih Wi-Fi milik KioskLock (bukan panel Settings), supaya perangkat yang baru
        // di-reset bisa online untuk cek status tanpa membuka jalan ke Settings.
        findViewById(R.id.btn_wifi).setOnClickListener(
                v -> startActivity(new Intent(this, WifiActivity.class)));

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        sRunning = false;
        if (sInstance.get() == this) sInstance = new WeakReference<>(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Diabaikan saat terkunci.
    }

    private void handleIntent(Intent intent) {
        LockState state = LockController.get(this).currentState();
        if (ACTION_RELEASE.equals(intent.getAction()) || !state.locked) {
            release();
            return;
        }
        enterLockTask();
    }

    private void enterLockTask() {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                startLockTask();
            } catch (Exception e) {
                Log.e(TAG, "startLockTask gagal", e);
            }
        }
    }

    private void release() {
        try {
            stopLockTask();
        } catch (Exception ignored) {
            // Tidak sedang lock task.
        }
        LockController.get(this).onLockActivityReleased();
        finish();
    }

    private void refresh() {
        LockState state = LockController.get(this).currentState();
        if (!state.locked) {
            release();
            return;
        }
        if (!TextUtils.isEmpty(state.message)) mMessage.setText(state.message);
        mContact.setText(state.contact);
        mContact.setVisibility(TextUtils.isEmpty(state.contact) ? View.GONE : View.VISIBLE);
        showAllowedApps(LockController.get(this).launchableAllowedApps(state));
    }

    /** Ikon aplikasi yang diizinkan saat terkunci (hanya yang terpasang & lolos daftar blokir). */
    private void showAllowedApps(List<String> packages) {
        mAppGrid.removeAllViews();
        int visibility = packages.isEmpty() ? View.GONE : View.VISIBLE;
        mAppsLabel.setVisibility(visibility);
        mAppGrid.setVisibility(visibility);
        PackageManager pm = getPackageManager();
        float dp = getResources().getDisplayMetrics().density;
        for (String pkg : packages) {
            CharSequence label;
            Drawable icon;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // baru saja di-uninstall
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding((int) (6 * dp), (int) (8 * dp), (int) (6 * dp), (int) (8 * dp));
            cell.setBackgroundResource(android.R.drawable.list_selector_background);
            cell.setContentDescription(label);

            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            cell.addView(iv, new LinearLayout.LayoutParams((int) (48 * dp), (int) (48 * dp)));

            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(0xFFDDDDDD);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(2);
            tv.setEllipsize(TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams((int) (72 * dp),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = (int) (4 * dp);
            cell.addView(tv, tlp);

            cell.setOnClickListener(v -> openApp(pkg));
            mAppGrid.addView(cell);
        }
    }

    private void openApp(String pkg) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        try {
            // Paket ini ada di daftar Lock Task, jadi boleh dibuka. Tombol Home / Back dari
            // akar aplikasi itu kembali ke layar kunci.
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "Gagal membuka " + pkg, e);
        }
    }

    private void checkNow() {
        mCheck.setEnabled(false);
        mStatus.setText(R.string.checking);
        new Thread(() -> {
            boolean ok = SyncJobService.syncBlocking(getApplicationContext());
            runOnUiThread(() -> {
                mCheck.setEnabled(true);
                mStatus.setText(ok ? "" : getString(R.string.check_failed));
                if (!isFinishing()) refresh();
            });
        }, "KioskLockCheck").start();
    }
}

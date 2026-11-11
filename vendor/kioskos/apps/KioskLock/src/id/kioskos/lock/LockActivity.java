package id.kioskos.lock;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Layar kunci kiosk. Berjalan dalam Lock Task Mode sehingga tombol Home/Recents tidak berfungsi. */
public class LockActivity extends Activity {
    private static final String TAG = "KioskLock.Lock";
    public static final String ACTION_RELEASE = "id.kioskos.lock.action.RELEASE";

    private static volatile boolean sRunning;

    private TextView mMessage;
    private TextView mContact;
    private TextView mStatus;
    private Button mCheck;

    static boolean isRunning() {
        return sRunning;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sRunning = true;
        setContentView(R.layout.activity_lock);
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
        // Panel Wi-Fi, supaya perangkat yang baru di-reset bisa online untuk cek status.
        findViewById(R.id.btn_wifi).setOnClickListener(
                v -> startActivity(new Intent(Settings.Panel.ACTION_WIFI)));

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

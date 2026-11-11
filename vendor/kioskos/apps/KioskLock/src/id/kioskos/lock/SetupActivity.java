package id.kioskos.lock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Activity HOME dengan prioritas lebih tinggi dari Setup Wizard, sehingga dijalankan paling
 * pertama setelah first boot / factory reset. Tugasnya:
 *   1. Pasang KioskLock sebagai Device Owner (harus sebelum setup selesai).
 *   2. Terapkan status kunci dari partisi FRP -> jika status "locked", perangkat langsung
 *      terkunci walau baru di-reset dan belum ada internet.
 *   3. Nonaktifkan dirinya sendiri, lalu Setup Wizard berjalan seperti biasa.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "KioskLock.Setup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean owner = Provisioner.ensureDeviceOwner(this);
        Log.i(TAG, "Device owner: " + owner);

        LockController.get(this).applyFromStore();
        SyncJobService.schedule(this);

        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, SetupActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        finish();
    }
}

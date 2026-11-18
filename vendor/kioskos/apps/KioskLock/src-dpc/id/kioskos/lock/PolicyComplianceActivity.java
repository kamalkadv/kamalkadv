package id.kioskos.lock;

import android.app.Activity;
import android.os.Bundle;

/**
 * Wajib untuk provisioning lewat QR code (Android 12+): dipanggil di akhir setup. Di sini
 * kebijakan diterapkan, lalu Setup Wizard dilanjutkan.
 */
public class PolicyComplianceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Provisioner.ensureDeviceOwner(this);
        LockController.get(this).applyFromStore();
        SyncJobService.schedule(this);
        setResult(RESULT_OK);
        finish();
    }
}

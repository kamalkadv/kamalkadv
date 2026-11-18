package id.kioskos.lock;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;

/**
 * Wajib untuk provisioning lewat QR code (Android 12+): memberi tahu Setup Wizard bahwa
 * perangkat dijadikan "fully managed" (Device Owner).
 */
public class ProvisioningModeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_OK, new Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE));
        finish();
    }
}

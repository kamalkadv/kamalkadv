package id.kioskos.lock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Setiap boot: terapkan status dari partisi persisten SEGERA (tanpa menunggu internet),
 * lalu jadwalkan sinkron ke server.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            // Direct boot: user belum unlock layar, cukup terapkan status tersimpan.
            LockController.get(context).applyFromStore();
            return;
        }
        // BOOT_COMPLETED / MY_PACKAGE_REPLACED
        Provisioner.ensureDeviceOwner(context);
        LockController.get(context).applyFromStore();
        SyncJobService.schedule(context);
    }
}

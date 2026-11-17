package id.kioskos.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Jadwalkan pengecekan update setiap boot / setelah aplikasi diperbarui. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            UpdateManager.get(context).onBootCompleted();
        }
        UpdateJobService.scheduleChecks(context);
    }
}

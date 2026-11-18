package id.kioskos.lock;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public class KioskAdminReceiver extends DeviceAdminReceiver {

    public static ComponentName component(Context context) {
        return new ComponentName(context, KioskAdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        Provisioner.ensureDeviceOwner(context);
        LockController.get(context).applyFromStore();
        SyncJobService.schedule(context);
    }
}

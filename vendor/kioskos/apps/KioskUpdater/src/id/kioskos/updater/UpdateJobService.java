package id.kioskos.updater;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/**
 * Dua job:
 *   JOB_CHECK  : berkala, cek server lalu (jika ada) minta update_engine memasang update.
 *   JOB_REBOOT : setelah update terpasang, restart saat perangkat diam & sedang dicas.
 */
public class UpdateJobService extends JobService {
    private static final String TAG = "KioskUpdater.Job";
    static final int JOB_CHECK = 10;
    static final int JOB_REBOOT = 11;

    static void scheduleChecks(Context context) {
        long hours = Math.max(1, context.getResources().getInteger(R.integer.config_ota_check_interval_hours));
        boolean unmetered = context.getResources().getBoolean(R.bool.config_ota_unmetered_only);
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB_CHECK,
                new ComponentName(context, UpdateJobService.class))
                .setRequiredNetworkType(unmetered ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(hours))
                .setPersisted(true)
                .build());
    }

    static void scheduleReboot(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_REBOOT) != null) return;
        js.schedule(new JobInfo.Builder(JOB_REBOOT, new ComponentName(context, UpdateJobService.class))
                .setRequiresDeviceIdle(true)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build());
        Log.i(TAG, "Restart dijadwalkan saat perangkat diam & dicas");
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() == JOB_REBOOT) {
            if (UpdateManager.get(this).isRebootPending()) {
                Log.i(TAG, "Restart untuk menyelesaikan update");
                getSystemService(PowerManager.class).reboot(null);
            }
            return false;
        }
        new Thread(() -> {
            UpdateManager.get(this).checkAndApply();
            jobFinished(params, false);
        }, "KioskUpdaterCheck").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // update_engine tetap berjalan sendiri walau job dihentikan; tidak perlu dijadwalkan ulang.
        return false;
    }
}

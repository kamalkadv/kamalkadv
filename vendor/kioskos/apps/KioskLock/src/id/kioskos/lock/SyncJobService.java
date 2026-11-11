package id.kioskos.lock;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.TimeUnit;

/** Sinkron status ke server secara berkala (butuh jaringan). */
public class SyncJobService extends JobService {
    private static final int JOB_PERIODIC = 1;
    private static final int JOB_NOW = 2;

    public static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        long minutes = Math.max(15,
                context.getResources().getInteger(R.integer.config_sync_interval_minutes));
        js.schedule(new JobInfo.Builder(JOB_PERIODIC,
                new ComponentName(context, SyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.MINUTES.toMillis(minutes))
                .setPersisted(true)
                .build());
        syncNow(context);
    }

    public static void syncNow(Context context) {
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB_NOW,
                new ComponentName(context, SyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    /** Sinkron langsung (blocking). Jangan dipanggil dari main thread. */
    public static boolean syncBlocking(Context context) {
        LockState state = new ServerClient(context).fetch();
        if (state == null) return false;
        LockController.get(context).update(state);
        return true;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean ok = syncBlocking(this);
            jobFinished(params, !ok && params.getJobId() == JOB_NOW);
        }, "KioskLockSync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}

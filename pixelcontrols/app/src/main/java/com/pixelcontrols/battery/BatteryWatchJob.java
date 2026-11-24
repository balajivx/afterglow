package com.pixelcontrols.battery;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Battery watch's hourly check (scheduled by BatteryWatch.schedule; Android may batch it). */
public class BatteryWatchJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try { BatteryWatch.check(getApplicationContext()); } finally { jobFinished(params, false); }
        }, "battery-watch").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}

package com.lynxeye;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

public class ServiceWatcher extends Worker {

    public ServiceWatcher(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Revisar si AudioService sigue vivo
        // Si no hay servicio corriendo no podemos saber fácilmente,
        // pero el intent lo reinicia solo si no está
        try {
            Intent intent = new Intent(getApplicationContext(), AudioService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(intent);
            } else {
                getApplicationContext().startService(intent);
            }
        } catch (Exception ignored) {}
        return Result.success();
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ServiceWatcher.class, 15, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "LynxEyeWatcher",
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }
}

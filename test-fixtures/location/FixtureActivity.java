package com.ambient.locationfixture;

import android.app.Activity;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Test-only APK: never compiled into the Ambient app. Requires emulator mock-location app-op. */
public final class FixtureActivity extends Activity {
    private static final String TAG = "AmbientLocationFixture";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent command = getIntent();
        final String requestId = command.getStringExtra("request_id");
        final LocationManager manager = getSystemService(LocationManager.class);
        // Return Ambient to the foreground before injection so its foreground-only listener is active.
        finish();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (command.getBooleanExtra("reset", false)) {
                        try {
                            manager.removeTestProvider(LocationManager.GPS_PROVIDER);
                        } catch (IllegalArgumentException ignored) {
                            // Reset is intentionally idempotent.
                        }
                        Log.i(TAG, requestId + " RESET");
                        return;
                    }
                    installProvider(manager);
                    inject(manager, command);
                    Log.i(TAG, requestId + " INJECTED 1/3");
                    final Handler handler = new Handler(Looper.getMainLooper());
                    for (int index = 2; index <= 3; index++) {
                        final int sampleIndex = index;
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    inject(manager, command);
                                    Log.i(TAG, requestId + " INJECTED " + sampleIndex + "/3");
                                } catch (RuntimeException error) {
                                    Log.e(TAG, requestId + " ERROR " + error.getClass().getSimpleName());
                                }
                            }
                        }, (index - 1) * 1000L);
                    }
                } catch (RuntimeException error) {
                    Log.e(TAG, requestId + " ERROR " + error.getClass().getSimpleName());
                }
            }
        }, 1000L);
    }

    @SuppressWarnings("deprecation")
    private static void installProvider(LocationManager manager) {
        manager.addTestProvider(LocationManager.GPS_PROVIDER,
                false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
    }

    private static void inject(LocationManager manager, Intent command) {
        final double ageSeconds = command.getFloatExtra("age_seconds", 0f);
        final double futureSeconds = command.getFloatExtra("future_seconds", 0f);
        final long offsetNanos = (long) ((futureSeconds - ageSeconds) * 1_000_000_000.0);
        final Location location = new Location(LocationManager.GPS_PROVIDER);
        // Fixed synthetic coordinates exist only in this independent emulator fixture.
        location.setLatitude(31.2304);
        location.setLongitude(121.4737);
        location.setAccuracy(3f);
        location.setTime(System.currentTimeMillis() + offsetNanos / 1_000_000L);
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() + offsetNanos);
        if (!command.getBooleanExtra("no_speed", false)) {
            location.setSpeed(command.getFloatExtra("speed", 5.144444f));
            final float accuracy = command.getFloatExtra("accuracy", Float.NaN);
            if (!Float.isNaN(accuracy) && accuracy >= 0f) {
                location.setSpeedAccuracyMetersPerSecond(accuracy);
            }
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
    }
}

package com.android.server;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;
import android.view.WindowManagerGlobal;

import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

public class MovementBasedLockService extends SystemService {
    private static final boolean DBG = false;
    private static final String TAG = "MovementBasedLockService";

    /** The listener that receives the movement based lock gesture event. */
    private final MovementBasedLockEventListener mMovementBasedLockListener = new MovementBasedLockEventListener();

    private PowerManager mPowerManager;
    private WindowManagerInternal mWindowManagerInternal;

    private Sensor mMovementBasedLockSensor;
    private Context mContext;

    private boolean mMovementBasedLockRegistered;
    private int mUserId;

    public MovementBasedLockService(Context context) {
        super(context);
        mContext = context;
    }

    public void onStart() {
        LocalServices.addService(MovementBasedLockService.class, this);
    }

    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            Resources resources = mContext.getResources();
            if (!isMovementBasedLockEnabled(resources)) {
                if (DBG) Slog.d(TAG, "Movement based lock is disabled in system properties.");
                return;
            }

            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mPowerManager = (PowerManager) mContext.getSystemService(
                    Context.POWER_SERVICE);
            updateMovementBasedLockRegistered();

            mUserId = ActivityManager.getCurrentUser();
            mContext.registerReceiver(mUserReceiver, new IntentFilter(Intent.ACTION_USER_SWITCHED));
            registerContentObserver();
        }
    }

    private void registerContentObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.MOVEMENT_BASED_LOCK_GESTURE_DISABLED),
                false, mSettingObserver, mUserId);
    }

    private void updateMovementBasedLockRegistered() {
        if(isMovementBasedLockSettingEnabled(mContext, mUserId)) {
            registerMovementBasedLockGesture();
        } else {
            unregisterMovementBasedLockGesture();
        }
    }

    private void unregisterMovementBasedLockGesture() {
        if (mMovementBasedLockRegistered) {
            mMovementBasedLockRegistered = false;

            SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                    Context.SENSOR_SERVICE);
            sensorManager.unregisterListener(mMovementBasedLockListener);
        }
    }

    /**
     * Registers for the movement based lock gesture.
     */
    private void registerMovementBasedLockGesture() {
        if (mMovementBasedLockRegistered) {
            return;
        }
        SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                Context.SENSOR_SERVICE);
        mMovementBasedLockRegistered = false;
        mMovementBasedLockSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

        if (mMovementBasedLockSensor != null) {
            mMovementBasedLockRegistered = sensorManager.registerListener(mMovementBasedLockListener,
                    mMovementBasedLockSensor, 0);
        }
        if (DBG) Slog.d(TAG, "Linear acceleration sensor registered: " + mMovementBasedLockRegistered);
    }

    public static boolean isMovementBasedLockSettingEnabled(Context context, int userId) {
        return isMovementBasedLockEnabled(context.getResources())
                && (Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.MOVEMENT_BASED_LOCK_GESTURE_DISABLED, 1, userId) == 0);
    }

    public static boolean isMovementBasedLockEnabled(Resources resources) {
        return resources.getBoolean(
                com.android.internal.R.bool.config_movementBasedLockGestureEnabled);
    }

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                mUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
                registerContentObserver();
                updateMovementBasedLockRegistered();
            }
        }
    };

    private final ContentObserver mSettingObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, android.net.Uri uri, int userId) {
            if (userId == mUserId) {
                updateMovementBasedLockRegistered();
            }
        }
    };

    private final class MovementBasedLockEventListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!mMovementBasedLockRegistered) {
              if (DBG) Slog.d(TAG, "Ignoring movement based lock event because it's unregistered.");
              return;
            }
            if (event.sensor == mMovementBasedLockSensor) {
                boolean keyguardLocked = mWindowManagerInternal.isKeyguardLocked();
                boolean interactive = mPowerManager.isInteractive();
                if (DBG) {
                    float[] values = event.values;
                    Slog.d(TAG, String.format("Received a camera launch event: " +
                            "values=[%.4f, %.4f, %.4f].", values[0], values[1], values[2]));
                }
                if (!keyguardLocked && interactive) {
                    handleMovementBasedLock(event);
                } else {
                    if (DBG) Slog.d(TAG, "Ignoring movement based lock event");
                }
                return;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Ignored.
        }

        private void handleMovementBasedLock(SensorEvent event) {
            float DEFAULT_THRESHOLD = 40f;

            float[] values = event.values;
            float x = values[0];
            float y = values[1];
            float z = values[2];
            float total = (float) Math.sqrt((x * x) + (y * y) + (z * z));

            if (total > DEFAULT_THRESHOLD) {
                try {
                    WindowManagerGlobal.getWindowManagerService().lockNow(null);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error while trying to lock device.");
                }
            }
        }
    }
}

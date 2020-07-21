package com.dexterous.flutterlocalnotifications;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.Log;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.view.FlutterCallbackInformation;
import io.flutter.view.FlutterMain;
import io.flutter.view.FlutterNativeView;
import io.flutter.view.FlutterRunArguments;

public class FlutterLocalNotificationsService extends Service {

  private static final String TAG = "FlutterLocalNotificationService";

  public static final String ACTION_NOTIFICATION_DISMISSED =
      "com.dexterous.flutterlocalnotifications.NOTIFICATION_DISMISSED";

  private static final String SHARED_PREFERENCES_KEY = "com.dexterous.flutterlocalnotifications";
  private static final String BACKGROUND_SETUP_CALLBACK_HANDLE_KEY = "background_setup_callback";
  private static final String DISMISS_NOTIFICATION_CALLBACK_HANDLE_KEY =
      "dismiss_notification_callback";

  private final static List<String> backgroundDismissedNotificationsQueue =
      Collections.synchronizedList(new LinkedList<String>());

  private static AtomicBoolean isBackgroundInitialized = new AtomicBoolean(false);

  /**
   * Background Dart execution context.
   */
  private static FlutterNativeView backgroundFlutterView;

  private static Long dismissNotificationHandle;

  private static final AtomicBoolean isIsolateRunning = new AtomicBoolean(false);

  private static PluginRegistry.PluginRegistrantCallback pluginRegistrantCallback;

  private static MethodChannel backgroundChannel;

  private static Context backgroundContext;

  public static Class<?> getServiceClass(Context context) {
    return FlutterLocalNotificationsService.class;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    //
    try {
      onIntentReceived(intent);
    } catch (Exception ex) {
      Log.w(TAG, "Exception processing intent: " +
          (intent == null ? "null" : intent.getAction()));
    }

    // Processed
    stopSelf(startId);
    return START_REDELIVER_INTENT;
  }

  private void onIntentReceived(Intent intent) {
    if (FlutterLocalNotificationsPlugin.DISMISS_NOTIFICATION.equals(intent.getAction())) {
      final String payload = intent.getStringExtra(FlutterLocalNotificationsPlugin.PAYLOAD);
      onNotificationDismissed(payload);
    }
  }

  private void onNotificationDismissed(final String payload) {
    if (isApplicationForeground(this)) {
      Intent intent = new Intent(ACTION_NOTIFICATION_DISMISSED);
      intent.putExtra(FlutterLocalNotificationsPlugin.PAYLOAD, payload);
      LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    } else {
      // If background isolate is not running yet, put event in queue and it will be handled
      // when the isolate starts.
      if (!isIsolateRunning.get()) {
        backgroundDismissedNotificationsQueue.add(payload);
      } else {
        final boolean isOnMainThread = (Looper.myLooper() == Looper.getMainLooper());
        if (isOnMainThread) {
          executeDartCallbackInBackgroundIsolate(
              FlutterLocalNotificationsService.this, payload, null);
        } else {
          final CountDownLatch latch = new CountDownLatch(1);

          new Handler(getMainLooper())
              .post(
                  new Runnable() {
                    @Override
                    public void run() {
                      executeDartCallbackInBackgroundIsolate(
                          FlutterLocalNotificationsService.this, payload, latch);
                    }
                  });
          try {
            latch.await();
          } catch (InterruptedException ex) {
            Log.i(TAG, "Exception waiting to execute Dart callback", ex);
          }
        }
      }
    }
  }

  private static boolean isApplicationForeground(Context context) {
    final KeyguardManager keyguardManager =
        (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

    if (keyguardManager == null || keyguardManager.isKeyguardLocked()) {
      return false;
    }

    final int myPid = android.os.Process.myPid();
    final ActivityManager activityManager =
        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

    final List<ActivityManager.RunningAppProcessInfo> list =
        activityManager == null ? null : activityManager.getRunningAppProcesses();

    if (list != null) {
      for (ActivityManager.RunningAppProcessInfo aList : list) {
        if (aList.pid == myPid) {
          return aList.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
      }
    }
    return false;
  }

  @Override
  public void onCreate() {
    super.onCreate();

    // backgroundContext = getApplicationContext();
    backgroundContext = this;
    FlutterMain.ensureInitializationComplete(backgroundContext, null);

    // If background isolate is not running start it.
    if (!isIsolateRunning.get()) {
      SharedPreferences p = backgroundContext.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
      long callbackHandle = p.getLong(BACKGROUND_SETUP_CALLBACK_HANDLE_KEY, 0);
      startBackgroundIsolate(new BackgroundIsolateMethodCallHandler(), backgroundContext, callbackHandle);
    }
  }

  /**
   * Acknowledge that background notifications event handling on the Dart side is ready.
   * This is called by the Dart side once all background initialization is complete
   */
  public static void onInitialized() {
    isIsolateRunning.set(true);
    synchronized (backgroundDismissedNotificationsQueue) {
      // Handle all the events received before the Dart isolate was
      // initialized, then clear the queue.
      for (String payload : backgroundDismissedNotificationsQueue) {
        executeDartCallbackInBackgroundIsolate(backgroundContext, payload, null);
      }
      backgroundDismissedNotificationsQueue.clear();
    }
  }

  /**
   * Set the registrant callback. This is called by the app's Application class if
   * dismiss notification callback is configured.
   *
   * @param callback Application class which implements PluginRegistrantCallback.
   */
  public static void setPluginRegistrant(PluginRegistry.PluginRegistrantCallback callback) {
    pluginRegistrantCallback = callback;
  }

  /**
   * Set the background setup handle for future use. The Dart side of this plugin has a
   * method that sets up the background method channel. When ready to setup the background channel
   * the Dart side needs to be able to retrieve the setup method. This method is called by the Dart
   * side via `FcmDartService#start`.
   *
   * @param context               Registrar context.
   * @param setupBackgroundHandle Handle representing the dart side method that will setup the
   *                              background method channel.
   */
  public static void setBackgroundSetupHandle(Context context, long setupBackgroundHandle) {
    // Store background setup handle in shared preferences so it can be retrieved
    // by other application instances.
    SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
    prefs.edit().putLong(BACKGROUND_SETUP_CALLBACK_HANDLE_KEY, setupBackgroundHandle).apply();
  }

  /**
   * Setup the background isolate that would allow dismiss event to be handled on the Dart
   * side. Called either by the plugin when the app is starting up or when a dismiss
   * notification is called
   *
   * @param methodCallHandler a method call handler impl that handles the call from dart side.
   *                          {@link BackgroundIsolateMethodCallHandler}.
   * @param context           Registrar or FirebaseMessagingService context.
   * @param callbackHandle    Handle used to retrieve the Dart function that sets up background
   *                          handling on the dart side.
   */
  public static void startBackgroundIsolate(
      final BackgroundIsolateMethodCallHandler methodCallHandler,
      Context context,
      long callbackHandle) {
    if (isBackgroundInitialized.get()) {
      return;
    }

    isBackgroundInitialized.set(true);
    FlutterMain.ensureInitializationComplete(context, null);

    final FlutterCallbackInformation flutterCallback =
        FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);

    //noinspection ConstantConditions
    // lookupCallbackInformation says it won't return null, but it can;
    // for example, when callbackHandle is 0. Could be in other cases as well though
    if (flutterCallback == null) {
      Log.e(TAG, "Fatal: failed to find callback");
      isBackgroundInitialized.set(false);
      return;
    }

    // Note that we're passing `true` as the second argument to our
    // FlutterNativeView constructor. This specifies the FlutterNativeView
    // as a background view and does not create a drawing surface.
    backgroundFlutterView = new FlutterNativeView(context, true);

    backgroundChannel =
        new MethodChannel(
            backgroundFlutterView.getDartExecutor().getBinaryMessenger(),
            "dexterous.com/flutter/local_notifications_background");
    backgroundChannel.setMethodCallHandler(methodCallHandler);

    final String appBundlePath = FlutterMain.findAppBundlePath();
    if (!isIsolateRunning.get()) {
      if (pluginRegistrantCallback == null) {
        isBackgroundInitialized.set(false);
        throw new RuntimeException("PluginRegistrantCallback is not set.");
      }
      FlutterRunArguments args = new FlutterRunArguments();
      args.bundlePath = appBundlePath;
      args.entrypoint = flutterCallback.callbackName;
      args.libraryPath = flutterCallback.callbackLibraryPath;
      backgroundFlutterView.runFromBundle(args);
      pluginRegistrantCallback.registerWith(backgroundFlutterView.getPluginRegistry());
    }
  }

  private static void executeDartCallbackInBackgroundIsolate(
      Context context,
      String payload,
      final CountDownLatch latch) {
    if (backgroundChannel == null) {
      throw new RuntimeException("backgroundChannel is null, exiting.");
    }

    // If another thread is waiting, then wake that thread when the callback returns a result.
    MethodChannel.Result result = null;
    if (latch != null) {
      result = new LatchResult(latch).getResult();
    }

    final Map<String, Object> args = new HashMap<>();
    if (dismissNotificationHandle == null) {
      dismissNotificationHandle = getDismissNotificationHandle(context);
    }

    args.put("handle", dismissNotificationHandle);
    args.put("payload", payload);

    backgroundChannel.invokeMethod("handleBackgroundNotificationEvent", args, result);
  }

  public static Long getDismissNotificationHandle(Context context) {
    return context
        .getSharedPreferences(SHARED_PREFERENCES_KEY, 0)
        .getLong(DISMISS_NOTIFICATION_CALLBACK_HANDLE_KEY, 0);
  }

  public static void setDismissNotificationHandle(Context context, long handle) {
    dismissNotificationHandle = handle;

    // Store dismiss notification callback handle in shared preferences so it can be retrieved
    // by other application instances.
    SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
    prefs.edit().putLong(DISMISS_NOTIFICATION_CALLBACK_HANDLE_KEY, handle).apply();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  public static class LatchResult {
    private MethodChannel.Result result;

    public LatchResult(final CountDownLatch latch) {
      result =
          new MethodChannel.Result() {
            @Override
            public void success(Object result) {
              latch.countDown();
            }

            @Override
            public void error(String errorCode, String errorMessage, Object errorDetails) {
              latch.countDown();
            }

            @Override
            public void notImplemented() {
              latch.countDown();
            }
          };
    }

    public MethodChannel.Result getResult() {
      return result;
    }
  }

  public static class BackgroundIsolateMethodCallHandler implements MethodChannel.MethodCallHandler {
    private static final String DART_SERVICE_INITIALIZED_COMPLETE_METHOD = "localNotificationDartService#initialized";

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
      if (DART_SERVICE_INITIALIZED_COMPLETE_METHOD.equals(call.method)) {
        FlutterLocalNotificationsService.onInitialized();
        result.success(true);
      }
    }
  }
}


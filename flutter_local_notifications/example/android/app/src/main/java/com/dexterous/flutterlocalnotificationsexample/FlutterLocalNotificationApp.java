package com.dexterous.flutterlocalnotificationsexample;

import com.dexterous.flutterlocalnotifications.FlutterLocalNotificationsService;

import io.flutter.app.FlutterApplication;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.view.FlutterMain;

public class FlutterLocalNotificationApp extends FlutterApplication implements PluginRegistry.PluginRegistrantCallback {
  @Override
  public void registerWith(PluginRegistry registry) {
    FlutterLocalNotificationsPluginRegistrant.registerWith(registry);
  }

  @Override
  public void onCreate() {
    super.onCreate();

    FlutterLocalNotificationsService.setPluginRegistrant(this);
    FlutterMain.startInitialization(this);
  }
}
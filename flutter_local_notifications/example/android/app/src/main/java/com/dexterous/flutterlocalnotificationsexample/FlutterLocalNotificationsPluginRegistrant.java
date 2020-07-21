package com.dexterous.flutterlocalnotificationsexample;

import com.dexterous.flutterlocalnotifications.FlutterLocalNotificationsPlugin;
import com.dexterous.flutterlocalnotifications.FlutterLocalNotificationsService;

import io.flutter.plugin.common.PluginRegistry;

public class FlutterLocalNotificationsPluginRegistrant {
  static void registerWith(PluginRegistry registry) {
    if (alreadyRegisteredWith(registry)) {
      return;
    }
    FlutterLocalNotificationsPlugin.registerWith(registry.registrarFor(
        "com.dexterous.flutterlocalnotifications.FlutterLocalNotificationsPlugin"));
  }

  private static boolean alreadyRegisteredWith(PluginRegistry registry) {
    String key = FlutterLocalNotificationsPluginRegistrant.class.getName();
    if (registry.hasPlugin(key)) {
      return true;
    }
    registry.registrarFor(key);
    return false;
  }
}

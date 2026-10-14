package com.buddy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

public class BuddyService extends Service {
  @Override public IBinder onBind(Intent i) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && "STOP".equals(intent.getAction())) {
      Intent b = new Intent("com.buddy.app.STOP");
      b.setPackage(getPackageName());
      sendBroadcast(b);
      stopForeground(true);
      stopSelf();
      return START_NOT_STICKY;
    }
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(new NotificationChannel("buddy", "Buddy", NotificationManager.IMPORTANCE_LOW));
    Intent stop = new Intent(this, BuddyService.class);
    stop.setAction("STOP");
    PendingIntent sp = PendingIntent.getService(this, 1, stop,
      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Intent open = new Intent(this, MainActivity.class);
    PendingIntent op = PendingIntent.getActivity(this, 2, open,
      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n = new Notification.Builder(this, "buddy")
      .setContentTitle("Buddy is listening")
      .setContentText("Tap to open, or press Stop")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(op)
      .addAction(new Notification.Action.Builder(
        Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", sp).build())
      .setOngoing(true)
      .build();
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    } else {
      startForeground(1, n);
    }
    return START_NOT_STICKY;
  }
}

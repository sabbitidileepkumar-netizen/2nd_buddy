package com.buddy.app;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class BuddyAccess extends AccessibilityService {
  public static volatile long armedUntil = 0;

  @Override public void onAccessibilityEvent(AccessibilityEvent e) {
    if (System.currentTimeMillis() > armedUntil) return;
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return;
    AccessibilityNodeInfo send = null;
    String[] ids = {"com.whatsapp:id/send", "com.whatsapp.w4b:id/send"};
    for (String id : ids) {
      List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
      if (l != null && !l.isEmpty()) { send = l.get(0); break; }
    }
    if (send == null) {
      List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText("Send");
      if (l != null && !l.isEmpty()) send = l.get(0);
    }
    if (send == null) return;
    AccessibilityNodeInfo n = send;
    while (n != null && !n.isClickable()) n = n.getParent();
    if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
      armedUntil = 0;
    }
  }

  @Override public void onInterrupt() {}
}

package com.voicestudio.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class QQAccessibilityService extends AccessibilityService {
    private static final String TAG = "QQT7";
    private static final String QQ_PKG = "com.tencent.mobileqq";
    private static volatile QQAccessibilityService instance;

    public static QQAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op; this probe only acts when the overlay button is pressed.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public AccessibilityNodeInfo findQQRoot() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null && QQ_PKG.contentEquals(root.getPackageName())) {
                        return root;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getWindows failed", t);
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && QQ_PKG.contentEquals(root.getPackageName())) {
            return root;
        }
        return root;
    }

    /** QQ 聊天页顶部的标题（联系人名）；找不到返回 null。 */
    public String currentChatTitle() {
        AccessibilityNodeInfo root = findQQRoot();
        if (root == null) return null;
        int limit = dp(260);
        String best = null;
        int bestLen = 0;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            try {
                if (n.isVisibleToUser()) {
                    CharSequence txt = n.getText();
                    if (txt != null && txt.length() > 0) {
                        Rect r = new Rect();
                        n.getBoundsInScreen(r);
                        String value = txt.toString().trim();
                        if (r.top >= 0 && r.top < limit && r.width() > dp(100)
                                && value.length() > bestLen && value.length() <= 24
                                && !value.equals("发送") && !value.equals("取消")
                                && !value.equals("返回") && !value.equals("更多")) {
                            best = value;
                            bestLen = value.length();
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    public String dumpTree(String tag) {
        AccessibilityNodeInfo root = findQQRoot();
        if (root == null) return "NO_ROOT";
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        if (dir != null && !dir.exists()) dir.mkdirs();
        String name = "qq-tree-" + tag + "-"
            + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".txt";
        File f = new File(dir, name);
        StringBuilder sb = new StringBuilder();
        sb.append("rootPkg=").append(root.getPackageName()).append('\n');
        walk(root, 0, sb);
        try (FileWriter w = new FileWriter(f)) {
            w.write(sb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "dumpTree failed", t);
            return "ERR:" + t;
        }
        Log.i(TAG, "dumpTree " + f.getAbsolutePath());
        return f.getAbsolutePath();
    }

    private void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 50 || sb.length() > 300000) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < Math.min(depth, 30); i++) indent.append("  ");
        sb.append(indent)
            .append("class=").append(n.getClassName())
            .append(" text=").append(oneLine(n.getText()))
            .append(" desc=").append(oneLine(n.getContentDescription()))
            .append(" id=").append(n.getViewIdResourceName())
            .append(" bounds=").append(r.left).append(',').append(r.top).append(',')
            .append(r.right).append(',').append(r.bottom)
            .append(" click=").append(n.isClickable())
            .append(" long=").append(n.isLongClickable())
            .append(" vis=").append(n.isVisibleToUser())
            .append('\n');
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            walk(n.getChild(i), depth + 1, sb);
        }
    }

    private static String oneLine(CharSequence s) {
        if (s == null) return "";
        return s.toString().replace('\n', ' ').replace('\r', ' ');
    }

    public int[] findVoiceButton() {
        AccessibilityNodeInfo root = findQQRoot();
        if (root == null) return null;

        AccessibilityNodeInfo label = null;
        int labelArea = -1;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            String text = (n.getText() == null ? "" : n.getText().toString()) + " "
                + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
            if (n.isVisibleToUser() && text.contains("按住")
                && (text.contains("说话") || text.contains("变声"))) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                int area = r.width() * r.height();
                if (area > labelArea) {
                    label = n;
                    labelArea = area;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        if (label == null) return null;

        Rect lr = new Rect();
        label.getBoundsInScreen(lr);

        // QQ 的“按住说话”文字只是标签；真正按住的是它下方的大按钮。
        AccessibilityNodeInfo target = null;
        int bestArea = -1;
        AccessibilityNodeInfo parent = label.getParent();
        if (parent != null) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                AccessibilityNodeInfo c = parent.getChild(i);
                if (c == null || !c.isClickable() || !c.isVisibleToUser()) continue;
                Rect cr = new Rect();
                c.getBoundsInScreen(cr);
                int cy = cr.centerY();
                if (cy < lr.centerY() || cy > lr.bottom + 600) continue;
                int area = cr.width() * cr.height();
                if (area > bestArea) {
                    target = c;
                    bestArea = area;
                }
            }
        }
        if (target == null) {
            ArrayDeque<AccessibilityNodeInfo> q2 = new ArrayDeque<>();
            q2.add(root);
            while (!q2.isEmpty()) {
                AccessibilityNodeInfo n = q2.poll();
                if (n == null) continue;
                if (n.isClickable() && n.isVisibleToUser()) {
                    Rect cr = new Rect();
                    n.getBoundsInScreen(cr);
                    int cy = cr.centerY();
                    int cx = cr.centerX();
                    if (cy >= lr.centerY() && cy <= lr.bottom + 600
                        && cx >= lr.left - 400 && cx <= lr.right + 400) {
                        int area = cr.width() * cr.height();
                        if (area > bestArea) {
                            target = n;
                            bestArea = area;
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) q2.add(n.getChild(i));
            }
        }

        int x;
        int y;
        if (target != null) {
            Rect tr = new Rect();
            target.getBoundsInScreen(tr);
            x = tr.centerX();
            y = tr.centerY();
            Log.i(TAG, "voice target=" + tr + " label=" + lr);
        } else {
            x = lr.centerX();
            y = lr.bottom + Math.max(120, lr.height() * 3);
            Log.i(TAG, "voice fallback=" + x + "," + y + " label=" + lr);
        }
        return new int[]{x, y};
    }

    public boolean clickText(String target) {
        AccessibilityNodeInfo root = findQQRoot();
        if (root == null) return false;

        // Pass 1: exact match. Pass 2: contains (safer first).
        for (int pass = 0; pass < 2; pass++) {
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo n = queue.poll();
                if (n == null) continue;
                String text = (n.getText() == null ? "" : n.getText().toString()) + " "
                    + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
                boolean hit = pass == 0 ? text.trim().equals(target) : text.contains(target);
                if (hit) {
                    AccessibilityNodeInfo c = n;
                    for (int i = 0; i < 3 && c != null; i++) {
                        if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "clicked text=" + c.getText() + " desc=" + c.getContentDescription());
                            return true;
                        }
                        c = c.getParent();
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
            }
        }
        return false;
    }

    public boolean clickDesc(String target) {
        AccessibilityNodeInfo root = findQQRoot();
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            CharSequence d = n.getContentDescription();
            if (n.isVisibleToUser() && d != null && d.toString().equals(target)) {
                AccessibilityNodeInfo c = n;
                for (int i = 0; i < 3 && c != null; i++) {
                    if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "clicked desc=" + target);
                        return true;
                    }
                    c = c.getParent();
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return false;
    }

    public boolean longPress(int x, int y, long durationMs) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(p, 0, Math.max(100, durationMs));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "gesture completed");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "gesture cancelled");
            }
        }, new Handler(Looper.getMainLooper()));
        Log.i(TAG, "longPress " + x + "," + y + " " + durationMs + "ms -> " + ok);
        return ok;
    }

    public boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(p, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }
}

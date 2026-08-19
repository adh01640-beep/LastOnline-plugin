package com.aliucord.plugins;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.DimenUtils;
import com.aliucord.utils.ReflectUtils;
import com.discord.models.user.User;
import com.discord.stores.StoreStream;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@AliucordPlugin
public class LastOnline extends Plugin {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yy, HH:mm:ss", Locale.getDefault());
    private static final String LAST_ONLINE_TAG = "BETTER_USER_DETAILS_LAST_ONLINE_ROW";

    @Override
    public void start(Context context) throws Throwable {
        // 1. Track live Gateway presence updates
        try {
            for (Method method : StoreStream.getPresences().getClass().getDeclaredMethods()) {
                if (method.getName().equals("handlePresenceUpdate") || method.getName().equals("onPresencesLoaded")) {
                    method.setAccessible(true);
                    patcher.patch(method, new Hook(param -> {
                        try {
                            if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                                processPresence(param.args[0]);
                            }
                        } catch (Throwable ignored) {}
                    }));
                }
            }
        } catch (Throwable ignored) {}

        // 2. Hook WidgetUserSheet.configureNote (Matching BetterUserDetails's insertion lifecycle)
        ClassLoader cl = context.getClassLoader();
        Class<?> userSheetClass = cl.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheet");
        Class<?> loadedClass = cl.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheetViewModel$ViewState$Loaded");

        Method configureNoteMethod = userSheetClass.getDeclaredMethod("configureNote", loadedClass);
        configureNoteMethod.setAccessible(true);

        patcher.patch(configureNoteMethod, new Hook(param -> {
            try {
                Object sheet = param.thisObject;
                Object loadedState = param.args[0];
                if (sheet == null || loadedState == null) return;

                User user = (User) ReflectUtils.invokeMethod(loadedState, "getUser");
                if (user == null) {
                    user = (User) ReflectUtils.getField(loadedState, "user");
                }
                if (user == null) return;

                long userId = user.getId();

                Object binding = ReflectUtils.getField(sheet, "binding");
                if (binding == null) return;

                View aboutMeCard = (View) ReflectUtils.getField(binding, "aboutMeCard");
                if (aboutMeCard == null || !(aboutMeCard.getParent() instanceof ViewGroup)) return;

                ViewGroup content = (ViewGroup) aboutMeCard.getParent();
                renderLastOnline(content, aboutMeCard, userId);
            } catch (Throwable ignored) {}
        }));
    }

    private void processPresence(Object data) {
        try {
            if (data instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) data;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    long uid = 0L;
                    if (entry.getKey() instanceof Number) {
                        uid = ((Number) entry.getKey()).longValue();
                    } else if (entry.getKey() instanceof String) {
                        try {
                            uid = Long.parseLong((String) entry.getKey());
                        } catch (Throwable ignored) {}
                    }
                    saveIfOnline(uid, entry.getValue());
                }
            } else {
                long uid = 0L;
                try {
                    Object user = ReflectUtils.invokeMethod(data, "getUser");
                    if (user instanceof User) uid = ((User) user).getId();
                } catch (Throwable ignored) {}

                if (uid == 0L) {
                    try {
                        Object idObj = ReflectUtils.getField(data, "userId");
                        if (idObj instanceof Number) uid = ((Number) idObj).longValue();
                    } catch (Throwable ignored) {}
                }

                if (uid != 0L) {
                    saveIfOnline(uid, data);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void saveIfOnline(long userId, Object presenceObj) {
        if (presenceObj == null || userId == 0L) return;
        String status = String.valueOf(presenceObj).toLowerCase(Locale.ROOT);
        if (status.contains("online") || status.contains("idle") || status.contains("dnd")) {
            settings.setLong(String.valueOf(userId), System.currentTimeMillis());
        }
    }

    private String toRelativeTime(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 60000) return "Active Now";
        long days = TimeUnit.MILLISECONDS.toDays(diff);
        if (days == 0L) {
            return dateFormat.format(new Date(timestamp)) + " (Today)";
        } else if (days == 1L) {
            return dateFormat.format(new Date(timestamp)) + " (Yesterday)";
        } else {
            return dateFormat.format(new Date(timestamp)) + " (" + days + " days ago)";
        }
    }

    private void renderLastOnline(ViewGroup parent, View anchor, long userId) {
        try {
            Context ctx = parent.getContext();
            long lastSeen = settings.getLong(String.valueOf(userId), 0L);

            // Check Discord memory presence
            try {
                Map<?, ?> presences = (Map<?, ?>) ReflectUtils.invokeMethod(StoreStream.getPresences(), "getPresences");
                if (presences != null && presences.containsKey(userId)) {
                    Object p = presences.get(userId);
                    String str = String.valueOf(p).toLowerCase(Locale.ROOT);
                    if (str.contains("online") || str.contains("idle") || str.contains("dnd")) {
                        lastSeen = System.currentTimeMillis();
                        settings.setLong(String.valueOf(userId), lastSeen);
                    }
                }
            } catch (Throwable ignored) {}

            String displayText;
            if (lastSeen > 0L) {
                displayText = "Last online: " + toRelativeTime(lastSeen);
            } else {
                displayText = "Last online: Unknown";
            }

            // Find BetterUserDetails container or fallback to parent container
            ViewGroup targetContainer = parent;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child instanceof LinearLayout && child != anchor) {
                    targetContainer = (ViewGroup) child;
                    break;
                }
            }

            TextView tv = targetContainer.findViewWithTag(LAST_ONLINE_TAG);
            if (tv == null) {
                tv = parent.findViewWithTag(LAST_ONLINE_TAG);
                if (tv != null && tv.getParent() instanceof ViewGroup) {
                    ((ViewGroup) tv.getParent()).removeView(tv);
                    tv = null;
                }
            }

            if (tv == null) {
                tv = new TextView(ctx);
                tv.setTag(LAST_ONLINE_TAG);
                tv.setTextSize(12f);
                tv.setTextColor(Color.parseColor("#B9BBBE"));

                if (targetContainer != parent) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lp.topMargin = DimenUtils.dpToPx(1);
                    tv.setLayoutParams(lp);
                    targetContainer.addView(tv);
                } else {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    int padH = DimenUtils.dpToPx(16);
                    int padV = DimenUtils.dpToPx(2);
                    tv.setPadding(padH, 0, padH, padV);
                    tv.setLayoutParams(lp);

                    int index = parent.indexOfChild(anchor);
                    if (index >= 0) {
                        parent.addView(tv, index);
                    } else {
                        parent.addView(tv);
                    }
                }
            }

            tv.setText(displayText);
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

package com.aliucord.plugins;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.DimenUtils;
import com.aliucord.utils.ReflectUtils;
import com.discord.models.user.User;
import com.discord.stores.StoreStream;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

@AliucordPlugin
public class LastOnline extends Plugin {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yy, HH:mm:ss", Locale.getDefault());
    private static final int VIEW_ID = 0x7f090099;

    @Override
    public void start(Context context) throws Throwable {
        // 1. تتبع StoreUserPresence لتسجيل الحالات الحية
        try {
            patcher.patch(
                StoreStream.getPresences().getClass().getDeclaredMethod("onPresencesLoaded", Map.class),
                new Hook(param -> recordPresences(param.args[0]))
            );
        } catch (Throwable ignored) {}

        try {
            for (java.lang.reflect.Method m : StoreStream.getPresences().getClass().getDeclaredMethods()) {
                if (m.getName().equals("handlePresenceUpdate")) {
                    patcher.patch(m, new Hook(param -> {
                        if (param.args.length > 0 && param.args[0] != null) {
                            extractAndSavePresence(param.args[0]);
                        }
                    }));
                }
            }
        } catch (Throwable ignored) {}

        // 2. نفس Hook إضافة BetterUserDetails بالضبط (WidgetUserSheet.configureNote)
        ClassLoader classLoader = context.getClassLoader();
        Class<?> userSheetClass = classLoader.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheet");
        Class<?> loadedClass = classLoader.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheetViewModel$ViewState$Loaded");

        patcher.patch(
            userSheetClass.getDeclaredMethod("configureNote", loadedClass),
            new Hook(param -> {
                try {
                    Object sheet = param.thisObject;
                    Object loadedState = param.args[0];
                    if (loadedState == null) return;

                    User user = (User) ReflectUtils.invokeMethod(loadedState, "getUser");
                    if (user == null) return;

                    long userId = user.getId();

                    // الوصول للـ View عبر Binding
                    Object binding = ReflectUtils.getField(sheet, "binding");
                    if (binding == null) return;

                    View aboutMeCard = (View) ReflectUtils.getField(binding, "aboutMeCard");
                    if (aboutMeCard == null || !(aboutMeCard.getParent() instanceof ViewGroup)) return;

                    ViewGroup parent = (ViewGroup) aboutMeCard.getParent();
                    parent.post(() -> injectRow(parent, aboutMeCard, userId));
                } catch (Throwable ignored) {}
            })
        );
    }

    private void recordPresences(Object mapObj) {
        if (!(mapObj instanceof Map)) return;
        Map<?, ?> map = (Map<?, ?>) mapObj;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                long uid = 0L;
                if (entry.getKey() instanceof Number) {
                    uid = ((Number) entry.getKey()).longValue();
                } else if (entry.getKey() instanceof String) {
                    try {
                        uid = Long.parseLong((String) entry.getKey());
                    } catch (Throwable ignored) {}
                }
                saveIfActive(uid, entry.getValue());
            }
        }
    }

    private void extractAndSavePresence(Object presenceObj) {
        try {
            long uid = 0L;
            try {
                Object user = ReflectUtils.invokeMethod(presenceObj, "getUser");
                if (user instanceof User) {
                    uid = ((User) user).getId();
                }
            } catch (Throwable ignored) {}

            if (uid == 0L) {
                try {
                    Object idObj = ReflectUtils.getField(presenceObj, "userId");
                    if (idObj instanceof Number) uid = ((Number) idObj).longValue();
                } catch (Throwable ignored) {}
            }

            if (uid != 0L) {
                saveIfActive(uid, presenceObj);
            }
        } catch (Throwable ignored) {}
    }

    private void saveIfActive(long userId, Object presenceObj) {
        if (presenceObj == null || userId == 0L) return;
        String status = String.valueOf(presenceObj).toLowerCase(Locale.ROOT);
        if (status.contains("online") || status.contains("idle") || status.contains("dnd")) {
            settings.setLong(String.valueOf(userId), System.currentTimeMillis());
        }
    }

    private void injectRow(ViewGroup parent, View anchor, long userId) {
        try {
            Context ctx = parent.getContext();
            TextView tv = parent.findViewById(VIEW_ID);

            long lastSeen = settings.getLong(String.valueOf(userId), 0L);
            String text;

            if (lastSeen > 0) {
                long diff = System.currentTimeMillis() - lastSeen;
                long days = diff / (1000 * 60 * 60 * 24);

                if (diff < 60000) {
                    text = "Last online: Active Now";
                } else if (days == 0) {
                    text = "Last online: " + dateFormat.format(new Date(lastSeen)) + " (Today)";
                } else {
                    text = "Last online: " + dateFormat.format(new Date(lastSeen)) + " (" + days + " days ago)";
                }
            } else {
                text = "Last online: Unknown (No data recorded)";
            }

            if (tv == null) {
                tv = new TextView(ctx);
                tv.setId(VIEW_ID);
                tv.setTextSize(12f);
                tv.setTextColor(Color.parseColor("#B9BBBE"));

                int padStart = DimenUtils.dpToPx(16);
                int padBottom = DimenUtils.dpToPx(2);
                tv.setPadding(padStart, 0, padStart, padBottom);

                // وضعه مباشرة قبل كارت About Me ليظهر أسفل Last message
                int index = parent.indexOfChild(anchor);
                parent.addView(tv, Math.max(0, index));
            }

            tv.setText(text);
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

@AliucordPlugin
public class LastOnline extends Plugin {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yy, HH:mm:ss", Locale.getDefault());
    private static final int VIEW_ID = View.generateViewId();

    @Override
    public void start(Context context) throws Throwable {
        // 1. Presence Tracker
        try {
            for (java.lang.reflect.Method m : StoreStream.getPresences().getClass().getDeclaredMethods()) {
                if (m.getName().equals("handlePresenceUpdate") || m.getName().equals("onPresencesLoaded")) {
                    patcher.patch(m, new Hook(param -> {
                        if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                            processPresence(param.args[0]);
                        }
                    }));
                }
            }
        } catch (Throwable ignored) {}

        // 2. Exact BetterUserDetails Hook
        ClassLoader classLoader = context.getClassLoader();
        Class<?> userSheetClass = classLoader.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheet");
        Class<?> loadedClass = classLoader.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheetViewModel$ViewState$Loaded");

        // Hook configureUI & configureNote
        for (java.lang.reflect.Method method : userSheetClass.getDeclaredMethods()) {
            if (method.getName().equals("configureUI") || method.getName().equals("configureNote")) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0].isAssignableFrom(loadedClass)) {
                    patcher.patch(method, new Hook(param -> {
                        try {
                            Object sheet = param.thisObject;
                            Object loadedState = param.args[0];
                            if (sheet == null || loadedState == null) return;

                            User user = (User) ReflectUtils.invokeMethod(loadedState, "getUser");
                            if (user == null) return;

                            long userId = user.getId();

                            // Retrieve Discord ViewBinding
                            Object binding = ReflectUtils.getField(sheet, "binding");
                            if (binding == null) return;

                            View aboutMeCard = (View) ReflectUtils.getField(binding, "aboutMeCard");
                            if (aboutMeCard == null || !(aboutMeCard.getParent() instanceof ViewGroup)) return;

                            ViewGroup parentContainer = (ViewGroup) aboutMeCard.getParent();
                            parentContainer.post(() -> renderRow(parentContainer, aboutMeCard, userId));
                        } catch (Throwable ignored) {}
                    }));
                }
            }
        }
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
                        try { uid = Long.parseLong((String) entry.getKey()); } catch (Throwable ignored) {}
                    }
                    saveIfActive(uid, entry.getValue());
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
                    saveIfActive(uid, data);
                }
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

    private void renderRow(ViewGroup parentContainer, View aboutMeCard, long userId) {
        try {
            Context ctx = parentContainer.getContext();
            TextView tv = parentContainer.findViewById(VIEW_ID);

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

                // Check if BetterUserDetails LinearLayout container exists
                ViewGroup targetParent = parentContainer;
                int targetIndex = parentContainer.indexOfChild(aboutMeCard);

                // Look for BetterUserDetails container (it inserts a LinearLayout right before aboutMeCard)
                for (int i = 0; i < parentContainer.getChildCount(); i++) {
                    View child = parentContainer.getChildAt(i);
                    if (child instanceof LinearLayout && child != aboutMeCard) {
                        targetParent = (LinearLayout) child;
                        targetIndex = -1; // Append inside BetterUserDetails container
                        break;
                    }
                }

                if (targetParent == parentContainer) {
                    int padStart = DimenUtils.dpToPx(16);
                    int padBottom = DimenUtils.dpToPx(2);
                    tv.setPadding(padStart, 0, padStart, padBottom);
                    if (targetIndex >= 0) {
                        parentContainer.addView(tv, targetIndex);
                    } else {
                        parentContainer.addView(tv);
                    }
                } else {
                    targetParent.addView(tv);
                }
            }

            tv.setText(text);
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

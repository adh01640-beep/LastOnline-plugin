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
    private final int lastOnlineViewId = View.generateViewId();

    @Override
    public void start(Context context) throws Throwable {
        // 1. Presence Listener
        try {
            for (java.lang.reflect.Method method : StoreStream.getPresences().getClass().getDeclaredMethods()) {
                if (method.getName().equals("handlePresenceUpdate") || method.getName().equals("onPresencesLoaded")) {
                    patcher.patch(method, new Hook(param -> {
                        try {
                            if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                                processPresences(param.args[0]);
                            }
                        } catch (Throwable ignored) {}
                    }));
                }
            }
        } catch (Throwable ignored) {}

        // 2. Exact BetterUserDetails hook: WidgetUserSheet.configureNote
        ClassLoader cl = context.getClassLoader();
        Class<?> userSheetClass = cl.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheet");
        Class<?> loadedClass = cl.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheetViewModel$ViewState$Loaded");

        patcher.patch(
            userSheetClass.getDeclaredMethod("configureNote", loadedClass),
            new Hook(param -> {
                try {
                    Object sheet = param.thisObject;
                    Object loadedState = param.args[0];
                    if (sheet == null || loadedState == null) return;

                    // Extract User
                    User user = (User) ReflectUtils.invokeMethod(loadedState, "getUser");
                    if (user == null) return;

                    long userId = user.getId();

                    // Access binding
                    Object binding = ReflectUtils.getField(sheet, "binding");
                    if (binding == null) return;

                    // Access aboutMeCard and its parent container (user_sheet_content)
                    View aboutMeCard = (View) ReflectUtils.getField(binding, "aboutMeCard");
                    if (aboutMeCard == null || !(aboutMeCard.getParent() instanceof ViewGroup)) return;

                    ViewGroup parent = (ViewGroup) aboutMeCard.getParent();

                    // Render LastOnline
                    renderLastOnline(parent, aboutMeCard, userId);
                } catch (Throwable ignored) {}
            })
        );
    }

    private void processPresences(Object data) {
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
                    checkAndStore(uid, entry.getValue());
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
                    checkAndStore(uid, data);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void checkAndStore(long userId, Object presenceObj) {
        if (presenceObj == null || userId == 0L) return;
        String status = String.valueOf(presenceObj).toLowerCase(Locale.ROOT);
        if (status.contains("online") || status.contains("idle") || status.contains("dnd")) {
            settings.setLong(String.valueOf(userId), System.currentTimeMillis());
        }
    }

    private void renderLastOnline(ViewGroup parent, View aboutMeCard, long userId) {
        try {
            Context ctx = parent.getContext();

            // Find or create the view
            TextView tv = parent.findViewById(lastOnlineViewId);

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
                tv.setId(lastOnlineViewId);
                tv.setTextSize(12f);
                tv.setTextColor(Color.parseColor("#B9BBBE"));

                // Check if BetterUserDetails created its container
                LinearLayout betterUserDetailsContainer = null;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    if (child instanceof LinearLayout && child != aboutMeCard) {
                        betterUserDetailsContainer = (LinearLayout) child;
                        break;
                    }
                }

                if (betterUserDetailsContainer != null) {
                    // Append inside BetterUserDetails container as the last row
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lp.topMargin = DimenUtils.dpToPx(2);
                    tv.setLayoutParams(lp);
                    betterUserDetailsContainer.addView(tv);
                } else {
                    // Standalone placement right before aboutMeCard
                    int padH = DimenUtils.dpToPx(16);
                    int padV = DimenUtils.dpToPx(2);
                    tv.setPadding(padH, padV, padH, padV);

                    int index = parent.indexOfChild(aboutMeCard);
                    if (index >= 0) {
                        parent.addView(tv, index);
                    } else {
                        parent.addView(tv);
                    }
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

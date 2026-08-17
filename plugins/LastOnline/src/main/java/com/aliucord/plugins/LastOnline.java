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

@AliucordPlugin
public class LastOnline extends Plugin {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yy, HH:mm:ss", Locale.getDefault());
    private static final int VIEW_ID = 0x7f098855;

    @Override
    public void start(Context context) throws Throwable {
        // 1. Hook Gateway presence updates
        try {
            for (Method m : StoreStream.getPresences().getClass().getDeclaredMethods()) {
                if (m.getName().equals("handlePresenceUpdate") || m.getName().equals("onPresencesLoaded")) {
                    m.setAccessible(true);
                    patcher.patch(m, new Hook(param -> {
                        try {
                            if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                                savePresenceUpdates(param.args[0]);
                            }
                        } catch (Throwable ignored) {}
                    }));
                }
            }
        } catch (Throwable ignored) {}

        // 2. Direct Hook on WidgetUserProfileSheet onViewBound (Root container)
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> sheetClass = cl.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheet");

            for (Method m : sheetClass.getDeclaredMethods()) {
                if (m.getName().equals("onViewBound") || m.getName().equals("configureUI") || m.getName().equals("configureNote")) {
                    m.setAccessible(true);
                    patcher.patch(m, new Hook(param -> {
                        try {
                            Object target = param.thisObject;
                            View sheetRoot = (View) ReflectUtils.invokeMethod(target, "requireView");
                            if (sheetRoot != null) {
                                sheetRoot.post(() -> attachLastOnline(target, sheetRoot));
                            }
                        } catch (Throwable ignored) {}
                    }));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void savePresenceUpdates(Object data) {
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
                    recordTime(uid, entry.getValue());
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
                    recordTime(uid, data);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void recordTime(long userId, Object presenceObj) {
        if (presenceObj == null || userId == 0L) return;
        String status = String.valueOf(presenceObj).toLowerCase(Locale.ROOT);
        if (status.contains("online") || status.contains("idle") || status.contains("dnd")) {
            settings.setLong(String.valueOf(userId), System.currentTimeMillis());
        }
    }

    private void attachLastOnline(Object sheet, View sheetRoot) {
        try {
            Context ctx = sheetRoot.getContext();

            // Extract User ID
            long userId = 0L;
            try {
                Object binding = ReflectUtils.getField(sheet, "binding");
                Object viewModel = ReflectUtils.getField(sheet, "viewModel");
                Object viewState = ReflectUtils.invokeMethod(viewModel, "getViewState");
                User user = (User) ReflectUtils.invokeMethod(viewState, "getUser");
                if (user != null) userId = user.getId();
            } catch (Throwable ignored) {}

            if (userId == 0L) {
                try {
                    android.os.Bundle args = (android.os.Bundle) ReflectUtils.invokeMethod(sheet, "getArguments");
                    if (args != null) {
                        if (args.containsKey("USER_ID")) userId = args.getLong("USER_ID");
                        else if (args.containsKey("user_id")) userId = args.getLong("user_id");
                    }
                } catch (Throwable ignored) {}
            }

            if (userId == 0L) return;

            // Find current presence or last seen
            long lastSeen = settings.getLong(String.valueOf(userId), 0L);
            try {
                Map<?, ?> activeMap = (Map<?, ?>) ReflectUtils.invokeMethod(StoreStream.getPresences(), "getPresences");
                if (activeMap != null && activeMap.containsKey(userId)) {
                    Object p = activeMap.get(userId);
                    String str = String.valueOf(p).toLowerCase(Locale.ROOT);
                    if (str.contains("online") || str.contains("idle") || str.contains("dnd")) {
                        lastSeen = System.currentTimeMillis();
                        settings.setLong(String.valueOf(userId), lastSeen);
                    }
                }
            } catch (Throwable ignored) {}

            String text;
            if (lastSeen > 0) {
                long diff = System.currentTimeMillis() - lastSeen;
                long days = diff / (1000L * 60 * 60 * 24);

                if (diff < 60000) {
                    text = "Last online: Active Now";
                } else if (days == 0) {
                    text = "Last online: " + dateFormat.format(new Date(lastSeen)) + " (Today)";
                } else {
                    text = "Last online: " + dateFormat.format(new Date(lastSeen)) + " (" + days + " days ago)";
                }
            } else {
                text = "Last online: Unknown (No activity logged)";
            }

            // Find insertion container: search for about_me_card or user_sheet_content
            int aboutMeId = ctx.getResources().getIdentifier("about_me_card", "id", ctx.getPackageName());
            int contentId = ctx.getResources().getIdentifier("user_sheet_content", "id", ctx.getPackageName());

            View aboutMeCard = (aboutMeId != 0) ? sheetRoot.findViewById(aboutMeId) : null;
            ViewGroup container = null;

            if (contentId != 0) {
                View cv = sheetRoot.findViewById(contentId);
                if (cv instanceof ViewGroup) container = (ViewGroup) cv;
            }

            if (container == null && aboutMeCard != null && aboutMeCard.getParent() instanceof ViewGroup) {
                container = (ViewGroup) aboutMeCard.getParent();
            }

            if (container == null) return;

            TextView tv = container.findViewById(VIEW_ID);
            if (tv == null) {
                tv = new TextView(ctx);
                tv.setId(VIEW_ID);
                tv.setTextSize(12f);
                tv.setTextColor(Color.parseColor("#B9BBBE"));

                int padH = DimenUtils.dpToPx(16);
                int padV = DimenUtils.dpToPx(2);
                tv.setPadding(padH, padV, padH, padV);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                tv.setLayoutParams(lp);

                if (aboutMeCard != null && container.indexOfChild(aboutMeCard) != -1) {
                    container.addView(tv, container.indexOfChild(aboutMeCard));
                } else {
                    container.addView(tv);
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

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
    private static final int VIEW_ID = 0x7f090099;

    @Override
    public void start(Context context) throws Throwable {
        // 1. Hook StoreUserPresence to record real-time gateway presence updates
        try {
            for (java.lang.reflect.Method m : StoreStream.getPresences().getClass().getDeclaredMethods()) {
                if (m.getName().equals("handlePresenceUpdate") || m.getName().equals("onPresencesLoaded")) {
                    patcher.patch(m, new Hook(param -> {
                        if (param.args.length > 0 && param.args[0] != null) {
                            processPresenceData(param.args[0]);
                        }
                    }));
                }
            }
        } catch (Throwable ignored) {}

        // 2. Exact hook used to render profile details in the user sheet
        ClassLoader classLoader = context.getClassLoader();
        Class<?> userSheetClass = classLoader.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheet");

        for (java.lang.reflect.Method method : userSheetClass.getDeclaredMethods()) {
            if (method.getName().equals("onViewBound")) {
                patcher.patch(method, new Hook(param -> {
                    try {
                        Object sheet = param.thisObject;
                        View rootView = (View) param.args[0];
                        hookSheetBinding(sheet, rootView);
                    } catch (Throwable ignored) {}
                }));
            }
        }
    }

    private void processPresenceData(Object data) {
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

    private void hookSheetBinding(Object sheet, View rootView) {
        rootView.post(() -> {
            try {
                // Retrieve the user ID currently open in the sheet
                long userId = 0L;
                try {
                    Object viewModel = ReflectUtils.getField(sheet, "viewModel");
                    Object viewState = ReflectUtils.invokeMethod(viewModel, "getViewState");
                    Object user = ReflectUtils.invokeMethod(viewState, "getUser");
                    if (user instanceof User) userId = ((User) user).getId();
                } catch (Throwable ignored) {}

                if (userId == 0L) {
                    android.os.Bundle args = (android.os.Bundle) ReflectUtils.invokeMethod(sheet, "getArguments");
                    if (args != null) {
                        if (args.containsKey("USER_ID")) userId = args.getLong("USER_ID");
                        else if (args.containsKey("user_id")) userId = args.getLong("user_id");
                    }
                }

                if (userId == 0L) return;

                // Find user_sheet_content and about_me_card directly in the view tree
                Context ctx = rootView.getContext();
                int contentId = ctx.getResources().getIdentifier("user_sheet_content", "id", ctx.getPackageName());
                int aboutMeId = ctx.getResources().getIdentifier("about_me_card", "id", ctx.getPackageName());

                ViewGroup targetContainer = null;
                View aboutMeCard = null;

                if (contentId != 0) {
                    View cv = rootView.findViewById(contentId);
                    if (cv instanceof ViewGroup) targetContainer = (ViewGroup) cv;
                }

                if (aboutMeId != 0) {
                    aboutMeCard = rootView.findViewById(aboutMeId);
                    if (aboutMeCard != null && targetContainer == null && aboutMeCard.getParent() instanceof ViewGroup) {
                        targetContainer = (ViewGroup) aboutMeCard.getParent();
                    }
                }

                if (targetContainer == null) return;

                TextView tv = targetContainer.findViewById(VIEW_ID);

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

                    int padH = DimenUtils.dpToPx(16);
                    int padV = DimenUtils.dpToPx(2);
                    tv.setPadding(padH, padV, padH, padV);

                    if (aboutMeCard != null && targetContainer.indexOfChild(aboutMeCard) != -1) {
                        int index = targetContainer.indexOfChild(aboutMeCard);
                        targetContainer.addView(tv, index);
                    } else {
                        targetContainer.addView(tv);
                    }
                }

                tv.setText(text);
            } catch (Throwable ignored) {}
        });
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

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
    private static final String LAST_ONLINE_TAG = "BETTER_USER_DETAILS_LAST_ONLINE";

    @Override
    public void start(Context context) throws Throwable {
        // 1. Gateway presence updates tracker
        try {
            for (Method m : StoreStream.getPresences().getClass().getDeclaredMethods()) {
                if (m.getName().equals("handlePresenceUpdate") || m.getName().equals("onPresencesLoaded")) {
                    m.setAccessible(true);
                    patcher.patch(m, new Hook(param -> {
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

        Method configureNoteMethod = userSheetClass.getDeclaredMethod("configureNote", loadedClass);
        configureNoteMethod.setAccessible(true);

        patcher.patch(configureNoteMethod, new Hook(param -> {
            try {
                Object sheet = param.thisObject;
                Object loadedState = param.args[0];
                if (sheet == null || loadedState == null) return;

                // Extract User
                User user = (User) ReflectUtils.invokeMethod(loadedState, "getUser");
                if (user == null) {
                    user = (User) ReflectUtils.getField(loadedState, "user");
                }
                if (user == null) return;

                long userId = user.getId();

                // Access WidgetUserSheetBinding
                Object binding = ReflectUtils.getField(sheet, "binding");
                if (binding == null) return;

                View aboutMeCard = (View) ReflectUtils.getField(binding, "aboutMeCard");
                if (aboutMeCard == null || !(aboutMeCard.getParent() instanceof ViewGroup)) return;

                ViewGroup contentContainer = (ViewGroup) aboutMeCard.getParent();

                // Render in UI pass
                renderEntry(contentContainer, aboutMeCard, userId);
            } catch (Throwable ignored) {}
        }));
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

    private String toRelativeTime(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 60000) {
            return "Active Now";
        }
        long days = TimeUnit.MILLISECONDS.toDays(diff);
        if (days == 0) {
            return dateFormat.format(new Date(timestamp)) + " (Today)";
        } else if (days == 1) {
            return dateFormat.format(new Date(timestamp)) + " (Yesterday)";
        } else {
            return dateFormat.format(new Date(timestamp)) + " (" + days + " days ago)";
        }
    }

    private void renderEntry(ViewGroup contentContainer, View aboutMeCard, long userId) {
        try {
            Context ctx = contentContainer.getContext();

            // 1. Check live memory cache
            long lastSeen = settings.getLong(String.valueOf(userId), 0L);
            try {
                Map<?, ?> liveMap = (Map<?, ?>) ReflectUtils.invokeMethod(StoreStream.getPresences(), "getPresences");
                if (liveMap != null && liveMap.containsKey(userId)) {
                    Object p = liveMap.get(userId);
                    String str = String.valueOf(p).toLowerCase(Locale.ROOT);
                    if (str.contains("online") || str.contains("idle") || str.contains("dnd")) {
                        lastSeen = System.currentTimeMillis();
                        settings.setLong(String.valueOf(userId), lastSeen);
                    }
                }
            } catch (Throwable ignored) {}

            String formattedText;
            if (lastSeen > 0) {
                formattedText = "Last online: " + toRelativeTime(lastSeen);
            } else {
                formattedText = "Last online: Unknown";
            }

            // 2. Identify BetterUserDetails LinearLayout container
            LinearLayout budContainer = null;
            for (int i = 0; i < contentContainer.getChildCount(); i++) {
                View child = contentContainer.getChildAt(i);
                if (child instanceof LinearLayout && child != aboutMeCard) {
                    budContainer = (LinearLayout) child;
                    break;
                }
            }

            ViewGroup targetParent = (budContainer != null) ? budContainer : contentContainer;
            TextView tv = targetParent.findViewWithTag(LAST_ONLINE_TAG);

            if (tv == null) {
                tv = new TextView(ctx);
                tv.setTag(LAST_ONLINE_TAG);
                tv.setTextSize(12f);
                tv.setTextColor(Color.parseColor("#B9BBBE"));

                if (targetParent == budContainer) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lp.topMargin = DimenUtils.dpToPx(1);
                    tv.setLayoutParams(lp);
                    targetParent.addView(tv);
                } else {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    int padStart = DimenUtils.dpToPx(16);
                    int padBottom = DimenUtils.dpToPx(2);
                    tv.setPadding(padStart, 0, padStart, padBottom);
                    tv.setLayoutParams(lp);

                    int index = contentContainer.indexOfChild(aboutMeCard);
                    if (index >= 0) {
                        contentContainer.addView(tv, index);
                    } else {
                        contentContainer.addView(tv);
                    }
                }
            }

            tv.setText(formattedText);
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

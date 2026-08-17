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
    private static final int LAST_ONLINE_VIEW_ID = View.generateViewId();

    @Override
    public void start(Context context) throws Throwable {
        // 1. تتبع الـ Presence لتسجيل التوقيت
        try {
            patcher.patch(
                StoreStream.getPresences().getClass().getDeclaredMethod("handlePresenceUpdate", Map.class),
                new Hook(param -> {
                    try {
                        Map<?, ?> updates = (Map<?, ?>) param.args[0];
                        if (updates == null) return;

                        long now = System.currentTimeMillis();
                        for (Map.Entry<?, ?> entry : updates.entrySet()) {
                            Object key = entry.getKey();
                            Object val = entry.getValue();

                            if (val != null) {
                                String str = String.valueOf(val).toLowerCase(Locale.ROOT);
                                if (str.contains("online") || str.contains("idle") || str.contains("dnd")) {
                                    long targetId = 0L;
                                    if (key instanceof Number) {
                                        targetId = ((Number) key).longValue();
                                    } else if (key instanceof String) {
                                        targetId = Long.parseLong((String) key);
                                    }
                                    if (targetId != 0L) {
                                        settings.setLong(String.valueOf(targetId), now);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                })
            );
        } catch (Throwable ignored) {}

        // 2. نفس Hook إضافة BetterUserDetails لوضع السطر أسفله مباشرة
        try {
            ClassLoader classLoader = context.getClassLoader();
            Class<?> userSheetClass = classLoader.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheet");
            Class<?> loadedStateClass = classLoader.loadClass("com.discord.widgets.user.usersheet.WidgetUserSheetViewModel$ViewState$Loaded");

            patcher.patch(
                userSheetClass.getDeclaredMethod("configureNote", loadedStateClass),
                new Hook(param -> {
                    try {
                        Object sheet = param.thisObject;
                        Object loadedState = param.args[0];
                        if (loadedState == null) return;

                        // استخراج الـ User من الـ Loaded State
                        User user = (User) ReflectUtils.invokeMethod(loadedState, "getUser");
                        if (user == null) return;

                        long userId = user.getId();
                        View sheetView = (View) ReflectUtils.invokeMethod(sheet, "requireView");

                        if (sheetView != null) {
                            sheetView.post(() -> addLastOnlineView(sheetView, userId));
                        }
                    } catch (Throwable ignored) {}
                })
            );
        } catch (Throwable ignored) {}
    }

    private void addLastOnlineView(View sheetView, long userId) {
        try {
            Context ctx = sheetView.getContext();

            // العثور على الحاوية العلوية أسفل اسم المستخدم
            int aboutMeId = ctx.getResources().getIdentifier("about_me_card", "id", ctx.getPackageName());
            int contentId = ctx.getResources().getIdentifier("user_sheet_content", "id", ctx.getPackageName());

            View targetAnchor = (aboutMeId != 0) ? sheetView.findViewById(aboutMeId) : null;
            ViewGroup container = null;

            if (targetAnchor != null && targetAnchor.getParent() instanceof ViewGroup) {
                container = (ViewGroup) targetAnchor.getParent();
            } else if (contentId != 0) {
                View contentView = sheetView.findViewById(contentId);
                if (contentView instanceof ViewGroup) {
                    container = (ViewGroup) contentView;
                }
            }

            if (container == null && sheetView instanceof ViewGroup) {
                container = (ViewGroup) sheetView;
            }

            if (container == null) return;

            TextView tv = sheetView.findViewById(LAST_ONLINE_VIEW_ID);

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
                tv.setId(LAST_ONLINE_VIEW_ID);
                tv.setTextSize(12f);
                tv.setTextColor(Color.parseColor("#B9BBBE"));

                int padStart = DimenUtils.dpToPx(16);
                int padBottom = DimenUtils.dpToPx(4);
                tv.setPadding(padStart, 0, padStart, padBottom);

                // إضافته في آخر عنصر قبل كارت About Me ليظهر أسفل Last message
                if (targetAnchor != null) {
                    int index = container.indexOfChild(targetAnchor);
                    container.addView(tv, Math.max(0, index));
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

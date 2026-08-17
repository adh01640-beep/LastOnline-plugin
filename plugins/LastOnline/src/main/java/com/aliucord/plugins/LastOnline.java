package com.aliucord.plugins;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.DimenUtils;
import com.discord.models.user.User;
import com.discord.stores.StoreStream;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

@AliucordPlugin
public class LastOnline extends Plugin {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault());
    private static final int LAST_ONLINE_VIEW_ID = View.generateViewId();

    @Override
    public void start(Context context) throws Throwable {
        // 1. تتبع تحديثات الحالات وتخزين الوقت
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

        // 2. ربط فتح البروفايل عبر WidgetUserProfile (الأكثر توافقاً)
        try {
            patcher.patch(
                "com.discord.widgets.user.profile.WidgetUserProfileHeader",
                "configureUI",
                new Class<?>[]{
                    Class.forName("com.discord.widgets.user.profile.UserProfileHeaderViewModel$ViewState")
                },
                new Hook(param -> {
                    try {
                        View headerView = (View) param.thisObject;
                        Object viewState = param.args[0];
                        if (viewState == null) return;

                        // جلب User من الـ ViewState
                        User user = (User) viewState.getClass().getMethod("getUser").invoke(viewState);
                        if (user != null) {
                            long userId = user.getId();
                            headerView.post(() -> injectLastOnlineView(headerView, userId));
                        }
                    } catch (Throwable ignored) {}
                })
            );
        } catch (Throwable ignored) {}

        // 3. Fallback للربط مع UserProfileHeaderView
        try {
            patcher.patch(
                "com.discord.widgets.user.profile.UserProfileHeaderView",
                "updateBio",
                new Class<?>[]{ CharSequence.class, long.class },
                new Hook(param -> {
                    try {
                        View headerView = (View) param.thisObject;
                        long userId = (long) param.args[1];
                        headerView.post(() -> injectLastOnlineView(headerView, userId));
                    } catch (Throwable ignored) {}
                })
            );
        } catch (Throwable ignored) {}
    }

    private void injectLastOnlineView(View rootView, long userId) {
        try {
            Context ctx = rootView.getContext();
            TextView lastOnlineTv = rootView.findViewById(LAST_ONLINE_VIEW_ID);

            long lastSeen = settings.getLong(String.valueOf(userId), 0L);
            String displayText;

            if (lastSeen > 0) {
                long diff = System.currentTimeMillis() - lastSeen;
                if (diff < 60000) {
                    displayText = "LastOnline: Active Now";
                } else {
                    displayText = "LastOnline: " + dateFormat.format(new Date(lastSeen));
                }
            } else {
                displayText = "LastOnline: Unknown (Waiting for activity)";
            }

            if (lastOnlineTv == null) {
                lastOnlineTv = new TextView(ctx);
                lastOnlineTv.setId(LAST_ONLINE_VIEW_ID);
                lastOnlineTv.setTextSize(13f);
                lastOnlineTv.setTypeface(Typeface.DEFAULT_BOLD);
                lastOnlineTv.setTextColor(Color.parseColor("#B9BBBE"));

                int pad = DimenUtils.dpToPx(6);
                lastOnlineTv.setPadding(pad, pad, pad, pad);

                // محاولة إيجاد حاوية البايو أو إضافتها لأسفل الواجهة
                int bioId = ctx.getResources().getIdentifier("user_profile_header_bio", "id", ctx.getPackageName());
                View bioView = (bioId != 0) ? rootView.findViewById(bioId) : null;

                if (bioView != null && bioView.getParent() instanceof ViewGroup) {
                    ViewGroup parent = (ViewGroup) bioView.getParent();
                    int index = parent.indexOfChild(bioView);
                    parent.addView(lastOnlineTv, index + 1);
                } else if (rootView instanceof ViewGroup) {
                    ((ViewGroup) rootView).addView(lastOnlineTv);
                }
            }

            lastOnlineTv.setText(displayText);
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

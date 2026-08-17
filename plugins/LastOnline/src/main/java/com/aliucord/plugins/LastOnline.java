package com.aliucord.plugins;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.DimenUtils;
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
        // 1. Hook presence updates
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
                            String presenceStr = String.valueOf(val).toLowerCase(Locale.ROOT);
                            if (presenceStr.contains("online") || presenceStr.contains("idle") || presenceStr.contains("dnd")) {
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

        // 2. Patch Profile About Me UI
        patcher.patch(
            "com.discord.widgets.user.profile.UserProfileHeaderView",
            "updateBio",
            new Class<?>[]{ CharSequence.class, long.class },
            new Hook(param -> {
                View headerView = (View) param.thisObject;
                long userId = (long) param.args[1];
                headerView.post(() -> renderLastOnline(headerView, userId));
            })
        );
    }

    private void renderLastOnline(View headerView, long userId) {
        try {
            Context ctx = headerView.getContext();
            TextView lastOnlineTv = headerView.findViewById(LAST_ONLINE_VIEW_ID);

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
                displayText = "LastOnline: Unknown (Not recorded yet)";
            }

            if (lastOnlineTv == null) {
                lastOnlineTv = new TextView(ctx);
                lastOnlineTv.setId(LAST_ONLINE_VIEW_ID);
                lastOnlineTv.setTextSize(12f);
                lastOnlineTv.setTypeface(Typeface.DEFAULT_BOLD);
                lastOnlineTv.setTextColor(Color.parseColor("#B9BBBE"));

                int pad = DimenUtils.dpToPx(4);
                lastOnlineTv.setPadding(0, pad, 0, pad);

                int bioResId = ctx.getResources().getIdentifier("user_profile_header_bio", "id", ctx.getPackageName());
                View bioView = headerView.findViewById(bioResId);

                if (bioView != null && bioView.getParent() instanceof ViewGroup) {
                    ViewGroup parent = (ViewGroup) bioView.getParent();
                    parent.addView(lastOnlineTv, parent.indexOfChild(bioView) + 1);
                } else if (headerView instanceof ViewGroup) {
                    ((ViewGroup) headerView).addView(lastOnlineTv);
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

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
import com.discord.models.presence.Presence;
import com.discord.stores.StoreStream;
import com.discord.utilities.color.ColorCompat;

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
        // 1. Hook presence updates to track when users are online, idle, or dnd
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

                        if (val instanceof Presence) {
                            Presence presence = (Presence) val;
                            String status = extractStatus(presence);

                            if (status.equals("online") || status.equals("idle") || status.equals("dnd")) {
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

    private String extractStatus(Presence presence) {
        if (presence == null || presence.getStatus() == null) return "offline";
        try {
            Object clientStatus = presence.getStatus();
            String statusStr = clientStatus.toString().toLowerCase(Locale.ROOT);
            if (statusStr.contains("online")) return "online";
            if (statusStr.contains("idle")) return "idle";
            if (statusStr.contains("dnd")) return "dnd";
        } catch (Throwable ignored) {}
        return "offline";
    }

    private void renderLastOnline(View headerView, long userId) {
        try {
            Context ctx = headerView.getContext();
            TextView lastOnlineTv = headerView.findViewById(LAST_ONLINE_VIEW_ID);

            Map<Long, Presence> presencesMap = StoreStream.getPresences().getPresences();
            Presence currentPresence = presencesMap != null ? presencesMap.get(userId) : null;
            String currentStatus = extractStatus(currentPresence);

            String displayText;
            if (currentStatus.equals("online") || currentStatus.equals("idle") || currentStatus.equals("dnd")) {
                displayText = "LastOnline: Active Now (" + currentStatus.toUpperCase(Locale.ROOT) + ")";
                settings.setLong(String.valueOf(userId), System.currentTimeMillis());
            } else {
                long lastSeen = settings.getLong(String.valueOf(userId), 0L);
                displayText = lastSeen > 0 
                        ? "LastOnline: " + dateFormat.format(new Date(lastSeen))
                        : "LastOnline: Unknown (Not recorded yet)";
            }

            if (lastOnlineTv == null) {
                lastOnlineTv = new TextView(ctx);
                lastOnlineTv.setId(LAST_ONLINE_VIEW_ID);
                lastOnlineTv.setTextSize(12f);
                lastOnlineTv.setTypeface(Typeface.DEFAULT_BOLD);

                // Safe fallback for text color without referencing R.attr directly
                try {
                    int attrId = ctx.getResources().getIdentifier("text_muted", "attr", ctx.getPackageName());
                    if (attrId != 0) {
                        lastOnlineTv.setTextColor(ColorCompat.getThemedColor(ctx, attrId));
                    } else {
                        lastOnlineTv.setTextColor(Color.GRAY);
                    }
                } catch (Throwable e) {
                    lastOnlineTv.setTextColor(Color.GRAY);
                }

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

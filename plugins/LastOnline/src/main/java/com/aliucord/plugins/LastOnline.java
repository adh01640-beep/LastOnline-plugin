package com.aliucord.plugins;

import android.content.Context;
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
        // 1. تتبع تحديثات حالة المستخدمين وحفظ وقت التواجد
        patcher.patch(
            StoreStream.getPresences().getClass().getDeclaredMethod("handlePresenceUpdate", Map.class),
            new Hook(param -> {
                try {
                    Map<?, ?> updates = (Map<?, ?>) param.args[0];
                    if (updates == null) return;

                    long now = System.currentTimeMillis();
                    for (Map.Entry<?, ?> entry : updates.entrySet()) {
                        Object val = entry.getValue();
                        if (val instanceof Presence) {
                            Presence presence = (Presence) val;
                            String status = presence.getStatus() != null 
                                    ? presence.getStatus().toLowerCase(Locale.ROOT) 
                                    : "offline";

                            if (status.equals("online") || status.equals("idle") || status.equals("dnd")) {
                                settings.setLong(String.valueOf(presence.getUserId()), now);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            })
        );

        // 2. التعديل على واجهة البروفايل لإضافة النص أسفل البايو (About Me)
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

            Presence currentPresence = StoreStream.getPresences().getPresence(userId);
            String currentStatus = currentPresence != null && currentPresence.getStatus() != null 
                    ? currentPresence.getStatus().toLowerCase(Locale.ROOT) 
                    : "offline";

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
                lastOnlineTv.setTextColor(ColorCompat.getThemedColor(ctx, com.lytefast.flexpad.R.attr.text_muted));
                
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

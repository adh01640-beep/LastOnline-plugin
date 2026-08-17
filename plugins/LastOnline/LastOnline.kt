package com.aliucord.plugins

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.ReflectUtils
import com.discord.databinding.WidgetUserSheetBinding
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@AliucordPlugin
class LastOnline : Plugin() {

    private val dateFormat = SimpleDateFormat("dd/MM/yy, HH:mm:ss", Locale.getDefault())
    private val lastOnlineTag = "BETTER_USER_DETAILS_LAST_ONLINE_ROW"

    override fun start(context: Context) {
        // 1. Gateway Presence Updates Hook
        try {
            for (method in StoreStream.getPresences().javaClass.declaredMethods) {
                if (method.name == "handlePresenceUpdate" || method.name == "onPresencesLoaded") {
                    method.isAccessible = true
                    patcher.after<Any>(method) { param ->
                        param.args?.getOrNull(0)?.let { processPresence(it) }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Exact BetterUserDetails hook using PatcherExtensionsKt.after
        patcher.after<WidgetUserSheet>("configureNote", WidgetUserSheetViewModel.ViewState.Loaded::class.java) { param ->
            val sheet = param.thisObject as? WidgetUserSheet ?: return@after
            val loadedState = param.args[0] as? WidgetUserSheetViewModel.ViewState.Loaded ?: return@after
            val user = loadedState.user ?: return@after
            val userId = user.id

            val binding = ReflectUtils.getField(sheet, "binding") as? WidgetUserSheetBinding ?: return@after
            val aboutMeCard = binding.aboutMeCard ?: return@after
            val content = binding.userSheetContent ?: return@after

            renderLastOnline(content, aboutMeCard, userId)
        }
    }

    private fun processPresence(data: Any) {
        try {
            if (data is Map<*, *>) {
                for ((key, value) in data) {
                    val uid = (key as? Number)?.toLong() ?: (key as? String)?.toLongOrNull() ?: 0L
                    saveIfOnline(uid, value)
                }
            } else {
                var uid = 0L
                try {
                    val user = ReflectUtils.invokeMethod(data, "getUser") as? User
                    if (user != null) uid = user.id
                } catch (_: Throwable) {}

                if (uid == 0L) {
                    val idObj = ReflectUtils.getField(data, "userId") as? Number
                    if (idObj != null) uid = idObj.toLong()
                }

                if (uid != 0L) {
                    saveIfOnline(uid, data)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun saveIfOnline(userId: Long, presenceObj: Any?) {
        if (presenceObj == null || userId == 0L) return
        val status = presenceObj.toString().lowercase(Locale.ROOT)
        if (status.contains("online") || status.contains("idle") || status.contains("dnd")) {
            settings.setLong(userId.toString(), System.currentTimeMillis())
        }
    }

    private fun toRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 60000) return "Active Now"
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when (days) {
            0L -> "${dateFormat.format(Date(timestamp))} (Today)"
            1L -> "${dateFormat.format(Date(timestamp))} (Yesterday)"
            else -> "${dateFormat.format(Date(timestamp))} ($days days ago)"
        }
    }

    private fun renderLastOnline(parent: LinearLayout, anchor: View, userId: Long) {
        try {
            val ctx = parent.context
            var lastSeen = settings.getLong(userId.toString(), 0L)

            // Check Discord live memory cache
            try {
                val presences = ReflectUtils.invokeMethod(StoreStream.getPresences(), "getPresences") as? Map<*, *>
                if (presences?.containsKey(userId) == true) {
                    val p = presences[userId].toString().lowercase(Locale.ROOT)
                    if (p.contains("online") || p.contains("idle") || p.contains("dnd")) {
                        lastSeen = System.currentTimeMillis()
                        settings.setLong(userId.toString(), lastSeen)
                    }
                }
            } catch (_: Throwable) {}

            val displayText = if (lastSeen > 0L) {
                "Last online: ${toRelativeTime(lastSeen)}"
            } else {
                "Last online: Unknown"
            }

            // Find BetterUserDetails LinearLayout container if present
            var targetContainer: ViewGroup = parent
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is LinearLayout && child !== anchor) {
                    targetContainer = child
                    break
                }
            }

            var tv = targetContainer.findViewWithTag<TextView>(lastOnlineTag)
            if (tv == null) {
                tv = TextView(ctx).apply {
                    tag = lastOnlineTag
                    textSize = 12f
                    setTextColor(Color.parseColor("#B9BBBE"))
                }

                if (targetContainer !== parent) {
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = DimenUtils.dpToPx(1)
                    }
                    tv.layoutParams = lp
                    targetContainer.addView(tv)
                } else {
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        val padH = DimenUtils.dpToPx(16)
                        val padV = DimenUtils.dpToPx(2)
                        setPadding(padH, 0, padH, padV)
                    }
                    tv.layoutParams = lp
                    val index = parent.indexOfChild(anchor)
                    if (index >= 0) parent.addView(tv, index) else parent.addView(tv)
                }
            }

            tv.text = displayText
        } catch (_: Throwable) {}
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}

package com.brainrot.pet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.brainrot.core.BrainEngine
import com.brainrot.core.formatDuration
import com.brainrot.pet.AppGraph
import com.brainrot.pet.BrainLog
import com.brainrot.pet.R
import com.brainrot.pet.overlay.BrainLook
import com.brainrot.pet.overlay.BrainRenderer
import com.brainrot.pet.overlay.SpeechLines
import com.brainrot.pet.ui.MainActivity

class BrainWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        BrainWidget.update(context, appWidgetManager, appWidgetIds)
    }
}

/** Home-screen widget: the brain as it looks now, plus today's reels and screen time. Main thread. */
object BrainWidget {
    private const val BITMAP_PX = 288
    private val renderer by lazy { BrainRenderer() }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, BrainWidgetProvider::class.java))
        if (ids.isNotEmpty()) update(context, manager, ids)
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        try {
            val d = AppGraph.store.data.value
            val live = AppGraph.runtime.live.value
            val mood = BrainEngine.mood(d.brain, sessionMs = 0)
            val stage = context.getString(SpeechLines.stageLabel(mood.stage))
            val screenMs = maxOf(live.totalScreenMs, d.today.screenMs)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_brain).apply {
                setImageViewBitmap(R.id.widget_brain, renderer.renderBitmap(BITMAP_PX, BrainLook.of(d.brain, mood)))
                setContentDescription(R.id.widget_brain, context.getString(R.string.bubble_cd, d.settings.brainName, stage))
                setTextViewText(R.id.widget_title, context.getString(R.string.widget_title, d.settings.brainName, stage))
                setTextViewText(R.id.widget_stats, context.getString(R.string.widget_stats, d.today.totalReels, formatDuration(screenMs)))
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        } catch (e: RuntimeException) {
            BrainLog.w("widget_update_failed", e)
        }
    }
}

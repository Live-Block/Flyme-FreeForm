package com.sunshine.freeform.broadcast

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import com.sunshine.freeform.ui.freeform.FreeformConfig
import com.sunshine.freeform.ui.freeform.FreeformService
import com.sunshine.freeform.ui.freeform.FreeformView

class StartFreeformReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", -1)
        val extras = intent.getStringExtra("extras")
        val parcelable = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_INTENT)
        var target: Parcelable
        if (packageName != null && activityName != null)
            target = Intent().setComponent(ComponentName(packageName, activityName))
        if (parcelable is Intent || parcelable is PendingIntent)
            target = parcelable
        context.startService(
            Intent(context, FreeformService::class.java)
                .setAction(FreeformService.ACTION_START_INTENT)
                .putExtra(Intent.EXTRA_INTENT, parcelable)
        )
    }
}
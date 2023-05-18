package com.sunshine.freeform.broadcast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import com.sunshine.freeform.ui.freeform.FreeformService

class StartFreeformReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", -1)
        val extras = intent.getStringExtra("extras")
        val parcelable = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_INTENT)
        var target = Intent()
        if (packageName != null && activityName != null)
            target = Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(packageName, activityName))
                .setPackage(packageName)
                .addCategory(Intent.CATEGORY_LAUNCHER)
        if (parcelable is Intent)
            target = parcelable
        context.startService(
            Intent(context, FreeformService::class.java)
                .setAction(FreeformService.ACTION_START_INTENT)
                .putExtra(Intent.EXTRA_INTENT, target)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, target.component)
                .putExtra(Intent.EXTRA_USER, userId)
        )
    }
}
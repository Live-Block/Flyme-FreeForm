package com.sunshine.freeform.ui.freeform

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Parcelable
import android.os.SystemClock
import com.sunshine.freeform.utils.ServiceUtils

class FreeformService: Service() {
    private lateinit var mFreeformView: FreeformView
    private lateinit var mIntent: Parcelable

    private var mUserId: Int = 0

    private val mDisplay by lazy {
        ServiceUtils.displayManager.createVirtualDisplay(
            "MiFreeform@${SystemClock.uptimeMillis()}",
            500,
            500,
            100,
            null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
    }

    override fun onCreate() {
        ServiceUtils.initWithShizuku(this)

        mFreeformView = FreeformView(FreeformConfig(), this)
        mFreeformView.virtualDisplay = mDisplay

        initFreeformView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null)
            return START_NOT_STICKY
        when (intent.action) {
            ACTION_START_INTENT -> {
                mUserId = intent.getIntExtra(Intent.EXTRA_USER, 0)
                val result = startIntent(
                    parcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT),
                    componentName = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME),
                )
                if (result < 0) {
                    return START_NOT_STICKY
                }
                startFreeformView()
            }
            ACTION_DESTROY_FREEFORM -> {
                mFreeformView.destroy()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        mFreeformView.destroy()
        mDisplay.release()
    }

    private fun initFreeformView() {
        mFreeformView.initSystemService()
        mFreeformView.initConfig()
        mFreeformView.initView()
    }

    private fun startFreeformView() {
        mFreeformView.config.apply {
            userId = mUserId
            intent = mIntent
        }
        if (mFreeformView.isDestroy) {
            mFreeformView.isDestroy = false
            initFreeformView()
        }
        if (mFreeformView.isFloating || mFreeformView.isHidden) {
            mFreeformView.moveToFirst()
        } else {
            mFreeformView.showWindow()
        }
    }

    private fun startIntent(
        parcelable: Parcelable? = mIntent,
        options: ActivityOptions = ActivityOptions.makeBasic().setLaunchDisplayId(mDisplay.display.displayId),
        componentName: ComponentName? = null,
    ): Int {
        var result = -1
        if (parcelable is Intent) {
            mIntent = parcelable
            result = mFreeformView.callIntent(parcelable, options, userId = mUserId)
        } else if (componentName != null) {
            mIntent = Intent(Intent.ACTION_MAIN).apply {
                component = componentName
                setPackage(componentName.packageName)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            result = mFreeformView.callIntent(mIntent as Intent, options)
            if (parcelable is PendingIntent) {
                result = mFreeformView.callPendingIntent(parcelable, options)
            }
        }
        return result
    }

    companion object {
        val ACTION_START_INTENT = "com.sunshine.freeform.action.start.intent"
        val ACTION_DESTROY_FREEFORM = "com.sunshine.freeform.action.destroy.freeform"
    }
}

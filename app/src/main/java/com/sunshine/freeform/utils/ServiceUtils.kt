package com.sunshine.freeform.utils

import android.app.ActivityManager
import android.app.IActivityManager
import android.app.IActivityTaskManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.IInputManager
import android.view.IWindowManager
import android.view.WindowManager
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * @date 2021/2/1
 * 服务相关工具类
 */
object ServiceUtils {
    lateinit var activityManager: IActivityManager
    lateinit var activityTaskManager: IActivityTaskManager
    lateinit var displayManager: DisplayManager
    lateinit var windowManager: WindowManager
    lateinit var iWindowManager: IWindowManager
    lateinit var inputManager: IInputManager

    fun initWithShizuku(context: Context) {
        activityManager = IActivityManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("activity")
            )
        )
        activityTaskManager = IActivityTaskManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("activity_task")
            )
        )
        displayManager = context.getSystemService(DisplayManager::class.java)
        windowManager = context.getSystemService(WindowManager::class.java)
        iWindowManager = IWindowManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("window")
            )
        )
        inputManager = IInputManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("input")
            )
        )
    }

    /**
     * 判断某个服务是否正在运行的方法
     *
     * @param mContext
     * @param serviceName 是包名+服务的类名（例如：net.loonggg.testbackstage.TestService）
     * @return true代表正在运行，false代表服务没有正在运行
     */
    fun isServiceWork(mContext: Context, serviceName: String): Boolean {
        var isWork = false
        val myAM = mContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val myList: List<ActivityManager.RunningServiceInfo> = myAM.getRunningServices(40)
        if (myList.isEmpty()) {
            return false
        }
        for (i in myList.indices) {
            val mName: String = myList[i].service.className
            myList[i].service.className
            if (mName == serviceName) {
                isWork = true
                break
            }
        }
        return isWork
    }
}
package com.sunshine.freeform.ui.freeform

import android.content.Context
import android.view.Surface

/**
 * @date 2022/8/22
 * @author sunshine0523
 * 小窗帮助类
 */
object FreeformHelper {
    fun getScreenDpi(context: Context): Int {
        return context.resources.displayMetrics.densityDpi
    }

    /**
     * @param rotation 屏幕方向是否是竖屏
     * @see Surface.ROTATION_0
     * @return orientation
     */
    fun screenIsPortrait(rotation: Int): Boolean {
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
    }
}
package com.sunshine.freeform.ui.freeform

import android.content.ComponentName
import android.os.Parcelable

data class FreeformConfig(
    // Component Name
    var componentName: ComponentName? = null,
    //启动的userId
    var userId: Int = -1,
    // Intent
    var intent: Parcelable? = null,
    //叠加层最大高度
    var maxHeight: Int = 1,
    //分辨率
    var freeformDpi: Int = 1,
    //宽高比，默认9：16
    var widthHeightRatio: Float = 9f / 16f,
    //使用shizuku/sui阻止小窗跳出到全屏
    var useSuiRefuseToFullScreen: Boolean = false,
    // 降低背景亮度
    var dimAmount: Float = 0.3f,
    //兼容模式启动
    @Deprecated("", ReplaceWith(""))
    var compatibleMode: Boolean = false,
    var rememberPosition: Boolean = false,
    // 挂起大小
    var floatViewSize: Float = 0.2f,
    //
    var freeformSize: Float = 0.75f,
    var freeformSizeLand: Float = 0.9f,
    //记录启动位置
    var rememberX: Int = 0,
    var rememberY: Int = 0,
    //手动调整小窗方向
    var manualAdjustFreeformRotation: Boolean = false,
)

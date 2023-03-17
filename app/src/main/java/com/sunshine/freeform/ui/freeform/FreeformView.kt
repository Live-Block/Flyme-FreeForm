package com.sunshine.freeform.ui.freeform

import android.animation.Animator
import android.animation.Animator.AnimatorListener
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.ContextHidden
import android.content.Intent
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.IInputManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.*
import android.view.animation.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.databinding.ViewFreeformFlymeBinding
import dev.rikka.tools.refine.Refine
import kotlinx.android.synthetic.main.view_bar.view.*
import kotlinx.android.synthetic.main.view_bar_flyme.view.*
import kotlinx.android.synthetic.main.view_floating_button.view.*
import kotlinx.android.synthetic.main.view_freeform.view.*
import kotlinx.android.synthetic.main.view_freeform.view.root
import kotlinx.android.synthetic.main.view_freeform_flyme.view.*
import kotlinx.coroutines.*
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformView(
    override val config: FreeformConfig,
    private val context: Context,
) : FreeformViewAbs(config), View.OnTouchListener {
    //服务
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var activityTaskManager: IActivityTaskManager? = null
    private var activityManager: IActivityManager? = null
    private var inputManager: IInputManager? = null
    private var iWindowManager: IWindowManager? = null

    private val shell = "com.android.shell"

    //ViewModel
    private val viewModel = FreeformViewModel(context)

    private val scope = MainScope()

    //默认屏幕，用于获取横竖屏状态
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //界面binding
    private lateinit var binding: ViewFreeformFlymeBinding

    //判断是否是初次启动，防止屏幕旋转时再初始化
    private var firstInit = true

    //该小窗是否已经销毁
    private var isDestroy = false

    //是否处于隐藏状态，当打开米窗的正在运行小窗界面时，应当隐藏所有小窗
    private var isHidden = false

    //是否处于后台挂起状态
    private var isBackstage = false

    //小窗中应用的taskId
    private var taskId = -1

    //叠加层Params
    private val windowLayoutParams = WindowManager.LayoutParams()

    //虚拟屏幕
    private lateinit var virtualDisplay: VirtualDisplay

    //物理屏幕方向
    private var screenRotation = defaultDisplay.rotation
    //虚拟屏幕方向，1 竖屏， 0 横屏
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    private val iRotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            if (rotation != screenRotation) {
                screenRotation = rotation
                scope.launch(Dispatchers.Main) {
                    onScreenOrientationChanged()
                }
            }
        }
    }

    private val screenListener = ScreenListener(context)

    //触摸监听
    private val touchListener = TouchListener()
    private val touchListenerPreQ = TouchListenerPreQ()

    //屏幕宽高，不保证大小
    private var realScreenWidth = 0
    private var realScreenHeight = 0

    //小窗的“尺寸”，该尺寸只在小窗内屏幕方向改变时变化
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //小窗界面的宽高，该宽高不随着屏幕、小窗方向改变而改变，即h>w恒成立。该尺寸只在物理屏幕方向变化时变化
    private var freeformHeight = 0
    private var freeformWidth = 0

    //max(freeformScreenHeight,freeformScreenWidth)，用于计算scale
    private var maxFreeformScreenSize = 0
    private var minFreeformScreenSize = 0

    // 挂起后与边缘的 Padding
    private var screenPaddingX: Int = context.resources.getDimension(R.dimen.freeform_screen_width_padding).roundToInt()
    private var screenPaddingY: Int = context.resources.getDimension(R.dimen.freeform_screen_height_padding).roundToInt()

    // Margins
    private var barHeight: Float = context.resources.getDimension(R.dimen.bottom_bar_height_flyme)
    private var freeformShadow: Float = context.resources.getDimension(R.dimen.freeform_shadow)
    private var cardHeightMargin: Float = barHeight + freeformShadow
    private var cardWidthMargin: Float = 0f

    // 存储上一次的悬浮位置
    private var lastFloatViewLocation: IntArray = intArrayOf(-1, -1)

    // 小窗大小
    private var hangUpViewHeight = 0
    private var hangUpViewWidth = 0

    // root
    private var rootHeight = 0
    private var rootWidth = 0

    // 小窗缩放比例
    private var mScaleX = 1f
    private var mScaleY = 1f

    // 触发互动的比例
    private var goFloatScale = 0.6f
    private var goFullScale = 0.9f

    //缩放比例
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    //新增 手动调整小窗方向 q220904.7
    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (config.manualAdjustFreeformRotation) {
                virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                    VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
                } else {
                    VIRTUAL_DISPLAY_ROTATION_PORTRAIT
                }
                onFreeFormRotationChanged()
            }
            return false
        }
    })

    private val backgroundGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isHangUp) {
                destroy()
            }
            return true
        }
    })

    private val sharedPreferencesChangeListener =
        OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key.equals("freeform_float_view_size")) {
                config.floatViewSize = (sharedPreferences.getInt(key, 20)) / 100.toFloat()
                hangUpViewHeight = (realScreenHeight * config.floatViewSize).roundToInt()
                hangUpViewWidth = (hangUpViewHeight * config.widthHeightRatio).roundToInt()
                if (isHangUp) {
                    if (isHidden) {
                        hiddenViewToFloatView(false)
                    }
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        width = hangUpViewWidth
                        height = hangUpViewHeight
                    })

                    binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * (hangUpViewWidth / realScreenWidth)

                    val windowCoordinate = intArrayOf(
                        windowLayoutParams.x,
                        windowLayoutParams.y,
                    )

                    val location = genFloatViewLocation()
                    lastFloatViewLocation = location

                    AnimatorSet().apply {
                        playTogether(
                            moveViewAnim(windowCoordinate, location)
                        )
                        duration = 1000
                        start()
                    }
                }
            }
        }

    //是否处于挂起状态
    private var isHangUp = false
    //挂起位置，0：是否在左，1：是否在上
    private val hangUpPosition = booleanArrayOf(false, true)

    @RequiresApi(Build.VERSION_CODES.Q)
    private val taskStackListener = MTaskStackListener()

    private fun initSystemService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }
        try {
            activityTaskManager = IActivityTaskManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task")))
            //目前仅支持Android Q及以上版本
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityTaskManager?.registerTaskStackListener(taskStackListener)
            }
            activityManager = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity")))
        }catch (e: Exception) {}
        try {
            inputManager = IInputManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input")))
        }catch (e: Exception) {}
        try {
            iWindowManager = IWindowManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("window")))
        } catch (e: Exception) {}
    }

    private fun initConfig() {
        config.maxHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)

        realScreenWidth = context.resources.displayMetrics.widthPixels
        realScreenHeight = context.resources.displayMetrics.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rect = windowManager.currentWindowMetrics.bounds
            realScreenWidth = rect.width()
            realScreenHeight = rect.height()
        }

        rootHeight = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenHeight else realScreenWidth
        rootWidth = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenWidth else realScreenHeight

        config.freeformDpi = FreeformHelper.getScreenDpi(context)

        //优化 QQ和微信也支持缩放了 q220917.1
        freeformScreenHeight = (min(realScreenHeight, realScreenWidth) / config.widthHeightRatio).roundToInt()
        if (!config.useCustomConfig) {
            freeformScreenHeight -=
                if (FreeformHelper.screenIsPortrait(screenRotation)) viewModel.getIntSp("freeform_scale_portrait", 0)
                else viewModel.getIntSp("freeform_scale_landscape", 0)

            freeformScreenHeight = max(1, freeformScreenHeight)
        }
        freeformScreenWidth = (freeformScreenHeight * config.widthHeightRatio).roundToInt()
        maxFreeformScreenSize = freeformScreenHeight
        minFreeformScreenSize = freeformScreenWidth

        if (config.useCustomConfig) return
        //---------------客制化配置-----------------

        config.rememberPosition = viewModel.getBooleanSp("remember_freeform_position", false)
        config.floatViewSize = (viewModel.getIntSp("freeform_float_view_size", 20)) / 100.toFloat()

        hangUpViewHeight = (rootHeight * config.floatViewSize).roundToInt()
        hangUpViewWidth = (hangUpViewHeight * config.widthHeightRatio).roundToInt()

        viewModel.registerOnSharedPreferenceChangeListener(sharedPreferencesChangeListener)

        scaleX = realScreenWidth / freeformScreenWidth.toFloat()
        scaleY = (realScreenHeight - cardHeightMargin) / freeformScreenHeight.toFloat()

        config.useSuiRefuseToFullScreen = viewModel.getBooleanSp("use_sui_refuse_to_fullscreen", false)
        config.manualAdjustFreeformRotation = viewModel.getBooleanSp("manual_adjust_freeform_rotation", false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        binding = ViewFreeformFlymeBinding.bind(LayoutInflater.from(context).inflate(R.layout.view_freeform_flyme, null, false))

        binding.root.setOnTouchListener(this)
        binding.bottomBar.middleView.apply {
            visibility = View.VISIBLE
            setOnTouchListener(this@FreeformView)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            hangUpPosition[0] = true
            binding.apply {
                bottomBar.root.visibility = View.GONE
                sideBar.root.apply {
                    visibility = View.VISIBLE
                    sideView.visibility = View.VISIBLE
                    sideView.setOnTouchListener(this@FreeformView)
                }
                (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                    topMargin = 0
                    bottomMargin = 0
                    rightMargin = barHeight.roundToInt()
                }
            }
            cardHeightMargin = 0f
            cardWidthMargin = barHeight
        }

        refreshFreeformSize()

        goFloatScale = (freeformHeight * 0.8f) / rootHeight
        goFullScale = (freeformHeight * 1.1f) / rootHeight

        resetScale()

        binding.freeformRoot.alpha = 1f
        binding.textureView.alpha = 0f

        initDisplay()
    }

    private fun initDisplay() {
        try {
            virtualDisplay = displayManager.createVirtualDisplay(
                "MiFreeform@${config.packageName}@${config.userId}",
                freeformScreenWidth,
                freeformScreenHeight,
                config.freeformDpi,
                null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.create_display_fail), Toast.LENGTH_SHORT).show()
            return
        }

        screenListener.begin(object : ScreenListener.ScreenStateListener {
            override fun onScreenOn() {}

            //关闭屏幕隐藏小窗
            override fun onScreenOff() {
                //挂起状态无需更新
                //修复 在有正在运行程序的情况下锁屏，米窗崩溃的问题 q220902.1
                //优化 锁屏后小窗的状态 q220917.3
                if (!isBackstage) {
                    binding.root.alpha = 0f
                    windowLayoutParams.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    windowLayoutParams.dimAmount = 0f
                    windowManager.updateViewLayout(binding.root, windowLayoutParams)
                }
            }

            //解锁恢复小窗
            override fun onUserPresent() {
                //挂起状态无需更新
                if (!isBackstage) {
                    binding.root.alpha = 1f
                    windowLayoutParams.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM

                    if (!isHangUp) windowLayoutParams.dimAmount = config.dimAmount
                    windowManager.updateViewLayout(binding.root, windowLayoutParams)
                }
            }
        })

        initOrientationChangedListener()
        initTextureViewListener()

        showWindow()
    }

    private fun initOrientationChangedListener() {
        iWindowManager?.watchRotation(iRotationWatcher, Display.DEFAULT_DISPLAY)
    }

    private fun initTextureViewListener() {
        //冷启动监听
        var updateFrameCount = 0
        var initFinish = false

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
                virtualDisplay.surface = Surface(surface)
                val options = ActivityOptions.makeBasic().apply {
                    launchDisplayId = virtualDisplay.display.displayId
                }

                if (firstInit) {
                    if (config.intent != null) {
                        var result = 0
                        if (config.intent is Intent) {
                            val intent = config.intent as Intent
                            intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NO_ANIMATION
                            result = activityManager!!.startActivityAsUserWithFeature(
                                null, shell, null, intent,
                                intent.type, null, null, 0, 0,
                                null, options.toBundle(), config.userId
                            )
                        } else if (config.intent is PendingIntent) {
                            val pendingIntent = Refine.unsafeCast<PendingIntentHidden>(config.intent)
                            result = activityManager!!.sendIntentSender(
                                pendingIntent.target, pendingIntent.whitelistToken, 0, null,
                                null, null, null, options.toBundle())
                            if (result >= 100) {
                                // TODO: 添加后台启动方法
                            }
                        }
                        if (result < 0) {
                            Toast.makeText(context, "Start Failed Result Code: $result", Toast.LENGTH_SHORT).show()
                            destroy()
                            return
                        }
                        if (result >= 100) {
                            Toast.makeText(context, "Start Not Success Result Code: $result", Toast.LENGTH_SHORT).show()
                        }

                        FreeformHelper.addFreeformToSet(this@FreeformView)
                        firstInit = false
                    } else
                    if (MiFreeform.me?.getControlService()?.execShell("am start -n ${config.packageName}/${config.activityName} --user ${config.userId} --display ${virtualDisplay.display.displayId}", false) == true) {
                        FreeformHelper.addFreeformToSet(this@FreeformView)
                        firstInit = false
                    }
                    //启动失败
                    else {
                        destroy()
                    }
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!initFinish) {
                    ++updateFrameCount
                    if (updateFrameCount > 2) {
                        binding.lottieView.cancelAnimation()
                        binding.lottieView.animate().alpha(0f).setDuration(200).start()
                        binding.textureView.animate().alpha(1f).setDuration(200).start()
                        initFinish = true
                    }
                }
            }
        }
    }

    private fun showWindow() {
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            windowLayoutParams.apply {
                width = rootWidth
                height = rootHeight
            }
        } else {
            windowLayoutParams.apply {
                width = rootHeight
                height = rootWidth
            }
            scaleY = realScreenWidth / freeformScreenWidth.toFloat()
            scaleX = (realScreenHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
            mScaleY = freeformWidth / realScreenWidth.toFloat()
            mScaleX = freeformHeight / realScreenHeight.toFloat()
            binding.freeformRoot.scaleX = mScaleX
            binding.freeformRoot.scaleY = mScaleY
        }
        windowLayoutParams.apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            format = PixelFormat.RGBA_8888
            dimAmount = config.dimAmount
            windowAnimations = android.R.style.Animation_Dialog
        }

        setWindowNoUpdateAnimation()

        //恢复记录的位置
        if (config.rememberPosition) {
            windowLayoutParams.apply {
                x = config.rememberX
                y = config.rememberY
            }
        } else {
            //横屏移动到屏幕左侧显示小窗
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                windowLayoutParams.apply {
                    x = (freeformWidth - rootHeight + screenPaddingX) / 2
                    //往上移动一些
                    y = 0
                }
            }
        }

        try {
            windowManager.addView(binding.root, windowLayoutParams)
        } catch (e: Exception) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: Exception) {}

            if (Settings.canDrawOverlays(context)) {
                windowManager.addView(binding.root, windowLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
            } else {
                destroy()
                try {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(
                        intent
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 禁用更新过渡动画
     */
    private fun setWindowNoUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        try {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue or noAnimFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        } catch (e: Exception) { }
    }

    private fun setWindowEnableUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        try {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue and noAnimFlag.inv()
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        } catch (e: Exception) { }
    }

    private fun onFreeFormRotationChanged() {
        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        maxFreeformScreenSize = tempHeight
        minFreeformScreenSize = tempWidth

        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            freeformScreenHeight = tempHeight
            freeformScreenWidth = tempWidth
        } else {
            freeformScreenHeight = tempWidth
            freeformScreenWidth = tempHeight

            //优化 小窗内部旋转时，如果旋转后的尺寸大于屏幕尺寸，则进行调整 q220904.3
            //小窗横屏-物理屏幕竖屏
            if (FreeformHelper.screenIsPortrait(screenRotation)) {
                if (freeformHeight > realScreenWidth) {
                    freeformHeight = realScreenWidth
                    freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
                    scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
                    scaleY = scaleX
                }
            }
        }

        resizeVirtualDisplay()
        updateWindowSize(false)
    }

    private fun onScreenOrientationChanged() {
        var tmpWidth = context.resources.displayMetrics.widthPixels
        var tmpHeight = context.resources.displayMetrics.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rect = windowManager.currentWindowMetrics.bounds
            tmpWidth = rect.width()
            tmpHeight = rect.height()
        }

        if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180) {
            realScreenHeight = max(tmpWidth, tmpHeight)
            realScreenWidth = min(tmpWidth, tmpHeight)
        } else {
            realScreenWidth = max(tmpWidth, tmpHeight)
            realScreenHeight = min(tmpWidth, tmpHeight)
        }

        config.maxHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)

        rootHeight = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenHeight else realScreenWidth
        rootWidth = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenWidth else realScreenHeight

        cardHeightMargin = if (FreeformHelper.screenIsPortrait(screenRotation)) (barHeight + freeformShadow) else 0f
        cardWidthMargin = if (FreeformHelper.screenIsPortrait(screenRotation)) 0f else barHeight

        refreshFreeformSize()

        hangUpViewHeight = (rootHeight * config.floatViewSize).roundToInt()
        hangUpViewWidth = (hangUpViewHeight * config.widthHeightRatio).roundToInt()

        goFloatScale = (freeformHeight * 0.8f) / rootHeight
        goFullScale = (freeformHeight * 1.1f) / rootHeight

        val location = genFloatViewLocation()
        lastFloatViewLocation = location

        binding.bottomBar.root.visibility = View.VISIBLE
        binding.sideBar.root.visibility = View.GONE

        if(!FreeformHelper.screenIsPortrait(screenRotation)) {
            binding.apply {
                bottomBar.root.visibility = View.GONE
                sideBar.root.apply {
                    visibility = View.VISIBLE
                    sideView.visibility = View.VISIBLE
                    sideView.setOnTouchListener(this@FreeformView)
                }
            }
        }

        refreshTouchScale()

        if (isHangUp && !isHidden) {
            moveFloatViewLocation(location, true)
        } else if (isHidden) {
            moveHiddenViewLocation(location)
        } else {
            windowLayoutParams.apply {
                height = rootHeight
                width = rootWidth
                x = 0
                y = 0
            }
            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                topMargin = freeformShadow.roundToInt()
                bottomMargin = barHeight.roundToInt()
                rightMargin = 0
            }
            if(!FreeformHelper.screenIsPortrait(screenRotation)) {
                binding.apply {
                    (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                        topMargin = 0
                        bottomMargin = 0
                        rightMargin = barHeight.roundToInt()
                    }
                }
                windowLayoutParams.apply {
                    x = (freeformWidth - rootHeight + screenPaddingX) / 2
                    //往上移动一些
                    y = 0
                }
            }
            resetScale()
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun resizeVirtualDisplay() {
        virtualDisplay.resize(
            freeformScreenWidth,
            freeformScreenHeight,
            config.freeformDpi
        )
    }

    private fun updateWindowSize(orientationChanged: Boolean) {
        resetScale()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            windowLayoutParams.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
        } else {
            windowLayoutParams.apply {
                width = freeformHeight
                height = freeformWidth + context.resources.getDimension(R.dimen.bottom_bar_height).toInt() + context.resources.getDimension(R.dimen.freeform_shadow).toInt()
            }
        }

        if (orientationChanged) {
            //横屏移动到屏幕左侧显示小窗
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                windowLayoutParams.apply {
                    x = (width - realScreenWidth) / 2
                }
            }
        }

        //非后台挂起状态才进行刷新
        if (!isBackstage) {
            windowManager.updateViewLayout(
                binding.root,
                windowLayoutParams
            )
        }
    }

    /**
     * 如果小窗无法控制了，可以尝试移动到屏幕中心以控制
     */
    override fun toScreenCenter() {
        if (isHangUp) return
        windowLayoutParams.x = 0
        windowLayoutParams.y = 0
    }

    override fun moveToFirst() {
        if (isHangUp) {
            if (isHidden) {
                hiddenViewToFloatView(true)
            } else {
                floatViewToMiniView()
            }
        } else {
            windowManager.removeViewImmediate(binding.root)
            windowManager.addView(binding.root, windowLayoutParams)
            FreeformHelper.addFreeformToSet(this)
        }
    }


    /**
     * 从后台恢复
     */
    override fun fromBackstage() {
        windowManager.addView(binding.root, windowLayoutParams)
        FreeformHelper.removeMiniFreeformFromSet(this)
        FreeformHelper.addFreeformToSet(this)
        isBackstage = false
    }

    private fun refreshFreeformSize() {
        freeformHeight = if (FreeformHelper.screenIsPortrait(screenRotation)) (config.maxHeight * 0.75).roundToInt() else (config.maxHeight * 0.9).roundToInt()
        freeformHeight += cardHeightMargin.roundToInt()
        freeformWidth = ((freeformHeight + cardWidthMargin) * config.widthHeightRatio).roundToInt()
    }

    private fun refreshScale() {
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()
        binding.freeformRoot.scaleX = mScaleX
        binding.freeformRoot.scaleY = mScaleY
    }

    private fun refreshTouchScale() {
        scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
        scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
    }

    private fun resetScale() {
        refreshTouchScale()
        refreshScale()
    }

    //按下时的坐标
    private var lastX = -1f
    private var lastY = -1f
    //当前正在操作的界面id
    private var touchId = -1
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (FreeformHelper.isShowingFirst(this)) {
                    handleDownEvent(v, event)
                } else {
                    //不是处于最上层要移动到最上层
                    moveToFirst()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                handleMoveEvent(v, event)
            }
            MotionEvent.ACTION_UP -> {
                handleUpEvent(v, event)
            }
        }
        return true
    }

    private fun handleDownEvent(v: View, event: MotionEvent) {
        if (touchId == -1) touchId = v.id

        lastX = event.rawX
        lastY = event.rawY
        when(v.id) {
            R.id.root -> {
                backgroundGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }
        }
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when(v.id) {
            R.id.root -> {
                backgroundGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                if (touchId == R.id.middleView) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY

                    handleToFloatScale(0f, dy)
                    lastX = event.rawX
                    lastY = event.rawY

                    middleGestureDetector.onTouchEvent(event)
                }
            }
            R.id.sideView -> {
                if (touchId == R.id.sideView) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY

                    handleToFloatScale(dx, 0f)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
        }
    }

    private fun handleUpEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.root -> {
                backgroundGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
                notifyToFloat()
            }
            R.id.sideView -> {
                notifyToFloat()
            }
        }
        touchId = -1
    }

    private var setDisplayIdMethod: Method? = null

    private fun genFloatViewLocation(): IntArray {
        return intArrayOf(
            (if (hangUpPosition[0]) ((realScreenWidth - hangUpViewWidth - screenPaddingX) / -2)
                else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2),
            (if (hangUpPosition[1]) (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                else (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2),
        )
    }

    private fun getRestoreFreeformScale(): FloatArray {
        refreshFreeformSize()
        return floatArrayOf(
            freeformWidth / rootWidth.toFloat(),
            freeformHeight / rootHeight.toFloat(),
        )
    }

    private fun cardViewMarginAnim(topStartMargin: Int, bottomStartMargin: Int, rightStartMargin: Int, topEndMargin: Int, bottomEndMargin: Int, rightEndMargin: Int): Animator {
        return AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(topStartMargin, topEndMargin)
                    .apply {
                        addUpdateListener {
                            binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                                topMargin = it.animatedValue as Int
                            }
                        }
                    },
                ValueAnimator.ofInt(bottomStartMargin, bottomEndMargin)
                    .apply {
                        addUpdateListener {
                            binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                                bottomMargin = it.animatedValue as Int
                            }
                        }
                    },
                ValueAnimator.ofInt(rightStartMargin, rightEndMargin)
                    .apply {
                        addUpdateListener {
                            binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                                rightMargin = it.animatedValue as Int
                            }
                        }
                    },
            )
        }
    }

    private fun moveViewAnim(startCoordinate: IntArray, endCoordinate: IntArray): Animator {
        val moveAnim = AnimatorSet()
        if (endCoordinate[0] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[0], endCoordinate[0])
                    .apply {
                        addUpdateListener {
                            windowManager.updateViewLayout(
                                binding.root,
                                windowLayoutParams.apply {
                                    x = it.animatedValue as Int
                                })
                        }
                    },
            )
        }
        if (endCoordinate[1] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[1], endCoordinate[1])
                    .apply {
                        addUpdateListener {
                            windowManager.updateViewLayout(
                                binding.root,
                                windowLayoutParams.apply {
                                    y = it.animatedValue as Int
                                })
                        }
                    },
            )
        }
        return moveAnim
    }

    private var isZoomOut = false

    private fun handleToFloatScale(dx: Float, dy: Float) {
        if (isHangUp) return

        if (dy != 0f) {
            val tempHeight = freeformHeight + dy
            if (tempHeight >= hangUpViewHeight && tempHeight <= rootHeight * 0.9) {
                freeformHeight += dy.roundToInt()
                freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                binding.freeformRoot.scaleX = mScaleX
                binding.freeformRoot.scaleY = mScaleY
                isZoomOut = true
            }
        } else if (dx != 0f) {
            val tempWidth = freeformWidth + dx
            if (tempWidth >= hangUpViewWidth && tempWidth <= rootWidth * 0.9) {
                freeformWidth += dx.roundToInt()
                freeformHeight = ((freeformWidth / config.widthHeightRatio) - cardWidthMargin).roundToInt()

                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                binding.freeformRoot.scaleX = mScaleX
                binding.freeformRoot.scaleY = mScaleY
                isZoomOut = true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun notifyToFloat() {
        if (isZoomOut) {
            val scaleX: Float = hangUpViewWidth / rootWidth.toFloat()
            val scaleY: Float = hangUpViewHeight / rootHeight.toFloat()

            if (mScaleY <= goFloatScale) {
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, scaleX),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, scaleY),
                        ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                        ObjectAnimator.ofFloat(binding.sideBar.root, View.ALPHA, 0f),
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0,
                            0,
                            0,
                        ),
                    )
                    addListener(object : AnimatorListener {
                        override fun onAnimationStart(animation: Animator) {
                            val windowCoordinate = intArrayOf(
                                windowLayoutParams.x,
                                windowLayoutParams.y,
                            )

                            var location = genFloatViewLocation()
                            if (lastFloatViewLocation[0] != -1) {
                                location = lastFloatViewLocation
                            }

                            AnimatorSet().apply {
                                playTogether(
                                    moveViewAnim(windowCoordinate, location),
                                    ValueAnimator.ofFloat(config.dimAmount, 0f)
                                        .apply {
                                            addUpdateListener {
                                                windowManager.updateViewLayout(
                                                    binding.root,
                                                    windowLayoutParams.apply {
                                                        dimAmount = it.animatedValue as Float
                                                    })
                                            }
                                        },
                                )
                                startDelay = 125
                                duration = 600
                                interpolator = OvershootInterpolator(1.5f)
                                addListener(object : AnimatorListener {
                                    override fun onAnimationStart(animation: Animator) {
                                        binding.textureView.setOnTouchListener(null)
                                        AnimatorSet().apply {
                                            duration = 100
                                            startDelay = 200
                                            addListener(object :AnimatorListener {
                                                override fun onAnimationStart(animation: Animator) {
                                                }

                                                override fun onAnimationEnd(animation: Animator) {
                                                    binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * scaleX
                                                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                                        height = (rootHeight * scaleY).roundToInt()
                                                        width = (rootWidth * scaleX).roundToInt()
                                                    })

                                                    binding.freeformRoot.scaleY = 1f
                                                    binding.freeformRoot.scaleX = 1f
                                                }

                                                override fun onAnimationCancel(animation: Animator) {
                                                }

                                                override fun onAnimationRepeat(animation: Animator) {
                                                }
                                            })
                                            start()
                                        }
                                        isHangUp = true
                                    }

                                    override fun onAnimationEnd(animation: Animator) {
                                        mScaleX = scaleX
                                        mScaleY = scaleY
                                        binding.textureView.setOnTouchListener(floatViewTouchListener())

                                        setWindowEnableUpdateAnimation()
                                    }

                                    override fun onAnimationCancel(animation: Animator) {
                                    }

                                    override fun onAnimationRepeat(animation: Animator) {
                                    }

                                })
                                start()
                            }
                        }

                        override fun onAnimationEnd(animation: Animator) {
                        }

                        override fun onAnimationCancel(animation: Animator) {
                        }

                        override fun onAnimationRepeat(animation: Animator) {
                        }
                    })
                    duration = 200
                    start()
                }
            } else if (mScaleY >= goFullScale){
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, 1f),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, 1f),
                        ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                        ObjectAnimator.ofFloat(binding.sideBar.root, View.ALPHA, 0f),
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0,
                            0,
                            0,
                        ),
                    )
                    addListener(object : AnimatorListener {
                        override fun onAnimationStart(animation: Animator) {
                            isDestroy = true
                            virtualDisplay.resize(realScreenWidth, realScreenHeight, FreeformHelper.getScreenDpi(context))
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (config.intent != null) {
                                val options = ActivityOptions.makeBasic().apply {
                                    launchDisplayId = Display.DEFAULT_DISPLAY
                                }
                                var result = 0
                                if (config.intent is Intent) {
                                    val intent = config.intent as Intent
                                    intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NO_ANIMATION
                                    result = activityManager!!.startActivityAsUserWithFeature(
                                        null, shell, null, intent,
                                        intent.type, null, null, 0, 0,
                                        null, options.toBundle(), config.userId
                                    )
                                } else if (config.intent is PendingIntent) {
                                    val pendingIntent = Refine.unsafeCast<PendingIntentHidden>(config.intent)
                                    result = activityManager!!.sendIntentSender(
                                        pendingIntent.target, pendingIntent.whitelistToken, 0, null,
                                        null, null, null, options.toBundle())
                                }
                                if (result < 0) {
                                    Toast.makeText(context, "Start Failed Result Code: $result", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                MiFreeform.me?.getControlService()?.execShell("am start -n ${config.packageName}/${config.activityName} --user ${config.userId} --display 0", false)
                            }
                            destroy()
                        }

                        override fun onAnimationCancel(animation: Animator) {
                        }

                        override fun onAnimationRepeat(animation: Animator) {
                        }
                    })
                    duration = 300
                    start()
                }
            } else {
                val restoreScale = getRestoreFreeformScale()

                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScale[0]),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScale[1]),
                    )
                    duration = 300
                    interpolator = OvershootInterpolator(1.5f)
                    start()
                }
            }
            isZoomOut = false
        }
    }

    private fun moveFloatViewLocation(location: IntArray, reset: Boolean) {
        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y,
        )

        AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, location),
            )
            addListener(object : AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    if (reset) {
                        binding.freeformRoot.scaleY = 1f
                        binding.freeformRoot.scaleX = 1f
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            height = hangUpViewHeight
                            width = hangUpViewWidth
                        })
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
            duration = 600
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private fun moveHiddenViewLocation(location: IntArray) {
        val layoutParams = hiddenView.layoutParams as WindowManager.LayoutParams
        val windowCoordinate = intArrayOf(
            layoutParams.x,
            layoutParams.y,
        )

        var position = 0
        // R
        if (layoutParams.x > 0) {
            location[0] += (hangUpViewHeight * config.widthHeightRatio + screenPaddingX).roundToInt()
            position = 1
        // L
        } else {
            location[0] -= (hangUpViewHeight * config.widthHeightRatio + screenPaddingX).roundToInt()
            position = -1
        }

        val floatingButtonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()

        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(windowCoordinate[0], (realScreenWidth - floatingButtonWidth) / 2 * position)
                    .apply {
                        addUpdateListener {
                            windowManager.updateViewLayout(
                                hiddenView,
                                layoutParams.apply {
                                    x = it.animatedValue as Int
                            })
                        }
                    },
                ValueAnimator.ofInt(windowCoordinate[1], location[1])
                    .apply {
                        addUpdateListener {
                            windowManager.updateViewLayout(
                                hiddenView,
                                layoutParams.apply {
                                    y = it.animatedValue as Int
                            })
                        }
                    },
                moveViewAnim(
                    intArrayOf(
                        windowLayoutParams.x,
                        windowLayoutParams.y,
                    ),
                    intArrayOf(
                        location[0],
                        location[1],
                    )
                )
            )
            duration = 600
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private lateinit var hiddenView: View

    private inner class floatViewTouchListener : View.OnTouchListener {
        var moveStartX : Float = -1f
        var moveStartY : Float = -1f

        var movedX : Float = -1f
        var movedY : Float = -1f

        var isMoved : Boolean = false

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            if (v?.id == R.id.root) {
                hideGestureDetector.onTouchEvent(event)
                return true
            }
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    hangUpGestureDetector.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    movedX = event.rawX - moveStartX
                    movedY = event.rawY - moveStartY
                    isMoved = true

                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        x += movedX.toInt()
                        y += movedY.toInt()
                    })

                    moveStartX = event.rawX
                    moveStartY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        val nowX = event.rawX
                        val nowY = event.rawY

                        val windowCoordinate = intArrayOf(
                            windowLayoutParams.x,
                            windowLayoutParams.y,
                        )

                        if (windowCoordinate[1] >= (realScreenHeight - screenPaddingY) / 2) {
                            destroy()
                            isMoved = false
                            return true
                        }

                        hangUpPosition[0] = windowCoordinate[0] <= 0
                        hangUpPosition[1] = windowCoordinate[1] <= 0

                        val location = genFloatViewLocation()

                        location[1] = windowLayoutParams.y

                        // min Y
                        if (nowY < (realScreenHeight * 0.1f)) {
                            location[1] = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                        }

                        // max Y
                        if (nowY > (realScreenHeight - (realScreenHeight * 0.1f))) {
                            location[1] = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                        }

                        var position = 0
                        // L
                        if (windowCoordinate[0] <= (realScreenWidth - (screenPaddingX / 2)) / -2) {
                            location[0] -= (hangUpViewHeight * config.widthHeightRatio + screenPaddingX).roundToInt()
                            position = -1
                        // R
                        } else if (windowCoordinate[0] >= (realScreenWidth - (screenPaddingX / 2)) / 2) {
                            location[0] += (hangUpViewHeight * config.widthHeightRatio + screenPaddingX).roundToInt()
                            position = 1
                        }

                        AnimatorSet().apply {
                            playTogether(
                                moveViewAnim(windowCoordinate, location),
                            )
                            addListener(object : AnimatorListener {
                                override fun onAnimationStart(animation: Animator) {
                                    if (position != 0) {
                                        isHidden = true
                                        hiddenView = LayoutInflater.from(context).inflate(R.layout.view_floating_button, null, false)
                                        hiddenView.root.apply {
                                            setOnTouchListener(this@floatViewTouchListener)
                                        }
                                        if (position == 1)
                                            hiddenView.backgroundView.background = context.getDrawable(R.drawable.floating_button_bg_right)

                                        val floatingButtonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()
                                        val floatingButtonHeight = context.resources.getDimension(R.dimen.floating_button_height).toInt()

                                        windowManager.addView(hiddenView,  WindowManager.LayoutParams().apply {
                                            x = (realScreenWidth - floatingButtonWidth) / 2 * position
                                            y = location[1]
                                            width = floatingButtonWidth
                                            height = floatingButtonHeight
                                            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                            format = PixelFormat.TRANSLUCENT
                                            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                        })
                                    }
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    if (!isHidden) {
                                        lastFloatViewLocation = location
                                    }
                                    isMoved = false
                                }

                                override fun onAnimationCancel(animation: Animator) {
                                }

                                override fun onAnimationRepeat(animation: Animator) {
                                }

                            })
                            duration = 400
                            interpolator = OvershootInterpolator(2f)
                            start()
                        }
                    } else {
                        hangUpGestureDetector.onTouchEvent(event)
                    }
                }
            }
            return true
        }
    }

    private val hangUpGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        @SuppressLint("ClickableViewAccessibility")
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            floatViewToMiniView()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToMiniView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y,
        )

        val center: IntArray = intArrayOf(0, 0)
        val restoreScale = getRestoreFreeformScale()

        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            center[0] = (freeformWidth - rootHeight + screenPaddingX) / 2
        }

        AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, center),
                ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            dimAmount = it.animatedValue as Float
                        })
                    }
                },
            )
            addListener(object : AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    AnimatorSet().apply {
                        startDelay = 95
                        addListener(object : AnimatorListener {
                            override fun onAnimationStart(animation: Animator) {
                                AnimatorSet().apply {
                                    startDelay = 100
                                    addListener(object : AnimatorListener {
                                        override fun onAnimationStart(animation: Animator) {
                                        }

                                        override fun onAnimationEnd(animation: Animator) {
                                            windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                                alpha = 1f
                                            })
                                        }

                                        override fun onAnimationCancel(animation: Animator) {
                                        }

                                        override fun onAnimationRepeat(animation: Animator) {
                                        }
                                    })
                                    start()
                                }
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                    height = rootHeight
                                    width = rootWidth
                                    alpha = 0f
                                })
                                binding.freeformRoot.scaleX = mScaleX
                                binding.freeformRoot.scaleY = mScaleY

                                binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius)
                            }

                            override fun onAnimationCancel(animation: Animator) {
                            }

                            override fun onAnimationRepeat(animation: Animator) {
                            }
                        })
                        start()
                    }
                    var topMargin = 0f
                    var bottomMargin = 0f
                    if (FreeformHelper.screenIsPortrait(screenRotation)) {
                        topMargin = freeformShadow
                        bottomMargin = barHeight
                    }

                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(
                                binding.bottomBar.root,
                                View.ALPHA,
                                1f
                            ),
                            ObjectAnimator.ofFloat(
                                binding.sideBar.root,
                                View.ALPHA,
                                1f
                            ),
                            ObjectAnimator.ofFloat(
                                binding.freeformRoot,
                                View.SCALE_X,
                                mScaleX,
                                restoreScale[0]
                            ),
                            ObjectAnimator.ofFloat(
                                binding.freeformRoot,
                                View.SCALE_Y,
                                mScaleY,
                                restoreScale[1]
                            ),
                            cardViewMarginAnim(
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                                topMargin.roundToInt(),
                                bottomMargin.roundToInt(),
                                cardWidthMargin.roundToInt(),
                            ),
                        )
                        duration = 600
                        startDelay = 125
                        interpolator = OvershootInterpolator(1.5f)
                        start()
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }

            })
            duration = 300
            interpolator = DecelerateInterpolator()
            start()
        }

        isHangUp = false

        setWindowNoUpdateAnimation()
    }

    private fun hiddenViewToFloatView(goMiniView: Boolean) {
        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y,
        )

        hangUpPosition[0] = windowCoordinate[0] <= 0
        hangUpPosition[1] = windowCoordinate[1] <= 0

        val location: IntArray = intArrayOf(
            (if (hangUpPosition[0])
                ((realScreenWidth - hangUpViewHeight * config.widthHeightRatio - screenPaddingX) / -2).roundToInt()
            else
                ((realScreenWidth - hangUpViewHeight * config.widthHeightRatio - screenPaddingX) / 2).roundToInt()),
            -1,
        )

        AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, location),
            )
            addListener(object : AnimatorListener {
                @SuppressLint("ClickableViewAccessibility")
                override fun onAnimationStart(animation: Animator) {
                    hiddenView.root.setOnTouchListener(null)
                    windowManager.removeView(hiddenView)
                    isHidden = false
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!isHidden) {
                        lastFloatViewLocation = intArrayOf(
                            location[0],
                            windowCoordinate[1],
                        )
                    }
                    if (goMiniView) {
                        floatViewToMiniView()
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }

            })
            duration = 400
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private val hideGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hiddenViewToFloatView(false)
            return true
        }
    })

    override fun destroy() {
        //记录位置
        if (viewModel.getBooleanSp("remember_freeform_position", false)) {
            val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                sp.edit()
                    .putInt(REMEMBER_LAND_X, windowLayoutParams.x)
                    .putInt(REMEMBER_LAND_Y, windowLayoutParams.y)
                    .putInt(REMEMBER_LAND_HEIGHT, freeformHeight)
                    .apply()
            } else {
                sp.edit()
                    .putInt(REMEMBER_X, windowLayoutParams.x)
                    .putInt(REMEMBER_Y, windowLayoutParams.y)
                    .putInt(REMEMBER_HEIGHT, freeformHeight)
                    .apply()
            }
        }

        if (isHidden) {
            windowManager.removeView(hiddenView)
        }

        isDestroy = true
        try {
            windowManager.removeViewImmediate(binding.root)
        }catch (e: Exception) { }
        virtualDisplay.surface.release()
        virtualDisplay.release()

        try {
            iWindowManager?.removeRotationWatcher(iRotationWatcher)
        } catch (e: Exception) {}


        try {
            screenListener.unregisterListener()
        } catch (e: Exception) {}

        //移除小窗管理
        FreeformHelper.removeFreeformFromSet(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityTaskManager?.unregisterTaskStackListener(taskStackListener)
        }
    }

    init {
        if (config.packageName.isEmpty() && config.intent == null) {
            Toast.makeText(context,"Missing Args Start Failed", Toast.LENGTH_SHORT).show()
        } else
        if (MiFreeform.me?.getControlService()?.asBinder()?.pingBinder() == true) {
            if (config.packageName.isEmpty()) {
                if (config.intent is Intent) {
                    val intent = config.intent as Intent
                    if (intent.component != null) {
                        config.packageName = intent.component!!.packageName
                    }
                } else if (config.intent is PendingIntent) {
                    val pendingIntent = config.intent as PendingIntent
                    config.packageName = pendingIntent.creatorPackage!!
                }
            }
            if (config.userId == -1) {
                config.userId = Refine.unsafeCast<ContextHidden>(context).userId
            }
            //尝试恢复小窗状态
            //优化 如果小窗移动到屏幕外导致无法控制，可以尝试从侧边栏再次点击该应用以移动到屏幕中心 q220917.4
            if (FreeformHelper.isAppInFreeform(config.packageName, config.userId)) {
                val freeformView = FreeformHelper.getFreeformStackSet().getByPackageName(config.packageName, config.userId)
                freeformView?.toScreenCenter()
                freeformView?.moveToFirst()
            } else if (FreeformHelper.isAppInMiniFreeform(config.packageName, config.userId)) {
                FreeformHelper.getMiniFreeformStackSet().getByPackageName(config.packageName, config.userId)?.fromBackstage()
            } else {
                FreeformHelper.checkAndClean()
                initSystemService()
                initConfig()

                //youtube单独适配
                if (config.packageName == YOUTUBE) {
                    config.activityName = YOUTUBE_ACTIVITY
                }
                initView()
            }
        }
        else {
            MiFreeform.me?.initShizuku()
            Toast.makeText(context, context.getString(R.string.service_not_running), Toast.LENGTH_SHORT).show()
        }
    }

    //优化 将触摸设置为一等公民，以支持多点触控，也可以看一下为什么那样，多点触控就不支持了... q220906.1
    private inner class TouchListener : View.OnTouchListener{
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (FreeformHelper.isShowingFirst(this@FreeformView)) {
                        touchId = R.id.textureView
                    } else {
                        moveToFirst()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touchId = -1
                }
            }
            return true
        }

        /**
         * 触控处理
         */
        private fun handleTouch(event: MotionEvent) {
            val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
            val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
            for (i in 0 until event.pointerCount) {
                val oldCoords = MotionEvent.PointerCoords()
                val pointerProperty = MotionEvent.PointerProperties()
                event.getPointerCoords(i, oldCoords)
                event.getPointerProperties(i, pointerProperty)
                pointerCoords[i] = oldCoords
                pointerCoords[i]!!.apply {
                    x = oldCoords.x / scaleX
                    y = oldCoords.y / scaleY
                }
                pointerProperties[i] = pointerProperty
            }

            val newEvent = MotionEvent.obtain(
                event.downTime,
                event.eventTime,
                event.action,
                event.pointerCount,
                pointerProperties,
                pointerCoords,
                event.metaState,
                event.buttonState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags,
                event.source,
                event.flags
            )

            setDisplayIdMethod?.invoke(newEvent, virtualDisplay.display.displayId)
            inputManager!!.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        }
    }

    private inner class TouchListenerPreQ : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (FreeformHelper.isShowingFirst(this@FreeformView)) {
                        touchId = R.id.textureView
                    } else {
                        moveToFirst()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touchId = -1
                }
            }
            return true
        }

        /**
         * 触控处理
         */
        private fun handleTouch(event: MotionEvent) {
            val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
            val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
            for (i in 0 until event.pointerCount) {
                val oldCoords = MotionEvent.PointerCoords()
                val pointerProperty = MotionEvent.PointerProperties()
                event.getPointerCoords(i, oldCoords)
                event.getPointerProperties(i, pointerProperty)
                pointerCoords[i] = oldCoords
                pointerCoords[i]!!.apply {
                    x = oldCoords.x / scaleX
                    y = oldCoords.y / scaleY
                }
                pointerProperties[i] = pointerProperty
            }

            val newEvent = MotionEvent.obtain(
                event.downTime,
                event.eventTime,
                event.action,
                event.pointerCount,
                pointerProperties,
                pointerCoords,
                event.metaState,
                event.buttonState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags,
                event.source,
                event.flags
            )
            inputManager?.injectInputEvent(newEvent, virtualDisplay.display.displayId)
            newEvent.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class MTaskStackListener : TaskStackListener() {
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            if (taskInfo.taskId == taskId) {
                scope.launch(Dispatchers.Main) {
                    destroy()
                }
            }
        }

        override fun onTaskDisplayChanged(tId: Int, newDisplayId: Int) {
            if (taskId == -1 && newDisplayId == virtualDisplay.display.displayId) taskId = tId

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (config.useSuiRefuseToFullScreen && !isDestroy && tId == taskId && newDisplayId == Display.DEFAULT_DISPLAY) {
                    activityTaskManager?.moveRootTaskToDisplay(tId, virtualDisplay.display.displayId)
                }
            }
        }

        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            try {
                val userId = taskInfo::class.java.getField("userId").get(taskInfo)
                if (taskInfo.baseActivity!!.packageName == config.packageName && userId == config.userId) {
                    taskId = taskInfo.taskId
                }
            } catch (e: Exception) { }
        }

        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            //q220902.2 某些竖屏软件也会横屏，经查，会有一个requestedOrientation为2的情况，将其转为1
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }

        //q220903.2 Android 10系统上需要该回调监听
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }
    }

    companion object {
        private const val TAG = "FreeformView"

        private const val YOUTUBE = "com.google.android.youtube"
        private const val YOUTUBE_ACTIVITY = "com.google.android.youtube.HomeActivity"

        const val REMEMBER_X = "freeform_remember_x"
        const val REMEMBER_Y = "freeform_remember_y"
        const val REMEMBER_LAND_X = "freeform_remember_land_x"
        const val REMEMBER_LAND_Y = "freeform_remember_land_y"
        const val REMEMBER_HEIGHT = "freeform_remember_height"
        const val REMEMBER_LAND_HEIGHT = "freeform_remember_land_height"

        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0
    }
}
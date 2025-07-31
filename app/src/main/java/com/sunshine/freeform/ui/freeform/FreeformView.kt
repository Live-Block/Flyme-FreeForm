package com.sunshine.freeform.ui.freeform

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.animation.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import androidx.core.view.WindowInsetsCompat
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.databinding.ViewFreeformFlymeBinding
import com.sunshine.freeform.databinding.ViewFloatingButtonBinding
import com.sunshine.freeform.utils.ServiceUtils.activityTaskManager
import com.sunshine.freeform.utils.ServiceUtils.displayManager
import com.sunshine.freeform.utils.ServiceUtils.iWindowManager
import com.sunshine.freeform.utils.ServiceUtils.inputManager
import com.sunshine.freeform.utils.ServiceUtils.windowManager
import kotlinx.coroutines.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformView(
    override var config: FreeformConfig,
    private val context: Context,
    private var virtualDisplay: VirtualDisplay,
    var screenListener: ScreenListener,
) : FreeformViewAbs(config), View.OnTouchListener, ScreenListener.ScreenStateListener {

    //ViewModel
    private val viewModel = FreeformViewModel(context)

    private val scope = MainScope()

    //默认屏幕，用于获取横竖屏状态
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //界面binding
    private lateinit var binding: ViewFreeformFlymeBinding

    private lateinit var backgroundView: View

    //该小窗是否已经销毁
    var isDestroy = false

    // 修复动画过程中调整方向导致的问题
    private var isAnimating = false
    private var pendingOrientationChange = false

    //是否处于隐藏状态，当打开米窗的正在运行小窗界面时，应当隐藏所有小窗
    var isHidden = false

    //小窗中应用的taskId
    private var taskList = ArrayList<Int>()

    // 输入法高度检测相关变量
    private var isKeyboardVisible = false
    private var originalWindowY = 0
    private var screenHeight = 0

    //叠加层Params
    private val windowLayoutParams = WindowManager.LayoutParams()

    private val backgroundViewLayoutParams = WindowManager.LayoutParams()

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

    //触摸监听
    private val touchListener = TouchListener()
    private val touchListenerPreQ = TouchListenerPreQ()

    //屏幕宽高，不保证大小
    private var realScreenWidth = 0
        get() {
            var tmpWidth = context.resources.displayMetrics.widthPixels
            var tmpHeight = context.resources.displayMetrics.heightPixels

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rect = windowManager.currentWindowMetrics.bounds
                tmpWidth = rect.width()
                tmpHeight = rect.height()
            }
            return if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180)
                        min(tmpWidth, tmpHeight)
                   else
                        max(tmpWidth, tmpHeight)
        }
    private var realScreenHeight = 0
        get() {
            var tmpWidth = context.resources.displayMetrics.widthPixels
            var tmpHeight = context.resources.displayMetrics.heightPixels

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rect = windowManager.currentWindowMetrics.bounds
                tmpWidth = rect.width()
                tmpHeight = rect.height()
            }

            return if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180)
                        max(tmpWidth, tmpHeight)
                   else
                        min(tmpWidth, tmpHeight)
        }

    //小窗的"尺寸"，该尺寸只在小窗内屏幕方向改变时变化
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //小窗界面的宽高，该宽高不随着屏幕、小窗方向改变而改变，即h>w恒成立。该尺寸只在物理屏幕方向变化时变化
    private var freeformHeight = 0
    private var freeformWidth = 0

    // 挂起后与边缘的 Padding
    private var screenPaddingX: Int = context.resources.getDimension(R.dimen.freeform_screen_width_padding).roundToInt()
    private var screenPaddingY: Int = context.resources.getDimension(R.dimen.freeform_screen_height_padding).roundToInt()

    // Margins
    private var barHeight: Float = context.resources.getDimension(R.dimen.bottom_bar_height_flyme)
    private var freeformShadow: Float = context.resources.getDimension(R.dimen.freeform_shadow)
    private var cardHeightMargin: Float = 0f
        get() {
            return if (FreeformHelper.screenIsPortrait(screenRotation)) (barHeight + freeformShadow) else 0f
        }
    private var cardWidthMargin: Float = 0f
        get() {
            return if (FreeformHelper.screenIsPortrait(screenRotation)) 0f else barHeight
        }

    // 存储上一次的悬浮位置
    private var lastFloatViewLocation: IntArray = intArrayOf(-1, -1)

    // 小窗大小
    private var hangUpViewHeight = 0
    private var hangUpViewWidth = 0

    // root
    private var rootHeight = 0
        get() {
            var tmp = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenHeight else realScreenWidth
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = ((rootWidth * config.widthHeightRatio) + cardHeightMargin).roundToInt()
                if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                    tmp = realScreenHeight
                }
            }
            return tmp
        }
    private var rootWidth = 0
        get() {
            var tmp = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenWidth else realScreenHeight
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = realScreenWidth
            }
            return tmp
        }

    // 小窗缩放比例
    private var mScaleX = 1f
        set(value) {
            field = value
            binding.freeformRoot.scaleX = value
        }
    private var mScaleY = 1f
        set(value) {
            field = value
            binding.freeformRoot.scaleY = value
        }

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
            if (!isFloating) {
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
                if (isFloating) {
                    if (isHidden) {
                        hiddenViewToFloatView(false)
                    }

                    binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * (hangUpViewWidth / rootWidth)

                    val windowCoordinate = intArrayOf(
                        windowLayoutParams.x,
                        windowLayoutParams.y,
                    )

                    val location = genFloatViewLocation()
                    lastFloatViewLocation[0] = location[0]

                    AnimatorSet().apply {
                        playTogether(
                            ValueAnimator.ofInt(windowLayoutParams.width, hangUpViewWidth)
                                .apply {
                                    addUpdateListener {
                                        windowManager.updateViewLayout(
                                            binding.root,
                                            windowLayoutParams.apply {
                                                width = it.animatedValue as Int
                                            })
                                    }
                                },
                            ValueAnimator.ofInt(windowLayoutParams.height, hangUpViewHeight)
                                .apply {
                                    addUpdateListener {
                                        windowManager.updateViewLayout(
                                            binding.root,
                                            windowLayoutParams.apply {
                                                height = it.animatedValue as Int
                                            })
                                    }
                                },
                            moveViewAnim(windowCoordinate, lastFloatViewLocation)
                        )
                        duration = 200
                        start()
                    }
                }
            } else {
                initConfig()
            }
        }

    //是否处于挂起状态
    var isFloating = false
    //挂起位置，0：是否在左，1：是否在上
    private val hangUpPosition = booleanArrayOf(false, true)

    @RequiresApi(Build.VERSION_CODES.Q)
    private val taskStackListener = MTaskStackListener()

    fun initSystemService() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku binder is not available")
                return
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityTaskManager.registerTaskStackListener(taskStackListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize system services", e)
        }
    }

    fun initConfig() {
        initFloatViewSize()

        config.freeformDpi = FreeformHelper.getScreenDpi(context)
        val tmpDpi = viewModel.getIntSp("freeform_scale", 50)
        if (tmpDpi > 50) {
            config.freeformDpi = tmpDpi
        }

        //优化 QQ和微信也支持缩放了 q220917.1
        freeformScreenHeight = (min(realScreenHeight, realScreenWidth) / config.widthHeightRatio).roundToInt()
        freeformScreenWidth = (freeformScreenHeight * config.widthHeightRatio).roundToInt()

        config.rememberPosition = viewModel.getBooleanSp("remember_freeform_position", false)
        if (config.rememberPosition) {
            lastFloatViewLocation[0] = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_X, -1)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_X, -1)
            }
            lastFloatViewLocation[1] = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_Y, -1)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_Y, -1)
            }
        }
        config.floatViewSize = (viewModel.getIntSp("freeform_float_view_size", 20)) / 100.toFloat()
        config.freeformSize = (viewModel.getIntSp("freeform_size", 75)) / 100.toFloat()
        config.freeformSizeLand = (viewModel.getIntSp("freeform_size_land", 90)) / 100.toFloat()
        config.dimAmount = (viewModel.getIntSp("freeform_dimming_amount", 20)) / 100.toFloat()

        viewModel.registerOnSharedPreferenceChangeListener(sharedPreferencesChangeListener)

        config.useSuiRefuseToFullScreen = viewModel.getBooleanSp("use_sui_refuse_to_fullscreen", false)
        config.manualAdjustFreeformRotation = viewModel.getBooleanSp("manual_adjust_freeform_rotation", false)
    }

    private fun initFloatViewSize() {
        // 基于屏幕方向计算悬浮小窗大小
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            // 竖屏状态下，根据设定的比例计算高度和宽度
            // 直接以屏幕宽度为基准计算正确宽高比的小窗
            hangUpViewWidth = (realScreenWidth * config.floatViewSize).roundToInt()
            hangUpViewHeight = (hangUpViewWidth / config.widthHeightRatio).roundToInt()
        } else {
            // 横屏状态保持原有逻辑
            hangUpViewHeight = (rootHeight * config.floatViewSize).roundToInt()
            hangUpViewWidth = (hangUpViewHeight * config.widthHeightRatio).roundToInt()
        }

        // 横屏内容特殊处理逻辑保持不变
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            hangUpViewWidth = (realScreenHeight * config.floatViewSize).roundToInt()
            hangUpViewHeight = (hangUpViewWidth * config.widthHeightRatio).roundToInt()
            if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                hangUpViewWidth = (realScreenWidth * config.floatViewSize).roundToInt()
                hangUpViewHeight = (hangUpViewWidth * config.widthHeightRatio).roundToInt()
            }
        }
    }

    // Method to elevate the window when the keyboard is shown
    private fun elevateWindow(keyboardHeight: Int) {
        // 根据屏幕高度和输入法高度计算抬高值
        val elevationRatio = 0.47 // 抬高比例，你可以调整这个比例
        val calculatedElevation = (keyboardHeight * elevationRatio).toInt()
        // 根据计算结果抬高窗口
        val newY = originalWindowY - calculatedElevation
        windowManager.updateViewLayout(
            binding.root,
            windowLayoutParams.apply {
                y = newY
            }
        )
    }

    // Method to reset the window position when the keyboard is hidden
    private fun resetWindowPosition() {
        windowManager.updateViewLayout(
            binding.root,
            windowLayoutParams.apply {
                y = originalWindowY
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("ClickableViewAccessibility")
    fun initView() {
        binding = ViewFreeformFlymeBinding.bind(LayoutInflater.from(context).inflate(R.layout.view_freeform_flyme, null, false))

        // 添加输入法监听
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.root.setOnApplyWindowInsetsListener { view, windowInsets ->
                val imeHeight = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val isImeVisible = imeHeight > 0
                
                if (isImeVisible != isKeyboardVisible) {
                    isKeyboardVisible = isImeVisible
                    if (isImeVisible) {
                        // 保存原始位置
                        originalWindowY = windowLayoutParams.y
                        // 只在非迷你状态且非横屏时调整位置
                        if (!isFloating && FreeformHelper.screenIsPortrait(screenRotation)) {
                            elevateWindow(imeHeight)
                        }
                    } else {
                        // 恢复原始位置
                        if (!isFloating && FreeformHelper.screenIsPortrait(screenRotation)) {
                            resetWindowPosition()
                        }
                    }
                }
                windowInsets
            }
        }

        backgroundView = View(context)
        backgroundView.setBackgroundColor(Color.TRANSPARENT)
        backgroundView.setOnTouchListener(this@FreeformView)
        backgroundView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                performBackKey()
            }
            true
        }
        backgroundView.id = View.generateViewId()

        binding.root.setOnTouchListener(this)
        binding.bottomBar.middleView.setOnTouchListener(this@FreeformView)
        binding.bottomBar.sideView.setOnTouchListener(this@FreeformView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            hangUpPosition[0] = true
            binding.apply {
                (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                    topMargin = 0
                    bottomMargin = 0
                    rightMargin = barHeight.roundToInt()
                }
            }
        }

        refreshFreeformSize()

        initFloatBar()

        resetScale()

        binding.freeformRoot.alpha = 1f
        binding.textureView.alpha = 0f
    }

    private fun performBackKey() {
        val downEvent = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK,
            0
        )
        val upEvent = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_BACK,
            0
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(downEvent, 0)

                setDisplayIdMethod?.invoke(upEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, 0)
            } else {
                inputManager.injectInputEvent(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, virtualDisplay.display.displayId)
            }
        }
    }

    private fun initFloatBar() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    barHeight.roundToInt(),
                ).apply {
                    topToBottom = R.id.cardRoot
                    startToEnd = ConstraintLayout.LayoutParams.UNSET
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.VISIBLE
                sideView.visibility = View.GONE
            }
        } else {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    barHeight.roundToInt(),
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToEnd = R.id.cardRoot
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.GONE
                sideView.visibility = View.VISIBLE
            }
        }
    }

    private fun initDisplay() {
        virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, config.freeformDpi)

        screenListener.addScreenStateListener(this@FreeformView)
    }

    override fun onScreenOn() {
    }

    override fun onScreenOff() {
        //挂起状态无需更新
        //修复 在有正在运行程序的情况下锁屏，米窗崩溃的问题 q220902.1
        //优化 锁屏后小窗的状态 q220917.3
        if (!isHidden) {
            windowLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    override fun onUserPresent() {
        //挂起状态无需更新
        if (!isHidden) {
            windowLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM

            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun initOrientationChangedListener() {
        iWindowManager.watchRotation(iRotationWatcher, Display.DEFAULT_DISPLAY)
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

    fun showWindow() {
        initDisplay()
        initOrientationChangedListener()
        initTextureViewListener()

        windowLayoutParams.apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }

        setWindowNoUpdateAnimation()

        windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
        }

        //横屏移动到屏幕左侧显示小窗
        if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                //往上移动一些
                y = genCenterLocation()[1]
            }
        }

        backgroundViewLayoutParams.apply {
            dimAmount = config.dimAmount
            format = PixelFormat.RGBA_8888
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        runCatching {
            windowManager.addView(backgroundView, backgroundViewLayoutParams)
            windowManager.addView(binding.root, windowLayoutParams)
        }.onFailure {
            runCatching {
                windowManager.removeViewImmediate(backgroundView)
                windowManager.removeViewImmediate(binding.root)
            }

            if (Settings.canDrawOverlays(context)) {
                windowManager.addView(backgroundView, backgroundViewLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
                windowManager.addView(binding.root, windowLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
            } else {
                destroy()
                runCatching {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(
                        intent
                    )
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 禁用更新过渡动画并添加系统应用覆盖标志
     */
    private fun setWindowNoUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            val sysAppOverlay: Field = layoutParamsClass.getField("PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY")
            
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            val sysAppOverlayFlag: Int = sysAppOverlay.getInt(windowLayoutParams)
            
            privateFlagsValue = privateFlagsValue or noAnimFlag or sysAppOverlayFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setWindowEnableUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            val sysAppOverlay: Field = layoutParamsClass.getField("PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY")
            
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            val sysAppOverlayFlag: Int = sysAppOverlay.getInt(windowLayoutParams)
            
            // 移除动画标志但保留系统应用覆盖标志
            privateFlagsValue = (privateFlagsValue and noAnimFlag.inv()) or sysAppOverlayFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun onFreeFormRotationChanged() {
        if (isDestroy) return

        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        initFloatViewSize()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            freeformScreenHeight = tempHeight
            freeformScreenWidth = tempWidth
        } else {
            freeformScreenHeight = tempWidth
            freeformScreenWidth = tempHeight
        }
        refreshFreeformSize()
        resetScale()
        resizeVirtualDisplay()
        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
            x = genCenterLocation()[0]
            y = genCenterLocation()[1]
        })
    }

    private fun onScreenOrientationChanged() {
        initFloatViewSize()

        refreshFreeformSize()
        initFloatBar()

        val location = genFloatViewLocation()
        lastFloatViewLocation = location

        refreshTouchScale()
        refreshActionScale()

        if (isFloating && !isHidden) {
            moveFloatViewLocation(location, true)
        } else if (isHidden) {
            moveHiddenViewLocation(location)
        } else {
            windowLayoutParams.apply {
                height = rootHeight
                width = rootWidth
            }
            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                topMargin = freeformShadow.roundToInt()
                bottomMargin = barHeight.roundToInt()
                rightMargin = 0
            }
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
            if(!FreeformHelper.screenIsPortrait(screenRotation)) {
                binding.apply {
                    (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                        topMargin = 0
                        bottomMargin = 0
                        rightMargin = barHeight.roundToInt()
                    }
                }
            }
            resetScale()
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun genCenterLocation(): IntArray {
        val center = intArrayOf(0, 0)
        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            center[0] = (freeformWidth - rootHeight + screenPaddingX) / 2
            if (!hangUpPosition[0])
                center[0] = (freeformWidth - rootHeight + screenPaddingX) / -2
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / 2
                if (!hangUpPosition[0])
                    center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / -2
            }
        }
        return center
    }

    private fun resizeVirtualDisplay() {
        virtualDisplay.resize(
            freeformScreenWidth,
            freeformScreenHeight,
            config.freeformDpi
        )
    }

    /**
     * 如果小窗无法控制了，可以尝试移动到屏幕中心以控制
     */
    override fun toScreenCenter() {
        if (isFloating) return
        windowLayoutParams.x = 0
        windowLayoutParams.y = 0
    }

    override fun moveToFirst() {
        if (isFloating) {
            if (isHidden) {
                hiddenViewToFloatView(true)
            } else {
                floatViewToMiniView()
            }
        }
    }

    private fun refreshFreeformSize() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            // 竖屏状态，以宽度为基准计算，确保宽高比正确
            freeformWidth = (rootWidth * config.freeformSize).roundToInt()
            val contentHeight = (freeformWidth - (freeformShadow * 2)) / config.widthHeightRatio
            freeformHeight = (contentHeight + cardHeightMargin).roundToInt()
        } else {
            // 横屏状态保持原有逻辑
            freeformHeight = (rootWidth * config.freeformSizeLand).roundToInt()
            freeformHeight += cardHeightMargin.roundToInt()
            freeformWidth = ((freeformHeight + cardWidthMargin) * config.widthHeightRatio).roundToInt()
        }
        
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            if (freeformHeight > rootWidth) {
                freeformWidth = (rootWidth - (rootWidth * 0.05)).roundToInt()
                freeformHeight = ((freeformWidth + (cardHeightMargin * 1.75)) * config.widthHeightRatio) .roundToInt()
            }
            if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                freeformWidth = (realScreenWidth / 2 + cardWidthMargin).roundToInt()
                freeformHeight = ((freeformWidth * config.widthHeightRatio) * 0.95).roundToInt()
            }
        }
    }

    private fun refreshScale() {
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()
    }

    private fun refreshTouchScale() {
        scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
        scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
    }

    private fun refreshActionScale() {
        goFloatScale = (freeformHeight * 0.8f) / rootHeight
        goFullScale = (freeformHeight * 1.1f) / rootHeight
    }

    private fun resetScale() {
        refreshTouchScale()
        refreshScale()
        refreshActionScale()
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
                handleDownEvent(v, event)
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
            R.id.root, backgroundView.id -> {
                backgroundGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }
            R.id.sideView -> {
                middleGestureDetector.onTouchEvent(event)
            }
        }
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when(v.id) {
            R.id.root, backgroundView.id -> {
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
            R.id.root, backgroundView.id -> {
                backgroundGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
                notifyToFloat()
            }
            R.id.sideView -> {
                notifyToFloat()
                middleGestureDetector.onTouchEvent(event)
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
        if (isFloating) return

        val ratio = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            1 / config.widthHeightRatio
        } else {
            config.widthHeightRatio
        }

        if (dy != 0f) {
            val tempHeight = freeformHeight + dy
            if (tempHeight >= hangUpViewHeight && tempHeight <= rootHeight * 0.9) {
                freeformHeight += dy.roundToInt()

                val contentHeight = freeformHeight - cardHeightMargin
                val contentWidth = contentHeight * ratio
                if (FreeformHelper.screenIsPortrait(screenRotation)) {
                    freeformWidth = (contentWidth + (freeformShadow * 2)).roundToInt()
                } else {
                    freeformWidth = (contentWidth + cardWidthMargin).roundToInt()
                }

                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()

                isZoomOut = true
            }
        } else if (dx != 0f) {
            val tempWidth = freeformWidth + dx
            if (tempWidth >= hangUpViewWidth && tempWidth <= rootWidth * 0.9) {
                freeformWidth += dx.roundToInt()

                val contentWidth = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                    freeformWidth - (freeformShadow * 2)
                } else {
                    freeformWidth - cardWidthMargin
                }
                val contentHeight = contentWidth / ratio
                freeformHeight = (contentHeight + cardHeightMargin).roundToInt()

                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()

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
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0,
                            0,
                            0,
                        ),
                    )
                    addListener(
                        onStart = {
                            isAnimating = true
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
                                                    backgroundView,
                                                    backgroundViewLayoutParams.apply {
                                                        dimAmount = it.animatedValue as Float
                                                    })
                                            }
                                        },
                                )
                                startDelay = 125
                                duration = 550
                                interpolator = OvershootInterpolator(1.5f)
                                addListener(
                                    onStart = {
                                        backgroundView.visibility = View.GONE
                                        binding.textureView.setOnTouchListener(null)
                                        AnimatorSet().apply {
                                            duration = 100
                                            startDelay = 200
                                            addListener(
                                                onEnd = {
                                                    mScaleX = scaleX
                                                    mScaleY = scaleY
                                                    binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * scaleX
                                                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                                        height = (rootHeight * scaleY).roundToInt()
                                                        width = (rootWidth * scaleX).roundToInt()
                                                    })

                                                    binding.freeformRoot.scaleY = 1f
                                                    binding.freeformRoot.scaleX = 1f
                                                }
                                            )
                                            start()
                                        }
                                        isFloating = true
                                    },
                                    onEnd = {
                                        binding.textureView.setOnTouchListener(FloatViewTouchListener())

                                        setWindowEnableUpdateAnimation()

                                        isAnimating = false
                                        if (pendingOrientationChange) {
                                            pendingOrientationChange = false
                                            onFreeFormRotationChanged()
                                        }
                                    },
                                )
                                start()
                            }
                        }
                    )
                    duration = 200
                    start()
                }
            } else if (mScaleY >= goFullScale){
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, 1f),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, 1f),
                        ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0,
                            0,
                            0,
                        ),
                    )
                    addListener(
                        onStart = { isAnimating = true },
                        onEnd = {
                            isAnimating = false
                            context.startService(
                                Intent(context, FreeformService::class.java)
                                    .setAction(FreeformService.ACTION_CALL_INTENT)
                                    .putExtra(FreeformService.EXTRA_DISPLAY_ID, defaultDisplay.displayId)
                            )
                            destroy()
                        }
                    )
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
                    addListener(
                        onStart = { isAnimating = true },
                        onEnd = {
                            isAnimating = false
                            if (pendingOrientationChange) {
                                pendingOrientationChange = false
                                onFreeFormRotationChanged()
                            }
                        }
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
            addListener(
                onStart = {
                    if (reset) {
                        binding.freeformRoot.scaleY = 1f
                        binding.freeformRoot.scaleX = 1f
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            height = hangUpViewHeight
                            width = hangUpViewWidth
                        })
                    }
                }
            )
            duration = 600
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private lateinit var hiddenView: View
    private lateinit var hiddenViewBinding: ViewFloatingButtonBinding

    private fun moveHiddenViewLocation(location: IntArray) {
        val layoutParams = hiddenView.layoutParams as WindowManager.LayoutParams
        val windowCoordinate = intArrayOf(
            layoutParams.x,
            layoutParams.y,
        )

        var position = 0
        // R
        if (layoutParams.x > 0) {
            location[0] += (hangUpViewWidth + screenPaddingX)
            position = 1
        // L
        } else {
            location[0] -= (hangUpViewWidth + screenPaddingX)
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

    private inner class FloatViewTouchListener : View.OnTouchListener {
        var moveStartX : Float = -1f
        var moveStartY : Float = -1f

        var movedX : Float = -1f
        var movedY : Float = -1f
        val minlong = 1.1

        var isMoved : Boolean = false

        @SuppressLint("ClickableViewAccessibility")
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
                    if (Math.abs(movedX) > minlong || Math.abs(movedY) > minlong) {
                        isMoved = true

                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            x += movedX.toInt()
                            y += movedY.toInt()
                        })

                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
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
                            location[0] -= (hangUpViewWidth + screenPaddingX)
                            position = -1
                        // R
                        } else if (windowCoordinate[0] >= (realScreenWidth - (screenPaddingX / 2)) / 2) {
                            location[0] += (hangUpViewWidth + screenPaddingX)
                            position = 1
                        }

                        AnimatorSet().apply {
                            playTogether(
                                moveViewAnim(windowCoordinate, location),
                            )
                            addListener (
                                onStart = {
                                    if (position != 0) {
                                        isHidden = true
                                        val inflater = LayoutInflater.from(context)
                                        hiddenViewBinding = ViewFloatingButtonBinding.inflate(inflater)
                                        hiddenView = hiddenViewBinding.root
                                        hiddenViewBinding.root.setOnTouchListener(this@FloatViewTouchListener)
                                        if (position == 1)
                                            hiddenViewBinding.backgroundView.background = context.getDrawable(R.drawable.floating_button_bg_right)

                                        val floatingButtonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()
                                        val floatingButtonHeight = context.resources.getDimension(R.dimen.floating_button_height).toInt()

                                        windowManager.addView(hiddenView, WindowManager.LayoutParams().apply {
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
                                },
                                onEnd = {
                                    if (!isHidden) {
                                        lastFloatViewLocation = location
                                    }
                                    isMoved = false
                                }
                            )
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

        val restoreScale = getRestoreFreeformScale()
        val center: IntArray = genCenterLocation()

        // 修复 Android14 放大动画卡顿
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {

            val fastDecelerateAnims = AnimatorSet().apply {
                playTogether(
                    moveViewAnim(windowCoordinate, center),
                    ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                        addUpdateListener {
                            windowManager.updateViewLayout(backgroundView, backgroundViewLayoutParams.apply {
                                dimAmount = it.animatedValue as Float
                            })
                        }
                    }
                )
                duration = 300
                interpolator = DecelerateInterpolator()
            }

            var topMargin = 0f
            var bottomMargin = 0f
            if (FreeformHelper.screenIsPortrait(screenRotation)) {
                topMargin = freeformShadow
                bottomMargin = barHeight
            }
            val overshootAnims = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 1f),
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScale[0]),
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScale[1]),
                    cardViewMarginAnim(
                        (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                        (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                        (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                        topMargin.roundToInt(),
                        bottomMargin.roundToInt(),
                        cardWidthMargin.roundToInt()
                    )
                )
                duration = 550
                interpolator = OvershootInterpolator(1.5f)
                startDelay = 125
            }

            val rootAnimator = AnimatorSet().apply {
                playTogether(fastDecelerateAnims, overshootAnims)

                addListener(
                    onStart = {
                        isAnimating = true
                        backgroundView.visibility = View.VISIBLE
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            height = rootHeight
                            width = rootWidth
                        })
                        binding.freeformRoot.scaleX = mScaleX
                        binding.freeformRoot.scaleY = mScaleY
                        binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius)
                    },
                    onEnd = {
                        isAnimating = false
                        if (pendingOrientationChange) {
                            pendingOrientationChange = false
                            onFreeFormRotationChanged()
                        }
                    }
                )
            }

            rootAnimator.start()
        } else {
            // Android13 以下
            AnimatorSet().apply {
                playTogether(
                    moveViewAnim(windowCoordinate, center),
                    ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                        addUpdateListener {
                            windowManager.updateViewLayout(backgroundView, backgroundViewLayoutParams.apply {
                                dimAmount = it.animatedValue as Float
                            })
                        }
                    },
                )
                addListener(
                    onStart = {
                        isAnimating = true
                        AnimatorSet().apply {
                            startDelay = 95
                            addListener(
                                onEnd = {
                                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                        height = rootHeight
                                        width = rootWidth
                                    })
                                    binding.freeformRoot.scaleX = mScaleX
                                    binding.freeformRoot.scaleY = mScaleY

                                    binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius)
                                }
                            )
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
                            duration = 550
                            startDelay = 125
                            interpolator = OvershootInterpolator(1.5f)
                            start()
                        }
                    },
                    onEnd = {
                        backgroundView.visibility = View.VISIBLE
                        isAnimating = false
                        if (pendingOrientationChange) {
                            pendingOrientationChange = false
                            onFreeFormRotationChanged()
                        }
                    }
                )
                duration = 300
                interpolator = DecelerateInterpolator()
                start()
            }
        }

        isFloating = false

        setWindowNoUpdateAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToFloatView(goMiniView: Boolean) {
        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y,
        )

        hangUpPosition[0] = windowCoordinate[0] <= 0
        hangUpPosition[1] = windowCoordinate[1] <= 0

        val location: IntArray = intArrayOf(
            (if (hangUpPosition[0])
                ((realScreenWidth - hangUpViewWidth - screenPaddingX) / -2)
            else
                ((realScreenWidth - hangUpViewWidth - screenPaddingX) / 2)),
            -1,
        )

        AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, location),
            )
            addListener(
                onStart = {
                    hiddenViewBinding.root.setOnTouchListener(null)
                    windowManager.removeView(hiddenView)
                    isHidden = false
                },
                onEnd = {
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
            )
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
        // 清理输入法状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 如果输入法是可见的，先重置输入法状态
            if (isKeyboardVisible) {
                isKeyboardVisible = false
                resetWindowPosition()
            }
            // 移除输入法监听器，避免监听器残留
            binding.root.setOnApplyWindowInsetsListener(null)
        }

        //记录位置
        if (viewModel.getBooleanSp("remember_freeform_position", false)) {
            val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                sp.edit()
                    .putInt(REMEMBER_LAND_X, lastFloatViewLocation[0])
                    .putInt(REMEMBER_LAND_Y, lastFloatViewLocation[1])
                    .apply()
            } else {
                sp.edit()
                    .putInt(REMEMBER_X, lastFloatViewLocation[0])
                    .putInt(REMEMBER_Y, lastFloatViewLocation[1])
                    .apply()
            }
        }

        if (isHidden) {
            windowManager.removeView(hiddenView)
        }
        if (isFloating) {
            windowLayoutParams.x = 0
            windowLayoutParams.y = 0
        }

        isDestroy = true
        isHidden = false
        isFloating = false

        // 清理虚拟显示
        runCatching {
            // 在释放surface前发送返回键，强制关闭可能的输入法
            if (virtualDisplay.surface != null) {
                performBackKey()
                // 短暂延迟确保输入法有时间关闭
                SystemClock.sleep(100)
            }
        }

        runCatching {
            windowManager.removeViewImmediate(binding.root)
            windowManager.removeViewImmediate(backgroundView)
        }
        if (virtualDisplay.surface != null) {
            virtualDisplay.surface.release()
            virtualDisplay.surface = null
        }

        runCatching {
            iWindowManager.removeRotationWatcher(iRotationWatcher)
        }

        screenListener.removeScreenStateListener(this@FreeformView)
        viewModel.unregisterOnSharedPreferenceChangeListener(sharedPreferencesChangeListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityTaskManager.unregisterTaskStackListener(taskStackListener)
        }
    }

    //优化 将触摸设置为一等公民，以支持多点触控，也可以看一下为什么那样，多点触控就不支持了... q220906.1
    private inner class TouchListener : View.OnTouchListener{
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchId = R.id.textureView
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
            // 修复滑动惯性
            val newEvent = MotionEvent.obtain(event)
            if (newEvent == null) {
                return
            }

            // 修复y轴偏移问题
            try {
                val invertedScaleX = if (scaleX != 0f && !scaleX.isNaN() && !scaleX.isInfinite()) 1.0f / scaleX else 1.0f
                val invertedScaleY = if (scaleY != 0f && !scaleY.isNaN() && !scaleY.isInfinite()) 1.0f / scaleY else 1.0f

                if (invertedScaleX != 1.0f || invertedScaleY != 1.0f) {
                    val matrix = Matrix()
                    matrix.setScale(invertedScaleX, invertedScaleY)
                    newEvent.transform(matrix)
                }

                // 使用EzXHelper库应用修复
                newEvent.invokeMethod("setDisplayId", args(virtualDisplay.display.displayId), argTypes(Integer.TYPE))
                inputManager.injectInputEvent(newEvent, 0)
            } finally {
                newEvent.recycle()
            }
        }
    }

    private inner class TouchListenerPreQ : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchId = R.id.textureView
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
            // 修复滑动惯性
            val newEvent = MotionEvent.obtain(event)
            if (newEvent == null) {
                return
            }

            // 修复y轴偏移问题
            try {
                val invertedScaleX = if (scaleX != 0f && !scaleX.isNaN() && !scaleX.isInfinite()) 1.0f / scaleX else 1.0f
                val invertedScaleY = if (scaleY != 0f && !scaleY.isNaN() && !scaleY.isInfinite()) 1.0f / scaleY else 1.0f

                if (invertedScaleX != 1.0f || invertedScaleY != 1.0f) {
                    val matrix = Matrix()
                    matrix.setScale(invertedScaleX, invertedScaleY)
                    newEvent.transform(matrix)
                }

                // 使用EzXHelper库应用修复
                newEvent.invokeMethod("setDisplayId", args(virtualDisplay.display.displayId), argTypes(Integer.TYPE))
                inputManager.injectInputEvent(newEvent, 0)
            } finally {
                newEvent.recycle()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class MTaskStackListener : TaskStackListener() {
        private var destroyJob: Job? = null
        private var orientationChangeJob: Job? = null
        override fun onTaskCreated(tId: Int, componentName: ComponentName?) {
            if (config.intent !is Intent) return
            if (componentName?.packageName == config.componentName?.packageName) {
                taskList.add(tId)
            }
            destroyJob?.cancel()
        }

        override fun onTaskRemoved(taskId: Int) {
            taskList.remove(taskId)
        }

        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            if (taskList.contains(taskInfo.taskId)) {
                taskList.remove(taskInfo.taskId)
                if (taskList.isEmpty()) {
                    destroyJob = scope.launch {
                        delay(250)
                        if (!isDestroy) {
                            destroy()
                        }
                    }
                }
            }
        }

        override fun onTaskDisplayChanged(tId: Int, newDisplayId: Int) {
            if (newDisplayId == virtualDisplay.display.displayId) {
                destroyJob?.cancel()
                if (!taskList.contains(tId)) {
                    taskList.add(tId)
                }
            }

            if (taskList.contains(tId) && isFloating && newDisplayId == Display.DEFAULT_DISPLAY) {
                context.startService(Intent(context, FreeformService::class.java).setAction(FreeformService.ACTION_START_INTENT).putExtra(Intent.EXTRA_INTENT, config.intent))
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!isDestroy && taskList.contains(tId) && newDisplayId == Display.DEFAULT_DISPLAY) {
                    if (config.useSuiRefuseToFullScreen)
                        activityTaskManager.moveRootTaskToDisplay(tId, virtualDisplay.display.displayId)
                    else
                        // try relaunch
                        context.startService(Intent(context, FreeformService::class.java).setAction(FreeformService.ACTION_CALL_INTENT))
                }
            }
        }

        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            handleOrientationChange(tId, requestedOrientation)
        }

        //q220903.2 Android 10系统上需要该回调监听
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            handleOrientationChange(tId, requestedOrientation)
        }

        private fun handleOrientationChange(tId: Int, requestedOrientation: Int) {
            val tempRotation = when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
                else -> VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            }
            if (taskList.contains(tId) && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                if (isAnimating) {
                    pendingOrientationChange = true
                    orientationChangeJob?.cancel()
                    return
                }

                orientationChangeJob?.cancel()
                orientationChangeJob = scope.launch(Dispatchers.Main) {
                    // 增加防抖，避免过于频繁的刷新
                    delay(100)
                    onFreeFormRotationChanged()
                }
            }
        }
    }

    companion object {
        private const val TAG = "FreeformView"

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

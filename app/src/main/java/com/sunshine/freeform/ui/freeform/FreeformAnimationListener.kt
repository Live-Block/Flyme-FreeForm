package com.sunshine.freeform.ui.freeform

import android.animation.Animator
import android.animation.Animator.AnimatorListener

class FreeformAnimationListener(
    val onAnimStart: () -> Unit = {},
    val onAnimEnd: () -> Unit = {},
    val onAnimCancel: () -> Unit = {},
    val onAnimaRepeat: () -> Unit = {},
): AnimatorListener  {

    override fun onAnimationStart(animation: Animator) {
        onAnimStart()
    }

    override fun onAnimationEnd(animation: Animator) {
        onAnimEnd()
    }

    override fun onAnimationCancel(animation: Animator) {
        onAnimCancel()
    }

    override fun onAnimationRepeat(animation: Animator) {
        onAnimaRepeat()
    }
}
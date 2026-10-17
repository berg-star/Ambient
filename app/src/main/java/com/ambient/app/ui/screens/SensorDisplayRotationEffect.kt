package com.ambient.app.ui.screens

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.ambient.app.domain.orientation.DisplayRotation
import com.ambient.app.ui.sensors.SensorsViewModel

/** Display changes are independent of sensor axes, including a 180-degree display rotation. */
@Composable
internal fun ObserveSensorDisplayRotation(viewModel: SensorsViewModel) {
    val view = LocalView.current
    DisposableEffect(view, viewModel) {
        val manager = view.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        fun updateRotation() {
            val display = view.display ?: return
            viewModel.setDisplayRotation(when (display.rotation) {
                Surface.ROTATION_90 -> DisplayRotation.R90
                Surface.ROTATION_180 -> DisplayRotation.R180
                Surface.ROTATION_270 -> DisplayRotation.R270
                else -> DisplayRotation.R0
            })
        }
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updateRotation()
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (view.display?.displayId == displayId) updateRotation()
            }
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = updateRotation()
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        manager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        view.addOnAttachStateChangeListener(attachListener)
        updateRotation()
        onDispose {
            manager?.unregisterDisplayListener(displayListener)
            view.removeOnAttachStateChangeListener(attachListener)
        }
    }
}

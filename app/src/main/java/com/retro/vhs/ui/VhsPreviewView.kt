package com.retro.vhs.ui

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.retro.vhs.render.VhsRenderThread

/** Plain SurfaceView; the render thread owns the EGL window surface behind it. */
class VhsPreviewView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var renderThread: VhsRenderThread? = null
        set(value) {
            field = value
            if (value != null && holder.surface?.isValid == true) {
                value.surfaceAvailable(holder.surface, width, height)
            }
        }

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderThread?.surfaceAvailable(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderThread?.surfaceDestroyed()
    }
}

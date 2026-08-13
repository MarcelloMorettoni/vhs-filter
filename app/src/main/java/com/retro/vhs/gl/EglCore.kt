package com.retro.vhs.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log

/**
 * Minimal EGL 1.4 wrapper. One context, many surfaces (the on-screen preview and the
 * MediaCodec encoder input surface both live under the same context so the filtered
 * frame is rendered exactly once and shown/recorded from the same textures).
 */
class EglCore(sharedContext: EGLContext? = null, recordable: Boolean = true) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private set

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "unable to get EGL14 display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "unable to initialize EGL14" }

        val attribList = mutableListOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT
        )
        if (recordable) {
            attribList.add(EGL_RECORDABLE_ANDROID)
            attribList.add(1)
        }
        attribList.add(EGL14.EGL_NONE)

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display, attribList.toIntArray(), 0, configs, 0, configs.size, numConfigs, 0
            ) && numConfigs[0] > 0
        ) { "unable to find a suitable EGLConfig" }
        config = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(
            display, config, sharedContext ?: EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
        )
        checkEglError("eglCreateContext")
        check(context != EGL14.EGL_NO_CONTEXT) { "null EGL context" }
    }

    /** [surface] must be a [android.view.Surface] or [android.graphics.SurfaceTexture]. */
    fun createWindowSurface(surface: Any): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        checkEglError("eglCreateWindowSurface")
        check(eglSurface != null) { "surface was null" }
        return eglSurface
    }

    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        checkEglError("eglCreatePbufferSurface")
        check(eglSurface != null) { "surface was null" }
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /** Timestamp handed to the encoder / compositor, in nanoseconds. */
    fun setPresentationTime(surface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nsecs)
    }

    fun querySurface(surface: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(display, surface, what, value, 0)
        return value[0]
    }

    fun surfaceWidth(surface: EGLSurface) = querySurface(surface, EGL14.EGL_WIDTH)
    fun surfaceHeight(surface: EGLSurface) = querySurface(surface, EGL14.EGL_HEIGHT)

    fun releaseSurface(surface: EGLSurface?) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
        }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            Log.e(TAG, "$msg: EGL error 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        private const val TAG = "EglCore"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

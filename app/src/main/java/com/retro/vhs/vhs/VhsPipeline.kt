package com.retro.vhs.vhs

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.hypot
import com.retro.vhs.gl.Fbo
import com.retro.vhs.gl.FullscreenQuad
import com.retro.vhs.gl.GlProgram

/** How the incoming camera / decoder image maps onto the 4:3 working frame. */
data class InputTransform(
    val rotationDegrees: Int = 0,
    val mirror: Boolean = false,
    val sourceWidth: Int = 4,
    val sourceHeight: Int = 3,
    /** true letterboxes the source inside the 4:3 raster instead of cropping it to fill. */
    val fit: Boolean = false
)

/**
 * Owns the three render stages and the ping-pong buffers they need. Everything the
 * app shows or records goes through exactly one [renderToTexture] per input frame; the
 * preview and the encoder then each run the cheap [drawToSurface] pass on the result.
 */
class VhsPipeline(val width: Int, val height: Int) {

    private val quad = FullscreenQuad()
    private val pickupProgram = GlProgram(VhsShaders.VERTEX, VhsShaders.FRAG_PICKUP)
    private val tapeProgram = GlProgram(VhsShaders.VERTEX, VhsShaders.FRAG_TAPE)
    private val crtProgram = GlProgram(VhsShaders.VERTEX, VhsShaders.FRAG_CRT)

    private val pickupBuffers = Array(2) { Fbo(width, height) }
    private val tapeBuffers = Array(2) { Fbo(width, height) }
    private var parity = 0

    val osd = OsdRenderer()

    @Volatile
    var preset: VhsPreset = VhsPreset.ALL[0]

    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val localMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val dxVec = FloatArray(4)
    private val dyVec = FloatArray(4)
    private val fit = floatArrayOf(1f, 1f)
    private val surfaceMatrixScratch = FloatArray(16)
    private val unitX = floatArrayOf(1f, 0f, 0f, 0f)
    private val unitY = floatArrayOf(0f, 1f, 0f, 0f)

    private var frames = 0L
    private var glitchStartNs = 0L

    /** What the aspect correction worked out this frame, for the debug report. */
    @Volatile
    var lastMeasure: String = "not measured"
        private set

    /** A burst of mistracking, the way a deck behaved for a moment after a tape change. */
    fun glitch() {
        glitchStartNs = System.nanoTime()
    }

    private fun glitchAmount(): Float {
        if (glitchStartNs == 0L) return 0f
        val elapsed = (System.nanoTime() - glitchStartNs) / 1_000_000L
        if (elapsed > GLITCH_MS) return 0f
        return 1f - elapsed.toFloat() / GLITCH_MS
    }

    /** Stage 1 + 2. Leaves the finished tape frame in [tapeBuffers]`[parity]`. */
    fun renderToTexture(
        oesTextureId: Int,
        surfaceMatrix: FloatArray,
        transform: InputTransform,
        timeSec: Float,
        osdState: OsdState
    ) {
        val cur = parity
        val prev = 1 - parity
        val p = preset

        buildTexMatrix(surfaceMatrix, transform)
        osd.update(osdState)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        // ---------- stage 1: lens + pickup ----------
        pickupBuffers[cur].bind()
        pickupProgram.use()
        pickupProgram.setMatrix("uTexMatrix", texMatrix)
        pickupProgram.bindTexture(
            "sCam", 0, oesTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        )
        pickupProgram.bindTexture("sPrev", 1, pickupBuffers[prev].texture)
        pickupProgram.bindTexture("sOsd", 2, osd.texture())
        pickupProgram.set("uDx", dxVec[0], dxVec[1])
        pickupProgram.set("uDy", dyVec[0], dyVec[1])
        pickupProgram.set("uSoftness", p.softness)
        pickupProgram.set("uSmear", p.ccdSmear)
        pickupProgram.set("uLag", p.tubeLag)
        pickupProgram.set("uBloom", p.lensBloom)
        pickupProgram.set("uExposure", p.exposure)
        pickupProgram.set("uGamma", p.gamma)
        pickupProgram.set("uContrast", p.contrast)
        pickupProgram.set("uSaturation", p.saturation)
        pickupProgram.set("uTint", p.tintR, p.tintG, p.tintB)
        pickupProgram.set("uVignette", p.lensVignette)
        pickupProgram.set("uFit", fit[0], fit[1])
        pickupProgram.set("uOsdAmount", if (osdState.style == OsdStyle.NONE) 0f else 1f)
        quad.draw(pickupProgram)

        // ---------- stage 2: composite + tape ----------
        val seed = ((frames * 0.6180339f) % 1f + 1f) % 1f
        val field = (frames % 2L).toFloat()

        tapeBuffers[cur].bind()
        tapeProgram.use()
        tapeProgram.setMatrix("uTexMatrix", identity)
        tapeProgram.bindTexture("sSrc", 0, pickupBuffers[cur].texture)
        tapeProgram.bindTexture("sPrev", 1, tapeBuffers[prev].texture)
        tapeProgram.set("uRes", width.toFloat(), height.toFloat())
        tapeProgram.set("uTime", timeSec)
        tapeProgram.set("uField", field)
        tapeProgram.set("uSeed", seed)
        tapeProgram.set("uLumaBw", p.lumaBw)
        tapeProgram.set("uChromaBw", p.chromaBw)
        tapeProgram.set("uChromaDelay", p.chromaDelay)
        tapeProgram.set("uSharpen", p.sharpen)
        tapeProgram.set("uDotCrawl", p.dotCrawl)
        tapeProgram.set("uRainbow", p.rainbow)
        tapeProgram.set("uNoise", p.noise)
        tapeProgram.set("uNoiseDark", p.noiseDark)
        tapeProgram.set("uChromaNoise", p.chromaNoise)
        val g = glitchAmount()
        tapeProgram.set("uJitter", p.jitter + g * 5f)
        tapeProgram.set("uWarp", p.warp + g * 6f)
        tapeProgram.set("uHeadSwitch", maxOf(p.headSwitch, g))
        tapeProgram.set("uTracking", maxOf(p.tracking, g * 0.9f))
        tapeProgram.set("uDropout", p.dropout)
        tapeProgram.set("uGhost", p.ghost)
        tapeProgram.set("uGhostDist", p.ghostDist)
        tapeProgram.set("uInterlace", p.interlace)
        tapeProgram.set("uPersist", p.persist)
        tapeProgram.set("uSat", p.sat)
        tapeProgram.set("uHue", p.hue)
        tapeProgram.set("uBlack", p.black)
        tapeProgram.set("uWhiteClip", p.whiteClip)
        tapeProgram.set("uSnow", maxOf(p.snow, g * 0.55f))
        quad.draw(tapeProgram)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        frames++
    }

    /** Stage 3, into whatever surface is current. Letterboxes the 4:3 frame. */
    fun drawToSurface(surfaceWidth: Int, surfaceHeight: Int, timeSec: Float) {
        val p = preset
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val aspect = width.toFloat() / height.toFloat()
        var vw = surfaceWidth
        var vh = (vw / aspect).toInt()
        if (vh > surfaceHeight) {
            vh = surfaceHeight
            vw = (vh * aspect).toInt()
        }
        GLES20.glViewport((surfaceWidth - vw) / 2, (surfaceHeight - vh) / 2, vw, vh)

        crtProgram.use()
        crtProgram.setMatrix("uTexMatrix", identity)
        crtProgram.bindTexture("sSrc", 0, tapeBuffers[parity].texture)
        crtProgram.set("uSrcRes", width.toFloat(), height.toFloat())
        crtProgram.set("uTime", timeSec)
        crtProgram.set("uCurve", p.curve)
        crtProgram.set("uScanline", p.scanline)
        crtProgram.set("uMask", p.mask)
        crtProgram.set("uBloom", p.crtBloom)
        crtProgram.set("uVignette", p.crtVignette)
        crtProgram.set("uGlare", p.glare)
        crtProgram.set("uOverscan", p.overscan)
        crtProgram.set("uBright", p.bright)
        crtProgram.set("uGammaOut", p.gammaOut)
        crtProgram.set("uFlicker", p.flicker)
        crtProgram.set("uCorner", p.corner)
        crtProgram.set("uOversample", vh.toFloat() / height.toFloat())
        quad.draw(crtProgram)
    }

    /** Call once per input frame, after every output surface has been drawn. */
    fun advanceFrame() {
        parity = 1 - parity
    }

    /**
     * quad uv -> source texture uv: crop to 4:3 and mirror in output space, rotate into
     * the sensor's orientation, then apply the SurfaceTexture's own transform.
     */
    private fun buildTexMatrix(surfaceMatrix: FloatArray, t: InputTransform) {
        System.arraycopy(surfaceMatrix, 0, surfaceMatrixScratch, 0, 16)
        // Orientation first, with no correction, so the aspect can be measured through
        // the finished chain. Working it out from the rotation alone is not safe: some
        // devices hand back a SurfaceTexture matrix that already swaps the axes, and the
        // swap then gets counted twice and squeezes the picture on one side.
        orient(t, 1f, 1f)

        unitX[0] = 1f
        unitX[1] = 0f
        unitY[0] = 0f
        unitY[1] = 1f
        Matrix.multiplyMV(dxVec, 0, texMatrix, 0, unitX, 0)
        Matrix.multiplyMV(dyVec, 0, texMatrix, 0, unitY, 0)

        // How many source pixels one output pixel spans, per axis. Equal means the
        // picture is not distorted; the ratio is exactly the correction needed.
        val sw = t.sourceWidth.toFloat()
        val sh = t.sourceHeight.toFloat()
        val perX = hypot(dxVec[0] * sw, dxVec[1] * sh) / width
        val perY = hypot(dyVec[0] * sw, dyVec[1] * sh) / height

        var cropX = 1f
        var cropY = 1f
        if (perX > 1e-6f && perY > 1e-6f) {
            if (t.fit) {
                // show all of it: stretch the other axis out past the source
                if (perX > perY) cropY = perX / perY else cropX = perY / perX
            } else {
                // fill the raster: sample less of whichever axis is covering more
                if (perX > perY) cropX = perY / perX else cropY = perX / perY
            }
        }
        fit[0] = cropX
        fit[1] = cropY
        val measured = "rot ${t.rotationDegrees} src ${t.sourceWidth}x${t.sourceHeight} " +
            "perX %.3f perY %.3f crop %.4f,%.4f".format(perX, perY, cropX, cropY)
        if (measured != lastMeasure) {
            lastMeasure = measured
            android.util.Log.d("VhsAspect", measured)
        }

        orient(t, if (t.mirror) -cropX else cropX, cropY)

        // One output pixel, expressed as a direction in source texture space, so the
        // pickup stage can smear "downwards" even when the sensor is sideways.
        unitX[0] = 1f / width
        unitX[1] = 0f
        unitY[0] = 0f
        unitY[1] = 1f / height
        Matrix.multiplyMV(dxVec, 0, texMatrix, 0, unitX, 0)
        Matrix.multiplyMV(dyVec, 0, texMatrix, 0, unitY, 0)
    }

    /** quad uv -> source uv: scale about the centre, rotate, then the device's own matrix. */
    private fun orient(t: InputTransform, scaleX: Float, scaleY: Float) {
        Matrix.setIdentityM(localMatrix, 0)
        Matrix.translateM(localMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(localMatrix, 0, -t.rotationDegrees.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(localMatrix, 0, scaleX, scaleY, 1f)
        Matrix.translateM(localMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, surfaceMatrixScratch, 0, localMatrix, 0)
    }

    fun release() {
        pickupBuffers.forEach { it.release() }
        tapeBuffers.forEach { it.release() }
        pickupProgram.release()
        tapeProgram.release()
        crtProgram.release()
        osd.release()
    }

    private companion object {
        const val GLITCH_MS = 550L
    }
}

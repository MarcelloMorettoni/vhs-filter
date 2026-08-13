package com.retro.vhs.record

import com.retro.vhs.vhs.AudioProfile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The audio half of the illusion. A linear VHS audio track ran past a fixed head at
 * tape speed, so it lost everything above ~10 kHz (~5 kHz at EP), hissed, saturated
 * early, and wobbled in pitch as the transport hunted - wow at well under a hertz,
 * flutter at a few hertz.
 *
 * Operates on interleaved mono 16-bit PCM in place.
 */
class VhsAudioProcessor(private val profile: AudioProfile, private val sampleRate: Int) {

    private val delayLine = FloatArray(sampleRate / 4)      // 250 ms is plenty
    private var writeIndex = 0

    private val baseDelay = sampleRate * 0.02f              // 20 ms nominal read offset
    private val wowAmplitude = amplitudeFor(profile.wowRateHz, profile.wowDepth)
    private val flutterAmplitude = amplitudeFor(profile.flutterRateHz, profile.flutterDepth)

    private var wowPhase = 0f
    private var flutterPhase = 0f

    private var hpState = 0f
    private var lp1 = 0f
    private var lp2 = 0f

    private val hpCoeff = coeff(profile.lowCutHz)
    private val lpCoeff = coeff(profile.highCutHz)

    private val random = Random(0x5648_5321)
    private var dropoutSamples = 0
    private var dropoutGain = 1f

    private val driveNorm = 1f / tanh(profile.drive)

    /**
     * Sinusoidal delay modulation of amplitude A at rate f produces a peak pitch
     * deviation of 2*pi*f*A, so invert that to hit the profile's wow/flutter depth.
     */
    private fun amplitudeFor(rateHz: Float, depth: Float): Float =
        if (rateHz <= 0f || depth <= 0f) 0f
        else (depth * sampleRate / (2f * PI.toFloat() * rateHz))

    private fun coeff(cutoffHz: Float): Float =
        1f - exp(-2f * PI.toFloat() * min(cutoffHz, sampleRate * 0.45f) / sampleRate)

    fun process(pcm: ShortArray, count: Int) {
        val wowStep = 2f * PI.toFloat() * profile.wowRateHz / sampleRate
        val flutterStep = 2f * PI.toFloat() * profile.flutterRateHz / sampleRate

        for (i in 0 until count) {
            var x = pcm[i] / 32768f

            // hiss goes in ahead of the head's roll-off so it is band limited too
            if (profile.hiss > 0f) {
                x += (random.nextFloat() - 0.5f) * 2f * profile.hiss
            }

            // tape saturation
            x = tanh(x * profile.drive) * driveNorm

            // transport speed error
            delayLine[writeIndex] = x
            val modulation = wowAmplitude * sin(wowPhase) + flutterAmplitude * sin(flutterPhase)
            wowPhase += wowStep
            flutterPhase += flutterStep
            if (wowPhase > 2f * PI.toFloat()) wowPhase -= 2f * PI.toFloat()
            if (flutterPhase > 2f * PI.toFloat()) flutterPhase -= 2f * PI.toFloat()

            var readPos = writeIndex - (baseDelay + modulation)
            while (readPos < 0f) readPos += delayLine.size
            while (readPos >= delayLine.size) readPos -= delayLine.size
            val i0 = readPos.toInt()
            val i1 = if (i0 + 1 >= delayLine.size) 0 else i0 + 1
            val frac = readPos - i0
            var y = delayLine[i0] * (1f - frac) + delayLine[i1] * frac

            writeIndex++
            if (writeIndex >= delayLine.size) writeIndex = 0

            // head response: one pole high pass, two pole low pass
            hpState += hpCoeff * (y - hpState)
            y -= hpState
            lp1 += lpCoeff * (y - lp1)
            lp2 += lpCoeff * (lp1 - lp2)
            y = lp2

            // brief level dropouts on a worn tape
            if (dropoutSamples > 0) {
                dropoutSamples--
                y *= dropoutGain
            } else if (profile.dropouts > 0f && random.nextFloat() < profile.dropouts / sampleRate * 40f) {
                dropoutSamples = (sampleRate * (0.01f + random.nextFloat() * 0.05f)).toInt()
                dropoutGain = random.nextFloat() * 0.4f
            }

            pcm[i] = (max(-1f, min(1f, y * 1.08f)) * 32767f).toInt().toShort()
        }
    }

    /** Hi-Fi AFM tracks were stereo; the linear edge track never was. */
    fun toOutput(pcm: ShortArray, count: Int, out: ShortArray): Int {
        if (profile.mono) {
            System.arraycopy(pcm, 0, out, 0, count)
            return count
        }
        // Gentle Haas-style spread so the AFM presets are not just doubled mono.
        var j = 0
        for (i in 0 until count) {
            val cur = pcm[i].toInt()
            val prev = pcm[max(0, i - 12)].toInt()
            out[j++] = ((cur * 0.86f + prev * 0.14f)).toInt().toShort()
            out[j++] = ((cur * 0.86f - prev * 0.10f)).toInt().toShort()
        }
        return j
    }
}

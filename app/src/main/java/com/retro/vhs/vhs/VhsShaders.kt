package com.retro.vhs.vhs

/**
 * The whole look is built as a three stage signal chain, mirroring what actually
 * happened between a 1980s lens and a 1980s television set:
 *
 *   1. [FRAG_PICKUP]    lens + image sensor (CCD smear, tube lag, halation, AGC, OSD burn-in)
 *   2. [FRAG_TAPE]      composite encode/decode + helical scan tape transport
 *                       (Y/C bandwidth loss, chroma delay, dot crawl, cross-colour,
 *                        tape noise, time-base error, head switching, dropouts, interlace)
 *   3. [FRAG_CRT]       display (barrel geometry, scanlines, shadow mask, halation, overscan)
 *
 * Everything runs on GLES 2.0 so it works on anything from 2012 onwards.
 */
object VhsShaders {

    const val VERTEX = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTex;
varying vec2 vQuad;
void main() {
    gl_Position = aPosition;
    vTex = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
    vQuad = aTexCoord;
}
"""

    /** Colour space + hashes shared by the fragment stages. */
    private const val COMMON = """
const float PI = 3.14159265;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec3 rgb2yiq(vec3 c) {
    return vec3(
        dot(c, vec3(0.299, 0.587, 0.114)),
        dot(c, vec3(0.5959, -0.2746, -0.3213)),
        dot(c, vec3(0.2115, -0.5227, 0.3112)));
}

vec3 yiq2rgb(vec3 c) {
    return vec3(
        c.x + 0.956 * c.y + 0.619 * c.z,
        c.x - 0.272 * c.y - 0.647 * c.z,
        c.x - 1.106 * c.y + 1.703 * c.z);
}

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(float x) {
    float i = floor(x);
    float f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(hash11(i), hash11(i + 1.0), f);
}
"""

    /**
     * Stage 1 - the camera head.
     *
     * Samples the external (camera / decoder) texture, applies the optics and the pickup
     * device character, then burns in the on-screen display exactly like a camcorder's
     * character generator did: before the signal ever reached the tape.
     */
    val FRAG_PICKUP = """
#extension GL_OES_EGL_image_external : require
precision highp float;
$COMMON
uniform samplerExternalOES sCam;
uniform sampler2D sPrev;
uniform sampler2D sOsd;
uniform vec2 uDx;          // one output pixel, expressed in source texture space
uniform vec2 uDy;
uniform float uSoftness;   // cheap 80s zoom lens
uniform float uSmear;      // CCD vertical smear on speculars
uniform float uLag;        // vidicon/newvicon comet trails
uniform float uBloom;      // halation inside the lens stack
uniform float uExposure;
uniform float uGamma;
uniform float uContrast;
uniform float uSaturation;
uniform vec3 uTint;
uniform float uVignette;
uniform float uOsdAmount;
uniform vec2 uFit;         // >1 on an axis means the source does not fill it
varying vec2 vTex;
varying vec2 vQuad;

void main() {
    // Anything the source does not reach is blanking level, not a smeared edge pixel:
    // 16:9 material on a 4:3 raster gets real black bars, exactly like a letterboxed tape.
    vec2 fitD = abs(vQuad - 0.5) * uFit;
    if (fitD.x > 0.5 || fitD.y > 0.5) {
        vec4 bar = texture2D(sOsd, vec2(vQuad.x, 1.0 - vQuad.y));
        gl_FragColor = vec4(bar.rgb * bar.a * uOsdAmount, 1.0);
        return;
    }

    vec3 c = texture2D(sCam, vTex).rgb;

    if (uSoftness > 0.001) {
        vec3 s = texture2D(sCam, vTex + uDx * 1.5).rgb
               + texture2D(sCam, vTex - uDx * 1.5).rgb
               + texture2D(sCam, vTex + uDy * 1.5).rgb
               + texture2D(sCam, vTex - uDy * 1.5).rgb;
        c = mix(c, s * 0.25, uSoftness);
    }

    c *= uExposure;

    // Vertical smear: an interline transfer CCD leaking charge down the column when a
    // specular highlight hits it. The 80s camcorder signature nobody could avoid.
    if (uSmear > 0.001) {
        float sm = 0.0;
        for (int i = 1; i <= 10; i++) {
            float d = float(i) * 3.0;
            float a = luma(texture2D(sCam, vTex + uDy * d).rgb);
            float b = luma(texture2D(sCam, vTex - uDy * d).rgb);
            float fall = 1.0 - float(i) / 11.0;
            sm += (max(a - 0.80, 0.0) + max(b - 0.80, 0.0)) * fall;
        }
        c += uSmear * sm * vec3(0.92, 0.96, 1.0);
    }

    // Halation around highlights inside the lens/prism block.
    if (uBloom > 0.001) {
        vec3 bl = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.7853981;
            bl += texture2D(sCam, vTex + (uDx * cos(a) + uDy * sin(a)) * 3.5).rgb;
        }
        c += uBloom * max(bl * 0.125 - 0.55, 0.0) * 1.6;
    }

    c = pow(max(c, 0.0), vec3(uGamma));
    c = (c - 0.5) * uContrast + 0.5;
    float y = luma(c);
    c = mix(vec3(y), c, uSaturation);
    c *= uTint;

    vec2 d = vQuad - 0.5;
    c *= 1.0 - uVignette * dot(d, d) * 1.6;

    // Pickup tube lag: bright areas keep glowing for a few frames -> comet trails.
    if (uLag > 0.001) {
        vec3 prev = texture2D(sPrev, vQuad).rgb;
        c = max(c, prev * uLag);
    }

    // Character generator overlay, burned in ahead of the tape stage.
    if (uOsdAmount > 0.001) {
        vec4 osd = texture2D(sOsd, vec2(vQuad.x, 1.0 - vQuad.y));
        c = mix(c, osd.rgb, osd.a * uOsdAmount);
    }

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    /**
     * Stage 2 - the tape.
     *
     * VHS records luma as an FM carrier with roughly 3 MHz of bandwidth and colour-under
     * chroma with about 400 kHz, which is why colour smears sideways for a dozen pixels
     * while edges stay comparatively crisp - and why the recorder's pre-emphasis leaves
     * that unmistakable bright ringing on every hard edge.
     */
    val FRAG_TAPE = """
precision highp float;
$COMMON
uniform sampler2D sSrc;
uniform sampler2D sPrev;
uniform vec2 uRes;
uniform float uTime;
uniform float uField;
uniform float uSeed;

uniform float uLumaBw;
uniform float uChromaBw;
uniform float uChromaDelay;
uniform float uSharpen;
uniform float uDotCrawl;
uniform float uRainbow;
uniform float uNoise;
uniform float uNoiseDark;
uniform float uChromaNoise;
uniform float uJitter;
uniform float uWarp;
uniform float uHeadSwitch;
uniform float uTracking;
uniform float uDropout;
uniform float uGhost;
uniform float uGhostDist;
uniform float uInterlace;
uniform float uPersist;
uniform float uSat;
uniform float uHue;
uniform float uBlack;
uniform float uWhiteClip;
uniform float uSnow;

varying vec2 vQuad;

void main() {
    vec2 uv = vQuad;
    float px = 1.0 / uRes.x;
    float ny = 1.0 - uv.y;                 // 0 at the top of the frame, like a scan
    float line = floor(ny * uRes.y);
    float t = uTime;
    float fld = floor(t * 59.94);

    // ---- time base error: no consumer deck had a TBC ----
    float jit = (vnoise(line * 1.7 + fld * 3.1) - 0.5) * 2.0;
    jit += (vnoise(line * 0.11 + t * 2.7) - 0.5) * 3.0;
    float warp = sin(ny * 9.0 + t * 1.7) * 0.6 + sin(ny * 23.0 - t * 2.3) * 0.25;
    float xoff = (jit * uJitter + warp * uWarp) * px;

    // ---- head switching noise: the torn band at the bottom of every VHS field ----
    float hs = smoothstep(0.970, 1.0, ny);
    xoff += hs * (0.010 + 0.030 * (vnoise(t * 40.0 + line) - 0.5)) * uHeadSwitch;

    // ---- mistracking band drifting up the picture ----
    float band = 0.0;
    if (uTracking > 0.001) {
        float pos = fract(t * 0.07);
        float d = abs(fract(ny - pos + 0.5) - 0.5);
        band = smoothstep(0.055, 0.0, d) * uTracking;
        xoff += band * (vnoise(line * 3.0 + t * 90.0) - 0.5) * 0.05;
    }

    uv.x = clamp(uv.x + xoff, 0.0, 1.0);

    // ---- colour-under chroma: wide box filter + the delay line's sideways shift ----
    float chStep = uChromaBw * px / 6.0;
    vec2 chroma = vec2(0.0);
    float ywide = 0.0;
    float wsum = 0.0;
    vec2 chL = vec2(0.0);
    vec2 chR = vec2(0.0);
    for (int i = -6; i <= 6; i++) {
        float fi = float(i);
        float sx = clamp(uv.x + fi * chStep - uChromaDelay * px, 0.0, 1.0);
        vec3 q = rgb2yiq(texture2D(sSrc, vec2(sx, uv.y)).rgb);
        float w = 1.0 - abs(fi) / 8.0;
        chroma += q.yz * w;
        ywide += q.x * w;
        wsum += w;
        if (i == -6) chL = q.yz;
        if (i == 6) chR = q.yz;
    }
    chroma /= wsum;
    ywide /= wsum;

    // ---- luma channel ----
    float lStep = uLumaBw * px / 3.0;
    float ynarrow = 0.0;
    float lw = 0.0;
    for (int i = -3; i <= 3; i++) {
        float fi = float(i);
        float w = exp(-fi * fi * 0.28);
        float sx = clamp(uv.x + fi * lStep, 0.0, 1.0);
        ynarrow += luma(texture2D(sSrc, vec2(sx, uv.y)).rgb) * w;
        lw += w;
    }
    ynarrow /= lw;

    // Record pre-emphasis that the player never quite undoes: symmetric ringing plus a
    // trailing overshoot, the bright fringe on the right hand side of every dark edge.
    float y = ynarrow + uSharpen * (ynarrow - ywide);
    float yl = luma(texture2D(sSrc, vec2(clamp(uv.x - 2.5 * px, 0.0, 1.0), uv.y)).rgb);
    y += uSharpen * 0.5 * (ynarrow - yl);

    // ---- composite crosstalk (skipped by the S-video presets) ----
    float phase = uv.x * uRes.x * 1.847 + line * PI + uField * PI;
    if (uDotCrawl > 0.001) {
        float edge = length(chR - chL);
        y += uDotCrawl * edge * 0.6 * cos(phase);
    }
    if (uRainbow > 0.001) {
        float hf = ynarrow - ywide;
        chroma += uRainbow * hf * vec2(cos(phase), sin(phase));
    }

    // ---- RF ghost / multipath, the giveaway of a dub made over an RF modulator ----
    if (uGhost > 0.001) {
        float gy = luma(texture2D(sSrc, vec2(clamp(uv.x - uGhostDist, 0.0, 1.0), uv.y)).rgb);
        y = (y + uGhost * gy * 0.45) / (1.0 + uGhost * 0.45);
    }

    // ---- tape noise: luma grain rises in the shadows, chroma noise is coarse ----
    float grain = hash21(vec2(uv.x * uRes.x, line) + vec2(uSeed * 37.0, uSeed * 17.0)) - 0.5;
    y += grain * uNoise * (1.0 + uNoiseDark * (1.0 - y));
    if (uChromaNoise > 0.001) {
        float cb = floor(uv.x * uRes.x / 6.0);
        chroma += vec2(
            hash21(vec2(cb, line) + vec2(uSeed * 3.0, uSeed * 7.0)) - 0.5,
            hash21(vec2(cb, line) + vec2(uSeed * 11.0, uSeed * 5.0)) - 0.5) * uChromaNoise;
    }

    // ---- dropouts: shed oxide, so the head reads nothing for part of a line ----
    if (uDropout > 0.001) {
        float dl = hash11(line * 3.7 + fld * 1.31);
        if (dl > 1.0 - uDropout) {
            float start = hash11(line * 9.1 + fld * 7.7);
            float len = 0.015 + 0.10 * hash11(line * 2.3 + fld * 3.3);
            if (uv.x > start && uv.x < start + len) {
                float dark = step(0.75, hash11(line * 5.9 + fld * 2.1));
                y = mix(y, mix(0.95, 0.03, dark), 0.9);
                chroma *= 0.08;
            }
        }
    }

    // ---- mistracking destroys the FM carrier entirely ----
    if (band > 0.001) {
        float sn = hash21(vec2(uv.x * uRes.x * 0.5, line) + vec2(uSeed * 13.0, uSeed * 29.0));
        y = mix(y, sn, band * 0.85);
        chroma *= 1.0 - band * 0.9;
    }

    // ---- the head switch band itself ----
    if (hs > 0.001 && uHeadSwitch > 0.001) {
        float sn = hash21(vec2(uv.x * uRes.x, line) + vec2(uSeed * 5.0, uSeed * 3.0));
        float k = hs * uHeadSwitch;
        y = mix(y, sn * 0.55 + 0.15, k * 0.9);
        chroma *= 1.0 - k;
    }

    // ---- RF snow ----
    if (uSnow > 0.001) {
        float s = hash21(vec2(floor(uv.x * uRes.x * 0.5), line) + vec2(uSeed * 23.0, uSeed * 41.0));
        y = mix(y, 1.0, step(1.0 - uSnow * 0.09, s) * 0.75);
    }

    // ---- levels ----
    float ca = cos(uHue);
    float sa = sin(uHue);
    chroma = vec2(chroma.x * ca - chroma.y * sa, chroma.x * sa + chroma.y * ca) * uSat;
    y = y * (1.0 - uBlack) + uBlack * 0.07;
    y = min(y, uWhiteClip);

    vec3 rgb = clamp(yiq2rgb(vec3(y, chroma)), 0.0, 1.0);

    // ---- 60i: each field only refreshes every other line, so motion combs ----
    vec3 prev = texture2D(sPrev, vQuad).rgb;
    float par = mod(line, 2.0);
    float useCur = 1.0 - uInterlace * step(0.5, abs(par - uField));
    rgb = mix(prev, rgb, useCur);
    if (uPersist > 0.001) {
        rgb = max(rgb, prev * uPersist);
    }

    gl_FragColor = vec4(rgb, 1.0);
}
"""

    /** Stage 3 - the set it was played back on. */
    val FRAG_CRT = """
precision highp float;
$COMMON
uniform sampler2D sSrc;
uniform vec2 uSrcRes;
uniform float uTime;
uniform float uCurve;
uniform float uScanline;
uniform float uMask;
uniform float uBloom;
uniform float uVignette;
uniform float uGlare;
uniform float uOverscan;
uniform float uBright;
uniform float uGammaOut;
uniform float uFlicker;
uniform float uCorner;
uniform float uOversample;   // output height / 480
varying vec2 vQuad;

vec2 curve(vec2 uv, float k) {
    uv = uv * 2.0 - 1.0;
    vec2 o = uv.yx * uv.yx;
    uv += uv * o * k;
    return uv * 0.5 + 0.5;
}

void main() {
    // A TV cropped the outer few percent of the raster - which is exactly where the
    // head switching mess lives, so overscan is what hid it.
    vec2 uv = (vQuad - 0.5) * (1.0 - uOverscan) + 0.5;
    uv = curve(uv, uCurve);

    vec3 col = vec3(0.0);
    if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
        col = texture2D(sSrc, uv).rgb;

        if (uBloom > 0.001) {
            vec3 b = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.7853981;
                b += texture2D(sSrc, uv + vec2(cos(a), sin(a)) * 0.0065).rgb;
            }
            col += max(b * 0.125 - 0.45, 0.0) * uBloom * 1.5;
        }

        // The line structure and the phosphor mask only exist if the output has the
        // resolution to draw them. Rendering 480 scanlines into a 480 pixel tall frame
        // does not look like a CRT, it just aliases into moire rings.
        float fine = clamp((uOversample - 1.15) / 0.85, 0.0, 1.0);

        if (uScanline * fine > 0.001) {
            float sl = abs(sin(uv.y * uSrcRes.y * PI));
            col *= mix(1.0, 0.30 + 0.70 * pow(sl, 0.75), uScanline * fine);
        }

        if (uMask * fine > 0.001) {
            float m = mod(gl_FragCoord.x, 3.0);
            vec3 mk = vec3(0.86);
            if (m < 1.0) mk.r = 1.18;
            else if (m < 2.0) mk.g = 1.18;
            else mk.b = 1.18;
            col *= mix(vec3(1.0), mk, uMask * fine);
        }
    }

    // Softly rounded bezel corners.
    if (uCorner > 0.0001) {
        vec2 q = abs(vQuad - 0.5) - (0.5 - uCorner);
        float d = length(max(q, 0.0)) - uCorner;
        col *= 1.0 - smoothstep(-0.004, 0.004, d);
    }

    vec2 dv = vQuad - 0.5;
    col *= 1.0 - uVignette * dot(dv, dv) * 2.2;
    if (uGlare > 0.001) {
        float g = max(0.0, 1.0 - length((vQuad - vec2(0.30, 0.76)) * vec2(1.5, 1.0)));
        col += uGlare * pow(g, 3.0) * 0.14;
    }
    if (uFlicker > 0.001) {
        col *= 1.0 + uFlicker * (hash11(floor(uTime * 59.94)) - 0.5) * 0.12;
    }

    col = pow(max(col, 0.0), vec3(uGammaOut)) * uBright;
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
}

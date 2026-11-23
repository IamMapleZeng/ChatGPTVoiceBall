package io.github.iammaplezeng.chatgptvoiceball.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gemini / ChatGPT Voice Mode style luminous sky-cloud glowing orb.
 * Supports both circular floating orb (collapsed) and wide glowing pill (expanded dock center).
 */
class ChatGptOrbView(
    context: Context,
    private val isPillMode: Boolean = false
) : View(context) {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val clipPath = Path()
    private val shapeRect = RectF()

    private var phase = 0f
    private var isPressedState = false
    private var isIdleMode = false

    private val orbitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            phase = anim.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setOrbPressed(pressed: Boolean) {
        if (isPressedState == pressed) return
        isPressedState = pressed
        animate()
            .scaleX(if (pressed) 0.92f else 1.0f)
            .scaleY(if (pressed) 0.92f else 1.0f)
            .setDuration(110L)
            .start()
    }

    fun triggerLaunchRipple() {
        setIdleMode(false)
        animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(110L)
            .withEndAction {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(140L).start()
            }
            .start()
    }

    fun setIdleMode(idle: Boolean) {
        isIdleMode = idle
        if (idle) {
            if (orbitAnimator.isRunning) orbitAnimator.pause()
            invalidate()
        } else {
            if (!orbitAnimator.isStarted) orbitAnimator.start()
            else if (orbitAnimator.isPaused) orbitAnimator.resume()
        }
    }

    fun pauseAnimations() {
        if (orbitAnimator.isRunning) orbitAnimator.pause()
    }

    fun resumeAnimations() {
        if (!isIdleMode) {
            if (!orbitAnimator.isStarted) orbitAnimator.start()
            else if (orbitAnimator.isPaused) orbitAnimator.resume()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val pad = min(w, h) * 0.08f
        shapeRect.set(pad, pad, w - pad, h - pad)

        // Bright white top transitioning smoothly into luminous sky-blue bottom (like Gemini Live orb)
        basePaint.shader = LinearGradient(
            0f, shapeRect.top,
            0f, shapeRect.bottom,
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#F5FAFF"),
                Color.parseColor("#CBE7FF"),
                Color.parseColor("#69B7FF"),
                Color.parseColor("#2A88F5")
            ),
            floatArrayOf(0.0f, 0.36f, 0.62f, 0.84f, 1.0f),
            Shader.TileMode.CLAMP
        )

        highlightPaint.shader = RadialGradient(
            w * 0.42f,
            h * 0.24f,
            min(w, h) * 0.55f,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        shadowPaint.color = Color.argb(40, 20, 45, 90)
        shadowPaint.setShadowLayer(min(w, h) * 0.10f, 0f, min(w, h) * 0.04f, Color.argb(95, 15, 35, 70))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val angle = phase * 2f * PI.toFloat()
        val cornerRadius = if (isPillMode) shapeRect.height() / 2f else min(shapeRect.width(), shapeRect.height()) / 2f

        // 1. Soft drop shadow
        canvas.drawRoundRect(shapeRect, cornerRadius, cornerRadius, shadowPaint)

        // 2. Clip inside orb / pill
        clipPath.reset()
        clipPath.addRoundRect(shapeRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // 3. Base white-to-sky-blue gradient
        canvas.drawRect(shapeRect, basePaint)

        // 4. Gently drifting sky-blue cloud aura wave at the bottom
        val waveCx = width * (0.5f + 0.14f * cos(angle))
        val waveCy = height * (0.86f + 0.05f * sin(angle * 2f))
        val waveRadius = maxOf(width, height) * 0.62f
        wavePaint.shader = RadialGradient(
            waveCx, waveCy, waveRadius,
            intArrayOf(
                Color.argb(180, 33, 150, 243),
                Color.argb(110, 100, 181, 246),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(waveCx, waveCy, waveRadius, wavePaint)

        // 5. Soft white top highlight
        canvas.drawRect(shapeRect, highlightPaint)
        canvas.restore()

        // 6. Delicate luminous rim
        rimPaint.color = Color.argb(190, 255, 255, 255)
        canvas.drawRoundRect(shapeRect, cornerRadius, cornerRadius, rimPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeAnimations()
    }

    override fun onDetachedFromWindow() {
        pauseAnimations()
        super.onDetachedFromWindow()
    }
}

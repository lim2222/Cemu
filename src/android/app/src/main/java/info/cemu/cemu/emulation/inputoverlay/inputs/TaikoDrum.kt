package info.cemu.cemu.emulation.inputoverlay.inputs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.view.MotionEvent
import info.cemu.cemu.emulation.inputoverlay.OverlayTaiko
import info.cemu.cemu.emulation.inputoverlay.Colors
import kotlin.math.sqrt

class TaikoDrum(
    private val onButtonStateChange: (button: OverlayTaiko, state: Boolean) -> Unit,
    private val alpha: Int,
    rect: Rect,
    private val drumBitmap: Bitmap,
    private val excludeRects: List<Rect> = emptyList(),
) : Input(rect) {
    private var backgroundFillColor: Int = 0
    private var backgroundStrokeColor: Int = 0

    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var halfWidth: Float = 0f
    private var halfHeight: Float = 0f
    private var donRatioX2: Float = 0f
    private var donRatioY2: Float = 0f

    private val activePointers = mutableMapOf<Int, Int>()

    override fun configure() {
        backgroundFillColor = Colors.backgroundFill(alpha)
        backgroundStrokeColor = Colors.backgroundStroke(alpha)
        configureColors(alpha)

        centerX = rect.exactCenterX()
        centerY = rect.exactCenterY()
        halfWidth = rect.width() * 0.5f
        halfHeight = rect.height() * 0.5f
		donRatioX2 = 0.28f * 0.28f
        donRatioY2 = 0.35f * 0.35f  // Don ( in between 0-1),adjustable
    }

    override fun moveInput(x: Int, y: Int, maxWidth: Int, maxHeight: Int) {
        val width = rect.width()
        val height = rect.height()
        val left = x - width / 2
        val top = y - height / 2
        rect = Rect(left, top, left + width, top + height)
        configure()
    }

    override fun resize(
        diffX: Int,
        diffY: Int,
        maxWidth: Int,
        maxHeight: Int,
        minWidthHeight: Int
    ) {
        val newRight = rect.right + diffX
        val newBottom = rect.bottom + diffY
        if (newRight - rect.left < minWidthHeight || newBottom - rect.top < minWidthHeight) {
            return
        }
        rect = Rect(rect.left, rect.top, newRight, newBottom)
        configure()
    }

    init {
        configure()
    }
	
	private fun isExcluded(x: Float, y: Float): Boolean {
		return excludeRects.any { it.contains(x.toInt(), y.toInt()) }
	}
	
    private fun getZoneByPosition(x: Float, y: Float): Int {
		if (isExcluded(x, y)) return NONE
		
		val dx = (x - centerX) / halfWidth
		val dy = (y - centerY) / halfHeight
		val norm2 = dx * dx + dy * dy

		if (norm2 > 1.0f) return NONE

        val donCheck = (dx * dx) / donRatioX2 + (dy * dy) / donRatioY2

        return if (donCheck < 1.0f) {
			if (dx < 0f) DON_LEFT else DON_RIGHT
		} else {
			if (dx < 0f) KA_LEFT else KA_RIGHT
		}
	}

    private fun updateZone(zoneBit: Int, pressed: Boolean) {
        when (zoneBit) {
            DON_LEFT -> onButtonStateChange(OverlayTaiko.DON_LEFT, pressed)
            DON_RIGHT -> onButtonStateChange(OverlayTaiko.DON_RIGHT, pressed)
            KA_LEFT -> onButtonStateChange(OverlayTaiko.KA_LEFT, pressed)
            KA_RIGHT -> onButtonStateChange(OverlayTaiko.KA_RIGHT, pressed)
        }
    }

    override fun onTouch(event: MotionEvent): Boolean {
        var handled = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (isInside(x, y)) {
                    val zone = getZoneByPosition(x, y)
                    if (zone != NONE) {
                        activePointers[pointerId] = zone
                        updateZone(zone, true)
                        handled = true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val previousZone = activePointers[pointerId] ?: continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val newZone = getZoneByPosition(x, y)
                    if (newZone != previousZone) {
                        updateZone(previousZone, false)
                        if (newZone != NONE) {
                            activePointers[pointerId] = newZone
                            updateZone(newZone, true)
                        } else {
                            activePointers.remove(pointerId)
                        }
                    }
                    handled = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointerId = event.getPointerId(event.actionIndex)
                val zone = activePointers.remove(pointerId)
                if (zone != null) {
                    updateZone(zone, false)
                    handled = true
                }
            }
        }
        return handled
    }

    override fun resetInput() {
        for ((_, zone) in activePointers) {
            updateZone(zone, false)
        }
        activePointers.clear()
    }

    override fun isInside(x: Float, y: Float): Boolean {
        val dx = (x - centerX) / halfWidth
        val dy = (y - centerY) / halfHeight
        return dx * dx + dy * dy <= 1.0f
    }

    override fun drawInput(canvas: Canvas) {
        paint.alpha = alpha

        val isPressed = activePointers.isNotEmpty()
        val isDonPressed = activePointers.values.any { it == DON_LEFT || it == DON_RIGHT }
        if (isPressed) {
            // Drum press effect
            val pressOffsetY = rect.height() * 0.01f
            val pressedRect = Rect(
                rect.left,
                (rect.top + pressOffsetY).toInt(),
                rect.right,
                (rect.bottom + pressOffsetY).toInt()
            )
            paint.alpha = minOf((alpha * 1.15f).toInt(), 255)
            canvas.drawBitmap(drumBitmap, null, pressedRect, paint)
        } else {
            canvas.drawBitmap(drumBitmap, null, rect, paint)
        }
        if (isDonPressed) {
			val offsetY = -halfHeight * 0.00f
			val glowCenterX = centerX
			val glowCenterY = centerY + offsetY

			val glowWidth = halfWidth * 0.46f * 1.1f
			val glowHeight = halfHeight * 0.60f * 1.1f
			val glowLeft = glowCenterX - glowWidth * 0.5f
			val glowTop = glowCenterY - glowHeight * 0.5f

			val glowPaint = Paint().apply {
				isAntiAlias = true
				shader = RadialGradient(
					glowCenterX, glowCenterY,
					glowWidth * 0.5f,
					intArrayOf(
						0xFFFBAD96.toInt(),
						0x00FBAD96,
					),
					floatArrayOf(0f, 1f),
					Shader.TileMode.CLAMP
				)
				alpha = minOf((this@TaikoDrum.alpha * 1.45f).toInt(), 255)
			}

			canvas.drawOval(glowLeft, glowTop, glowLeft + glowWidth, glowTop + glowHeight, glowPaint)
        }
    }

    companion object {
        private const val NONE = 0
        private const val DON_LEFT = 1 shl 0
        private const val DON_RIGHT = 1 shl 1
        private const val KA_LEFT = 1 shl 2
        private const val KA_RIGHT = 1 shl 3
    }
}
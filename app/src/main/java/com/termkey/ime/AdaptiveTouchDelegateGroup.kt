package com.termkey.ime

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import kotlin.math.max

data class AdaptiveTouchTarget(
    val view: View,
    val actualBounds: Rect,
    val delegateBounds: Rect,
    val slopBounds: Rect,
)

/**
 * Routes touches inside expanded hit regions to the correct key view while
 * preserving gesture deltas for the key's existing touch listener.
 */
class AdaptiveTouchDelegateGroup(
    hostView: View,
) : TouchDelegate(Rect(), hostView) {
    private data class ActiveTarget(
        val target: AdaptiveTouchTarget,
        val offsetX: Float,
        val offsetY: Float,
    )

    private var targets: List<AdaptiveTouchTarget> = emptyList()
    private var activeTarget: ActiveTarget? = null
    private val cancelOffsetPx = 10_000f

    fun updateTargets(updatedTargets: List<AdaptiveTouchTarget>) {
        targets = updatedTargets
        activeTarget = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = targets.firstOrNull { it.delegateBounds.contains(x, y) } ?: return false
                activeTarget = ActiveTarget(
                    target = target,
                    offsetX = computeOffset(x.toFloat(), target.actualBounds.left.toFloat(), target.actualBounds.right.toFloat()),
                    offsetY = computeOffset(y.toFloat(), target.actualBounds.top.toFloat(), target.actualBounds.bottom.toFloat()),
                )
                return dispatchToTarget(event, activeTarget!!, sendToTarget = true)
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                val active = activeTarget ?: return false
                val sendToTarget = active.target.slopBounds.contains(x, y)
                val handled = dispatchToTarget(event, active, sendToTarget)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    activeTarget = null
                }
                return handled
            }

            MotionEvent.ACTION_CANCEL -> {
                val active = activeTarget ?: return false
                activeTarget = null
                return dispatchToTarget(event, active, sendToTarget = false)
            }

            else -> return false
        }
    }

    private fun dispatchToTarget(
        event: MotionEvent,
        active: ActiveTarget,
        sendToTarget: Boolean,
    ): Boolean {
        val childEvent = MotionEvent.obtain(event)
        if (sendToTarget) {
            val localX = event.x - active.target.actualBounds.left + active.offsetX
            val localY = event.y - active.target.actualBounds.top + active.offsetY
            childEvent.setLocation(localX, localY)
        } else {
            childEvent.setLocation(-cancelOffsetPx, -cancelOffsetPx)
        }
        val handled = active.target.view.dispatchTouchEvent(childEvent)
        childEvent.recycle()
        return handled
    }

    private fun computeOffset(raw: Float, start: Float, end: Float): Float {
        val clamped = raw.coerceIn(start, max(start, end - 1f))
        return clamped - raw
    }
}

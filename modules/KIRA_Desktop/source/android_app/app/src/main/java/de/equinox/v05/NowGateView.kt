package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * Compatibility shell retained for old project references.
 *
 * Since Kira v0.8.3 the product "Tor zum Jetzt" no longer draws a tree, node or line
 * canvas.  Its only visible expression is local deformation of the selected background
 * medium inside [MediaBackdropView].  Keeping this transparent class avoids breaking
 * older diagnostic code while preventing the previous tree overlay from resurfacing.
 */
@Deprecated("Now Gate rendering moved to MediaBackdropView in Kira v0.8.3")
class NowGateView(context: Context) : View(context) {
    private var working: WorkingSetSnapshot? = null
    private var active = false
    private var flowSpeed = 0.42f

    fun setWorkingState(snapshot: WorkingSetSnapshot?) {
        working = snapshot
        invalidate()
    }

    fun setActive(value: Boolean) {
        active = value
        invalidate()
    }

    fun setFlowSpeed(value: Float) {
        flowSpeed = value.coerceIn(0.05f, 2.5f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Intentionally transparent. The real renderer is MediaBackdropView.
        @Suppress("UNUSED_VARIABLE") val keep = Triple(working, active, flowSpeed)
    }
}

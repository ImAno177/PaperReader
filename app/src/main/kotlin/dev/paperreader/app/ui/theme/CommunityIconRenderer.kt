package dev.paperreader.app.ui.theme

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.PathParser as AndroidPathParser
import dev.paperreader.extensions.api.PaperExtensionContract

@Composable
internal fun communityIconPainter(pathData: String): Painter {
    val image = remember(pathData) {
        ImageVector.Builder(
            name = "CommunityPaperIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = PaperExtensionContract.ICON_VIEWPORT.toFloat(),
            viewportHeight = PaperExtensionContract.ICON_VIEWPORT.toFloat(),
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }
    return rememberVectorPainter(image)
}

internal class CommunityIconDrawable(pathData: String, private val intrinsicSize: Int) : Drawable() {
    private val path = requireNotNull(AndroidPathParser.createPathFromPathData(pathData))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.BLACK
    }
    private var tint: ColorStateList? = null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(
            bounds.width() / PaperExtensionContract.ICON_VIEWPORT.toFloat(),
            bounds.height() / PaperExtensionContract.ICON_VIEWPORT.toFloat(),
        )
        canvas.drawPath(path, paint)
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = intrinsicSize

    override fun getIntrinsicHeight(): Int = intrinsicSize

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateTint(state)
    }

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean = updateTint(state)

    private fun updateTint(state: IntArray): Boolean {
        val nextColor = tint?.getColorForState(state, tint?.defaultColor ?: paint.color) ?: return false
        if (paint.color == nextColor) return false
        paint.color = nextColor
        invalidateSelf()
        return true
    }
}

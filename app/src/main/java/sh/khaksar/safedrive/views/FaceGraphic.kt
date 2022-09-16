package sh.khaksar.safedrive.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.face.Face

class FaceGraphic constructor(overlay: GraphicOverlay?, private val face: Face) :
    GraphicOverlay.Graphic(overlay) {
    private val facePositionPaint: Paint
    private val rectBoxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = BOX_STROKE_WIDTH
    }

    init {
        val selectedColor = Color.WHITE
        facePositionPaint = Paint()
        facePositionPaint.color = selectedColor
    }

    /** Draws the face annotations for position on the supplied canvas. */
    override fun draw(canvas: Canvas) {
        // Draws a circle at the position of the detected face, with the face's track id below.

        // Draws a circle at the position of the detected face, with the face's track id below.
        val x = translateX(face.boundingBox.centerX().toFloat())
        val y = translateY(face.boundingBox.centerY().toFloat())

        // Calculate positions.
        val left = x - scale(face.boundingBox.width() / 2.0f)
        val top = y - scale(face.boundingBox.height() / 2.0f)
        val right = x + scale(face.boundingBox.width() / 2.0f)
        val bottom = y + scale(face.boundingBox.height() / 2.0f)
        val lineHeight = ID_TEXT_SIZE + BOX_STROKE_WIDTH
        var yLabelOffset: Float = if (face.trackingId == null) 0f else -lineHeight

        yLabelOffset += ID_TEXT_SIZE
        canvas.drawRect(left, top, right, bottom, rectBoxPaint)
    }

    companion object {
        private const val FACE_POSITION_RADIUS = 8.0f
        private const val ID_TEXT_SIZE = 30.0f

        private const val BOX_STROKE_WIDTH = 5.0f
        private const val NUM_COLORS = 10
        private val COLORS =
            arrayOf(
                intArrayOf(Color.BLACK, Color.WHITE),
                intArrayOf(Color.WHITE, Color.MAGENTA),
                intArrayOf(Color.BLACK, Color.LTGRAY),
                intArrayOf(Color.WHITE, Color.RED),
                intArrayOf(Color.WHITE, Color.BLUE),
                intArrayOf(Color.WHITE, Color.DKGRAY),
                intArrayOf(Color.BLACK, Color.CYAN),
                intArrayOf(Color.BLACK, Color.YELLOW),
                intArrayOf(Color.WHITE, Color.BLACK),
                intArrayOf(Color.BLACK, Color.GREEN)
            )
    }
}
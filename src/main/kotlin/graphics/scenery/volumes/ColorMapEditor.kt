package graphics.scenery.volumes

import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener
import org.joml.Math.clamp
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.math.absoluteValue


class ColorMapEditor : JPanel() {

    private val colorPoints = listOf(
        ColorPoint(0.0f, Color(0.2f, 1f, 0f)),
        ColorPoint(0.6f, Color(0.5f, 0f, 0f)),
        ColorPoint(1f, Color(0f, 0f, 0.9f))
    )

    var hoveredOver:ColorPoint? = null
    var dragging:ColorPoint? = null

    init {
        this.layout = MigLayout()
        this.preferredSize = Dimension(200, preferredSize.height)

        this.addMouseListener(object : MouseListener{
            override fun mouseClicked(e: MouseEvent) {}
            override fun mousePressed(e: MouseEvent) {
                val cp = pointAtMouse(e)
                cp?.let {
                    if (0f < cp.position && cp.position < 1.0f){
                        // dont move first and last point
                        dragging = cp
                    }
                }
            }
            override fun mouseReleased(e: MouseEvent?) {
                dragging = null
            }
            override fun mouseEntered(e: MouseEvent?) {}
            override fun mouseExited(e: MouseEvent?) {}
        })

        this.addMouseMotionListener(object: MouseMotionListener{
            override fun mouseDragged(e: MouseEvent) {
                dragging?.position = clamp(0.05f,0.95f, e.x/width.toFloat())
                repaint()
            }
            override fun mouseMoved(e: MouseEvent) {
                val temp = hoveredOver
                hoveredOver = pointAtMouse(e) // height is the radius of the color point sphere
                if (temp != hoveredOver) repaint()
            }
        })
    }

    private fun pointAtMouse(e: MouseEvent) =
        colorPoints.firstOrNull() { (e.x - (width * it.position)).absoluteValue < height / 2 }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val w = width
        val h = height
        val pointList = colorPoints.sortedBy { it.position }

        // background Gradient
        for (i in 0 until pointList.size - 1) {
            val p1 = pointList[i]
            val p2 = pointList[i + 1]
            val p1x = w * p1.position
            val p2x = w * p2.position
            val gp = GradientPaint(p1x, 0f, p1.color, p2x, h.toFloat(), p2.color)

            g2d.paint = gp
            g2d.fillRect(p1x.toInt(), 0, p2x.toInt(), h)

        }

        // color point markers
        pointList.forEach {
            val p1x = w * it.position
            g2d.paint = if (it == hoveredOver) Color.white else Color.BLACK
            g2d.fillOval(p1x.toInt() - h / 2, 0, h, h)

            val margin = 0.05f
            val innerSize = h * (1f - margin)
            g2d.paint = it.color
            g2d.fillOval(
                (p1x - innerSize / 2).toInt(),
                (h * margin * 0.5f).toInt(),
                innerSize.toInt(),
                innerSize.toInt()
            )
        }
    }

    class ColorPoint(var position: Float, var color: Color)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val frame = JFrame()
            frame.preferredSize = Dimension(500, 200)

            frame.title = " function"
            val tfe = ColorMapEditor()
            frame.add(tfe)
            frame.pack()
            frame.isVisible = true
        }
    }
}

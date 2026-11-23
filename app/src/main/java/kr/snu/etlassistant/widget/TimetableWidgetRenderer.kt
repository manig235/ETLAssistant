package kr.snu.etlassistant.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.phase0.R
import java.time.Instant
import kotlin.math.floor
import kotlin.math.min

/** RemoteViews에는 네이티브 텍스트/이미지만 쓰고, 시간에 비례하는 주간 격자는 기기에서 그린다. */
object TimetableWidgetRenderer {
    private val colors = intArrayOf(0xFF9ACBF0.toInt(), 0xFFE5AFBC.toInt(), 0xFFAFA9E4.toInt(),
        0xFF88CDBD.toInt(), 0xFFECC596.toInt(), 0xFFE3CC8E.toInt(), 0xFFC6B1DD.toInt(), 0xFFB4CE9E.toInt())

    fun draw(context: Context, week: TimetableWidgetLogic.Week, widthDp: Int, heightDp: Int, now: Instant): Bitmap {
        // 위젯 실제 영역 이하의 비트맵 하나만 만든다. 큰 태블릿에서도 불필요하게 확대하지 않는다.
        val density = context.resources.displayMetrics.density.coerceAtMost(3f)
        val pixelScale = min(density, 1200f / maxOf(widthDp, heightDp))
        val bitmap = Bitmap.createBitmap((widthDp * pixelScale).toInt().coerceAtLeast(1),
            (heightDp * pixelScale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(pixelScale, pixelScale)
        val secondary = context.getColor(R.color.widget_secondary)
        val accent = context.getColor(R.color.widget_accent)
        val line = context.getColor(R.color.widget_separator)
        val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.5f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val grid = week.grid
        val axis = 23f * fontScale
        val header = 18f * fontScale
        val top = header + 5f
        val bottom = heightDp - 7f
        val column = (widthDp - axis) / grid.days.size
        val hour = (bottom - top) / (grid.endHour - grid.startHour)
        val local = now.atZone(EtlLogic.KST)

        for (i in grid.days.indices) {
            val date = week.monday.plusDays(i.toLong())
            val center = axis + (i + .5f) * column
            text.textAlign = Paint.Align.CENTER
            text.color = if (date == local.toLocalDate()) accent else secondary
            text.typeface = Typeface.create("sans-serif", if (date == local.toLocalDate()) Typeface.BOLD else Typeface.NORMAL)
            text.textSize = 11f * fontScale
            canvas.drawText(grid.days[i], center, 13f * fontScale, text)
        }
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        for (h in grid.startHour..grid.endHour) {
            val y = top + (h - grid.startHour) * hour
            paint.color = line
            paint.strokeWidth = .5f
            canvas.drawLine(axis, y, widthDp.toFloat(), y, paint)
            text.color = secondary
            text.textAlign = Paint.Align.RIGHT
            text.textSize = 9f * fontScale
            canvas.drawText("%02d".format(h), axis - 4f, y + 3f, text)
        }
        for (i in 0..grid.days.size) {
            val x = axis + i * column
            paint.color = line
            canvas.drawLine(x, top, x, bottom, paint)
        }

        for (block in grid.blocks) {
            val entry = week.byGridId.getValue(block.course.courseId)
            val x = axis + grid.days.indexOf(block.meeting.day) * column + block.lane * column / block.lanes
            val y = top + (block.startMinute / 60f - grid.startHour) * hour
            val bounds = RectF(x + 1.5f, y + 1f, x + column / block.lanes - 1.5f,
                top + (block.endMinute / 60f - grid.startHour) * hour - 1f)
            if (bounds.width() < 3 || bounds.height() < 3) continue
            paint.color = if (entry.cancelled) line else
                colors[Math.floorMod((entry.row.courseId?.hashCode() ?: entry.row.course.hashCode()), colors.size)]
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(bounds, 3f, 3f, paint)
            val save = canvas.save()
            canvas.clipRect(bounds)
            val contentWidth = (bounds.width() - 6f).toInt()
            if (contentWidth > 2) {
                text.textAlign = Paint.Align.LEFT
                text.color = if (entry.cancelled) secondary else Color.rgb(29, 42, 62)
                text.textSize = 11f * fontScale
                text.letterSpacing = .025f
                text.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                val lineHeight = text.fontMetrics.let { it.descent - it.ascent }
                val lineExtra = 1f * fontScale
                val maxLines = floor((bounds.height() - 4f + lineExtra) / (lineHeight * 1.12f + lineExtra))
                    .toInt().coerceIn(1, 6)
                val title = (if (entry.cancelled) "휴강 · " else if (entry.row.kind == "보강") "보강 · " else "") + entry.row.course
                val room = entry.row.room?.takeUnless { it.isBlank() || it == "미정" || it == "강의실 미정" }
                val content = title + if (room != null && maxLines >= 3) "\n$room" else ""
                val layout = StaticLayout.Builder.obtain(content, 0, content.length, text, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                    .setLineSpacing(lineExtra, 1.12f)
                    .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.translate(bounds.left + 3f, bounds.top + ((bounds.height() - layout.height) / 2f).coerceAtLeast(2f))
                layout.draw(canvas)
            }
            text.letterSpacing = 0f
            canvas.restoreToCount(save)
        }

        val todayIndex = local.dayOfWeek.value - 1
        val minute = local.hour * 60 + local.minute
        if (todayIndex in grid.days.indices && minute >= grid.startHour * 60 && minute < grid.endHour * 60) {
            val y = top + (minute / 60f - grid.startHour) * hour
            val x = axis + todayIndex * column
            paint.color = accent
            paint.strokeWidth = 1.3f
            canvas.drawLine(x, y, x + column, y, paint)
            canvas.drawCircle(x + 2f, y, 2.2f, paint)
        }
        return bitmap
    }
}

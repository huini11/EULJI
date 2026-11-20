package com.hanscreen.translator.ocr

import android.graphics.RectF
import kotlin.math.abs

data class Paragraph(
    val text: String,
    val box: RectF,
    val lineCount: Int
)

/**
 * 把 OCR 的逐行结果聚成段落，避免把一句话拆成多次翻译请求。
 */
object TextGrouper {

    fun group(lines: List<OcrLine>): List<Paragraph> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val groups = ArrayList<MutableList<OcrLine>>()

        for (line in sorted) {
            var target: MutableList<OcrLine>? = null
            for (g in groups.asReversed()) {
                val last = g.last()
                if (belongs(last.box, line.box, avgHeight(g))) {
                    target = g
                    break
                }
            }
            if (target != null) target.add(line) else groups.add(mutableListOf(line))
        }

        return groups.map { g ->
            val box = RectF(g.first().box)
            g.drop(1).forEach { box.union(it.box) }
            Paragraph(buildText(g), box, g.size)
        }
    }

    private fun avgHeight(g: List<OcrLine>): Float {
        var sum = 0f
        for (l in g) sum += l.box.height()
        return (sum / g.size).coerceAtLeast(1f)
    }

    private fun belongs(prev: RectF, cur: RectF, h: Float): Boolean {
        // 同一行被切成多段：垂直中心几乎重合且水平间距很小 → 合并
        val sameRow = abs(prev.centerY() - cur.centerY()) < h * 0.55f
        if (sameRow) {
            val gap = cur.left - prev.right
            if (gap < h * 2.0f && gap > -h * 2.0f) return true
        }

        val vGap = cur.top - prev.bottom
        if (vGap > h * 0.9f) return false        // 行距过大 → 新段
        if (vGap < -h * 1.6f) return false       // 反向重叠过多 → 新段

        val overlap = minOf(prev.right, cur.right) - maxOf(prev.left, cur.left)
        val minWidth = minOf(prev.width(), cur.width()).coerceAtLeast(1f)
        val leftAligned = abs(prev.left - cur.left) < h * 2.2f
        return overlap > minWidth * 0.22f || leftAligned
    }

    private fun buildText(g: List<OcrLine>): String =
        g.joinToString(" ") { it.text }
            .replace(Regex("\\s+"), " ")
            .trim()
}

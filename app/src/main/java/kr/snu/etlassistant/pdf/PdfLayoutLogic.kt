package kr.snu.etlassistant.pdf

import kr.snu.etlassistant.etl.EtlFiles
import kr.snu.etlassistant.etl.EtlLogic

/** make_pdf의 순수 규칙: 쪽 나누기, 파일 이름, 상한(JVM 단위 테스트 대상). */
object PdfLayoutLogic {
    /** A4(포인트, 1/72인치). */
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN_X = 56f
    const val MARGIN_TOP = 56f
    /** 아래 여백(쪽 번호 자리 포함). */
    const val MARGIN_BOTTOM = 64f

    const val MAX_PAGES = 200
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_MARKDOWN_CHARS = 300_000
    const val MAX_TITLE_CHARS = 120

    val contentWidth: Float get() = PAGE_WIDTH - 2 * MARGIN_X
    val contentHeight: Float get() = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM

    /**
     * 쪽에 놓을 조각 하나(글 한 줄, 수식 그림, 표 한 행 등). [gapBefore]는 앞 조각과의 간격(쪽 맨 위에서는 버린다).
     * [keepWithNext]면 다음 조각이 같은 쪽에 들어가지 않을 때 이 조각부터 다음 쪽으로 넘긴다(제목, 문단 첫 줄).
     */
    data class Item(val height: Float, val gapBefore: Float = 0f, val keepWithNext: Boolean = false)

    /** [page]는 0부터, [y]는 본문 영역 위에서부터의 거리. */
    data class Placement(val page: Int, val y: Float)

    /**
     * 조각들을 차례로 쪽에 놓는다. 쪽 높이보다 큰 조각은 새 쪽 맨 위에 놓는다(그리는 쪽이 줄여서 그린다).
     */
    fun paginate(items: List<Item>, pageHeight: Float = contentHeight): List<Placement> {
        val out = ArrayList<Placement>(items.size)
        var page = 0
        var y = 0f
        for ((i, item) in items.withIndex()) {
            var gap = if (y == 0f) 0f else item.gapBefore
            val h = minOf(item.height, pageHeight)
            if (y > 0f && y + gap + h > pageHeight) {
                page++
                y = 0f
                gap = 0f
            } else if (y > 0f && item.keepWithNext) {
                val next = items.getOrNull(i + 1)
                if (next != null && y + gap + h + next.gapBefore + minOf(next.height, pageHeight) > pageHeight &&
                    h + next.gapBefore + minOf(next.height, pageHeight) <= pageHeight
                ) {
                    page++
                    y = 0f
                    gap = 0f
                }
            }
            out += Placement(page, y + gap)
            y += gap + h
        }
        return out
    }

    fun pageCount(placements: List<Placement>): Int = (placements.maxOfOrNull { it.page } ?: 0) + 1

    /**
     * 저장할 파일 이름. [filename]이 있으면 그것, 없으면 제목. 경로 구분자·제어문자를 지우고(앱 공통 규칙),
     * 확장자는 늘 `.pdf`, UTF-8 200바이트 안으로 줄인다.
     */
    fun fileName(title: String, filename: String?): String {
        val raw = filename?.trim()?.takeIf { it.isNotEmpty() } ?: title.trim()
        val base = EtlLogic.safeName(raw, "영민이 문서").replace(Regex("\\s+"), " ").trim()
            .let { if (it.lowercase().endsWith(".pdf")) it.dropLast(4) else it }
            .trimEnd('.', ' ')
            .ifEmpty { "영민이 문서" }
        return EtlFiles.fitName("$base.pdf")
    }

    /** 쪽 아래 표시: "제목 · 3 / 10". 제목이 길면 줄인다. */
    fun footer(title: String, page: Int, total: Int): String {
        val t = title.trim().let { if (it.length > 40) it.take(39) + "…" else it }
        return if (t.isEmpty()) "$page / $total" else "$t · $page / $total"
    }
}

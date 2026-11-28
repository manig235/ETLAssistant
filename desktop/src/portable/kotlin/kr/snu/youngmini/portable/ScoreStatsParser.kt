// Ported from Android 0.3.8 (025ac84); platform storage is implemented in desktop/.
package kr.snu.youngmini.portable

import kr.snu.etlassistant.etl.*

/**
 * 성적 공지 본문에서 점수 분포를 뽑는다(2026-11-28, 기기 안, AI 없음, 순수 함수).
 * 공대 수업은 채점 뒤 공지에 "평균 62.4 / 표준편차 15.1 / 최고 98" 같은 통계를 올리는 일이 많아서, 시간마다 확인할 때
 * 깔끔하게 읽히는 공지만 바로 저장한다. **애매하면 저장하지 않는다**([Result.confident] = false): 그때는 알림의 "통계 저장" 버튼으로
 * 비서에게 맡긴다(히스토그램 그림도 마찬가지, 기기 안 글자 인식은 하지 않는다).
 *
 * 읽는 것
 * - 낱말 + 숫자: 평균/average/mean, 표준편차/std/σ, 최고(점)/max, 최저/min, 중앙값/median/Q2, Q1/1사분위/하위 25%, Q3/3사분위/상위 25%,
 *   만점·총점/out of N/"(100점 만점)"/"62.4/100", 응시 인원("42명").
 * - 표: 위 줄이 항목(평균·표준편차…)이고 아래 줄이 숫자인 표, 또는 줄마다 항목이고 칸마다 시험(중간·기말)인 표. HTML 칸은 " | "로 나눈다.
 * - 시험 이름(중간·기말·퀴즈 N·Exam N·HW N…)과 분반(N분반·section N)이 나오면 그 뒤 숫자는 그 시험·분반 것이다. 한 공지에 여럿이면 여러 항목.
 * 날짜·시각·강의실 번호처럼 낱말 바로 뒤가 아닌 숫자는 읽지 않고, 낱말 뒤 숫자라도 월·일·시·%·: 가 붙으면 버린다.
 */
object ScoreStatsParser {

    enum class Field { MIN, MAX, MEAN, Q1, MEDIAN, Q3, STD, COUNT, POINTS }

    /** 시험 하나(또는 분반 하나)의 분포. [key]는 맞춰 보기용 시험 이름(예: `mid`, `exam1`, `hw3`), 없으면 null. */
    data class Item(val label: String?, val key: String?, val section: Int?, val stats: ScoreStatistics, val points: Double?) {
        val fieldCount: Int get() = listOfNotNull(stats.min, stats.max, stats.mean, stats.q1, stats.median, stats.q3, stats.stddev).size
        val fieldNames: List<String> get() = listOfNotNull(
            "min".takeIf { stats.min != null }, "max".takeIf { stats.max != null }, "mean".takeIf { stats.mean != null },
            "q1".takeIf { stats.q1 != null }, "median".takeIf { stats.median != null }, "q3".takeIf { stats.q3 != null },
            "std".takeIf { stats.stddev != null }, "count".takeIf { stats.count != null }, "points".takeIf { points != null },
        )
    }

    data class Result(val items: List<Item>, val confident: Boolean, val reason: String? = null)

    private const val NUM = "(\\d{1,4}(?:\\.\\d{1,3})?)"
    private fun en(w: String) = "(?<![A-Za-z])(?:$w)(?![A-Za-z])"

    private val FIELD_WORDS: List<Pair<Field, Regex>> = listOf(
        Field.Q1 to "제?\\s*1\\s*사분위(?:수|값)?|하위\\s*25\\s*%(?:\\s*(?:지점|점수|값))?|(?<![A-Za-z0-9])Q1(?![0-9])|${en("1st\\s+quartile|lower\\s+quartile|25th\\s+percentile")}",
        Field.Q3 to "제?\\s*3\\s*사분위(?:수|값)?|상위\\s*25\\s*%(?:\\s*(?:지점|점수|값))?|(?<![A-Za-z0-9])Q3(?![0-9])|${en("3rd\\s+quartile|upper\\s+quartile|75th\\s+percentile")}",
        Field.MIN to "(?<![A-Za-z0-9])Q0(?![0-9])",
        Field.MAX to "(?<![A-Za-z0-9])Q4(?![0-9])",
        Field.MEDIAN to "중앙\\s*값|중간\\s*값|중위\\s*수|제?\\s*2\\s*사분위(?:수|값)?|(?<![A-Za-z0-9])Q2(?![0-9])|${en("median")}",
        Field.STD to "표준\\s*편차|σ|${en("std\\.?\\s*dev(?:iation)?\\.?|standard\\s+deviation|stdev|std\\.?|s\\.d\\.|SD")}",
        Field.MEAN to "평균(?:\\s*점수|\\s*값)?|${en("mean|average|avg\\.?")}",
        Field.MAX to "최고\\s*점(?:수)?|최고(?!의)|최댓값|최대값|최대\\s*점수|최대(?!한)|${en("max(?:imum)?\\.?|highest(?:\\s+score)?|top\\s+score")}",
        Field.MIN to "최저\\s*점(?:수)?|최저(?!의)|최솟값|최소값|최소\\s*점수|최소(?!한)|${en("min(?:imum)?\\.?|lowest(?:\\s+score)?")}",
        Field.COUNT to "응시\\s*(?:인원|자\\s*수|자)|수강\\s*(?:인원|생\\s*수)|인원|${en("n\\s*(?==)|number\\s+of\\s+students|students")}",
        Field.POINTS to "만점|총점|배점|${en("out\\s+of|total\\s+(?:points|score)|full\\s+(?:marks|score)")}",
    ).map { (f, p) -> f to Regex(p, RegexOption.IGNORE_CASE) }

    /** 시험 이름. 그룹: 1=말, 2=번호(있으면). "중간값"의 "중간"은 빼야 해서 따로 막는다. */
    private const val LABEL_NEXT = "(?=\\s*(?:성적|점수|결과|평가|통계|평균|표준|최고|최저|중앙|\\d|Q\\d|mean|avg|average|median)|[,.)\\]:：|]|\\s*$)"
    private val LABEL = Regex(
        "(?:(\\d)\\s*차\\s*)?(중간\\s*고사|중간\\s*시험|중간(?!\\s*값)$LABEL_NEXT|기말\\s*고사|기말\\s*시험|기말$LABEL_NEXT|퀴즈|쪽지\\s*시험|시험|과제|실험|프로젝트|" +
            "(?<![A-Za-z])(?:mid-?\\s*terms?(?:\\s*exams?)?|finals?(?:\\s*exams?)?|quiz(?:zes)?|exams?|tests?|hw|homework|assignments?|labs?|projects?)(?![A-Za-z]))" +
            "(?:\\s*#?\\s*(\\d{1,2})(?![\\d.]))?",
        RegexOption.IGNORE_CASE,
    )
    private val SECTION = Regex("(\\d{1,3})\\s*분반|분반\\s*(\\d{1,3})|(?<![A-Za-z])sec(?:tion|\\.)?\\s*(\\d{1,3})(?!\\d)|(?<!\\d)(\\d{3})\\s*반(?![가-힣])", RegexOption.IGNORE_CASE)
    /** 낱말과 숫자 사이에 와도 되는 것: 띄어쓰기·":"·"="·"은/는/이/가"·"점수"·"score" 정도. 그 밖의 말이 끼면 읽지 않는다. */
    private val FILLER_OK = Regex("^[\\s:：=\\-–은는이가(\\[]*(?:점수|값|score|of\\s+scores?)?[\\s:：=\\-–은는이가(\\[]*$", RegexOption.IGNORE_CASE)
    /** 숫자 뒤가 "점 만점"·"점 기준"이면 그 숫자는 만점이다(앞 낱말의 값이 아니다). */
    private val AFTER_POINTS = Regex("^\\s*점?\\s*(?:만점|기준)")
    private val CHANGE_AFTER = Regex("^\\s*점?\\s*(?:정도\\s*|가량\\s*)?(?:올|상승|하락|높|낮|증가|감소|떨어|차이|up\\b|down\\b|higher|lower)", RegexOption.IGNORE_CASE)
    private val CHANGE_BEFORE = Regex("(?:보다|대비|비해|than|compared)\\s*[^\\n.。]{0,6}$", RegexOption.IGNORE_CASE)
    private val TOTAL_ROW = Regex("^(전체|합계|총|total|all|overall)$", RegexOption.IGNORE_CASE)
    /** 숫자 뒤에 오면 점수가 아닌 것(날짜·시각·비율·횟수). 인원은 "명"을 허용한다. */
    private val AFTER_BAD = Regex("^\\s*(?:월|일(?![반])|시(?![험])|분(?![반])|%|퍼센트|년|개(?!월)|회|주(?!차)|호|교시|:\\d|\\.\\d|/\\d{1,2}\\s*\\()")
    private val SLASH_POINTS = Regex("^\\s*/\\s*$NUM(?![\\d.])")
    private val POINTS_BEFORE = Regex("$NUM\\s*점\\s*만점|$NUM\\s*점\\s*기준|(?<![A-Za-z])out\\s+of\\s+$NUM", RegexOption.IGNORE_CASE)

    private data class Hit(val start: Int, val end: Int, val field: Field)

    /** 공지 HTML을 줄 글로(표 칸은 " | "로 나눔). */
    fun textFromHtml(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        val cells = Regex("</\\s*t[dh]\\s*>", RegexOption.IGNORE_CASE).replace(html, " | ")
        return EtlLogic.htmlToText(cells, 20_000)
    }

    /** 표준 시험 이름과 맞춰 보기용 키. 이름이 없으면 null. 밑줄·하이픈은 띄어쓰기로 본다(`I2EP_Exam_1`). */
    fun examKey(text: String?): Pair<String, String>? {
        val s = text.orEmpty().replace('_', ' ').replace('-', ' ')
        val m = LABEL.find(s) ?: return null
        return canonical(m)
    }

    private fun canonical(m: MatchResult): Pair<String, String> {
        val word = m.groupValues[2].lowercase().replace(Regex("\\s+"), "")
        val n = (m.groupValues[3].ifEmpty { m.groupValues[1] }).toIntOrNull()
        val kind = when {
            word.startsWith("중간") || word.startsWith("mid") -> "mid"
            word.startsWith("기말") || word.startsWith("final") -> "final"
            word == "퀴즈" || word.startsWith("쪽지") || word.startsWith("quiz") -> "quiz"
            word == "hw" || word == "homework" || word == "과제" || word.startsWith("assignment") -> "hw"
            word.startsWith("lab") || word == "실험" -> "lab"
            word.startsWith("project") || word == "프로젝트" -> "project"
            else -> "exam"
        }
        val label = when (kind) {
            "mid" -> "중간고사"; "final" -> "기말고사"; "quiz" -> "퀴즈"; "hw" -> "과제"; "lab" -> "실험"; "project" -> "프로젝트"; else -> "시험"
        } + (n?.let { " $it" } ?: "")
        return (kind + (n ?: "")) to label
    }

    /** "Min/Q1/Q2/Q3/Max: 40 / 55 / 66 / 77 / 90"처럼 이름을 "/"로 묶고 값도 "/"로 묶은 줄. */
    private val GROUP = Regex("((?:[A-Za-z가-힣][A-Za-z가-힣0-9. ]{0,18}?\\s*/\\s*){1,7}[A-Za-z가-힣][A-Za-z가-힣0-9. ]{0,18}?)\\s*[:：=]\\s*($NUM(?:[ \\t]*/[ \\t]*$NUM){1,7})")

    /** 묶음 줄을 "Min 40, Q1 55, …"로 풀어 쓴다. 이름 수와 값 수가 다르거나 모르는 이름이 있으면 그대로 둔다. */
    internal fun expandGroups(text: String): String = GROUP.replace(text) { m ->
        val keys = m.groupValues[1].split('/').map { it.trim() }
        val vals = Regex(NUM).findAll(m.groupValues[2]).map { it.value }.toList()
        if (keys.size != vals.size || keys.any { fieldOf(it) == null }) m.value
        else keys.zip(vals).joinToString(", ") { (k, v) -> "$k $v" }
    }

    fun parse(title: String?, text: String): Result {
        val titleLabel = title?.replace('_', ' ')?.let { t -> LABEL.find(t)?.let(::canonical) }
        val lines = expandGroups(text.replace('_', ' ')).split('\n')
        val tableItems = mutableListOf<Item>()
        var ambiguous: String? = null
        val rest = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val consumed = tryTable(lines, i, titleLabel, tableItems)
            if (consumed < 0) { ambiguous = "표를 읽지 못함"; i++; continue }
            if (consumed > 0) { i += consumed; continue }
            rest.append(lines[i]).append('\n')
            i++
        }
        val (inline, why) = inline(rest.toString(), titleLabel)
        ambiguous = ambiguous ?: why
        val items = (tableItems + inline).filter { it.fieldCount > 0 }
        return judge(items, ambiguous)
    }

    private fun judge(items: List<Item>, ambiguous: String?): Result {
        if (items.isEmpty()) return Result(emptyList(), false, ambiguous ?: "통계 없음")
        if (ambiguous != null) return Result(items, false, ambiguous)
        if (items.size > 8) return Result(items, false, "항목이 너무 많음")
        if (items.size > 1 && (items.any { it.key == null && it.section == null } || items.map { it.key to it.section }.distinct().size != items.size))
            return Result(items, false, "시험 이름이 겹치거나 없음")
        for (it in items) {
            val core = listOfNotNull(it.stats.mean, it.stats.median)
            if (core.isEmpty() || it.fieldCount < 2) return Result(items, false, "값이 부족함")
            ScoreStats.validate(it.stats, null, it.points)?.let { e -> return Result(items, false, e) }
        }
        return Result(items, true)
    }

    // ---- 글 속 "낱말 숫자" -----------------------------------------------------------------

    private class Seg(val label: Pair<String, String>?, val section: Int?) {
        val values = linkedMapOf<Field, Double>()
        var conflict = false
        fun put(f: Field, v: Double) {
            val old = values[f]
            if (old != null && kotlin.math.abs(old - v) > 1e-9) conflict = true else values[f] = v
        }
    }

    private fun hits(s: String): List<Hit> {
        val all = FIELD_WORDS.flatMap { (f, r) -> r.findAll(s).map { Hit(it.range.first, it.range.last + 1, f) }.toList() }
            .sortedWith(compareBy<Hit> { it.start }.thenByDescending { it.end - it.start })
        val out = mutableListOf<Hit>()
        for (h in all) if (out.none { h.start < it.end && it.start < h.end }) out += h
        return out.sortedBy { it.start }
    }

    /** 이름 없는 "시험"·"과제"·"exam"은 번호가 있을 때만 시험 이름으로 본다("시험 성적을 공개합니다"의 "시험"은 이름이 아니다). */
    private fun isNamedLabel(key: String): Boolean = key !in setOf("exam", "hw", "lab", "project")

    private fun inline(s: String, titleLabel: Pair<String, String>?): Pair<List<Item>, String?> {
        data class Ev(val pos: Int, val label: Pair<String, String>? = null, val section: Int? = null, val hit: Hit? = null, val pointsBefore: Double? = null)
        val fieldHits = hits(s)
        val inWord = { p: Int -> fieldHits.any { p >= it.start && p < it.end } }
        val events = mutableListOf<Ev>()
        LABEL.findAll(s).forEach { m -> if (!inWord(m.range.first)) canonical(m).takeIf { isNamedLabel(it.first) }?.let { events += Ev(m.range.first, label = it) } }
        SECTION.findAll(s).forEach { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()?.let { events += Ev(m.range.first, section = it) } }
        fieldHits.forEach { events += Ev(it.start, hit = it) }
        POINTS_BEFORE.findAll(s).forEach { m -> events += Ev(m.range.first, pointsBefore = m.groupValues.drop(1).first { it.isNotEmpty() }.toDouble()) }
        events.sortBy { it.pos }

        val segs = mutableListOf<Seg>()
        var label = titleLabel
        var section: Int? = null
        var last: Seg? = null
        var why: String? = null
        fun seg(): Seg = (segs.firstOrNull { it.label == label && it.section == section } ?: Seg(label, section).also { segs += it }).also { last = it }
        for (e in events) {
            when {
                e.label != null -> { label = e.label; section = null }
                e.section != null -> section = e.section
                // "(100점 만점)": 바로 앞에서 값을 넣은 구간의 만점(없으면 지금 구간).
                e.pointsBefore != null -> (last ?: seg()).put(Field.POINTS, e.pointsBefore)
                e.hit != null -> {
                    val h = e.hit
                    val next = fieldHits.firstOrNull { it.start >= h.end }?.start ?: s.length
                    val tail = s.substring(h.end, minOf(s.length, next, h.end + 40))
                    val m = Regex("^([^\\d\\n|]{0,12}?)$NUM").find(tail) ?: continue
                    if (!FILLER_OK.matches(m.groupValues[1])) continue
                    val after = tail.substring(m.range.last + 1)
                    val v = m.groupValues[2].toDouble()
                    if (h.field == Field.COUNT) {
                        if (Regex("^\\s*(?:명|people|students)", RegexOption.IGNORE_CASE).containsMatchIn(after) || '=' in m.groupValues[1]) seg().put(Field.COUNT, v)
                        continue
                    }
                    if (h.field != Field.POINTS && AFTER_POINTS.containsMatchIn(after)) continue
                    // "지난 학기보다 평균이 5점 올랐어요" 같은 비교·변화 문장의 숫자는 분포 값이 아니다.
                    if (CHANGE_AFTER.containsMatchIn(after) || CHANGE_BEFORE.containsMatchIn(s.substring(maxOf(0, h.start - 12), h.start))) continue
                    if (AFTER_BAD.containsMatchIn(after)) { why = "숫자 뒤가 점수가 아님"; continue }
                    val target = seg()
                    target.put(h.field, v)
                    SLASH_POINTS.find(after)?.let { pm -> pm.groupValues[1].toDouble().takeIf { it >= v }?.let { target.put(Field.POINTS, it) } }
                }
            }
        }
        if (segs.any { it.conflict }) why = why ?: "같은 항목에 값이 둘"
        return segs.mapNotNull { it.toItem() } to why
    }

    private fun Seg.toItem(): Item? {
        val v = values
        val st = ScoreStatistics(v[Field.MIN], v[Field.MAX], v[Field.MEAN], v[Field.Q1], v[Field.MEDIAN], v[Field.Q3], v[Field.STD], v[Field.COUNT]?.toInt())
        if (st.isEmpty) return null
        return Item(label?.second, label?.first, section, st, v[Field.POINTS])
    }

    // ---- 표 ---------------------------------------------------------------------

    /** 줄을 칸으로. "|"가 있으면 "|"로(맨 앞 "|"는 빈 칸 하나), 없으면 숫자·항목 이름만 있는 줄일 때만 띄어쓰기로. */
    private fun cells(line: String): List<String>? {
        var t = line.trim()
        if (t.isEmpty()) return null
        if ('|' in t) {
            if (t.endsWith('|')) t = t.dropLast(1)
            return t.split('|').map { it.trim() }
        }
        return t.split(Regex("\\s+")).takeIf { parts -> parts.size >= 2 && parts.all { isNumber(it) || fieldOf(it) != null } }
    }

    private fun isRule(line: String) = Regex("^[\\s|:\\-–—=+]+$").matches(line) && '-' in line

    private fun isNumber(c: String) = Regex("^$NUM\\s*(?:점)?$").matches(c.trim())
    private fun number(c: String) = Regex(NUM).find(c)?.groupValues?.get(1)?.toDouble()

    /** 칸이 통째로 항목 이름인가(예: "평균", "Std.", "최고점"). */
    private fun fieldOf(c: String): Field? {
        val t = c.trim().trimEnd(':', '：').trim()
        if (t.isEmpty() || t.length > 20) return null
        return FIELD_WORDS.firstOrNull { (_, r) -> r.matchEntire(t) != null || r.matchEntire(t.replace(Regex("\\s*\\(.*\\)$"), "")) != null }?.first
    }

    private fun labelOf(c: String): Pair<String, String>? = LABEL.matchEntire(c.trim().replace(Regex("\\s*\\(.*\\)$"), ""))?.let(::canonical)
    private fun sectionOf(c: String): Int? = SECTION.matchEntire(c.trim())?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

    /**
     * [start] 줄부터 표를 읽어 [out]에 넣고 읽은 줄 수를 돌려준다. 표가 아니면 0, 표 같지만 읽을 수 없으면 −1.
     * 모양 A: 머리 줄 칸이 항목(첫 칸은 비거나 "구분"·"시험"일 수 있음), 아래 줄마다 [시험·분반] + 숫자.
     * 모양 B: 머리 줄 칸이 시험 이름, 아래 줄마다 항목 이름 + 숫자.
     */
    private fun tryTable(lines: List<String>, start: Int, titleLabel: Pair<String, String>?, out: MutableList<Item>): Int {
        var head = cells(lines[start]) ?: return 0
        if (head.size < 2) return 0
        var j = start + 1
        while (j < lines.size && isRule(lines[j])) j++
        // 마크다운 표("| a | b |")는 줄마다 맨 앞이 빈 칸이다. 몸통 첫 줄도 그러면 모두 뗀다.
        val strip = head.first().isEmpty() && j < lines.size && cells(lines[j])?.firstOrNull()?.isEmpty() == true
        fun row(i: Int) = cells(lines[i])?.let { if (strip && it.firstOrNull()?.isEmpty() == true) it.drop(1) else it }
        if (strip) head = head.drop(1)
        val headFields = head.map(::fieldOf)
        val lead = head.first().let { it.isEmpty() || Regex("^(구분|시험|항목|분반|반|exam|section|class|)$", RegexOption.IGNORE_CASE).matches(it) }
        val body = mutableListOf<List<String>>()
        while (j < lines.size) {
            if (isRule(lines[j])) { j++; continue }
            val r = row(j) ?: break
            if (r.size != head.size) break
            body += r; j++
        }
        // 모양 A
        val aFields = if (lead && headFields.first() == null) headFields.drop(1) else headFields
        if (aFields.count { it != null } >= 2 && aFields.all { it != null }) {
            if (body.isEmpty()) return 0
            val offset = head.size - aFields.size
            for (r in body) {
                val name = if (offset == 1) r.first() else ""
                val nums = r.drop(offset)
                if (nums.any { it.isNotEmpty() && !isNumber(it) }) return -1
                val v = aFields.zip(nums).mapNotNull { (f, c) -> number(c)?.let { f!! to it } }.toMap()
                val total = name.isEmpty() || TOTAL_ROW.matches(name)
                val lab = if (total) titleLabel else labelOf(name) ?: titleLabel
                val sec = if (total) null else sectionOf(name)
                if (!total && labelOf(name) == null && sec == null) return -1
                out += itemOf(lab, sec, v) ?: continue
            }
            return j - start
        }
        // 모양 B
        val labels = head.drop(1).map { if (TOTAL_ROW.matches(it)) titleLabel to null else labelOf(it) to sectionOf(it) }
        if (lead && head.size >= 2 && labels.all { it.first != null || it.second != null } && body.isNotEmpty() && body.all { fieldOf(it.first()) != null }) {
            val cols = labels.map { mutableMapOf<Field, Double>() }
            for (r in body) {
                val f = fieldOf(r.first())!!
                r.drop(1).forEachIndexed { k, c ->
                    if (c.isNotEmpty() && !isNumber(c)) return -1
                    number(c)?.let { cols[k][f] = it }
                }
            }
            labels.forEachIndexed { k, (lab, sec) -> itemOf(lab ?: titleLabel, sec, cols[k])?.let { out += it } }
            return j - start
        }
        return 0
    }

    private fun itemOf(label: Pair<String, String>?, section: Int?, v: Map<Field, Double>): Item? {
        val st = ScoreStatistics(v[Field.MIN], v[Field.MAX], v[Field.MEAN], v[Field.Q1], v[Field.MEDIAN], v[Field.Q3], v[Field.STD], v[Field.COUNT]?.toInt())
        if (st.isEmpty) return null
        return Item(label?.second, label?.first, section, st, v[Field.POINTS])
    }
}

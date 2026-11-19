package kr.snu.etlassistant.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kr.snu.etlassistant.phase0.R

/** 대화 목록. 사용자/어시스턴트 말풍선, 도구 호출 칩, 안내문 네 가지 모양이다. */
class ChatAdapter(private val ctx: Context, private val model: ChatModel) : RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(val frame: FrameLayout, val tv: TextView) : RecyclerView.ViewHolder(frame)

    private val dm = ctx.resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    private fun color(id: Int) = ctx.getColor(id)

    override fun getItemCount() = model.messages.size

    override fun getItemViewType(position: Int) = model.messages[position].role.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val role = Role.values()[viewType]
        val tv = TextView(ctx).apply {
            textSize = if (role == Role.TOOL || role == Role.NOTICE) 12.5f else 15.5f
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            maxWidth = (dm.widthPixels * 0.86f).toInt()
            movementMethod = LinkMovementMethod.getInstance()
            setLineSpacing(0f, 1.1f)
        }
        val frame = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
        }
        val gravity = when (role) {
            Role.USER -> Gravity.END
            Role.NOTICE -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity))
        return VH(frame, tv)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = model.messages[position]
        val bg = GradientDrawable().apply { cornerRadius = dp(16f).toFloat() }
        when (m.role) {
            Role.USER -> {
                bg.setColor(color(R.color.bubble_user))
                h.tv.setTextColor(color(R.color.bubble_user_text))
                h.tv.text = m.text
            }
            Role.ASSISTANT -> {
                bg.setColor(color(R.color.bubble_assistant))
                h.tv.setTextColor(color(R.color.bubble_assistant_text))
                h.tv.text = if (m.text.isEmpty()) "…" else toSpannable(MarkdownLite.render(m.text))
            }
            Role.TOOL -> {
                val failed = m.status == "failed"
                bg.setColor(color(if (failed) R.color.chip_error_bg else R.color.chip_bg))
                bg.cornerRadius = dp(12f).toFloat()
                h.tv.setTextColor(color(if (failed) R.color.chip_error_text else R.color.chip_text))
                h.tv.text = toolLabel(m)
            }
            Role.NOTICE -> {
                h.tv.setTextColor(color(R.color.notice_text))
                h.tv.text = m.text
                h.tv.background = null
                h.tv.setOnLongClickListener(null)
                return
            }
        }
        h.tv.background = bg
        // 길게 누르면 본문을 복사한다.
        h.tv.setOnLongClickListener {
            if (m.role == Role.USER || m.role == Role.ASSISTANT) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", m.text))
                Toast.makeText(ctx, "복사했습니다", Toast.LENGTH_SHORT).show()
                true
            } else false
        }
    }

    private fun toolLabel(m: Message): String {
        val name = TOOL_NAMES[m.text] ?: m.text
        return when (m.status) {
            "completed" -> "✓ $name"
            "failed" -> "✕ $name — ${m.error ?: "실패"}"
            else -> "⏳ $name 중…"
        }
    }

    private fun toSpannable(r: MarkdownLite.Result): CharSequence {
        val sb = SpannableStringBuilder(r.text)
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        for (s in r.spans) {
            when (s.kind) {
                MarkdownLite.Kind.BOLD -> sb.setSpan(StyleSpan(Typeface.BOLD), s.start, s.end, flag)
                MarkdownLite.Kind.CODE -> {
                    sb.setSpan(TypefaceSpan("monospace"), s.start, s.end, flag)
                    sb.setSpan(BackgroundColorSpan(color(R.color.code_bg)), s.start, s.end, flag)
                }
                MarkdownLite.Kind.CODE_BLOCK -> {
                    sb.setSpan(TypefaceSpan("monospace"), s.start, s.end, flag)
                    sb.setSpan(BackgroundColorSpan(color(R.color.code_bg)), s.start, s.end, flag)
                    sb.setSpan(RelativeSizeSpan(0.92f), s.start, s.end, flag)
                }
                MarkdownLite.Kind.HEADER -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), s.start, s.end, flag)
                    sb.setSpan(RelativeSizeSpan(1.12f), s.start, s.end, flag)
                }
                MarkdownLite.Kind.LINK -> sb.setSpan(URLSpan(s.url), s.start, s.end, flag)
            }
        }
        return sb
    }

    companion object {
        private val TOOL_NAMES = mapOf(
            "etl_digest" to "마감·공지 요약",
            "etl_courses" to "강의 목록",
            "etl_assignment" to "과제 상세",
            "etl_search_files" to "파일 검색",
            "etl_download" to "파일 다운로드",
            "etl_verify_submission" to "제출 확인",
            "etl_get" to "eTL 조회",
        )
    }
}

package kr.snu.etlassistant.notify

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** eTL 외부 링크 공통 진입점. 누른 시점에 LearningX가 없으면 같은 웹 원문을 연다. */
class LearningXOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val url = LearningXLink.webUrl(intent.dataString) ?: return
            val appUrl = LearningXLink.fromWebUrl(url) ?: return
            val opened = open(Intent(Intent.ACTION_VIEW, Uri.parse(appUrl)).setPackage(LearningXLink.PACKAGE))
            if (opened && LearningXLink.opensBoardList(url)) {
                Toast.makeText(this, "LearningX에서 게시판을 열어요. 해당 글은 목록에서 선택해 주세요.", Toast.LENGTH_LONG).show()
            }
            if (!opened) open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } finally {
            finish()
        }
    }

    private fun open(target: Intent): Boolean = try {
        startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        /** 앱 안의 모든 외부 링크가 공유한다. eTL은 LearningX로, 나머지는 원래 주소로 연다. */
        fun intent(context: Context, url: String): Intent {
            val etlUrl = LearningXLink.webUrl(url)
            return if (etlUrl != null) Intent(context, LearningXOpenActivity::class.java).setData(Uri.parse(etlUrl))
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
    }
}

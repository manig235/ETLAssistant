package kr.snu.etlassistant.etl

import android.content.Context
import java.io.File

/**
 * 설정의 "eTL 연결 해제". 토큰과, 앱이 eTL에서 **스스로** 받아 둔 캐시를 지운다.
 *
 * 지우는 것:
 * - eTL 토큰(Keystore로 암호화한 값), 게시판(LearningX) 세션 토큰(메모리)
 * - 과제 목록 캐시(`etl_snapshot`: 내 일정·위젯·알림 대체용), 새 과제·성적 비교 기준(`notify_watch`)
 * - 수강편람에서 받은 정규 시간표(`timetable`의 `weekly`), 비서용 강의계획서 첨부(`workspace/etl-files/syllabus/`)
 *
 * 남기는 것(사용자가 직접 만들었거나 요청한 것, 따로 지울 수 있다):
 * - 대화와 그 안에서 받은 파일·쪽 이미지(대화 삭제), 알림 기록(알림 기록 화면의 지우기),
 *   내 마감, 직접 넣은 수업·보강/휴강, 폰 Download/etl/에 저장한 파일
 * - 알림 중복 방지 기록(`notify_state`): 알림 ID만 있고, 지우면 내 마감 알림이 다시 울릴 수 있다
 */
object EtlDisconnect {

    /** eTL 데이터 캐시만 지운다(토큰 제외). 순서상 토큰을 먼저 지운 뒤 부른다. */
    fun clearCaches(context: Context) {
        val app = context.applicationContext
        BoardSession.clear()
        app.getSharedPreferences("etl_snapshot", Context.MODE_PRIVATE).edit().clear().apply()
        app.getSharedPreferences("notify_watch", Context.MODE_PRIVATE).edit().clear().apply()
        app.getSharedPreferences("timetable", Context.MODE_PRIVATE).edit().remove("weekly").apply()
        val root = File(app.filesDir, "workspace/${EtlFiles.DIR}")
        File(root, EtlFiles.SYLLABUS_DIR).deleteRecursively()
        // 이전 빌드는 계획서 첨부를 etl-files/ 바로 아래 `syllabus-…`로 두었다.
        root.listFiles()?.filter { it.isFile && it.name.startsWith("syllabus-") }?.forEach { it.delete() }
    }

    fun run(context: Context) {
        EtlService.tokens(context).clear()
        clearCaches(context)
    }
}

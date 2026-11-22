package kr.snu.etlassistant.notify

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 배터리 최적화 예외. 앱이 백그라운드에 있으면 절전 정책(삼성은 절전 앱·딥 슬립 앱 포함) 때문에 주기 확인이 늦거나
 * eTL 요청이 끊겨, 앱을 열어 둘 때만 알림이 제대로 오는 일이 있다. 예외로 두면 백그라운드 확인이 제때 돈다.
 *
 * APK로 직접 배포하므로 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`(Play 정책 제한 대상)를 쓴다.
 */
object BatteryOpt {
    /** 삼성 폰에서 따로 확인할 곳(시스템 창의 "허용"만으로는 절전 앱 목록에서 빠지지 않을 수 있다). */
    const val SAMSUNG_HINT = "삼성 폰은 설정 → 배터리 → 백그라운드 사용 제한에서 '절전 앱'이나 '딥 슬립 앱'에 영민이가 있으면 빼 주세요."

    /**
     * 절전 모드에서는 백그라운드 앱의 네트워크가 막혀(BATTERY_SAVER) 새 조회가 실패한다. 이미 받아 둔 과제로 마감 알림은 보낸다
     * ([SnapshotFallback]). 배터리 최적화 예외면 이 차단에서 빠진다(2026-11-22 S938N·S908N 실측: 예외면 절전 모드에서도
     * `effective=NONE`이고 새로 조회됨). 그래서 화면에는 절전 모드이면서 예외가 아닐 때만 보인다.
     */
    const val POWER_SAVE_HINT = "절전 모드에서는 새 과제·공지 알림이 늦을 수 있어요. 이미 받아 둔 마감 알림은 그대로 와요."

    fun isPowerSaveMode(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }.getOrDefault(false)

    fun isIgnoring(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(true) // 확인할 수 없으면 경고하지 않는다

    /** 시스템 "배터리 사용량 최적화 중지" 창을 연다. 안 되면 최적화 목록, 그것도 안 되면 앱 정보 화면. */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val pkg = context.packageName
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (i in intents) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}

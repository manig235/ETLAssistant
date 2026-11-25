package kr.snu.etlassistant.debug

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import kr.snu.etlassistant.onboarding.OnboardingWelcomeOnly

/**
 * 디버그 빌드 전용: 첫 실행 안내의 환영 화면만 띄운다(안내를 이미 마친 기기에서 화면 확인용).
 * 뷰모델·세션을 만들지 않고 [kr.snu.etlassistant.onboarding.OnboardingPrefs]나 다른 저장 값을 건드리지 않는다.
 * "시작하기"는 이 화면을 닫기만 한다.
 *
 *   adb shell am start -n kr.snu.etlassistant.phase0/kr.snu.etlassistant.debug.WelcomePreviewActivity [--es theme light|dark]
 *
 * theme은 이 화면에만 적용한다(localNightMode, 앱의 화면 모드 설정은 그대로).
 */
class WelcomePreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        when (intent.getStringExtra("theme")) {
            "light" -> delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OnboardingWelcomeOnly(onStart = { finish() }) }
    }
}

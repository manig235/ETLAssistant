package kr.snu.etlassistant.etl

import android.content.Context
import kr.snu.etlassistant.etl.EtlLogic.str
import kr.snu.etlassistant.onboarding.OnboardingLogic

/**
 * eTL 토큰을 **실제로 접속해 확인**(`GET /api/v1/users/self`, Bearer)하고, 맞으면 Keystore에 암호화해 저장한다.
 * 첫 실행 안내·설정의 붙여넣기와 "서울대 로그인으로 연결"이 같이 쓴다. 네트워크를 쓰므로 백그라운드 스레드에서 부른다.
 * 토큰 원문은 결과·오류 문구·로그에 넣지 않는다.
 */
object EtlTokenCheck {

    sealed class Result {
        /** 저장됨. [name]은 표시용 이름(없으면 null). */
        data class Saved(val name: String?) : Result()
        data class Error(val message: String) : Result()
    }

    const val BAD_FORMAT = "토큰 모양이 아니에요. 공백 없이 그대로 붙여넣어 주세요"

    fun verifyAndSave(context: Context, raw: String): Result {
        val token = raw.trim()
        if (!OnboardingLogic.isPlausibleEtlToken(token)) return Result.Error(BAD_FORMAT)
        return try {
            val me = EtlClient(token).getObject("/api/v1/users/self")
            val name = OnboardingLogic.displayName(me.str("name") ?: me.str("short_name"))
            EtlService.tokens(context).save(token)
            Result.Saved(name)
        } catch (e: EtlAuthException) {
            Result.Error("eTL이 이 토큰을 받아주지 않았어요. 다시 만들어서 붙여넣어 주세요")
        } catch (e: EtlException) {
            Result.Error("eTL에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요")
        } catch (e: Exception) {
            Result.Error("확인하지 못했어요(${e.javaClass.simpleName})")
        }
    }
}

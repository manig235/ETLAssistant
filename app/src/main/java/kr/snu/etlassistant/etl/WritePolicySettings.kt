package kr.snu.etlassistant.etl

import android.content.Context

class WritePolicySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("assistant_write_policy", Context.MODE_PRIVATE)

    fun mode(scope: WriteScope): WriteMode = WriteMode.parse(prefs.getString(scope.name, null))

    fun save(scope: WriteScope, mode: WriteMode, consented: Boolean = false) {
        require(mode != WriteMode.AUTO_ADD || consented) { "자동 추가는 사용자 동의가 필요해요" }
        prefs.edit().putString(scope.name, mode.name).apply()
    }

    fun requireEnabled(scope: WriteScope) {
        if (mode(scope) == WriteMode.OFF) throw EtlException("설정에서 비서의 ${scope.label} 변경이 꺼져 있어요")
    }

    fun toolEnabled(name: String): Boolean = WritePolicy.operation(name)?.let { mode(it.scope) != WriteMode.OFF } ?: true
}

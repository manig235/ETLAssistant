package kr.snu.etlassistant.onboarding

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.navigations.progressindicator.WantedLinearProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.snu.etlassistant.etl.EtlTokenCheck
import kr.snu.etlassistant.etl.EtlTokenIssuer
import kr.snu.etlassistant.phase0.CodexRunner

/**
 * "서울대 로그인으로 연결": 서울대 SSO 로그인 페이지를 앱 안에 띄우고, 사용자가 **직접** 로그인해 eTL 화면에 돌아오면
 * 그 세션으로 eTL 액세스 토큰 하나("영민이 (기기 모델)")를 만들어 확인·저장한다.
 *
 * - 비밀번호는 서울대 페이지에만 입력된다. 앱은 입력값을 읽지 않는다(JS 인터페이스 없음, 양식 저장 끔).
 * - 시작할 때와 끝날 때(성공·실패·취소·종료) 쿠키·웹 저장소·캐시·기록을 모두 지운다. 그래서 이전 로그인이 남아 있지 않고,
 *   토큰은 이번에 사용자가 로그인한 세션으로만 만든다.
 * - 토큰은 메모리에만 두고 곧바로 [EtlTokenCheck.verifyAndSave]로 저장한다. Intent·로그에 넣지 않는다.
 *   결과는 RESULT_OK와 표시용 이름([EXTRA_NAME])만 돌려준다.
 * - 로그에는 주소의 호스트·경로와 결과 분류(`ok`, `http 403`)만 남긴다.
 */
class EtlLoginActivity : AppCompatActivity() {

    private sealed class Phase {
        object Browsing : Phase()
        object Connecting : Phase()
        data class Error(val message: String, val suggestPaste: Boolean) : Phase()
    }

    private var phase by mutableStateOf<Phase>(Phase.Browsing)
    private var pageProgress by mutableIntStateOf(0)
    private var loading by mutableStateOf(true)

    private var webView: WebView? = null
    /** 세션 확인이 돌고 있다(같은 페이지 완료가 여러 번 와도 한 번만). */
    private var checking = false
    /** 이번 로그인으로 토큰 만들기를 이미 보냈다(자동으로 두 번 만들지 않는다). */
    private var issued = false
    private var wiped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val view = createWebView()
        webView = view
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = webView
                if (phase is Phase.Browsing && w != null && w.canGoBack()) w.goBack() else cancel()
            }
        })
        setContent { Screen(view) }
        startFresh()
    }

    override fun onDestroy() {
        // 성공·실패·취소 어느 경우든 로그인 흔적을 남기지 않는다.
        wipe()
        webView?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.stopLoading()
            w.webChromeClient = null
            w.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // ---- WebView -------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true // 서울대 SSO가 스크립트로 동작한다
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION")
            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
            setGeolocationEnabled(false)
            // window.open은 같은 화면에서 연다(새 창을 만들지 않음).
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(w, false) // 로그인은 모두 최상위 이동이라 필요 없다
        w.webViewClient = Client()
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageProgress = newProgress
            }
        }
        w.setDownloadListener { _, _, _, _, _ -> toast("이 화면에서는 파일을 받을 수 없어요") }
        return w
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString() ?: return true
            val nav = EtlLoginLogic.navigation(url)
            if (!request.isForMainFrame) {
                // 하위 프레임: https·빈 페이지만 그대로, 앱 호출은 사용자가 누른 경우만.
                return when {
                    nav == EtlLoginLogic.Nav.LOAD || nav == EtlLoginLogic.Nav.BROWSER || url.startsWith("about:") -> false
                    nav == EtlLoginLogic.Nav.EXTERNAL_APP && request.hasGesture() -> { openExternalApp(url); true }
                    else -> true
                }
            }
            when (nav) {
                EtlLoginLogic.Nav.LOAD -> return false
                EtlLoginLogic.Nav.BROWSER -> openBrowser(url)
                EtlLoginLogic.Nav.EXTERNAL_APP -> openExternalApp(url)
                EtlLoginLogic.Nav.BLOCK -> {
                    log("blocked: ${EtlLoginLogic.logUrl(url)}")
                    toast("이 화면에서는 열 수 없는 주소예요")
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loading = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            if (wiped) return
            log("page: ${EtlLoginLogic.logUrl(url)}")
            maybeConnect(url)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            log("renderer gone")
            // 이 WebView는 더 쓸 수 없다. 흔적을 지우고 닫는다(다시 누르면 새로 연다).
            toast("로그인 화면이 멈췄어요. 다시 시도해 주세요")
            webView = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            cancel()
            return true
        }
    }

    // ---- 연결 ----------------------------------------------------------------

    /** 쿠키·저장소·캐시를 지운 뒤 로그인 첫 페이지를 연다. */
    private fun startFresh() {
        val w = webView ?: return
        phase = Phase.Browsing
        checking = false
        issued = false
        loginPrefs(this).edit().putBoolean(PREF_DIRTY, true).apply()
        wiped = false
        clearWebData(w)
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            if (!isDestroyed && webView === w) {
                log("start")
                w.loadUrl(EtlLoginLogic.START_URL)
            }
        }
    }

    private fun maybeConnect(url: String?) {
        if (phase !is Phase.Browsing || checking || issued) return
        if (!EtlLoginLogic.isLoggedInCandidate(url)) return
        val cookie = CookieManager.getInstance().getCookie(EtlLoginLogic.SELF_URL) ?: return
        checking = true
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { EtlTokenIssuer.checkSession(cookie) }
            checking = false
            if (session != null) {
                // 아직 로그인 중간 단계이거나 연결이 잠깐 안 됨: 조용히 다음 페이지를 기다린다.
                log(
                    when (session) {
                        is EtlTokenIssuer.Result.NotLoggedIn -> "session: http ${session.code}"
                        is EtlTokenIssuer.Result.Failed -> "session: network"
                        else -> "session: ?"
                    }
                )
                return@launch
            }
            if (phase !is Phase.Browsing || issued) return@launch
            issued = true
            phase = Phase.Connecting
            log("session: ok")
            val purpose = EtlLoginLogic.tokenPurpose(Build.MODEL)
            val created = withContext(Dispatchers.IO) { EtlTokenIssuer.create(cookie, purpose) }
            // 세션은 더 쓰지 않는다. 성공·실패와 관계없이 바로 지운다.
            wipe()
            when (created) {
                is EtlTokenIssuer.Result.Issued -> {
                    log("token issue: ok")
                    val saved = withContext(Dispatchers.IO) { EtlTokenCheck.verifyAndSave(applicationContext, created.token) }
                    when (saved) {
                        is EtlTokenCheck.Result.Saved -> {
                            log("token check: ok")
                            setResult(RESULT_OK, Intent().apply { saved.name?.let { putExtra(EXTRA_NAME, it) } })
                            finish()
                        }
                        is EtlTokenCheck.Result.Error -> {
                            log("token check: error")
                            phase = Phase.Error(saved.message, suggestPaste = false)
                        }
                    }
                }
                is EtlTokenIssuer.Result.Failed -> {
                    log(if (created.code < 0) "token issue: network" else "token issue: http ${created.code}")
                    phase = Phase.Error(created.message, created.suggestPaste)
                }
                is EtlTokenIssuer.Result.NotLoggedIn -> {
                    log("token issue: not logged in")
                    phase = Phase.Error(EtlLoginLogic.issueErrorMessage(302), suggestPaste = false)
                }
            }
        }
    }

    private fun cancel() {
        wipe()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun choosePaste() {
        wipe()
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_PASTE, true))
        finish()
    }

    /** 로그인 흔적(쿠키, 웹 저장소, 캐시, 방문 기록, 양식·HTTP 인증 기록)을 지운다. 여러 번 불러도 된다. */
    private fun wipe() {
        if (wiped) return
        wiped = true
        webView?.let { w ->
            w.stopLoading()
            w.loadUrl("about:blank")
            clearWebData(w)
        }
        wipeGlobal(this)
        log("wiped")
    }

    private fun clearWebData(w: WebView) {
        w.clearCache(true)
        w.clearHistory()
        w.clearFormData()
        w.clearSslPreferences()
    }

    // ---- 다른 앱·브라우저 ------------------------------------------------------

    /** 인증 앱(Pass-Ni 등)으로 넘긴다. 다른 앱의 내부 화면을 직접 지정하지 못하게 컴포넌트·셀렉터를 지운다. */
    private fun openExternalApp(url: String) {
        log("external app: ${url.substringBefore(':').take(30)}")
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            null
        }
        if (intent != null) {
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.flags = 0
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        val fallback = EtlLoginLogic.browserFallbackUrl(url)
        when {
            fallback != null && EtlLoginLogic.navigation(fallback) == EtlLoginLogic.Nav.LOAD -> webView?.loadUrl(fallback)
            fallback != null -> openBrowser(fallback)
            intent?.`package` != null && PACKAGE_RE.matches(intent.`package`!!) -> {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                try {
                    startActivity(market)
                } catch (_: ActivityNotFoundException) {
                    toast(NO_APP)
                }
            }
            else -> toast(NO_APP)
        }
    }

    private fun openBrowser(url: String) {
        log("browser: ${EtlLoginLogic.logUrl(url)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            toast("링크를 열 앱이 없어요")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        CodexRunner.log.line("[eTL 로그인] $msg")
    }

    // ---- 화면 ----------------------------------------------------------------

    @androidx.compose.runtime.Composable
    private fun Screen(view: WebView) {
        DesignSystemTheme {
            val c = DesignSystemTheme.colors
            val t = DesignSystemTheme.typography
            Column(
                Modifier
                    .fillMaxSize()
                    .background(c.backgroundNeutralPrimary)
                    .systemBarsPadding()
                    .imePadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { cancel() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "닫기", tint = c.foregroundNeutralPrimary)
                    }
                    Text("서울대 로그인", style = t.headline1Bold, color = c.foregroundNeutralPrimary)
                }
                if (loading && phase is Phase.Browsing) {
                    WantedLinearProgressIndicator(
                        currentProgress = (pageProgress.coerceIn(5, 100)) / 100f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    HINT,
                    style = t.caption1Regular,
                    color = c.foregroundNeutralSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    when (val p = phase) {
                        Phase.Browsing -> {}
                        Phase.Connecting -> Overlay {
                            WantedCircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("연결하는 중…", style = t.headline2Bold, color = c.foregroundNeutralPrimary)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "eTL에 영민이 토큰을 만들고 있어요",
                                style = t.body2Regular,
                                color = c.foregroundNeutralSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        is Phase.Error -> Overlay {
                            Text("연결하지 못했어요", style = t.headline2Bold, color = c.foregroundNeutralPrimary)
                            Spacer(Modifier.height(8.dp))
                            Text(p.message, style = t.body2Regular, color = c.foregroundNeutralSecondary, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(20.dp))
                            val retry = @androidx.compose.runtime.Composable {
                                WantedButton(
                                    text = "다시 로그인하기",
                                    modifier = Modifier.fillMaxWidth(),
                                    type = if (p.suggestPaste) ButtonType.ASSISTIVE else ButtonType.PRIMARY,
                                    size = ButtonSize.LARGE,
                                    variant = if (p.suggestPaste) ButtonVariant.OUTLINED else ButtonVariant.SOLID,
                                    onClick = { startFresh() },
                                )
                            }
                            val paste = @androidx.compose.runtime.Composable {
                                WantedButton(
                                    text = "직접 붙여넣기로 하기",
                                    modifier = Modifier.fillMaxWidth(),
                                    type = if (p.suggestPaste) ButtonType.PRIMARY else ButtonType.ASSISTIVE,
                                    size = ButtonSize.LARGE,
                                    variant = if (p.suggestPaste) ButtonVariant.SOLID else ButtonVariant.OUTLINED,
                                    onClick = { choosePaste() },
                                )
                            }
                            if (p.suggestPaste) { paste(); Spacer(Modifier.height(8.dp)); retry() }
                            else { retry(); Spacer(Modifier.height(8.dp)); paste() }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Overlay(content: @androidx.compose.runtime.Composable () -> Unit) {
        val c = DesignSystemTheme.colors
        Box(
            Modifier
                .fillMaxSize()
                .background(c.backgroundNeutralPrimary)
                // 아래 로그인 화면이 눌리지 않게 막는다.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surfaceNeutralSecondary)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { content() }
        }
    }

    /** 결과: 연결됨(표시용 이름) / 붙여넣기로 하겠다 / 그냥 닫음. */
    sealed class Outcome {
        data class Connected(val name: String?) : Outcome()
        object Paste : Outcome()
        object Canceled : Outcome()
    }

    class Contract : ActivityResultContract<Unit, Outcome>() {
        override fun createIntent(context: Context, input: Unit): Intent = Intent(context, EtlLoginActivity::class.java)
        override fun parseResult(resultCode: Int, intent: Intent?): Outcome = when {
            resultCode == RESULT_OK -> Outcome.Connected(intent?.getStringExtra(EXTRA_NAME))
            intent?.getBooleanExtra(EXTRA_PASTE, false) == true -> Outcome.Paste
            else -> Outcome.Canceled
        }
    }

    companion object {
        private const val TAG = "YoungminEtlLogin"
        const val EXTRA_NAME = "name"
        const val EXTRA_PASTE = "paste"
        private const val PREF_DIRTY = "dirty"
        private const val NO_APP = "인증 앱을 열지 못했어요. 앱이 설치돼 있는지 확인해 주세요"
        const val HINT = "로그인하면 자동으로 연결돼요. 비밀번호는 서울대 페이지에만 입력되고 앱에 저장되지 않아요."
        private val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        private fun loginPrefs(context: Context) =
            context.applicationContext.getSharedPreferences("etl_login", Context.MODE_PRIVATE)

        /** 앱 전체 WebView 저장소(쿠키·웹 저장소·HTTP 인증 기록)를 지운다. 메인 스레드에서 부른다. */
        private fun wipeGlobal(context: Context) {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
            @Suppress("DEPRECATION")
            WebViewDatabase.getInstance(context).clearFormData()
            loginPrefs(context).edit().putBoolean(PREF_DIRTY, false).apply()
        }

        /**
         * 로그인 도중 앱이 강제로 끝나 지우지 못한 흔적이 있으면 지운다(앱 시작 때). 메인 스레드에서 부른다.
         * 흔적이 없으면 WebView를 건드리지 않는다.
         */
        fun wipeIfInterrupted(context: Context) {
            if (!loginPrefs(context).getBoolean(PREF_DIRTY, false)) return
            runCatching { wipeGlobal(context) }
        }
    }
}

package kr.snu.etlassistant.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.theme.DesignSystemTheme

/** Displays the exact policy bundled from docs/privacy-policy.md at build time, without network access. */
class PrivacyPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val policy = assets.open("privacy-policy.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        setContent {
            DesignSystemTheme {
                val colors = DesignSystemTheme.colors
                val type = DesignSystemTheme.typography
                Column(Modifier.fillMaxSize().background(colors.backgroundNeutralPrimary).systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = colors.foregroundNeutralPrimary)
                        }
                        Text("개인정보 처리방침", style = type.title2Bold, color = colors.foregroundNeutralPrimary)
                    }
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        MarkdownBody(
                            text = policy,
                            style = type.body1ReadingRegular,
                            color = colors.foregroundNeutralPrimary,
                            codeBackground = colors.surfaceNeutralTertiary,
                            linkColor = colors.foregroundBrandPrimary,
                            onLink = ::openLink,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }

    private fun openLink(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("https", "http")) return
        if (runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isFailure) {
            Toast.makeText(this, "링크를 열 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, PrivacyPolicyActivity::class.java)
    }
}

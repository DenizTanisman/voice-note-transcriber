package dev.deniz.ses.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import dev.deniz.ses.App
import dev.deniz.ses.data.State
import dev.deniz.ses.service.ActionReceiver
import kotlinx.coroutines.delay

/** WhatsApp › hold a voice message › Paylaş › Yazıya Dök. Shows the text in a small window over the chat. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val uris = uris(intent)
        setContent { Theme { Sheet(uris) } }
    }

    private fun uris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: i.data)
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(i.data)
        else -> emptyList()
    }

    @Composable
    private fun Sheet(uris: List<Uri>) {
        val ctx = LocalContext.current
        var texts by remember { mutableStateOf<List<String>>(emptyList()) }
        var refining by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(true) }
        var started by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var elapsed by remember { mutableLongStateOf(0L) }

        LaunchedEffect(Unit) {
            if (uris.isEmpty()) { error = "Ses dosyası gelmedi"; busy = false; return@LaunchedEffect }
            started = System.currentTimeMillis()
            for (u in uris) {
                val before = texts
                val v = App.jobs.transcribeUri(u) { r ->
                    // First call: fast text appears and the spinner goes; second call swaps in the accurate text.
                    runOnUiThread {
                        if (r.state == State.DONE) texts = before + r.text
                        if (r.state == State.EMPTY) texts = before + "(konuşma duyulmadı)"
                        refining = r.state == State.DONE && r.pass == 1
                        if (r.state == State.DONE || r.state == State.EMPTY) busy = false
                    }
                }
                if (v.state == State.FAILED) error = v.error
            }
            busy = false; refining = false
        }
        LaunchedEffect(busy) { while (busy) { elapsed = System.currentTimeMillis() - started; delay(250) } }

        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🎧 Sesli mesaj", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (busy) {
                    Text("Yazıya dökülüyor… ${elapsed / 1000} sn")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val all = texts.joinToString("\n\n")
                if (refining) Text("Daha doğru hâli hazırlanıyor…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (all.isNotEmpty()) {
                    SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(all, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                error?.let { Text("Olmadı: $it", color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    if (busy) TextButton(onClick = { App.jobs.cancel(); finish() }) { Text("Vazgeç") }
                    else {
                        if (all.isNotEmpty()) {
                            TextButton(onClick = { shareText(ctx, all) }) { Text("Paylaş") }
                            TextButton(onClick = { ActionReceiver.copy(ctx, all) }) { Text("Kopyala") }
                        }
                        Button(onClick = { finish() }) { Text("Kapat") }
                    }
                }
            }
        }
    }
}

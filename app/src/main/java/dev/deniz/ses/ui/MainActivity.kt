package dev.deniz.ses.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.deniz.ses.App
import dev.deniz.ses.data.Source
import dev.deniz.ses.data.State
import dev.deniz.ses.data.Voice
import dev.deniz.ses.service.ActionReceiver
import dev.deniz.ses.service.Notifier
import dev.deniz.ses.service.WaListener
import dev.deniz.ses.wa.WaFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var playing by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        getExternalFilesDir("models") // creates Android/data/<pkg>/files/models for adb push
        setContent { Theme { Root() } }
    }

    override fun onStop() {
        super.onStop()
        stop()
    }

    private fun play(v: Voice) {
        stop()
        if (v.source == Source.SHARE) return
        player = MediaPlayer().apply {
            setDataSource(v.id); prepare(); start()
            setOnCompletionListener { this@MainActivity.stop() }
        }
        playing = v.id
    }

    private fun stop() {
        player?.release(); player = null; playing = null
    }

    data class Setup(val files: Boolean, val listener: Boolean, val notify: Boolean, val model: String?)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        val changes by App.jobs.changes.collectAsState()
        val running by App.jobs.running.collectAsState()
        var limit by remember { mutableIntStateOf(60) }
        var items by remember { mutableStateOf<List<Voice>>(emptyList()) }
        var setup by remember { mutableStateOf<Setup?>(null) }
        var resumed by remember { mutableIntStateOf(0) }
        var settings by remember { mutableStateOf(false) }
        var auto by remember { mutableStateOf(App.prefs.auto) }

        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { resumed++ } }

        LaunchedEffect(changes, limit, resumed) {
            withContext(Dispatchers.IO) {
                setup = Setup(
                    files = Environment.isExternalStorageManager(),
                    listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
                    notify = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
                    model = App.jobs.engine.fastModel()?.name,
                )
                items = load(limit)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Yazıya Dök") },
                    actions = {
                        Text("Otomatik", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = auto, onCheckedChange = { auto = it; App.prefs.auto = it })
                        TextButton(onClick = { settings = true }) { Text("Ayarlar") }
                    },
                )
            },
        ) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                setup?.let { s -> if (!s.files || !s.listener || !s.notify || s.model == null) item { SetupCard(s) } }
                if (items.isEmpty() && setup?.files == true) item {
                    Text("Henüz sesli mesaj bulunamadı.", modifier = Modifier.padding(16.dp))
                }
                items(items, key = { it.id }) { v -> VoiceCard(v, running == v.id) }
                if (items.size >= limit) item {
                    OutlinedButton(onClick = { limit += 60 }, modifier = Modifier.fillMaxWidth()) { Text("Daha eski mesajlar") }
                }
            }
        }
        if (settings) SettingsDialog { settings = false; resumed++ }
    }

    private fun load(limit: Int): List<Voice> {
        val files = if (Environment.isExternalStorageManager()) WaFolder.recent(limit) else emptyList()
        val rows = App.db.byIds(files.map { it.absolutePath })
        val wa = files.map { f -> rows[f.absolutePath] ?: Voice(id = f.absolutePath, fileTime = f.lastModified(), state = State.QUEUED, source = Source.MANUAL, updated = 0) }
        val shares = App.db.shares(20)
        return (wa + shares).sortedByDescending { if (it.msgTime > 0) it.msgTime else it.fileTime }
    }

    @Composable
    private fun SetupCard(s: Setup) {
        val ctx = LocalContext.current
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kurulum", fontWeight = FontWeight.Bold)
                if (!s.files) Step("WhatsApp'ın ses klasörünü okumak için tüm dosyalara erişim") {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                if (!s.listener) Step("Gelen sesli mesajı fark etmek için bildirim erişimi") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, WaListener::class.java).flattenToString()),
                    )
                }
                if (!s.notify) Step("Yazıyı bildirim olarak göstermek için bildirim izni") {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                if (s.model == null) Text(
                    "Model yok: ggml-small-q5_1.bin ve ggml-medium-q5_0.bin dosyalarını şu klasöre koyun:\n" +
                        (ctx.getExternalFilesDir("models")?.absolutePath ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun Step(text: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text("Aç") }
        }
    }

    @Composable
    private fun VoiceCard(v: Voice, isRunning: Boolean) {
        val ctx = LocalContext.current
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        v.label ?: if (v.source == Source.SHARE) "Paylaşılan ses" else "Sesli mesaj",
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(Notifier.durationText(v.durationMs), whenText(if (v.msgTime > 0) v.msgTime else v.fileTime)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    v.state == State.DONE -> {
                        SelectionContainer { Text(v.text, style = MaterialTheme.typography.bodyLarge) }
                        if (isRunning && v.pass == 1) {
                            Text("Daha doğru hâli hazırlanıyor…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    isRunning || v.state == State.RUNNING -> {
                        Text("Yazıya dökülüyor…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    v.state == State.EMPTY -> Text("(konuşma duyulmadı)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    v.state == State.FAILED -> Text("Olmadı: ${v.error}", color = MaterialTheme.colorScheme.error)
                    v.updated > 0 -> Text("Sırada…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (v.source != Source.SHARE) {
                        TextButton(onClick = { if (playing == v.id) stop() else runCatching { play(v) } }) {
                            Text(if (playing == v.id) "■ Durdur" else "▶ Dinle")
                        }
                        if (!isRunning && v.state != State.RUNNING && File(v.id).exists()) {
                            TextButton(onClick = { App.jobs.enqueueFile(v.id) }) {
                                Text(if (v.state == State.DONE || v.state == State.EMPTY || v.state == State.FAILED) "Tekrar" else "Yazıya dök")
                            }
                        }
                    }
                    if (v.state == State.DONE) {
                        TextButton(onClick = { ActionReceiver.copy(ctx, v.text) }) { Text("Kopyala") }
                        TextButton(onClick = { shareText(ctx, v.text) }) { Text("Paylaş") }
                    }
                }
                if (v.state == State.DONE && v.tookMs > 0) Text(
                    "${v.model.removePrefix("ggml-").removeSuffix(".bin")} · ${"%.1f".format(v.tookMs / 1000.0)} sn",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun SettingsDialog(onClose: () -> Unit) {
        val p = App.prefs
        val e = App.jobs.engine
        val models = remember { e.models().map { it.name }.sorted() }
        var fast by remember { mutableStateOf(e.fastModel()?.name ?: "") }
        var good by remember { mutableStateOf(e.goodModel()?.name ?: "") }
        var refine by remember { mutableStateOf(p.refine) }
        var lang by remember { mutableStateOf(p.lang) }
        var threads by remember { mutableIntStateOf(p.threads) }
        AlertDialog(
            onDismissRequest = onClose,
            confirmButton = {
                TextButton(onClick = {
                    p.fastModel = fast; p.goodModel = good; p.refine = refine; p.lang = lang; p.threads = threads
                    onClose()
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Vazgeç") } },
            title = { Text("Ayarlar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (models.isEmpty()) Text("Model bulunamadı", color = MaterialTheme.colorScheme.error)
                    Text("1. Hızlı metin", fontWeight = FontWeight.SemiBold)
                    models.forEach { m -> FilterChip(selected = m == fast, onClick = { fast = m }, label = { Text(short(m)) }) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("2. Ardından daha doğru metin", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(checked = refine, onCheckedChange = { refine = it })
                    }
                    if (refine) models.forEach { m -> FilterChip(selected = m == good, onClick = { good = m }, label = { Text(short(m)) }) }
                    Text(
                        "Ölçüm (gerçek sesli mesajlar, hata oranı): small %27 · medium %14 · turbo %12. " +
                            "15 sn'lik mesajda small ~5 sn, medium ~20 sn, turbo ~40 sn.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Dil", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = lang == "tr", onClick = { lang = "tr" }, label = { Text("Türkçe") })
                        FilterChip(selected = lang == "auto", onClick = { lang = "auto" }, label = { Text("Otomatik") })
                    }
                    Text("İş parçacığı", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 6, 8).forEach { n -> FilterChip(selected = threads == n, onClick = { threads = n }, label = { Text("$n") }) }
                    }
                }
            },
        )
    }

    private fun short(m: String) = m.removePrefix("ggml-").removeSuffix(".bin")
}

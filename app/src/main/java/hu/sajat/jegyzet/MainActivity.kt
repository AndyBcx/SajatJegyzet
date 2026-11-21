package hu.sajat.jegyzet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onResume() {
        super.onResume()
        Thread { store.migrate() }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) androidx.compose.material3.dynamicDarkColorScheme(this)
                else androidx.compose.material3.dynamicLightColorScheme(this)
            ) {
                Surface(Modifier.fillMaxSize()) { App(store) }
            }
        }
    }
}

private fun fmtDate(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store) {
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) } // null = lista, "" = új jegyzet
    var tick by remember { mutableIntStateOf(0) }

    if (editing != null) {
        Editor(store, editing!!) { editing = null; tick++ }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("Jegyzetek", "Felvételek", "Beállítások").forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) ExtendedFloatingActionButton(onClick = { editing = "" }) { Text("+ Új jegyzet") }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> NotesTab(store, tick) { editing = it }
                1 -> RecordingsTab(store, tick) { tick++ }
                else -> SettingsTab(store) { tick++ }
            }
        }
    }
}

@Composable
fun NotesTab(store: Store, tick: Int, open: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val notes = remember(tick, q) {
        store.notes().filter { q.isBlank() || it.body.contains(q, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("Keresés") }, singleLine = true
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)) {
            items(notes, key = { it.id }) { n ->
                Column(Modifier.fillMaxWidth().clickable { open(n.id) }.padding(vertical = 10.dp)) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        n.body.lineSequence().drop(1).firstOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2
                    )
                    Text(fmtDate(n.modified), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun Editor(store: Store, id: String, close: () -> Unit) {
    var text by remember { mutableStateOf(if (id.isEmpty()) "" else store.readNote(id)) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun saveAndClose() { store.saveNote(id.ifEmpty { null }, text); close() }
    BackHandler { saveAndClose() }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { saveAndClose() }) { Text("← Kész") }
            if (id.isNotEmpty()) TextButton(onClick = { confirmDelete = true }) { Text("Törlés") }
        }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxSize(),
            placeholder = { Text("Első sor = cím\n\nÍrj ide...") }
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Törlöd a jegyzetet?") },
        confirmButton = { TextButton(onClick = { store.deleteNote(id); close() }) { Text("Törlés") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Mégse") } }
    )
}

@Composable
fun RecordingsTab(store: Store, tick: Int, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val recording by RecState.recording.collectAsState()
    val startedAt by RecState.startedAt.collectAsState()
    var elapsed by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var local by remember { mutableIntStateOf(0) }
    var recStatus by remember { mutableStateOf("") }
    var transcribing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val files = remember(tick, local, recording) { store.recordings() }

    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(recording) {
        while (recording) { elapsed = (System.currentTimeMillis() - startedAt) / 1000; delay(500) }
        if (!recording) refresh()
    }

    fun startService() {
        ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startService()
    }
    fun toggle() {
        if (recording) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startService()
        } else {
            perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    fun play(f: File) {
        player?.release(); player = null
        if (playing == f) { playing = null; return }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
            setOnCompletionListener { playing = null }
        }
        playing = f
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { toggle() }, Modifier.fillMaxWidth()) {
            Text(if (recording) "■ Leállítás  (%d:%02d)".format(elapsed / 60, elapsed % 60) else "● Felvétel indítása")
        }
        if (recStatus.isNotEmpty()) Text(recStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(files, key = { it.name }) { f ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleMedium)
                    Text(fmtDate(f.lastModified()), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { play(f) }) { Text(if (playing == f) "Stop" else "Lejátszás") }
                        TextButton(enabled = transcribing == null, onClick = {
                            if (store.asrUrl.isBlank()) { recStatus = "Add meg az átirat szerver URL-jét a Beállításokban."; return@TextButton }
                            transcribing = f.name; recStatus = "Átirat készül: ${f.name}..."
                            scope.launch {
                                recStatus = try {
                                    val text = withContext(Dispatchers.IO) {
                                        Transcriber.transcribe(store.asrUrl, store.asrKey, store.asrModel, store.asrLang, f)
                                    }
                                    store.importText("Átirat – ${f.nameWithoutExtension}", text)
                                    refresh()
                                    "Kész, új jegyzet készült az átiratból."
                                } catch (e: Exception) { "Hiba: ${e.message}" }
                                transcribing = null
                            }
                        }) { Text(if (transcribing == f.name) "..." else "Átirat") }
                        TextButton(onClick = { renaming = f }) { Text("Átnevez") }
                        TextButton(onClick = {
                            if (playing == f) { player?.release(); player = null; playing = null }
                            f.delete(); local++
                        }) { Text("Törlés") }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    renaming?.let { f ->
        var name by remember(f) { mutableStateOf(f.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Átnevezés") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { store.renameRecording(f, name); renaming = null; local++ }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Mégse") } }
        )
    }
}

@Composable
fun SettingsTab(store: Store, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(store.davUrl) }
    var user by remember { mutableStateOf(store.davUser) }
    var pass by remember { mutableStateOf(store.davPass) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(store.autoSync) }
    var asrUrl by remember { mutableStateOf(store.asrUrl) }
    var asrKey by remember { mutableStateOf(store.asrKey) }
    var asrModel by remember { mutableStateOf(store.asrModel) }
    var asrLang by remember { mutableStateOf(store.asrLang) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            status = try {
                val n = withContext(Dispatchers.IO) { Importer.import(ctx, store, uris) }
                refresh(); "Importálva: $n jegyzet."
            } catch (e: Exception) { "Import hiba: ${e.message}" }
        }
    }

    var hasAccess by remember { mutableStateOf(store.sharedAccess) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasAccess = store.sharedAccess
        scope.launch { withContext(Dispatchers.IO) { store.migrate() }; refresh() }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { store.exportZip(it) }
                }
                "Exportálva."
            } catch (e: Exception) { "Export hiba: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Szinkron Syncthinggel (ajánlott)", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasAccess) "Az adatok itt vannak: ${store.rootDir.absolutePath}\nAdd hozzá ezt a mappát a Syncthing(-Fork) appban, és oszd meg a szerveroddal."
            else "Az adatok most az app belső tárolójában vannak, ezt a Syncthing nem látja. Adj „Minden fájl elérése\" jogot, és az adatok átkerülnek a közös /SajatJegyzet mappába.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (!hasAccess) Button(onClick = {
            accessLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
            )
        }, modifier = Modifier.fillMaxWidth()) { Text("Hozzáférés megadása") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Szinkron (WebDAV / Nextcloud) – opcionális", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Mappa URL") },
            placeholder = { Text("https://cloud.pelda.hu/remote.php/dav/files/nev/SajatJegyzet") }, singleLine = true)
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Felhasználó") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Jelszó / app-jelszó") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && url.isNotBlank(), onClick = {
            store.davUrl = url.trim(); store.davUser = user.trim(); store.davPass = pass
            busy = true; status = "Szinkron..."
            scope.launch {
                status = try {
                    withContext(Dispatchers.IO) { WebDav.sync(store.davUrl, store.davUser, store.davPass, store) }
                } catch (e: Exception) { "Hiba: ${e.message}" }
                busy = false; refresh()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Szinkronizálás most") }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Automatikus szinkron óránként (hálózattal)")
            Switch(checked = auto, onCheckedChange = {
                auto = it; store.autoSync = it
                store.davUrl = url.trim(); store.davUser = user.trim(); store.davPass = pass
                SyncWorker.schedule(ctx, it)
            })
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Hang → szöveg (saját szerver)", style = MaterialTheme.typography.titleMedium)
        Text("OpenAI-kompatibilis STT, pl. faster-whisper-server vagy whisper.cpp server.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(asrUrl, { asrUrl = it; store.asrUrl = it.trim() }, Modifier.fillMaxWidth(), label = { Text("URL") },
            placeholder = { Text("http://192.168.1.10:8000/v1/audio/transcriptions") }, singleLine = true)
        OutlinedTextField(asrModel, { asrModel = it; store.asrModel = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Modell") }, singleLine = true)
        OutlinedTextField(asrLang, { asrLang = it; store.asrLang = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Nyelv (pl. hu, üres = auto)") }, singleLine = true)
        OutlinedTextField(asrKey, { asrKey = it; store.asrKey = it.trim() }, Modifier.fillMaxWidth(), label = { Text("API kulcs (ha kell)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Import", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }, Modifier.fillMaxWidth()) {
            Text("Jegyzetek importálása (.txt, .md, Keep .json)")
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Biztonsági mentés", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { exporter.launch("SajatJegyzet-mentes.zip") }, Modifier.fillMaxWidth()) {
            Text("Exportálás ZIP-be (jegyzetek + felvételek)")
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

package app.cloudies.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import app.cloudies.android.storage.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

private val Navy = Color(0xFF101D35)
private val Teal = Color(0xFF72E4CD)

class MainActivity : ComponentActivity() {
    private val viewModel: CloudViewModel by viewModels()
    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readShared(intent)
        setContent { CloudiesScreen(viewModel, sharedUris, { sharedUris = emptyList() }, ::openFile) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readShared(intent) }
    @Suppress("DEPRECATION")
    private fun readShared(intent: Intent) {
        sharedUris = when(intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }.filter { it.scheme == "content" }
    }
    private fun openFile(account: String, file: CloudFile, share: Boolean) {
        val uri = DocumentsContract.buildDocumentUri(CloudDocumentsProvider.AUTHORITY, CloudDocumentsProvider.documentId(account, file.id))
        try {
            val action = if (share) Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, uri)
            else if (file.mime.startsWith("video/") || file.mime.startsWith("audio/") || file.name.substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "webm", "mp3", "m4a", "wav", "ogg"))
                Intent(this, PlayerActivity::class.java).setData(uri).putExtra("name", file.name)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
            action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            action.clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            startActivity(if (share) Intent.createChooser(action, "Share ${file.name}") else action)
        } catch (_: Exception) { viewModel.message("No compatible app was found. Save the file offline or install a viewer for this file type.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CloudiesScreen(vm: CloudViewModel, sharedUris: List<Uri>, clearShared: () -> Unit, open: (String, CloudFile, Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    val state by vm.state.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences = remember { context.getSharedPreferences("settings", 0) }
    var dark by remember { mutableStateOf(preferences.getBoolean("dark", false)) }
    var followSystem by remember { mutableStateOf(preferences.getBoolean("systemTheme", true)) }
    val isDark = if (followSystem) isSystemInDarkTheme() else dark
    val scheme = if (isDark) darkColorScheme(primary = Teal, onPrimary = Navy, secondary = Teal, background = Color(0xFF0B1424), surface = Navy, surfaceContainer = Color(0xFF192740))
        else lightColorScheme(primary = Color(0xFF087F72), secondary = Color(0xFF087F72), background = Color(0xFFF4F7FB), surface = Color.White, surfaceContainer = Color(0xFFEAF0F7))
    var page by remember { mutableIntStateOf(0) }
    var connect by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var actionFile by remember { mutableStateOf<CloudFile?>(null) }
    var editName by remember { mutableStateOf<CloudFile?>(null) }
    var createFolder by remember { mutableStateOf(false) }
    var deleteFile by remember { mutableStateOf<CloudFile?>(null) }
    var disconnect by remember { mutableStateOf<Connection?>(null) }
    var wifi by remember { mutableStateOf(preferences.getBoolean("wifi", true)) }
    val snackbar = remember { SnackbarHostState() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { vm.upload(uris); page = 2 }
    }
    val googleResult = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(result.data)
            vm.connectDrive { connect = false; page = 1 }
        } catch (_: Exception) { vm.message("Google authorization did not finish. If you see error 10, register this APK's signing SHA-1 in your Google Cloud project; instructions are in the repository.") }
    }
    fun google() {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveClient.SCOPE))).build()
        Identity.getAuthorizationClient(activity).authorize(request).addOnSuccessListener { result ->
            if (result.hasResolution()) result.pendingIntent?.let { googleResult.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            else vm.connectDrive { connect = false; page = 1 }
        }.addOnFailureListener { vm.message("Google Drive needs one-time developer setup for this personal APK. Follow docs/GOOGLE_DRIVE.md in the repository. WebDAV accounts can connect immediately.") }
    }
    fun upload() {
        if(state.active == null) { connect = true; return }
        if(Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        picker.launch(arrayOf("*/*"))
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.message(null) } }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = scheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = scheme.surface) {
                    listOf("Home" to Icons.Outlined.Cloud, "Files" to Icons.Outlined.Folder, "Transfers" to Icons.Outlined.SwapVert, "Settings" to Icons.Outlined.Settings).forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(selected = page == index, onClick = { page = index; if(index == 1) vm.refresh() }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            },
            floatingActionButton = { if(page < 2) FloatingActionButton(onClick = { upload() }, containerColor = if(isDark) Teal else Navy, contentColor = if(isDark) Navy else Teal) { Icon(Icons.Outlined.Add, "Upload files") } }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Navy, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Cloud, "Cloudies", tint = Teal) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("cloudies", fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp); Text("Your space, everywhere", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
                    IconButton(onClick = { connect = true }) { Icon(Icons.Outlined.AddCircleOutline, "Connect cloud account") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when(page) {
                    0 -> LazyColumn(contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy, Color(0xFF204B5C))), RoundedCornerShape(28.dp)).padding(26.dp)) {
                                Surface(color = Teal.copy(alpha = 0.15f), shape = CircleShape) { Text("PERSONAL CLOUD", color = Teal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, modifier = Modifier.padding(12.dp, 7.dp)) }
                                Spacer(Modifier.height(20.dp))
                                Text("Less on your phone.\nMore room for you.", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("Keep files in your cloud. Open them when you need them.", color = Color(0xFFC7D9E3), style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(22.dp))
                                Button(onClick = { if(state.connections.isEmpty()) connect = true else page = 1 }, colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Navy)) {
                                    Text(if(state.connections.isEmpty()) "Connect your first cloud" else "Explore your files"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MetricCard("Cloud accounts", state.connections.size.toString(), Icons.Outlined.CloudDone, Modifier.weight(1f))
                                MetricCard("Kept offline", humanBytes(state.offline.sumOf { it.second.size }), Icons.Outlined.OfflinePin, Modifier.weight(1f))
                            }
                        }
                        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Your clouds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = { connect = true }) { Text("Connect") } } }
                        if(state.connections.isEmpty()) item { EmptyState(Icons.Outlined.AddToDrive, "A little setup, a lot of space", "Connect Koofr, Nextcloud, pCloud, or another HTTPS WebDAV service. Your own storage account stays in your control.") }
                        items(state.connections, key = { it.id }) { account ->
                            Card(onClick = { vm.select(account); page = 1 }, colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if(account.kind == "drive") Icons.Outlined.AddToDrive else Icons.Outlined.CloudQueue, null, tint = scheme.primary, modifier = Modifier.size(30.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) { Text(account.name, fontWeight = FontWeight.SemiBold); Text(if(account.kind == "drive") "Cloudies folder · Google Drive" else "WebDAV · encrypted connection", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                                    Icon(Icons.Outlined.ChevronRight, null)
                                }
                            }
                        }
                        item { Card(onClick = { vm.showOffline(); page = 1 }) { ListItem(headlineContent = { Text("Available offline") }, supportingContent = { Text("Files you chose to keep on this phone") }, leadingContent = { Icon(Icons.Outlined.OfflinePin, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }) } }
                        item { Text("No ads. No Cloudies subscription. Your storage provider's free limits still apply.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                    }
                    1 -> {
                        if(!state.showOffline && state.connections.size > 1) FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.connections.forEach { account -> FilterChip(selected = account.id == state.active?.id, onClick = { query = ""; vm.select(account) }, label = { Text(account.name) }) }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if(state.showOffline || state.path.isNotEmpty()) IconButton(onClick = { if(state.showOffline) { vm.root() } else vm.up(); query = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Parent folder") }
                            Text(if(state.showOffline) "Available offline" else state.path.lastOrNull()?.name ?: state.active?.name ?: "Your files", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if(!state.showOffline && state.active != null) {
                                IconButton(onClick = { createFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "New folder") }
                                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh files") }
                            }
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search this folder") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(20.dp, 10.dp), shape = RoundedCornerShape(18.dp), singleLine = true)
                        val rows = if(state.showOffline) state.offline.filter { it.second.name.contains(query, true) } else state.files.filter { it.name.contains(query, true) }.map { state.active?.id.orEmpty() to it }
                        if(rows.isEmpty() && !state.busy) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(if(state.showOffline) Icons.Outlined.OfflinePin else Icons.Outlined.FolderOpen, if(state.showOffline) "Ready when you are" else if(query.isNotEmpty()) "No matching files" else "A fresh space", if(state.showOffline) "Use Keep offline on a file to open it without internet." else if(state.active == null) "Connect a cloud account to start." else "Upload a file or create a folder here.") }
                        } else LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(rows, key = { "${it.first}:${it.second.id}" }) { (account, file) ->
                                FileRow(file, state.offline.any { it.first == account && it.second.id == file.id }, onClick = { if(file.directory) { query = ""; vm.enter(file) } else open(account, file, false) }, onMore = {
                                    if(state.showOffline) { open(account, file, true) } else actionFile = file
                                }, onRemoveOffline = if(state.showOffline) ({ vm.removeOffline(account, file) }) else null)
                            }
                        }
                    }
                    2 -> LazyColumn(contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Transfers", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = { vm.clearFinished() }) { Text("Clear finished") } } }
                        item { Text(if(wifi) "New transfers wait for Wi-Fi or another unmetered network." else "Mobile data is allowed for new transfers.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                        if(transfers.isEmpty()) item { EmptyState(Icons.Outlined.SwapVert, "Everything has its place", "Uploads and offline downloads appear here. Your original files are kept on your phone.") }
                        items(transfers.sortedByDescending { it.state == WorkInfo.State.RUNNING }, key = { it.id }) { work -> TransferCard(work, { vm.cancel(work) }, { vm.retry(work) }) }
                        item { Text("Offline downloads can continue from a partial file when you retry. Uploads in this first version restart when retried.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 35.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { Text("Make it yours", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                        item { Text("CONNECTIONS", style = MaterialTheme.typography.labelMedium, color = scheme.primary) }
                        items(state.connections, key = { it.id }) { account -> Card { ListItem(headlineContent = { Text(account.name) }, supportingContent = { Text(if(account.kind == "drive") "Cloudies folder only" else account.endpoint, maxLines = 2, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Outlined.Cloud, null) }, trailingContent = { IconButton(onClick = { disconnect = account }) { Icon(Icons.Outlined.LinkOff, "Disconnect account") } }) } }
                        item { OutlinedButton(onClick = { connect = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("Connect a cloud account") } }
                        item { Card { Column { SwitchRow("Wi-Fi only", "Applies to new uploads and offline downloads", wifi) { wifi = it; preferences.edit().putBoolean("wifi", it).apply() }; SwitchRow("Use device theme", "Follow your Samsung light or dark setting", followSystem) { followSystem = it; preferences.edit().putBoolean("systemTheme", it).apply() }; if(!followSystem) SwitchRow("Dark appearance", "A quieter view for the evening", dark) { dark = it; preferences.edit().putBoolean("dark", it).apply() } } } }
                        item { Card { ListItem(headlineContent = { Text("Streaming buffer") }, supportingContent = { Text("Up to 16 MB in RAM. Streamed content is not saved to disk by Cloudies.") }, trailingContent = { TextButton(onClick = { vm.clearBuffer() }) { Text("Clear") } }) } }
                        item { Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Your privacy", fontWeight = FontWeight.Bold); Text("Cloud connections use HTTPS. Saved account credentials are encrypted with Android Keystore. Your provider can still access ordinary uploaded files; file end-to-end encryption is planned and is not enabled in this version.", style = MaterialTheme.typography.bodySmall); Text("Cloudies does not automatically delete originals. Other apps may save their own local copies when you open or share a cloud file.", style = MaterialTheme.typography.bodySmall) } } }
                        item { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/akggautamasar/Cloudies#readme"))) }) { Text("Setup, free storage options & source code ↗") } }
                        item { Text("Cloudies 0.1.0 · Personal edition\nMade for your own cloud accounts.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if(connect) ConnectionDialog(state.busy, { connect = false }, { name, url, user, pass -> vm.connectWebDav(name, url, user, pass) { connect = false; page = 1 } }, { google() })
        if(createFolder) NameDialog("New folder", "", { createFolder = false }) { name -> vm.folder(name); createFolder = false }
        editName?.let { file -> NameDialog("Rename file", file.name, { editName = null }) { name -> vm.rename(file, name); editName = null } }
        actionFile?.let { file ->
            ModalBottomSheet(onDismissRequest = { actionFile = null }) {
                Text(file.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if(!file.directory) {
                    ListItem(headlineContent = { Text("Open") }, leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, modifier = Modifier.clickable { state.active?.let { open(it.id, file, false) }; actionFile = null })
                    ListItem(headlineContent = { Text("Keep offline") }, supportingContent = { Text("Uses ${humanBytes(file.size)} of phone storage") }, leadingContent = { Icon(Icons.Outlined.OfflinePin, null) }, modifier = Modifier.clickable { state.active?.let { vm.keepOffline(it.id, file) }; actionFile = null; page = 2 })
                    ListItem(headlineContent = { Text("Share file") }, leadingContent = { Icon(Icons.Outlined.Share, null) }, modifier = Modifier.clickable { state.active?.let { open(it.id, file, true) }; actionFile = null })
                }
                ListItem(headlineContent = { Text("Rename") }, leadingContent = { Icon(Icons.Outlined.Edit, null) }, modifier = Modifier.clickable { editName = file; actionFile = null })
                ListItem(headlineContent = { Text("Delete from cloud", color = scheme.error) }, leadingContent = { Icon(Icons.Outlined.DeleteOutline, null, tint = scheme.error) }, modifier = Modifier.clickable { deleteFile = file; actionFile = null })
                Spacer(Modifier.height(28.dp))
            }
        }
        deleteFile?.let { file -> AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("Delete ${file.name}?") }, text = { Text(if(state.active?.kind == "drive") "This moves the cloud file to Google Drive's trash and removes its Cloudies offline copy." else "This deletes the cloud file${if(file.directory) " and everything in this folder" else ""} and its Cloudies offline copy. Recovery depends on your provider. This action may be permanent.") }, confirmButton = { TextButton(onClick = { vm.delete(file); deleteFile = null }) { Text("Delete", color = scheme.error) } }, dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("Cancel") } }) }
        disconnect?.let { account -> AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("Disconnect ${account.name}?") }, text = { Text("Removes this account's saved credentials, file list, and offline copies from Cloudies. Files stored in the cloud are kept.") }, confirmButton = { TextButton(onClick = { vm.disconnect(account); disconnect = null }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Cancel") } }) }
        if(sharedUris.isNotEmpty()) AlertDialog(onDismissRequest = clearShared, title = { Text("Upload shared files?") }, text = { Text(if(state.active == null) "Connect a cloud account first, then share these files again." else "Upload ${sharedUris.size} file(s) to ${state.active?.name} / ${state.path.lastOrNull()?.name ?: "root"}? Shared-file access may expire; use the Upload button for transfers you need to retry later.") }, confirmButton = { TextButton(onClick = { if(state.active != null) { vm.upload(sharedUris); page = 2 } else connect = true; clearShared() }) { Text(if(state.active == null) "Connect" else "Upload") } }, dismissButton = { TextButton(onClick = clearShared) { Text("Cancel") } })
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(18.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(14.dp)); Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable
private fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable
private fun FileRow(file: CloudFile, offline: Boolean, onClick: () -> Unit, onMore: () -> Unit, onRemoveOffline: (() -> Unit)?) {
    val icon = when { file.directory -> Icons.Outlined.Folder; file.mime.startsWith("video/") -> Icons.Outlined.Videocam; file.mime.startsWith("audio/") -> Icons.Outlined.Headphones; file.mime.startsWith("image/") -> Icons.Outlined.Image; else -> Icons.Outlined.Description }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        ListItem(headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) }, supportingContent = { Text((if(file.directory) "Folder" else humanBytes(file.size)) + if(offline) " · Offline" else "", style = MaterialTheme.typography.bodySmall) }, leadingContent = { Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) } }, trailingContent = { Row { if(onRemoveOffline != null) IconButton(onClick = onRemoveOffline) { Icon(Icons.Outlined.RemoveCircleOutline, "Remove offline copy") }; IconButton(onClick = onMore) { Icon(if(onRemoveOffline == null) Icons.Outlined.MoreVert else Icons.Outlined.Share, if(onRemoveOffline == null) "File actions" else "Share file") } } }, modifier = Modifier.clickable(onClick = onClick))
    }
}
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) }, trailingContent = { Switch(checked = checked, onCheckedChange = change) }) }
@Composable
private fun TransferCard(work: WorkInfo, cancel: () -> Unit, retry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(work.progress.getString("name") ?: work.outputData.getString("name") ?: work.tags.firstOrNull { it.startsWith("name:") }?.removePrefix("name:") ?: "Cloud transfer", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(when(work.state) { WorkInfo.State.ENQUEUED -> "Queued · waiting for network or a transfer slot"; WorkInfo.State.RUNNING -> "Transferring · ${work.progress.getInt("percent", 0)}%"; WorkInfo.State.SUCCEEDED -> "Completed"; WorkInfo.State.FAILED -> work.outputData.getString("error") ?: "Transfer failed"; WorkInfo.State.CANCELLED -> "Cancelled"; else -> "Waiting" }, style = MaterialTheme.typography.bodySmall, color = if(work.state == WorkInfo.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(work.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { work.progress.getInt("percent", 0) / 100f }, modifier = Modifier.fillMaxWidth())
        if(!work.state.isFinished) TextButton(onClick = cancel) { Text("Cancel") }
        if(work.state == WorkInfo.State.FAILED) TextButton(onClick = retry) { Text("Retry") }
    } }
}
@Composable
private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = { TextButton(enabled = runCatching { safeFileName(name) }.isSuccess, onClick = { submit(name) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionDialog(busy: Boolean, dismiss: () -> Unit, connect: (String, String, String, String) -> Unit, google: () -> Unit) {
    var name by remember { mutableStateOf("Koofr") }
    var endpoint by remember { mutableStateOf("https://app.koofr.net/dav/Koofr/") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if(!busy) dismiss() }, title = { Text("Connect your cloud") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Use your own account. WebDAV connects directly; no Cloudies server is involved.", style = MaterialTheme.typography.bodySmall) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Koofr", "Nextcloud", "pCloud", "Other").forEach { provider -> FilterChip(selected = name == provider, onClick = {
                    name = provider; endpoint = when(provider) { "Koofr" -> "https://app.koofr.net/dav/Koofr/"; "pCloud" -> "https://webdav.pcloud.com/"; else -> "" }
                }, label = { Text(provider) }) }
            } }
            item { OutlinedTextField(name, { name = it }, label = { Text("Account label") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS WebDAV address") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(username, { username = it }, label = { Text("Username / email") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(password, { password = it }, label = { Text("App password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Text("Koofr: generate an app password in account settings. Nextcloud: copy the WebDAV URL from Files settings. pCloud EU accounts use https://ewebdav.pcloud.com/.", style = MaterialTheme.typography.bodySmall) }
            item { HorizontalDivider(); TextButton(enabled = !busy, onClick = google) { Icon(Icons.Outlined.AddToDrive, null); Spacer(Modifier.width(8.dp)); Text("Google Drive · setup required") } }
            item { Text("Google Drive requires a free Google Cloud project with this app's package name and signing SHA-1. See the setup guide in the repository.", style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { Button(enabled = !busy && username.isNotBlank() && password.isNotBlank() && endpoint.startsWith("https://"), onClick = { connect(name, endpoint, username, password) }) { Text(if(busy) "Connecting…" else "Connect WebDAV") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancel") } })
}

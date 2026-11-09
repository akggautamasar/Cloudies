package app.cloudies.android.myfiles

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MyFilesSetupCard(open: () -> Unit) {
    val status by MyFilesBridge.status.collectAsStateWithLifecycle()
    Card(onClick = open) {
        ListItem(
            headlineContent = { Text("Samsung My Files", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(if (status.running) "Connection running · browse your clouds" else "Add Cloudies through Network storage") },
            leadingContent = { Icon(Icons.Outlined.FolderShared, null) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }
        )
    }
}

@Composable
fun MyFilesSetupDialog(hasAccounts: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val status by MyFilesBridge.status.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf<MyFilesLogin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification permission is optional; Android still requires a foreground service.
        MyFilesBridge.start(context)
    }
    LaunchedEffect(Unit) {
        try { login = withContext(Dispatchers.IO) { MyFilesBridgeStore(context).login() } }
        catch (_: Exception) { error = "The connection password could not be loaded. Restart Cloudies and try again." }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Cloudies in My Files") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save a Cloudies connection in Samsung My Files → Network storage. Samsung controls the built-in cloud tiles; adding this connection once is required.")
                Text("1. Start the connection", fontWeight = FontWeight.SemiBold)
                if (!hasAccounts) Text("Connect a cloud account in Cloudies first.", color = MaterialTheme.colorScheme.error)
                if (status.running) {
                    Text("Running on this phone", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { MyFilesBridge.stop(context) }) { Text("Stop connection") }
                } else {
                    Button(enabled = hasAccounts && login != null && !status.starting, onClick = {
                        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else MyFilesBridge.start(context)
                    }) { Text(if (status.starting) "Starting…" else "Start connection") }
                }
                status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("2. Add it in Samsung My Files", fontWeight = FontWeight.SemiBold)
                Text("Open My Files → Network storage → + → FTP server. Enter these details; leave anonymous sign-in and encryption/SSL turned off.")
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SetupValue("Server address", "127.0.0.1")
                        SetupValue("Port", MyFilesBridge.PORT.toString())
                        SetupValue("Username", login?.username ?: "cloudies")
                        SetupValue("Display name", "Cloudies")
                        if (showPassword) login?.let { SetupValue("Password", it.password) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = login != null, onClick = {
                        val clip = ClipData.newPlainText("Cloudies My Files password", login!!.password)
                        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        copied = true
                    }) { Text(if (copied) "Password copied" else "Copy password") }
                    TextButton(enabled = login != null, onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show password") }
                }
                OutlinedButton(onClick = {
                    try {
                        val launch = context.packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles")
                        if (launch == null) error = "Samsung My Files is not installed. Use Android's file picker or Share → Cloudies."
                        else context.startActivity(launch)
                    } catch (_: Exception) { error = "Could not open My Files. Open it from your phone's app list." }
                }, modifier = Modifier.fillMaxWidth()) { Text("Open Samsung My Files") }
                Text("3. Save as Cloudies", fontWeight = FontWeight.SemiBold)
                Text("Your accounts appear as folders inside the saved connection. If My Files offers a favourite or main-screen shortcut for a folder, add it there. Pinning options depend on your My Files version.")
                Text("Browse, open and copy files from your clouds. This connection is read-only; upload with My Files → Share → Cloudies. My Files may download a local copy when opening a file.", style = MaterialTheme.typography.bodySmall)
                Text("Keep the connection running while using it. It stops after 15 minutes without activity, when you stop it, or when Android ends the service. Start it here again to reconnect. Cloud reads use your internet connection and may use mobile data; the transfer queue's Wi-Fi-only setting does not apply.", style = MaterialTheme.typography.bodySmall)
                Text("The connection is restricted to this phone and requires the generated password. No server is exposed to your Wi-Fi network. Internet transfers to your providers still use HTTPS.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
}

@Composable
private fun SetupValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

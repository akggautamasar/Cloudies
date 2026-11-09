package app.cloudies.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.data ?: run { finish(); return }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var error by remember { mutableStateOf<String?>(null) }
                val exo = remember { ExoPlayer.Builder(this).build().also {
                    player = it
                    it.addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error = "Cannot stream this file. Check your connection or save it offline and open it in another player." } })
                    it.setMediaItem(MediaItem.fromUri(uri)); it.prepare(); it.playWhenReady = true
                } }
                DisposableEffect(exo) { onDispose { exo.release(); player = null } }
                Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    TextButton(onClick = { finish() }) { Text("‹ Back to Cloudies", color = Color(0xFF72E4CD)) }
                    AndroidView(factory = { context -> PlayerView(context).apply { player = exo; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } }, modifier = Modifier.weight(1f).fillMaxWidth())
                    Text(intent.getStringExtra("name") ?: "Cloudies player", color = Color.White, modifier = Modifier.padding(20.dp))
                    error?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(20.dp)) }
                }
            }
        }
    }
    override fun onStop() { player?.pause(); super.onStop() }
}

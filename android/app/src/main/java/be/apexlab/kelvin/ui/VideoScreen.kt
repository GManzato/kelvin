package be.apexlab.kelvin.ui

import android.content.Intent
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import be.apexlab.kelvin.Capture
import be.apexlab.kelvin.Captures
import be.apexlab.kelvin.PH
import be.apexlab.kelvin.PW
import be.apexlab.kelvin.UiState
import be.apexlab.kelvin.durationText
import be.apexlab.kelvin.Units
import java.util.Date
import java.util.Locale

/** A recorded thermal video: playback (tap to pause), details, share and delete. */
@Composable
fun VideoViewer(c: Capture, ui: UiState, captures: Captures, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var playing by remember(c.id) { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    Box(Modifier.fillMaxSize().background(C.Background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).tap { ui.back() }, contentAlignment = Alignment.Center) { Icon(Ic.Back, C.Text, 22.dp) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    BasicText("Thermal video", style = text(17.sp, FontWeight.SemiBold))
                    BasicText(DATE.format(Date(c.time)), style = text(12.sp, color = C.Text2))
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(PW.toFloat() / PH).background(Color.Black).tap {
                val v = player ?: return@tap
                if (v.isPlaying) v.pause() else v.start()
                playing = v.isPlaying
            }) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoPath(c.videoFile.path)
                            setOnPreparedListener { mp -> mp.isLooping = true; start() }
                            player = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.stopPlayback() },
                )
                if (!playing) Box(Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color(0xB30B0B0F)), contentAlignment = Alignment.Center) {
                    Icon(Ic.Play, C.Text, 28.dp)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Detail("Duration", durationText(c.durationMs), Modifier.weight(1f))
                    Detail("Max", Units.tu(c.max), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Detail("Emissivity", "%.2f · %s".format(Locale.US, c.emissivity, c.emissivityLabel), Modifier.weight(1f))
                    Detail("Min", Units.tu(c.min), Modifier.weight(1f))
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(Color(0x0FFFFFFF)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Action(Ic.Share, "Share video", C.Text, Modifier.weight(1f)) {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", c.videoFile)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share thermal video"))
                }
                Action(Ic.Trash, "Delete", C.Hot, Modifier.weight(1f)) { confirmDelete = true }
            }
        }
        DeleteDialogs(c, captures, ui, onChanged, "The video will be removed from this phone.", confirmDelete) { confirmDelete = false }
    }
}

package be.apexlab.kelvin.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import be.apexlab.kelvin.CameraController
import be.apexlab.kelvin.Conn
import be.apexlab.kelvin.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CompatibilityScreen(ui: UiState, cam: CameraController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeCameraSerial by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connectionLabel(): String = when (val state = cam.conn) {
        Conn.NoCamera -> "No camera"
        is Conn.Connecting -> "Connecting (step ${state.step})"
        Conn.Live -> if (cam.paused) "Paused" else "Live"
        is Conn.Reconnecting -> "Reconnecting (attempt ${state.attempt})"
        is Conn.Restarting -> "Restarting"
        Conn.Denied -> "USB permission denied"
        is Conn.Unsupported -> "Unsupported camera"
    }

    fun refresh(includeSerialChoice: Boolean = includeCameraSerial) {
        if (busy) return
        val connection = connectionLabel()
        val includeSerial = includeSerialChoice
        report = null
        busy = true
        error = null
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { cam.diagnostics.snapshot(connection, includeSerial) }
                report = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(C.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).tap { ui.back() }, contentAlignment = Alignment.Center) { Icon(Ic.Back, C.Text, 22.dp) }
            BasicText("Compatibility report", style = text(20.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                "The report includes phone and Android details, FLIR USB metadata, camera identity and connection events. It never includes photos or phone identifiers. Camera serial is optional and off by default.",
                style = text(13.sp, color = C.Secondary, lineHeight = 19.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText("Include camera serial", style = text(15.sp, FontWeight.SemiBold))
                    BasicText("Only in a report you refresh with this enabled", style = text(11.sp, color = C.Secondary))
                }
                Toggle(on = includeCameraSerial, onToggle = {
                    if (!busy) {
                        val nextChoice = !includeCameraSerial
                        includeCameraSerial = nextChoice
                        report = null
                        error = null
                        refresh(nextChoice)
                    }
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(if (busy) "Working…" else "Refresh report", { refresh() }, Modifier.weight(1f), height = 50.dp)
                OutlineButton("Share", {
                    val displayed = report ?: return@OutlineButton
                    if (busy) return@OutlineButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { cam.diagnostics.export(displayed) }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, "Compatibility report", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share compatibility report"))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: failure.javaClass.simpleName
                        } finally {
                            busy = false
                        }
                    }
                }, Modifier.weight(1f), height = 50.dp)
            }
            if (busy) BasicText("Preparing report…", style = text(12.sp, color = C.Secondary))
            error?.let { message ->
                BasicText("Could not prepare report: $message", style = text(13.sp, color = C.Hot, lineHeight = 18.sp))
            }
            if (report == null) {
                BasicText("No report yet. Refresh to capture current diagnostics.", style = text(13.sp, color = C.Muted), modifier = Modifier.padding(vertical = 8.dp))
            } else {
                BasicText("REPORT PREVIEW", style = Label, modifier = Modifier.padding(top = 2.dp))
                SelectionContainer {
                    BasicText(
                        report!!,
                        style = mono(11.sp, C.Text2).copy(lineHeight = 16.sp),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Well).padding(14.dp),
                    )
                }
                BasicText("Sharing sends only this report through Android’s share sheet. Nothing is uploaded automatically.", style = text(11.sp, color = C.Secondary, lineHeight = 16.sp))
            }
        }
    }
}

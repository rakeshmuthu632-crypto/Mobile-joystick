package com.example.joystick

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---- XUSB button bitmasks (match ViGEm / Xbox 360) ----
object Btn {
    const val UP = 0x0001; const val DOWN = 0x0002
    const val LEFT = 0x0004; const val RIGHT = 0x0008
    const val START = 0x0010; const val BACK = 0x0020
    const val LB = 0x0100; const val RB = 0x0200
    const val A = 0x1000; const val B = 0x2000
    const val X = 0x4000; const val Y = 0x8000
}

// ---- Shared controller state, read by the UDP sender ----
object PadState {
    @Volatile var lx = 0; @Volatile var ly = 0
    @Volatile var rx = 0; @Volatile var ry = 0
    @Volatile var lt = 0; @Volatile var rt = 0
    private var buttons = 0
    @Synchronized fun press(m: Int) { buttons = buttons or m }
    @Synchronized fun release(m: Int) { buttons = buttons and m.inv() }
    @Synchronized fun mask() = buttons
}

// ---- UDP sender: pushes state ~60 times/second ----
class UdpSender {
    private var job: Job? = null

    fun start(scope: CoroutineScope, host: String, port: Int = 9876) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                val addr = InetAddress.getByName(host)
                val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                while (isActive) {
                    buf.clear()
                    buf.putShort(PadState.lx.toShort())
                    buf.putShort(PadState.ly.toShort())
                    buf.putShort(PadState.rx.toShort())
                    buf.putShort(PadState.ry.toShort())
                    buf.put(PadState.lt.toByte())
                    buf.put(PadState.rt.toByte())
                    buf.putShort(PadState.mask().toShort())
                    socket.send(DatagramPacket(buf.array(), 12, addr, port))
                    delay(16)
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { GamepadScreen() } }
    }
}

@Composable
fun GamepadScreen() {
    val scope = rememberCoroutineScope()
    val sender = remember { UdpSender() }
    var ip by remember { mutableStateOf("192.168.1.10") }
    var connected by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { sender.stop() } }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(12.dp)) {
        // Connection bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ip, onValueChange = { ip = it },
                label = { Text("PC IP address") }, singleLine = true,
                modifier = Modifier.width(240.dp)
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                if (connected) sender.stop() else sender.start(scope, ip.trim())
                connected = !connected
            }) { Text(if (connected) "Disconnect" else "Connect") }
            Spacer(Modifier.weight(1f))
            PadButton("BACK", 56, 36) { if (it) PadState.press(Btn.BACK) else PadState.release(Btn.BACK) }
            Spacer(Modifier.width(8.dp))
            PadButton("START", 56, 36) { if (it) PadState.press(Btn.START) else PadState.release(Btn.START) }
        }

        // Shoulders and triggers
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("LT", 72, 40) { PadState.lt = if (it) 255 else 0 }
                PadButton("LB", 72, 40) { if (it) PadState.press(Btn.LB) else PadState.release(Btn.LB) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("RB", 72, 40) { if (it) PadState.press(Btn.RB) else PadState.release(Btn.RB) }
                PadButton("RT", 72, 40) { PadState.rt = if (it) 255 else 0 }
            }
        }

        Spacer(Modifier.weight(1f))

        // Sticks and face buttons
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Joystick(150) { x, y -> PadState.lx = x; PadState.ly = y }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                Joystick(150) { x, y -> PadState.rx = x; PadState.ry = y }
                FaceButtons()
            }
        }
    }
}

@Composable
fun FaceButtons() {
    Box(Modifier.size(170.dp)) {
        PadButton("Y", 56, 56, round = true, modifier = Modifier.align(Alignment.TopCenter)) { if (it) PadState.press(Btn.Y) else PadState.release(Btn.Y) }
        PadButton("A", 56, 56, round = true, modifier = Modifier.align(Alignment.BottomCenter)) { if (it) PadState.press(Btn.A) else PadState.release(Btn.A) }
        PadButton("X", 56, 56, round = true, modifier = Modifier.align(Alignment.CenterStart)) { if (it) PadState.press(Btn.X) else PadState.release(Btn.X) }
        PadButton("B", 56, 56, round = true, modifier = Modifier.align(Alignment.CenterEnd)) { if (it) PadState.press(Btn.B) else PadState.release(Btn.B) }
    }
}

@Composable
fun PadButton(
    label: String, w: Int, h: Int,
    round: Boolean = false,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(w.dp, h.dp)
            .background(
                if (down) Color(0xFF4CAF50) else Color(0x44FFFFFF),
                if (round) CircleShape else RoundedCornerShape(10.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true; onChange(true)
                    tryAwaitRelease()
                    down = false; onChange(false)
                })
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White) }
}

/** Analog stick. Reports x/y in -32767..32767, with up = positive y (XInput convention). */
@Composable
fun Joystick(diameter: Int, onMove: (Int, Int) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val knobSize = (diameter / 2.5f).dp

    Box(
        Modifier
            .size(diameter.dp)
            .background(Color(0x22FFFFFF), CircleShape)
            .pointerInput(Unit) {
                val radius = min(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                fun update(pos: Offset) {
                    var v = pos - center
                    val len = sqrt(v.x * v.x + v.y * v.y)
                    if (len > radius) v = v * (radius / len)
                    knob = v
                    onMove((v.x / radius * 32767).toInt(), (-v.y / radius * 32767).toInt())
                }
                fun reset() { knob = Offset.Zero; onMove(0, 0) }
                detectDragGestures(
                    onDragStart = { update(it) },
                    onDragEnd = { reset() },
                    onDragCancel = { reset() }
                ) { change, _ -> change.consume(); update(change.position) }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(knobSize)
                .background(Color(0xAA2196F3), CircleShape)
        )
    }
}

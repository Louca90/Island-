package com.louca.island

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.louca.island.ui.SendArrow
import com.louca.island.ui.islandColors
import com.louca.island.ui.wallColor
import kotlinx.coroutines.delay

/** Small transparent window shaped like the expanded island, used only to type a reply. */
class ReplyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val target = IslandStore.replyTarget
        if (target == null) { finish(); return }
        setContent { ReplySheet(target) { finish() } }
    }

    override fun finish() {
        IslandStore.replying.value = null
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun ReplySheet(a: Act, close: () -> Unit) {
    val ctx = LocalContext.current
    val s = Prefs.state.value
    val c = remember { islandColors(s, wallColor(ctx)) }
    var text by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LaunchedEffect(Unit) { shown = true; delay(120); runCatching { fr.requestFocus() }; kb?.show() }

    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        a.actions.firstOrNull { it.isReply }?.fire(t)
        kb?.hide()
        IslandStore.acts.value.find { it.kind == Kind.NOTIF && it.key == a.key }?.let { IslandStore.remove(it.id) }
        IslandStore.upsert(Act(id = "sys", kind = Kind.SYSTEM, title = "Message envoyé", variant = 6, stamp = System.currentTimeMillis()), ttl = 1800)
        close()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f))
            .pointerInput(Unit) { detectTapGestures { close() } }
    ) {
        val w = minOf(s.expW.dp, maxWidth - 16.dp)
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(160)) + expandVertically(tween(300, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(220), shrinkTowards = Alignment.Top),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = (top - 18.dp).coerceAtLeast(4.dp)),
        ) {
            Column(
                Modifier.width(w).clip(RoundedCornerShape(34.dp)).background(c.bg)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 16.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = a.icon ?: a.appIcon
                    if (icon != null) Image(icon.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp).clip(CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${a.appName} · ${a.title}", color = c.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.text, color = c.fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = TextStyle(color = c.fg, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.fg),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f).focusRequester(fr),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.btn)
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (text.isEmpty()) Text("Message…", color = c.dim, fontSize = 15.sp)
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    val ready = text.isNotBlank()
                    val bg by animateColorAsState(if (ready) c.fg else c.btn, tween(200), label = "send")
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(bg)
                            .semantics { contentDescription = "Envoyer"; role = Role.Button }
                            .pointerInput(Unit) { detectTapGestures { send() } },
                        contentAlignment = Alignment.Center,
                    ) { SendArrow(if (ready) c.bg else c.dim, 18.dp) }
                }
            }
        }
    }
}

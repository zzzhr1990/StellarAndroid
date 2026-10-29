package cn.coldlake.stellar.opensource

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.presentation.DiscNavigationUi
import com.stellar.player.disc.DiscMenuKey

@Composable
internal fun BoxScope.DiscMenuOverlay(state: DiscNavigationUi, visible: Boolean, onOpen: () -> Unit,
    onExit: () -> Unit, onBack: () -> Unit, onFullscreen: () -> Unit,
    input: (DiscMenuKey, Int, Int) -> Unit, fullscreen: Boolean, playing: Boolean, onPlayback: () -> Unit) {
    if (!state.available) return
    if (!state.active) {
        if (visible) Column(Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0xAA000000))) {
            TextButton(onClick=onOpen, enabled=!state.loading, modifier=Modifier.testTag("disc-menu-open")) {
                Text(stringResource(if (state.loading) R.string.disc_menu_loading else R.string.disc_menu),color=Color.White)
            }
            if(state.failed) Text(stringResource(R.string.disc_menu_unavailable),color=Color.White,modifier=Modifier.widthIn(max=260.dp).padding(8.dp))
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val stateNow by rememberUpdatedState(state)
    val label = stringResource(R.string.disc_menu)
    var controls by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).onKeyEvent { event ->
        val key = when (event.key) {
            Key.DirectionUp -> DiscMenuKey.UP; Key.DirectionDown -> DiscMenuKey.DOWN
            Key.DirectionLeft -> DiscMenuKey.LEFT; Key.DirectionRight -> DiscMenuKey.RIGHT
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> DiscMenuKey.ACTIVATE
            Key.Escape -> DiscMenuKey.RETURN; else -> null
        }
        if (key != null) { if(event.type==KeyEventType.KeyDown) input(key,0,0);true } else false
    }.focusable()) {
        val menu=state.menu
        val ratio=menu.aspect
        val width=if(maxWidth/maxHeight>ratio) maxHeight*ratio else maxWidth
        val height=width/ratio
        Canvas(Modifier.size(width,height).align(Alignment.Center).testTag("disc-menu-image")
            .semantics { contentDescription=label }
            .pointerInput(Unit) { detectTapGestures { p ->
                val current=stateNow.menu
                if(current.width>0&&current.height>0) input(DiscMenuKey.TOUCH,
                    (p.x/size.width*current.width).toInt(),(p.y/size.height*current.height).toInt())
            } }) {
            menu.image?.let { drawImage(it.asImageBitmap(),dstOffset=IntOffset.Zero,dstSize=IntSize(size.width.toInt(),size.height.toInt())) }
        }
        if(fullscreen) {
            TextButton(onClick={controls=!controls},modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().background(Color(0x88000000))) {
                Text(stringResource(R.string.disc_menu),color=Color.White)
            }
            if(controls) DiscMenuControls(onExit,onBack,onFullscreen,input,playing,onPlayback,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().background(Color(0xCC000000)))
        }
        if(state.failed) Text(stringResource(if(menu.error==2) R.string.disc_menu_unavailable else R.string.disc_menu_failed),color=Color.White,
            modifier=Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(16.dp))
    }
}

@Composable
internal fun DiscMenuControls(onExit: () -> Unit, onBack: () -> Unit, onFullscreen: () -> Unit,
    input: (DiscMenuKey,Int,Int) -> Unit, playing: Boolean, onPlayback: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=onBack) { Text(stringResource(R.string.disc_menu_close),color=Color.White) }
            TextButton(onClick=onPlayback,modifier=Modifier.testTag("disc-menu-playback")) {
                Text(stringResource(if(playing) com.stellar.player.ui.R.string.pause else com.stellar.player.ui.R.string.play),color=Color.White)
            }
            TextButton(onClick={input(DiscMenuKey.TOP,0,0)},modifier=Modifier.testTag("disc-menu-top")) { Text(stringResource(R.string.disc_menu_top),color=Color.White) }
            TextButton(onClick=onExit,modifier=Modifier.testTag("disc-menu-exit")) { Text(stringResource(R.string.disc_menu_titles),color=Color.White) }
            TextButton(onClick=onFullscreen) { Text(stringResource(R.string.disc_menu_fullscreen),color=Color.White) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            val keys=listOf(DiscMenuKey.RETURN to R.string.disc_menu_return,DiscMenuKey.LEFT to R.string.disc_menu_left,
                DiscMenuKey.UP to R.string.disc_menu_up,DiscMenuKey.ACTIVATE to R.string.disc_menu_ok,
                DiscMenuKey.DOWN to R.string.disc_menu_down,DiscMenuKey.RIGHT to R.string.disc_menu_right,
                DiscMenuKey.POPUP to R.string.disc_menu_popup)
            keys.forEach { (key,text) ->
                TextButton(onClick={input(key,0,0)},contentPadding=PaddingValues(4.dp),
                    modifier=Modifier.widthIn(min=40.dp).testTag("disc-menu-${key.name.lowercase()}")) {
                    Text(stringResource(text),color=Color.White)
                }
            }
        }
    }
}

package dev.amrakshay.shutterlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.shutterlink.protocol.Role
import dev.amrakshay.shutterlink.transport.NearbyTransport
import dev.amrakshay.shutterlink.transport.Pairing
import dev.amrakshay.shutterlink.transport.Peer
import dev.amrakshay.shutterlink.transport.thisPhoneName

/**
 * Finding the other phone, confirming it, and holding the link.
 *
 * One screen for both roles: the camera advertises and waits, the controller
 * looks and picks, but the confirmation and the connected state are identical
 * and should not be written twice.
 */
@Composable
fun PairingScreen(role: Role, onBack: () -> Unit, onUseAlone: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairing = remember {
        Pairing(NearbyTransport(context), role, scope)
    }
    val state by pairing.state.collectAsState()

    LaunchedEffect(Unit) { pairing.start() }
    DisposableEffect(Unit) { onDispose { pairing.stop() } }

    // Keep a live latency reading rather than one stale number from connect time.
    LaunchedEffect(state.connected) {
        if (state.connected != null && role == Role.CONTROLLER) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                pairing.ping()
            }
        }
    }

    // Once connected, the live screens take the whole window. They are drawn
    // outside the padded Box below on purpose: a viewfinder inset by 24dp and
    // the system bars is not a viewfinder.
    if (state.connected != null && !state.incompatible && state.pending == null) {
        if (role == Role.CAMERA) {
            CameraRigScreen(pairing = pairing, onEnd = onBack)
        } else {
            RemoteViewfinderScreen(pairing = pairing, state = state, onBack = onBack)
        }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        when {
            state.incompatible -> Incompatible(onBack)
            state.pending != null -> Confirm(
                peer = state.pending!!.first,
                code = state.pending!!.second,
                onYes = pairing::confirm,
                onNo = pairing::deny,
            )

            role == Role.CAMERA -> Waiting(onBack, onUseAlone)
            else -> Looking(state.peers, pairing::connectTo, onBack)
        }

        state.reconnecting?.let {
            Text(
                it,
                color = Ink.Warning,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 22.dp),
            )
        }

        state.problem?.let {
            Text(
                it,
                color = Ink.Warning,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Waiting(onBack: () -> Unit, onUseAlone: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))
        Box(
            Modifier.size(118.dp).background(Ink.AccentWell, CircleShape)
                .border(1.dp, Ink.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).border(3.dp, Ink.Accent, CircleShape))
        }
        Spacer(Modifier.height(30.dp))
        Heading("Waiting for your other phone")
        Spacer(Modifier.height(10.dp))
        Body("Open ShutterLink on the phone you are holding and choose Controller.")
        Spacer(Modifier.height(26.dp))
        Column(
            modifier = Modifier
                .background(Ink.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("LOOK FOR", color = Ink.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(thisPhoneName(), color = Ink.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        // Pairing must not take away the camera that already worked. Someone
        // who opens this with no second phone to hand should still be able to
        // take a picture.
        onUseAlone?.let {
            Secondary("Use as a normal camera", it)
            Spacer(Modifier.height(10.dp))
        }
        Secondary("Cancel", onBack)
    }
}

@Composable
private fun Looking(peers: List<Peer>, onPick: (Peer) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Heading("Nearby phones")
        Spacer(Modifier.height(8.dp))
        Body("Phones running ShutterLink and waiting to be a camera.")
        Spacer(Modifier.height(22.dp))

        if (peers.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Still looking…", color = Ink.Faint, fontSize = 14.sp)
            }
        }

        peers.forEach { peer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .background(Ink.Surface, RoundedCornerShape(16.dp))
                    .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
                    .clickable { onPick(peer) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    peer.name,
                    color = Ink.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("Connect", color = Ink.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.weight(1f))
        Secondary("Back", onBack)
    }
}

@Composable
private fun Confirm(peer: Peer, code: String, onYes: () -> Unit, onNo: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Heading("Same number on both?")
        Spacer(Modifier.height(10.dp))
        Body("Check the other phone. If it shows a different number, something else is trying to connect.")
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            code.forEach { digit ->
                Box(
                    modifier = Modifier
                        .width(62.dp).height(84.dp)
                        .background(Ink.Surface, RoundedCornerShape(16.dp))
                        .border(1.dp, Ink.Accent, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(digit.toString(), color = Ink.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Body("Connecting to ${peer.name}")
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { Secondary("No", onNo) }
            Box(Modifier.weight(1.3f)) { Primary("Yes, connect", onYes) }
        }
    }
}


@Composable
private fun Incompatible(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Heading("These two versions cannot talk")
        Spacer(Modifier.height(10.dp))
        Body("One phone is running a build too old for the other. Update them both to the same version.")
        Spacer(Modifier.weight(1f))
        Secondary("Back", onBack)
    }
}

@Composable
private fun Heading(text: String) = Text(
    text,
    color = Ink.Text,
    fontSize = 25.sp,
    lineHeight = 30.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun Body(text: String) = Text(
    text,
    color = Ink.Muted,
    fontSize = 14.sp,
    lineHeight = 21.sp,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun Primary(text: String, onClick: () -> Unit) = Text(
    text,
    color = Ink.AccentInk,
    fontSize = 16.sp,
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.Center,
    modifier = Modifier
        .fillMaxWidth()
        .background(Ink.Accent, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 17.dp),
)

@Composable
private fun Secondary(text: String, onClick: () -> Unit) = Text(
    text,
    color = Ink.Text,
    fontSize = 15.sp,
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.Center,
    modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 16.dp),
)

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.shutterlink.protocol.Role

@Composable
fun RolePickerScreen(onPick: (Role, remember: Boolean) -> Unit) {
    var rememberChoice by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(2.dp, Ink.Text, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(Ink.Accent, CircleShape))
            }
            Spacer(Modifier.size(10.dp))
            Text("ShutterLink", color = Ink.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(40.dp))
        Text(
            "Which phone is this?",
            color = Ink.Text,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Install ShutterLink on both phones, then give each one a job. You can swap them " +
                "over at any time.",
            color = Ink.Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(32.dp))

        RoleCard(
            title = "Camera",
            blurb = "Prop this phone up where you want it. It takes the photo.",
            onClick = { onPick(Role.CAMERA, rememberChoice) },
        )
        Spacer(Modifier.height(14.dp))
        RoleCard(
            title = "Controller",
            blurb = "Keep this one in your hand. See the view, press the shutter.",
            onClick = { onPick(Role.CONTROLLER, rememberChoice) },
        )

        Spacer(Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { rememberChoice = !rememberChoice }
                .padding(vertical = 12.dp),
        ) {
            Checkbox(
                checked = rememberChoice,
                onCheckedChange = { rememberChoice = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = Ink.Accent,
                    checkmarkColor = Ink.AccentInk,
                    uncheckedColor = Ink.Muted,
                ),
            )
            Spacer(Modifier.size(6.dp))
            Text("Remember this choice on this phone", color = Ink.Muted, fontSize = 14.sp)
        }

        Text(
            "Nothing is sent to the internet. The two phones talk directly to each other.",
            color = Ink.Faint,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun RoleCard(title: String, blurb: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Surface, RoundedCornerShape(18.dp))
            .border(1.dp, Ink.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF1F262E), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(22.dp).border(2.dp, Ink.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(Ink.Accent, CircleShape))
            }
        }
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, color = Ink.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(blurb, color = Ink.Muted, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

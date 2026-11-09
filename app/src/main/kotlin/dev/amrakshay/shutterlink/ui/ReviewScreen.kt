package dev.amrakshay.shutterlink.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.shutterlink.camera.PhotoStore
import dev.amrakshay.shutterlink.transport.ArrivedPhoto

/**
 * The shot that just came over, so you can decide whether to take another.
 *
 * It is not saved to this phone's gallery unless you say so. The whole reason
 * the photo is sent across is to judge it, and auto-saving would fill the
 * gallery with the rejects as well as the keepers. The original is on the camera
 * phone regardless, so nothing is at stake in saying no.
 */
@Composable
fun ReviewScreen(
    photo: ArrivedPhoto,
    onRetake: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val photos = remember { PhotoStore(context) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var saved by remember { mutableStateOf(false) }
    var tooBig by remember { mutableStateOf(false) }

    LaunchedEffect(photo) {
        // Through the ContentResolver, never by path. Nearby leaves received
        // files in its own Download folder, which scoped storage will not open
        // by path from here.
        //
        // Downsampled on purpose: a 12MP JPEG decoded whole is around 48MB of
        // bitmap, which is a plausible way to run a mid-range phone out of heap
        // while it is also decoding video.
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(photo.uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outWidth / sample > 1600) sample *= 2
        val decoded = runCatching {
            resolver.openInputStream(photo.uri).use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.getOrNull()
        if (decoded == null) tooBig = true else bitmap = decoded.asImageBitmap()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(photo.name, color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${photo.bytes / 1024} KB · also on the camera phone",
                    color = Ink.Muted,
                    fontSize = 12.sp,
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .background(Color.Black, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "The shot that just came over",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            } ?: Text(
                if (tooBig) "That image could not be opened" else "Opening…",
                color = Ink.Muted,
                fontSize = 13.sp,
            )
        }

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Retake",
                    color = Ink.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Ink.Line, RoundedCornerShape(15.dp))
                        .clickable(onClick = onRetake)
                        .padding(vertical = 16.dp),
                )
                Text(
                    if (saved) "Saved" else "Save here",
                    color = if (saved) Ink.Positive else Ink.AccentInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (saved) Color.Transparent else Ink.Accent,
                            RoundedCornerShape(15.dp),
                        )
                        .border(
                            1.dp,
                            if (saved) Ink.Positive else Ink.Accent,
                            RoundedCornerShape(15.dp),
                        )
                        .clickable(enabled = !saved) {
                            saved = photos.publish(photo.uri, photo.name) != null
                        }
                        .padding(vertical = 16.dp),
                )
            }

            Text(
                "Back to the viewfinder",
                color = Ink.Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDone).padding(12.dp),
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

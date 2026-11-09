package dev.amrakshay.shutterlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.amrakshay.shutterlink.protocol.PROTOCOL_VERSION

/**
 * Phase 0 placeholder. The role picker (Camera / Controller) lands in Phase 1 —
 * see docs/PLAN.md. This exists so the repository builds, installs and runs from
 * the first commit, and so CI has something real to assemble.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Placeholder()
                }
            }
        }
    }
}

@Composable
private fun Placeholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ShutterLink", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Nothing here yet — the role picker arrives in Phase 1.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Protocol v$PROTOCOL_VERSION",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

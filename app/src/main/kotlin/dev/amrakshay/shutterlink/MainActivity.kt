package dev.amrakshay.shutterlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.amrakshay.shutterlink.data.RoleStore
import dev.amrakshay.shutterlink.protocol.Role
import dev.amrakshay.shutterlink.ui.CameraPermission
import dev.amrakshay.shutterlink.ui.CameraScreen
import dev.amrakshay.shutterlink.ui.Ink
import dev.amrakshay.shutterlink.ui.NotYetScreen
import dev.amrakshay.shutterlink.ui.RolePickerScreen
import dev.amrakshay.shutterlink.ui.ShutterLinkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ShutterLinkTheme {
                App()
            }
        }
    }
}

/**
 * Three screens and no navigation library.
 *
 * The app has one branch — pick a role, then do that role — so a graph would be
 * more machinery than the thing it describes. If Phase 5 brings a review stack
 * that needs real back behaviour, swap it then.
 */
@Composable
private fun App() {
    val context = LocalContext.current
    val store = remember { RoleStore(context) }
    val scope = rememberCoroutineScope()

    // null while the remembered role is still being read, so the picker does not
    // flash up for someone who already chose one.
    var role by remember { mutableStateOf<Role?>(null) }
    var decided by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        role = store.role.first()
        decided = true
    }

    if (!decided) {
        Box(Modifier.fillMaxSize().background(Ink.Ground))
        return
    }

    fun leave() {
        role = null
        // Backing out is an explicit "not this role", so stop remembering it —
        // otherwise the next launch drops straight back in and the picker
        // becomes unreachable.
        scope.launch { store.clear() }
    }

    when (role) {
        null -> RolePickerScreen { picked, remember ->
            role = picked
            if (remember) scope.launch { store.remember(picked) }
        }

        Role.CAMERA -> CameraPermission { CameraScreen(onBack = ::leave) }

        Role.CONTROLLER -> NotYetScreen(onBack = ::leave)
    }
}

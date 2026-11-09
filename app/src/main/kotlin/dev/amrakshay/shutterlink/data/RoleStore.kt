package dev.amrakshay.shutterlink.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amrakshay.shutterlink.protocol.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "shutterlink")

/**
 * The role this phone last played, so a phone that is always the camera does not
 * ask every time.
 *
 * Stored rather than inferred: which phone you prop up is a habit, not something
 * the app can work out. [clear] exists because swapping the phones over has to
 * stay a two-tap operation.
 */
class RoleStore(private val context: Context) {

    private val key = stringPreferencesKey("role")

    /** Emits null until a role has been chosen, or after [clear]. */
    val role: Flow<Role?> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { stored ->
            // An unknown value means a downgrade, or a renamed enum. Treat it as
            // "not chosen" rather than crashing on valueOf.
            Role.entries.firstOrNull { it.name == stored }
        }
    }

    suspend fun remember(role: Role) {
        context.dataStore.edit { it[key] = role.name }
    }

    suspend fun clear() {
        context.dataStore.edit { it.remove(key) }
    }
}

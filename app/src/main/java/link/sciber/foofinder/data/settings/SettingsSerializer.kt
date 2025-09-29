package link.sciber.foofinder.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import java.io.InputStream
import java.io.OutputStream
import link.sciber.foofinder.datastore.UserSettings

object UserSettingsSerializer : Serializer<UserSettings> {
    override val defaultValue: UserSettings = UserSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): UserSettings =
            try {
                UserSettings.parseFrom(input)
            } catch (e: Exception) {
                throw CorruptionException("Cannot read proto", e)
            }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) {
        t.writeTo(output)
    }
}

val Context.userSettingsStore: DataStore<UserSettings> by dataStore(
        fileName = "user_settings.pb",
        serializer = UserSettingsSerializer
)

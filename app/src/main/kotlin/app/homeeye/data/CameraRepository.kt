package app.homeeye.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File

/** Saved cameras, kept in a JSON file in the app's private storage. */
class CameraRepository(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "cameras.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _cameras = MutableStateFlow(load())
    val cameras: StateFlow<List<Camera>> = _cameras.asStateFlow()

    /** Bumped whenever a thumbnail is saved, so lists re-read it. */
    private val _thumbVersion = MutableStateFlow(0)
    val thumbVersion: StateFlow<Int> = _thumbVersion.asStateFlow()

    fun get(id: String): Camera? = _cameras.value.firstOrNull { it.id == id }

    fun upsert(camera: Camera) {
        _cameras.update { list ->
            if (list.any { it.id == camera.id }) list.map { if (it.id == camera.id) camera else it }
            else list + camera
        }
        save()
    }

    fun delete(id: String) {
        _cameras.update { list -> list.filterNot { it.id == id } }
        thumbnailFile(id).delete()
        save()
    }

    fun thumbnailFile(id: String): File = File(dir, "thumbs/$id.jpg")

    fun thumbnailSaved() = _thumbVersion.update { it + 1 }

    private fun load(): List<Camera> = runCatching {
        if (file.exists()) json.decodeFromString<List<Camera>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun save() {
        // Write to a temp file first so a crash mid-write can't corrupt the list.
        val tmp = File(dir, "cameras.json.tmp")
        tmp.writeText(json.encodeToString(_cameras.value))
        tmp.renameTo(file)
    }
}

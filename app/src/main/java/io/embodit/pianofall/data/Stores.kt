package io.embodit.pianofall.data

import android.content.Context
import io.embodit.pianofall.domain.AssistLevel
import io.embodit.pianofall.domain.HandSel
import io.embodit.pianofall.domain.KeyCount
import io.embodit.pianofall.domain.LanguagePref
import io.embodit.pianofall.domain.PanelSide
import io.embodit.pianofall.domain.PianoMode
import io.embodit.pianofall.domain.Quat4
import io.embodit.pianofall.domain.SavedKeyboard
import io.embodit.pianofall.domain.Settings
import io.embodit.pianofall.domain.SongMemory
import io.embodit.pianofall.domain.Vec3
import org.json.JSONObject

/** Pose of a world anchor in Stage metres. */
data class AnchorPose(val position: Vec3, val rotation: Quat4)

/** A world anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/** The platform's persistent anchors (`platform.anchor.WorldAnchorGateway`; a fake in tests). */
interface AnchorGateway {
    suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space; an empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>
}

/** Settings, the keyboard and the per-song memory on the device (no network, no account; design `dataModel`). */
interface PianoStore {
    fun loadSettings(): Settings

    fun saveSettings(settings: Settings)

    fun loadKeyboard(): SavedKeyboard?

    fun saveKeyboard(keyboard: SavedKeyboard?)

    fun loadSongs(): Map<String, SongMemory>

    fun saveSong(id: String, memory: SongMemory)
}

class PrefsPianoStore(context: Context) : PianoStore {
    private val prefs = context.getSharedPreferences("piano_fall", Context.MODE_PRIVATE)

    override fun loadSettings(): Settings =
        Settings(
            language = LanguagePref.entries.firstOrNull { it.tag == prefs.getString("lang", null) } ?: LanguagePref.SYSTEM,
            muted = prefs.getBoolean("muted", false),
            volume = prefs.getFloat("volume", 0.7f).coerceIn(0f, 1f),
            metronome = prefs.getBoolean("metronome", false),
            lookAheadSec = prefs.getInt("lookahead", 3).coerceIn(2, 4),
            panelSide = PanelSide.entries.firstOrNull { it.tag == prefs.getString("side", null) } ?: PanelSide.CENTER,
            midiOffset = prefs.getInt("midiOffset", 0),
            legendSeen = prefs.getBoolean("legend", false),
            assist = AssistLevel.of(prefs.getString("assist", null)),
        )

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("lang", settings.language.tag)
            .putBoolean("muted", settings.muted)
            .putFloat("volume", settings.volume)
            .putBoolean("metronome", settings.metronome)
            .putInt("lookahead", settings.lookAheadSec)
            .putString("side", settings.panelSide.tag)
            .putInt("midiOffset", settings.midiOffset)
            .putBoolean("legend", settings.legendSeen)
            .putString("assist", settings.assist.tag)
            .apply()
    }

    override fun loadKeyboard(): SavedKeyboard? =
        runCatching {
            val o = JSONObject(prefs.getString(KEY_KEYBOARD, null) ?: return null)
            fun v(k: String) = Vec3(o.getDouble("${k}x").toFloat(), o.getDouble("${k}y").toFloat(), o.getDouble("${k}z").toFloat())
            SavedKeyboard(
                mode = PianoMode.entries.first { it.tag == o.getString("mode") },
                count = KeyCount.of(o.getInt("keys")) ?: KeyCount.K61,
                length = o.getDouble("length").toFloat(),
                leftAnchor = o.optString("la").ifEmpty { null },
                rightAnchor = o.optString("ra").ifEmpty { null },
                left = v("l"),
                right = v("r"),
                alignedAtMs = o.optLong("at"),
            )
        }.getOrNull()

    override fun saveKeyboard(keyboard: SavedKeyboard?) {
        if (keyboard == null) {
            prefs.edit().remove(KEY_KEYBOARD).apply()
            return
        }
        val o =
            JSONObject()
                .put("mode", keyboard.mode.tag)
                .put("keys", keyboard.count.keys)
                .put("length", keyboard.length.toDouble())
                .put("la", keyboard.leftAnchor ?: "")
                .put("ra", keyboard.rightAnchor ?: "")
                .put("lx", keyboard.left.x.toDouble()).put("ly", keyboard.left.y.toDouble()).put("lz", keyboard.left.z.toDouble())
                .put("rx", keyboard.right.x.toDouble()).put("ry", keyboard.right.y.toDouble()).put("rz", keyboard.right.z.toDouble())
                .put("at", keyboard.alignedAtMs)
        prefs.edit().putString(KEY_KEYBOARD, o.toString()).apply()
    }

    override fun loadSongs(): Map<String, SongMemory> =
        runCatching {
            val o = JSONObject(prefs.getString(KEY_SONGS, null) ?: return emptyMap())
            o.keys().asSequence().associateWith { id ->
                val s = o.getJSONObject(id)
                SongMemory(
                    speed = s.optDouble("speed", 1.0).toFloat(),
                    hands = HandSel.entries.firstOrNull { it.tag == s.optString("hands") } ?: HandSel.RIGHT,
                    bestAccuracy = s.optDouble("best", -1.0).toFloat(),
                    bestStars = s.optInt("stars", 0),
                    played = s.optBoolean("played", false),
                )
            }
        }.getOrDefault(emptyMap())

    override fun saveSong(id: String, memory: SongMemory) {
        val o = runCatching { JSONObject(prefs.getString(KEY_SONGS, null) ?: "{}") }.getOrDefault(JSONObject())
        o.put(id, JSONObject().put("speed", memory.speed.toDouble()).put("hands", memory.hands.tag).put("best", memory.bestAccuracy.toDouble()).put("stars", memory.bestStars).put("played", memory.played))
        prefs.edit().putString(KEY_SONGS, o.toString()).apply()
    }

    private companion object {
        const val KEY_KEYBOARD = "keyboard"
        const val KEY_SONGS = "songs"
    }
}

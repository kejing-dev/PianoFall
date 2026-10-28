package io.embodit.pianofall.domain

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Writes every song's notes to `app/build/songs.json` for `tools/export_songs.py` (.mid + 30 s preview per song
 * for the gate C listening check, design `songLicense`).
 */
class SongExportTest {
    @Test
    fun exportSongs() {
        val out = JSONArray()
        for (s in Library.all) {
            val notes = JSONArray()
            for (n in s.notes) notes.put(JSONObject().put("start", n.start.toDouble()).put("length", n.length.toDouble()).put("midi", n.midi).put("hand", n.hand.tag))
            out.put(
                JSONObject()
                    .put("id", s.id).put("zh", s.zh).put("en", s.en).put("level", s.level).put("bpm", s.bpm.toDouble())
                    .put("beatsPerBar", s.beatsPerBar).put("source", s.source).put("leftByUs", s.leftByUs).put("arrangedByUs", s.arrangedByUs)
                    .put("notes", notes)
            )
        }
        val f = File("build/songs.json")
        f.parentFile?.mkdirs()
        f.writeText(out.toString(1))
    }
}

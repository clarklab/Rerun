package team.superfun.rerun

import android.content.res.AssetManager
import android.util.Log

data class Clip(
    val assetPath: String,
    val title: String,
    val director: String = "",
    val year: String = "",
    val studio: String = "",
) {
    /** Which movie the clip is from, so the shuffle can keep two clips of one movie apart. */
    val movie: String get() = "${title.trim().lowercase()}|$year"

    /** Up to three lines for the bottom-left card: title / director / year + studio. */
    fun infoLines(): List<String> {
        val yearStudio = listOf(year, studio).filter { it.isNotBlank() }.joinToString("  ")
        return listOf(title, director, yearStudio).filter { it.isNotBlank() }
    }
}

/** Finds every clip bundled in the APK and pairs it with its row in clips.csv. */
object ClipLibrary {
    private const val TAG = "ClipLibrary"

    fun load(assets: AssetManager): List<Clip> {
        val files = (assets.list(Config.CLIPS_DIR) ?: emptyArray())
            .filter { it.substringAfterLast('.', "").lowercase() in Config.VIDEO_EXTENSIONS }
            .sorted()
        val metadata = readMetadata(assets)
        Log.i(TAG, "Found ${files.size} clips, ${metadata.size} metadata rows")
        return files.map { file ->
            metadata[file.lowercase()]?.copy(assetPath = "${Config.CLIPS_DIR}/$file")
                ?: fromFileName(file)
        }
    }

    private fun readMetadata(assets: AssetManager): Map<String, Clip> {
        val text = try {
            assets.open("${Config.CLIPS_DIR}/${Config.METADATA_FILE}").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyMap()
        }
        val rows = parseCsv(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyMap()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name)
        val iFile = col("file").takeIf { it >= 0 } ?: 0
        val iTitle = col("title")
        val iDirector = col("director")
        val iYear = col("year")
        val iStudio = col("studio")
        fun List<String>.at(i: Int) = if (i in indices) this[i].trim() else ""

        return rows.drop(1)
            .filter { it.at(iFile).isNotEmpty() }
            .associate { row ->
                val file = row.at(iFile)
                file.lowercase() to Clip(
                    assetPath = "",
                    title = row.at(iTitle).ifEmpty { fromFileName(file).title },
                    director = row.at(iDirector),
                    year = row.at(iYear),
                    studio = row.at(iStudio),
                )
            }
    }

    /** "Back to the Future (1985).mp4" -> title "Back to the Future", year "1985". */
    private fun fromFileName(file: String): Clip {
        val base = file.substringBeforeLast('.').replace('_', ' ').trim()
        val match = Regex("""^(.*?)[\s\-]*[(\[]?((?:19|20)\d\d)[)\]]?$""").find(base)
        val title = match?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: base
        val year = if (match != null && match.groupValues[1].isNotBlank()) match.groupValues[2] else ""
        return Clip(assetPath = "${Config.CLIPS_DIR}/$file", title = title, year = year)
    }

    /** Minimal RFC 4180 parser so titles like "Crouching Tiger, Hidden Dragon" work when quoted. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row.add(field.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }
}

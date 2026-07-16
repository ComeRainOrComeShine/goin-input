/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rime

import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File
import java.util.Locale

object YihuangReadingUpdater {
    const val SCHEMA_ID = "yihuang"

    private const val USER_READING_DICT_FILE = "yihuang_user_readings.dict.yaml"
    private const val USER_READING_DICT_NAME = "yihuang_user_readings"
    private const val BASE_WEIGHT = 20000
    private val codeRegex = Regex("^[a-z]{1,24}$")
    private val lock = Any()

    fun recordSelection(
        schemaId: String,
        rawInput: String?,
        text: String?,
    ): Boolean {
        if (schemaId != SCHEMA_ID) return false
        val code = normalizeCode(rawInput) ?: return false
        val hanText = normalizeSingleHan(text) ?: return false

        return runCatching {
            synchronized(lock) {
                val file = DataManager.userDataDir.resolve(USER_READING_DICT_FILE)
                val entries = readEntries(file)
                val key = EntryKey(hanText, code)
                val old = entries[key]
                entries[key] = (old ?: 0) + 1
                writeEntries(file, entries)
                old == null
            }
        }.onFailure {
            Timber.w(it, "Failed to record yihuang reading: %s -> %s", text, rawInput)
        }.getOrDefault(false)
    }

    private fun normalizeCode(rawInput: String?): String? {
        val code = rawInput
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return null
        return code.takeIf { codeRegex.matches(it) }
    }

    private fun normalizeSingleHan(text: String?): String? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.codePointCount(0, value.length) != 1) return null
        val codePoint = value.codePointAt(0)
        return value.takeIf { Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN }
    }

    private fun readEntries(file: File): MutableMap<EntryKey, Int> {
        if (!file.exists()) return linkedMapOf()
        val entries = linkedMapOf<EntryKey, Int>()
        file.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#") || line.startsWith("---") || line == "...") return@forEachLine
            val columns = line.split('\t')
            if (columns.size != 3) return@forEachLine
            val text = columns[0].trim()
            val code = columns[1].trim()
            val weight = columns[2].trim().toIntOrNull() ?: return@forEachLine
            if (normalizeSingleHan(text) != null && codeRegex.matches(code)) {
                entries[EntryKey(text, code)] = (weight - BASE_WEIGHT).coerceAtLeast(1)
            }
        }
        return entries
    }

    private fun writeEntries(file: File, entries: Map<EntryKey, Int>) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "$USER_READING_DICT_FILE.tmp")
        tempFile.bufferedWriter().use { writer ->
            writer.appendLine("# Rime dictionary")
            writer.appendLine("# encoding: utf-8")
            writer.appendLine("# Auto-learned yihuang single-character readings from user selections.")
            writer.appendLine()
            writer.appendLine("---")
            writer.appendLine("name: $USER_READING_DICT_NAME")
            writer.appendLine("version: \"user\"")
            writer.appendLine("sort: by_weight")
            writer.appendLine("use_preset_vocabulary: false")
            writer.appendLine("columns:")
            writer.appendLine("  - text")
            writer.appendLine("  - code")
            writer.appendLine("  - weight")
            writer.appendLine("...")
            entries.entries
                .sortedWith(
                    compareByDescending<Map.Entry<EntryKey, Int>> { it.value }
                        .thenBy { it.key.text }
                        .thenBy { it.key.code },
                ).forEach { (key, count) ->
                    writer.appendLine("${key.text}\t${key.code}\t${BASE_WEIGHT + count}")
                }
        }
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
    }

    private data class EntryKey(
        val text: String,
        val code: String,
    )
}

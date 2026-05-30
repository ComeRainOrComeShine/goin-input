/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.update

import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object GanRimeUpdateManager {
    private const val MAX_MANIFEST_BYTES = 512L * 1024L
    private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
    private const val VERSION_FILE_NAME = "gan_rime_update_version.txt"

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = AppPrefs.defaultInstance().profile

    private val allowedFileNames = setOf(
        "gan_nanchang_ganxi_mixed.schema.yaml",
        "gan_nanchang_ganxi_mixed.dict.yaml",
        "gan_auxiliary_pinyin.schema.yaml",
        "gan_auxiliary_comments.tsv",
        "koinese_pinyin.schema.yaml",
        "koinese_pinyin.dict.yaml",
        "rime.lua",
    )

    @Serializable
    private data class UpdateManifest(
        val version: String,
        val files: List<UpdateFile>,
    )

    @Serializable
    private data class UpdateFile(
        val path: String,
        val url: String,
        @SerialName("sha256")
        val sha256: String,
    )

    data class UpdateResult(
        val version: String,
        val updatedFiles: List<String>,
        val skipped: Boolean,
    )

    suspend fun updateFromManifest(manifestUrl: String): UpdateResult = withContext(Dispatchers.IO) {
        require(manifestUrl.startsWith("https://")) { "Update source must use HTTPS" }

        val manifest = json.decodeFromString<UpdateManifest>(
            downloadText(manifestUrl, MAX_MANIFEST_BYTES),
        )
        require(manifest.version.isNotBlank()) { "Manifest version is blank" }
        require(manifest.files.isNotEmpty()) { "Manifest has no files" }

        val currentVersion = DataManager.userDataDir.resolve(VERSION_FILE_NAME).takeIf { it.exists() }?.readText()?.trim()
        if (currentVersion == manifest.version) {
            prefs.ganRimeLastUpdateVersion.setValue(manifest.version)
            return@withContext UpdateResult(manifest.version, emptyList(), skipped = true)
        }

        val stagingDir = File(DataManager.userDataDir, ".gan_rime_update").also {
            FileUtils.delete(it).getOrThrow()
            it.mkdirs()
        }
        val downloadedFiles =
            manifest.files.map { entry ->
                val fileName = validateFileName(entry.path)
                require(entry.url.startsWith("https://")) { "File URL must use HTTPS: $fileName" }
                require(entry.sha256.matches(Regex("[A-Fa-f0-9]{64}"))) { "Invalid SHA-256 for $fileName" }

                val tempFile = File(stagingDir, "$fileName.download")
                downloadFile(entry.url, tempFile, MAX_FILE_BYTES)
                val actualSha256 = tempFile.sha256()
                require(actualSha256.equals(entry.sha256, ignoreCase = true)) {
                    "SHA-256 mismatch for $fileName"
                }
                fileName to tempFile
            }

        val appliedFiles = mutableListOf<String>()
        try {
            downloadedFiles.forEach { (fileName, tempFile) ->
                val destFile = DataManager.userDataDir.resolve(fileName)
                val backupFile = File(stagingDir, "$fileName.backup")
                if (destFile.exists()) {
                    destFile.copyTo(backupFile, overwrite = true)
                }
                tempFile.copyTo(destFile, overwrite = true)
                appliedFiles += fileName
            }
            FileUtils.delete(DataManager.stagingDir).getOrThrow()
            DataManager.userDataDir.resolve(VERSION_FILE_NAME).writeText(manifest.version)
            prefs.ganRimeLastUpdateVersion.setValue(manifest.version)
            UpdateResult(manifest.version, appliedFiles, skipped = false)
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply Gan Rime update; rolling back.")
            appliedFiles.forEach { fileName ->
                val destFile = DataManager.userDataDir.resolve(fileName)
                val backupFile = File(stagingDir, "$fileName.backup")
                if (backupFile.exists()) {
                    backupFile.copyTo(destFile, overwrite = true)
                } else {
                    destFile.delete()
                }
            }
            throw e
        } finally {
            FileUtils.delete(stagingDir)
        }
    }

    private fun validateFileName(path: String): String {
        require(path == path.substringAfterLast('/')) { "Nested paths are not allowed: $path" }
        require(path == path.substringAfterLast('\\')) { "Nested paths are not allowed: $path" }
        require(path in allowedFileNames) { "File is not allowed to update: $path" }
        return path
    }

    private fun downloadText(
        url: String,
        maxBytes: Long,
    ): String {
        val tempFile = File.createTempFile("gan-rime-manifest", ".json")
        return try {
            downloadFile(url, tempFile, maxBytes)
            tempFile.readText()
        } finally {
            tempFile.delete()
        }
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        maxBytes: Long,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GanInput/${prefs.ganRimeLastUpdateVersion.getValue().ifBlank { "test" }}")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code: $url" }
            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= maxBytes) { "Downloaded file is too large: $url" }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

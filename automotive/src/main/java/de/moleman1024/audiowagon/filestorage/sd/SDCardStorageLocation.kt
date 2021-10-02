/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorageLocation
import de.moleman1024.audiowagon.filestorage.IndexingStatus
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.File
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.*

private const val URI_SCHEME = "sdAudio"
private const val TAG = "SDCardStorLoc"
private val logger = Logger

/**
 * NOTE: SD card support is only enabled in debug builds used in the Android emulator
 */
class SDCardStorageLocation(override val device: SDCardMediaDevice) : AudioFileStorageLocation {
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    private var isIndexingCancelled: Boolean = false

    @ExperimentalCoroutinesApi
    override fun indexAudioFiles(scope: CoroutineScope): ReceiveChannel<AudioFile> {
        logger.debug(TAG, "indexAudioFiles(${device.getShortName()})")
        return scope.produce {
            try {
                for (file in device.walkTopDown(device.getRoot())) {
                    if (isIndexingCancelled) {
                        logger.debug(TAG, "Cancel indexAudioFiles()")
                        break
                    }
                    if (!isPlayableAudioFile(file)) {
                        logger.debug(TAG, "Skipping non-audio file: $file")
                        continue
                    }
                    val builder: Uri.Builder = Uri.Builder()
                    builder.scheme(URI_SCHEME).authority(storageID).appendEncodedPath(
                        Uri.encode(file.absolutePath.removePrefix("/"), StandardCharsets.UTF_8.toString())
                    )
                    val audioFile = AudioFile(builder.build())
                    audioFile.lastModifiedDate = Date(file.lastModified())
                    send(audioFile)
                }
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
            } finally {
                isIndexingCancelled = false
            }
        }
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getDataSourceForURI(uri)
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getBufferedDataSourceForURI(uri)
    }

    override fun close() {
        device.close()
    }

    override fun setDetached() {
        device.isClosed = true
        isDetached = true
    }

    // TODO: remove code duplication
    private fun isPlayableAudioFile(file: File): Boolean {
        if (file.isDirectory) {
            return false
        }
        val guessedContentType: String
        try {
            val safeFileName = makeFileNameSafeForContentTypeGuessing(file.name)
            guessedContentType = URLConnection.guessContentTypeFromName(safeFileName) ?: return false
        } catch (exc: StringIndexOutOfBoundsException) {
            logger.exception(TAG, "Error when guessing content type of: ${file.name}", exc)
            return false
        }
        if (!guessedContentType.startsWith("audio")) {
            return false
        }
        if (isPlaylistFile(guessedContentType)) {
            return false
        }
        return true
    }

    private fun makeFileNameSafeForContentTypeGuessing(fileName: String): String {
        return fileName.replace("#", "")
    }

    private fun isPlaylistFile(contentType: String): Boolean {
        return listOf("mpequrl", "mpegurl").any { it in contentType }
    }

    override fun getDirectoriesWithIndexingIssues(): List<String> {
        // TODO
        return emptyList()
    }

    override fun cancelIndexAudioFiles() {
        logger.debug(TAG, "Cancelling audio file indexing")
        isIndexingCancelled = true
    }

}

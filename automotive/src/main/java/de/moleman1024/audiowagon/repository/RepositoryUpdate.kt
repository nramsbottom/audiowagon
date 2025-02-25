/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import android.content.Context
import android.content.res.Configuration
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.Util.Companion.getSortNameOrBlank
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.filestorage.GeneralFile
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemType
import de.moleman1024.audiowagon.medialibrary.AudioMetadataMaker
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.CONTENT_HIERARCHY_MAX_NUM_ITEMS
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.DATABASE_ID_UNKNOWN
import de.moleman1024.audiowagon.repository.entities.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue


private const val TAG = "RepositoryUpdate"
private val logger = Logger

@ExperimentalCoroutinesApi
class RepositoryUpdate(private val repo: AudioItemRepository, private val context: Context) {
    private var pseudoCompilationArtistID: Long? = null
    private var pseudoCompilationArtistName: String? = null
    val trackIDsToKeep = ConcurrentLinkedQueue<Long>()
    val pathIDsToKeep = ConcurrentLinkedQueue<Long>()

    /**
     * Adds a new entries into database (tracks, artists, albums, paths).
     *
     * Locked externally
     */
    fun populateDatabaseFrom(audioFile: AudioFile, metadata: AudioItem, albumArtSource: FileLike?) {
        var artistID: Long = DATABASE_ID_UNKNOWN
        var albumArtistID: Long = DATABASE_ID_UNKNOWN
        var newArtist: Artist? = null
        var artistInDB: Artist? = null
        var newAlbumArtist: Artist? = null
        if (metadata.artist.isNotBlank()) {
            // multiple artists with same name are unlikely, ignore this case
            artistInDB = repo.getDatabaseNoLock()?.artistDAO()?.queryByName(metadata.artist)
            if (artistInDB?.artistId != null) {
                artistID = artistInDB.artistId
            } else {
                newArtist = Artist(name = metadata.artist, sortName = getSortNameOrBlank(metadata.artist))
                if (metadata.isInCompilation) {
                    newArtist.isCompilationArtist = true
                }
            }
        }
        if (metadata.albumArtist.isNotBlank()) {
            if (metadata.albumArtist == metadata.artist) {
                albumArtistID = artistID
                newArtist?.isAlbumArtist = true
                if (artistInDB?.isAlbumArtist == false) {
                    // this artist was previously added without being an album artist, need to change
                    logger.debug(TAG, "Need to update isAlbumArtist=true for: $artistInDB")
                    repo.getDatabaseNoLock()?.artistDAO()?.setIsAlbumArtistByID(artistInDB.artistId)
                    artistInDB.isAlbumArtist = true
                }
            } else {
                // this will support album artists https://github.com/MoleMan1024/audiowagon/issues/22
                // (these are not considered compilations, the album artist is treated as the "main" artist)
                if (!metadata.isInCompilation) {
                    val albumArtistInDB: Artist? =
                        repo.getDatabaseNoLock()?.artistDAO()?.queryByName(metadata.albumArtist)
                    if (albumArtistInDB?.artistId != null) {
                        albumArtistID = albumArtistInDB.artistId
                    } else {
                        newAlbumArtist = Artist(
                            name = metadata.albumArtist,
                            sortName = getSortNameOrBlank(metadata.albumArtist),
                            isAlbumArtist = true
                        )
                    }
                }
            }
        }
        if (newArtist != null || newAlbumArtist != null) {
            if (newArtist?.name == newAlbumArtist?.name) {
                // do not insert duplicate artists
                newArtist = null
            }
            if (metadata.albumArtist.isBlank() && !metadata.isInCompilation) {
                newArtist?.isAlbumArtist = true
            }
            if (newArtist != null) {
                logger.debug(TAG, "Inserting artist: $newArtist")
                artistID = repo.getDatabaseNoLock()?.artistDAO()?.insert(newArtist) ?: DATABASE_ID_UNKNOWN
            }
            if (newAlbumArtist != null) {
                logger.debug(TAG, "Inserting album artist: $newAlbumArtist")
                albumArtistID = repo.getDatabaseNoLock()?.artistDAO()?.insert(newAlbumArtist) ?: DATABASE_ID_UNKNOWN
            }
        }
        if (albumArtistID <= DATABASE_ID_UNKNOWN) {
            albumArtistID = artistID
        }
        val albumArtID = Util.createIDForAlbumArtForFile(audioFile.path)
        val albumArtContentURIForAlbum = AudioMetadataMaker.createURIForAlbumArtForAlbum(albumArtID)
        var albumID: Long = DATABASE_ID_UNKNOWN
        if (metadata.album.isNotBlank()) {
            // Watch out for special cases for albums:
            // - same album name across several artists, e.g. "Greatest Hits" albums
            // - same album name for multiple artists could also be a compilation/various artists album
            if (metadata.isInCompilation) {
                albumArtistID = makePseudoCompilationArtist()
            }
            val albumInDB: Album? =
                repo.getDatabaseNoLock()?.albumDAO()?.queryByNameAndArtist(metadata.album, albumArtistID)
            val albumArtURINeedsUpdate = doesAlbumArtURINeedUpdate(albumInDB, albumArtSource)
            if (albumArtURINeedsUpdate) {
                // delete the album with the outdated album art info and create new one below
                albumInDB?.albumId?.let { repo.getDatabaseNoLock()?.albumDAO()?.deleteByID(it) }
            }
            albumID = if (albumInDB?.albumId != null && !albumArtURINeedsUpdate) {
                albumInDB.albumId
            } else {
                val album = Album(
                    name = metadata.album,
                    parentArtistId = albumArtistID,
                    sortName = getSortNameOrBlank(metadata.album),
                    albumArtURIString = albumArtContentURIForAlbum.toString(),
                    albumArtSourceURIString = albumArtSource?.uri?.toString() ?: "",
                    hasFolderImage = albumArtSource is GeneralFile
                )
                logger.debug(TAG, "Inserting album: $album")
                repo.getDatabaseNoLock()?.albumDAO()?.insert(album) ?: DATABASE_ID_UNKNOWN
            }
        }
        val albumArtContentURIForTrack = AudioMetadataMaker.createURIForAlbumArtForTrack(albumArtID)
        val lastModifiedTime = audioFile.lastModifiedDate.toInstant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
        val track = Track(
            name = metadata.title,
            sortName = getSortNameOrBlank(metadata.title),
            // TODO: to allow https://github.com/MoleMan1024/audiowagon/issues/23 I would need to allow multiple parent
            //  artist/albumartist IDs
            parentArtistId = artistID,
            parentAlbumArtistId = albumArtistID,
            parentAlbumId = albumID,
            trackNum = metadata.trackNum,
            discNum = metadata.discNum,
            albumArtURIString = albumArtContentURIForTrack.toString(),
            yearEpochTime = Util.yearShortToEpochTime(metadata.year),
            uriString = audioFile.uri.toString(),
            lastModifiedEpochTime = lastModifiedTime,
            durationMS = metadata.durationMS,
        )
        logger.debug(TAG, "Inserting track: $track")
        val trackID: Long = repo.getDatabaseNoLock()?.trackDAO()?.insert(track) ?: DATABASE_ID_UNKNOWN
        trackIDsToKeep.add(trackID)
    }

    // locked externally
    fun populateDatabaseFromFileOrDir(fileLike: FileLike) {
        val lastModifiedTime = fileLike.lastModifiedDate.toInstant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
        val parentPathID =
            repo.getDatabaseNoLock()?.pathDAO()?.queryParentPath(fileLike.parentPath)?.pathId ?: DATABASE_ID_UNKNOWN
        val path = Path(
            parentPathId = parentPathID,
            parentPath = fileLike.parentPath,
            name = fileLike.name,
            lastModifiedEpochTime = lastModifiedTime
        )
        if (fileLike is Directory) {
            path.isDirectory = true
        }
        logger.debug(TAG, "Inserting file/directory: $path")
        val pathDatabaseID: Long = repo.getDatabaseNoLock()?.pathDAO()?.insert(path) ?: DATABASE_ID_UNKNOWN
        pathIDsToKeep.add(pathDatabaseID)
    }

    // locked externally
    fun cleanGroups() {
        logger.debug(TAG, "cleanGroups()")
        repo.getDatabaseNoLock()?.artistDAO()?.deleteAllArtistGroups()
        repo.getDatabaseNoLock()?.albumDAO()?.deleteAllAlbumGroups()
        repo.getDatabaseNoLock()?.trackDAO()?.deleteAllTrackGroups()
    }

    /**
     * Creates groups of 400 tracks/artist/albums in database because creating those dynamically required too many
     * database queries which was too slow (mostly for tracks, see issue #38)
     *
     * locked externally
     */
    suspend fun createGroups() {
        createGroupsForType(AudioItemType.TRACK)
        // usually there are a lot less artists and albums than tracks, so the speed-up will be less for these
        createGroupsForType(AudioItemType.ARTIST)
        createGroupsForType(AudioItemType.ALBUM)
        // for files this should not be needed unless a user stores their whole music library without any directories
    }

    private suspend fun createGroupsForType(audioItemType: AudioItemType) {
        logger.debug(TAG, "createGroupsForType($audioItemType)")
        val numItems: Int = when (audioItemType) {
            AudioItemType.TRACK -> {
                repo.getNumTracksNoLock()
            }
            AudioItemType.ALBUM -> {
                repo.getNumAlbumsNoLock()
            }
            AudioItemType.ARTIST -> {
                repo.getNumAlbumAndCompilationArtistsNoLock()
            }
            else -> throw AssertionError("")
        }
        logger.debug(TAG, "num items in group $audioItemType: $numItems")
        var offset = 0
        val lastGroupIndex = numItems / CONTENT_HIERARCHY_MAX_NUM_ITEMS
        for (groupIndex in 0..lastGroupIndex) {
            val offsetRows = if (groupIndex < lastGroupIndex) {
                offset + CONTENT_HIERARCHY_MAX_NUM_ITEMS - 1
            } else {
                offset + (numItems % CONTENT_HIERARCHY_MAX_NUM_ITEMS) - 1
            }
            when (audioItemType) {
                AudioItemType.TRACK -> {
                    val firstItemInGroup = repo.getDatabaseNoLock()?.trackDAO()?.queryTracksLimitOffset(1, offset)
                    val lastItemInGroup = repo.getDatabaseNoLock()?.trackDAO()?.queryTracksLimitOffset(1, offsetRows)
                    if (firstItemInGroup.isNullOrEmpty() || lastItemInGroup.isNullOrEmpty()) {
                        break
                    }
                    val trackGroup = TrackGroup(
                        trackGroupIndex = groupIndex,
                        startTrackId = firstItemInGroup[0].trackId,
                        endTrackId = lastItemInGroup[0].trackId
                    )
                    repo.getDatabaseNoLock()?.trackDAO()?.insertGroup(trackGroup)
                }
                AudioItemType.ALBUM -> {
                    val firstItemInGroup = repo.getDatabaseNoLock()?.albumDAO()?.queryAlbumsLimitOffset(1, offset)
                    val lastItemInGroup = repo.getDatabaseNoLock()?.albumDAO()?.queryAlbumsLimitOffset(1, offsetRows)
                    if (firstItemInGroup.isNullOrEmpty() || lastItemInGroup.isNullOrEmpty()) {
                        break
                    }
                    val albumGroup = AlbumGroup(
                        albumGroupIndex = groupIndex,
                        startAlbumId = firstItemInGroup[0].albumId,
                        endAlbumId = lastItemInGroup[0].albumId
                    )
                    repo.getDatabaseNoLock()?.albumDAO()?.insertGroup(albumGroup)
                }
                AudioItemType.ARTIST -> {
                    val firstItemInGroup =
                        repo.getDatabaseNoLock()?.artistDAO()?.queryAlbumAndCompilationArtistsLimitOffset(1, offset)
                    val lastItemInGroup =
                        repo.getDatabaseNoLock()?.artistDAO()?.queryAlbumAndCompilationArtistsLimitOffset(1, offsetRows)
                    if (firstItemInGroup.isNullOrEmpty() || lastItemInGroup.isNullOrEmpty()) {
                        break
                    }
                    val artistGroup = ArtistGroup(
                        artistGroupIndex = groupIndex,
                        startArtistId = firstItemInGroup[0].artistId,
                        endArtistId = lastItemInGroup[0].artistId
                    )
                    repo.getDatabaseNoLock()?.artistDAO()?.insertGroup(artistGroup)
                }
                else -> throw AssertionError("createGroups() not supported for type: $audioItemType")
            }
            offset += CONTENT_HIERARCHY_MAX_NUM_ITEMS
        }
    }

    /**
     * Creates a pseudo artist for compilations ("Various artists") in database if necessary. Returns database ID of
     * that pseudo artist.
     *
     * Locked externally
     */
    private fun makePseudoCompilationArtist(): Long {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID!!
        }
        pseudoCompilationArtistName = getPseudoCompilationArtistNameEnglish()
        val artistInDB: Artist? = repo.getDatabaseNoLock()?.artistDAO()?.queryByName(pseudoCompilationArtistName!!)
        val resultID: Long = if (artistInDB?.artistId == null) repo.getDatabaseNoLock()?.artistDAO()
            ?.insert(Artist(name = pseudoCompilationArtistName!!, isAlbumArtist = true))
            ?: DATABASE_ID_UNKNOWN else artistInDB.artistId
        pseudoCompilationArtistID = resultID
        logger.verbose(TAG, "Added pseudo compilation artist with ID: $pseudoCompilationArtistID")
        return resultID
    }

    // #110: the entry for "Various artists" should always be English internally, it will only be shown localized to
    // the user in GUI
    fun getPseudoCompilationArtistNameEnglish(): String {
        if (pseudoCompilationArtistName != null) {
            return pseudoCompilationArtistName!!
        }
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale("en_US"))
        return context.createConfigurationContext(configuration).resources.getString(
            R.string.browse_tree_various_artists
        )
    }

    /**
     * Returns the ID of the compilation-artist (shown as "Various artists")
     */
    suspend fun getPseudoCompilationArtistID(): Long? {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID
        }
        val pseudoCompilationArtistName = getPseudoCompilationArtistNameEnglish()
        val artistInDB: Artist? = repo.getDatabase()?.artistDAO()?.queryByName(pseudoCompilationArtistName)
        pseudoCompilationArtistID = artistInDB?.artistId
        return pseudoCompilationArtistID
    }

    // locked externally
    fun removeTrack(trackID: Long) {
        logger.debug(TAG, "Removing track from database: $trackID")
        repo.getDatabaseNoLock()?.trackDAO()?.deleteByID(trackID)
    }

    // locked externally
    fun removePath(pathID: Long) {
        logger.debug(TAG, "Removing path from database: $pathID")
        repo.getDatabaseNoLock()?.pathDAO()?.deleteByID(pathID)
    }

    /**
     * Remove all tracks/albums/artists/paths in database that were not added by previous buildLibrary() call
     *
     * locked externally
     */
    fun clean() {
        logger.debug(TAG, "Cleaning no longer available items from database")
        val allTracksInDB = repo.getDatabaseNoLock()?.trackDAO()?.queryAll() ?: listOf()
        for (track in allTracksInDB) {
            if (track.trackId in trackIDsToKeep) {
                continue
            }
            logger.verbose(TAG, "Removing track from database: $track")
            repo.getDatabaseNoLock()?.trackDAO()?.delete(track)
            repo.hasUpdatedDatabase = true
        }
        trackIDsToKeep.clear()
        val allPathsInDB = repo.getDatabaseNoLock()?.pathDAO()?.queryAll() ?: listOf()
        for (path in allPathsInDB) {
            if (path.pathId in pathIDsToKeep) {
                continue
            }
            logger.verbose(TAG, "Removing path from database: $path")
            repo.getDatabaseNoLock()?.pathDAO()?.delete(path)
            repo.hasUpdatedDatabase = true
        }
        pruneAlbums()
        pruneArtists()
    }

    /**
     * Remove albums that no longer have any associated tracks
     */
    private fun pruneAlbums() {
        val albumIDs = repo.getDatabaseNoLock()?.albumDAO()?.queryAll()?.map { it.albumId }
        albumIDs?.forEach { albumID ->
            val numTracksForAlbum = repo.getDatabaseNoLock()?.trackDAO()?.queryNumTracksForAlbum(albumID) ?: 0
            if (numTracksForAlbum <= 0) {
                logger.verbose(TAG, "Removing album from database: $albumID")
                repo.getDatabaseNoLock()?.albumDAO()?.deleteByID(albumID)
            }
        }
    }

    /**
     * Remove artists that no longer have any associated tracks
     */
    private fun pruneArtists() {
        val artistIDs = repo.getDatabaseNoLock()?.artistDAO()?.queryAll()?.map { it.artistId }
        artistIDs?.forEach { artistID ->
            val numTracksForArtist = repo.getDatabaseNoLock()?.trackDAO()?.queryNumTracksForArtist(artistID) ?: 0
            val numTracksForAlbumArtist =
                repo.getDatabaseNoLock()?.trackDAO()?.queryNumTracksForAlbumArtist(artistID) ?: 0
            if (numTracksForArtist <= 0 && numTracksForAlbumArtist <= 0) {
                logger.verbose(TAG, "Removing artist from database: $artistID")
                repo.getDatabaseNoLock()?.artistDAO()?.deleteByID(artistID)
            }
        }
    }

    // In case an audio file is converted into another file type (e.g. .m4a to .mp3) the album art URI might be
    // pointing to the old file. Check if this is the case by comparing filename URIs without the extension
    // https://github.com/MoleMan1024/audiowagon/issues/118
    private fun doesAlbumArtURINeedUpdate(albumInDB: Album?, albumArtSource: FileLike?): Boolean {
        if (albumInDB == null || albumArtSource == null) {
            return false
        }
        if (albumInDB.albumArtSourceURIString == albumArtSource.uri.toString()) {
            // nothing has changed
            return false
        }
        if (albumInDB.albumArtSourceURIString.isEmpty() && albumArtSource.uri.toString().isNotEmpty()) {
            // someone has added album art when previously there was none, we need to update album in database
            logger.debug(TAG, "New album art URI to add: ${albumArtSource.uri}")
            return true
        }
        if (albumInDB.albumArtSourceURIString.isNotEmpty() && albumArtSource.uri.toString().isEmpty()) {
            // someone has removed album art, we need to update album in database
            logger.debug(TAG, "Album art URI to remove: ${albumInDB.albumArtSourceURIString}")
            return true
        }
        val albumArtURIInDBNoExtension = albumInDB.albumArtSourceURIString.substringBeforeLast(".")
        val albumArtURINoExtension = albumArtSource.uri.toString().substringBeforeLast(".")
        if (albumArtURIInDBNoExtension != albumArtURINoExtension) {
            return false
        }
        // if just the extension for the album art source URI differs, it was likely converted between file formats
        logger.debug(TAG, "Album art URI needs update from: ${albumInDB.albumArtURIString} to ${albumArtSource.uri}")
        return true
    }

}

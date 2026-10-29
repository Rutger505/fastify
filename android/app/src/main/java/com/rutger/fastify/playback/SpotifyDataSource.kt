package com.rutger.fastify.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.rutger.fastify.core.CoreException
import com.rutger.fastify.core.TrackStream
import java.io.IOException

/** Serves decrypted Ogg Vorbis bytes from the Rust core (disk cache first, CDN otherwise). */
@UnstableApi
class SpotifyDataSource(private val pool: StreamPool) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var stream: TrackStream? = null
    private var position = 0L
    private var remaining = 0L
    private var buffer = EMPTY
    private var bufferPos = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val trackId = dataSpec.uri.lastPathSegment
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val s = try {
            pool.get(trackId)
        } catch (e: CoreException) {
            com.rutger.fastify.diag.AppLog.e("Stream", "open $trackId failed", e)
            throw IOException("Can't open track $trackId: ${e.message}", e)
        }
        val length = s.length().toLong()
        if (dataSpec.position > length) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        uri = dataSpec.uri
        stream = s
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, length - position)
        } else {
            length - position
        }
        buffer = EMPTY
        bufferPos = 0
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufferPos >= buffer.size) {
            val s = stream ?: throw IOException("Data source not open")
            buffer = try {
                s.read(position.toULong(), CHUNK.toUInt())
            } catch (e: CoreException) {
                throw IOException(e.message, e)
            }
            bufferPos = 0
            if (buffer.isEmpty()) return C.RESULT_END_OF_INPUT
        }
        val n = minOf(length.toLong(), (buffer.size - bufferPos).toLong(), remaining).toInt()
        System.arraycopy(buffer, bufferPos, target, offset, n)
        bufferPos += n
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        buffer = EMPTY
        if (stream != null) {
            // The stream stays in the pool for the next seek.
            stream = null
            transferEnded()
        }
    }

    class Factory(private val pool: StreamPool) : DataSource.Factory {
        override fun createDataSource(): DataSource = SpotifyDataSource(pool)
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val CHUNK = 32 * 1024
    }
}

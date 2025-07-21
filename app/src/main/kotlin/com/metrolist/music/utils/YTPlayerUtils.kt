package com.metrolist.music.utils

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.metrolist.music.constants.AudioQuality
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_PREMIUM
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    
    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()
    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * [com.metrolist.innertube.models.YouTubeClient.WEB_REMIX] should be preferred here because currently it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX
    /**
     * Primary clients to try first - includes authenticated client for premium content
     */
    private val PRIMARY_CLIENTS: Array<YouTubeClient> = arrayOf(
        WEB_REMIX,      // Best for metadata and premium formats
        WEB_PREMIUM,    // For authenticated content
        MOBILE          // Fast mobile streams
    )
    /**
     * Clients used for fallback streams in case the primary clients do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_NO_AUTH,
        MOBILE,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS,
        WEB,
        WEB_CREATOR,
        WEB_PREMIUM
    )
    
    /**
     * Get optimized client order based on login status
     */
    private fun getOptimizedClientOrder(): Array<YouTubeClient> {
        val isLoggedIn = YouTube.cookie != null
        return if (isLoggedIn) {
            // If logged in, prioritize premium client for restricted content
            arrayOf(WEB_PREMIUM, WEB_REMIX, MOBILE) + STREAM_FALLBACK_CLIENTS
        } else {
            // If not logged in, use standard order
            PRIMARY_CLIENTS + STREAM_FALLBACK_CLIENTS
        }
    }
    
    /**
     * Check if the content appears to be restricted/premium based on playability status
     */
    private fun isRestrictedContent(playerResponse: PlayerResponse?): Boolean {
        val status = playerResponse?.playabilityStatus?.status
        val reason = playerResponse?.playabilityStatus?.reason?.lowercase()
        
        return when {
            status == "LOGIN_REQUIRED" -> true
            status == "UNPLAYABLE" && reason?.contains("sign in") == true -> true
            status == "UNPLAYABLE" && reason?.contains("premium") == true -> true
            status == "UNPLAYABLE" && reason?.contains("membership") == true -> true
            else -> false
        }
    }
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        /**
         * This is required for some clients to get working streams however
         * it should not be forced for the [MAIN_CLIENT] because the response of the [MAIN_CLIENT]
         * is required even if the streams won't work from this client.
         * This is why it is allowed to be null.
         */
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).d("Signature timestamp: $signatureTimestamp")

        val isLoggedIn = YouTube.cookie != null
        val sessionId =
            if (isLoggedIn) {
                // signed in sessions use dataSyncId as identifier
                YouTube.dataSyncId
            } else {
                // signed out sessions use visitorData as identifier
                YouTube.visitorData
            }
        Timber.tag(logTag).d("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        // Try primary clients first
        Timber.tag(logTag).d("Trying primary clients first for better performance")
        val allClientsToTry = getOptimizedClientOrder()
        
        var mainPlayerResponse: PlayerResponse? = null
        var audioConfig: PlayerResponse.PlayerConfig.AudioConfig? = null
        var videoDetails: PlayerResponse.VideoDetails? = null
        var playbackTracking: PlayerResponse.PlaybackTracking? = null
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null

        for (clientIndex in allClientsToTry.indices) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            val client = allClientsToTry[clientIndex]
            val isPrimaryClient = clientIndex < PRIMARY_CLIENTS.size

            if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                // skip client if it requires login but user is not logged in
                Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                continue
            }

            Timber.tag(logTag).d("Fetching player response for client: ${client.clientName}")
            streamPlayerResponse =
                YouTube.player(videoId, playlistId, client, signatureTimestamp).getOrNull()

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${client.clientName}")

                mainPlayerResponse = streamPlayerResponse
                audioConfig = mainPlayerResponse.playerConfig?.audioConfig
                videoDetails = mainPlayerResponse.videoDetails
                playbackTracking = mainPlayerResponse.playbackTracking

                format =
                    findFormat(
                        mainPlayerResponse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${client.clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                streamUrl = findUrlOrNull(format, videoId)
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                if (clientIndex == allClientsToTry.size - 1) {
                    /** skip [validateStatus] for last client */
                    Timber.tag(logTag).d("Using last fallback client without validation: ${client.clientName}")
                    break
                }

                if (validateStatus(streamUrl)) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${client.clientName}")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${client.clientName}")
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
                
                // Check if this is restricted content that might need authentication
                if (isRestrictedContent(streamPlayerResponse) && !isLoggedIn) {
                    Timber.tag(logTag).w("Detected restricted content but user is not logged in - content may require premium access")
                }
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            
            // Provide more helpful error message for restricted content
            val enhancedErrorMessage = if (isRestrictedContent(streamPlayerResponse)) {
                "This content requires authentication or premium access. Please sign in to YouTube Music."
            } else {
                errorReason
            }
            
            throw PlaybackException(
                enhancedErrorMessage,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }
    /**
     * Get the best client for initial metadata based on login status and content requirements
     */
    private fun getBestInitialClient(): YouTubeClient {
        val isLoggedIn = YouTube.cookie != null
        return if (isLoggedIn) {
            WEB_PREMIUM // Use premium client if logged in for better access to restricted content
        } else {
            WEB_REMIX   // Use regular client if not logged in
        }
    }
    
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        val bestClient = getBestInitialClient()
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using client: ${bestClient.clientName}")
        return YouTube.player(videoId, playlistId, client = bestClient)
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val isSuccessful = response.isSuccessful
            Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${response.code})")
            return isSuccessful
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).d("Signature timestamp obtained: $it") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get signature timestamp")
                reportException(it)
            }
            .getOrNull()
    }
    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId")
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onSuccess { Timber.tag(logTag).d("Stream URL obtained successfully") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get stream URL")
                reportException(it)
            }
            .getOrNull()
    }
}

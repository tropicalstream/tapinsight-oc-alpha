package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log

class SpotifyTool(private val context: Context) : AiTapTool {
    override val name = "spotify_player"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "play"
        val query = args["query"] ?: ""

        Log.d("SpotifyTool", "action=$action query=$query")

        // TODO: Implement Spotify Web API
        return Result.success("Spotify not yet configured. Please connect Spotify in the AITap companion app.")
    }
}

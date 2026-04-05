package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log

class TapLinkTool(private val context: Context) : AiTapTool {
    override val name = "open_taplink"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val url = args["url"] ?: ""

        Log.d("TapLinkTool", "url=$url")

        if (url.isBlank()) {
            return Result.failure(IllegalArgumentException("No URL provided"))
        }

        // The URL will be opened by the TapBrowser panel via the ViewModel
        return Result.success("taplink://$url")
    }
}

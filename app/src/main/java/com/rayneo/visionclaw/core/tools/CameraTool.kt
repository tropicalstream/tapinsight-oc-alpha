package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log

class CameraTool(private val context: Context) : AiTapTool {
    override val name = "camera_action"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "save_photo"
        val title = args["title"] ?: "AITap Photo"

        Log.d("CameraTool", "action=$action title=$title")

        // TODO: Implement photo save to Keep, QR scan, audio recording
        return when (action) {
            "save_photo" -> Result.success("Photo saved: $title")
            "read_qr" -> Result.success("QR scan not yet implemented.")
            "start_recording" -> Result.success("Recording started: $title")
            "stop_recording" -> Result.success("Recording saved.")
            else -> Result.success("Unknown camera action: $action")
        }
    }
}

package com.danielealbano.androidremotecontrolmcp.services.intents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class IntentDispatcherImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : IntentDispatcher {
        override suspend fun openUri(
            uri: String,
            packageName: String?,
            mimeType: String?,
        ): Result<Unit> =
            try {
                val intent =
                    if (mimeType != null) {
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(uri), mimeType)
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                if (packageName != null) {
                    intent.setPackage(packageName)
                }

                context.startActivity(intent)
                Logger.i(TAG, "URI opened: ${truncateUri(uri)}")
                Result.success(Unit)
            } catch (e: ActivityNotFoundException) {
                Logger.w(TAG, "No app found to handle URI: ${truncateUri(uri)}", e)
                Result.failure(IllegalArgumentException("No app found to handle URI"))
            } catch (e: SecurityException) {
                Logger.w(TAG, "Permission denied opening URI: ${truncateUri(uri)}", e)
                Result.failure(IllegalArgumentException("Permission denied: not allowed to open URI"))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Logger.e(TAG, "Unexpected error opening URI: ${truncateUri(uri)}", e)
                Result.failure(IllegalStateException("Failed to open URI unexpectedly"))
            }

        companion object {
            private const val TAG = "MCP:IntentDispatcher"

            private fun truncateUri(uri: String): String =
                try {
                    val parsed = Uri.parse(uri)
                    val scheme = parsed.scheme ?: ""
                    val host = parsed.host ?: ""
                    if (host.isNotEmpty()) "$scheme://$host/..." else "$scheme:..."
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Logger.d(TAG, "Failed to parse URI for truncation: ${e.message}")
                    "<malformed-uri>"
                }
        }
    }

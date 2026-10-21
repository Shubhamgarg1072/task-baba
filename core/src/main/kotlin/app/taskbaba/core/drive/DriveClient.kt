package app.taskbaba.core.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String = "",
    /** Drive returns sizes as decimal strings. */
    val size: String? = null,
    val sha256Checksum: String? = null,
    val md5Checksum: String? = null,
    val modifiedTime: String? = null,
    val trashed: Boolean = false,
) {
    val sizeBytes: Long? get() = size?.toLongOrNull()
    val isFolder: Boolean get() = mimeType == FOLDER_MIME

    companion object { const val FOLDER_MIME = "application/vnd.google-apps.folder" }
}

@Serializable
private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

class DriveApiException(val status: Int, message: String) : Exception(message)

/** Read-only Drive v3 metadata client. Never downloads file contents. */
class DriveClient(
    private val auth: GoogleOAuth,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val baseUrl: String = "https://www.googleapis.com/drive/v3",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listFolders(nameContains: String? = null): List<DriveFile> {
        val q = buildString {
            append("mimeType='${DriveFile.FOLDER_MIME}' and trashed=false")
            if (!nameContains.isNullOrBlank()) append(" and name contains '${escape(nameContains)}'")
        }
        return list(q, "files(id,name,mimeType,modifiedTime),nextPageToken", orderBy = "name")
    }

    suspend fun listChildren(folderId: String): List<DriveFile> =
        list(
            "'${escape(folderId)}' in parents and trashed=false",
            "files(id,name,mimeType,size,sha256Checksum,md5Checksum,modifiedTime,trashed),nextPageToken",
            orderBy = "name",
        )

    suspend fun getFile(id: String): DriveFile? {
        val url = "$baseUrl/files/${enc(id)}?fields=${enc("id,name,mimeType,trashed")}&supportsAllDrives=true"
        return try {
            json.decodeFromString(DriveFile.serializer(), get(url))
        } catch (e: DriveApiException) {
            if (e.status == 404) null else throw e
        }
    }

    private suspend fun list(q: String, fields: String, orderBy: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        var pages = 0
        do {
            val url = buildString {
                append("$baseUrl/files?q=").append(enc(q))
                append("&fields=").append(enc(fields))
                append("&orderBy=").append(enc(orderBy))
                append("&pageSize=200&spaces=drive")
                page?.let { append("&pageToken=").append(enc(it)) }
            }
            val res = json.decodeFromString(FileList.serializer(), get(url))
            out += res.files
            page = res.nextPageToken
        } while (page != null && ++pages < 20)
        return out
    }

    private suspend fun get(url: String, retried: Boolean = false): String {
        val token = auth.accessToken()
        val res = withContext(io) {
            try {
                http.sendAsync(
                    HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer $token").GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DriveAuthException(DriveAuthException.Reason.NETWORK, "Couldn't reach Google Drive. Check your connection.")
            }
        }
        return when (res.statusCode()) {
            200 -> res.body()
            401 -> if (!retried) {
                auth.invalidateAccessToken()
                get(url, retried = true)
            } else throw DriveAuthException(DriveAuthException.Reason.EXPIRED, "Google Drive access expired. Connect again.")
            403 -> throw DriveApiException(403, "Google Drive refused the request (permission or quota).")
            404 -> throw DriveApiException(404, "Not found on Google Drive.")
            else -> throw DriveApiException(res.statusCode(), "Google Drive error (HTTP ${res.statusCode()}).")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
}

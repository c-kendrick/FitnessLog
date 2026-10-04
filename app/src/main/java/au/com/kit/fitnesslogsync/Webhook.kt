package au.com.kit.fitnesslogsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class SyncProblem(val code: String, override val message: String, val retryable: Boolean = false) : IOException(message)

class Webhook(private val config: Configuration) {
    suspend fun send(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // A missing one-time response URL does not prove that the POST failed.
        // Retry the same idempotent payload to obtain a fresh response URL.
        for (attempt in 0..2) {
            try { return@withContext sendOnce(payload)
            } catch (problem: SyncProblem) {
                if (problem.code != "response_404" || attempt == 2) throw problem
                delay(1500L * (attempt + 1))
            }
        }
        error("Upload retry loop finished without a result.")
    }

    private fun sendOnce(payload: JSONObject): JSONObject {
        if (!config.ready()) throw SyncProblem("configuration", "Open the app and configure the Google connection.")
        val body = JSONObject().put("token", config.secret).put("payload", payload).toString()
        var url = config.endpoint
        var method = "POST"
        var result: JSONObject? = null
        val trace = mutableListOf<String>()
        // FL-NET-3: report only hostnames/statuses, never URLs, redirect keys or response bodies.
        fun diagnostic(message: String): String =
            "$message [FL-NET-3: ${trace.takeLast(3).joinToString("; ")}] Data uploads stay queued until confirmed."
        // Apps Script returns a redirect to a ContentService result. Do not resend the secret to it.
        for (attempt in 0..4) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 20_000; readTimeout = 40_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache, no-store")
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (method == "POST") connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val origin = URI(url).host.orEmpty().take(100)
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: run {
                        trace += "$method $origin HTTP $status (no Location)"
                        throw SyncProblem("response", diagnostic("Google returned an incomplete redirect."), true)
                    }
                    val next = try { URI(url).resolve(location) } catch (_: IllegalArgumentException) {
                        trace += "$method $origin HTTP $status (invalid Location)"
                        throw SyncProblem("response", diagnostic("Google returned an invalid redirect."), true)
                    }
                    val target = next.host.orEmpty().take(100).ifEmpty { "no hostname" }
                    trace += "$method $origin HTTP $status -> $target"
                    if (next.scheme != "https" || next.userInfo != null || next.port !in listOf(-1, 443))
                        throw SyncProblem("google_redirect", diagnostic("Google returned an unsupported redirect. It was not followed."), true)
                    if (next.host == "accounts.google.com")
                        throw SyncProblem("google_signin", diagnostic("The phone received a Google sign-in redirect. This does not identify which deployment setting caused it."))
                    // The observed response chain can return to the Apps Script host.
                    // Both are HTTPS Google service hosts; all redirect hops remain GET-only.
                    if (next.host !in setOf("script.googleusercontent.com", "script.google.com"))
                        throw SyncProblem("google_redirect", diagnostic("Google returned an unexpected redirect. It was not followed."), true)
                    if (method == "POST" && status in listOf(307, 308))
                        throw SyncProblem("response", diagnostic("Google requested a redirect that would resend the upload. The connection key was not forwarded."), true)
                    url = next.toString(); method = "GET"
                    continue
                }
                trace += "$method $origin HTTP $status"
                if (status == 404 && method == "GET")
                    throw SyncProblem("response_404", diagnostic("Google's upload response was unavailable (HTTP 404). Automatic retries will request a fresh confirmation."), true)
                if (status !in 200..299) throw SyncProblem("http_$status", diagnostic("Google returned HTTP $status."), status == 429 || status >= 500)
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                result = try { JSONObject(response) } catch (_: Exception) {
                    throw SyncProblem("google_response", diagnostic("Google returned a page instead of the expected JSON confirmation."), true)
                }
                break
            } catch (problem: SyncProblem) { throw problem
            } catch (error: IOException) {
                // The exception type distinguishes DNS failures from timeouts without logging its URL-bearing message.
                trace += "$method ${URI(url).host} (${error.javaClass.simpleName})"
                throw SyncProblem("network", diagnostic("The connection failed before Google confirmed the upload."), true)
            } finally { connection.disconnect() }
        }
        val json = result ?: throw SyncProblem("response", diagnostic("Too many Google redirects."), true)
        if (!json.optBoolean("ok")) throw SyncProblem(json.optString("code", "server"), json.optString("error", "Google rejected the upload."), json.optBoolean("retryable", false))
        if (json.optString("request_id") != payload.getString("request_id") || !json.optBoolean("verified"))
            throw SyncProblem("verification", "Google's upload confirmation did not match. The upload is retained for retry.", true)
        if (json.optInt("status_schema_version", 0) < 3)
            throw SyncProblem("server_update", "Update Code.gs from the v1.3.1 package and redeploy the existing web app as a New version. Keep the same URL and connection key.")
        return json
    }
}

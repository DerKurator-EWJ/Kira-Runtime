package de.equinox.v05

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Opt-in transport; call on a background thread, never from Android's UI thread. */
class KiraCoreBridgeClient(
    private val token: String,
    private val endpoint: String = "http://127.0.0.1:8765"
) {
    init { require(token.length >= 24) }

    fun postEvent(actor: String, kind: String, scope: String, payload: JSONObject): JSONObject {
        require(kind in setOf("observation", "expectation", "action", "outcome", "reflection"))
        require(scope in setOf("BASE", "PERSONAL", "DERIVED", "ORIGIN", "EPISODIC"))
        val event = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("timestamp", java.time.Instant.now().toString())
            .put("actor_id", actor)
            .put("kind", kind)
            .put("scope", scope)
            .put("payload", payload)
        return postExistingEvent(event)
    }

    /** Resend the identical event after a timeout for idempotent persistence. */
    fun postExistingEvent(event: JSONObject): JSONObject = request("POST", "/v1/events", event)
    fun health(): JSONObject = request("GET", "/v1/health", null)
    fun getEvent(id: String): JSONObject = request("GET", "/v1/events/" + UUID.fromString(id), null)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val connection = URL(endpoint + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            if (code !in 200..299) throw IllegalStateException("Bridge HTTP $code: $result")
            return JSONObject(result)
        } finally { connection.disconnect() }
    }
}

package com.personal.shelf

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class User(val id: Long, val name: String, val email: String, val picture: String?)
data class Rating(val average: Double, val count: Int)
data class Review(
    val id: Long,
    val appId: String,
    val rating: Int,
    val body: String,
    val versionName: String?,
    val authorName: String,
    val authorPicture: String?,
    val mine: Boolean,
    val reply: String?,
    val updatedAt: String,
)
data class AppReviews(val average: Double, val count: Int, val histogram: List<Int>, val reviews: List<Review>) {
    val mine get() = reviews.firstOrNull { it.mine }
}
data class ServerConfig(val devLogin: Boolean, val googleSignIn: Boolean)

class ApiException(message: String, val status: Int) : Exception(message)

/** Talks to the Shelf review server (shelf/server). */
object ReviewsApi {
    fun enabled(ctx: Context) = Prefs.reviewsUrl(ctx).isNotBlank()

    private suspend fun call(ctx: Context, method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val c = URL(Prefs.reviewsUrl(ctx).trimEnd('/') + path).openConnection() as HttpURLConnection
            // The free server sleeps when idle and takes up to a minute to wake up.
            c.connectTimeout = 30000
            c.readTimeout = 70000
            c.requestMethod = method
            c.setRequestProperty("Accept", "application/json")
            Account.token(ctx)?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            try {
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (code !in 200..299) {
                    if (code == 401) Account.clear(ctx)
                    throw ApiException(json.optString("error").ifBlank { "Server error ($code)" }, code)
                }
                json
            } finally {
                c.disconnect()
            }
        }

    suspend fun config(ctx: Context) = call(ctx, "GET", "/api/config").let {
        ServerConfig(it.optBoolean("devLogin"), it.optBoolean("googleSignIn"))
    }

    suspend fun ratings(ctx: Context): Map<String, Rating> {
        val json = call(ctx, "GET", "/api/ratings")
        Prefs.setCachedRatings(ctx, json.toString())
        return parseRatings(json)
    }

    fun cachedRatings(ctx: Context): Map<String, Rating> =
        Prefs.cachedRatings(ctx)?.let { runCatching { parseRatings(JSONObject(it)) }.getOrNull() } ?: emptyMap()

    private fun parseRatings(json: JSONObject): Map<String, Rating> {
        val apps = json.optJSONObject("apps") ?: return emptyMap()
        return apps.keys().asSequence().associateWith {
            val o = apps.getJSONObject(it)
            Rating(o.optDouble("average"), o.optInt("count"))
        }
    }

    suspend fun reviews(ctx: Context, appId: String): AppReviews {
        val j = call(ctx, "GET", "/api/apps/$appId/reviews")
        val h = j.optJSONArray("histogram") ?: JSONArray()
        return AppReviews(j.optDouble("average"), j.optInt("count"), (0 until h.length()).map { h.getInt(it) },
            parseReviews(j.optJSONArray("reviews")))
    }

    suspend fun saveReview(ctx: Context, appId: String, rating: Int, body: String, installed: Packages.Installed?) {
        call(ctx, "PUT", "/api/apps/$appId/review", JSONObject()
            .put("rating", rating)
            .put("body", body)
            .put("versionName", installed?.versionName)
            .put("versionCode", installed?.versionCode)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"))
    }

    suspend fun deleteReview(ctx: Context, appId: String) { call(ctx, "DELETE", "/api/apps/$appId/review") }

    suspend fun myReviews(ctx: Context): List<Review> {
        val j = call(ctx, "GET", "/api/me")
        Account.save(ctx, null, parseUser(j.getJSONObject("user")))
        return parseReviews(j.optJSONArray("reviews"))
    }

    suspend fun signInWithGoogle(activity: Activity) {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return
        }
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw ApiException("Google didn't return an account", 0)
        }
        val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
        finishSignIn(activity, call(activity, "POST", "/api/auth/google", JSONObject().put("idToken", idToken)))
    }

    /** Only works against a local server started with DEV_LOGIN=1. */
    suspend fun signInDev(ctx: Context) =
        finishSignIn(ctx, call(ctx, "POST", "/api/auth/dev", JSONObject().put("name", "Test User").put("email", "test@example.com")))

    private fun finishSignIn(ctx: Context, j: JSONObject) =
        Account.save(ctx, j.getString("token"), parseUser(j.getJSONObject("user")))

    suspend fun signOut(ctx: Context) {
        runCatching { call(ctx, "POST", "/api/auth/logout") }
        Account.clear(ctx)
        runCatching { CredentialManager.create(ctx).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
    }

    suspend fun deleteAccount(ctx: Context) {
        call(ctx, "DELETE", "/api/me")
        Account.clear(ctx)
    }

    private fun parseUser(o: JSONObject) =
        User(o.getLong("id"), o.getString("name"), o.optString("email"), o.optString("picture").takeIf { it.isNotBlank() && it != "null" })

    private fun parseReviews(a: JSONArray?): List<Review> = if (a == null) emptyList() else (0 until a.length()).map {
        val o = a.getJSONObject(it)
        val author = o.getJSONObject("author")
        Review(
            id = o.getLong("id"),
            appId = o.getString("appId"),
            rating = o.getInt("rating"),
            body = o.optString("body"),
            versionName = o.optString("versionName").takeIf { v -> v.isNotBlank() && v != "null" },
            authorName = author.optString("name"),
            authorPicture = author.optString("picture").takeIf { v -> v.isNotBlank() && v != "null" },
            mine = o.optBoolean("mine"),
            reply = o.optString("reply").takeIf { v -> v.isNotBlank() && v != "null" },
            updatedAt = o.optString("updatedAt"),
        )
    }
}

/** The signed-in Shelf profile, kept in preferences. */
object Account {
    val user = MutableStateFlow<User?>(null)

    fun load(ctx: Context) {
        user.value = Prefs.userJson(ctx)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                User(o.getLong("id"), o.getString("name"), o.optString("email"), o.optString("picture").takeIf { it.isNotBlank() })
            }.getOrNull()
        }
    }

    fun token(ctx: Context): String? = Prefs.sessionToken(ctx)

    fun save(ctx: Context, token: String?, u: User) {
        if (token != null) Prefs.setSessionToken(ctx, token)
        Prefs.setUserJson(ctx, JSONObject().put("id", u.id).put("name", u.name).put("email", u.email)
            .put("picture", u.picture ?: "").toString())
        user.value = u
    }

    fun clear(ctx: Context) {
        Prefs.setSessionToken(ctx, null)
        Prefs.setUserJson(ctx, null)
        user.value = null
    }
}

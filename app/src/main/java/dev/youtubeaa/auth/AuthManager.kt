package dev.youtubeaa.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class AuthManager(private val context: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()

    // YouTube TV app credentials — public, used by yt-dlp and open-source clients
    // https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube.py
    private val clientId = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private val clientSecret = "SboVhoG9s0rNafixCSGGKXAT"

    private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    private val KEY_TOKEN_EXPIRY = stringPreferencesKey("token_expiry")

    data class DeviceCodeResponse(
        @SerializedName("device_code") val deviceCode: String,
        @SerializedName("user_code") val userCode: String,
        @SerializedName("verification_url") val verificationUrl: String,
        @SerializedName("expires_in") val expiresIn: Int,
        @SerializedName("interval") val interval: Int
    )

    data class TokenResponse(
        @SerializedName("access_token") val accessToken: String? = null,
        @SerializedName("refresh_token") val refreshToken: String? = null,
        @SerializedName("expires_in") val expiresIn: Int? = null,
        @SerializedName("error") val error: String? = null
    )

    // Step 1: request a device code — user goes to youtube.com/activate and enters userCode
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "https://www.googleapis.com/auth/youtube")
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/device/code")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val json = response.body?.string() ?: error("Empty response from device code endpoint")
        gson.fromJson(json, DeviceCodeResponse::class.java)
    }

    // Step 2: poll until user approves on their phone/PC
    suspend fun pollForToken(deviceCode: String): TokenResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val json = response.body?.string() ?: return@withContext TokenResponse(error = "empty")
        gson.fromJson(json, TokenResponse::class.java)
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = accessToken
            prefs[KEY_REFRESH_TOKEN] = refreshToken
            prefs[KEY_TOKEN_EXPIRY] = (System.currentTimeMillis() + expiresIn * 1000L).toString()
        }
    }

    // Returns a valid access token, refreshing if expired
    suspend fun getValidAccessToken(): String? {
        val expiry = context.dataStore.data
            .map { it[KEY_TOKEN_EXPIRY]?.toLongOrNull() ?: 0L }.first()
        val stored = context.dataStore.data
            .map { it[KEY_ACCESS_TOKEN] }.first()

        return if (stored != null && System.currentTimeMillis() < expiry - 60_000) {
            stored
        } else {
            refreshAccessToken()
        }
    }

    private suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val refreshToken = context.dataStore.data
            .map { it[KEY_REFRESH_TOKEN] }.first() ?: return@withContext null

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val json = response.body?.string() ?: return@withContext null
        val token = gson.fromJson(json, TokenResponse::class.java)

        token.accessToken?.let { newToken ->
            context.dataStore.edit { prefs ->
                prefs[KEY_ACCESS_TOKEN] = newToken
                prefs[KEY_TOKEN_EXPIRY] = (System.currentTimeMillis() + (token.expiresIn ?: 3600) * 1000L).toString()
            }
        }

        token.accessToken
    }

    suspend fun isLoggedIn(): Boolean =
        context.dataStore.data.map { it[KEY_REFRESH_TOKEN] != null }.first()

    suspend fun logout() {
        context.dataStore.edit { it.clear() }
    }
}

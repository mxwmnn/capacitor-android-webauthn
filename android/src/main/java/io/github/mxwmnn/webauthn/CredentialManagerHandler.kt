package io.github.mxwmnn.webauthn

import android.app.Activity
import android.util.Log
import androidx.credentials.*
import androidx.credentials.exceptions.*
import org.json.JSONObject

/**
 * A class that encapsulates the credential manager object and provides simplified APIs for
 * creating and retrieving public key credentials.
 * 
 * Based on Google's official sample from:
 * https://github.com/android/identity-samples/tree/main/WebView/CredentialManagerWebView
 */
class CredentialManagerHandler(private val activity: Activity) {

    private val mCredMan = CredentialManager.create(activity.applicationContext)
    private val TAG = "CredentialManagerHandler"

    /**
     * Encapsulates the create passkey API for credential manager in a less error-prone manner.
     *
     * @param request a create public key credential request JSON required by [CreatePublicKeyCredentialRequest].
     * @return [CreatePublicKeyCredentialResponse] containing the result of the credential creation.
     */
    suspend fun createPasskey(request: String): CreatePublicKeyCredentialResponse {
        val normalizedRequest = normalizeRequest(request, CREATE_ALLOWED_KEYS, "create")
        val createRequest = CreatePublicKeyCredentialRequest(normalizedRequest)
        try {
            return mCredMan.createCredential(activity, createRequest) as CreatePublicKeyCredentialResponse
        } catch (e: CreateCredentialException) {
            Log.i(TAG, "Error creating credential: ErrMessage: ${e.errorMessage}, ErrType: ${e.type}")
            throw e
        }
    }

    /**
     * Encapsulates the get passkey API for credential manager in a less error-prone manner.
     *
     * @param request a get public key credential request JSON required by [GetCredentialRequest].
     * @return [GetCredentialResponse] containing the result of the credential retrieval.
     */
    suspend fun getPasskey(request: String): GetCredentialResponse {
        val normalizedRequest = normalizeRequest(request, GET_ALLOWED_KEYS, "get")
        val getRequest = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(normalizedRequest, null)))
        try {
            return mCredMan.getCredential(activity, getRequest)
        } catch (e: GetCredentialException) {
            Log.i(TAG, "Error retrieving credential: ${e.message} type=${e.type}")
            throw e
        }
    }

    private fun normalizeRequest(request: String, allowedKeys: Set<String>, flowType: String): String {
        return try {
            val requestJson = JSONObject(request)
            val normalizedJson = JSONObject()
            val droppedKeys = mutableListOf<String>()

            val keys = requestJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (allowedKeys.contains(key)) {
                    normalizedJson.put(key, requestJson.get(key))
                } else {
                    droppedKeys.add(key)
                }
            }

            if (droppedKeys.isNotEmpty()) {
                Log.i(TAG, "Dropping unsupported $flowType request keys: $droppedKeys")
            }

            if (normalizedJson.length() == 0) {
                Log.w(TAG, "Normalized $flowType request is empty, using original payload")
                request
            } else {
                normalizedJson.toString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to normalize $flowType request payload: ${t.message}")
            request
        }
    }

    companion object {
        private val GET_ALLOWED_KEYS = setOf(
            "challenge",
            "timeout",
            "rpId",
            "allowCredentials",
            "userVerification",
            "extensions"
        )

        private val CREATE_ALLOWED_KEYS = setOf(
            "rp",
            "user",
            "challenge",
            "pubKeyCredParams",
            "timeout",
            "excludeCredentials",
            "authenticatorSelection",
            "attestation",
            "extensions"
        )
    }
}

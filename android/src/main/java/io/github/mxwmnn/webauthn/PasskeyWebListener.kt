package io.github.mxwmnn.webauthn

import android.app.Activity
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * PasskeyWebListener handles communication between the WebView and native Android code.
 * 
 * Based on Google's official sample from:
 * https://github.com/android/identity-samples/tree/main/WebView/CredentialManagerWebView
 *
 * This web listener looks for the 'postMessage()' call on the JavaScript web code, and when it
 * receives it, it will handle it in the manner dictated in this local codebase. This allows for
 * JavaScript on the web to interact with the local setup on device that contains more complex logic.
 *
 * The embedded JavaScript can be found in CredentialManagerWebView/javascript/encode.js.
 */
class PasskeyWebListener(
    private val activity: Activity,
    private val coroutineScope: CoroutineScope,
    private val credentialManagerHandler: CredentialManagerHandler
) : WebViewCompat.WebMessageListener {

    /** havePendingRequest is true if there is an outstanding WebAuthn request. There is only ever
    one request outstanding at a time.*/
    private var havePendingRequest = false

    /** pendingRequestIsDoomed is true if the WebView has navigated since starting a request. The
    fido module cannot be cancelled, but the response will never be delivered in this case.*/
    private var pendingRequestIsDoomed = false

    /** replyChannel is the port that the page is listening for a response on. It
    is valid if `havePendingRequest` is true.*/
    private var replyChannel: ReplyChannel? = null

    private var activeRequestId = 0L
    private var requestSequence = 0L
    private var pendingRequestType: String? = null
    private var pendingRequestPayloadHash: Int? = null
    private val pendingDuplicateReplies = mutableListOf<ReplyChannel>()

    /** Called by the page when it wants to do a WebAuthn `get` or 'post' request. */
    @UiThread
    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        Log.i(TAG, "In Post Message : $message source: $sourceOrigin")
        val messageData = message.data ?: return
        onRequest(messageData, sourceOrigin, isMainFrame, JavaScriptReplyChannel(replyProxy))
    }

    private fun onRequest(
        msg: String,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        reply: ReplyChannel,
    ) {
        val jsonObj = try {
            JSONObject(msg)
        } catch (t: Throwable) {
            Log.i(TAG, "Invalid request payload: ${t.message}")
            postErrorMessage(reply, "Invalid request payload", UNKNOWN_TYPE)
            return
        }

        val type = jsonObj.optString(TYPE_KEY, UNKNOWN_TYPE)
        val message = jsonObj.optString(REQUEST_KEY, "")
        if (message.isEmpty()) {
            postErrorMessage(reply, "request payload is missing", type)
            return
        }

        if (havePendingRequest) {
            if (isDuplicateInFlightRequest(type, message)) {
                if (pendingDuplicateReplies.size >= MAX_DUPLICATE_PENDING_REPLIES) {
                    postErrorMessage(reply, "too many duplicate requests while request in progress", type)
                } else {
                    pendingDuplicateReplies.add(reply)
                    Log.i(TAG, "Queued duplicate request for requestId=$activeRequestId type=$type")
                }
            } else {
                postErrorMessage(reply, "request already in progress", type)
            }
            return
        }

        if (!isMainFrame) {
            postErrorMessage(reply, "requests from subframes are not supported", type)
            return
        }

        val originScheme = sourceOrigin.scheme
        if (originScheme == null || originScheme.lowercase() != "https") {
            postErrorMessage(reply, "WebAuthn not permitted for current URL", type)
            return
        }

        val requestId = beginPendingRequest(type, message, reply)
        Log.i(TAG, "Starting requestId=$requestId type=$type")
        when (type) {
            CREATE_UNIQUE_KEY ->
                this.coroutineScope.launch {
                    handleCreateFlow(credentialManagerHandler, message, reply, requestId)
                }

            GET_UNIQUE_KEY -> this.coroutineScope.launch {
                handleGetFlow(credentialManagerHandler, message, reply, requestId)
            }

            else -> {
                postErrorMessage(reply, "Incorrect request json", type)
                clearPendingRequestState(requestId)
            }
        }
    }

    // Handles the get flow in a less error-prone way
    private suspend fun handleGetFlow(
        credentialManagerHandler: CredentialManagerHandler,
        message: String,
        reply: ReplyChannel,
        requestId: Long,
    ) {
        try {
            val r = credentialManagerHandler.getPasskey(message)
            if (!isRequestStillValid(requestId)) {
                Log.i(TAG, "Dropping stale get response for requestId=$requestId")
                return
            }
            val successArray = ArrayList<Any>()
            successArray.add("success")
            successArray.add(JSONObject(
                (r.credential as PublicKeyCredential).authenticationResponseJson))
            successArray.add(GET_UNIQUE_KEY)
            sendMessageToAllReplies(reply, JSONArray(successArray).toString(), requestId)
        } catch (e: GetCredentialException) {
            if (isRequestStillValid(requestId)) {
                postErrorMessageToAllReplies(reply, "Error: ${e.errorMessage} w type: ${e.type} w obj: $e",
                    GET_UNIQUE_KEY)
            } else {
                Log.i(TAG, "Ignoring get error for stale requestId=$requestId")
            }
        } catch (t: Throwable) {
            if (isRequestStillValid(requestId)) {
                postErrorMessageToAllReplies(reply, "Error: ${t.message}", GET_UNIQUE_KEY)
            } else {
                Log.i(TAG, "Ignoring get throwable for stale requestId=$requestId")
            }
        } finally {
            Log.i(TAG, "Finishing requestId=$requestId type=$GET_UNIQUE_KEY")
            clearPendingRequestState(requestId)
        }
    }

    // handles the create flow in a less error prone way
    private suspend fun handleCreateFlow(
        credentialManagerHandler: CredentialManagerHandler,
        message: String,
        reply: ReplyChannel,
        requestId: Long,
    ) {
        try {
            val response = credentialManagerHandler.createPasskey(message)
            if (!isRequestStillValid(requestId)) {
                Log.i(TAG, "Dropping stale create response for requestId=$requestId")
                return
            }
            val successArray = ArrayList<Any>()
            successArray.add("success")
            successArray.add(JSONObject(response.registrationResponseJson))
            successArray.add(CREATE_UNIQUE_KEY)
            sendMessageToAllReplies(reply, JSONArray(successArray).toString(), requestId)
        } catch (e: CreateCredentialException) {
            if (isRequestStillValid(requestId)) {
                postErrorMessageToAllReplies(reply, "Error: ${e.errorMessage} w type: ${e.type} w obj: $e",
                    CREATE_UNIQUE_KEY)
            } else {
                Log.i(TAG, "Ignoring create error for stale requestId=$requestId")
            }
        } catch (t: Throwable) {
            if (isRequestStillValid(requestId)) {
                postErrorMessageToAllReplies(reply, "Error: ${t.message}", CREATE_UNIQUE_KEY)
            } else {
                Log.i(TAG, "Ignoring create throwable for stale requestId=$requestId")
            }
        } finally {
            Log.i(TAG, "Finishing requestId=$requestId type=$CREATE_UNIQUE_KEY")
            clearPendingRequestState(requestId)
        }
    }

    /** Invalidates any current request.  */
    fun onPageStarted() {
        if (havePendingRequest) {
            pendingRequestIsDoomed = true
        }
    }

    private fun beginPendingRequest(type: String, payload: String, reply: ReplyChannel): Long {
        requestSequence += 1
        val requestId = requestSequence

        havePendingRequest = true
        pendingRequestIsDoomed = false
        activeRequestId = requestId
        replyChannel = reply
        pendingRequestType = type
        pendingRequestPayloadHash = payload.hashCode()
        pendingDuplicateReplies.clear()

        return requestId
    }

    private fun isDuplicateInFlightRequest(type: String, payload: String): Boolean {
        if (!havePendingRequest) {
            return false
        }

        return pendingRequestType == type && pendingRequestPayloadHash == payload.hashCode()
    }

    private fun isRequestStillValid(requestId: Long): Boolean {
        return havePendingRequest && activeRequestId == requestId && !pendingRequestIsDoomed
    }

    private fun clearPendingRequestState(requestId: Long) {
        if (activeRequestId != requestId) {
            return
        }

        clearPendingRequestState()
    }

    private fun clearPendingRequestState() {
        havePendingRequest = false
        pendingRequestIsDoomed = false
        activeRequestId = 0L
        replyChannel = null
        pendingRequestType = null
        pendingRequestPayloadHash = null
        pendingDuplicateReplies.clear()
    }

    private fun sendMessageToAllReplies(primaryReply: ReplyChannel, message: String,
                                        requestId: Long) {
        if (!isRequestStillValid(requestId)) {
            return
        }

        primaryReply.send(message)
        if (pendingDuplicateReplies.isNotEmpty()) {
            Log.i(TAG, "Sending result to ${pendingDuplicateReplies.size} duplicate requests")
            pendingDuplicateReplies.forEach { duplicateReply ->
                duplicateReply.send(message)
            }
        }
    }

    private fun postErrorMessageToAllReplies(primaryReply: ReplyChannel, errorMessage: String,
                                             type: String) {
        postErrorMessage(primaryReply, errorMessage, type)
        if (pendingDuplicateReplies.isNotEmpty()) {
            Log.i(TAG, "Sending error to ${pendingDuplicateReplies.size} duplicate requests")
            pendingDuplicateReplies.forEach { duplicateReply ->
                postErrorMessage(duplicateReply, errorMessage, type)
            }
        }
    }

    private fun postErrorMessage(reply: ReplyChannel, errorMessage: String, type: String) {
        Log.i(TAG, "Sending error message back to the page via replyChannel $errorMessage")
        val array: MutableList<Any?> = ArrayList()
        array.add("error")
        array.add(errorMessage)
        array.add(type)
        reply.send(JSONArray(array).toString())
    }

    private class JavaScriptReplyChannel(private val reply: JavaScriptReplyProxy) :
        ReplyChannel {
        override fun send(message: String?) {
            try {
                reply.postMessage(message!!)
            } catch (t: Throwable) {
                Log.i(TAG, "Reply failure due to: " + t.message)
            }
        }
    }

    /** ReplyChannel is the interface over which replies to the embedded site are sent. This allows
    for testing because AndroidX bans mocking its objects.*/
    interface ReplyChannel {
        fun send(message: String?)
    }

    companion object {
        /** INTERFACE_NAME is the name of the MessagePort that must be injected into pages. */
        const val INTERFACE_NAME = "__webauthn_interface__"

        const val CREATE_UNIQUE_KEY = "create"
        const val GET_UNIQUE_KEY = "get"
        const val TYPE_KEY = "type"
        const val REQUEST_KEY = "request"
        const val UNKNOWN_TYPE = "unknown"
        const val MAX_DUPLICATE_PENDING_REPLIES = 8

        /** INJECTED_VAL is the minified version of the JavaScript code described at this class
         * heading. The non minified form is found at ./javascript/encode.js.*/
        const val INJECTED_VAL = """
            var __webauthn_interface__,__webauthn_hooks__;!function(e){console.log("In the hook."),__webauthn_interface__.addEventListener("message",function e(n){var r=JSON.parse(n.data),t=r[2];"get"===t?o(r):"create"===t?u(r):console.log("Incorrect response format for reply")});var n=null,r=null,t=null,a=null;function o(e){if(null!==n&&null!==t){if("success"!=e[0]){var r=t;n=null,t=null,r(new DOMException(e[1],"NotAllowedError"));return}var a=i(e[1]),o=n;n=null,t=null,o(a)}}function l(e){var n=e.length%4;return Uint8Array.from(atob(e.replace(/-/g,"+").replace(/_/g,"/").padEnd(e.length+(0===n?0:4-n),"=")),function(e){return e.charCodeAt(0)}).buffer}function s(e){return btoa(Array.from(new Uint8Array(e),function(e){return String.fromCharCode(e)}).join("")).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+${'$'}/,"")}function u(e){if(null===r||null===a){console.log("Here: "+r+" and reject: "+a);return}if(console.log("Output back: "+e),"success"!=e[0]){var n=a;r=null,a=null,n(new DOMException(e[1],"NotAllowedError"));return}var t=i(e[1]),o=r;r=null,a=null,o(t)}function i(e){return console.log("Here is the response from credential manager: "+e),e.rawId=l(e.rawId),e.response.clientDataJSON=l(e.response.clientDataJSON),e.response.hasOwnProperty("attestationObject")&&(e.response.attestationObject=l(e.response.attestationObject)),e.response.hasOwnProperty("authenticatorData")&&(e.response.authenticatorData=l(e.response.authenticatorData)),e.response.hasOwnProperty("signature")&&(e.response.signature=l(e.response.signature)),e.response.hasOwnProperty("userHandle")&&(e.response.userHandle=l(e.response.userHandle)),e.getClientExtensionResults=function e(){return{}},e}e.create=function n(t){if(!("publicKey"in t))return e.originalCreateFunction(t);var o=new Promise(function(e,n){r=e,a=n}),l=t.publicKey;if(l.hasOwnProperty("challenge")){var u=s(l.challenge);l.challenge=u}if(l.hasOwnProperty("user")&&l.user.hasOwnProperty("id")){var i=s(l.user.id);l.user.id=i}var c=JSON.stringify({type:"create",request:l});return __webauthn_interface__.postMessage(c),o},e.get=function r(a){if(!("publicKey"in a))return e.originalGetFunction(a);var o=new Promise(function(e,r){n=e,t=r}),l=a.publicKey;if(l.hasOwnProperty("challenge")){var u=s(l.challenge);l.challenge=u}var i=JSON.stringify({type:"get",request:l});return __webauthn_interface__.postMessage(i),o},e.onReplyGet=o,e.CM_base64url_decode=l,e.CM_base64url_encode=s,e.onReplyCreate=u}(__webauthn_hooks__||(__webauthn_hooks__={})),__webauthn_hooks__.originalGetFunction=navigator.credentials.get,__webauthn_hooks__.originalCreateFunction=navigator.credentials.create,navigator.credentials.get=__webauthn_hooks__.get,navigator.credentials.create=__webauthn_hooks__.create,window.PublicKeyCredential=function(){},window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable=function(){return Promise.resolve(!1)};
        """
        const val TAG = "PasskeyWebListener"
    }
}

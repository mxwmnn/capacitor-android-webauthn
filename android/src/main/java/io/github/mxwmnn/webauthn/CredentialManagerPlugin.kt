package io.github.mxwmnn.webauthn

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * CredentialManagerPlugin is the main Capacitor plugin entry point.
 * It automatically initializes on app start and injects JavaScript into the WebView
 * to polyfill navigator.credentials API with Android's Credential Manager.
 *
 * This plugin requires ZERO modifications to MainActivity - it's completely self-contained.
 *
 * Based on Google's official sample from:
 * https://github.com/android/identity-samples/tree/main/WebView/CredentialManagerWebView
 */
@CapacitorPlugin(name = "CredentialManager")
class CredentialManagerPlugin : Plugin() {

    private val TAG = "CredentialManagerPlugin"

    private lateinit var credentialManagerHandler: CredentialManagerHandler
    private lateinit var passkeyWebListener: PasskeyWebListener
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Called when the plugin is first loaded.
     * This is where we auto-initialize and hook into the WebView.
     */
    override fun load() {
        Log.i(TAG, "CredentialManagerPlugin loading...")

        try {
            // Initialize the Credential Manager handler (requires Activity for UI dialogs)
            credentialManagerHandler = CredentialManagerHandler(activity)

            // Initialize the WebView listener with coroutine scope
            passkeyWebListener = PasskeyWebListener(activity, coroutineScope, credentialManagerHandler)

            // Hook into Capacitor's WebView
            val webView = bridge.webView
            setupWebViewInjection(webView)

            // Listen for page load events from Capacitor bridge
            bridge.webView.post {
                Log.i(TAG, "Setting up page load listeners...")
                // Inject after a short delay to ensure page is ready
                bridge.webView.postDelayed({
                    injectPolyfillIntoWebView()
                }, 500)
            }

            Log.i(TAG, "CredentialManagerPlugin loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load CredentialManagerPlugin", e)
        }
    }

    /**
     * Set up JavaScript injection and WebMessageListener
     */
    private fun setupWebViewInjection(webView: WebView) {
        Log.d(TAG, "Setting up WebView injection...")

        // Set up WebMessageListener for bidirectional communication
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(
                    webView,
                    PasskeyWebListener.INTERFACE_NAME,
                    setOf("*"),  // Allow all origins
                    passkeyWebListener
                )
                Log.i(TAG, "WebMessageListener registered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register WebMessageListener", e)
            }
        } else {
            Log.w(TAG, "WebMessageListener not supported on this device")
        }

        Log.i(TAG, "WebView injection setup complete")
    }

    /**
     * Inject the polyfill into the WebView
     */
    private fun injectPolyfillIntoWebView() {
        val webView = bridge.webView
        if (webView == null) {
            Log.e(TAG, "WebView not available for polyfill injection")
            return
        }

        try {
            Log.i(TAG, "Injecting WebAuthn polyfill into WebView...")
            webView.evaluateJavascript(PasskeyWebListener.INJECTED_VAL) { result ->
                Log.i(TAG, "Polyfill injection completed with result: $result")

                // Verify injection worked
                webView.evaluateJavascript(
                    "(function() { return typeof window.__webauthn_interface__ !== 'undefined'; })()"
                ) { verified ->
                    Log.i(TAG, "Polyfill verification: __webauthn_interface__ exists = $verified")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject polyfill", e)
        }
    }

    /**
     * Echo method for testing plugin communication
     */
    @PluginMethod
    fun echo(call: PluginCall) {
        val value = call.getString("value")

        val ret = JSObject()
        ret.put("value", value)
        call.resolve(ret)
    }

    /**
     * Manual injection trigger for debugging
     */
    @PluginMethod
    fun injectPolyfill(call: PluginCall) {
        try {
            Log.i(TAG, "Manual polyfill injection requested")
            injectPolyfillIntoWebView()

            val ret = JSObject()
            ret.put("success", true)
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject polyfill manually", e)
            call.reject("Failed to inject polyfill", e)
        }
    }

    /**
     * Check if the polyfill is injected by verifying __webauthn_interface__ exists
     */
    @PluginMethod
    fun checkInjection(call: PluginCall) {
        val webView = bridge.webView
        if (webView == null) {
            Log.e(TAG, "WebView not available for polyfill check")
            call.reject("WebView not available")
            return
        }

        try {
            webView.evaluateJavascript(
                "(function() { return typeof window.__webauthn_interface__ !== 'undefined'; })()"
            ) { result ->
                Log.i(TAG, "Polyfill check result: $result")
                val ret = JSObject()
                ret.put("isInjected", result == "true")
                ret.put("rawResult", result)
                call.resolve(ret)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check injection", e)
            call.reject("Failed to check injection", e)
        }
    }
}

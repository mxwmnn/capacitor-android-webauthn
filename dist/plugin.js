var capacitorCredentialManager = (function (exports, core) {
    'use strict';

    const CredentialManager = core.registerPlugin('CredentialManager', {
        web: () => Promise.resolve().then(function () { return web; }).then(m => new m.CredentialManagerWeb()),
    });
    // Plugin automatically initializes on Android - no manual setup needed
    // The native code will inject JavaScript into the WebView to polyfill navigator.credentials

    class CredentialManagerWeb extends core.WebPlugin {
        async echo(options) {
            console.log('ECHO', options);
            return options;
        }
        async injectPolyfill() {
            // On web, the native WebAuthn API is already available, no polyfill needed
            console.log('CredentialManager: No polyfill injection needed on web platform');
            return { success: true };
        }
        async checkInjection() {
            // On web, check if the native WebAuthn API is available
            const isAvailable = typeof window.PublicKeyCredential !== 'undefined';
            return {
                isInjected: isAvailable,
                rawResult: isAvailable ? 'native WebAuthn API available' : 'WebAuthn not available'
            };
        }
    }

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        CredentialManagerWeb: CredentialManagerWeb
    });

    exports.CredentialManager = CredentialManager;

    return exports;

})({}, capacitorExports);
//# sourceMappingURL=plugin.js.map

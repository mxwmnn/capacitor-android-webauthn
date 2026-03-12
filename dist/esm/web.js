import { WebPlugin } from '@capacitor/core';
export class CredentialManagerWeb extends WebPlugin {
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

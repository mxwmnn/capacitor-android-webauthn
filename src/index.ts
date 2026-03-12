import { registerPlugin } from '@capacitor/core';

import type { CredentialManagerPlugin } from './definitions';

const CredentialManager = registerPlugin<CredentialManagerPlugin>('CredentialManager', {
  web: () => import('./web').then(m => new m.CredentialManagerWeb()),
});

export * from './definitions';
export { CredentialManager };

// Plugin automatically initializes on Android - no manual setup needed
// The native code will inject JavaScript into the WebView to polyfill navigator.credentials


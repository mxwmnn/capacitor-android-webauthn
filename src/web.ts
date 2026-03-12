import { WebPlugin } from '@capacitor/core';

import type { CredentialManagerPlugin } from './definitions';

export class CredentialManagerWeb extends WebPlugin implements CredentialManagerPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }

  async injectPolyfill(): Promise<{ success: boolean }> {
    // On web, the native WebAuthn API is already available, no polyfill needed
    console.log('CredentialManager: No polyfill injection needed on web platform');
    return { success: true };
  }

  async checkInjection(): Promise<{ isInjected: boolean; rawResult: string }> {
    // On web, check if the native WebAuthn API is available
    const isAvailable = typeof window.PublicKeyCredential !== 'undefined';
    return { 
      isInjected: isAvailable, 
      rawResult: isAvailable ? 'native WebAuthn API available' : 'WebAuthn not available' 
    };
  }
}


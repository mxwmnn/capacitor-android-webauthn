export interface CredentialManagerPlugin {
  /**
   * Echo a value back (for testing plugin communication)
   */
  echo(options: { value: string }): Promise<{ value: string }>;

  /**
   * Manually inject the WebAuthn polyfill into the current page
   * Useful for debugging or re-injecting after navigation
   */
  injectPolyfill(): Promise<{ success: boolean }>;

  /**
   * Check if the polyfill has been successfully injected
   * Returns true if __webauthn_interface__ exists in the window
   */
  checkInjection(): Promise<{ isInjected: boolean; rawResult: string }>;
}

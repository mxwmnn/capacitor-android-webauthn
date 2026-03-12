# Capacitor Android WebAuthn

`@mxwmnn/capacitor-android-webauthn`

A Capacitor plugin that polyfills the WebAuthn `navigator.credentials` API on Android using Android Credential Manager. This lets passkeys work in Capacitor WebViews on Android 14+ or Android 9+ with Play Services.

## Based On

This implementation is based on Google's official sample:
https://github.com/android/identity-samples/tree/main/WebView/CredentialManagerWebView

## How It Works

1. **Auto-initialization**: The plugin automatically loads when your app starts
2. **JavaScript Injection**: Injects a polyfill that intercepts `navigator.credentials.create()` and `navigator.credentials.get()` calls
3. **Native Bridge**: Routes WebAuthn requests through Android's Credential Manager API
4. **Zero Configuration**: No changes needed to MainActivity or your app code

## Architecture

```
┌─────────────────────────────────────┐
│     JavaScript (WebView)            │
│  navigator.credentials.create()    │
│  navigator.credentials.get()       │
└──────────────┬──────────────────────┘
               │ (polyfilled by encode.js)
               ▼
┌─────────────────────────────────────┐
│  PasskeyWebListener (Kotlin)        │
│  - Receives postMessage from JS     │
│  - Validates origin & request       │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  CredentialManagerHandler (Kotlin)  │
│  - Calls Android Credential Manager │
│  - Returns result to WebView        │
└─────────────────────────────────────┘
```

## Files

### Android Native Code
  - `android/src/main/java/io/github/mxwmnn/webauthn/`
  - `CredentialManagerPlugin.kt` - Main Capacitor plugin entry point
  - `PasskeyWebListener.kt` - Handles WebView message communication
  - `CredentialManagerHandler.kt` - Wraps Android Credential Manager API
  - `ReplyChannel.kt` - Interface for sending messages back to WebView

### JavaScript
- `javascript/encode.js` - Non-minified polyfill (reference only)
  - This gets minified and embedded in `PasskeyWebListener.INJECTED_VAL`
  - Intercepts `navigator.credentials` calls
  - Communicates with native code via `postMessage`

### TypeScript
- `src/definitions.ts` - TypeScript interfaces
- `src/index.ts` - Main export
- `src/web.ts` - Web platform stub (no-op, uses native WebAuthn)

## Installation

This plugin targets Capacitor v8 and Android only.

```bash
npm install @mxwmnn/capacitor-android-webauthn
```

Or with pnpm:

```bash
pnpm i @mxwmnn/capacitor-android-webauthn
```

Or with Bun:

```bash
bun add @mxwmnn/capacitor-android-webauthn
```

## Development

This repository uses Bun for local development workflows.

Install dependencies:

```bash
bun install
```

Build the plugin:

```bash
bun run build
```

Run Android verification:

```bash
bun run verify
```

`bun run verify` uses the Gradle wrapper checked into `android/`, so it does not depend on a system Gradle installation.

## Usage

### Basic Usage (Automatic)

The plugin works automatically! Just use the standard WebAuthn API:

```typescript
// Create a passkey (registration)
const credential = await navigator.credentials.create({
  publicKey: {
    challenge: new Uint8Array([/* challenge bytes */]),
    rp: { name: "My App", id: "example.com" },
    user: {
      id: new Uint8Array([/* user id */]),
      name: "user@example.com",
      displayName: "User"
    },
    pubKeyCredParams: [{ type: "public-key", alg: -7 }],
    authenticatorSelection: {
      authenticatorAttachment: "platform",
      userVerification: "required"
    }
  }
});

// Authenticate with a passkey
const credential = await navigator.credentials.get({
  publicKey: {
    challenge: new Uint8Array([/* challenge bytes */]),
    rpId: "example.com",
    userVerification: "required"
  }
});
```

### Debug Methods

The plugin exposes debug methods for troubleshooting:

```typescript
import { CredentialManager } from '@mxwmnn/capacitor-android-webauthn';

// Check if polyfill is injected
const { isInjected } = await CredentialManager.checkInjection();
console.log('Polyfill injected:', isInjected);

// Manually re-inject polyfill (shouldn't be needed)
await CredentialManager.injectPolyfill();

// Test plugin communication
const { value } = await CredentialManager.echo({ value: 'test' });
console.log('Echo:', value);
```

## Debugging

### Android Logs

View logs with:
```bash
adb logcat | grep -E "CredentialManager|PasskeyWebListener"
```

Expected logs on startup:
```
I CredentialManagerPlugin: CredentialManagerPlugin loading...
D CredentialManagerPlugin: Setting up WebView injection...
I CredentialManagerPlugin: Injecting WebAuthn polyfill into current page...
I CredentialManagerPlugin: WebMessageListener registered successfully
I CredentialManagerPlugin: WebView injection setup complete
I CredentialManagerPlugin: CredentialManagerPlugin loaded successfully
I CredentialManagerPlugin: Initial polyfill injection completed: null
```

When a passkey request is made:
```
I PasskeyWebListener: In Post Message : {"type":"create","request":{...}} source: https://...
```

### Common Issues

1. **"WebAuthn not permitted for current URL"**
   - URL must be HTTPS (not HTTP or localhost)
   - Main frame only (iframes not supported)

2. **No logs from PasskeyWebListener**
   - Check that polyfill is injected: `CredentialManager.checkInjection()`
   - Verify WebMessageListener is registered in logs

3. **Polyfill not working**
   - The polyfill injects immediately when the plugin loads
   - It should work on the first page load
   - Use `injectPolyfill()` to manually re-inject if needed

## Modifying the JavaScript Polyfill

The JavaScript code in `javascript/encode.js` is the human-readable version. It gets minified and embedded in `PasskeyWebListener.kt` as `INJECTED_VAL`.

To update the polyfill:

1. Edit `javascript/encode.js`
2. Minify it using the toptal minifier:
   ```bash
   cat javascript/encode.js | grep -v '^let __webauthn_interface__;$' | \
   curl -X POST --data-urlencode input@- \
   https://www.toptal.com/developers/javascript-minifier/api/raw | tr '"' "'"
   ```
3. Copy the output and replace `INJECTED_VAL` in `PasskeyWebListener.kt`

Note: The `grep` removes the `let __webauthn_interface__;` line which is declared in the minified output.

## Changelog

- 8.0.0 - First public release under the `@mxwmnn` scope for Capacitor v8.

## Requirements

- Android 14+ (API level 34+) or Android 9+ (API Level 28+) with Play Services
- AndroidX Credentials library
- AndroidX WebKit library
- HTTPS origin (for production use)

## License

MIT. The implementation is based in part on Google's Apache 2.0 licensed Credential Manager WebView sample.

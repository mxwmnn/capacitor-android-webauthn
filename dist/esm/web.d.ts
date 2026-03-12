import { WebPlugin } from '@capacitor/core';
import type { CredentialManagerPlugin } from './definitions';
export declare class CredentialManagerWeb extends WebPlugin implements CredentialManagerPlugin {
    echo(options: {
        value: string;
    }): Promise<{
        value: string;
    }>;
    injectPolyfill(): Promise<{
        success: boolean;
    }>;
    checkInjection(): Promise<{
        isInjected: boolean;
        rawResult: string;
    }>;
}
//# sourceMappingURL=web.d.ts.map
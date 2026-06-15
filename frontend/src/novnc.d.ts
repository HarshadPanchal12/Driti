declare module '@novnc/novnc' {
  export default class RFB {
    constructor(
      target: HTMLElement,
      url: string,
      options?: {
        wsProtocols?: string[];
        credentials?: { password?: string };
      },
    );

    scaleViewport: boolean;
    resizeSession: boolean;
    showDotCursor: boolean;
    background: string;

    disconnect(): void;
    sendCredentials(credentials: { password?: string }): void;
    addEventListener(type: string, listener: (event: Event) => void): void;
    clipViewport: boolean;
  }
}

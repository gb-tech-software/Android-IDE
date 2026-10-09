(() => {
  if (window.__androidIdeDevtoolsLoaded) return;
  window.__androidIdeDevtoolsLoaded = true;
  if (window.eruda) {
    window.eruda.init({
      tool: ['console', 'elements', 'network', 'resources', 'sources', 'info', 'snippets'],
      useShadowDom: true,
      defaults: { displaySize: 50, transparency: 1 }
    });
    window.eruda.hide();
    const nativePort = browser.runtime.connectNative('android-ide');
    nativePort.onMessage.addListener(message => {
      if (message && message.type === 'developer-tools') {
        if (message.open) window.eruda.show();
        else window.eruda.hide();
      }
    });
  }
})();

(() => {
  const CONTENT_PORT_NAME = "android-ide-devtools-content";
  const NATIVE_APPLICATION = "android_ide";
  const contentPorts = new Set();
  let nativePort = null;
  let visibility = { type: "developer-tools", enabled: true, open: false };

  function broadcast(message) {
    for (const port of [...contentPorts]) {
      try {
        port.postMessage(message);
      } catch (_) {
        contentPorts.delete(port);
      }
    }
  }

  function connectNative() {
    if (nativePort) return;
    let port;
    try {
      port = browser.runtime.connectNative(NATIVE_APPLICATION);
    } catch (_) {
      setTimeout(connectNative, 1000);
      return;
    }
    nativePort = port;
    port.onMessage.addListener(message => {
      if (!message || message.type !== "developer-tools") return;
      visibility = {
        type: "developer-tools",
        enabled: message.enabled !== false,
        open: message.open === true,
      };
      broadcast(visibility);
    });
    port.onDisconnect.addListener(() => {
      if (nativePort === port) nativePort = null;
      setTimeout(connectNative, 1000);
    });
  }

  browser.runtime.onConnect.addListener(port => {
    if (port.name !== CONTENT_PORT_NAME) return;
    contentPorts.add(port);
    port.onDisconnect.addListener(() => contentPorts.delete(port));
    try {
      port.postMessage(visibility);
    } catch (_) {
      contentPorts.delete(port);
    }
  });

  connectNative();
})();

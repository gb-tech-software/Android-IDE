(() => {
  if (window.top !== window || window.__androidIdeDevtoolsContentLoaded) return;
  window.__androidIdeDevtoolsContentLoaded = true;

  const SOURCE = "android-ide-devtools";
  const CONTENT_PORT = "android-ide-devtools-content";
  const channel = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  let visibilityOpen = false;
  let bridgeReady = false;
  let injectionStarted = false;

  function postToPage(type, fields = {}) {
    window.postMessage({ source: SOURCE, channel, type, ...fields }, "*");
  }

  window.addEventListener("message", event => {
    if (event.source !== window) return;
    const message = event.data;
    if (!message || message.source !== SOURCE || message.channel !== channel) return;
    if (message.type === "ready") {
      bridgeReady = true;
      syncVisibility();
    } else if (message.type === "error") {
      console.error("Android IDE developer tools could not initialize:", message.message);
    }
  });

  const contentPort = browser.runtime.connect({ name: CONTENT_PORT });
  let interactionActive = false;

  function isDevtoolsTarget(event) {
    return event.composedPath().some(node => {
      if (!node || !node.classList) return false;
      const name = `${node.id || ""} ${node.className || ""}`.toLowerCase();
      return name.includes("eruda") || name.includes("devtools");
    });
  }

  function syncVisibility() {
    if (!visibilityOpen) {
      interactionActive = false;
      if (bridgeReady) postToPage("visibility", { open: false, destroy: true });
      return;
    }
    if (document.visibilityState !== "visible") {
      interactionActive = false;
      if (bridgeReady) postToPage("visibility", { open: false, destroy: false });
      return;
    }
    if (bridgeReady) postToPage("visibility", { open: true });
    else injectPageBridge();
  }

  document.addEventListener("visibilitychange", syncVisibility);

  document.addEventListener("touchstart", event => {
    interactionActive = isDevtoolsTarget(event);
  }, true);
  document.addEventListener("touchmove", event => {
    if (interactionActive) event.stopPropagation();
  }, true);
  document.addEventListener("touchend", () => { interactionActive = false; }, true);
  document.addEventListener("touchcancel", () => { interactionActive = false; }, true);

  contentPort.onMessage.addListener(message => {
    if (!message || message.type !== "developer-tools") return;
    visibilityOpen = message.open === true;
    syncVisibility();
  });

  function injectPageBridge() {
    if (injectionStarted) return;
    injectionStarted = true;
    const documentRoot = document.documentElement;
    if (!documentRoot) {
      injectionStarted = false;
      const observer = new MutationObserver(() => {
        if (!document.documentElement) return;
        observer.disconnect();
        injectPageBridge();
      });
      observer.observe(document, { childList: true, subtree: true });
      return;
    }

    const assets = ["eruda.js", "page-bridge.js"];
    let index = 0;
    const loadNext = () => {
      if (index >= assets.length) {
        postToPage("initialize");
        return;
      }
      const script = document.createElement("script");
      script.src = browser.runtime.getURL(assets[index++]);
      script.async = false;
      script.onload = () => {
        script.remove();
        loadNext();
      };
      script.onerror = () => {
        script.remove();
        injectionStarted = false;
        console.error("Android IDE could not load developer-tools asset:", assets[index - 1]);
      };
      documentRoot.appendChild(script);
    };
    loadNext();
  }
})();

(() => {
  const SOURCE = "android-ide-devtools";
  if (window.__androidIdeDevtoolsPageBridge) return;
  window.__androidIdeDevtoolsPageBridge = true;

  let channel = null;
  let initialized = false;

  function post(type, fields = {}) {
    if (!channel) return;
    window.postMessage({ source: SOURCE, channel, type, ...fields }, "*");
  }

  function openTools() {
    try {
      if (!window.eruda) throw new Error("The Eruda page script did not load");
      if (!initialized) {
        window.eruda.init({
          container: document.body || document.documentElement,
          tool: ["console", "elements", "network", "resources", "sources", "info", "snippets"],
          useShadowDom: true,
          inline: true,
          defaults: { displaySize: 50, transparency: 1 }
        });
        initialized = true;
      } else {
        window.eruda.show();
      }
    } catch (error) {
      post("error", { message: String(error && error.message || error) });
    }
  }

  function closeTools() {
    if (!initialized || !window.eruda) return;
    try {
      window.eruda.hide();
      window.eruda.destroy();
    } finally {
      initialized = false;
    }
  }

  window.addEventListener("message", event => {
    if (event.source !== window) return;
    const message = event.data;
    if (!message || message.source !== SOURCE) return;

    if (message.type === "initialize") {
      channel = message.channel;
      post("ready");
      return;
    }
    if (message.channel !== channel) return;

    if (message.type === "visibility") {
      if (message.open === true) openTools();
      else closeTools();
    }
  });
})();

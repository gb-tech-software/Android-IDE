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
        // Eruda applies id="eruda" and style.all="initial" to its container.
        // Let it create a dedicated host; using document.body here resets the site's styles.
        window.eruda.init({
          tool: ["console", "elements", "network", "resources", "sources", "info", "snippets"],
          useShadowDom: true,
          inline: false,
          defaults: { displaySize: 50, transparency: 1 }
        });
        initialized = true;
      }
      window.eruda.show();
    } catch (error) {
      post("error", { message: String(error && error.message || error) });
    }
  }

  function hideTools() {
    if (!initialized || !window.eruda) return;
    try { window.eruda.hide(); }
    catch (error) { post("error", { message: String(error && error.message || error) }); }
  }

  function closeTools() {
    if (!initialized || !window.eruda) return;
    let closeError = null;
    try { window.eruda.hide(); } catch (error) { closeError = error; }
    try { window.eruda.destroy(); } catch (error) { closeError = closeError || error; }
    initialized = false;
    if (closeError) post("error", { message: String(closeError.message || closeError) });
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
      else if (message.destroy === true) closeTools();
      else hideTools();
    }
  });
})();

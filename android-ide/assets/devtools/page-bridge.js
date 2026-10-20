(() => {
  const SOURCE = "android-ide-devtools";
  if (window.__androidIdeDevtoolsPageBridge) return;
  window.__androidIdeDevtoolsPageBridge = true;

  let channel = null;
  let initialized = false;
  let initializationQueued = false;
  let readySent = false;

  function post(type, fields = {}) {
    if (!channel) return;
    window.postMessage({ source: SOURCE, channel, type, ...fields }, "*");
  }

  function announceReady() {
    if (initialized && channel && !readySent) {
      readySent = true;
      post("ready");
    }
  }

  function setEntryVisibility(exposed) {
    if (!initialized || !window.eruda) return;
    try {
      const entryButton = window.eruda.get("entryBtn");
      if (exposed) entryButton.show();
      else entryButton.hide();
    } catch (_) {}
  }

  function initializeTools() {
    if (initialized || !window.eruda) return;
    // At document_start the body may not exist yet. Wait for its creation
    // without waiting for window.load or allowing page code to block setup.
    if (!document.body) {
      if (initializationQueued) return;
      initializationQueued = true;
      const observer = new MutationObserver(() => {
        if (!document.body) return;
        observer.disconnect();
        initializationQueued = false;
        initializeTools();
      });
      observer.observe(document.documentElement, { childList: true });
      return;
    }
    try {
      // Eruda owns the page console and network hooks. Do not wrap or replay
      // console methods here: doing so changes the apparent source location
      // from the user's file to this bridge or Eruda itself.
      window.eruda.init({
        tool: ["console", "elements", "network", "resources", "sources", "info", "snippets"],
        useShadowDom: true,
        inline: false,
        defaults: { displaySize: 50, transparency: 1 }
      });
      initialized = true;
      // Prevent Eruda's default launcher from appearing before native state
      // has explicitly authorized user exposure.
      setEntryVisibility(false);
      window.eruda.hide();
      announceReady();
    } catch (error) {
      // A page error must not prevent the native bridge from coming up.
      post("error", { message: String(error && error.message || error) });
    }
  }

  function openTools() {
    try {
      if (!window.eruda) throw new Error("The Eruda page script did not load");
      initializeTools();
      if (!initialized) throw new Error("The page developer-tools capture layer is not ready");
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

  window.addEventListener("message", event => {
    if (event.source !== window) return;
    const message = event.data;
    if (!message || message.source !== SOURCE) return;

    if (message.type === "initialize") {
      channel = message.channel;
      initializeTools();
      announceReady();
      return;
    }
    if (message.channel !== channel) return;

    if (message.type === "visibility") {
      setEntryVisibility(message.exposed === true);
      if (message.open === true && message.exposed === true) openTools();
      else hideTools();
    }
  });
})();

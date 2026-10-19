(() => {
  const SOURCE = "android-ide-devtools";
  if (window.__androidIdeDevtoolsPageBridge) return;
  window.__androidIdeDevtoolsPageBridge = true;

  let channel = null;
  let initialized = false;
  let initializationQueued = false;
  let readySent = false;
  let passiveObserversInstalled = false;

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

  function pageOrigin(error) {
    return {
      source: error && error.filename || location.href,
      line: error && error.lineno || 0,
      column: error && error.colno || 0,
      stack: error && error.error && error.error.stack || error && error.reason && error.reason.stack || "",
    };
  }

  function reportPageDiagnostic(kind, value, origin = {}) {
    const details = {
      kind,
      message: typeof value === "string" ? value : String(value && value.message || value),
      ...origin,
    };
    post("page-diagnostic", details);
    // Show passive page-origin metadata without replacing page console or
    // network methods. The source and stack remain browser event values.
    if (initialized && window.eruda) {
      try { window.eruda.get("console").error(`[page ${kind}]`, details); } catch (_) {}
    }
  }

  function installPassiveObservers() {
    if (passiveObserversInstalled) return;
    passiveObserversInstalled = true;
    window.addEventListener("error", event => {
      reportPageDiagnostic("error", event.message || "Uncaught page error", pageOrigin(event));
    }, true);
    window.addEventListener("unhandledrejection", event => {
      const reason = event.reason;
      reportPageDiagnostic("unhandledrejection", reason, pageOrigin({ reason }));
    }, true);
    if (window.PerformanceObserver) {
      try {
        const observer = new PerformanceObserver(list => {
          list.getEntries().forEach(entry => {
            const details = {
              name: entry.name,
              initiatorType: entry.initiatorType || "resource",
              startTime: entry.startTime,
              duration: entry.duration,
              transferSize: entry.transferSize || 0,
              responseStatus: entry.responseStatus || 0,
            };
            post("page-network", details);
            if (initialized && window.eruda) {
              try { window.eruda.get("console").log("[page network]", details); } catch (_) {}
            }
          });
        });
        observer.observe({ type: "resource", buffered: true });
      } catch (_) {}
    }
  }

  function initializeTools() {
    if (initialized || !window.eruda) return;
    installPassiveObservers();
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

  function closeTools() {
    // Keep the bridge and capture hooks alive for the current document. The
    // page-level guard is intentionally one-shot; destroying Eruda here would
    // make a later enablement unable to initialize the same page again.
    hideTools();
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
      if (message.open === true) openTools();
      else if (message.destroy === true) closeTools();
      else hideTools();
    }
  });
})();

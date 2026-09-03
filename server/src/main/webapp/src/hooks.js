import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Dialogs are addressable: /app#/preferences opens Preferences, and Back closes it.
 *
 * Both events are needed. `popstate` covers the Back button after open() pushed a state; `hashchange`
 * covers a URL typed or pasted into the address bar, which does not fire popstate.
 *
 * open() pushes rather than assigning location.hash. Assigning fires `hashchange`, which would run
 * the reader below and re-enter the same open -- pushing changes the URL silently and leaves the
 * listener handling only the direction that matters, which is going back.
 *
 * The history calls sit outside the state updaters on purpose. An updater has to be pure: React
 * calls it twice under StrictMode in development, and a pushState inside one pushed two entries per
 * open, so Back closed nothing. Reading `dialog` from the render instead costs a re-created callback
 * per change, which is nothing.
 */
export function useHashDialog(names) {
  const read = useCallback(() => {
    const m = /^#\/([a-z]+)$/.exec(window.location.hash || "");
    return m && names.includes(m[1]) ? m[1] : null;
  }, [names]);

  const [dialog, setDialog] = useState(read);

  useEffect(() => {
    const sync = () => {
      // Whatever moved the history moved it past our entry too.
      pushedByUs.current = false;
      setDialog(read());
    };
    window.addEventListener("popstate", sync);
    window.addEventListener("hashchange", sync);
    return () => {
      window.removeEventListener("popstate", sync);
      window.removeEventListener("hashchange", sync);
    };
  }, [read]);

  /** Whether the entry now on screen is one open() pushed, and so one close() should pop. */
  const pushedByUs = useRef(false);

  const open = useCallback(
    (name) => {
      if (dialog === name) return;
      // Replace rather than push when one dialog leads to another, so Back closes the pair
      // instead of walking backwards through them one at a time.
      if (dialog) {
        history.replaceState({ dialog: name }, "", `#/${name}`);
      } else {
        history.pushState({ dialog: name }, "", `#/${name}`);
        pushedByUs.current = true;
      }
      setDialog(name);
    },
    [dialog],
  );

  const close = useCallback(() => {
    if (!dialog) return;
    if (pushedByUs.current) {
      // Go back rather than replace. Replacing left the pushed entry sitting in the history with
      // the same address as the page under it, so the first press of Back after closing a dialog
      // appeared to do nothing at all and it took two to leave.
      pushedByUs.current = false;
      setDialog(null);
      history.back();
      return;
    }
    // Nothing of ours to pop -- the dialog was opened by an address typed or pasted in. Strip the
    // fragment rather than leaving a bare "#", which would otherwise sit in the address bar and in
    // anything the reader copies out of it.
    history.replaceState(null, "", window.location.pathname + window.location.search);
    setDialog(null);
  }, [dialog]);

  return [dialog, open, close];
}

/**
 * Chef mode's screen wake lock.
 *
 * Best effort and deliberately silent. The request is refused when the document is not visible,
 * when the browser is saving power, and everywhere the API is missing -- none of which is worth
 * interrupting someone mid-recipe about, because the recipe is still on screen either way.
 *
 * The API is secure-context only, so a Salty reached over plain http on the LAN -- a normal way to
 * run this -- does not have it at all. `supported` is exported so Settings can say that out loud
 * rather than offering a switch that silently does nothing.
 */
export const wakeLockSupported = () =>
  typeof navigator !== "undefined" && "wakeLock" in navigator;

export function useWakeLock(active) {
  const sentinel = useRef(null);

  useEffect(() => {
    let cancelled = false;

    const acquire = async () => {
      if (!active || !wakeLockSupported() || sentinel.current) return;
      try {
        const lock = await navigator.wakeLock.request("screen");
        if (cancelled) {
          lock.release().catch(() => {});
          return;
        }
        sentinel.current = lock;
        // The browser drops the lock whenever the tab is hidden, and does not restore it. Without
        // this, tabbing away once ends chef mode's whole reason for existing.
        lock.addEventListener("release", () => {
          sentinel.current = null;
        });
      } catch {
        /* refused: see the note above */
      }
    };

    const onVisible = () => {
      if (document.visibilityState === "visible") acquire();
    };

    acquire();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", onVisible);
      sentinel.current?.release().catch(() => {});
      sentinel.current = null;
    };
  }, [active]);
}

/**
 * The browser's own "leave site?" prompt, armed only while there is something to lose.
 *
 * This covers closing the tab and following a link out. Navigation *inside* the app is React's
 * business and is guarded separately -- the browser never sees it.
 */
export function useUnloadGuard(dirty) {
  useEffect(() => {
    if (!dirty) return undefined;
    const onBeforeUnload = (e) => {
      e.preventDefault();
      // Browsers ignore custom text now, but returnValue still has to be set for the prompt to show.
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);
}

/** localStorage that survives a browser refusing it -- private mode, or site data turned off. */
export const readStored = (key, fallback) => {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : v;
  } catch {
    return fallback;
  }
};

export const writeStored = (key, value) => {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    /* a remembered preference is not worth an exception */
  }
};

/** A media query as state: true while it matches, updated as the window or the OS changes. */
export function useMediaQuery(query) {
  const [matches, setMatches] = useState(
    () => window.matchMedia?.(query).matches ?? false,
  );
  useEffect(() => {
    const m = window.matchMedia(query);
    const onChange = (e) => setMatches(e.matches);
    m.addEventListener("change", onChange);
    setMatches(m.matches);
    return () => m.removeEventListener("change", onChange);
  }, [query]);
  return matches;
}

/**
 * Whether the window is too narrow for three columns.
 *
 * 900px, the same breakpoint the Alpine app used. Below it the layout is one pane at a time and the
 * rail becomes a drawer, because 260 + 360 + a readable recipe does not fit on a phone and shrinking
 * all three leaves three unusable columns instead of one usable one.
 */
export const useCompact = (maxWidth = 900) => useMediaQuery(`(max-width: ${maxWidth}px)`);

/** Follows the OS rather than offering a switch, matching what the Mustache shell does pre-paint. */
export const usePrefersDark = () => useMediaQuery("(prefers-color-scheme: dark)");

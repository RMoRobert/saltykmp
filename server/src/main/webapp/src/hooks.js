import { useCallback, useEffect, useRef, useState } from "react";

/** The empty route -- /app with nothing after it. Spread into every route so the shape is fixed. */
const NO_ROUTE = { dialog: null, recipeId: null, editing: false };

const routeToHash = (route) => {
  if (route.dialog) return `#/${route.dialog}`;
  if (route.recipeId) {
    return `#/recipe/${encodeURIComponent(route.recipeId)}${route.editing ? "/edit" : ""}`;
  }
  return "";
};

/**
 * A recipe's address, for a link or a new tab -- `#/recipe/<id>`, relative to whatever /app path is
 * serving the page. Exported so the one place that writes recipe URLs is the one that reads them.
 */
export const recipeHash = (id, editing = false) => routeToHash({ recipeId: id, editing });

function hashToRoute(dialogNames) {
  const hash = window.location.hash || "";
  const dialog = /^#\/([a-z]+)$/.exec(hash);
  if (dialog) {
    return dialogNames.includes(dialog[1]) ? { ...NO_ROUTE, dialog: dialog[1] } : NO_ROUTE;
  }
  // Ids are UUIDs, so the encoding is a formality -- but a route that only works for the ids we
  // happen to mint today is a route that breaks quietly the first time one of them isn't.
  const recipe = /^#\/recipe\/([^/]+)(\/edit)?$/.exec(hash);
  if (recipe) {
    return { ...NO_ROUTE, recipeId: decodeURIComponent(recipe[1]), editing: Boolean(recipe[2]) };
  }
  return NO_ROUTE;
}

/**
 * The hash IS the route: which recipe is open, whether it is being edited, and which dialog is up.
 *
 *   #/recipe/<id>        a recipe, being read
 *   #/recipe/<id>/edit   the same recipe, in the editor
 *   #/preferences        a dialog -- one of the names the caller passes in
 *
 * A route is one or the other, never both: a dialog's address replaces the recipe's for as long as
 * it is open, and closing it goes BACK to the recipe rather than forward to a third address. That is
 * what makes one press of Back mean "close this", which is the only thing anyone expects it to mean.
 *
 * Anything not in the grammar above -- a bare /app, `#/`, a typo -- reads as the empty route, so a
 * mangled address lands on the list rather than on an error.
 *
 * Both events are needed. `popstate` covers the Back button after push() pushed a state; `hashchange`
 * covers a URL typed or pasted into the address bar, which does not fire popstate.
 *
 * push() and replace() write through `history` rather than assigning `location.hash`. Assigning
 * fires `hashchange`, which would run the reader below and re-enter the same navigation -- the
 * history calls change the URL silently and leave the listener handling only the direction that
 * matters, which is arriving from outside.
 *
 * The history calls sit outside the state updaters on purpose. An updater has to be pure: React
 * calls it twice under StrictMode in development, and a pushState inside one pushed two entries per
 * navigation, so Back moved nothing.
 */

export function useHashRoute(dialogNames) {
  const read = useCallback(() => hashToRoute(dialogNames), [dialogNames]);

  const [route, setRoute] = useState(read);

  useEffect(() => {
    const sync = () => {
      // Whatever moved the history moved it past our entry too.
      pushedByUs.current = false;
      setRoute(read());
    };
    window.addEventListener("popstate", sync);
    window.addEventListener("hashchange", sync);
    return () => {
      window.removeEventListener("popstate", sync);
      window.removeEventListener("hashchange", sync);
    };
  }, [read]);

  /** Whether the entry now on screen is one push() pushed, and so one closeDialog() should pop. */
  const pushedByUs = useRef(false);

  const write = useCallback((next, pushing) => {
    const route = { ...NO_ROUTE, ...next };
    const hash = routeToHash(route);
    // Not a bare "#", which would otherwise sit in the address bar and in anything copied out of it.
    const url = hash || window.location.pathname + window.location.search;
    if (pushing) {
      history.pushState(route, "", url);
      pushedByUs.current = true;
    } else {
      history.replaceState(route, "", url);
    }
    setRoute(route);
    return route;
  }, []);

  /** A new place: Back returns to where the reader was. Opening a recipe, or a dialog over one. */
  const push = useCallback((next) => write(next, true), [write]);

  /**
   * The same place, described differently -- entering the editor, saving a draft, losing the recipe
   * a delete just took away. None of those is somewhere Back should return to.
   */
  const replace = useCallback((next) => write(next, false), [write]);

  /**
   * Closing a dialog, which is a pop rather than a push when we pushed to open it.
   *
   * Replacing instead left the pushed entry sitting in the history with the same address as the
   * page under it, so the first press of Back after closing a dialog appeared to do nothing at all
   * and it took two to leave.
   *
   * `under` is the route the dialog is sitting on top of -- the caller knows it, because it is what
   * the panes are showing. It is set here rather than waited for so the dialog closes on the click:
   * the pop is what actually restores it, a moment later, to the same value.
   */
  const closeDialog = useCallback(
    (under) => {
      if (pushedByUs.current) {
        pushedByUs.current = false;
        setRoute({ ...NO_ROUTE, ...under });
        history.back();
        return;
      }
      // Nothing of ours to pop -- the dialog was opened by an address typed or pasted in.
      write(under, false);
    },
    [write],
  );

  return { route, push, replace, closeDialog };
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

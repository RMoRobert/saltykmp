/*
 * The one place that talks to the server.
 *
 * Salty's web UI is a client of the same JSON API the Swift and Compose apps use -- there is no
 * web-only endpoint. What this module adds over `fetch` is the three things every call needs and
 * no call should restate: the CSRF header on writes, the redirect-to-login on 401, and turning a
 * non-2xx body into an Error carrying the server's own message.
 */

/**
 * Config the server rendered into the page. Read from data attributes rather than a script literal:
 * Mustache escapes for HTML, which is right for an attribute and wrong inside <script>, where a
 * backslash in a username starts a bogus \u escape and takes the whole app down with a SyntaxError.
 */
export const SALTY = (() => {
  const d = document.getElementById("salty-config")?.dataset ?? {};
  return {
    csrfToken: d.csrf || "",
    username: d.username || "",
    isAdmin: d.isAdmin === "true",
    version: d.version || "",
    buildTime: d.buildTime || "",
    minPasswordLength: Number(d.minPasswordLength) || 8,
  };
})();

async function request(method, path, body) {
  const headers = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  // A session cookie is ambient credential; a Bearer token is not. Only the former needs this.
  if (method !== "GET" && method !== "HEAD") headers["X-CSRF-Token"] = SALTY.csrfToken;

  const resp = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  // Session gone: go and sign in rather than failing every pane with an opaque error.
  if (resp.status === 401) {
    window.location.href = "/login";
    throw new Error("Not signed in");
  }
  if (resp.status === 204) return null;

  const text = await resp.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!resp.ok) {
    const err = new Error(data?.error || data?.message || `${resp.status} ${resp.statusText}`);
    err.status = resp.status;
    err.data = data; // a 409 answers with the server's current row; conflict handling needs it
    throw err;
  }
  return data;
}

const get = (p) => request("GET", p);
const post = (p, b) => request("POST", p, b);
const put = (p, b) => request("PUT", p, b);
const del = (p) => request("DELETE", p);

/** The API pluralises; the UI's filter kinds are singular. One place to reconcile that. */
export const CLASSIFIER_PATH = { category: "categories", course: "courses", tag: "tags" };

export const api = {
  recipes: {
    list: () => get("/api/recipes"),
    get: (id) => get(`/api/recipes/${encodeURIComponent(id)}`),
    save: (r) => put(`/api/recipes/${encodeURIComponent(r.id)}`, r),
    remove: (id) => del(`/api/recipes/${encodeURIComponent(id)}`),
    importFrom: (url) => post("/api/recipes/import", { url }),
  },
  classifiers: {
    list: (kind) => get(`/api/${CLASSIFIER_PATH[kind]}`),
    create: (kind, name) => post(`/api/${CLASSIFIER_PATH[kind]}`, { name }),
    rename: (kind, id, name) =>
      put(`/api/${CLASSIFIER_PATH[kind]}/${encodeURIComponent(id)}`, { id, name }),
    remove: (kind, id) => del(`/api/${CLASSIFIER_PATH[kind]}/${encodeURIComponent(id)}`),
  },
  shoppingLists: {
    list: () => get("/api/shoppingLists"),
    get: (id) => get(`/api/shoppingLists/${encodeURIComponent(id)}`),
    save: (l) => put(`/api/shoppingLists/${encodeURIComponent(l.id)}`, l),
    remove: (id) => del(`/api/shoppingLists/${encodeURIComponent(id)}`),
  },
  account: {
    changePassword: (currentPassword, newPassword) =>
      post("/api/account/password", { currentPassword, newPassword }),
  },
};

/**
 * The list asks for thumbnails, not full images: the server generates and caches those, so a
 * hundred rows cost a hundred small requests rather than a hundred full-size photos.
 */
export const thumbUrl = (r) =>
  r?.imageFilename
    ? `/api/recipes/images/${encodeURIComponent(r.imageFilename)}/thumbnail`
    : null;

export const imageUrl = (r) =>
  r?.imageFilename ? `/api/recipes/images/${encodeURIComponent(r.imageFilename)}` : null;

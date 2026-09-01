/*
 * The one place that talks to the server.
 *
 * Salty's web UI is a client of the same JSON API the Swift and Compose apps use -- there is no
 * web-only endpoint. What this module adds over `fetch` is the three things every call needs and
 * no call should restate: the CSRF header on writes, the redirect-to-login on 401, and turning a
 * non-2xx body into an Error carrying the server's own message.
 */

import { uuidv7 } from "./model";

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
    /**
     * The id is minted here, not by the server: the route receives a whole ServerCategory/Course/Tag
     * and its id is non-null, so a body of just a name fails to deserialize and comes back 400.
     * Client-minted ids are the rule everywhere else in Salty too -- they are UUIDv7, so they sort
     * by creation, and creation is this moment.
     */
    create: (kind, name) => post(`/api/${CLASSIFIER_PATH[kind]}`, { id: uuidv7(), name }),
    rename: (kind, id, name) =>
      put(`/api/${CLASSIFIER_PATH[kind]}/${encodeURIComponent(id)}`, { id, name }),
    remove: (kind, id) => del(`/api/${CLASSIFIER_PATH[kind]}/${encodeURIComponent(id)}`),
  },
  shoppingLists: {
    list: () => get("/api/shoppingLists"),
    get: (id) => get(`/api/shoppingLists/${encodeURIComponent(id)}`),
    save: (l) => put(`/api/shoppingLists/${encodeURIComponent(l.id)}`, l),
    remove: (id) => del(`/api/shoppingLists/${encodeURIComponent(id)}`),
    /**
     * Three-way merge, run on the server.
     *
     * The native clients keep a `syncedSnapshot` of the last agreed state and merge locally. The
     * server keeps no such column for the web, so the browser holds the base itself and hands both
     * sides over -- the merge that runs is the same shared one, not a web-only rule.
     */
    resolve: (id, base, local) =>
      post(`/api/shoppingLists/${encodeURIComponent(id)}/resolve`, { base, local }),
  },
  account: {
    changePassword: (currentPassword, newPassword) =>
      post("/api/account/password", { currentPassword, newPassword }),
  },
  /** Sync enrolments: one row per native client that has ever synced against this account. */
  devices: {
    list: () => get("/api/auth/devices"),
    rename: (deviceId, deviceName) =>
      request("PATCH", `/api/auth/devices/${encodeURIComponent(deviceId)}`, { deviceName }),
    remove: (deviceId) => del(`/api/auth/devices/${encodeURIComponent(deviceId)}`),
    revokeAll: () => post("/api/auth/devices/revoke-all"),
  },
  users: {
    list: () => get("/api/users"),
    create: (username, password, isAdmin) => post("/api/users", { username, password, isAdmin }),
    setPassword: (id, password) => post(`/api/users/${encodeURIComponent(id)}/password`, { password }),
    setAdmin: (id, isAdmin) => request("PATCH", `/api/users/${encodeURIComponent(id)}`, { isAdmin }),
    remove: (id) => del(`/api/users/${encodeURIComponent(id)}`),
  },
};

/**
 * Images do not go through `request`: the body is multipart, so the browser has to set
 * Content-Type itself in order to add the boundary. Setting it by hand produces a request the
 * server cannot parse.
 *
 * `lastModifiedImageDate` is bumped independently of the recipe's own stamp, so a text-only edit
 * never re-transfers the image and an image-only edit never re-transfers the body. The pair has to
 * travel together, which is why the caller passes the stamp in rather than letting each side guess.
 */
export async function uploadImage(recipeId, file, stamp) {
  const body = new FormData();
  body.append("file", file, file.name || "image");
  body.append("lastModifiedImageDate", stamp);
  const resp = await fetch(`/api/recipes/${encodeURIComponent(recipeId)}/image`, {
    method: "POST",
    headers: { "X-CSRF-Token": SALTY.csrfToken },
    credentials: "same-origin",
    body,
  });
  if (!resp.ok) {
    let detail = `${resp.status} ${resp.statusText}`;
    try {
      const j = await resp.json();
      if (j?.error) detail = j.error;
    } catch {
      /* keep the status line */
    }
    throw new Error(detail);
  }
  return resp.json();
}

export const deleteImage = (recipeId, stamp) =>
  request(
    "DELETE",
    `/api/recipes/${encodeURIComponent(recipeId)}/image` +
      `?lastModifiedImageDate=${encodeURIComponent(stamp)}`,
  );

/**
 * Mirrors the server: ImageStore identifies the format from the BYTES and stores only these three,
 * and MAX_IMAGE_UPLOAD_BYTES caps the request. Checking here as well turns a 25 MB round trip that
 * ends in 415 into an immediate, specific message.
 */
export const IMAGE_TYPES = ["image/jpeg", "image/png", "image/gif"];
export const MAX_IMAGE_BYTES = 25 * 1024 * 1024;

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

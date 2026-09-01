import { useCallback, useEffect, useState } from "react";
import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Body1,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Spinner,
  Subtitle2,
  Switch,
  Tab,
  TabList,
  Tooltip,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  Add20Regular,
  Delete20Regular,
  Dismiss24Regular,
  Key20Regular,
  Rename20Regular,
} from "@fluentui/react-icons";

import { SALTY, api } from "../api";
import { relativeDate, uuidv7, wireNow } from "../model";

const useStyles = makeStyles({
  rows: { display: "grid", gap: tokens.spacingVerticalXS, marginTop: tokens.spacingVerticalM },
  row: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalXS },
  rowInput: { flex: 1 },
  rowText: { flex: 1, minWidth: 0 },
  sub: { color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200 },
  count: {
    minWidth: "5.5rem",
    textAlign: "end",
    color: tokens.colorNeutralForeground3,
    fontSize: tokens.fontSizeBase200,
  },
  addRow: { display: "flex", gap: tokens.spacingHorizontalS, marginTop: tokens.spacingVerticalM },
  fields: { display: "grid", gap: tokens.spacingVerticalM },
  kv: { display: "grid", gridTemplateColumns: "auto 1fr", gap: tokens.spacingHorizontalM },
  key: { color: tokens.colorNeutralForeground3 },
  section: { marginTop: tokens.spacingVerticalL },
  wide: { maxWidth: "44rem" },
});

/* ------------------------------------------------------------------ devices -- */

/**
 * Sync enrolments: one row per native client that has ever synced against this account.
 *
 * Revoked rows stay listed. Signing a device out and forgetting it happened are different things,
 * and the second is rarely what anyone wants -- "which phone was that?" is a question you ask
 * after revoking, not before.
 */
function Devices({ notify, ask }) {
  const styles = useStyles();
  const [rows, setRows] = useState(null);

  const load = useCallback(async () => {
    try {
      setRows((await api.devices.list()) || []);
    } catch (e) {
      notify(e.message || "Could not load your devices", "error");
      setRows([]);
    }
  }, [notify]);

  useEffect(() => {
    load();
  }, [load]);

  if (rows === null) return <Spinner size="tiny" label="Loading devices…" />;
  if (rows.length === 0) return <Body1>No device has synced with this account yet.</Body1>;

  return (
    <div className={styles.rows}>
      {rows.map((d) => (
        <div key={d.deviceId} className={styles.row}>
          <div className={styles.rowText}>
            <div>{d.deviceName || "Unnamed device"}</div>
            <div className={styles.sub}>
              {d.hasToken ? "Signed in" : "Signed out"}
              {d.tokenLastUsed ? ` · last used ${relativeDate(d.tokenLastUsed)}` : ""}
            </div>
          </div>
          <Tooltip content="Rename" relationship="label">
            <Button
              appearance="subtle"
              icon={<Rename20Regular />}
              onClick={() =>
                ask({
                  title: "Rename device",
                  prompt: "Device name",
                  initialValue: d.deviceName || "",
                  confirmLabel: "Rename",
                  onConfirm: async (name) => {
                    await api.devices.rename(d.deviceId, name);
                    load();
                  },
                })
              }
            />
          </Tooltip>
          <Tooltip content="Remove" relationship="label">
            <Button
              appearance="subtle"
              icon={<Delete20Regular />}
              onClick={() =>
                ask({
                  title: "Remove app",
                  body: `${d.deviceName || "This device"} will have to sign in again to sync.`,
                  confirmLabel: "Remove",
                  onConfirm: async () => {
                    await api.devices.remove(d.deviceId);
                    load();
                    notify("Device removed");
                  },
                })
              }
            />
          </Tooltip>
        </div>
      ))}
      <div className={styles.addRow}>
        <Button
          onClick={() =>
            ask({
              title: "Sign every app out",
              body: "Every device will need to sign in again before it can sync.",
              confirmLabel: "Sign all out",
              onConfirm: async () => {
                const res = await api.devices.revokeAll();
                load();
                notify(`Signed out ${res?.revoked ?? 0} device(s)`);
              },
            })
          }
        >
          Sign every app out
        </Button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------- preferences -- */

export function PreferencesDialog({ open, onClose, notify, ask, wakeLockPref, onWakeLockPref }) {
  const styles = useStyles();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const tooShort = newPassword.length > 0 && newPassword.length < SALTY.minPasswordLength;
  // Secure-context only, so a Salty reached over plain http on the LAN -- a normal way to run this
  // -- does not have it at all. Better said out loud than offered as a switch that does nothing.
  const wakeLockSupported = typeof navigator !== "undefined" && "wakeLock" in navigator;

  const submit = async () => {
    setBusy(true);
    try {
      await api.account.changePassword(currentPassword, newPassword);
      // The server signs every *other* device out and leaves this session alone. Worth saying:
      // a password change that silently revoked your phone would be a surprise.
      notify("Password changed. Other devices have been signed out.");
      setCurrentPassword("");
      setNewPassword("");
      onClose();
    } catch (e) {
      notify(e.message || "Could not change the password", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface className={styles.wide}>
        <DialogBody>
          <DialogTitle
            action={
              <Tooltip content="Close" relationship="label">
                <Button appearance="subtle" icon={<Dismiss24Regular />} onClick={onClose} />
              </Tooltip>
            }
          >
            Settings
          </DialogTitle>
          <DialogContent>
            <div className={styles.fields}>
              <Subtitle2 as="h3">Chef mode</Subtitle2>
              <Switch
                checked={wakeLockPref}
                disabled={!wakeLockSupported}
                onChange={(_, d) => onWakeLockPref(d.checked)}
                label="Keep the screen awake in chef mode"
              />
              {wakeLockSupported ? null : (
                <MessageBar intent="warning">
                  <MessageBarBody>
                    This browser only offers the wake lock over HTTPS, so Salty cannot hold the
                    screen on here.
                  </MessageBarBody>
                </MessageBar>
              )}

              <Subtitle2 as="h3">Password</Subtitle2>
              <Field label="Current password">
                <Input
                  type="password"
                  value={currentPassword}
                  onChange={(_, d) => setCurrentPassword(d.value)}
                />
              </Field>
              <Field
                label="New password"
                validationState={tooShort ? "error" : "none"}
                validationMessage={
                  tooShort ? `At least ${SALTY.minPasswordLength} characters.` : undefined
                }
              >
                <Input
                  type="password"
                  value={newPassword}
                  onChange={(_, d) => setNewPassword(d.value)}
                />
              </Field>
              <div>
                <Button
                  appearance="primary"
                  disabled={busy || !currentPassword || !newPassword || tooShort}
                  onClick={submit}
                >
                  Change password
                </Button>
              </div>

              <Subtitle2 as="h3">Apps and devices</Subtitle2>
              {open ? <Devices notify={notify} ask={ask} /> : null}

              {/* Microsoft's own guidance for app settings puts About at the bottom of the
                  settings page, collapsed: "app information that isn't accessed very often, such
                  as privacy policy, help, app version, or copyright info". Accordion is v9's
                  equivalent of the SettingsExpander that guidance names. */}
              <Accordion collapsible>
                <AccordionItem value="about">
                  <AccordionHeader>About Salty</AccordionHeader>
                  <AccordionPanel>
                    <div className={styles.kv}>
                      <span className={styles.key}>Version</span>
                      <span>{SALTY.version || "—"}</span>
                      <span className={styles.key}>Built</span>
                      <span>{SALTY.buildTime || "—"}</span>
                      <span className={styles.key}>Signed in as</span>
                      <span>
                        {SALTY.username || "—"}
                        {SALTY.isAdmin ? " (admin)" : ""}
                      </span>
                    </div>
                  </AccordionPanel>
                </AccordionItem>
              </Accordion>
            </div>
          </DialogContent>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

/* -------------------------------------------------------------------- users -- */

export function UsersDialog({ open, onClose, notify, ask }) {
  const styles = useStyles();
  const [rows, setRows] = useState(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [isAdmin, setIsAdmin] = useState(false);

  const load = useCallback(async () => {
    try {
      setRows((await api.users.list()) || []);
    } catch (e) {
      notify(e.message || "Could not load users", "error");
      setRows([]);
    }
  }, [notify]);

  useEffect(() => {
    if (open) load();
  }, [open, load]);

  const create = async () => {
    try {
      await api.users.create(username.trim(), password, isAdmin);
      setUsername("");
      setPassword("");
      setIsAdmin(false);
      load();
      notify("User added");
    } catch (e) {
      notify(e.message || "Could not add that user", "error");
    }
  };

  const tooShort = password.length > 0 && password.length < SALTY.minPasswordLength;

  return (
    <Dialog open={open} onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface className={styles.wide}>
        <DialogBody>
          <DialogTitle
            action={
              <Tooltip content="Close" relationship="label">
                <Button appearance="subtle" icon={<Dismiss24Regular />} onClick={onClose} />
              </Tooltip>
            }
          >
            Users
          </DialogTitle>
          <DialogContent>
            {rows === null ? (
              <Spinner size="tiny" label="Loading users…" />
            ) : (
              <div className={styles.rows}>
                {rows.map((u) => (
                  <div key={u.id} className={styles.row}>
                    <div className={styles.rowText}>
                      <div>
                        {u.username}
                        {u.isSelf ? " (you)" : ""}
                      </div>
                      <div className={styles.sub}>{u.isAdmin ? "Administrator" : "User"}</div>
                    </div>

                    {/* The server refuses to demote or delete the last administrator; the UI does
                        not try to predict that, it just reports what comes back. */}
                    <Tooltip
                      content={u.isAdmin ? "Remove administrator" : "Make administrator"}
                      relationship="label"
                    >
                      <Button
                        appearance="subtle"
                        onClick={async () => {
                          try {
                            await api.users.setAdmin(u.id, !u.isAdmin);
                            load();
                          } catch (e) {
                            notify(e.message || "Could not change that", "error");
                          }
                        }}
                      >
                        {u.isAdmin ? "Demote" : "Promote"}
                      </Button>
                    </Tooltip>

                    <Tooltip content="Reset password" relationship="label">
                      <Button
                        appearance="subtle"
                        icon={<Key20Regular />}
                        onClick={() =>
                          ask({
                            title: `Reset password for ${u.username}`,
                            body: "Their apps will be signed out and they will need the new password.",
                            prompt: "New password",
                            confirmLabel: "Reset",
                            onConfirm: async (value) => {
                              try {
                                await api.users.setPassword(u.id, value);
                                notify("Password reset");
                              } catch (e) {
                                notify(e.message || "Could not reset the password", "error");
                              }
                            },
                          })
                        }
                      />
                    </Tooltip>

                    <Tooltip content="Delete user" relationship="label">
                      <Button
                        appearance="subtle"
                        icon={<Delete20Regular />}
                        disabled={u.isSelf}
                        onClick={() =>
                          ask({
                            title: "Delete user",
                            body: `${u.username} and all of their recipes will be deleted.`,
                            confirmLabel: "Delete",
                            onConfirm: async () => {
                              try {
                                await api.users.remove(u.id);
                                load();
                                notify("User deleted");
                              } catch (e) {
                                notify(e.message || "Could not delete that user", "error");
                              }
                            },
                          })
                        }
                      />
                    </Tooltip>
                  </div>
                ))}

                <div className={styles.section}>
                  <Subtitle2 as="h3">Add a user</Subtitle2>
                  <div className={styles.addRow}>
                    <Input
                      className={styles.rowInput}
                      placeholder="Username"
                      value={username}
                      onChange={(_, d) => setUsername(d.value)}
                    />
                    <Input
                      className={styles.rowInput}
                      type="password"
                      placeholder={`Password (${SALTY.minPasswordLength}+ characters)`}
                      value={password}
                      onChange={(_, d) => setPassword(d.value)}
                    />
                    <Checkbox
                      label="Admin"
                      checked={isAdmin}
                      onChange={(_, d) => setIsAdmin(!!d.checked)}
                    />
                    <Button
                      appearance="primary"
                      icon={<Add20Regular />}
                      disabled={!username.trim() || !password || tooShort}
                      onClick={create}
                    >
                      Add
                    </Button>
                  </div>
                </div>
              </div>
            )}
          </DialogContent>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

/* ---------------------------------------------------------- manage library -- */

const KINDS = [
  { kind: "category", label: "Categories", singular: "category" },
  { kind: "course", label: "Courses", singular: "course" },
  { kind: "tag", label: "Tags", singular: "tag" },
];

export function ManageLibraryDialog({
  open,
  onClose,
  courses,
  categories,
  tags,
  recipes,
  onChanged,
  notify,
  ask,
}) {
  const styles = useStyles();
  const [kind, setKind] = useState("category");
  const [draftName, setDraftName] = useState("");
  const [edits, setEdits] = useState({});

  const items = kind === "category" ? categories : kind === "course" ? courses : tags;

  const countFor = (id) =>
    recipes.filter((r) =>
      kind === "course"
        ? r.courseId === id
        : kind === "category"
          ? (r.categoryIds || []).includes(id)
          : (r.tagIds || []).includes(id),
    ).length;

  const rename = async (item, name) => {
    const trimmed = name.trim();
    if (!trimmed || trimmed === item.name) return;
    try {
      await api.classifiers.rename(kind, item.id, trimmed);
      await onChanged();
    } catch (e) {
      notify(e.message || "Could not rename", "error");
    }
  };

  const remove = (item) => {
    const n = countFor(item.id);
    ask({
      title: "Delete from library",
      body: n
        ? `${item.name} is used by ${n} recipe${n === 1 ? "" : "s"}. They keep their other details.`
        : `${item.name} is not used by any recipe.`,
      confirmLabel: "Delete",
      onConfirm: async () => {
        try {
          await api.classifiers.remove(kind, item.id);
          await onChanged();
        } catch (e) {
          notify(e.message || "Could not delete", "error");
        }
      },
    });
  };

  const add = async () => {
    const name = draftName.trim();
    if (!name) return;
    setDraftName("");
    try {
      await api.classifiers.create(kind, name);
      await onChanged();
    } catch (e) {
      notify(e.message || "Could not add", "error");
    }
  };

  return (
    <Dialog open={open} onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface className={styles.wide}>
        <DialogBody>
          <DialogTitle
            action={
              <Tooltip content="Close" relationship="label">
                <Button appearance="subtle" icon={<Dismiss24Regular />} onClick={onClose} />
              </Tooltip>
            }
          >
            Organize library
          </DialogTitle>
          <DialogContent>
            <TabList selectedValue={kind} onTabSelect={(_, d) => setKind(d.value)}>
              {KINDS.map((k) => (
                <Tab key={k.kind} value={k.kind}>
                  {k.label}
                </Tab>
              ))}
            </TabList>

            <div className={styles.rows}>
              {items.length === 0 ? (
                <Body1>Nothing here yet.</Body1>
              ) : (
                items.map((item) => (
                  <div key={item.id} className={styles.row}>
                    <Input
                      className={styles.rowInput}
                      value={edits[item.id] ?? item.name ?? ""}
                      onChange={(_, d) => setEdits((e) => ({ ...e, [item.id]: d.value }))}
                      onBlur={(e) => rename(item, e.target.value)}
                    />
                    <span className={styles.count}>
                      {countFor(item.id)} recipe{countFor(item.id) === 1 ? "" : "s"}
                    </span>
                    <Tooltip content="Delete" relationship="label">
                      <Button
                        appearance="subtle"
                        icon={<Delete20Regular />}
                        onClick={() => remove(item)}
                      />
                    </Tooltip>
                  </div>
                ))
              )}
            </div>

            <div className={styles.addRow}>
              <Input
                className={styles.rowInput}
                placeholder={`New ${KINDS.find((k) => k.kind === kind).singular}`}
                value={draftName}
                onChange={(_, d) => setDraftName(d.value)}
                onKeyDown={(e) => e.key === "Enter" && add()}
              />
              <Button
                appearance="primary"
                icon={<Add20Regular />}
                disabled={!draftName.trim()}
                onClick={add}
              >
                Add
              </Button>
            </div>
          </DialogContent>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ import -- */

export function ImportDialog({ open, onClose, onImported, notify }) {
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    try {
      const result = await api.recipes.importFrom(url.trim());
      const r = result?.recipe ?? {};
      const now = wireNow();
      // The import is a draft that exists only in this tab until Save: the id is minted here so it
      // sorts by when you created it, not by when the save happened to land.
      onImported({
        ...r,
        id: uuidv7(),
        createdDate: now,
        lastModifiedDate: now,
        categoryIds: [],
        tagIds: [],
        notes: r.notes ?? [],
        variations: r.variations ?? [],
        preparationTimes: r.preparationTimes ?? [],
      });
      setUrl("");
    } catch (e) {
      notify(e.message || "Could not import that page", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface>
        <DialogBody>
          <DialogTitle>Import from web</DialogTitle>
          <DialogContent>
            <Field
              label="Recipe page address"
              hint="Sites that publish JSON-LD import cleanly; others come back empty."
            >
              <Input
                value={url}
                placeholder="https://…"
                onChange={(_, d) => setUrl(d.value)}
                onKeyDown={(e) => e.key === "Enter" && url.trim() && run()}
              />
            </Field>
          </DialogContent>
          <DialogActions>
            <Button appearance="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button appearance="primary" disabled={busy || !url.trim()} onClick={run}>
              {busy ? "Fetching…" : "Import"}
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

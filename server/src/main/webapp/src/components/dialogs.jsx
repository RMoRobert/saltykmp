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
  Radio,
  RadioGroup,
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
  Merge20Regular,
  Rename20Regular,
} from "@fluentui/react-icons";

import { SALTY, api } from "../api";
import PasswordInput from "./PasswordInput";
import { wakeLockSupported } from "../hooks";
import { LIST_STYLES, relativeDate, uuidv7, wireNow } from "../model";

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
  /* The bar under the tabs: Select on the right normally; the count, Merge and Done while selecting. */
  selectBar: {
    display: "flex",
    alignItems: "center",
    justifyContent: "flex-end",
    gap: tokens.spacingHorizontalS,
    marginTop: tokens.spacingVerticalS,
    minHeight: "32px",
  },
  selectCount: { flex: 1, color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200 },
  /* A selectable row is the checkbox's own label plus the count, at the same height as an Input row
     so entering Select mode does not reflow the list. */
  checkRow: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalXS, minHeight: "32px" },
  /* Full width on purpose: RadioGroup lays its children out without stretching them, and the count
     belongs at the right edge, as it is in the list behind the dialog. */
  candidate: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalXS, width: "100%" },
  fields: { display: "grid", gap: tokens.spacingVerticalM },
  kv: { display: "grid", gridTemplateColumns: "auto 1fr", gap: tokens.spacingHorizontalM },
  key: { color: tokens.colorNeutralForeground3 },
  section: { marginTop: tokens.spacingVerticalL },
  /* Fluent clamps the surface to the viewport on a phone, so this is only the desktop bound. The
     narrow case needs its own padding: 24px a side out of 390 is a tenth of the screen spent on
     margin, and the fields inside are what should have it. */
  wide: {
    maxWidth: "44rem",
    "@media (max-width: 480px)": { padding: tokens.spacingHorizontalL },
  },
  /* Body size, not caption size, with the quieter colour carrying the hierarchy instead. 12px was
     the only text this small in the dialog, which made the one section that used it read as shrunk
     rather than as secondary -- and on a phone it was simply hard to read. */
  radioHint: { color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase300 },
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
                const n = res?.revoked ?? 0;
                notify(`Signed out ${n} ${n === 1 ? "device" : "devices"}`);
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

/**
 * The password section of Settings, as its own component so its state is the dialog's: closing
 * Settings without submitting unmounts this and takes the half-typed passwords with it, rather
 * than leaving them in memory for the next time the dialog opens.
 */
function ChangePassword({ notify, onChanged }) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const tooShort = newPassword.length > 0 && newPassword.length < SALTY.minPasswordLength;

  const submit = async () => {
    setBusy(true);
    try {
      await api.account.changePassword(currentPassword, newPassword);
      // The server signs every *other* device out and leaves this session alone. Worth saying:
      // a password change that silently revoked your phone would be a surprise.
      notify("Password changed. Other devices have been signed out.");
      onChanged();
    } catch (e) {
      notify(e.message || "Could not change the password", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Field label="Current password">
        <PasswordInput value={currentPassword} onChange={(_, d) => setCurrentPassword(d.value)} />
      </Field>
      <Field
        label="New password"
        validationState={tooShort ? "error" : "none"}
        validationMessage={tooShort ? `At least ${SALTY.minPasswordLength} characters.` : undefined}
      >
        <PasswordInput value={newPassword} onChange={(_, d) => setNewPassword(d.value)} />
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
    </>
  );
}

export function PreferencesDialog({
  open,
  onClose,
  notify,
  ask,
  wakeLockPref,
  onWakeLockPref,
  listStyle,
  onListStyle,
}) {
  const styles = useStyles();
  // Secure-context only, so a Salty reached over plain http on the LAN -- a normal way to run this
  // -- does not have it at all. Better said out loud than offered as a switch that does nothing.
  const wakeLock = wakeLockSupported();

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
              {/* First, because it is the one setting here that changes what is on screen behind
                  the dialog -- and it does so as it is chosen, so the answer to "which of these
                  is it?" is the list still visible in the column to the left. */}
              <Subtitle2 as="h3">Recipe list</Subtitle2>
              <RadioGroup
                aria-label="Recipe list style"
                value={listStyle}
                onChange={(_, d) => onListStyle(d.value)}
              >
                {LIST_STYLES.map((option) => (
                  <Radio
                    key={option.key}
                    value={option.key}
                    label={{
                      children: (
                        <>
                          <div>{option.label}</div>
                          <div className={styles.radioHint}>{option.hint}</div>
                        </>
                      ),
                    }}
                  />
                ))}
              </RadioGroup>

              <Subtitle2 as="h3">Chef mode</Subtitle2>
              <Switch
                checked={wakeLockPref}
                disabled={!wakeLock}
                onChange={(_, d) => onWakeLockPref(d.checked)}
                label="Keep the screen awake in chef mode"
              />
              {wakeLock ? null : (
                <MessageBar intent="warning">
                  <MessageBarBody>
                    This browser only offers the wake lock over HTTPS, so Salty cannot hold the
                    screen on here.
                  </MessageBarBody>
                </MessageBar>
              )}

              <Subtitle2 as="h3">Password</Subtitle2>
              <ChangePassword notify={notify} onChanged={onClose} />

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

/**
 * Adding a user, as its own component so its state is the dialog's.
 *
 * Same reason ChangePassword is one: this holds a password, and a half-typed one used to survive
 * closing the dialog and be sitting in the fields the next time it opened.
 */
function AddUser({ styles, onAdded, notify }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [isAdmin, setIsAdmin] = useState(false);
  const [busy, setBusy] = useState(false);

  const tooShort = password.length > 0 && password.length < SALTY.minPasswordLength;

  const create = async () => {
    setBusy(true);
    try {
      await api.users.create(username.trim(), password, isAdmin);
      setUsername("");
      setPassword("");
      setIsAdmin(false);
      await onAdded();
      notify("User added");
    } catch (e) {
      notify(e.message || "Could not add that user", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={styles.section}>
      <Subtitle2 as="h3">Add a user</Subtitle2>
      <div className={styles.addRow}>
        <Input
          className={styles.rowInput}
          placeholder="Username"
          value={username}
          onChange={(_, d) => setUsername(d.value)}
        />
        <PasswordInput
          className={styles.rowInput}
          placeholder={`Password (${SALTY.minPasswordLength}+ characters)`}
          value={password}
          onChange={(_, d) => setPassword(d.value)}
        />
        <Checkbox label="Admin" checked={isAdmin} onChange={(_, d) => setIsAdmin(!!d.checked)} />
        <Button
          appearance="primary"
          icon={<Add20Regular />}
          disabled={busy || !username.trim() || !password || tooShort}
          onClick={create}
        >
          Add
        </Button>
      </div>
    </div>
  );
}

export function UsersDialog({ open, onClose, notify, ask }) {
  const styles = useStyles();
  const [rows, setRows] = useState(null);

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
                    {/* `description`, not `label`: the button has visible text, and a label
                        tooltip would replace that text as its accessible name. */}
                    <Tooltip
                      content={u.isAdmin ? "Remove administrator" : "Make administrator"}
                      relationship="description"
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
                            secret: true,
                            confirmLabel: "Reset",
                            onConfirm: async (value) => {
                              await api.users.setPassword(u.id, value);
                              notify("Password reset");
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
                              await api.users.remove(u.id);
                              load();
                              notify("User deleted");
                            },
                          })
                        }
                      />
                    </Tooltip>
                  </div>
                ))}

                <AddUser styles={styles} onAdded={load} notify={notify} />
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

/**
 * Rows in survivor order: the one a merge should keep comes first. The rule the native apps' duplicate
 * scan uses for a user-facing merge -- most recipes, ties to the oldest row -- so the common case is
 * one click, with the name that survives still on screen to be changed. Ids are UUIDv7, so the
 * smallest is the earliest-created.
 */
function rankForMerge(rows) {
  return [...rows].sort((a, b) => b.recipeCount - a.recipeCount || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** "A", "A and B", "A, B, and C". */
function andList(items) {
  if (items.length <= 1) return items[0] ?? "";
  if (items.length === 2) return `${items[0]} and ${items[1]}`;
  return `${items.slice(0, -1).join(", ")}, and ${items[items.length - 1]}`;
}

/** Title for the delete confirmation, naming the single row where there is one. */
function deletionTitle(rows, k) {
  return rows.length === 1 ? `Delete "${rows[0].name}"?` : `Delete ${rows.length} ${k.label.toLowerCase()}?`;
}

/**
 * What the delete costs, in recipes. Recipes are never deleted -- they only lose the classification,
 * which is the part worth saying out loud before a category disappears from thirty of them. An
 * unused row still gets a confirmation, just a shorter one: a delete that asks only sometimes is a
 * delete you stop reading. Word for word the Compose app's `classifierDeletionMessage`.
 */
function deletionMessage(rows, k) {
  const affected = rows.reduce((n, r) => n + r.recipeCount, 0);
  if (affected === 0) return "This cannot be undone.";
  // One recipe can hold two of the categories/tags being deleted, so a multi-row total is an upper
  // bound. A recipe has only one course, so that total is exact.
  const many = rows.length > 1;
  const mayDoubleCount = many && k.kind !== "course";
  const count = affected === 1 ? "1 recipe" : `${affected} recipes`;
  const recipes = mayDoubleCount ? `up to ${count}` : count;
  if (k.kind === "course") {
    const subject = many ? "these courses" : "this course";
    const remain =
      affected === 1
        ? "That recipe will remain, but its course selection will be removed."
        : "Those recipes will remain, but their course selection will be removed.";
    return `${recipes} ${affected === 1 ? "is" : "are"} currently classified with ${subject}. ${remain} This cannot be undone.`;
  }
  return many
    ? `These ${k.label.toLowerCase()} are being used by ${recipes}. Removing them will remove them from those recipes, but the recipes will remain. This cannot be undone.`
    : `This ${k.singular} is being used by ${recipes}. Removing it will remove it from those recipes, but the recipes will remain. This cannot be undone.`;
}

/** Title for the merge dialog. Counts rather than names: the names are listed right below it. */
function mergeTitle(rows, k) {
  return `Merge ${rows.length} ${rows.length === 1 ? k.singular : k.label.toLowerCase()}`;
}

/**
 * What the merge does, in the survivor's own name -- which is the whole reason the dialog exists,
 * since the survivor's spelling is the one that remains. Word for word the Compose app's
 * `classifierMergeMessage`, so the three clients say the same thing about the same action.
 */
function mergeMessage(rows, survivorId, k) {
  const survivor = rows.find((r) => r.id === survivorId);
  if (!survivor) return `Choose which ${k.singular} to keep.`;
  const losing = rows.filter((r) => r.id !== survivor.id);
  if (losing.length === 0) return `Nothing to merge into "${survivor.name}".`;
  const subject =
    losing.length <= 3
      ? andList(losing.map((r) => `"${r.name}"`))
      : `The other ${losing.length} ${k.label.toLowerCase()}`;
  const verb =
    losing.length === 1 ? "will be deleted, and its recipes" : "will be deleted, and their recipes";
  const outcome =
    k.kind === "course" ? `will use "${survivor.name}" instead` : `will be added to "${survivor.name}"`;
  return `${subject} ${verb} ${outcome}. The recipes themselves are not deleted. This cannot be undone.`;
}

/**
 * Add, rename, delete -- and, in Select mode, merge or delete several at once. Select is a mode
 * rather than a permanent column of checkboxes, as in the Compose app: it exists for the two jobs
 * that are miserable a row at a time, clearing out a drift of unused tags and folding "Deserts" /
 * "desserts" / "Dessert " back into one. Both run on the server (`api.classifiers.merge` and
 * `removeMany`), which re-points or clears the recipes and moves their stamps so the native clients
 * pick the change up on their next sync.
 */
export function ManageLibraryDialog({
  open,
  onClose,
  courses,
  categories,
  tags,
  recipes,
  onChanged,
  onRecipesTouched,
  notify,
  ask,
}) {
  const styles = useStyles();
  const [kind, setKind] = useState("category");
  const [draftName, setDraftName] = useState("");
  const [edits, setEdits] = useState({});
  /* Select mode and what is checked. Switching tabs leaves it: ids only mean anything in the tab
     they came from. */
  const [selecting, setSelecting] = useState(false);
  const [checked, setChecked] = useState([]);
  /* The merge being confirmed: the rows captured when it opened, ranked, and the survivor picked so
     far. Captured rather than derived, because the list under the dialog keeps updating. */
  const [merge, setMerge] = useState(null);
  const [merging, setMerging] = useState(false);

  const items = kind === "category" ? categories : kind === "course" ? courses : tags;
  const kindInfo = KINDS.find((k) => k.kind === kind);

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

  const leaveSelectMode = () => {
    setSelecting(false);
    setChecked([]);
  };

  const showKind = (next) => {
    if (next === kind) return;
    setKind(next);
    leaveSelectMode();
  };

  const toggleChecked = (id, on) =>
    setChecked((ids) => (on ? [...new Set([...ids, id])] : ids.filter((x) => x !== id)));

  /** Opens the merge dialog over the rows checked, the survivor pre-picked. Two is the minimum. */
  const requestMerge = () => {
    const rows = rankForMerge(
      items.filter((i) => checked.includes(i.id)).map((i) => ({ ...i, recipeCount: countFor(i.id) })),
    );
    if (rows.length < 2) return;
    setMerge({ candidates: rows, survivorId: rows[0].id });
  };

  const confirmMerge = async () => {
    if (!merge) return;
    const { candidates, survivorId } = merge;
    const survivor = candidates.find((c) => c.id === survivorId);
    const duplicateIds = candidates.filter((c) => c.id !== survivorId).map((c) => c.id);
    if (!survivor || duplicateIds.length === 0) return;
    setMerging(true);
    try {
      const result = await api.classifiers.merge(kind, survivorId, duplicateIds);
      setMerge(null);
      leaveSelectMode();
      await onRecipesTouched(result);
      const n = result.removedIds.length;
      notify(`Merged ${n} ${n === 1 ? kindInfo.singular : kindInfo.label.toLowerCase()} into ${survivor.name}`);
    } catch (e) {
      notify(e.message || "Could not merge", "error");
    } finally {
      setMerging(false);
    }
  };

  /**
   * Opens the delete confirmation for the rows given, one or many. Every delete is confirmed: one
   * that asks only sometimes is one you stop reading. The rows are captured with their counts as
   * they were shown, since the list under the dialog keeps updating.
   */
  const requestDeletion = (rows) => {
    const captured = rows.map((r) => ({ ...r, recipeCount: countFor(r.id) }));
    if (captured.length === 0) return;
    ask({
      title: deletionTitle(captured, kindInfo),
      body: deletionMessage(captured, kindInfo),
      confirmLabel: "Delete",
      onConfirm: async () => {
        const result = await api.classifiers.removeMany(kind, captured.map((r) => r.id));
        leaveSelectMode();
        await onRecipesTouched(result);
        const n = result.removedIds.length;
        notify(`Deleted ${n} ${n === 1 ? kindInfo.singular : kindInfo.label.toLowerCase()}`);
      },
    });
  };

  const remove = (item) => requestDeletion([item]);

  const add = async () => {
    const name = draftName.trim();
    if (!name) return;
    try {
      await api.classifiers.create(kind, name);
      // Cleared only once it is actually saved. Clearing first meant a failed request took the
      // typed name with it and left the reader with a toast and an empty box.
      setDraftName("");
      await onChanged();
    } catch (e) {
      notify(e.message || "Could not add", "error");
    }
  };

  // Rename boxes hold what was typed into them until the rename lands. A failed one used to stay
  // here for the life of the tab, so reopening the dialog showed a name the server had never taken.
  useEffect(() => {
    if (!open) {
      setEdits({});
      setSelecting(false);
      setChecked([]);
      setMerge(null);
    }
  }, [open]);

  return (
    <>
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
            Edit classifiers
          </DialogTitle>
          <DialogContent>
            <TabList selectedValue={kind} onTabSelect={(_, d) => showKind(d.value)}>
              {KINDS.map((k) => (
                <Tab key={k.kind} value={k.kind}>
                  {k.label}
                </Tab>
              ))}
            </TabList>

            {/* Select needs something to merge into, so it is offered only from two rows up. */}
            <div className={styles.selectBar}>
              {selecting ? (
                <>
                  <span className={styles.selectCount} aria-live="polite">
                    {checked.length === 0
                      ? `Select ${kindInfo.label.toLowerCase()} to merge`
                      : `${checked.length} selected`}
                  </span>
                  <Button
                    icon={<Merge20Regular />}
                    disabled={checked.length < 2}
                    onClick={requestMerge}
                  >
                    Merge…
                  </Button>
                  <Button
                    icon={<Delete20Regular />}
                    disabled={checked.length === 0}
                    onClick={() => requestDeletion(items.filter((i) => checked.includes(i.id)))}
                  >
                    Delete
                  </Button>
                  <Button appearance="subtle" onClick={leaveSelectMode}>
                    Done
                  </Button>
                </>
              ) : (
                <Button
                  appearance="subtle"
                  disabled={items.length < 2}
                  onClick={() => setSelecting(true)}
                >
                  Select
                </Button>
              )}
            </div>

            <div className={styles.rows}>
              {items.length === 0 ? (
                <Body1>Nothing here yet.</Body1>
              ) : selecting ? (
                items.map((item) => (
                  <div key={item.id} className={styles.checkRow}>
                    <div className={styles.rowText}>
                      <Checkbox
                        label={item.name || "(unnamed)"}
                        checked={checked.includes(item.id)}
                        onChange={(_, d) => toggleChecked(item.id, !!d.checked)}
                      />
                    </div>
                    <span className={styles.count}>
                      {countFor(item.id)} recipe{countFor(item.id) === 1 ? "" : "s"}
                    </span>
                  </div>
                ))
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

            {selecting ? null : (
              <div className={styles.addRow}>
                <Input
                  className={styles.rowInput}
                  placeholder={`New ${kindInfo.singular}`}
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
            )}
          </DialogContent>
        </DialogBody>
      </DialogSurface>
    </Dialog>

      {/* Confirms a merge, and is the only place the survivor is chosen -- which matters because the
          survivor's spelling is the one that remains. A dialog of its own rather than the app's
          confirm(): the choice is a list, and the outcome is irreversible. A sibling of the manager
          rather than a child: a Dialog given two children reads the first as its trigger. */}
      <Dialog open={!!merge} onOpenChange={(_, d) => !d.open && !merging && setMerge(null)}>
        <DialogSurface>
          <DialogBody>
            <DialogTitle>{merge ? mergeTitle(merge.candidates, kindInfo) : ""}</DialogTitle>
            <DialogContent>
              {merge ? (
                <>
                  <Field label={`${kindInfo.singular[0].toUpperCase()}${kindInfo.singular.slice(1)} to keep`}>
                    <RadioGroup
                      value={merge.survivorId}
                      onChange={(_, d) => setMerge((m) => m && { ...m, survivorId: d.value })}
                    >
                      {merge.candidates.map((c) => (
                        <div key={c.id} className={styles.candidate}>
                          <div className={styles.rowText}>
                            <Radio value={c.id} label={c.name || "(unnamed)"} />
                          </div>
                          <span className={styles.count}>
                            {c.recipeCount} recipe{c.recipeCount === 1 ? "" : "s"}
                          </span>
                        </div>
                      ))}
                    </RadioGroup>
                  </Field>
                  <p className={styles.radioHint}>{mergeMessage(merge.candidates, merge.survivorId, kindInfo)}</p>
                </>
              ) : null}
            </DialogContent>
            <DialogActions>
              <Button appearance="secondary" disabled={merging} onClick={() => setMerge(null)}>
                Cancel
              </Button>
              <Button appearance="primary" disabled={merging || !merge?.survivorId} onClick={confirmMerge}>
                {merging ? <Spinner size="tiny" /> : "Merge"}
              </Button>
            </DialogActions>
          </DialogBody>
        </DialogSurface>
      </Dialog>
    </>
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
              hint="Most sites that publish JSON-LD recipe data will import; unsupported sites will not fetch any data."
            >
              <Input
                value={url}
                placeholder="https://…"
                onChange={(_, d) => setUrl(d.value)}
                // `busy` too: the button disables itself while fetching but the key did not, so
                // two Enters imported twice and the second draft replaced the first.
                onKeyDown={(e) => e.key === "Enter" && !busy && url.trim() && run()}
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

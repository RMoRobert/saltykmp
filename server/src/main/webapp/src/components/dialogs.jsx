import { useState } from "react";
import {
  Body1,
  Button,
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
  Tab,
  TabList,
  Tooltip,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { Add20Regular, Delete20Regular, Dismiss24Regular } from "@fluentui/react-icons";

import { SALTY, api } from "../api";
import { uuidv7, wireNow } from "../model";

const useStyles = makeStyles({
  rows: { display: "grid", gap: tokens.spacingVerticalXS, marginTop: tokens.spacingVerticalM },
  row: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalXS },
  rowInput: { flex: 1 },
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
});

/* ------------------------------------------------------------------- about -- */

export function AboutDialog({ open, onClose }) {
  const styles = useStyles();
  return (
    <Dialog open={open} onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface>
        <DialogBody>
          <DialogTitle>About Salty</DialogTitle>
          <DialogContent>
            <div className={styles.kv}>
              <span className={styles.key}>Version</span>
              <span>{SALTY.version || "—"}</span>
              <span className={styles.key}>Built</span>
              <span>{SALTY.buildTime || "—"}</span>
              <span className={styles.key}>Signed in as</span>
              <span>{SALTY.username || "—"}</span>
            </div>
          </DialogContent>
          <DialogActions>
            <Button appearance="primary" onClick={onClose}>
              Close
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

/* ------------------------------------------------------------- preferences -- */

export function PreferencesDialog({ open, onClose, notify }) {
  const styles = useStyles();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const tooShort = newPassword.length > 0 && newPassword.length < SALTY.minPasswordLength;

  const submit = async () => {
    setBusy(true);
    try {
      await api.account.changePassword(currentPassword, newPassword);
      // The server signs every *other* device out and leaves this session alone; say so, because a
      // password change that silently revoked your phone would be a surprise worth avoiding.
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
      <DialogSurface>
        <DialogBody>
          <DialogTitle>Settings</DialogTitle>
          <DialogContent>
            <div className={styles.fields}>
              <MessageBar intent="info">
                <MessageBarBody>
                  Light and dark follow your system setting; there is no separate control.
                </MessageBarBody>
              </MessageBar>

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
            </div>
          </DialogContent>
          <DialogActions>
            <Button appearance="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button
              appearance="primary"
              disabled={busy || !currentPassword || !newPassword || tooShort}
              onClick={submit}
            >
              Change password
            </Button>
          </DialogActions>
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

  const remove = async (item) => {
    const n = countFor(item.id);
    const warning = n
      ? `${item.name} is used by ${n} recipe${n === 1 ? "" : "s"}. Delete it anyway?`
      : `Delete ${item.name}?`;
    if (!window.confirm(warning)) return;
    try {
      await api.classifiers.remove(kind, item.id);
      await onChanged();
    } catch (e) {
      notify(e.message || "Could not delete", "error");
    }
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
      <DialogSurface style={{ maxWidth: "42rem" }}>
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
              <Button appearance="primary" icon={<Add20Regular />} disabled={!draftName.trim()} onClick={add}>
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

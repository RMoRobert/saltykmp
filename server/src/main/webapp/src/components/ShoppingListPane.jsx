import { useCallback, useEffect, useRef, useState } from "react";
import {
  Button,
  Checkbox,
  Input,
  Menu,
  MenuDivider,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Spinner,
  Subtitle1,
  Subtitle2,
  Textarea,
  Tooltip,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import {
  Add24Regular,
  Broom20Regular,
  Cart24Regular,
  Delete20Regular,
  DocumentText20Regular,
  MoreHorizontal24Regular,
  Rename20Regular,
  Star20Filled,
  Star20Regular,
  TaskListSquareLtr20Regular,
  TextHeader120Regular,
} from "@fluentui/react-icons";

import { api } from "../api";
import { newRow, uuidv7, wireNow } from "../model";

const useStyles = makeStyles({
  head: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalXS,
    padding: `${tokens.spacingVerticalM} ${tokens.spacingHorizontalM} ${tokens.spacingVerticalS}`,
  },
  title: { flex: 1 },
  scroll: { flex: 1, overflow: "hidden auto" },
  list: { listStyle: "none", margin: 0, padding: 0 },
  row: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalS,
    width: "100%",
    boxSizing: "border-box",
    padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
    border: "none",
    background: "none",
    textAlign: "start",
    cursor: "pointer",
    color: "inherit",
    ":hover": { backgroundColor: tokens.colorNeutralBackground1Hover },
  },
  selected: { backgroundColor: tokens.colorNeutralBackground1Selected },
  sub: { color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200 },
  bar: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalS,
    padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalL}`,
    borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
  },
  barSpace: { flex: 1 },
  doc: {
    maxWidth: "48rem",
    margin: "0 auto",
    padding: `${tokens.spacingVerticalL} ${tokens.spacingHorizontalXXL}`,
  },
  itemRow: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalXS,
    paddingBlock: "2px",
  },
  itemText: { flex: 1 },
  done: { textDecoration: "line-through", color: tokens.colorNeutralForeground3 },
  heading: {
    fontWeight: tokens.fontWeightSemibold,
    marginTop: tokens.spacingVerticalM,
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalXS,
  },
  important: { color: tokens.colorPaletteMarigoldForeground1 },
  addRow: { display: "flex", gap: tokens.spacingHorizontalS, marginTop: tokens.spacingVerticalM },
  empty: {
    height: "100%",
    display: "grid",
    placeContent: "center",
    justifyItems: "center",
    gap: tokens.spacingVerticalM,
    color: tokens.colorNeutralForeground3,
  },
});

/** Both shapes a list can take. Which one it is cannot change after it is made, as in the apps. */
const listKindLabel = (l) => (l.isFreeform ? "Markdown" : "Checklist");

function ListsIndex({ lists, selectedId, onSelect, onChanged, notify, ask }) {
  const styles = useStyles();

  const create = (isFreeform) =>
    ask({
      title: isFreeform ? "New markdown list" : "New checklist",
      prompt: "List name",
      confirmLabel: "Create",
      onConfirm: async (name) => {
        try {
          await api.shoppingLists.save({
            id: uuidv7(),
            name,
            isFreeform,
            // Exactly one of these carries the contents; the other stays null so the server's
            // column for it is left alone rather than being written empty.
            contentsForList: isFreeform ? null : [],
            contentsForFreeform: isFreeform ? "" : null,
            lastModifiedDate: wireNow(),
          });
          await onChanged();
          notify("List created");
        } catch (e) {
          notify(e.message || "Could not create the list", "error");
        }
      },
    });

  return (
    <>
      <div className={styles.head}>
        <Subtitle1 className={styles.title}>Shopping Lists</Subtitle1>
        <Menu>
          <MenuTrigger disableButtonEnhancement>
            <Tooltip content="New list" relationship="label">
              <Button appearance="subtle" icon={<Add24Regular />} />
            </Tooltip>
          </MenuTrigger>
          <MenuPopover>
            <MenuList>
              <MenuItem icon={<TaskListSquareLtr20Regular />} onClick={() => create(false)}>
                Checklist
              </MenuItem>
              <MenuItem icon={<DocumentText20Regular />} onClick={() => create(true)}>
                Markdown list
              </MenuItem>
            </MenuList>
          </MenuPopover>
        </Menu>
      </div>
      <div className={styles.scroll}>
        <ul className={styles.list} role="listbox" aria-label="Shopping lists">
          {lists.map((l) => (
            <li key={l.id}>
              <div
                role="option"
                aria-selected={l.id === selectedId}
                tabIndex={0}
                className={mergeClasses(styles.row, l.id === selectedId && styles.selected)}
                onClick={() => onSelect(l.id)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    onSelect(l.id);
                  }
                }}
              >
                <div>
                  <div>{l.name || "Untitled list"}</div>
                  <div className={styles.sub}>{listKindLabel(l)}</div>
                </div>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </>
  );
}

function ListDetail({ id, notify, ask, onChanged }) {
  const styles = useStyles();
  const [list, setList] = useState(null);
  /** The list as the server last agreed it: the base side of any three-way merge. */
  const base = useRef(null);
  const [loading, setLoading] = useState(false);
  const [newText, setNewText] = useState("");

  const load = useCallback(async () => {
    if (!id) {
      setList(null);
      return;
    }
    setLoading(true);
    try {
      const loaded = await api.shoppingLists.get(id);
      setList(loaded);
      base.current = structuredClone(loaded);
    } catch (e) {
      notify(e.message || "Could not open that list", "error");
    } finally {
      setLoading(false);
    }
  }, [id, notify]);

  useEffect(() => {
    load();
  }, [load]);

  /**
   * Saves carry `baseRevision`, which is what makes conflict detection possible: the server rejects
   * with 409 when the row moved under us and answers with its own copy. The native clients run a
   * three-way merge on that; this reloads and says so, which is honest rather than silent.
   */
  const persist = async (next) => {
    setList(next);
    try {
      const saved = await api.shoppingLists.save({
        ...next,
        baseRevision: next.revision,
        lastModifiedDate: wireNow(),
      });
      // The echo defines the new agreed base: it is what the server holds now, so a later merge
      // starts from it rather than from whatever this tab last typed.
      if (saved) {
        setList(saved);
        base.current = structuredClone(saved);
      }
      onChanged?.();
    } catch (e) {
      // 409 means someone else wrote since we loaded. Rather than picking a winner -- which would
      // silently drop a check-off made on a phone in the aisle -- hand both sides and the base to
      // the server and take back what the shared merge produces.
      if (e.status === 409) {
        try {
          const merged = await api.shoppingLists.resolve(id, base.current, next);
          if (merged) {
            setList(merged);
            base.current = structuredClone(merged);
            notify("This list changed elsewhere; both sets of changes were kept");
            onChanged?.();
          }
        } catch (again) {
          // A third write can land between the server's read and its write. One retry from the
          // copy it just handed back, then give up and reload rather than looping.
          if (again.status === 409 && again.data) {
            base.current = structuredClone(again.data);
            notify("This list is being changed on another device; reloading it", "warning");
          } else {
            notify(again.message || "Could not merge the list", "error");
          }
          load();
        }
      } else {
        notify(e.message || "Could not save the list", "error");
      }
    }
  };

  if (!id) {
    return (
      <div className={styles.empty}>
        <Cart24Regular style={{ width: 48, height: 48 }} />
        <span>Select a shopping list.</span>
      </div>
    );
  }
  if (loading || !list) {
    return (
      <div className={styles.empty}>
        <Spinner size="small" label="Loading…" />
      </div>
    );
  }

  const items = list.contentsForList ?? [];
  const setItems = (next) => persist({ ...list, contentsForList: next });
  const completed = items.filter((i) => i.isCompleted && !i.isHeading).length;

  const addItem = (isHeading) => {
    const text = isHeading ? "New section" : newText.trim();
    if (!text) return;
    setItems([
      ...items,
      { ...newRow(text), isCompleted: false, isImportant: false, isHeading: !!isHeading },
    ]);
    if (!isHeading) setNewText("");
  };

  return (
    <>
      <div className={styles.bar}>
        <Subtitle2>{list.name || "Untitled list"}</Subtitle2>
        <span className={styles.barSpace} />
        <Menu>
          <MenuTrigger disableButtonEnhancement>
            <Tooltip content="List actions" relationship="label">
              <Button appearance="subtle" icon={<MoreHorizontal24Regular />} />
            </Tooltip>
          </MenuTrigger>
          <MenuPopover>
            <MenuList>
              <MenuItem
                icon={<Rename20Regular />}
                onClick={() =>
                  ask({
                    title: "Rename list",
                    prompt: "List name",
                    initialValue: list.name || "",
                    confirmLabel: "Rename",
                    onConfirm: (name) => persist({ ...list, name }),
                  })
                }
              >
                Rename…
              </MenuItem>
              {list.isFreeform ? null : (
                <MenuItem
                  icon={<Broom20Regular />}
                  disabled={completed === 0}
                  onClick={() => setItems(items.filter((i) => i.isHeading || !i.isCompleted))}
                >
                  Clear completed ({completed})
                </MenuItem>
              )}
              <MenuDivider />
              <MenuItem
                icon={<Delete20Regular />}
                onClick={() =>
                  ask({
                    title: "Delete list",
                    body: `“${list.name || "Untitled list"}” will be removed from every device.`,
                    confirmLabel: "Delete",
                    onConfirm: async () => {
                      try {
                        await api.shoppingLists.remove(list.id);
                        await onChanged?.();
                        setList(null);
                        notify("List deleted");
                      } catch (e) {
                        notify(e.message || "Could not delete the list", "error");
                      }
                    },
                  })
                }
              >
                Delete list…
              </MenuItem>
            </MenuList>
          </MenuPopover>
        </Menu>
      </div>

      <div className={styles.scroll}>
        <div className={styles.doc}>
          {list.isFreeform ? (
            <Textarea
              resize="vertical"
              style={{ width: "100%", minHeight: "24rem" }}
              value={list.contentsForFreeform ?? ""}
              onChange={(_, d) => setList({ ...list, contentsForFreeform: d.value })}
              // On blur rather than on every keystroke: a save per character would be a request per
              // character, and every one of them a chance to lose a revision race with itself.
              onBlur={() => persist(list)}
            />
          ) : (
            <>
              {items.map((it, i) =>
                it.isHeading ? (
                  <div key={it.id} className={styles.heading}>
                    <Input
                      appearance="underline"
                      value={it.text}
                      onChange={(_, d) =>
                        setList({
                          ...list,
                          contentsForList: items.map((x, j) =>
                            j === i ? { ...x, text: d.value } : x,
                          ),
                        })
                      }
                      onBlur={() => persist(list)}
                    />
                    <Tooltip content="Remove" relationship="label">
                      <Button
                        appearance="subtle"
                        size="small"
                        icon={<Delete20Regular />}
                        onClick={() => setItems(items.filter((_, j) => j !== i))}
                      />
                    </Tooltip>
                  </div>
                ) : (
                  <div key={it.id} className={styles.itemRow}>
                    <Checkbox
                      checked={!!it.isCompleted}
                      onChange={(_, d) =>
                        setItems(
                          items.map((x, j) => (j === i ? { ...x, isCompleted: d.checked } : x)),
                        )
                      }
                      label={
                        <span
                          className={mergeClasses(
                            styles.itemText,
                            it.isCompleted && styles.done,
                            it.isImportant && styles.important,
                          )}
                        >
                          {it.text}
                        </span>
                      }
                    />
                    <span className={styles.barSpace} />
                    <Tooltip content="Important" relationship="label">
                      <Button
                        appearance="subtle"
                        size="small"
                        className={mergeClasses(it.isImportant && styles.important)}
                        icon={it.isImportant ? <Star20Filled /> : <Star20Regular />}
                        onClick={() =>
                          setItems(
                            items.map((x, j) =>
                              j === i ? { ...x, isImportant: !x.isImportant } : x,
                            ),
                          )
                        }
                      />
                    </Tooltip>
                    <Tooltip content="Remove" relationship="label">
                      <Button
                        appearance="subtle"
                        size="small"
                        icon={<Delete20Regular />}
                        onClick={() => setItems(items.filter((_, j) => j !== i))}
                      />
                    </Tooltip>
                  </div>
                ),
              )}

              <div className={styles.addRow}>
                <Input
                  style={{ flex: 1 }}
                  placeholder="Add an item"
                  value={newText}
                  onChange={(_, d) => setNewText(d.value)}
                  onKeyDown={(e) => e.key === "Enter" && addItem(false)}
                />
                <Button
                  appearance="primary"
                  icon={<Add24Regular />}
                  disabled={!newText.trim()}
                  onClick={() => addItem(false)}
                >
                  Add
                </Button>
                <Tooltip content="Add a section heading" relationship="label">
                  <Button icon={<TextHeader120Regular />} onClick={() => addItem(true)} />
                </Tooltip>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}

export default { List: ListsIndex, Detail: ListDetail };

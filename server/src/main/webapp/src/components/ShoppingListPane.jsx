import { useCallback, useEffect, useRef, useState } from "react";
import {
  Button,
  Checkbox,
  Hamburger,
  Input,
  List,
  ListItem,
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
  ArrowLeft24Regular,
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
  /* Layout only, as in RecipeList: the press, hover, focus ring and cursor are ListItem's. */
  row: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalS,
    boxSizing: "border-box",
    padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
    cursor: "pointer",
  },
  rowSelected: { backgroundColor: "var(--colorSaltySelectedBackground)" },
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
  freeform: { width: "100%", minHeight: "24rem" },
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
  addInput: { flex: 1 },
  empty: {
    height: "100%",
    display: "grid",
    placeContent: "center",
    justifyItems: "center",
    gap: tokens.spacingVerticalM,
    color: tokens.colorNeutralForeground3,
  },
  /* Fluent icons draw at 1em, so the 48px empty-state glyph is a font size, not a second icon. */
  emptyIcon: { fontSize: "48px" },
});

/** Both shapes a list can take. Which one it is cannot change after it is made, as in the apps. */
const listKindLabel = (l) => (l.isFreeform ? "Markdown" : "Checklist");

/**
 * The lists index: Fluent's List in single-selection mode, exactly as the recipe list is, so the
 * two columns share their listbox semantics, roving focus and keyboard handling rather than one
 * of them rebuilding those by hand.
 */
export function ShoppingListsIndex({
  lists,
  selectedId,
  onSelect,
  onChanged,
  notify,
  ask,
  onShowRail,
}) {
  const styles = useStyles();

  const create = (isFreeform) =>
    ask({
      title: isFreeform ? "New markdown list" : "New checklist",
      prompt: "List name",
      confirmLabel: "Create",
      onConfirm: async (name) => {
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
      },
    });

  return (
    <>
      <div className={styles.head}>
        {onShowRail ? (
          <Tooltip content="Show library" relationship="label">
            <Hamburger onClick={onShowRail} />
          </Tooltip>
        ) : null}
        <Subtitle1 className={styles.title}>Shopping lists</Subtitle1>
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
        <List
          className={styles.list}
          aria-label="Shopping lists"
          selectionMode="single"
          selectedItems={selectedId ? [selectedId] : []}
          onSelectionChange={(_, data) => {
            const [id] = [...data.selectedItems];
            // Clicking the open list again would otherwise deselect it and empty the pane.
            if (id) onSelect(id);
          }}
        >
          {lists.map((l) => (
            <ListItem
              key={l.id}
              value={l.id}
              checkmark={null}
              className={mergeClasses(styles.row, l.id === selectedId && styles.rowSelected)}
            >
              <div>
                <div>{l.name || "Untitled list"}</div>
                <div className={styles.sub}>{listKindLabel(l)}</div>
              </div>
            </ListItem>
          ))}
        </List>
      </div>
    </>
  );
}

export function ShoppingListDetail({ id, notify, ask, onChanged, onDeleted, onBack }) {
  const styles = useStyles();
  const [list, setList] = useState(null);
  /** The list as the server last agreed it: the base side of any three-way merge. */
  const base = useRef(null);
  /**
   * The revision the next write is based on, kept out of render state deliberately.
   *
   * A save built from `list` carried the revision that was on screen when the edit was made, and
   * the server bumps the revision on every accepted write -- so ticking two boxes quickly sent the
   * same base twice and the second came back 409, reporting "this list changed elsewhere" for a
   * change made in this very tab. Writes are queued below, and each one reads this at the moment it
   * is actually sent.
   */
  const revision = useRef(null);
  /** Serialises writes, so the second edit is sent against the revision the first one produced. */
  const queue = useRef(Promise.resolve());
  /** How many writes are queued, so only the last one applies its echo to the screen. */
  const pending = useRef(0);
  /** Which load is current; a slower earlier list must not land under a later selection. */
  const loadSerial = useRef(0);
  const [loading, setLoading] = useState(false);
  const [newText, setNewText] = useState("");

  const adopt = useCallback((row) => {
    base.current = structuredClone(row);
    revision.current = row.revision ?? null;
  }, []);

  const load = useCallback(async () => {
    if (!id) {
      setList(null);
      return;
    }
    const serial = (loadSerial.current += 1);
    setLoading(true);
    try {
      const loaded = await api.shoppingLists.get(id);
      // Switching lists quickly used to leave the earlier list's items under the later list's name
      // -- and `base` wrong for the next merge, which is how a merge posts one list's contents to
      // another list's id.
      if (serial !== loadSerial.current) return;
      setList(loaded);
      adopt(loaded);
    } catch (e) {
      if (serial === loadSerial.current) notify(e.message || "Could not open that list", "error");
    } finally {
      if (serial === loadSerial.current) setLoading(false);
    }
  }, [adopt, id, notify]);

  useEffect(() => {
    load();
  }, [load]);

  /**
   * Saves carry `baseRevision`, which is what makes conflict detection possible: the server rejects
   * with 409 when the row moved under us and answers with its own copy. The native clients run a
   * three-way merge on that; this reloads and says so, which is honest rather than silent.
   */
  const send = async (next) => {
    try {
      const saved = await api.shoppingLists.save({
        ...next,
        // The revision as of this moment, not as of when the edit was made: an earlier queued write
        // has already moved it on.
        baseRevision: revision.current,
        lastModifiedDate: wireNow(),
      });
      // The echo defines the new agreed base: it is what the server holds now, so a later merge
      // starts from it rather than from whatever this tab last typed.
      if (saved) {
        adopt(saved);
        // Only when nothing else is waiting. Applying an echo while a later edit is still queued
        // put the screen back to the state before that edit -- the box you had just ticked
        // visibly un-ticked itself until the next response arrived.
        if (pending.current <= 1) setList(saved);
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
            adopt(merged);
            if (pending.current <= 1) setList(merged);
            notify("This list changed elsewhere; both sets of changes were kept");
            onChanged?.();
          }
        } catch (again) {
          // A third write can land between the server's read and its write. One retry from the
          // copy it just handed back, then give up and reload rather than looping.
          if (again.status === 409 && again.data) {
            adopt(again.data);
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

  /**
   * Queues a write. Serialised, so the next one is sent against the revision this one produces.
   */
  const persist = (next) => {
    setList(next);
    pending.current += 1;
    queue.current = queue.current.then(() => send(next)).finally(() => {
      pending.current -= 1;
    });
    return queue.current;
  };

  /**
   * For the fields that save on blur. Leaving a field you did not change must not be a write:
   * every save bumps the revision, and a revision bumped for nothing is a merge some other device
   * then has to do for nothing.
   */
  const persistIfChanged = () => {
    if (JSON.stringify(list) !== JSON.stringify(base.current)) persist(list);
  };

  if (!id) {
    return (
      <div className={styles.empty}>
        <Cart24Regular className={styles.emptyIcon} />
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
        {onBack ? (
          <Tooltip content="Back to the lists" relationship="label">
            <Button appearance="subtle" icon={<ArrowLeft24Regular />} onClick={onBack} />
          </Tooltip>
        ) : null}
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
                      await api.shoppingLists.remove(list.id);
                      await onChanged?.();
                      // The parent owns which list is open. Left to itself this pane would keep
                      // the deleted id and sit on its spinner waiting for a list that is gone.
                      onDeleted?.();
                      notify("List deleted");
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
              className={styles.freeform}
              value={list.contentsForFreeform ?? ""}
              onChange={(_, d) => setList({ ...list, contentsForFreeform: d.value })}
              // On blur rather than on every keystroke: a save per character would be a request per
              // character, and every one of them a chance to lose a revision race with itself.
              onBlur={persistIfChanged}
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
                      onBlur={persistIfChanged}
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
                  className={styles.addInput}
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

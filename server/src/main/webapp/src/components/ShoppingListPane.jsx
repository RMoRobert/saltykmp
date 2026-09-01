import { useEffect, useState } from "react";
import {
  Button,
  Checkbox,
  Input,
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
  Cart24Regular,
  Delete20Regular,
  Star20Filled,
  Star20Regular,
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
  bar: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalS,
    padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalL}`,
    borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
  },
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
  heading: { fontWeight: tokens.fontWeightSemibold, marginTop: tokens.spacingVerticalM },
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

function ListsIndex({ lists, selectedId, onSelect, onChanged, notify }) {
  const styles = useStyles();

  const create = async () => {
    const name = window.prompt("Name the new list");
    if (!name) return;
    try {
      await api.shoppingLists.save({
        id: uuidv7(),
        name,
        isFreeform: false,
        contentsForList: [],
        lastModifiedDate: wireNow(),
      });
      await onChanged();
      notify("List created");
    } catch (e) {
      notify(e.message || "Could not create the list", "error");
    }
  };

  return (
    <>
      <div className={styles.head}>
        <Subtitle1 className={styles.title}>Shopping Lists</Subtitle1>
        <Tooltip content="New list" relationship="label">
          <Button appearance="subtle" icon={<Add24Regular />} onClick={create} />
        </Tooltip>
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
                {l.name || "Untitled list"}
              </div>
            </li>
          ))}
        </ul>
      </div>
    </>
  );
}

function ListDetail({ id, notify }) {
  const styles = useStyles();
  const [list, setList] = useState(null);
  const [loading, setLoading] = useState(false);
  const [newText, setNewText] = useState("");

  useEffect(() => {
    if (!id) {
      setList(null);
      return;
    }
    let alive = true;
    setLoading(true);
    api.shoppingLists
      .get(id)
      .then((l) => alive && setList(l))
      .catch((e) => alive && notify(e.message || "Could not open that list", "error"))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [id, notify]);

  /**
   * Saves carry `baseRevision`, which is what makes conflict detection possible: the server rejects
   * with 409 when the row moved under us, and answers with its own copy. Recovering from that is
   * the native clients' three-way merge and is out of scope here -- this reloads and says so.
   */
  const persist = async (next) => {
    setList(next);
    try {
      const saved = await api.shoppingLists.save({
        ...next,
        baseRevision: next.revision,
        lastModifiedDate: wireNow(),
      });
      if (saved) setList(saved);
    } catch (e) {
      if (e.status === 409) {
        notify("This list changed elsewhere; reloading it", "warning");
        setList(await api.shoppingLists.get(id));
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

  return (
    <>
      <div className={styles.bar}>
        <Subtitle2>{list.name || "Untitled list"}</Subtitle2>
      </div>
      <div className={styles.scroll}>
        <div className={styles.doc}>
          {list.isFreeform ? (
            <Textarea
              resize="vertical"
              style={{ width: "100%", minHeight: "16rem" }}
              value={list.contentsForFreeform ?? ""}
              onChange={(_, d) => setList({ ...list, contentsForFreeform: d.value })}
              onBlur={() => persist(list)}
            />
          ) : (
            <>
              {items.map((it, i) =>
                it.isHeading ? (
                  <div key={it.id} className={styles.heading}>
                    {it.text}
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
                    <span style={{ flex: 1 }} />
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
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && newText.trim()) {
                      setItems([
                        ...items,
                        { ...newRow(newText.trim()), isCompleted: false, isImportant: false, isHeading: false },
                      ]);
                      setNewText("");
                    }
                  }}
                />
                <Button
                  appearance="primary"
                  icon={<Add24Regular />}
                  disabled={!newText.trim()}
                  onClick={() => {
                    setItems([
                      ...items,
                      { ...newRow(newText.trim()), isCompleted: false, isImportant: false, isHeading: false },
                    ]);
                    setNewText("");
                  }}
                >
                  Add
                </Button>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}

export default { List: ListsIndex, Detail: ListDetail };

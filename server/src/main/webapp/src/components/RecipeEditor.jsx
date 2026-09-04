import { useEffect, useRef, useState } from "react";
import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Button,
  Dropdown,
  Field,
  Input,
  Option,
  Rating,
  SpinButton,
  Subtitle2,
  Switch,
  Textarea,
  Tooltip,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import {
  Add20Regular,
  ArrowDown20Regular,
  ArrowUp20Regular,
  Delete20Regular,
  Image20Regular,
  ReOrderDotsVertical20Regular,
  TextT20Regular,
} from "@fluentui/react-icons";

import { IMAGE_TYPES, MAX_IMAGE_BYTES, imageUrl } from "../api";
import { DIFFICULTIES, NUTRITION_GROUPS, cleanNutrition, newRow } from "../model";

const useStyles = makeStyles({
  bar: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalS,
    padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalL}`,
    borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
  },
  barSpace: { flex: 1 },
  scroll: { flex: 1, overflow: "hidden auto" },
  doc: {
    maxWidth: "56rem",
    margin: "0 auto",
    padding: `${tokens.spacingVerticalL} ${tokens.spacingHorizontalXXL} ${tokens.spacingVerticalXXXL}`,
    display: "flex",
    flexDirection: "column",
    gap: tokens.spacingVerticalM,
  },
  grid: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit, minmax(13rem, 1fr))",
    gap: tokens.spacingHorizontalM,
    /* Fluent's Dropdown carries a 250px min-width of its own, wider than a 13rem track, so at
       tablet widths — three columns on an iPad mini — each dropdown spilled over the field beside
       it. Let them shrink to their track like every other control here. */
    "& .fui-Dropdown": { minWidth: 0 },
  },
  switches: { display: "flex", gap: tokens.spacingHorizontalXXL, flexWrap: "wrap" },
  section: { marginTop: tokens.spacingVerticalL },
  sectionHead: {
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: tokens.spacingVerticalS,
  },
  rowActions: { display: "flex", gap: tokens.spacingHorizontalXXS },
  row: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalXS,
    marginBottom: tokens.spacingVerticalXS,
  },
  rowInput: { flex: 1 },
  headingInput: { fontWeight: tokens.fontWeightSemibold },
  addRow: { display: "flex", gap: tokens.spacingHorizontalS, marginTop: tokens.spacingVerticalXS },
  pairRow: {
    display: "grid",
    gridTemplateColumns: "1fr 1fr auto",
    gap: tokens.spacingHorizontalS,
    marginBottom: tokens.spacingVerticalXS,
  },
  blockRow: { marginBottom: tokens.spacingVerticalM, display: "grid", gap: tokens.spacingVerticalXS },
  /* Where the row will land if dropped now. A line, not a reflowed list: moving rows around on
     every dragover makes the target you are aiming at slide out from under the cursor. */
  dropTarget: { borderTop: `2px solid ${tokens.colorBrandStroke1}` },
  dragging: { opacity: 0.4 },
  handle: { cursor: "grab", color: tokens.colorNeutralForeground3, display: "flex" },
  imageRow: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalM },
  tagRow: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalXS },
  preview: {
    width: "120px",
    height: "120px",
    objectFit: "cover",
    borderRadius: tokens.borderRadiusMedium,
    backgroundColor: tokens.colorNeutralBackground3,
  },
});

/**
 * The recipe photo.
 *
 * Nothing is sent until Save. A picked file is previewed from a blob URL and staged; a removal is
 * staged the same way. That keeps "Cancel leaves no trace" true of the image as well as the text,
 * which it would not be if the upload fired on selection.
 */
function ImageField({ styles, recipe, file, removed, onPick, onRemove, notify }) {
  const input = useRef(null);
  const [previewUrl, setPreviewUrl] = useState(null);

  // Created and revoked by the SAME effect. Split across a useMemo and an effect it survived only
  // one mount: StrictMode runs mount, cleanup, mount in development, and the cleanup revoked the URL
  // the memo was still handing back -- so a freshly picked photo previewed as a broken image.
  // A blob URL is held by the document until revoked, so trying five photos would leak five of them.
  useEffect(() => {
    if (!file) {
      setPreviewUrl(null);
      return undefined;
    }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  const shown = previewUrl || (removed ? null : imageUrl(recipe));

  const pick = (e) => {
    const chosen = e.target.files?.[0];
    e.target.value = ""; // so picking the same file twice still fires a change
    if (!chosen) return;
    // Mirrors the server, which identifies the format from the bytes and caps the request size.
    // Checking here turns a 25 MB round trip ending in 415 into an immediate, specific message.
    if (!IMAGE_TYPES.includes(chosen.type)) {
      notify("Images must be JPEG, PNG or GIF", "error");
      return;
    }
    if (chosen.size > MAX_IMAGE_BYTES) {
      notify("That image is larger than 25 MB", "error");
      return;
    }
    onPick(chosen);
  };

  return (
    <Field label="Photo">
      <div className={styles.imageRow}>
        {shown ? (
          <img className={styles.preview} src={shown} alt="" />
        ) : (
          <div className={styles.preview} aria-hidden="true" />
        )}
        <div className={styles.rowActions}>
          <input ref={input} type="file" accept={IMAGE_TYPES.join(",")} hidden onChange={pick} />
          <Button icon={<Image20Regular />} onClick={() => input.current?.click()}>
            {shown ? "Replace…" : "Choose…"}
          </Button>
          {shown ? (
            <Button appearance="subtle" icon={<Delete20Regular />} onClick={onRemove}>
              Remove
            </Button>
          ) : null}
        </div>
      </div>
    </Field>
  );
}

/** One editable line of a list: the text, what kind of line it is, and where it sits. */
function ListRow({ styles, row, index, count, onChange, onMove, onRemove, allowMain, drag }) {
  return (
    <div
      className={mergeClasses(
        styles.row,
        drag.overIndex === index && styles.dropTarget,
        drag.fromIndex === index && styles.dragging,
      )}
      onDragOver={(e) => {
        e.preventDefault();
        drag.setOverIndex(index);
      }}
      onDrop={(e) => {
        e.preventDefault();
        drag.drop(index);
      }}
    >
      {/* Only the handle is draggable, not the row: a draggable row swallows text selection in the
          input, which is where most of the time in this editor is spent. */}
      <span
        className={styles.handle}
        draggable
        aria-hidden="true"
        onDragStart={(e) => {
          // Firefox will not start a drag at all without payload on the event.
          e.dataTransfer.setData("text/plain", String(index));
          e.dataTransfer.effectAllowed = "move";
          drag.setFromIndex(index);
        }}
        onDragEnd={drag.end}
      >
        <ReOrderDotsVertical20Regular />
      </span>
      <Input
        className={styles.rowInput}
        input={row.isHeading ? { className: styles.headingInput } : undefined}
        value={row.text}
        placeholder={row.isHeading ? "Section heading" : "One line"}
        onChange={(_, d) => onChange({ ...row, text: d.value })}
      />
      <div className={styles.rowActions}>
        {allowMain && !row.isHeading ? (
          <Tooltip content="Main ingredient" relationship="label">
            <Button
              appearance={row.isMain ? "primary" : "subtle"}
              size="small"
              icon={<TextT20Regular />}
              onClick={() => onChange({ ...row, isMain: !row.isMain })}
            />
          </Tooltip>
        ) : null}
        <Tooltip content="Move up" relationship="label">
          <Button
            appearance="subtle"
            size="small"
            icon={<ArrowUp20Regular />}
            disabled={index === 0}
            onClick={() => onMove(index, -1)}
          />
        </Tooltip>
        <Tooltip content="Move down" relationship="label">
          <Button
            appearance="subtle"
            size="small"
            icon={<ArrowDown20Regular />}
            disabled={index === count - 1}
            onClick={() => onMove(index, 1)}
          />
        </Tooltip>
        <Tooltip content="Remove" relationship="label">
          <Button
            appearance="subtle"
            size="small"
            icon={<Delete20Regular />}
            onClick={() => onRemove(index)}
          />
        </Tooltip>
      </div>
    </div>
  );
}

function EditableList({ styles, title, rows, onRows, allowMain, addLabel }) {
  const [fromIndex, setFromIndex] = useState(null);
  const [overIndex, setOverIndex] = useState(null);

  const set = (i, next) => onRows(rows.map((r, j) => (j === i ? next : r)));
  const move = (i, d) => {
    const next = [...rows];
    const [item] = next.splice(i, 1);
    next.splice(i + d, 0, item);
    onRows(next);
  };
  const remove = (i) => onRows(rows.filter((_, j) => j !== i));

  const drag = {
    fromIndex,
    overIndex,
    setFromIndex,
    setOverIndex,
    end: () => {
      setFromIndex(null);
      setOverIndex(null);
    },
    drop: (to) => {
      if (fromIndex !== null && fromIndex !== to) {
        const next = [...rows];
        const [item] = next.splice(fromIndex, 1);
        // The line is drawn on TOP of the row under the cursor, so the drop means "before this
        // row". Removing the dragged row first shifts everything after it up by one, so a downward
        // drag has to aim one lower to land where the line was -- without this, dropping the first
        // row onto the third put it after the third.
        next.splice(to > fromIndex ? to - 1 : to, 0, item);
        onRows(next);
      }
      setFromIndex(null);
      setOverIndex(null);
    },
  };

  return (
    <section className={styles.section}>
      <div className={styles.sectionHead}>
        <Subtitle2 as="h2">{title}</Subtitle2>
      </div>
      {rows.map((row, i) => (
        <ListRow
          key={row.id}
          styles={styles}
          row={row}
          index={i}
          count={rows.length}
          onChange={(next) => set(i, next)}
          onMove={move}
          onRemove={remove}
          allowMain={allowMain}
          drag={drag}
        />
      ))}
      <div className={styles.addRow}>
        <Button
          appearance="subtle"
          icon={<Add20Regular />}
          onClick={() =>
            onRows([
              ...rows,
              newRow("", allowMain ? { isHeading: false, isMain: false } : { isHeading: false }),
            ])
          }
        >
          {addLabel}
        </Button>
        <Button
          appearance="subtle"
          icon={<Add20Regular />}
          onClick={() => onRows([...rows, newRow("New section", { isHeading: true })])}
        >
          Add section heading
        </Button>
      </div>
    </section>
  );
}

/**
 * The editor. App mounts it with `key={recipe.id}`, so opening a different recipe starts a fresh
 * draft rather than this component having to notice the prop change and reset itself.
 *
 * `unsaved` says the recipe has never been written -- a blank new one, or the draft a web import
 * handed back. Nothing on the server holds either, so leaving one that has content in it loses
 * that content, and the guard has to know that even though nothing has been *typed* into it.
 */
export default function RecipeEditor({
  recipe,
  unsaved,
  courses,
  categories,
  tags,
  onCancel,
  onSave,
  onDirtyChange,
  onCreateTag,
  notify,
}) {
  const styles = useStyles();
  const [draft, setDraft] = useState(recipe);
  const [imageFile, setImageFile] = useState(null);
  const [imageRemoved, setImageRemoved] = useState(false);
  const [saving, setSaving] = useState(false);

  const ingredients = draft.ingredients ?? [];
  const directions = draft.directions ?? [];

  // What the unsaved-changes guard reads. Comparing the whole draft rather than setting a flag on
  // every edit means typing a character and typing it back again leaves nothing to warn about. An
  // untouched blank draft is not worth a prompt; an untouched import is, because it is not blank.
  // Compared through cleanNutrition, which is what save applies: typing a calorie count and then
  // clearing it leaves `{ id, calories: null }` where there was nothing before, and comparing the
  // raw drafts called that an edit and asked whether to discard a recipe nobody had changed.
  const normalized = (r) => JSON.stringify({ ...r, nutrition: cleanNutrition(r.nutrition) });
  const edited = normalized(draft) !== normalized(recipe) || !!imageFile || imageRemoved;
  const holdsContent = !!(draft.name?.trim() || ingredients.length || directions.length);
  const dirty = edited || (unsaved && holdsContent);
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  // And nothing to discard once this is gone. Without it the flag stayed true after the editor
  // unmounted -- entering select mode does that -- so the next click asked about edits that no
  // longer existed and the browser's own leave-the-page prompt stayed armed.
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);

  const set = (patch) => setDraft((d) => ({ ...d, ...patch }));
  const times = draft.preparationTimes ?? [];
  const notes = draft.notes ?? [];
  const variations = draft.variations ?? [];
  const nutrition = draft.nutrition ?? {};

  const save = async () => {
    setSaving(true);
    // Cleared to nothing means no record, not a record full of nulls -- see cleanNutrition.
    await onSave({ ...draft, nutrition: cleanNutrition(draft.nutrition) }, { imageFile, imageRemoved });
    setSaving(false);
  };

  return (
    <>
      <div className={styles.bar}>
        <Subtitle2>{recipe.name ? "Edit recipe" : "New recipe"}</Subtitle2>
        <span className={styles.barSpace} />
        <Button appearance="subtle" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button appearance="primary" onClick={save} disabled={saving}>
          {saving ? "Saving…" : "Save"}
        </Button>
      </div>

      <div className={styles.scroll}>
        <div className={styles.doc}>
          {/* Not `required`: the Swift and Compose apps accept an untitled recipe and the list
              shows "Untitled" for one, so an asterisk here promised a rule nothing enforced. */}
          <Field label="Name">
            <Input
              value={draft.name ?? ""}
              onChange={(_, d) => set({ name: d.value })}
            />
          </Field>

          <ImageField
            styles={styles}
            recipe={draft}
            file={imageFile}
            removed={imageRemoved}
            notify={notify}
            onPick={(f) => {
              setImageFile(f);
              setImageRemoved(false);
            }}
            onRemove={() => {
              setImageFile(null);
              setImageRemoved(true);
            }}
          />

          <div className={styles.grid}>
            <Field label="Source">
              <Input value={draft.source ?? ""} onChange={(_, d) => set({ source: d.value })} />
            </Field>
            <Field label="Source details">
              <Input
                value={draft.sourceDetails ?? ""}
                onChange={(_, d) => set({ sourceDetails: d.value })}
              />
            </Field>
          </div>

          <Field label="Introduction">
            <Textarea
              resize="vertical"
              value={draft.introduction ?? ""}
              onChange={(_, d) => set({ introduction: d.value })}
            />
          </Field>

          <div className={styles.grid}>
            <Field label="Course">
              <Dropdown
                value={courses.find((c) => c.id === draft.courseId)?.name ?? ""}
                selectedOptions={draft.courseId ? [draft.courseId] : []}
                onOptionSelect={(_, d) => set({ courseId: d.optionValue || null })}
              >
                <Option value="">(none)</Option>
                {courses.map((c) => (
                  <Option key={c.id} value={c.id}>
                    {c.name || "Untitled"}
                  </Option>
                ))}
              </Dropdown>
            </Field>

            <Field label="Difficulty">
              <Dropdown
                value={DIFFICULTIES.find((d) => d.value === (draft.difficulty ?? 0))?.label ?? ""}
                selectedOptions={[String(draft.difficulty ?? 0)]}
                onOptionSelect={(_, d) => set({ difficulty: Number(d.optionValue) || null })}
              >
                {DIFFICULTIES.map((d) => (
                  <Option key={d.value} value={String(d.value)}>
                    {d.label}
                  </Option>
                ))}
              </Dropdown>
            </Field>

            <Field label="Rating">
              <Rating
                value={draft.rating ?? 0}
                color="marigold"
                onChange={(_, d) => set({ rating: d.value || null })}
              />
            </Field>
          </div>

          <div className={styles.grid}>
            <Field label="Yield">
              <Input
                value={draft.yield ?? ""}
                placeholder="e.g., Makes 16"
                onChange={(_, d) => set({ yield: d.value })}
              />
            </Field>
            <Field label="Servings">
              <SpinButton
                min={0}
                // null, not 0: "0 servings" is a claim about the recipe, and an unset field is not
                // making it.
                value={draft.servings ?? null}
                displayValue={draft.servings == null ? "" : undefined}
                onChange={(_, d) => {
                  // SpinButton reports `value` for the arrows and `displayValue` for typing; a
                  // cleared field is neither, and must land as null rather than 0.
                  const n = d.value ?? (d.displayValue === "" ? null : Number(d.displayValue));
                  set({ servings: Number.isFinite(n) && n > 0 ? n : null });
                }}
              />
            </Field>
          </div>

          <div className={styles.grid}>
            <Field label="Categories">
              <Dropdown
                multiselect
                placeholder="None"
                value={categories
                  .filter((c) => (draft.categoryIds ?? []).includes(c.id))
                  .map((c) => c.name)
                  .join(", ")}
                selectedOptions={draft.categoryIds ?? []}
                onOptionSelect={(_, d) => set({ categoryIds: d.selectedOptions })}
              >
                {categories.map((c) => (
                  <Option key={c.id} value={c.id}>
                    {c.name || "Untitled"}
                  </Option>
                ))}
              </Dropdown>
            </Field>

            <Field label="Tags">
              <div className={styles.tagRow}>
                <Dropdown
                  className={styles.rowInput}
                  multiselect
                  placeholder="None"
                  value={tags
                    .filter((t) => (draft.tagIds ?? []).includes(t.id))
                    .map((t) => t.name)
                    .join(", ")}
                  selectedOptions={draft.tagIds ?? []}
                  onOptionSelect={(_, d) => set({ tagIds: d.selectedOptions })}
                >
                  {tags.map((t) => (
                    <Option key={t.id} value={t.id}>
                      {t.name || "Untitled"}
                    </Option>
                  ))}
                </Dropdown>
                {/* Tagging is the one classifier you reach for mid-edit, when the tag you want does
                    not exist yet. Sending someone to Organize and back loses the edit's thread. */}
                <Tooltip content="New tag" relationship="label">
                  <Button
                    data-testid="new-tag"
                    icon={<Add20Regular />}
                    onClick={() =>
                      onCreateTag(async (created) => {
                        if (created) set({ tagIds: [...(draft.tagIds ?? []), created.id] });
                      })
                    }
                  />
                </Tooltip>
              </div>
            </Field>
          </div>

          <div className={styles.switches}>
            <Switch
              label="Favorite"
              checked={!!draft.isFavorite}
              onChange={(_, d) => set({ isFavorite: d.checked })}
            />
            <Switch
              label="Want to make"
              checked={!!draft.wantToMake}
              onChange={(_, d) => set({ wantToMake: d.checked })}
            />
          </div>

          <EditableList
            styles={styles}
            title="Ingredients"
            addLabel="Add ingredient"
            rows={ingredients}
            onRows={(rows) => set({ ingredients: rows })}
            allowMain
          />

          <EditableList
            styles={styles}
            title="Directions"
            addLabel="Add step"
            rows={directions}
            onRows={(rows) => set({ directions: rows })}
          />

          <Accordion multiple collapsible className={styles.section}>
            <AccordionItem value="times">
              <AccordionHeader>Preparation times ({times.length})</AccordionHeader>
              <AccordionPanel>
                {times.map((t, i) => (
                  <div key={t.id} className={styles.pairRow}>
                    <Input
                      value={t.type ?? ""}
                      placeholder="Prep"
                      onChange={(_, d) =>
                        set({
                          preparationTimes: times.map((x, j) =>
                            j === i ? { ...x, type: d.value } : x,
                          ),
                        })
                      }
                    />
                    <Input
                      value={t.timeString ?? ""}
                      placeholder="30 min"
                      onChange={(_, d) =>
                        set({
                          preparationTimes: times.map((x, j) =>
                            j === i ? { ...x, timeString: d.value } : x,
                          ),
                        })
                      }
                    />
                    <Button
                      appearance="subtle"
                      icon={<Delete20Regular />}
                      onClick={() =>
                        set({ preparationTimes: times.filter((_, j) => j !== i) })
                      }
                    />
                  </div>
                ))}
                <Button
                  appearance="subtle"
                  icon={<Add20Regular />}
                  onClick={() =>
                    set({
                      preparationTimes: [
                        ...times,
                        { id: newRow().id, type: "", timeString: "" },
                      ],
                    })
                  }
                >
                  Add time
                </Button>
              </AccordionPanel>
            </AccordionItem>

            <AccordionItem value="notes">
              <AccordionHeader>Notes ({notes.length})</AccordionHeader>
              <AccordionPanel>
                {notes.map((n, i) => (
                  <div key={n.id} className={styles.blockRow}>
                    <Input
                      value={n.title ?? ""}
                      placeholder="Title"
                      onChange={(_, d) =>
                        set({ notes: notes.map((x, j) => (j === i ? { ...x, title: d.value } : x)) })
                      }
                    />
                    <Textarea
                      resize="vertical"
                      value={n.content ?? ""}
                      onChange={(_, d) =>
                        set({
                          notes: notes.map((x, j) => (j === i ? { ...x, content: d.value } : x)),
                        })
                      }
                    />
                    <Button
                      appearance="subtle"
                      icon={<Delete20Regular />}
                      onClick={() => set({ notes: notes.filter((_, j) => j !== i) })}
                    >
                      Remove note
                    </Button>
                  </div>
                ))}
                <Button
                  appearance="subtle"
                  icon={<Add20Regular />}
                  onClick={() =>
                    set({ notes: [...notes, { id: newRow().id, title: "", content: "" }] })
                  }
                >
                  Add note
                </Button>
              </AccordionPanel>
            </AccordionItem>

            <AccordionItem value="variations">
              <AccordionHeader>Variations ({variations.length})</AccordionHeader>
              <AccordionPanel>
                {variations.map((v, i) => (
                  <div key={v.id} className={styles.blockRow}>
                    <Input
                      value={v.variationName ?? ""}
                      placeholder="Name"
                      onChange={(_, d) =>
                        set({
                          variations: variations.map((x, j) =>
                            j === i ? { ...x, variationName: d.value } : x,
                          ),
                        })
                      }
                    />
                    <Textarea
                      resize="vertical"
                      value={v.text ?? ""}
                      onChange={(_, d) =>
                        set({
                          variations: variations.map((x, j) =>
                            j === i ? { ...x, text: d.value } : x,
                          ),
                        })
                      }
                    />
                    <Button
                      appearance="subtle"
                      icon={<Delete20Regular />}
                      onClick={() => set({ variations: variations.filter((_, j) => j !== i) })}
                    >
                      Remove variation
                    </Button>
                  </div>
                ))}
                <Button
                  appearance="subtle"
                  icon={<Add20Regular />}
                  onClick={() =>
                    set({
                      variations: [
                        ...variations,
                        { id: newRow().id, variationName: "", text: "" },
                      ],
                    })
                  }
                >
                  Add variation
                </Button>
              </AccordionPanel>
            </AccordionItem>

            <AccordionItem value="nutrition">
              <AccordionHeader>Nutrition</AccordionHeader>
              <AccordionPanel>
                {NUTRITION_GROUPS.map((g) => (
                  <div key={g.group}>
                    <Subtitle2 as="h3">{g.group}</Subtitle2>
                    <div className={styles.grid}>
                      {g.fields.map((f) => (
                        <Field key={f.key} label={f.unit ? `${f.label} (${f.unit})` : f.label}>
                          <Input
                            type={f.text ? "text" : "number"}
                            placeholder={f.placeholder}
                            value={nutrition[f.key] ?? ""}
                            onChange={(_, d) =>
                              set({
                                nutrition: {
                                  ...nutrition,
                                  id: nutrition.id ?? newRow().id,
                                  [f.key]: f.text ? d.value : d.value === "" ? null : Number(d.value),
                                },
                              })
                            }
                          />
                        </Field>
                      ))}
                    </div>
                  </div>
                ))}
              </AccordionPanel>
            </AccordionItem>
          </Accordion>
        </div>
      </div>
    </>
  );
}

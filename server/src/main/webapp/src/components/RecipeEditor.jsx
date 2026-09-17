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
  Tag,
  TagPicker,
  TagPickerControl,
  TagPickerGroup,
  TagPickerInput,
  TagPickerList,
  TagPickerOption,
  Textarea,
  ToggleButton,
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
  Medal20Filled,
  Medal20Regular,
  ReOrderDotsVertical20Regular,
  TextBulletListSquareEdit20Regular,
  bundleIcon,
} from "@fluentui/react-icons";

import { IMAGE_TYPES, MAX_IMAGE_BYTES, imageUrl } from "../api";
import {
  DIFFICULTIES,
  NUTRITION_GROUPS,
  cleanNutrition,
  formatDirections,
  formatIngredients,
  newRow,
  parseDirections,
  parseIngredients,
} from "../model";
import ListTextDialog from "./ListTextDialog";

// Filled while its ToggleButton is checked (and on hover), outlined otherwise.
const MedalIcon = bundleIcon(Medal20Filled, Medal20Regular);

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
    /* The tag picker grows a line for every row of tags, and a stretched Categories dropdown beside
       it grew with it. */
    alignItems: "start",
    /* Fluent's Dropdown carries a 250px min-width of its own, wider than a 13rem track, so at
       tablet widths — three columns on an iPad mini — each dropdown spilled over the field beside
       it. Let them shrink to their track like every other control here. The tag picker carries
       the same 250px. */
    "& .fui-Dropdown, & .fui-TagPickerControl": { minWidth: 0 },
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
  /* A step's row, whose box can be several lines: the handle and buttons stay by its first line. */
  tallRow: { alignItems: "flex-start" },
  /* A step grows to fit its text (every current browser has field-sizing) and past Fluent's 260px
     cap, and can still be dragged taller for room to write. */
  stepText: { fieldSizing: "content", maxHeight: "none" },
  /* Section headings, and the title half of a note, variation or time. Semibold is Fluent's
     emphasis weight, and it is the weight the read view gives the same text, so a title stays
     distinguishable from the body under it once the placeholder saying which is which has gone. */
  headingInput: { fontWeight: tokens.fontWeightSemibold },
  addRow: { display: "flex", gap: tokens.spacingHorizontalS, marginTop: tokens.spacingVerticalXS },
  pairRow: {
    display: "grid",
    /* minmax(0, …), not a bare 1fr: a track will not go below the input's intrinsic width
       otherwise, and at phone width the two inputs pushed the remove button off the edge. */
    gridTemplateColumns: "minmax(0, 1fr) minmax(0, 1fr) auto",
    gap: tokens.spacingHorizontalS,
    marginBottom: tokens.spacingVerticalXS,
  },
  /* A note or variation: title and remove button on the first line, the text under the title. The
     button's column lines up with the time rows' above, and the text ends where its title does. */
  blockRow: {
    marginBottom: tokens.spacingVerticalM,
    display: "grid",
    gridTemplateColumns: "1fr auto",
    gap: `${tokens.spacingVerticalXS} ${tokens.spacingHorizontalS}`,
  },
  /* For the inner <textarea> slot, not the Textarea: Fluent caps that element at 260px (medium),
     so the resize grip stopped there and a long introduction or note could never be shown whole. */
  longText: { maxHeight: "none" },
  /* Where the row will land if dropped now. A line, not a reflowed list: moving rows around on
     every dragover makes the target you are aiming at slide out from under the cursor. */
  dropTarget: { borderTop: `2px solid ${tokens.colorBrandStroke1}` },
  dragging: { opacity: 0.4 },
  handle: { cursor: "grab", color: tokens.colorNeutralForeground3, display: "flex" },
  imageRow: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalM },
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

// The two list entries that are not tags. Tag ids are UUIDs, so neither can collide with one.
const CREATE_TAG = ":create";
const NO_TAGS = ":none";

/**
 * The recipe's tags, typed rather than picked from a closed list.
 *
 * Courses and categories are few and kept tidy, so a dropdown suits them. Tags are the catch-all
 * and can run long, so this narrows as you type and offers to create a name nothing matches --
 * reaching for a tag that does not exist yet is common mid-edit, and sending someone to Organize
 * and back loses the edit's thread. The tag is created when picked, not at Save, as the "New tag"
 * dialog this replaces did.
 *
 * "Create" is offered only when no tag has that name ignoring case, the same lookup the Swift
 * app's addTag makes. The server keys tags by id alone and would keep a second "Dinner" beside
 * the first.
 *
 * `onChange` takes an updater, not a list: a create resolves after a round trip, and a tag removed
 * in the meantime must stay removed.
 */
function TagsField({ tags, tagIds, onChange, onCreateTag }) {
  const [query, setQuery] = useState("");
  // Fluent opens the list on a keydown it reads as typing. A paste is not one, and Android keyboards
  // report every key as "Unidentified", so the list is opened by the text changing instead.
  const [open, setOpen] = useState(false);
  const name = query.trim();
  const needle = name.toLocaleLowerCase();
  const byId = new Map(tags.map((t) => [t.id, t]));
  const shown = tagIds.filter((id) => byId.has(id));
  const exists = tags.some((t) => (t.name ?? "").trim().toLocaleLowerCase() === needle);
  const matches = tags
    .filter((t) => !tagIds.includes(t.id) && (t.name ?? "").toLocaleLowerCase().includes(needle))
    .sort((a, b) => (a.name ?? "").localeCompare(b.name ?? "", undefined, { sensitivity: "base" }));
  const offerCreate = !!name && !exists;

  const pick = async (_, d) => {
    setQuery("");
    if (d.value === NO_TAGS) return;
    if (d.value === CREATE_TAG) {
      const created = await onCreateTag(name);
      if (created) onChange((ids) => (ids.includes(created.id) ? ids : [...ids, created.id]));
      return;
    }
    onChange(() => d.selectedOptions);
  };

  return (
    <Field label="Tags">
      <TagPicker
        open={open}
        onOpenChange={(_, d) => setOpen(d.open)}
        selectedOptions={tagIds}
        onOptionSelect={pick}
      >
        <TagPickerControl>
          <TagPickerGroup aria-label="Selected tags">
            {shown.map((id) => (
              <Tag key={id} value={id} shape="rounded">
                {byId.get(id).name || "Untitled"}
              </Tag>
            ))}
          </TagPickerGroup>
          <TagPickerInput
            value={query}
            placeholder={shown.length ? undefined : "Type to add a tag"}
            onChange={(e) => {
              setQuery(e.target.value);
              setOpen(true);
            }}
          />
        </TagPickerControl>
        <TagPickerList>
          {matches.map((t) => (
            <TagPickerOption key={t.id} value={t.id} text={t.name || "Untitled"}>
              {t.name || "Untitled"}
            </TagPickerOption>
          ))}
          {offerCreate ? (
            <TagPickerOption value={CREATE_TAG} text={`Create "${name}"`} media={<Add20Regular />}>
              {`Create "${name}"`}
            </TagPickerOption>
          ) : null}
          {!matches.length && !offerCreate ? (
            <TagPickerOption value={NO_TAGS} text="No tags">
              {name ? `"${name}" is already added` : tags.length ? "Every tag is added" : "Type a name to create a tag"}
            </TagPickerOption>
          ) : null}
        </TagPickerList>
      </TagPicker>
    </Field>
  );
}

/** One editable line of a list: the text, what kind of line it is, and where it sits. */
function ListRow({ styles, row, index, count, onChange, onMove, onRemove, allowMain, multiline, drag }) {
  const tall = multiline && !row.isHeading;
  return (
    <div
      className={mergeClasses(
        styles.row,
        tall && styles.tallRow,
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
      {/* A step can run to a paragraph; an ingredient or a heading is one line. */}
      {tall ? (
        <Textarea
          className={styles.rowInput}
          resize="vertical"
          textarea={{ className: styles.stepText }}
          value={row.text}
          placeholder="Step"
          onChange={(_, d) => onChange({ ...row, text: d.value })}
        />
      ) : (
        <Input
          className={styles.rowInput}
          input={row.isHeading ? { className: styles.headingInput } : undefined}
          value={row.text}
          placeholder={row.isHeading ? "Section heading" : "One line"}
          onChange={(_, d) => onChange({ ...row, text: d.value })}
        />
      )}
      <div className={styles.rowActions}>
        {allowMain && !row.isHeading ? (
          <Tooltip content="Main ingredient" relationship="label">
            {/* A toggle, so a screen reader hears on or off, and a medal that fills when on: the
                Swift and Compose apps' mark for a main ingredient. */}
            <ToggleButton
              appearance="subtle"
              size="small"
              checked={!!row.isMain}
              icon={<MedalIcon />}
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

// What "Edit as text" needs for each list
const INGREDIENTS_AS_TEXT = {
  format: formatIngredients,
  parse: parseIngredients,
  stripNumbering: false,
  help:
    "One ingredient per line. A blank line before a line marks that line as a section heading, or so does " +
    "ending it with a colon. End an ingredient with [*] to mark it as a main ingredient. \"Clean up\" " +
    "removes bullets and extra spaces.",
};
const DIRECTIONS_AS_TEXT = {
  format: formatDirections,
  parse: parseDirections,
  stripNumbering: true,
  help:
    "Use a blank line to separate steps (lines with no blank line between them join as one). " +
    "Two blank lines before a line indicate a section heading, or so does ending it with a colon. " +
    "\"Clean up\" removes bullets, step numbers and extra spaces.",
};

function EditableList({ styles, title, rows, onRows, allowMain, multiline, asText, addLabel }) {
  const [fromIndex, setFromIndex] = useState(null);
  const [overIndex, setOverIndex] = useState(null);
  const [editingText, setEditingText] = useState(false);

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
        {/* As the label, not a description: a screen reader then says the longer name once, rather
            than "Edit as text" followed by nearly the same words again. It starts with the visible
            text, so a voice command naming what is on screen still finds the button. */}
        <Tooltip content="Edit as text (bulk edit)" relationship="label">
          <Button appearance="subtle" icon={<TextBulletListSquareEdit20Regular />} onClick={() => setEditingText(true)}>
            Edit as text
          </Button>
        </Tooltip>
      </div>
      {editingText ? (
        <ListTextDialog
          title={`Edit ${title.toLowerCase()} as text`}
          help={asText.help}
          initialText={asText.format(rows)}
          stripNumbering={asText.stripNumbering}
          onApply={(text) => onRows(asText.parse(text))}
          onClose={() => setEditingText(false)}
        />
      ) : null}
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
          multiline={multiline}
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
              textarea={{ className: styles.longText }}
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

            <TagsField
              tags={tags}
              tagIds={draft.tagIds ?? []}
              onChange={(update) => setDraft((d) => ({ ...d, tagIds: update(d.tagIds ?? []) }))}
              onCreateTag={onCreateTag}
            />
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
            asText={INGREDIENTS_AS_TEXT}
          />

          <EditableList
            styles={styles}
            title="Directions"
            addLabel="Add step"
            rows={directions}
            onRows={(rows) => set({ directions: rows })}
            multiline
            asText={DIRECTIONS_AS_TEXT}
          />

          <Accordion multiple collapsible className={styles.section}>
            <AccordionItem value="times">
              <AccordionHeader>Preparation times ({times.length})</AccordionHeader>
              <AccordionPanel>
                {times.map((t, i) => (
                  <div key={t.id} className={styles.pairRow}>
                    {/* These rows have no Field, so aria-label is their only name: a placeholder
                        is an example, and it disappears the moment there is text to describe. */}
                    <Input
                      aria-label="Time type"
                      input={{ className: styles.headingInput }}
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
                      aria-label="Duration"
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
                    <Tooltip content="Remove time" relationship="label">
                      <Button
                        appearance="subtle"
                        icon={<Delete20Regular />}
                        onClick={() =>
                          set({ preparationTimes: times.filter((_, j) => j !== i) })
                        }
                      />
                    </Tooltip>
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
                      aria-label="Note title"
                      input={{ className: styles.headingInput }}
                      value={n.title ?? ""}
                      placeholder="Title"
                      onChange={(_, d) =>
                        set({ notes: notes.map((x, j) => (j === i ? { ...x, title: d.value } : x)) })
                      }
                    />
                    <Tooltip content="Remove note" relationship="label">
                      <Button
                        appearance="subtle"
                        icon={<Delete20Regular />}
                        onClick={() => set({ notes: notes.filter((_, j) => j !== i) })}
                      />
                    </Tooltip>
                    <Textarea
                      aria-label="Note text"
                      resize="vertical"
                      textarea={{ className: styles.longText }}
                      value={n.content ?? ""}
                      onChange={(_, d) =>
                        set({
                          notes: notes.map((x, j) => (j === i ? { ...x, content: d.value } : x)),
                        })
                      }
                    />
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
                      aria-label="Variation name"
                      input={{ className: styles.headingInput }}
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
                    <Tooltip content="Remove variation" relationship="label">
                      <Button
                        appearance="subtle"
                        icon={<Delete20Regular />}
                        onClick={() => set({ variations: variations.filter((_, j) => j !== i) })}
                      />
                    </Tooltip>
                    <Textarea
                      aria-label="Variation text"
                      resize="vertical"
                      textarea={{ className: styles.longText }}
                      value={v.text ?? ""}
                      onChange={(_, d) =>
                        set({
                          variations: variations.map((x, j) =>
                            j === i ? { ...x, text: d.value } : x,
                          ),
                        })
                      }
                    />
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

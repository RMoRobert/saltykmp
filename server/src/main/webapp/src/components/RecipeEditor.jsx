import { useEffect, useState } from "react";
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
  tokens,
} from "@fluentui/react-components";
import {
  Add20Regular,
  ArrowDown20Regular,
  ArrowUp20Regular,
  Delete20Regular,
  TextT20Regular,
} from "@fluentui/react-icons";

import { NUTRITION_GROUPS, DIFFICULTIES, newRow } from "../model";

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
});

/** One editable line of a list: the text, what kind of line it is, and where it sits. */
function ListRow({ styles, row, index, count, onChange, onMove, onRemove, allowMain }) {
  return (
    <div className={styles.row}>
      <Input
        className={styles.rowInput}
        input={row.isHeading ? { className: styles.headingInput } : undefined}
        value={row.text}
        placeholder={row.isHeading ? "Section heading" : "One line"}
        onChange={(_, d) => onChange({ ...row, text: d.value })}
      />
      <div className={styles.rowActions}>
        {allowMain ? (
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

function EditableList({ styles, title, rows, onRows, allowMain }) {
  const set = (i, next) => onRows(rows.map((r, j) => (j === i ? next : r)));
  const move = (i, d) => {
    const next = [...rows];
    const [item] = next.splice(i, 1);
    next.splice(i + d, 0, item);
    onRows(next);
  };
  const remove = (i) => onRows(rows.filter((_, j) => j !== i));

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
        />
      ))}
      <div className={styles.addRow}>
        <Button
          appearance="subtle"
          icon={<Add20Regular />}
          onClick={() => onRows([...rows, newRow("", allowMain ? { isHeading: false, isMain: false } : { isHeading: false })])}
        >
          Add line
        </Button>
        <Button
          appearance="subtle"
          icon={<Add20Regular />}
          onClick={() => onRows([...rows, newRow("New section", { isHeading: true })])}
        >
          Add section
        </Button>
      </div>
    </section>
  );
}

export default function RecipeEditor({ recipe, courses, categories, tags, onCancel, onSave }) {
  const styles = useStyles();
  const [draft, setDraft] = useState(recipe);
  const [saving, setSaving] = useState(false);

  useEffect(() => setDraft(recipe), [recipe]);

  const set = (patch) => setDraft((d) => ({ ...d, ...patch }));
  const ingredients = draft.ingredients ?? [];
  const directions = draft.directions ?? [];
  const times = draft.preparationTimes ?? [];
  const notes = draft.notes ?? [];
  const variations = draft.variations ?? [];
  const nutrition = draft.nutrition ?? {};

  const save = async () => {
    setSaving(true);
    const ok = await onSave(draft);
    setSaving(false);
    if (!ok) return;
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
          <Field label="Name" required>
            <Input
              value={draft.name ?? ""}
              onChange={(_, d) => set({ name: d.value })}
              placeholder="What is it called?"
            />
          </Field>

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
                placeholder="e.g. Makes 16"
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
              <Dropdown
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
            rows={ingredients}
            onRows={(rows) => set({ ingredients: rows })}
            allowMain
          />

          <EditableList
            styles={styles}
            title="Directions"
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

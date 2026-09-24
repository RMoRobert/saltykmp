import { useMemo, useState } from "react";
import {
  Button,
  Combobox,
  Link,
  Menu,
  MenuDivider,
  MenuGroup,
  MenuGroupHeader,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Option,
  RatingDisplay,
  Subtitle2,
  Title1,
  Title2,
  Title3,
  Tooltip,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import {
  Add20Regular,
  ArrowExit20Regular,
  ArrowLeft24Regular,
  BookOpen48Regular,
  Bookmark20Filled,
  Bookmark20Regular,
  CalendarCheckmark20Regular,
  Delete20Regular,
  Edit20Regular,
  Heart20Filled,
  Heart20Regular,
  Info20Regular,
  MoreHorizontal24Regular,
  PlayCircle20Regular,
  Subtract20Regular,
} from "@fluentui/react-icons";

import { imageUrl } from "../api";
import {
  SCALE_PRESETS,
  difficultyLabel,
  displayParts,
  formatDay,
  parseScale,
  scaleLabel,
  sourceLink,
  stepNumbers,
  stepScale,
  wireNow,
} from "../model";

const useStyles = makeStyles({
  bar: {
    display: "flex",
    alignItems: "center",
    justifyContent: "flex-end",
    gap: tokens.spacingHorizontalXS,
    padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalL}`,
    borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
  },
  scroll: { flex: 1, overflow: "hidden auto" },
  barSpace: { flex: 1 },
  doc: {
    maxWidth: "56rem",
    margin: "0 auto",
    padding: `${tokens.spacingVerticalXL} ${tokens.spacingHorizontalXXL} ${tokens.spacingVerticalXXXL}`,
  },
  /* Floated rather than gridded: the text should wrap back under a small image, as it does in the
     Uno client, instead of leaving a permanently empty column beside a short introduction. */
  image: {
    float: "right",
    width: "min(38%, 260px)",
    marginInlineStart: tokens.spacingHorizontalXL,
    marginBottom: tokens.spacingVerticalM,
    borderRadius: tokens.borderRadiusMedium,
    objectFit: "cover",
  },
  meta: { color: tokens.colorNeutralForeground2, margin: `${tokens.spacingVerticalXS} 0` },
  source: { color: tokens.colorNeutralForeground2, margin: `${tokens.spacingVerticalXS} 0` },
  intro: { margin: `${tokens.spacingVerticalL} 0`, lineHeight: tokens.lineHeightBase400 },
  section: { marginTop: tokens.spacingVerticalXXL, clear: "none" },
  sectionHead: {
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    gap: tokens.spacingHorizontalM,
    marginBottom: tokens.spacingVerticalS,
  },
  scaler: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalXXS },
  /* Sized for "1½×" or "1.33×" plus the list's chevron. Fluent gives a Combobox a 250px minimum,
     which here would push the heading beside it onto a line of its own on a phone. The value is
     centred and semibold as the plain number it replaced was: the scale is read more than set. */
  scaleBox: {
    minWidth: 0,
    width: "6.5rem",
    "& input": { textAlign: "center", fontWeight: tokens.fontWeightSemibold, minWidth: 0 },
  },
  list: { listStyle: "none", margin: 0, padding: 0, lineHeight: tokens.lineHeightBase400 },
  item: { paddingBlock: "2px", display: "flex", gap: tokens.spacingHorizontalS },
  bullet: { color: tokens.colorNeutralForeground3 },
  heading: {
    fontWeight: tokens.fontWeightSemibold,
    marginTop: tokens.spacingVerticalM,
    color: tokens.colorNeutralForeground2,
  },
  /* The quantity leads the line and is what a cook checks against the bowl, so it carries the
     line's emphasis -- the same weight the Swift app's ingredient rows give it. */
  quantity: { fontWeight: tokens.fontWeightSemibold },
  /* Scaling is the only thing on the line that is not what the author typed, so a scaled quantity
     takes colour on top of that weight. */
  scaled: { color: tokens.colorBrandForeground1 },
  stepNo: { color: tokens.colorNeutralForeground3, minWidth: "1.6rem" },
  block: { marginBottom: tokens.spacingVerticalM },
  empty: {
    height: "100%",
    display: "grid",
    placeContent: "center",
    justifyItems: "center",
    gap: tokens.spacingVerticalM,
    color: tokens.colorNeutralForeground3,
  },
  favActive: { color: tokens.colorPaletteRedForeground1 },
  /* Chef mode: the same document, sized for reading it from across a kitchen. The top of Fluent's
     base ramp reaches far enough, so nothing here is a hand-picked size. */
  chefDoc: {
    fontSize: tokens.fontSizeBase600,
    lineHeight: tokens.lineHeightBase600,
    maxWidth: "64rem",
  },
  /* The reading-size line height is set on the lists themselves, so it would otherwise win over
     the document's and pack 24px text into 22px lines. */
  chefText: { lineHeight: tokens.lineHeightBase600 },
  /* Two pixels between lines reads as a paragraph, not as a list of things to do one at a time. */
  chefItem: { paddingBlock: tokens.spacingVerticalS },
  /* Wide enough for a two-digit step, so the tenth direction starts where the ninth did. */
  chefStepNo: { minWidth: "2.6rem" },
  chefSection: { marginTop: tokens.spacingVerticalXXXL },
  chefSubhead: { marginTop: tokens.spacingVerticalL },
  chefBar: { justifyContent: "space-between" },
});

/**
 * Nothing open.
 *
 * It carries the back button when there is one, because compact shows a single pane: deleting a
 * recipe, or cancelling a new one, left this on screen with the list unmounted, the rail unmounted,
 * and nothing at all to press.
 */
/**
 * How much of the recipe to show: − and + step between the common sizes, and the box between them
 * takes any size typed as a cook would write it -- "1.5", "1/2", "1 1/3", "½", "2x" -- or picked from
 * its list. See parseScale for what it reads.
 *
 * What is typed stays as typed until Enter or leaving the box. Then it becomes the scale, or, if it
 * isn't one, the box goes back to the scale it had: a half-typed "1/" must not blank every quantity
 * on the page while the second number is on its way. Escape abandons the typing the same way.
 */
function ScaleControl({ styles, factor, onChange }) {
  const [typed, setTyped] = useState(null);
  const [open, setOpen] = useState(false);
  const shown = `${scaleLabel(factor)}×`;
  const preset = SCALE_PRESETS.find((p) => Math.abs(p.value - factor) < 1e-9);
  const down = stepScale(factor, -1);
  const up = stepScale(factor, 1);

  const commit = () => {
    if (typed === null) return;
    const value = parseScale(typed);
    if (value !== null) onChange(value);
    setTyped(null);
  };

  return (
    <div className={styles.scaler}>
      <Tooltip content="Scale down" relationship="label">
        <Button
          appearance="subtle"
          size="small"
          icon={<Subtract20Regular />}
          disabled={down === null}
          onClick={() => onChange(down)}
        />
      </Tooltip>
      <Combobox
        freeform
        size="small"
        aria-label="Scale"
        className={styles.scaleBox}
        value={typed ?? shown}
        selectedOptions={preset ? [String(preset.value)] : []}
        onChange={(e) => setTyped(e.target.value)}
        onOptionSelect={(e, d) => {
          // No option is Fluent clearing its selection because the typing no longer matches it --
          // which is typing going on, not a choice, and must not throw the typed text away.
          if (d.optionValue === undefined) return;
          // Fluent handles Enter before onKeyDown below and selects whichever option is highlighted,
          // matching the text or not, so "1/2" came out as whatever the list had lit up. Typed text
          // is what Enter means; onKeyDown applies it next.
          if (e.type === "keydown" && typed !== null) return;
          const value = Number(d.optionValue);
          if (value > 0) onChange(value);
          setTyped(null);
        }}
        onOpenChange={(_, d) => setOpen(d.open)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            if (typed === null) return; // choosing a highlighted option: Fluent's to handle
            commit();
            // Still focused, so select the result: the next thing typed replaces it rather than
            // being appended to "½×". After the render that puts the new value in the box.
            const input = e.currentTarget;
            setTimeout(() => input.select());
          } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
            setTyped(null); // moving through the list is choosing from it, not typing
          } else if (e.key === "Escape" && (typed !== null || open)) {
            // Escape here abandons the typing or closes the list, and that is all it does: chef
            // mode's Escape listens on the window, and without this one press would do both.
            e.stopPropagation();
            setTyped(null);
          }
        }}
        // Selecting the whole value on focus, so typing replaces "1×" rather than appending to it.
        input={{ onFocus: (e) => e.target.select() }}
      >
        {SCALE_PRESETS.map((p) => (
          <Option key={p.label} value={String(p.value)} text={`${p.label}×`}>
            {`${p.label}×`}
          </Option>
        ))}
      </Combobox>
      <Tooltip content="Scale up" relationship="label">
        <Button
          appearance="subtle"
          size="small"
          icon={<Add20Regular />}
          disabled={up === null}
          onClick={() => onChange(up)}
        />
      </Tooltip>
    </div>
  );
}

function Placeholder({ styles, onBack }) {
  return (
    <>
      {onBack ? (
        <div className={styles.bar}>
          <Tooltip content="Back to the list" relationship="label">
            <Button appearance="subtle" icon={<ArrowLeft24Regular />} onClick={onBack} />
          </Tooltip>
          <span className={styles.barSpace} />
        </div>
      ) : null}
      <div className={styles.empty}>
        <BookOpen48Regular />
        <span>Select a recipe.</span>
      </div>
    </>
  );
}

export default function RecipeDetail({
  recipe,
  courses,
  chefMode,
  onBack,
  onEdit,
  onDelete,
  onGetInfo,
  onSetPrepared,
  onPickLastMade,
  onToggleFavorite,
  onToggleWantToMake,
  onEnterChefMode,
  onExitChefMode,
}) {
  const styles = useStyles();
  const [factor, setFactor] = useState(1);

  // Fluent's type ramp is in rem, so enlarging the document leaves fixed-size headings looking
  // smaller than the body they head. Moving up the ramp keeps the hierarchy the right way round.
  const Heading = chefMode ? Title3 : Subtitle2;
  const PageTitle = chefMode ? Title1 : Title2;

  // A scale belongs to the recipe you were reading, not to the pane: App keys this component by
  // recipe id, so opening another one starts it over at 1x without an effect to reset it.

  const ingredients = recipe?.ingredients ?? [];
  const directions = recipe?.directions ?? [];
  const numbers = useMemo(() => stepNumbers(directions), [directions]);

  if (!recipe) return <Placeholder styles={styles} onBack={onBack} />;

  // Chef mode is the reading document with more air in it; merging once here keeps the five
  // sections below from repeating the same conditional.
  const sectionCls = mergeClasses(styles.section, chefMode && styles.chefSection);
  const listCls = mergeClasses(styles.list, chefMode && styles.chefText);
  const itemCls = mergeClasses(styles.item, chefMode && styles.chefItem);
  const subheadCls = mergeClasses(styles.heading, chefMode && styles.chefSubhead);

  const courseName = courses.find((c) => c.id === recipe.courseId)?.name;
  const link = sourceLink(recipe);
  const img = imageUrl(recipe);

  const meta = [courseName, recipe.yield, recipe.servings ? `Serves ${recipe.servings}` : null,
    difficultyLabel(recipe.difficulty)].filter(Boolean);
  const times = (recipe.preparationTimes ?? []).filter((t) => t.type || t.timeString);

  return (
    <>
      <div className={mergeClasses(styles.bar, chefMode && styles.chefBar)}>
        {chefMode ? (
          <>
            {/* Nothing else belongs here while cooking: the way out is the only thing worth
                offering, and everything in the overflow menu is an editing action. */}
            <span />
            <Button appearance="primary" icon={<ArrowExit20Regular />} onClick={onExitChefMode}>
              Exit chef mode
            </Button>
          </>
        ) : (
          <>
            {onBack ? (
              <Tooltip content="Back to the list" relationship="label">
                <Button appearance="subtle" icon={<ArrowLeft24Regular />} onClick={onBack} />
              </Tooltip>
            ) : null}
            <span className={styles.barSpace} />
            <Button appearance="subtle" icon={<PlayCircle20Regular />} onClick={onEnterChefMode}>
              Chef mode
            </Button>
            <Button appearance="outline" icon={<Edit20Regular />} onClick={onEdit} aria-keyshortcuts="E">
              Edit
            </Button>
            <Menu>
              <MenuTrigger disableButtonEnhancement>
                <Tooltip content="More actions" relationship="label">
                  <Button appearance="subtle" icon={<MoreHorizontal24Regular />} />
                </Tooltip>
              </MenuTrigger>
              <MenuPopover>
                <MenuList>
                  <MenuItem icon={<Info20Regular />} onClick={onGetInfo}>
                    Get info
                  </MenuItem>
                  <Menu>
                    <MenuTrigger disableButtonEnhancement>
                      <MenuItem icon={<CalendarCheckmark20Regular />}>Last prepared date</MenuItem>
                    </MenuTrigger>
                    <MenuPopover>
                      <MenuList>
                        <MenuGroup>
                          <MenuGroupHeader>{"Last prepared: " + (formatDay(recipe.lastPrepared) || "not set")}</MenuGroupHeader>
                          <MenuItem onClick={() => onSetPrepared(wireNow())}>Set to today</MenuItem>
                          <MenuItem onClick={onPickLastMade}>Set as date…</MenuItem>
                        </MenuGroup>
                      </MenuList>
                    </MenuPopover>
                  </Menu>
                  <MenuDivider />
                  <MenuItem
                    icon={recipe.isFavorite ? <Heart20Filled /> : <Heart20Regular />}
                    onClick={() => onToggleFavorite(recipe)}
                  >
                    {recipe.isFavorite ? "Remove from favorites" : "Add to favorites"}
                  </MenuItem>
                  <MenuItem
                    icon={recipe.wantToMake ? <Bookmark20Filled /> : <Bookmark20Regular />}
                    onClick={() => onToggleWantToMake(recipe)}
                  >
                    {recipe.wantToMake ? "Remove from want to make" : "Add to want to make"}
                  </MenuItem>
                  <MenuDivider />
                  <MenuItem icon={<Delete20Regular />} onClick={() => onDelete(recipe)}>
                    Delete recipe…
                  </MenuItem>
                </MenuList>
              </MenuPopover>
            </Menu>
          </>
        )}
      </div>

      <div className={styles.scroll}>
        <article className={mergeClasses(styles.doc, chefMode && styles.chefDoc)}>
          {img ? <img className={styles.image} src={img} alt="" /> : null}

          <PageTitle as="h1">{recipe.name || "Untitled"}</PageTitle>

          {recipe.rating ? (
            <div>
              {/* Stars alone: the number beside them repeats what the stars already say. */}
              <RatingDisplay value={recipe.rating} max={5} color="marigold" size="medium" valueText={null} />
            </div>
          ) : null}

          {recipe.source || recipe.sourceDetails ? (
            <p className={styles.source}>
              {recipe.source}
              {recipe.source && recipe.sourceDetails ? " — " : ""}
              {link ? (
                <Link href={link} target="_blank" rel="noreferrer noopener">
                  {recipe.sourceDetails || link}
                </Link>
              ) : (
                recipe.sourceDetails
              )}
            </p>
          ) : null}

          {meta.length ? <p className={styles.meta}>{meta.join(" · ")}</p> : null}

          {/* The times' one place on the page. They were also a Times section further down, the same
              values again, which on a phone stacked into six lines of repetition. Up here they are
              read before starting, which is when they matter. */}
          {times.length ? (
            <p className={styles.meta}>
              {times.map((t) => `${t.type} ${t.timeString}`.trim()).join(" · ")}
            </p>
          ) : null}

          {recipe.introduction ? (
            <p className={mergeClasses(styles.intro, chefMode && styles.chefText)}>
              {recipe.introduction}
            </p>
          ) : null}

          {ingredients.length ? (
            <section className={sectionCls}>
              <div className={styles.sectionHead}>
                <Heading as="h2">Ingredients</Heading>
                <ScaleControl styles={styles} factor={factor} onChange={setFactor} />
              </div>
              <ul className={listCls}>
                {ingredients.map((row) => {
                  if (row.isHeading) {
                    return (
                      <li key={row.id} className={subheadCls}>
                        {row.text}
                      </li>
                    );
                  }
                  const { quantity, remainder } = displayParts(row, factor);
                  // `row.isMain` is deliberately not rendered. The editor still sets it and it
                  // still travels with the recipe -- it is reserved for search and the like, and
                  // bolding the main ingredients was competing with the quantity for the only
                  // emphasis an ingredient line has.
                  return (
                    <li key={row.id} className={itemCls}>
                      <span className={styles.bullet} aria-hidden="true">
                        ·
                      </span>
                      <span>
                        {quantity ? (
                          <span
                            className={mergeClasses(
                              styles.quantity,
                              factor !== 1 && styles.scaled,
                            )}
                          >
                            {quantity}
                          </span>
                        ) : null}
                        {quantity ? (remainder ? ` ${remainder}` : "") : row.text}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </section>
          ) : null}

          {directions.length ? (
            <section className={sectionCls}>
              <Heading as="h2">Directions</Heading>
              {/* A <ul> with explicit numbers, not an <ol>: section headings are list items too,
                  and an <ol> counts them, so every heading shifts the numbering. */}
              <ul className={listCls}>
                {directions.map((row, i) =>
                  row.isHeading ? (
                    <li key={row.id} className={subheadCls}>
                      {row.text}
                    </li>
                  ) : (
                    <li key={row.id} className={itemCls}>
                      <span className={mergeClasses(styles.stepNo, chefMode && styles.chefStepNo)}>
                        {numbers[i]}.
                      </span>
                      <span>{row.text}</span>
                    </li>
                  ),
                )}
              </ul>
            </section>
          ) : null}

          {(recipe.notes ?? []).length ? (
            <section className={sectionCls}>
              <Heading as="h2">Notes</Heading>
              {recipe.notes.map((n) => (
                <div key={n.id} className={styles.block}>
                  <strong>{n.title}</strong>
                  <p style={{ margin: 0 }}>{n.content}</p>
                </div>
              ))}
            </section>
          ) : null}

          {(recipe.variations ?? []).length ? (
            <section className={sectionCls}>
              <Heading as="h2">Variations</Heading>
              {recipe.variations.map((v) => (
                <div key={v.id} className={styles.block}>
                  <strong>{v.variationName}</strong>
                  <p style={{ margin: 0 }}>{v.text}</p>
                </div>
              ))}
            </section>
          ) : null}
        </article>
      </div>
    </>
  );
}

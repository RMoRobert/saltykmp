import { useEffect, useMemo, useState } from "react";
import {
  Button,
  Link,
  Menu,
  MenuDivider,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
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
  Delete20Regular,
  Edit20Regular,
  Heart20Filled,
  Heart20Regular,
  MoreHorizontal24Regular,
  PlayCircle20Regular,
  Subtract20Regular,
} from "@fluentui/react-icons";

import { imageUrl } from "../api";
import { SCALES, difficultyLabel, scaleLine, sourceLink, stepNumbers } from "../model";

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
  scaleValue: { minWidth: "2.5rem", textAlign: "center", fontWeight: tokens.fontWeightSemibold },
  list: { listStyle: "none", margin: 0, padding: 0, lineHeight: tokens.lineHeightBase400 },
  item: { paddingBlock: "2px", display: "flex", gap: tokens.spacingHorizontalS },
  bullet: { color: tokens.colorNeutralForeground3 },
  heading: {
    fontWeight: tokens.fontWeightSemibold,
    marginTop: tokens.spacingVerticalM,
    color: tokens.colorNeutralForeground2,
  },
  main: { fontWeight: tokens.fontWeightSemibold },
  /* The scaled quantity is the only thing on the line that is not what the author typed. */
  scaled: { color: tokens.colorBrandForeground1, fontWeight: tokens.fontWeightSemibold },
  stepNo: { color: tokens.colorNeutralForeground3, minWidth: "1.6rem" },
  pairs: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit, minmax(11rem, 1fr))",
    gap: tokens.spacingVerticalS,
    margin: 0,
  },
  pairTerm: { color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200 },
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
  /* Chef mode: the same document, sized for reading it from across a kitchen. Fluent's ramp does
     not go this large, so the two values here are the only hand-picked sizes in the app. */
  chefDoc: { fontSize: "1.35rem", lineHeight: "2rem", maxWidth: "64rem" },
  chefBar: { justifyContent: "space-between" },
});

function Placeholder({ styles }) {
  return (
    <div className={styles.empty}>
      <BookOpen48Regular />
      <span>Select a recipe.</span>
    </div>
  );
}

export default function RecipeDetail({
  recipe,
  courses,
  chefMode,
  onBack,
  onEdit,
  onDelete,
  onToggleFavorite,
  onToggleWantToMake,
  onEnterChefMode,
  onExitChefMode,
}) {
  const styles = useStyles();
  const [scaleIdx, setScaleIdx] = useState(1);

  // Fluent's type ramp is in rem, so enlarging the document leaves fixed-size headings looking
  // smaller than the body they head. Moving up the ramp keeps the hierarchy the right way round.
  const Heading = chefMode ? Title3 : Subtitle2;
  const PageTitle = chefMode ? Title1 : Title2;

  // A scale belongs to the recipe you were reading, not to the pane.
  useEffect(() => setScaleIdx(1), [recipe?.id]);

  const ingredients = recipe?.ingredients ?? [];
  const directions = recipe?.directions ?? [];
  const numbers = useMemo(() => stepNumbers(directions), [directions]);

  if (!recipe) return <Placeholder styles={styles} />;

  const factor = SCALES[scaleIdx];
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
            <Button appearance="outline" icon={<Edit20Regular />} onClick={onEdit}>
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
                    {recipe.wantToMake ? "Remove from Want to Make" : "Add to Want to Make"}
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

          {times.length ? (
            <p className={styles.meta}>
              {times.map((t) => `${t.type} ${t.timeString}`.trim()).join(" · ")}
            </p>
          ) : null}

          {recipe.introduction ? <p className={styles.intro}>{recipe.introduction}</p> : null}

          {ingredients.length ? (
            <section className={styles.section}>
              <div className={styles.sectionHead}>
                <Heading as="h2">Ingredients</Heading>
                <div className={styles.scaler}>
                  <Tooltip content="Scale down" relationship="label">
                    <Button
                      appearance="subtle"
                      size="small"
                      icon={<Subtract20Regular />}
                      disabled={scaleIdx === 0}
                      onClick={() => setScaleIdx((i) => Math.max(0, i - 1))}
                    />
                  </Tooltip>
                  <output className={styles.scaleValue}>{factor}×</output>
                  <Tooltip content="Scale up" relationship="label">
                    <Button
                      appearance="subtle"
                      size="small"
                      icon={<Add20Regular />}
                      disabled={scaleIdx === SCALES.length - 1}
                      onClick={() => setScaleIdx((i) => Math.min(SCALES.length - 1, i + 1))}
                    />
                  </Tooltip>
                </div>
              </div>
              <ul className={styles.list}>
                {ingredients.map((row) => {
                  if (row.isHeading) {
                    return (
                      <li key={row.id} className={styles.heading}>
                        {row.text}
                      </li>
                    );
                  }
                  const { amount, rest } = scaleLine(row.text, factor);
                  return (
                    <li
                      key={row.id}
                      className={mergeClasses(styles.item, row.isMain && styles.main)}
                    >
                      <span className={styles.bullet} aria-hidden="true">
                        ·
                      </span>
                      <span>
                        {amount ? <span className={styles.scaled}>{amount}</span> : null}
                        {amount ? rest : row.text}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </section>
          ) : null}

          {directions.length ? (
            <section className={styles.section}>
              <Heading as="h2">Directions</Heading>
              {/* A <ul> with explicit numbers, not an <ol>: section headings are list items too,
                  and an <ol> counts them, so every heading shifts the numbering. */}
              <ul className={styles.list}>
                {directions.map((row, i) =>
                  row.isHeading ? (
                    <li key={row.id} className={styles.heading}>
                      {row.text}
                    </li>
                  ) : (
                    <li key={row.id} className={styles.item}>
                      <span className={styles.stepNo}>{numbers[i]}.</span>
                      <span>{row.text}</span>
                    </li>
                  ),
                )}
              </ul>
            </section>
          ) : null}

          {times.length ? (
            <section className={styles.section}>
              <Heading as="h2">Times</Heading>
              <dl className={styles.pairs}>
                {times.map((t) => (
                  <div key={t.id}>
                    <dt className={styles.pairTerm}>{t.type}</dt>
                    <dd style={{ margin: 0 }}>{t.timeString}</dd>
                  </div>
                ))}
              </dl>
            </section>
          ) : null}

          {(recipe.notes ?? []).length ? (
            <section className={styles.section}>
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
            <section className={styles.section}>
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

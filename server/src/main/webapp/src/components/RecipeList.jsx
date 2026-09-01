import {
  Button,
  Menu,
  MenuDivider,
  MenuItem,
  MenuItemRadio,
  MenuList,
  MenuPopover,
  MenuTrigger,
  RatingDisplay,
  SearchBox,
  Spinner,
  Subtitle1,
  Tooltip,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import {
  Add24Regular,
  ArrowSort24Regular,
  BookOpen24Regular,
  Heart20Filled,
  Heart20Regular,
  Link24Regular,
  MoreHorizontal24Regular,
} from "@fluentui/react-icons";

import { SORT_OPTIONS, rowSubtitle } from "../model";
import { thumbUrl } from "../api";

const useStyles = makeStyles({
  head: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalXS,
    padding: `${tokens.spacingVerticalM} ${tokens.spacingHorizontalM} ${tokens.spacingVerticalXS}`,
  },
  title: { flex: 1, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  search: { margin: `0 ${tokens.spacingHorizontalM} ${tokens.spacingVerticalS}`, width: "auto" },
  scroll: { flex: 1, overflow: "hidden auto", paddingBottom: tokens.spacingVerticalM },
  list: { listStyle: "none", margin: 0, padding: 0 },
  row: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalS,
    width: "100%",
    // Griffel sets no global box-sizing, so a 100%-wide row plus inline padding overflows the
    // pane and clips whatever sits at its end -- here, the favourite heart.
    boxSizing: "border-box",
    padding: `${tokens.spacingVerticalXS} ${tokens.spacingHorizontalM}`,
    border: "none",
    background: "none",
    textAlign: "start",
    cursor: "pointer",
    color: "inherit",
    borderRadius: tokens.borderRadiusMedium,
    ":hover": { backgroundColor: tokens.colorNeutralBackground1Hover },
  },
  rowSelected: {
    backgroundColor: tokens.colorNeutralBackground1Selected,
    ":hover": { backgroundColor: tokens.colorNeutralBackground1Selected },
  },
  thumb: {
    flex: "0 0 auto",
    width: "48px",
    height: "48px",
    borderRadius: tokens.borderRadiusMedium,
    objectFit: "cover",
    backgroundColor: tokens.colorNeutralBackground3,
  },
  thumbEmpty: {
    display: "grid",
    placeItems: "center",
    color: tokens.colorNeutralForeground3,
  },
  text: { flex: 1, minWidth: 0 },
  name: {
    fontWeight: tokens.fontWeightSemibold,
    fontSize: tokens.fontSizeBase300,
    overflow: "hidden",
    textOverflow: "ellipsis",
    whiteSpace: "nowrap",
  },
  sub: {
    color: tokens.colorNeutralForeground3,
    fontSize: tokens.fontSizeBase200,
    overflow: "hidden",
    textOverflow: "ellipsis",
    whiteSpace: "nowrap",
  },
  trailing: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalXXS,
    flex: "0 0 auto",
  },
  heart: {
    minWidth: "24px",
    color: tokens.colorPaletteRedForeground1,
  },
  empty: {
    padding: tokens.spacingHorizontalXXL,
    textAlign: "center",
    color: tokens.colorNeutralForeground3,
  },
  centre: { display: "grid", placeItems: "center", padding: tokens.spacingVerticalXXL },
});

function Row({ recipe, selected, onSelect, onToggleFavorite, styles }) {
  const thumb = thumbUrl(recipe);
  const sub = rowSubtitle(recipe);
  return (
    <li>
      <div
        className={mergeClasses(styles.row, selected && styles.rowSelected)}
        role="option"
        aria-selected={selected}
        tabIndex={0}
        onClick={() => onSelect(recipe.id)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onSelect(recipe.id);
          }
        }}
      >
        {thumb ? (
          <img className={styles.thumb} src={thumb} alt="" loading="lazy" />
        ) : (
          <div className={mergeClasses(styles.thumb, styles.thumbEmpty)} aria-hidden="true">
            <BookOpen24Regular />
          </div>
        )}

        <div className={styles.text}>
          <div className={styles.name} title={recipe.name || "Untitled"}>
            {recipe.name || "Untitled"}
          </div>
          {sub ? (
            <div className={styles.sub} title={sub}>
              {sub}
            </div>
          ) : null}
        </div>

        <div className={styles.trailing}>
          {recipe.rating ? (
            <RatingDisplay
              value={recipe.rating}
              max={5}
              size="small"
              color="marigold"
              valueText={null}
            />
          ) : null}
          <Tooltip
            content={recipe.isFavorite ? "Remove from favorites" : "Add to favorites"}
            relationship="label"
          >
            <Button
              appearance="transparent"
              size="small"
              className={mergeClasses(recipe.isFavorite && styles.heart)}
              icon={recipe.isFavorite ? <Heart20Filled /> : <Heart20Regular />}
              onClick={(e) => {
                e.stopPropagation();
                onToggleFavorite(recipe);
              }}
            />
          </Tooltip>
        </div>
      </div>
    </li>
  );
}

export default function RecipeList({
  title,
  rows,
  loading,
  query,
  onQuery,
  sortBy,
  sortAsc,
  onSort,
  selectedId,
  onSelect,
  onNew,
  onImport,
  onToggleFavorite,
}) {
  const styles = useStyles();
  const active = SORT_OPTIONS.find((o) => o.key === sortBy) ?? SORT_OPTIONS[0];

  return (
    <>
      <div className={styles.head}>
        <Subtitle1 className={styles.title}>{title}</Subtitle1>

        <Menu
          checkedValues={{ field: [sortBy], dir: [sortAsc ? "asc" : "desc"] }}
          onCheckedValueChange={(_, data) => {
            if (data.name === "field") onSort(data.checkedItems[0], sortAsc);
            else onSort(sortBy, data.checkedItems[0] === "asc");
          }}
        >
          <MenuTrigger disableButtonEnhancement>
            <Tooltip content="Sort" relationship="label">
              <Button appearance="subtle" icon={<ArrowSort24Regular />} />
            </Tooltip>
          </MenuTrigger>
          <MenuPopover>
            <MenuList>
              {SORT_OPTIONS.map((o) => (
                <MenuItemRadio key={o.key} name="field" value={o.key}>
                  {o.label}
                </MenuItemRadio>
              ))}
              <MenuDivider />
              {/* The direction's wording follows the field: "Ascending" on a date says nothing. */}
              <MenuItemRadio name="dir" value="asc">
                {active.asc}
              </MenuItemRadio>
              <MenuItemRadio name="dir" value="desc">
                {active.desc}
              </MenuItemRadio>
            </MenuList>
          </MenuPopover>
        </Menu>

        <Tooltip content="New recipe" relationship="label">
          <Button appearance="subtle" icon={<Add24Regular />} onClick={onNew} />
        </Tooltip>

        <Menu>
          <MenuTrigger disableButtonEnhancement>
            <Tooltip content="Other ways to add" relationship="label">
              <Button appearance="subtle" icon={<MoreHorizontal24Regular />} />
            </Tooltip>
          </MenuTrigger>
          <MenuPopover>
            <MenuList>
              <MenuItem icon={<Add24Regular />} onClick={onNew}>
                New recipe
              </MenuItem>
              <MenuItem icon={<Link24Regular />} onClick={onImport}>
                Import from web…
              </MenuItem>
            </MenuList>
          </MenuPopover>
        </Menu>
      </div>

      <SearchBox
        className={styles.search}
        placeholder="Search recipes"
        value={query}
        onChange={(_, data) => onQuery(data.value)}
      />

      <div className={styles.scroll}>
        {loading ? (
          <div className={styles.centre}>
            <Spinner size="small" label="Loading recipes…" />
          </div>
        ) : rows.length === 0 ? (
          <p className={styles.empty}>
            {query ? "No recipes match that search." : "Nothing here yet."}
          </p>
        ) : (
          <ul className={styles.list} role="listbox" aria-label="Recipes">
            {rows.map((r) => (
              <Row
                key={r.id}
                recipe={r}
                selected={r.id === selectedId}
                onSelect={onSelect}
                onToggleFavorite={onToggleFavorite}
                styles={styles}
              />
            ))}
          </ul>
        )}
      </div>
    </>
  );
}

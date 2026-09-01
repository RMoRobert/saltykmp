import {
  Button,
  List,
  ListItem,
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
  ArrowLeft24Regular,
  ArrowSort24Regular,
  Delete24Regular,
  Dismiss24Regular,
  SelectAllOn24Regular,
  TextBulletListSquare24Regular,
  Heart20Filled,
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
  /* Layout only. The press, hover, focus ring and cursor all come from ListItem. */
  row: {
    display: "flex",
    alignItems: "center",
    gap: tokens.spacingHorizontalS,
    boxSizing: "border-box",
    padding: `${tokens.spacingVerticalXS} ${tokens.spacingHorizontalM}`,
    cursor: "pointer",
  },
  /* The brand's tint, not a neutral grey: colorBrandBackground2 is Fluent's own token for a
     brand wash behind content, and it is the one place in this UI where Salty's blue does real
     work rather than decoration. */
  rowSelected: { backgroundColor: tokens.colorBrandBackground2 },
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
  heart: { color: tokens.colorPaletteRedForeground1 },
  empty: {
    padding: tokens.spacingHorizontalXXL,
    textAlign: "center",
    color: tokens.colorNeutralForeground3,
  },
  centre: { display: "grid", placeItems: "center", padding: tokens.spacingVerticalXXL },
});

/**
 * One recipe.
 *
 * Fluent's ListItem rather than a hand-rolled row: with `selectionMode="single"` on the List it
 * renders listbox/option semantics, carries the roving focus and arrow keys, and handles Enter and
 * Space -- all of which this file used to do by hand and less well. `checkmark={null}` drops the
 * selection tick, because the selected row is shown by its background, not by a mark.
 */
function Row({ recipe, selected, styles, sortBy, selectMode }) {
  const thumb = thumbUrl(recipe);
  const sub = rowSubtitle(recipe, sortBy);
  return (
    <ListItem
      value={recipe.id}
      // No checkbox outside select mode: a column of them on every row is a standing invitation to
      // a gesture almost nobody wants, and it costs the names the width it takes.
      checkmark={selectMode ? undefined : null}
      className={mergeClasses(styles.row, selected && styles.rowSelected)}
    >
      {thumb ? (
        <img className={styles.thumb} src={thumb} alt="" loading="lazy" />
      ) : (
        <div className={mergeClasses(styles.thumb, styles.thumbEmpty)} aria-hidden="true">
          <TextBulletListSquare24Regular />
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
        {/* Marked favourites only, and as a mark rather than a control. An outline on every row is
            a column of buttons asking to be pressed, when the row's job is to be chosen; the place
            to change it is the recipe itself. */}
        {recipe.isFavorite ? (
          <Heart20Filled className={styles.heart} aria-label="Favorite" />
        ) : null}
      </div>
    </ListItem>
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
  selectMode,
  onSelectMode,
  checkedIds,
  onCheckedChange,
  onDeleteChecked,
  onNew,
  onImport,
  onBack,
}) {
  const styles = useStyles();
  const active = SORT_OPTIONS.find((o) => o.key === sortBy) ?? SORT_OPTIONS[0];

  return (
    <>
      {selectMode ? (
        <div className={styles.head}>
          <Tooltip content="Done selecting" relationship="label">
            <Button appearance="subtle" icon={<Dismiss24Regular />} onClick={() => onSelectMode(false)} />
          </Tooltip>
          <Subtitle1 className={styles.title}>
            {checkedIds.length ? `${checkedIds.length} selected` : "Select recipes"}
          </Subtitle1>
          <Button
            appearance="subtle"
            icon={<Delete24Regular />}
            disabled={checkedIds.length === 0}
            onClick={() => onDeleteChecked(checkedIds)}
          >
            Delete
          </Button>
        </div>
      ) : (
        <div className={styles.head}>
          {/* Compact only: the rail is a drawer there, so the list needs its own way back to it. */}
          {onBack ? (
            <Tooltip content="Library" relationship="label">
              <Button appearance="subtle" icon={<ArrowLeft24Regular />} onClick={onBack} />
            </Tooltip>
          ) : null}
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
                <MenuDivider />
                <MenuItem icon={<SelectAllOn24Regular />} onClick={() => onSelectMode(true)}>
                  Select recipes…
                </MenuItem>
              </MenuList>
            </MenuPopover>
          </Menu>
        </div>
      )}

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
          <List
            className={styles.list}
            aria-label="Recipes"
            // One component, two jobs: normally the selection IS the recipe being read, and in
            // select mode it is the set a bulk action will act on.
            selectionMode={selectMode ? "multiselect" : "single"}
            selectedItems={selectMode ? checkedIds : selectedId ? [selectedId] : []}
            onSelectionChange={(_, data) => {
              const ids = [...data.selectedItems];
              if (selectMode) onCheckedChange(ids);
              // Clicking the open recipe again would otherwise deselect it and empty the pane.
              else if (ids.length) onSelect(ids[0]);
            }}
          >
            {rows.map((r) => (
              <Row
                key={r.id}
                recipe={r}
                selected={!selectMode && r.id === selectedId}
                styles={styles}
                sortBy={sortBy}
                selectMode={selectMode}
              />
            ))}
          </List>
        )}
      </div>
    </>
  );
}

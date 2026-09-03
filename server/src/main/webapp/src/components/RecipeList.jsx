import {
  Button,
  Hamburger,
  List,
  ListItem,
  Menu,
  MenuDivider,
  MenuGroup,
  MenuGroupHeader,
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
  Delete24Regular,
  Dismiss24Regular,
  SelectAllOn24Regular,
  TextBulletListSquare16Regular,
  TextBulletListSquare20Regular,
  TextBulletListSquare24Regular,
  Heart16Filled,
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
  /* The brand's tint, not a neutral grey: this is the one place in this UI where Salty's blue
     does real work rather than decoration. A theme key of our own rather than Fluent's
     colorBrandBackground2, which is a step too faint to read as a selection -- see theme.js. */
  rowSelected: { backgroundColor: "var(--colorSaltySelectedBackground)" },
  /* Both dense styles are as tall as their text rather than their thumbnail, so the padding is
     what is left to give and both spend it: 2px a side, taking "Small icons" to 40px and "List" to
     24px -- which is Fluent's own small row height, what a TreeItem or a MenuItem takes at
     `size="small"`, rather than a number picked to look like Explorer.

     The same `padding` shorthand as `row` rather than a `paddingBlock` longhand, so the merge is a
     plain override whichever way Griffel expands it. */
  rowSmall: { padding: `${tokens.spacingVerticalXXS} ${tokens.spacingHorizontalM}` },
  rowTiny: {
    padding: `${tokens.spacingVerticalXXS} ${tokens.spacingHorizontalM}`,
    gap: tokens.spacingHorizontalXS,
  },
  thumb: {
    flex: "0 0 auto",
    width: "48px",
    height: "48px",
    borderRadius: tokens.borderRadiusMedium,
    objectFit: "cover",
    backgroundColor: tokens.colorNeutralBackground3,
  },
  thumbSmall: { width: "32px", height: "32px" },
  /* Still the photo, shrunk, and not a uniform file-type glyph: at this size it is a smear of
     colour rather than a picture, but a smear of colour is the fastest thing on the row to
     recognise, and one placeholder rule across all three styles beats a special case here. */
  thumbTiny: { width: "20px", height: "20px", borderRadius: tokens.borderRadiusSmall },
  thumbEmpty: {
    display: "grid",
    placeItems: "center",
    color: tokens.colorNeutralForeground3,
  },
  text: { flex: 1, minWidth: 0 },
  /* Both lines state their own line-height rather than inheriting the body's. Two lines of text
     are what set a dense row's height, and a size without its matching leading is a row whose
     height depends on a rule set three stylesheets away. */
  name: {
    fontWeight: tokens.fontWeightSemibold,
    fontSize: tokens.fontSizeBase300,
    lineHeight: tokens.lineHeightBase300,
    overflow: "hidden",
    textOverflow: "ellipsis",
    whiteSpace: "nowrap",
  },
  /* Regular weight in "List". Semibold earns its place while there is a quieter second line under
     it to be distinguished from; with nothing but names on screen it is a page set in bold. */
  nameTiny: { fontWeight: tokens.fontWeightRegular },
  sub: {
    color: tokens.colorNeutralForeground3,
    fontSize: tokens.fontSizeBase200,
    lineHeight: tokens.lineHeightBase200,
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
 *
 * `listStyle` is the reader's density (see LIST_STYLES). It changes what the row *shows*, not what
 * it is: every style is the same ListItem with the same value, semantics and selection, so nothing
 * above this function has to know which one is in force.
 */
function Row({ recipe, selected, styles, sortBy, selectMode, listStyle }) {
  const tiny = listStyle === "list";
  const small = listStyle === "smallIcons";
  const thumb = thumbUrl(recipe);
  // One line has room for a name and its marks, and not for a sentence about the recipe as well.
  const sub = tiny ? null : rowSubtitle(recipe, sortBy);
  const Placeholder = tiny
    ? TextBulletListSquare16Regular
    : small
      ? TextBulletListSquare20Regular
      : TextBulletListSquare24Regular;
  const thumbClass = mergeClasses(
    styles.thumb,
    small && styles.thumbSmall,
    tiny && styles.thumbTiny,
  );
  return (
    <ListItem
      value={recipe.id}
      // No checkbox outside select mode: a column of them on every row is a standing invitation to
      // a gesture almost nobody wants, and it costs the names the width it takes.
      checkmark={selectMode ? undefined : null}
      className={mergeClasses(
        styles.row,
        small && styles.rowSmall,
        tiny && styles.rowTiny,
        selected && styles.rowSelected,
      )}
    >
      {thumb ? (
        <img className={thumbClass} src={thumb} alt="" loading="lazy" />
      ) : (
        <div className={mergeClasses(thumbClass, styles.thumbEmpty)} aria-hidden="true">
          <Placeholder />
        </div>
      )}

      <div className={styles.text}>
        <div
          className={mergeClasses(styles.name, tiny && styles.nameTiny)}
          title={recipe.name || "Untitled"}
        >
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
            // Five stars beside a name on a 24px row is most of the width the name wanted, so the
            // densest style takes Fluent's own compact form -- one star and the number -- rather
            // than dropping the rating, which is a thing people scan this column for.
            compact={tiny}
            valueText={tiny ? undefined : null}
          />
        ) : null}
        {/* Marked favourites only, and as a mark rather than a control. An outline on every row is
            a column of buttons asking to be pressed, when the row's job is to be chosen; the place
            to change it is the recipe itself. */}
        {recipe.isFavorite ? (
          tiny ? (
            <Heart16Filled className={styles.heart} aria-label="Favorite" />
          ) : (
            <Heart20Filled className={styles.heart} aria-label="Favorite" />
          )
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
  onShowRail,
  listStyle,
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
          {/* Whenever the rail is not on screen -- closed at full width, or a drawer when compact --
              this is the Hamburger that brings it back, as in Fluent's own NavDrawer pattern. */}
          {onShowRail ? (
            <Tooltip content="Show library" relationship="label">
              <Hamburger onClick={onShowRail} />
            </Tooltip>
          ) : null}
          <Subtitle1 className={styles.title}>{title}</Subtitle1>

          <Tooltip content="New recipe" relationship="label">
            <Button appearance="subtle" icon={<Add24Regular />} onClick={onNew} />
          </Tooltip>

          {/* One overflow menu, not two. Sorting used to have a button of its own, but a toolbar of
              three anonymous glyphs over a list is harder to read than a single "..." holding
              everything the list itself can do -- and the ordering is a thing you set once and
              forget, not one you reach for on every visit. */}
          <Menu
            checkedValues={{ field: [sortBy], dir: [sortAsc ? "asc" : "desc"] }}
            onCheckedValueChange={(_, data) => {
              if (data.name === "field") onSort(data.checkedItems[0], sortAsc);
              else onSort(sortBy, data.checkedItems[0] === "asc");
            }}
          >
            <MenuTrigger disableButtonEnhancement>
              <Tooltip content="List options" relationship="label">
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
                {/* Flat rather than a "Sort by" submenu: six radios is a short enough list to show
                    outright, and a submenu would put the ordering behind a hover. */}
                <MenuDivider />
                <MenuGroup>
                  <MenuGroupHeader>Sort by</MenuGroupHeader>
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
                </MenuGroup>
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
                listStyle={listStyle}
              />
            ))}
          </List>
        )}
      </div>
    </>
  );
}

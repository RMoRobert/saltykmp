import {
  Button,
  Hamburger,
  Menu,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  NavCategory,
  NavCategoryItem,
  NavDivider,
  NavDrawer,
  NavDrawerBody,
  NavDrawerFooter,
  NavDrawerHeader,
  NavItem,
  NavSectionHeader,
  NavSubItem,
  NavSubItemGroup,
  Subtitle2,
  Tooltip,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  ArrowExit24Regular,
  Bookmark24Regular,
  BookOpen24Regular,
  Cart24Regular,
  Food24Regular,
  Grid24Regular,
  Heart24Regular,
  Options24Regular,
  Person24Regular,
  People24Regular,
  Settings24Regular,
  Tag24Regular,
  TableSimple24Regular,
} from "@fluentui/react-icons";

import { SALTY } from "../api";

const useStyles = makeStyles({
  empty: {
    display: "block",
    padding: `${tokens.spacingVerticalXS} ${tokens.spacingHorizontalXXL}`,
    color: tokens.colorNeutralForeground3,
    fontSize: tokens.fontSizeBase200,
  },
  footButton: { justifyContent: "flex-start" },
  /* The hamburger had this row to itself and the rest of it was empty, so the app's identity
     goes beside it rather than costing a row in the body. 24px to match the nav glyphs below:
     the mark is a full-bleed coloured plate, and at Fluent's default 32 it was the loudest thing
     in a rail of thin line icons. 192px source so it stays crisp -- the same file the tab favicon
     and an installed shortcut use, so it is almost always already in cache. */
  brand: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalS },
  appIcon: { width: "24px", height: "24px", display: "block" },
});

/*
 * Every destination in the rail is one string, which is what NavDrawer's `selectedValue` and
 * `onNavItemSelect` trade in. Plain filters are their kind; classifiers and lists carry an id.
 */
const CLASSIFIER_GROUPS = [
  { kind: "category", label: "Categories", icon: <Grid24Regular /> },
  { kind: "course", label: "Courses", icon: <Food24Regular /> },
  { kind: "tag", label: "Tags", icon: <Tag24Regular /> },
];
const LISTS_INDEX = "lists";

function selectedValueFor({ section, filter }) {
  // The rail has one destination for the lists, whichever list is open: the lists themselves are
  // the middle column's, not the rail's, so there is nothing else here to select.
  if (section === "lists") return LISTS_INDEX;
  return filter.id ? `${filter.kind}:${filter.id}` : filter.kind;
}

/** The group a value lives in, so a collapsed group still shows that something inside is chosen. */
function categoryFor(value) {
  const [head] = value.split(":");
  return CLASSIFIER_GROUPS.some((g) => g.kind === head) ? head : undefined;
}

/**
 * The library rail: Fluent's NavDrawer.
 *
 * Inline at full width, where it is a column of the layout; an overlay when compact, where it is
 * a drawer over the one pane there is room for. The same tree either way -- only `type` changes,
 * and the hamburger that closes it is Fluent's, in the drawer's own header.
 * Selection is NavDrawer's own (`selectedValue`), which is what a hand-styled Tree was standing in
 * for before.
 */
export default function NavRail({
  type,
  open,
  onOpenChange,
  onUsers,
  onShoppingLists,
  section,
  filter,
  onFilter,
  courses,
  categories,
  tags,
  onManageLibrary,
  onPreferences,
}) {
  const styles = useStyles();

  // Closed is gone, as in Fluent's own NavDrawer pattern: the control that reopens it is a
  // Hamburger *outside* the drawer, which here is the one at the head of the list column. WinUI's
  // NavigationView has a compact icon-rail mode; the web NavDrawer has no such thing, and an
  // icon strip built by hand to imitate it would be the one part of the rail that was not Fluent's.
  if (type === "inline" && !open) return null;

  const groups = { category: categories, course: courses, tag: tags };
  const selected = selectedValueFor({ section, filter });

  const select = (value) => {
    const [head, id] = value.split(":");
    if (head === "classifiers") return onManageLibrary();
    if (head === "settings") return onPreferences();
    if (head === LISTS_INDEX) return onShoppingLists();
    if (id) {
      const name = groups[head].find((it) => it.id === id)?.name;
      return onFilter(head, id, name || "Untitled");
    }
    return onFilter(head, null, { all: "All recipes", favorites: "Favorites", wantToMake: "Want to make" }[head]);
  };

  return (
    <NavDrawer
      type={type}
      open={open}
      separator
      onOpenChange={(_, d) => onOpenChange(d.open)}
      selectedValue={selected}
      selectedCategoryValue={categoryFor(selected)}
      onNavItemSelect={(_, d) => select(d.value)}
      defaultOpenCategories={["category"]}
      aria-label="Library"
    >
      <NavDrawerHeader>
        <div className={styles.brand}>
          <Tooltip content="Hide library" relationship="label">
            <Hamburger onClick={() => onOpenChange(false)} />
          </Tooltip>
          <img src="/static/icon-192.png" alt="" className={styles.appIcon} />
          <Subtitle2>Salty</Subtitle2>
        </div>
      </NavDrawerHeader>

      <NavDrawerBody>
        {/* Two sections, because the rail holds two unrelated things: ways of looking at the
            recipes, and the shopping lists. Without the headings "All lists" reads as one more
            recipe filter, which is the one thing it is not. */}
        <NavSectionHeader>Recipes</NavSectionHeader>

        <NavItem value="all" icon={<BookOpen24Regular />}>
          All recipes
        </NavItem>
        <NavItem value="favorites" icon={<Heart24Regular />}>
          Favorites
        </NavItem>
        <NavItem value="wantToMake" icon={<Bookmark24Regular />}>
          Want to make
        </NavItem>

        {CLASSIFIER_GROUPS.map((g) => (
          <NavCategory key={g.kind} value={g.kind}>
            <NavCategoryItem icon={g.icon}>{g.label}</NavCategoryItem>
            <NavSubItemGroup>
              {groups[g.kind].length === 0 ? (
                <span className={styles.empty}>None yet</span>
              ) : (
                groups[g.kind].map((it) => (
                  <NavSubItem key={it.id} value={`${g.kind}:${it.id}`}>
                    {it.name || "Untitled"}
                  </NavSubItem>
                ))
              )}
            </NavSubItemGroup>
          </NavCategory>
        ))}

        {/* One row, not a group of the lists themselves: unlike recipes, the lists are not
            classified, so the rail has nothing to branch on and the middle column is where they
            belong -- which is also the shape the SwiftUI app keeps, three panes throughout. */}
        <NavSectionHeader>Shopping lists</NavSectionHeader>
        <NavItem value={LISTS_INDEX} icon={<Cart24Regular />}>
          All lists
        </NavItem>
      </NavDrawerBody>

      <NavDrawerFooter>
        {/* Down here with Settings, for the reason the Compose app gives in its own drawer:
            editing the classifiers is rare and app-level, so it is one row rather than an "Edit…"
            hung off each of the three groups above. The recipe list's toolbar is for actions on
            the list. Label matches the Compose app's. Neither is ever `selectedValue`: they open
            dialogs rather than changing what the panes show. */}
        <NavItem value="classifiers" icon={<Options24Regular />}>
          Edit classifiers
        </NavItem>
        <NavItem value="settings" icon={<Settings24Regular />}>
          Settings
        </NavItem>
        <NavDivider />

        {/* The account's own actions, kept apart from the library's: signing out and administering
            users are not things you do to a recipe collection. */}
        <Menu>
          <MenuTrigger disableButtonEnhancement>
            <Button appearance="subtle" icon={<Person24Regular />} className={styles.footButton}>
              {SALTY.username || "Account"}
            </Button>
          </MenuTrigger>
          <MenuPopover>
            <MenuList>
              {SALTY.isAdmin ? (
                <MenuItem icon={<People24Regular />} onClick={onUsers}>
                  Manage users…
                </MenuItem>
              ) : null}
              <MenuItem
                icon={<TableSimple24Regular />}
                onClick={() => {
                  window.location.href = "/classic";
                }}
              >
                Classic view
              </MenuItem>
              <MenuItem
                icon={<ArrowExit24Regular />}
                onClick={() => {
                  window.location.href = "/logout";
                }}
              >
                Log out
              </MenuItem>
            </MenuList>
          </MenuPopover>
        </Menu>
      </NavDrawerFooter>
    </NavDrawer>
  );
}

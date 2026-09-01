import {
  Button,
  Divider,
  Menu,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Tooltip,
  Tree,
  TreeItem,
  TreeItemLayout,
  makeStyles,
  mergeClasses,
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
  Navigation24Regular,
  Options24Regular,
  Person24Regular,
  People24Regular,
  Settings24Regular,
  Tag24Regular,
  TableSimple24Regular,
} from "@fluentui/react-icons";

import { SALTY } from "../api";

const useStyles = makeStyles({
  rail: {
    height: "100vh",
    overflow: "hidden auto",
    display: "flex",
    flexDirection: "column",
    backgroundColor: tokens.colorNeutralBackground2,
    borderRight: `1px solid ${tokens.colorNeutralStroke2}`,
  },
  head: { padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalS}` },
  tree: { paddingInline: tokens.spacingHorizontalXS },
  // Pushes Organize/Settings to the bottom of the rail, as the Uno app does.
  spacer: { flex: 1, minHeight: tokens.spacingVerticalXL },
  foot: {
    padding: tokens.spacingHorizontalXS,
    paddingBottom: tokens.spacingVerticalM,
    display: "flex",
    flexDirection: "column",
    alignItems: "stretch",
    gap: "2px",
    borderTop: `1px solid ${tokens.colorNeutralStroke2}`,
  },
  footButton: { justifyContent: "flex-start" },
  /* The rail's own selected state. Fluent's Tree selection renders radio/checkbox indicators,
     which is a different idea from "this is the filter you are looking at". */
  selected: {
    backgroundColor: tokens.colorBrandBackground2,
    fontWeight: tokens.fontWeightSemibold,
  },
  empty: {
    padding: `${tokens.spacingVerticalXS} ${tokens.spacingHorizontalXL}`,
    color: tokens.colorNeutralForeground3,
    fontSize: tokens.fontSizeBase200,
  },
  collapsed: {
    display: "flex",
    flexDirection: "column",
    alignItems: "center",
    gap: "2px",
    paddingTop: tokens.spacingVerticalS,
  },
});

/** A classifier group: a branch whose children filter the list. */
function ClassifierGroup({ styles, kind, label, icon, items, filter, onFilter }) {
  return (
    <TreeItem itemType="branch" value={kind}>
      <TreeItemLayout iconBefore={icon}>{label}</TreeItemLayout>
      <Tree>
        {items.length === 0 ? (
          <TreeItem itemType="leaf" value={`${kind}:none`} disabled>
            <TreeItemLayout>
              <span className={styles.empty}>None yet</span>
            </TreeItemLayout>
          </TreeItem>
        ) : (
          items.map((it) => (
            <TreeItem
              key={it.id}
              itemType="leaf"
              value={`${kind}:${it.id}`}
              onClick={() => onFilter(kind, it.id, it.name || "Untitled")}
            >
              <TreeItemLayout
                className={mergeClasses(
                  filter.kind === kind && filter.id === it.id && styles.selected,
                )}
              >
                {it.name || "Untitled"}
              </TreeItemLayout>
            </TreeItem>
          ))
        )}
      </Tree>
    </TreeItem>
  );
}

export default function NavRail({
  open,
  onToggle,
  onUsers,
  onShoppingLists,
  section,
  filter,
  onFilter,
  courses,
  categories,
  tags,
  shoppingLists,
  selectedListId,
  onSelectList,
  onManageLibrary,
  onPreferences,
}) {
  const styles = useStyles();

  if (!open) {
    return (
      <nav className={mergeClasses(styles.rail, styles.collapsed)} aria-label="Library">
        <Tooltip content="Show library" relationship="label">
          <Button appearance="subtle" icon={<Navigation24Regular />} onClick={onToggle} />
        </Tooltip>
        <Tooltip content="All Recipes" relationship="label">
          <Button
            appearance="subtle"
            icon={<BookOpen24Regular />}
            onClick={() => onFilter("all", null, "All Recipes")}
          />
        </Tooltip>
        <Tooltip content="Favorites" relationship="label">
          <Button
            appearance="subtle"
            icon={<Heart24Regular />}
            onClick={() => onFilter("favorites", null, "Favorites")}
          />
        </Tooltip>
        <Tooltip content="Want to Make" relationship="label">
          <Button
            appearance="subtle"
            icon={<Bookmark24Regular />}
            onClick={() => onFilter("wantToMake", null, "Want to Make")}
          />
        </Tooltip>
      </nav>
    );
  }

  return (
    <nav className={styles.rail} aria-label="Library">
      <div className={styles.head}>
        <Tooltip content="Hide library" relationship="label">
          <Button appearance="subtle" icon={<Navigation24Regular />} onClick={onToggle} />
        </Tooltip>
      </div>

      <Tree
        aria-label="Library"
        className={styles.tree}
        defaultOpenItems={["categories"]}
        size="medium"
      >
        <TreeItem itemType="leaf" value="all" onClick={() => onFilter("all", null, "All Recipes")}>
          <TreeItemLayout
            iconBefore={<BookOpen24Regular />}
            className={mergeClasses(
              section === "recipes" && filter.kind === "all" && styles.selected,
            )}
          >
            All Recipes
          </TreeItemLayout>
        </TreeItem>

        <TreeItem
          itemType="leaf"
          value="favorites"
          onClick={() => onFilter("favorites", null, "Favorites")}
        >
          <TreeItemLayout
            iconBefore={<Heart24Regular />}
            className={mergeClasses(
              section === "recipes" && filter.kind === "favorites" && styles.selected,
            )}
          >
            Favorites
          </TreeItemLayout>
        </TreeItem>

        <TreeItem
          itemType="leaf"
          value="wantToMake"
          onClick={() => onFilter("wantToMake", null, "Want to Make")}
        >
          <TreeItemLayout
            iconBefore={<Bookmark24Regular />}
            className={mergeClasses(
              section === "recipes" && filter.kind === "wantToMake" && styles.selected,
            )}
          >
            Want to Make
          </TreeItemLayout>
        </TreeItem>

        <ClassifierGroup
          styles={styles}
          kind="category"
          label="Categories"
          icon={<Grid24Regular />}
          items={categories}
          filter={filter}
          onFilter={onFilter}
        />
        <ClassifierGroup
          styles={styles}
          kind="course"
          label="Courses"
          icon={<Food24Regular />}
          items={courses}
          filter={filter}
          onFilter={onFilter}
        />
        <ClassifierGroup
          styles={styles}
          kind="tag"
          label="Tags"
          icon={<Tag24Regular />}
          items={tags}
          filter={filter}
          onFilter={onFilter}
        />

        {/* Clicking the group shows the lists index, as well as expanding it. Expanding alone
            left no route to the index pane -- you could only reach a list you could already name. */}
        <TreeItem itemType="branch" value="lists" onClick={onShoppingLists}>
          <TreeItemLayout
            iconBefore={<Cart24Regular />}
            className={mergeClasses(section === "lists" && !selectedListId && styles.selected)}
          >
            Shopping Lists
          </TreeItemLayout>
          <Tree>
            {shoppingLists.length === 0 ? (
              <TreeItem itemType="leaf" value="lists:none" disabled>
                <TreeItemLayout>
                  <span className={styles.empty}>None yet</span>
                </TreeItemLayout>
              </TreeItem>
            ) : (
              shoppingLists.map((l) => (
                <TreeItem
                  key={l.id}
                  itemType="leaf"
                  value={`list:${l.id}`}
                  onClick={() => onSelectList(l.id)}
                >
                  <TreeItemLayout
                    className={mergeClasses(
                      section === "lists" && selectedListId === l.id && styles.selected,
                    )}
                  >
                    {l.name || "Untitled list"}
                  </TreeItemLayout>
                </TreeItem>
              ))
            )}
          </Tree>
        </TreeItem>
      </Tree>

      <div className={styles.spacer} />

      <div className={styles.foot}>
        {/* Down here with Settings, for the reason the Compose app gives in its own drawer:
            editing the classifiers is rare and app-level, so it is one row rather than an "Edit…"
            hung off each of the three groups above. The recipe list's toolbar is for actions on
            the list. Label matches the Compose app's. */}
        <Button
          appearance="subtle"
          icon={<Options24Regular />}
          className={styles.footButton}
          onClick={onManageLibrary}
        >
          Edit Classifiers
        </Button>
        <Button
          appearance="subtle"
          icon={<Settings24Regular />}
          className={styles.footButton}
          onClick={onPreferences}
        >
          Settings
        </Button>
        <Divider />

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
      </div>
    </nav>
  );
}

import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Button,
  Dialog,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Tooltip,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import { Dismiss24Regular } from "@fluentui/react-icons";

import { formatDay, formatMoment, relativeDate } from "../model";

const useStyles = makeStyles({
  surface: { maxWidth: "30rem" },
  /* The name, not "Get info": which recipe this is about is the first thing to say, and the menu
     item the reader just used already said what the panel is. Long names wrap rather than
     truncate -- there is nothing else competing for the line. */
  title: { overflowWrap: "anywhere" },
  body: { display: "grid", gap: tokens.spacingVerticalL },
  kv: {
    display: "grid",
    gridTemplateColumns: "auto 1fr",
    columnGap: tokens.spacingHorizontalXL,
    rowGap: tokens.spacingVerticalS,
    alignItems: "baseline",
  },
  key: { color: tokens.colorNeutralForeground3 },
  sub: { color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200 },
  id: { fontFamily: tokens.fontFamilyMonospace, fontSize: tokens.fontSizeBase200, overflowWrap: "anywhere" },
  note: { color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200, margin: 0 },
  syncKv: { marginTop: tokens.spacingVerticalM },
});

/** One labeled fact, with the "3 days ago" reading under it when there is a date to place. */
function Fact({ styles, label, value, hint }) {
  return (
    <>
      <span className={styles.key}>{label}</span>
      <span>
        {value}
        {hint ? <div className={styles.sub}>{hint}</div> : null}
      </span>
    </>
  );
}

/**
 * Get Info: what is known about a recipe, read only.
 *
 * The Swift app's panel of the same name, and the same three dates it shows -- added, modified and
 * last prepared. Called "Get info" rather than "Info…" because that is the name of the command on the
 * platform it is borrowed from, where it deliberately carries no ellipsis; the case is Fluent's
 * rather than that platform's, which the app applies to every label it shows.
 *
 * The last-prepared date is *shown* here and *set* from the Last prepared menu next door, which is where the CMP and
 * Swift apps put it too. It was set here at first, when this panel was the only place a browser
 * could answer the date at all; a panel that reports facts is the wrong place to keep the one
 * control that changes one of them.
 *
 * Not addressable by URL hash, unlike Settings and the rest. Those are app-level and survive a
 * reload; a panel is about a recipe, and one restored over a list nobody navigated to is a panel
 * with nothing behind it.
 *
 * `detail` is how much of that recipe is in hand, because this opens on a list row as well as on the
 * recipe being read, and a row is a summary. Every date above the fold is on the summary; the one
 * field that is not is the last-prepared sync stamp, so that line -- and only that line -- says so
 * while the fetch is in flight (`loading`) or if it never landed (`unavailable`). See App's openInfo.
 */
export default function RecipeInfoDialog({ recipe, detail = "full", onClose }) {
  const styles = useStyles();

  if (!recipe) return null;

  const madeOn = formatDay(recipe.lastPrepared);
  // A photo with no stamp is possible -- a body upload can set the filename without the image ever
  // going through the endpoint that dates it -- so "no photo" has to mean the filename, not the date.
  const photo = !recipe.imageFilename
    ? "No photo"
    : formatMoment(recipe.lastModifiedImageDate) || "Unknown";
  const preparedStamp = formatMoment(recipe.lastModifiedPreparedDate);

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface className={styles.surface}>
        <DialogBody>
          <DialogTitle
            className={styles.title}
            action={
              <Tooltip content="Close" relationship="label">
                <Button appearance="subtle" icon={<Dismiss24Regular />} onClick={onClose} />
              </Tooltip>
            }
          >
            {recipe.name || "Untitled"}
          </DialogTitle>
          <DialogContent>
            <div className={styles.body}>
              <div className={styles.kv}>
                <Fact
                  styles={styles}
                  label="Added"
                  value={formatMoment(recipe.createdDate) || "Unknown"}
                  hint={relativeDate(recipe.createdDate)}
                />
                <Fact
                  styles={styles}
                  label="Modified"
                  value={formatMoment(recipe.lastModifiedDate) || "Unknown"}
                  hint={relativeDate(recipe.lastModifiedDate)}
                />
                {/* A day, not a moment: "last prepared" is a date someone picked off a calendar, and
                    printing the time it happens to be stored at would invent a precision the
                    field has never had. */}
                <Fact
                  styles={styles}
                  label="Last prepared"
                  value={madeOn || "Not set"}
                  hint={madeOn ? relativeDate(recipe.lastPrepared) : null}
                />
              </div>

              {/* Rarely wanted, so collapsed -- the same treatment About gets at the foot of
                  Settings. It is here at all because the three separate stamps are what illustrate
                  that, e.g., setting a last-prepared date doesn't affected Last Modified. */}
              <Accordion collapsible>
                <AccordionItem value="sync">
                  <AccordionHeader>Sync details</AccordionHeader>
                  <AccordionPanel>
                    <p className={styles.note}>
                      Salty syncs recipe data (ingredients, directions, notes, etc.), the photo, and its last-prepared
                      date as three separate entities, syncing only one(s) changed since last sync.
                    </p>
                    <div className={mergeClasses(styles.kv, styles.syncKv)}>
                      <Fact
                        styles={styles}
                        label="Recipe"
                        value={formatMoment(recipe.lastModifiedDate) || "Unknown"}
                      />
                      <Fact styles={styles} label="Photo" value={photo} />
                      <Fact
                        styles={styles}
                        label='"Last prepared" modification'
                        value={
                          detail === "loading"
                            ? "Loading…"
                            : detail === "unavailable"
                              ? "Unknown"
                              : preparedStamp || "Never set"
                        }
                      />
                    </div>
                  </AccordionPanel>
                </AccordionItem>
              </Accordion>
            </div>
          </DialogContent>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

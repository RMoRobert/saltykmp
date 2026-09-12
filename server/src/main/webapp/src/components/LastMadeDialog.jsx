import { useState } from "react";
import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Input,
  makeStyles,
  tokens,
} from "@fluentui/react-components";

import { dayValueToPrepared, preparedToDayValue, todayValue } from "../model";

const useStyles = makeStyles({
  date: { maxWidth: "12rem" },
  note: { color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200 },
});

/**
 * "Set as date…" from the Last prepared menu: one day, picked and saved -- or cleared.
 *
 * A date field rather than Fluent's DatePicker, which lives in a separate compat package: `type=
 * "date"` is the platform's own picker, so it is the wheel on a phone and the calendar the browser
 * already draws elsewhere, and it is typeable for anyone who would rather not hunt through months.
 * The field is a Fluent `Input`, so the box around it is the app's.
 *
 * Clear is here rather than on the menu, and it empties the field rather than writing straight
 * through: in a dialog that commits on Save, a button that commits on its own and closes would be
 * the one place a mis-click could not be taken back. Emptied, Cancel still undoes it, and Save
 * writes the clear -- `dayValueToPrepared` reads an empty field as "no date", which is the same
 * null the menu used to send.
 *
 * "Set to today" stays a menu item: it is an answer nobody has to compose, so a dialog around it
 * would be a step for nothing. See [RecipeDetail].
 */
export default function LastMadeDialog({ recipe, onClose, onSetPrepared }) {
  const styles = useStyles();
  const today = todayValue();
  const stored = preparedToDayValue(recipe?.lastPrepared);
  const [value, setValue] = useState(stored);

  if (!recipe) return null;

  // A recipe cannot have been prepared in the future. `max` says so to the picker, but a date field can
  // still be typed into, so the refusal has to be here as well -- and what was typed is kept,
  // because silently rewriting someone's date is worse than telling them.
  const future = value > today;
  const save = () => {
    onSetPrepared(dayValueToPrepared(value));
    onClose();
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface>
        <DialogBody>
          <DialogTitle>Last prepared</DialogTitle>
          <DialogContent>
            <Field
              label="Date prepared"
              validationState={future ? "error" : "none"}
              validationMessage={
                future ? "Cannot set last prepared date to date in future." : null
              }
            >
              <Input
                autoFocus
                className={styles.date}
                type="date"
                max={today}
                value={value}
                onChange={(_, d) => setValue(d.value)}
              />
            </Field>
            {/* Which recipe: the menu that opened this named the recipe only by being attached to
                it, and this dialog is not. */}
            <p className={styles.note}>
              {value
                ? `This sets the last prepared date on ${recipe.name || "Untitled"} to the selected date.`
                : `This clears the last prepared date on ${recipe.name || "Untitled"}.`}
            </p>
          </DialogContent>
          {/* Left of the pair, not among it: Clear answers the field, while Cancel and Save answer
              the dialog. Fluent's own grid slot for that, rather than a margin. */}
          <DialogActions position="start">
            <Button disabled={!value} onClick={() => setValue("")}>
              Clear
            </Button>
          </DialogActions>
          <DialogActions position="end">
            <Button appearance="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button appearance="primary" disabled={future || value === stored} onClick={save}>
              Save
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

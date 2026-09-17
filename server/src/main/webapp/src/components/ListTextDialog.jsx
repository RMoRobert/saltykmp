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
  Textarea,
  makeStyles,
  tokens,
} from "@fluentui/react-components";

import { cleanUpListText } from "../model";

const useStyles = makeStyles({
  /* The same bounds as the other wide dialogs: Fluent clamps the surface on a phone, and there the
     box is what should have the width, not the margin around it. */
  wide: {
    maxWidth: "44rem",
    "@media (max-width: 480px)": { padding: tokens.spacingHorizontalL },
  },
  /* Monospaced because the format is whitespace-significant, and proportional type hides the blank
     lines that carry the meaning. Tall from the start, and past Fluent's 260px cap once dragged. */
  text: {
    fontFamily: tokens.fontFamilyMonospace,
    height: "min(24rem, 55vh)",
    maxHeight: "none",
  },
});

/**
 * "Edit as text": a whole ingredient or direction list as one block, the Swift and Compose apps' bulk
 * editors. Row-at-a-time entry is hopeless for a recipe pasted from somewhere else, which is the case
 * this is for.
 *
 * The grammar (model.js's port of RecipeListText) cannot be guessed, so the help is under the box
 * rather than behind a button; a dialog this wide has room for it.
 *
 * Local state only. Apply replaces the editor's rows and Cancel leaves them alone -- and Apply is
 * not Save, which is why it is not called that: the recipe is still only written by the editor's own
 * Save. Unchanged text applies nothing, since re-parsing mints new row ids and the editor would
 * count that as an edit.
 *
 * A click outside does not close it. That is Fluent's default for a modal, and here it would throw
 * away a pasted recipe on a stray click; Escape and Cancel are both deliberate.
 */
export default function ListTextDialog({ title, help, initialText, stripNumbering, onApply, onClose }) {
  const styles = useStyles();
  const [text, setText] = useState(initialText);

  return (
    <Dialog
      open
      onOpenChange={(_, d) => {
        if (!d.open && d.type !== "backdropClick") onClose();
      }}
    >
      <DialogSurface className={styles.wide}>
        <DialogBody>
          <DialogTitle>{title}</DialogTitle>
          <DialogContent>
            <Field hint={help}>
              <Textarea
                autoFocus
                aria-label={title}
                resize="vertical"
                textarea={{ className: styles.text }}
                value={text}
                onChange={(_, d) => setText(d.value)}
              />
            </Field>
          </DialogContent>
          <DialogActions position="start">
            <Button
              disabled={!text.trim()}
              onClick={() => setText(cleanUpListText(text, stripNumbering))}
            >
              Clean up
            </Button>
          </DialogActions>
          <DialogActions position="end">
            <Button appearance="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button
              appearance="primary"
              onClick={() => {
                if (text !== initialText) onApply(text);
                onClose();
              }}
            >
              Apply
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

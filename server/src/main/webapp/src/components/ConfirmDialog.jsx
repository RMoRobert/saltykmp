import { useEffect, useState } from "react";
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
} from "@fluentui/react-components";

/**
 * The app's stand-in for `window.confirm` and `window.prompt`.
 *
 * Not a style preference. A native dialog blocks the event loop, cannot be themed, renders outside
 * Fluent's dark mode entirely, and on a destructive action gives you "OK" where the button should
 * say what it is about to do. This is one component so that every confirmation in the app words and
 * behaves the same way.
 *
 * Driven by a request object rather than by a boolean and a pile of props:
 *
 *   ask({ title, body, confirmLabel, destructive, prompt, initialValue, onConfirm })
 *
 * `prompt` turns it into the prompt() case -- a labelled field whose value is handed to onConfirm.
 */
export default function ConfirmDialog({ request, onClose }) {
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);

  // A fresh request starts from its own initial value, not from what the last one was left holding.
  useEffect(() => setValue(request?.initialValue ?? ""), [request]);

  if (!request) return null;

  const { title, body, confirmLabel, prompt, placeholder, onConfirm } = request;
  const blocked = prompt && !value.trim();

  const confirm = async () => {
    setBusy(true);
    try {
      await onConfirm?.(prompt ? value.trim() : undefined);
      onClose();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface>
        <DialogBody>
          <DialogTitle>{title}</DialogTitle>
          <DialogContent>
            {body ? <p style={{ marginTop: 0 }}>{body}</p> : null}
            {prompt ? (
              <Field label={prompt}>
                <Input
                  autoFocus
                  value={value}
                  placeholder={placeholder}
                  onChange={(_, d) => setValue(d.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !blocked) confirm();
                  }}
                />
              </Field>
            ) : null}
          </DialogContent>
          <DialogActions>
            <Button appearance="secondary" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            {/* Fluent has no destructive appearance and this app does not invent one: the
                wording carries the weight, which is why every caller passes a verb rather than
                accepting a default "OK". */}
            <Button appearance="primary" disabled={busy || blocked} onClick={confirm}>
              {confirmLabel || "OK"}
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

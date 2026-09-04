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
} from "@fluentui/react-components";

import PasswordInput from "./PasswordInput";

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
 *   ask({ title, body, confirmLabel, prompt, initialValue, secret, onConfirm })
 *
 * `prompt` turns it into the prompt() case -- a labelled field whose value is handed to onConfirm.
 * `secret` makes that field a password field, masked with a reveal toggle.
 *
 * `onCancel` is for the callers that have something to put back rather than merely nothing to do --
 * the one that matters is Back out of an unsaved editor, where the history has already moved and the
 * address has to be returned to the editor's if the reader decides to stay. It runs for every way of
 * refusing: the button, Escape, and a click outside.
 *
 * Errors are handled here, once. `onConfirm` may throw or reject; the dialog then stays open with
 * the field intact and hands the error to `onError`, so a failed request can be retried rather than
 * vanishing into an unhandled rejection. Callers therefore need no try/catch of their own.
 *
 * The caller mounts this with a `key` that changes per request, which is what starts the field from
 * that request's `initialValue` rather than from whatever the last prompt was left holding.
 */
export default function ConfirmDialog({ request, onClose, onError }) {
  const [value, setValue] = useState(() => request?.initialValue ?? "");
  const [busy, setBusy] = useState(false);

  if (!request) return null;

  const { title, body, confirmLabel, prompt, placeholder, secret, onConfirm, onCancel } = request;
  const blocked = prompt && !value.trim();
  const PromptInput = secret ? PasswordInput : Input;

  const dismiss = () => {
    onCancel?.();
    onClose();
  };

  const confirm = async () => {
    setBusy(true);
    try {
      await onConfirm?.(prompt ? value.trim() : undefined);
      onClose();
    } catch (e) {
      onError?.(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && dismiss()}>
      <DialogSurface>
        <DialogBody>
          <DialogTitle>{title}</DialogTitle>
          <DialogContent>
            {body ? <p style={{ marginTop: 0 }}>{body}</p> : null}
            {prompt ? (
              <Field label={prompt}>
                <PromptInput
                  autoFocus
                  value={value}
                  placeholder={placeholder}
                  onChange={(_, d) => setValue(d.value)}
                  onKeyDown={(e) => {
                    // `busy` as well as `blocked`: the button is disabled while the request is in
                    // flight but the key was not, so a second Enter made a second list, a second
                    // tag, a second password reset.
                    if (e.key === "Enter" && !blocked && !busy) confirm();
                  }}
                />
              </Field>
            ) : null}
          </DialogContent>
          <DialogActions>
            <Button appearance="secondary" onClick={dismiss} disabled={busy}>
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

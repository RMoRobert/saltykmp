import { useState } from "react";
import { Button, Input, Tooltip } from "@fluentui/react-components";
import { Eye20Regular, EyeOff20Regular } from "@fluentui/react-icons";

/**
 * A password field with a reveal toggle, in Input's own `contentAfter` slot.
 *
 * Every password the app takes goes through this -- your own, a new user's, an admin's reset for
 * someone else -- so they are all masked, and none of them makes a typo invisible: the eye toggles
 * the field between masked and shown. It starts masked every time, since a field that remembered
 * "shown" across dialogs would surprise the next person to open one.
 */
export default function PasswordInput(props) {
  const [shown, setShown] = useState(false);
  return (
    <Input
      {...props}
      type={shown ? "text" : "password"}
      contentAfter={
        <Tooltip content={shown ? "Hide password" : "Show password"} relationship="label">
          <Button
            appearance="transparent"
            size="small"
            icon={shown ? <EyeOff20Regular /> : <Eye20Regular />}
            onClick={() => setShown((s) => !s)}
          />
        </Tooltip>
      }
    />
  );
}

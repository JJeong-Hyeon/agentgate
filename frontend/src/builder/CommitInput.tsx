import { useEffect, useState, type InputHTMLAttributes } from "react";

type Props = Omit<InputHTMLAttributes<HTMLInputElement | HTMLTextAreaElement>, "value" | "onChange"> & {
  value: string;
  onCommit: (value: string) => void;
  multiline?: boolean;
  rows?: number;
};

/**
 * Text field that edits a local draft and reports it on blur or Enter, for values that are parsed
 * (lists, ids) and would fight the user if re-rendered on every keystroke.
 */
export function CommitInput({ value, onCommit, multiline, rows, ...rest }: Props) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);

  const commit = () => {
    if (draft !== value) onCommit(draft);
  };
  const common = {
    ...rest,
    value: draft,
    onChange: (e: { target: { value: string } }) => setDraft(e.target.value),
    onBlur: commit,
  };
  if (multiline) return <textarea {...common} rows={rows} />;
  return (
    <input
      {...common}
      onKeyDown={(e) => {
        if (e.key === "Enter") commit();
      }}
    />
  );
}

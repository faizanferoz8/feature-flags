interface Props {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  disabled?: boolean;
}

/** An on/off control. The label is for assistive technology; the visible text sits beside it. */
export function Switch({ checked, onChange, label, disabled }: Props) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className="switch"
      onClick={() => onChange(!checked)}
    >
      <span className="switch-thumb" />
    </button>
  );
}

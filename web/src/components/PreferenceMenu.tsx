import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Check, ChevronDown } from 'lucide-react';

/** Header-sized pill button with a small radio menu, shared by the language and appearance pickers. */
export function PreferenceMenu<T extends string>({ options, value, onChange, icon, triggerText, label, optionLabel, optionHint, className }: {
  options: readonly T[];
  value: T;
  onChange: (next: T) => void;
  icon: ReactNode;
  triggerText: string;
  label: string;
  optionLabel: (option: T) => string;
  optionHint?: (option: T) => string | undefined;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    itemRefs.current[Math.max(0, options.indexOf(value))]?.focus();
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open, options, value]);

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current?.focus();
  };
  const choose = (next: T) => {
    onChange(next);
    close(true);
  };
  const onMenuKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = itemRefs.current.filter((item): item is HTMLButtonElement => item !== null);
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    const move = (to: number) => { event.preventDefault(); items[(to + items.length) % items.length]?.focus(); };
    if (event.key === 'ArrowDown') move(index + 1);
    else if (event.key === 'ArrowUp') move(index - 1);
    else if (event.key === 'Home') move(0);
    else if (event.key === 'End') move(items.length - 1);
    else if (event.key === 'Escape') { event.preventDefault(); close(true); }
    else if (event.key === 'Tab') close(false);
  };

  return (
    <div className={className ? `lang-menu ${className}` : 'lang-menu'} ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="lang-menu__trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={`${label}: ${optionLabel(value)}`}
        onClick={() => setOpen(current => !current)}
        onKeyDown={event => { if (event.key === 'ArrowDown' && !open) { event.preventDefault(); setOpen(true); } }}
      >
        {icon}
        <span className="lang-menu__code">{triggerText}</span>
        <ChevronDown size={14} aria-hidden="true" className="lang-menu__chevron" />
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label={label} className="lang-menu__list" onKeyDown={onMenuKeyDown}>
          {options.map((option, index) => {
            const checked = option === value;
            const hint = optionHint?.(option);
            return (
              <button
                key={option}
                ref={element => { itemRefs.current[index] = element; }}
                type="button"
                role="menuitemradio"
                aria-checked={checked}
                tabIndex={-1}
                className="lang-menu__item"
                onClick={() => choose(option)}
              >
                <span className="lang-menu__text">
                  <span>{optionLabel(option)}</span>
                  {hint && <span className="lang-menu__hint">{hint}</span>}
                </span>
                {checked ? <Check size={15} aria-hidden="true" className="lang-menu__check" /> : <span className="lang-menu__check" aria-hidden="true" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

import React, { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { useAppTranslation } from '../i18n/LocaleProvider';
import { emptyAttributeRow, type AttributeRow } from './attributes';

interface AttributeEditorProps {
  rows: AttributeRow[];
  onChange: (rows: AttributeRow[]) => void;
  disabled?: boolean;
  addLabel: string;
}

/**
 * Attribute rows styled like the read-only attribute tiles: the name is a small
 * label above the value, both editable in place.
 */
export const AttributeEditor: React.FC<AttributeEditorProps> = ({ rows, onChange, disabled, addLabel }) => {
  const { t } = useAppTranslation();
  const [focusId, setFocusId] = useState<string | null>(null);

  const update = (id: string, patch: Partial<Pick<AttributeRow, 'key' | 'value'>>) =>
    onChange(rows.map(row => (row.id === id ? { ...row, ...patch } : row)));
  const remove = (id: string) => onChange(rows.filter(row => row.id !== id));
  const add = () => {
    const row = emptyAttributeRow();
    setFocusId(row.id);
    onChange([...rows, row]);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
      {rows.length > 0 && (
        <div className="attribute-grid">
          {rows.map(row => (
            <div key={row.id} className="attribute-tile">
              <div style={{ flex: 1, minWidth: 0 }}>
                <input
                  type="text"
                  className="attribute-key-input"
                  aria-label={t('newClientModal.attributePlaceholder')}
                  placeholder={t('newClientModal.attributePlaceholder')}
                  value={row.key}
                  disabled={disabled}
                  autoFocus={row.id === focusId}
                  onChange={(e) => update(row.id, { key: e.target.value })}
                />
                <input
                  type="text"
                  className="attribute-value-input"
                  aria-label={t('newClientModal.valuePlaceholder')}
                  placeholder={t('newClientModal.valuePlaceholder')}
                  value={row.value}
                  disabled={disabled}
                  onChange={(e) => update(row.id, { value: e.target.value })}
                />
              </div>
              <button
                type="button"
                aria-label={t('common.remove')}
                disabled={disabled}
                onClick={() => remove(row.id)}
                style={{ background: 'none', border: 'none', color: '#f87171', cursor: 'pointer', padding: '4px', flexShrink: 0 }}
              >
                <Trash2 size={15} />
              </button>
            </div>
          ))}
        </div>
      )}
      <button type="button" className="btn-secondary" style={{ alignSelf: 'flex-start', padding: '6px 12px', fontSize: '12px' }} disabled={disabled} onClick={add}>
        <Plus size={14} /> {addLabel}
      </button>
    </div>
  );
};

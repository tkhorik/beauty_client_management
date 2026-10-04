export type AttributeValue = string | number | boolean;

/**
 * One editable attribute. `original` is the value read from the server, kept so
 * an untouched number or boolean is saved back with its type instead of being
 * turned into a string by an unrelated edit.
 */
export interface AttributeRow {
  id: string;
  key: string;
  value: string;
  original?: AttributeValue;
}

let nextRowId = 0;
const newRowId = () => `attr-${++nextRowId}`;

const display = (value: AttributeValue) => (typeof value === 'object' ? JSON.stringify(value) : String(value));

export const emptyAttributeRow = (key = '', value = ''): AttributeRow => ({ id: newRowId(), key, value });

export const toAttributeRows = (fields: Record<string, AttributeValue> | undefined): AttributeRow[] =>
  Object.entries(fields ?? {}).map(([key, value]) => ({ id: newRowId(), key, value: display(value), original: value }));

/** Returns the first trimmed key that appears more than once, ignoring blank keys. */
export const duplicateAttributeKey = (rows: AttributeRow[]): string | null => {
  const seen = new Set<string>();
  for (const row of rows) {
    const key = row.key.trim();
    if (!key) continue;
    if (seen.has(key)) return key;
    seen.add(key);
  }
  return null;
};

/** Blank keys are dropped; unchanged values keep their original JSON type. */
export const toAttributeRecord = (rows: AttributeRow[]): Record<string, AttributeValue> => {
  const record: Record<string, AttributeValue> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (!key) continue;
    record[key] = row.original !== undefined && row.value === display(row.original) ? row.original : row.value.trim();
  }
  return record;
};

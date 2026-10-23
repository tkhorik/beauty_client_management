/// <reference types="node" />
import { expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const css = readFileSync('src/index.css', 'utf8');
const lightBlocks = [...css.matchAll(/:root \{([^}]+)\}/g)].map(match => match[1]).join('\n');
const darkBlock = css.match(/:root\[data-theme='dark'\] \{([^}]+)\}/)![1];
function palette(text: string) {
  const tokens = Object.fromEntries([...text.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(match => [match[1], match[2].trim()]));
  function resolve(name: string): string {
    const value = tokens[name];
    if (!value) throw new Error(`Missing color ${name}`);
    return value.startsWith('var(') ? resolve(value.slice(4, -1)) : value;
  }
  return resolve;
}
function luminance(hex: string) {
  let color = hex.replace('#', '');
  if (color.length === 3) color = [...color].map(c => c + c).join('');
  const rgb = [0, 2, 4].map(offset => parseInt(color.slice(offset, offset + 2), 16) / 255)
    .map(v => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
}
function contrast(foreground: string, background: string) {
  const [a, b] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (a + 0.05) / (b + 0.05);
}
for (const [name, block] of [['light', lightBlocks], ['dark', lightBlocks + darkBlock]]) {
  it(`${name} semantic text and control colors meet AA on their surfaces`, () => {
    const color = palette(block);
    const textPairs = [
      ['--text-main', '--bg-dark'], ['--text-main', '--bg-card'], ['--text-muted', '--bg-card'],
      ['--text-muted', '--bg-elevated'], ['--rose-gold-primary', '--bg-card'],
      ['--danger', '--danger-bg'], ['--emerald-accent', '--success-bg'],
      ['--on-ink', '--ink'], ['--on-peach', '--peach'], ['--brand-panel-text', '--brand-panel'],
    ];
    for (const [fg, bg] of textPairs) expect(contrast(color(fg), color(bg)), `${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
    for (const bg of ['--bg-dark', '--bg-card', '--bg-elevated']) {
      expect(contrast(color('--control-border'), color(bg)), `control on ${bg}`).toBeGreaterThanOrEqual(3);
    }
  });
}
it('dark badge and status foreground/background pairs meet AA', () => {
  const color = palette(lightBlocks + darkBlock);
  for (const role of ['success', 'peach', 'purple', 'info']) {
    expect(contrast(color(`--theme-${role}-text`), color(`--theme-${role}-bg`)), role).toBeGreaterThanOrEqual(4.5);
  }
});

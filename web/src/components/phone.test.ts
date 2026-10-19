import { expect, it } from 'vitest';
import { formatPhone, maskedLength, phoneIssue } from './phone';

it('masks US and Russian numbers as they are typed', () => {
  expect(formatPhone('15550000000')).toBe('+1 (555) 000-0000');
  expect(formatPhone('79991234567')).toBe('+7 (999) 123-45-67');
  expect(formatPhone('1555')).toBe('+1 (555');
  expect(formatPhone('1555000')).toBe('+1 (555) 000');
});

it('reads a leading 8 as +7 and a missing country code as +1', () => {
  expect(formatPhone('89991234567')).toBe('+7 (999) 123-45-67');
  expect(formatPhone('5550000000')).toBe('+1 (555) 000-0000');
});

it('never types past the last digit of the mask', () => {
  expect(formatPhone('+1 (555) 000-00009999')).toBe('+1 (555) 000-0000');
  expect(maskedLength('+1 (555)')).toBe(17);
  expect(maskedLength('+7 (999)')).toBe(18);
  expect(maskedLength('')).toBe(18);
});

it('requires a complete number', () => {
  expect(phoneIssue('')).toBe('PHONE_REQUIRED');
  expect(phoneIssue('+1 (555) 000-000')).toBe('PHONE_INVALID');
  expect(phoneIssue('+1 (555) 000-0000')).toBeNull();
  expect(phoneIssue('+7 (999) 123-45-67')).toBeNull();
});

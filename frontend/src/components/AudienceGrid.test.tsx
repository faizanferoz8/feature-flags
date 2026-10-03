import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Evaluation, EvaluationContext } from '../types';
import { AudienceGrid } from './AudienceGrid';

const audience: EvaluationContext[] = [
  { key: 'user-001', attributes: { country: 'PK', plan: 'pro' } },
  { key: 'user-002', attributes: { country: 'US', plan: 'free' } },
  { key: 'user-003', attributes: { country: 'DE', plan: 'free' } },
];

const evaluations: Evaluation[] = [
  { flagKey: 'f', value: true, reason: 'RULE_MATCH', ruleId: 'second', bucket: null },
  { flagKey: 'f', value: true, reason: 'FALLTHROUGH', ruleId: null, bucket: 1234 },
  { flagKey: 'f', value: false, reason: 'FALLTHROUGH', ruleId: null, bucket: 8800 },
];

afterEach(cleanup);

describe('AudienceGrid', () => {
  it('counts the users the flag is on for', () => {
    render(<AudienceGrid audience={audience} evaluations={evaluations} ruleIds={['first', 'second']} />);

    expect(screen.getByText('2')).toBeTruthy();
    expect(screen.getByLabelText('user-003: off').getAttribute('data-state')).toBe('off');
  });

  it('names the rule that decided, by its position in the list', () => {
    render(<AudienceGrid audience={audience} evaluations={evaluations} ruleIds={['first', 'second']} />);

    expect(screen.getByText('On: matched rule 2.')).toBeTruthy();
  });

  it('explains a rollout decision with the position of the selected user', () => {
    render(<AudienceGrid audience={audience} evaluations={evaluations} ruleIds={[]} />);

    fireEvent.click(screen.getByLabelText('user-003: off'));

    expect(screen.getByText(/Off: no rule matched.*position 88\.00 of 100/)).toBeTruthy();
  });

  it('shows squares as pending, not off, while the answer is unknown', () => {
    render(<AudienceGrid audience={audience} evaluations={undefined} ruleIds={[]} />);

    expect(screen.getByLabelText('user-001: pending')).toBeTruthy();
    expect(screen.getByText('–')).toBeTruthy();
  });
});

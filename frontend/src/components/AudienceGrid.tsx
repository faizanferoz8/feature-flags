import { useState } from 'react';
import type { Evaluation, EvaluationContext } from '../types';

interface Props {
  audience: EvaluationContext[];
  evaluations: Evaluation[] | undefined;
  /** Rule ids in order, to name the rule that decided a square. */
  ruleIds: string[];
}

function explain(evaluation: Evaluation, ruleIds: string[]): string {
  const outcome = evaluation.value ? 'On' : 'Off';
  switch (evaluation.reason) {
    case 'OFF':
      return 'Off: the flag is switched off in this environment.';
    case 'RULE_MATCH':
      return `${outcome}: matched rule ${ruleIds.indexOf(evaluation.ruleId ?? '') + 1}${bucketNote(evaluation)}.`;
    case 'FALLTHROUGH':
      return `${outcome}: no rule matched, so the default rollout decided${bucketNote(evaluation)}.`;
    default:
      return outcome;
  }
}

function bucketNote(evaluation: Evaluation): string {
  return evaluation.bucket === null ? '' : ` (position ${(evaluation.bucket / 100).toFixed(2)} of 100)`;
}

/**
 * One square per sample user, lit when the draft configuration turns the flag on for
 * them. A user keeps the same square, so dragging a rollout up shows squares lighting
 * and staying lit: that is the stickiness the hashing guarantees, made visible.
 */
export function AudienceGrid({ audience, evaluations, ruleIds }: Props) {
  const [selected, setSelected] = useState(0);
  const on = evaluations?.filter((evaluation) => evaluation.value).length ?? 0;
  const chosen = audience[selected];
  const chosenEvaluation = evaluations?.[selected];

  return (
    <section className="audience" aria-label="Who gets this flag">
      <header>
        <h2>Who gets it</h2>
        <p className="audience-count">
          <strong>{evaluations ? on : '–'}</strong> of {audience.length} sample users
        </p>
      </header>
      <div className="audience-grid" role="list">
        {audience.map((user, index) => {
          const evaluation = evaluations?.[index];
          const state = evaluation ? (evaluation.value ? 'on' : 'off') : 'pending';
          return (
            <button
              key={user.key}
              type="button"
              role="listitem"
              className="cell"
              data-state={state}
              data-reason={evaluation?.reason}
              aria-pressed={index === selected}
              aria-label={`${user.key}: ${state}`}
              onMouseEnter={() => setSelected(index)}
              onFocus={() => setSelected(index)}
              onClick={() => setSelected(index)}
            />
          );
        })}
      </div>
      <div className="audience-detail">
        <p>
          <code>{chosen.key}</code> {chosen.attributes.country}, {chosen.attributes.plan} plan
        </p>
        <p>{chosenEvaluation ? explain(chosenEvaluation, ruleIds) : 'Working out who this reaches…'}</p>
      </div>
      <p className="hint">
        Sample users are fixed. Raising a rollout only ever adds squares: nobody who has the feature loses it.
      </p>
    </section>
  );
}

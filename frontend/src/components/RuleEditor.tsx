import { SAMPLE_ATTRIBUTES } from '../audience';
import type { Condition, Operator, Rule } from '../types';

const OPERATORS: { value: Operator; label: string }[] = [
  { value: 'IN', label: 'is one of' },
  { value: 'NOT_IN', label: 'is not one of' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'STARTS_WITH', label: 'starts with' },
  { value: 'ENDS_WITH', label: 'ends with' },
  { value: 'GREATER_THAN', label: 'is greater than' },
  { value: 'LESS_THAN', label: 'is less than' },
];

interface Props {
  rules: Rule[];
  onChange: (rules: Rule[]) => void;
  disabled: boolean;
}

export function newRule(): Rule {
  return {
    id: crypto.randomUUID().slice(0, 8),
    description: '',
    conditions: [{ attribute: 'country', operator: 'IN', values: [] }],
    rolloutPercentage: 100,
  };
}

const parseValues = (text: string) =>
  text
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean);

export function RuleEditor({ rules, onChange, disabled }: Props) {
  const update = (index: number, patch: Partial<Rule>) =>
    onChange(rules.map((rule, i) => (i === index ? { ...rule, ...patch } : rule)));

  const updateCondition = (ruleIndex: number, conditionIndex: number, patch: Partial<Condition>) =>
    update(ruleIndex, {
      conditions: rules[ruleIndex].conditions.map((condition, i) =>
        i === conditionIndex ? { ...condition, ...patch } : condition,
      ),
    });

  const move = (index: number, by: number) => {
    const next = [...rules];
    const [rule] = next.splice(index, 1);
    next.splice(index + by, 0, rule);
    onChange(next);
  };

  return (
    <ol className="rules">
      {rules.map((rule, ruleIndex) => (
        <li key={rule.id} className="rule">
          <div className="rule-head">
            <span className="rule-number">Rule {ruleIndex + 1}</span>
            <div className="rule-actions">
              <button type="button" className="link" disabled={disabled || ruleIndex === 0} onClick={() => move(ruleIndex, -1)}>
                Move up
              </button>
              <button
                type="button"
                className="link"
                disabled={disabled || ruleIndex === rules.length - 1}
                onClick={() => move(ruleIndex, 1)}
              >
                Move down
              </button>
              <button
                type="button"
                className="link danger"
                disabled={disabled}
                onClick={() => onChange(rules.filter((_, i) => i !== ruleIndex))}
              >
                Remove
              </button>
            </div>
          </div>

          {rule.conditions.map((condition, conditionIndex) => (
            <div key={conditionIndex} className="condition">
              <span className="joiner">{conditionIndex === 0 ? 'If' : 'and'}</span>
              <input
                aria-label="Attribute"
                list="known-attributes"
                value={condition.attribute}
                disabled={disabled}
                onChange={(event) => updateCondition(ruleIndex, conditionIndex, { attribute: event.target.value })}
              />
              <select
                aria-label="Comparison"
                value={condition.operator}
                disabled={disabled}
                onChange={(event) =>
                  updateCondition(ruleIndex, conditionIndex, { operator: event.target.value as Operator })
                }
              >
                {OPERATORS.map((operator) => (
                  <option key={operator.value} value={operator.value}>
                    {operator.label}
                  </option>
                ))}
              </select>
              <input
                aria-label="Values, separated by commas"
                placeholder="PK, AE"
                className="values"
                defaultValue={condition.values.join(', ')}
                disabled={disabled}
                onChange={(event) =>
                  updateCondition(ruleIndex, conditionIndex, { values: parseValues(event.target.value) })
                }
              />
              {rule.conditions.length > 1 && (
                <button
                  type="button"
                  className="link"
                  disabled={disabled}
                  aria-label="Remove condition"
                  onClick={() =>
                    update(ruleIndex, { conditions: rule.conditions.filter((_, i) => i !== conditionIndex) })
                  }
                >
                  ✕
                </button>
              )}
            </div>
          ))}

          <div className="rule-foot">
            <button
              type="button"
              className="link"
              disabled={disabled}
              onClick={() =>
                update(ruleIndex, {
                  conditions: [...rule.conditions, { attribute: 'plan', operator: 'IN', values: [] }],
                })
              }
            >
              Add a condition
            </button>
            <label className="serve">
              then turn it on for
              <input
                type="number"
                min={0}
                max={100}
                value={rule.rolloutPercentage}
                disabled={disabled}
                onChange={(event) =>
                  update(ruleIndex, { rolloutPercentage: Math.max(0, Math.min(100, Number(event.target.value) || 0)) })
                }
              />
              % of them
            </label>
          </div>
        </li>
      ))}
      <datalist id="known-attributes">
        {SAMPLE_ATTRIBUTES.map((attribute) => (
          <option key={attribute} value={attribute} />
        ))}
      </datalist>
    </ol>
  );
}

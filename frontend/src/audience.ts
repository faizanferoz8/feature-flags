import type { EvaluationContext } from './types';

const COUNTRIES = ['PK', 'US', 'DE', 'AE', 'GB'];
const PLANS = ['free', 'free', 'pro', 'enterprise'];

/**
 * One hundred made-up users with a spread of countries and plans. The server evaluates
 * a draft configuration against them, so the grid shows who a change would reach
 * before it is saved. They are fixed, so the same user is always the same square.
 */
export const SAMPLE_AUDIENCE: EvaluationContext[] = Array.from({ length: 100 }, (_, i) => ({
  key: `user-${String(i + 1).padStart(3, '0')}`,
  attributes: {
    country: COUNTRIES[(i * 7) % COUNTRIES.length],
    plan: PLANS[(i * 3) % PLANS.length],
    email: `user${i + 1}@${i % 9 === 0 ? 'acme.test' : 'example.com'}`,
  },
}));

export const SAMPLE_ATTRIBUTES = ['key', 'country', 'plan', 'email'];

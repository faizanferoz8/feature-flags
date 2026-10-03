export type Role = 'VIEWER' | 'EDITOR' | 'ADMIN';

export type Operator =
  | 'IN'
  | 'NOT_IN'
  | 'CONTAINS'
  | 'STARTS_WITH'
  | 'ENDS_WITH'
  | 'GREATER_THAN'
  | 'LESS_THAN';

export interface Condition {
  attribute: string;
  operator: Operator;
  values: string[];
}

export interface Rule {
  id: string;
  description: string;
  conditions: Condition[];
  rolloutPercentage: number;
}

export interface FlagConfig {
  environment: string;
  environmentName: string;
  enabled: boolean;
  rules: Rule[];
  fallthroughPercentage: number;
  version: number;
  updatedAt: string;
}

export interface Flag {
  key: string;
  name: string;
  description: string;
  createdAt: string;
  environments: FlagConfig[];
}

export interface User {
  id: string;
  email: string;
  role: Role;
  createdAt: string;
}

export interface Session {
  user: User;
  organization: string;
  environments: { key: string; name: string }[];
}

export interface EvaluationContext {
  key: string;
  attributes: Record<string, string>;
}

export interface Evaluation {
  flagKey: string;
  value: boolean;
  reason: 'OFF' | 'RULE_MATCH' | 'FALLTHROUGH' | 'FLAG_NOT_FOUND';
  ruleId: string | null;
  bucket: number | null;
}

export interface ApiKey {
  id: string;
  name: string;
  environment: string;
  prefix: string;
  createdBy: string;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
}

export interface AuditEntry {
  id: number;
  actor: string;
  action: string;
  target: string;
  environment: string | null;
  before: unknown;
  after: unknown;
  at: string;
}

export interface AuditPage {
  items: AuditEntry[];
  page: number;
  size: number;
  total: number;
}

/** The editable part of a flag's configuration in one environment. */
export interface Draft {
  enabled: boolean;
  rules: Rule[];
  fallthroughPercentage: number;
}

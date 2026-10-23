export type Role = 'PAYER' | 'MERCHANT' | 'OPS';
export type Outcome = 'APPROVED' | 'DECLINED' | 'PENDING' | 'TIMEOUT';
export type PaymentStatus = 'SUCCEEDED' | 'FAILED' | 'PENDING' | 'EXPIRED';
export interface User {
  id: string; name: string; alias: string; phone: string | null; role: Role;
  status: 'ACTIVE' | 'PENDING' | 'REJECTED'; balance: number; reserved: number;
  completed_at: string | null; campaign: string; created_at: string; onboarding_step?: number;
}
export interface Contact { id: string; name: string; alias: string; role: Role }
export interface Ledger { account: string; amount: number; phase: string; created_at: string }
export interface SupportCase {
  id: string; payment_id: string; category: string; note: string; status: string; resolution: string | null;
}
export interface Payment {
  id: string; payer_id: string; destination_id: string; amount: number; channel: 'ALIAS' | 'QR';
  scenario: Outcome; status: PaymentStatus; reason: string; created_at: string; updated_at: string;
  reversed: boolean; destination_name: string; destination_alias: string; payer_name: string;
  retry_allowed: boolean; dispute_allowed: boolean; investigation_allowed: boolean; duplicate?: boolean;
  ledger?: Ledger[]; cases?: SupportCase[];
}
export interface PaymentInput { destination: string; amount: number; channel: 'ALIAS' | 'QR'; scenario: Outcome; qrId: string | null }
export interface Qr { id: string; merchant_id: string; name: string; alias: string; amount: number; expires_at: string; image?: string }
export interface Mismatch { id: string; payment_id: string; kind: string; status: string }
export interface ReconciliationRow { payment: Payment; provider: {status: string; amount: number} | null; issues: Mismatch[]; matched: boolean; duplicate_requests: number }
export interface Reconciliation { date: string; matched: number; unmatched: number; resolved: number; total: number; rows: ReconciliationRow[] }
export interface Metric {
  month: string; checkout_started: number; checkout_succeeded: number; dropoff_percent: number | null;
  mau: number; new_active_users: number; spend_cents: number; cac_cents: number | null; onboarding_median_seconds: number | null;
  attribution: {campaign: string; new_active_users: number; spend_cents: number | null; cac_cents: number | null}[];
}
export interface Metrics { months: Metric[]; notice: string; acquisition: { month: string; campaign: string; cents: number }[] }
export interface Audit { id: string; action: string; actor: string; reference_id: string | null; request_id: string; correlation_id: string; detail: string; created_at: string }
export interface AgentReply { message: string; intent: string; trace_id: string; mode: string; human_review_required: boolean }

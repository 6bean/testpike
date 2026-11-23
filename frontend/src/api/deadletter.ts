import { request } from './request'
import type { PageResult } from './request'




export const DEAD_LETTER_STATUSES = ['pending', 'replayed', 'voided'] as const
export type DeadLetterStatus = (typeof DEAD_LETTER_STATUSES)[number]


export const DEAD_LETTER_STALE_HOURS = 24


export function isStaleDeadLetterRow(row: { createdAtUtc: string | null }, now: number = Date.now()): boolean {
  if (!row.createdAtUtc) return false
  const created = Date.parse(row.createdAtUtc)
  if (Number.isNaN(created)) return false
  return now - created > DEAD_LETTER_STALE_HOURS * 3600_000
}


export const DEAD_LETTER_FAILURE_CLASSES = [
  'ENTITY_GONE',
  'PROVIDER_TRANSIENT',
  'CONFIG_MISSING',
  'LLM_TIMEOUT',
  'DESERIALIZATION_FAIL',
  'UNKNOWN',
] as const
export type DeadLetterFailureClass = (typeof DEAD_LETTER_FAILURE_CLASSES)[number]


export const DEAD_LETTER_REPLAY_FORBIDDEN_CLASS: DeadLetterFailureClass = 'ENTITY_GONE'


export interface DeadLetterQueueCount {
  queueName: string
  pendingCount: number
}


export interface DeadLetterClassCount {
  failureClass: string
  count: number
}


export interface DeadLetterLedgerRow {
  id: number
  messageFingerprint: string
  queueName: string
  origExchange: string | null
  origRoutingKey: string | null
  payloadJson: string | null
  failureClass: string
  failureReason: string | null
  replayRound: number
  status: string
  voidReason: string | null
  operator: string | null
  createdAtUtc: string | null
  updatedAtUtc: string | null
}


export interface DeadLetterBrowseQuery {
  queueName?: string
  failureClass?: string
  status?: string
  page?: number
  size?: number
}


export function listQueueBacklog(status?: string): Promise<DeadLetterQueueCount[]> {
  return request<DeadLetterQueueCount[]>({
    url: '/mq-dead-letter/queue-backlog',
    method: 'get',
    params: status ? { status } : {},
  })
}


export function listClassDistribution(queueName?: string, status?: string): Promise<DeadLetterClassCount[]> {
  return request<DeadLetterClassCount[]>({
    url: '/mq-dead-letter/class-distribution',
    method: 'get',
    params: { ...(queueName ? { queueName } : {}), ...(status ? { status } : {}) },
  })
}


export function browseDeadLetters(query: DeadLetterBrowseQuery): Promise<PageResult<DeadLetterLedgerRow>> {
  return request<PageResult<DeadLetterLedgerRow>>({
    url: '/mq-dead-letter/browse',
    method: 'get',
    params: query,
  })
}


export interface DeadLetterReplayReceipt {
  ledgerId: number
  messageFingerprint: string
  deadLetterQueueName: string
  repostedExchange: string
  repostedRoutingKey: string
  replayRound: number
}


export function replayDeadLetterOne(ledgerId: number, confirmReexecute: boolean): Promise<DeadLetterReplayReceipt> {
  return request<DeadLetterReplayReceipt>({
    url: '/mq-dead-letter/replay/one',
    method: 'post',
    params: { ledgerId, confirmReexecute },
  })
}


export function replayDeadLetterByClass(queueName: string, failureClass: string, confirmReexecute: boolean): Promise<number> {
  return request<number>({
    url: '/mq-dead-letter/replay/by-class',
    method: 'post',
    params: { queueName, failureClass, confirmReexecute },
  })
}


export function replayDeadLetterQueue(queueName: string, confirmReexecute: boolean): Promise<number> {
  return request<number>({
    url: '/mq-dead-letter/replay/queue',
    method: 'post',
    params: { queueName, confirmReexecute },
  })
}


export function voidDeadLetter(ledgerId: number, reason: string): Promise<boolean> {
  return request<boolean>({
    url: '/mq-dead-letter/void',
    method: 'post',
    params: { ledgerId, reason },
  })
}
